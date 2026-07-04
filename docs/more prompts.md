Prompt T-1 — Dual Node Identity
Plan Mode
In core/, replace the single NodeIdentity with a dual-transport
identity model. One username, two permanent node IDs.

─── NodeIdentity.kt (replace existing) ───────────────────────
@Serializable
data class NodeIdentity(
  val username: String,          // "Keshav" — human name, mesh-unique
  val btNodeId: String,          // "BT-A3F2-9D12" — permanent, BT mesh
  val wifiNodeId: String,        // "WIFI-C8B1-4E33" — permanent, WiFi mesh
  val avatarSeed: String,        // deterministic color seed
  val createdAt: Long,
  val usernameClaimed: Boolean   // false until mesh confirms unique
)

─── NodeIdGenerator.kt ────────────────────────────────────────
object NodeIdGenerator {
  fun generateBtId(): String {
    val suffix = UUID.randomUUID().toString()
      .replace("-", "").uppercase().take(8)
    return "BT-${suffix.take(4)}-${suffix.takeLast(4)}"
  }

  fun generateWifiId(): String {
    val suffix = UUID.randomUUID().toString()
      .replace("-", "").uppercase().take(8)
    return "WIFI-${suffix.take(4)}-${suffix.takeLast(4)}"
  }
}

─── Rules ─────────────────────────────────────────────────────
- btNodeId and wifiNodeId are generated ONCE on first launch
  and NEVER regenerated — they are permanent device identifiers
- Both IDs are stored in EncryptedSharedPreferences
- username can be changed only if user resets identity
  (triggers new username claim cycle on the mesh)
- avatarSeed = btNodeId (consistent avatar across both transports)

─── IdentityRepository (update interface in domain/) ──────────
interface IdentityRepository {
  fun observeIdentity(): Flow<NodeIdentity>
  suspend fun createIdentity(username: String): NodeIdentity
  suspend fun markUsernameClaimed()
  fun getActiveNodeId(mode: TransportMode): String
    // BLUETOOTH → btNodeId, WIFI → wifiNodeId, BOTH → btNodeId
}

─── IdentityRepositoryImpl (update in data/) ──────────────────
- On first launch: generate both IDs via NodeIdGenerator
- Store full NodeIdentity in EncryptedSharedPreferences as JSON
- getActiveNodeId() returns the correct ID for the active transport
- Never expose raw IDs in logs — always truncate to last 4 chars
  e.g. Timber.d("Active node: ...${id.takeLast(4)}")

─── Unit tests ────────────────────────────────────────────────
- testBtAndWifiIdsAreDistinct()
- testIdsNeverRegeneratedOnSecondLaunch()
- testAvatarSeedIsConsistentAcrossTransports()

Prompt T-2 — Username Claim Protocol
Plan Mode
In core/, implement the distributed username reservation protocol.
No central server — uniqueness is enforced by mesh consensus.

─── New packet types (add to PacketType enum) ─────────────────
enum class PacketType {
  CHAT, CONTROL, METRICS, PRESENCE,
  DELIVERY_ACK, TYPING,
  USERNAME_CLAIM,     // ← new: "I want this username"
  USERNAME_CONFLICT,  // ← new: "That username is taken"
  USERNAME_REGISTRY,  // ← new: gossip — here is my known registry
  USERNAME_RELEASE,   // ← new: user changed name, releasing old one
}

─── UsernameClaimPayload.kt ───────────────────────────────────
@Serializable
data class UsernameClaimPayload(
  val username: String,
  val btNodeId: String,
  val wifiNodeId: String,
  val timestamp: Long,
  val nonce: String   // UUID — prevents replay of old claims
)

@Serializable
data class UsernameConflictPayload(
  val claimedUsername: String,
  val existingBtNodeId: String,
  val existingWifiNodeId: String,
  val conflictingNonce: String  // echo back the claim nonce
)

@Serializable
data class UsernameRegistryPayload(
  // gossip: map of username → btNodeId for all known users
  val registry: Map<String, String>  // username → btNodeId
)

─── UsernameClaimProtocol.kt ──────────────────────────────────
class UsernameClaimProtocol(
  private val meshNode: MeshNode,
  private val identityRepo: IdentityRepository,
) {
  // Local registry: username → btNodeId (all known users)
  private val mutex = Mutex()
  private val registry = mutableMapOf<String, String>()

  // Result of a claim attempt
  sealed class ClaimResult {
    object Claimed : ClaimResult()
    data class Conflict(val takenBy: String) : ClaimResult()
    object Timeout : ClaimResult()
  }

  // Call this during setup when user enters their name
  suspend fun claimUsername(username: String, identity: NodeIdentity): ClaimResult {
    // Step 1: Check local registry first (instant fail if known taken)
    mutex.withLock {
      val existing = registry[username.lowercase()]
      if (existing != null && existing != identity.btNodeId) {
        return ClaimResult.Conflict(takenBy = existing)
      }
    }

    // Step 2: Broadcast USERNAME_CLAIM to mesh
    val nonce = UUID.randomUUID().toString()
    val payload = UsernameClaimPayload(
      username = username.lowercase(),
      btNodeId = identity.btNodeId,
      wifiNodeId = identity.wifiNodeId,
      timestamp = System.currentTimeMillis(),
      nonce = nonce
    )
    meshNode.broadcast(
      Json.encodeToString(payload).encodeToByteArray(),
      type = PacketType.USERNAME_CLAIM
    )

    // Step 3: Wait up to 5 seconds for CONFLICT response
    return withTimeoutOrNull(5_000L) {
      meshNode.chatMessageFlow
        .filter { it.type == PacketType.USERNAME_CONFLICT }
        .map { Json.decodeFromString<UsernameConflictPayload>(it.decryptedPayload) }
        .filter { it.conflictingNonce == nonce }
        .first()
        .let { ClaimResult.Conflict(takenBy = it.existingBtNodeId) }
    } ?: run {
      // No conflict received — name is ours
      mutex.withLock { registry[username.lowercase()] = identity.btNodeId }
      identityRepo.markUsernameClaimed()
      broadcastRegistry()  // tell mesh about new registration
      ClaimResult.Claimed
    }
  }

  // Called when a node receives USERNAME_CLAIM from someone else
  suspend fun onClaimReceived(payload: UsernameClaimPayload, identity: NodeIdentity) {
    val conflict = mutex.withLock {
      val existing = registry[payload.username]
      if (existing != null && existing != payload.btNodeId) {
        existing  // we know this name is taken
      } else {
        // New claim — add to our registry and re-broadcast (flood)
        registry[payload.username] = payload.btNodeId
        null
      }
    }
    // Send CONFLICT back if we have a registry entry for that name
    if (conflict != null) {
      meshNode.send(
        dst = payload.btNodeId,
        payload = Json.encodeToString(UsernameConflictPayload(
          claimedUsername = payload.username,
          existingBtNodeId = conflict,
          existingWifiNodeId = "",
          conflictingNonce = payload.nonce
        )).encodeToByteArray(),
        type = PacketType.USERNAME_CONFLICT
      )
    }
  }

  // Gossip registry to a newly connected peer
  suspend fun onPeerConnected(peerId: String) {
    val snapshot = mutex.withLock { registry.toMap() }
    if (snapshot.isEmpty()) return
    meshNode.send(
      dst = peerId,
      payload = Json.encodeToString(
        UsernameRegistryPayload(registry = snapshot)
      ).encodeToByteArray(),
      type = PacketType.USERNAME_REGISTRY
    )
  }

  // Merge incoming registry from another node
  suspend fun onRegistryReceived(payload: UsernameRegistryPayload) {
    mutex.withLock {
      payload.registry.forEach { (username, nodeId) ->
        if (!registry.containsKey(username)) {
          registry[username] = nodeId  // first-seen wins
        }
      }
    }
  }

  private suspend fun broadcastRegistry() {
    val snapshot = mutex.withLock { registry.toMap() }
    meshNode.broadcast(
      Json.encodeToString(UsernameRegistryPayload(registry = snapshot))
        .encodeToByteArray(),
      type = PacketType.USERNAME_REGISTRY
    )
  }
}

─── Rules ─────────────────────────────────────────────────────
- All username comparisons use .lowercase().trim()
- Max username length: 24 chars
- Allowed chars: letters, digits, underscore, hyphen only
  Regex: ^[a-zA-Z0-9_-]{1,24}$
- Username is permanent after claim (no rename without reset)
- On mesh rejoin, re-broadcast claim to re-assert ownership

─── Unit tests ────────────────────────────────────────────────
- testClaimSucceedsWhenMeshEmpty()
- testClaimFailsWhenNameTaken()
- testConflictDetectedWithinTimeout()
- testRegistryMergesCorrectlyOnJoin()
- testUsernameComparisonIsCaseInsensitive()
- testClaimIsIdempotentForSameNode()

Prompt T-3 — TransportMode + DualTransportManager
Plan Mode
In core/ and transports/, implement the transport switching system.

─── TransportMode.kt (in core/) ───────────────────────────────
enum class TransportMode {
  BLUETOOTH,   // Nearby P2P_STAR — lower power, shorter range
  WIFI,        // Nearby P2P_CLUSTER — higher speed, longer range
  BOTH         // run both simultaneously, merge peer sets
}

─── DualTransportManager.kt (in transports/) ──────────────────
class DualTransportManager @Inject constructor(
  private val btTransport: BluetoothNearbyTransport,
  private val wifiTransport: WifiNearbyTransport,
  private val identityRepo: IdentityRepository,
  private val prefs: AppPreferences,
) : TransportAdapter {

  private val _modeFlow = MutableStateFlow(TransportMode.BLUETOOTH)
  val modeFlow: StateFlow<TransportMode> = _modeFlow.asStateFlow()

  // Merged peer set from whichever transports are active
  override val peersFlow: StateFlow<Set<String>> = combine(
    btTransport.peersFlow,
    wifiTransport.peersFlow,
    _modeFlow,
  ) { btPeers, wifiPeers, mode ->
    when (mode) {
      TransportMode.BLUETOOTH -> btPeers
      TransportMode.WIFI      -> wifiPeers
      TransportMode.BOTH      -> btPeers + wifiPeers
    }
  }.stateIn(scope, SharingStarted.WhileSubscribed(), emptySet())

  // Merged incoming packets — tag each with its transport source
  override val incomingFlow: Flow<Pair<String, ByteArray>> = merge(
    btTransport.incomingFlow
      .filter { modeFlow.value != TransportMode.WIFI },
    wifiTransport.incomingFlow
      .filter { modeFlow.value != TransportMode.BLUETOOTH },
  )

  // Switch transport at runtime
  suspend fun switchMode(newMode: TransportMode) {
    val current = _modeFlow.value
    if (current == newMode) return

    // Start new transport before stopping old (no connectivity gap)
    when (newMode) {
      TransportMode.BLUETOOTH -> {
        val identity = identityRepo.observeIdentity().first()
        btTransport.start(identity.btNodeId)
        if (current == TransportMode.WIFI) wifiTransport.stop()
      }
      TransportMode.WIFI -> {
        val identity = identityRepo.observeIdentity().first()
        wifiTransport.start(identity.wifiNodeId)
        if (current == TransportMode.BLUETOOTH) btTransport.stop()
      }
      TransportMode.BOTH -> {
        val identity = identityRepo.observeIdentity().first()
        btTransport.start(identity.btNodeId)
        wifiTransport.start(identity.wifiNodeId)
      }
    }

    _modeFlow.value = newMode
    prefs.saveTransportMode(newMode)  // persist across app restarts
  }

  override suspend fun send(peerId: String, data: ByteArray) {
    when (_modeFlow.value) {
      TransportMode.BLUETOOTH -> btTransport.send(peerId, data)
      TransportMode.WIFI      -> wifiTransport.send(peerId, data)
      TransportMode.BOTH      -> {
        // Try BT first, fallback to WiFi
        runCatching { btTransport.send(peerId, data) }
          .onFailure { wifiTransport.send(peerId, data) }
      }
    }
  }

  override suspend fun broadcast(data: ByteArray) {
    when (_modeFlow.value) {
      TransportMode.BLUETOOTH -> btTransport.broadcast(data)
      TransportMode.WIFI      -> wifiTransport.broadcast(data)
      TransportMode.BOTH      -> {
        // Parallel broadcast on both
        coroutineScope {
          launch { btTransport.broadcast(data) }
          launch { wifiTransport.broadcast(data) }
        }
      }
    }
  }
}

─── BluetoothNearbyTransport.kt ───────────────────────────────
Strategy: P2P_STAR (one hub + spokes — optimized for BT range)
  - Uses Nearby Connections with Strategy.P2P_STAR
  - nodeId prefix filter: only connects to "BT-" prefixed node IDs
  - SERVICE_ID: "com.meshchat.bluetooth"
  - Lower advertise power: BluetoothAdapter standard power

─── WifiNearbyTransport.kt ────────────────────────────────────
Strategy: P2P_CLUSTER (full mesh — all nodes equal)
  - Uses Nearby Connections with Strategy.P2P_CLUSTER
  - nodeId prefix filter: only connects to "WIFI-" prefixed node IDs
  - SERVICE_ID: "com.meshchat.wifi"
  - Higher bandwidth: uses WiFi Direct under the hood

─── Unit tests ────────────────────────────────────────────────
- testSwitchFromBtToWifiStopsOldTransport()
- testBothModeReceivesFromEitherTransport()
- testPeerSetMergesCorrectlyInBothMode()
- testSendFallsBackToWifiWhenBtFails()
- testModePersistsAfterAppRestart()

Prompt T-4 — Setup Screen (Username Claim UI)
Plan Mode
In app/ui/setup/, update SetupScreen to handle the username
claim flow with real-time mesh feedback.

─── SetupViewModel.kt ─────────────────────────────────────────
@HiltViewModel
class SetupViewModel @Inject constructor(
  private val claimProtocol: UsernameClaimProtocol,
  private val identityRepo: IdentityRepository,
  private val prefs: AppPreferences,
) : ViewModel() {

  sealed class SetupUiState {
    object Idle : SetupUiState()
    object Checking : SetupUiState()          // "Checking mesh..."
    object Claimed : SetupUiState()           // "Name is yours!"
    data class Conflict(
      val username: String
    ) : SetupUiState()                        // "Already taken"
    object NoMesh : SetupUiState()            // offline — claim locally
  }

  private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
  val state: StateFlow<SetupUiState> = _state.asStateFlow()

  var username by mutableStateOf("")
  var selectedMode by mutableStateOf(TransportMode.BLUETOOTH)

  fun onUsernameChanged(value: String) {
    username = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
      .take(24)
    _state.value = SetupUiState.Idle  // reset on any change
  }

  fun onConfirm() {
    if (username.isBlank()) return
    viewModelScope.launch {
      _state.value = SetupUiState.Checking
      val identity = identityRepo.createIdentity(username)
      when (val result = claimProtocol.claimUsername(username, identity)) {
        is ClaimResult.Claimed  -> _state.value = SetupUiState.Claimed
        is ClaimResult.Conflict -> _state.value = SetupUiState.Conflict(username)
        is ClaimResult.Timeout  -> _state.value = SetupUiState.NoMesh
          // offline — allow anyway, will re-assert on next mesh join
      }
    }
  }
}

─── SetupScreen.kt ────────────────────────────────────────────
Three steps rendered as a vertical pager (no tabs — linear flow):

STEP 1 — Choose transport:
  Title: "How do you want to connect?"
  Two large selectable cards side by side:

  ┌─────────────────┐  ┌─────────────────┐
  │   📶            │  │   🔵            │
  │   WiFi          │  │   Bluetooth     │
  │   Longer range  │  │   Lower power   │
  │   Higher speed  │  │   No WiFi needed│
  └─────────────────┘  └─────────────────┘
  And a third option below:
  ┌───────────────────────────────────────┐
  │   Both (uses more battery)            │
  └───────────────────────────────────────┘

  Selected card: primary color border + background tint
  "Next →" button

STEP 2 — Enter username:
  Title: "Choose your name"
  Subtitle: "Once claimed on the mesh, no one else can use it"
  Large TextField (DM Sans 20sp)
  Live validation:
    - Red: blank or invalid chars
    - Amber: too long
    - Green: valid format

  Below field show the two node IDs being generated live:
    🔵 BT-A3F2-9D12   (dim, monospace)
    📶 WIFI-C8B1-4E33  (dim, monospace)
  With label: "Your device IDs — auto-generated, permanent"

  "Claim Name" button — only active when username is valid

STEP 3 — Claim status:
  Show animated state:

  CHECKING:
    Spinning radar animation
    "Checking if "@{username}" is available on the mesh..."

  CLAIMED:
    ✓ checkmark animation (draw stroke animation)
    "@{username} is yours!"
    "Your identity is set. Welcome to MeshChat."
    "Start Chatting →" button

  CONFLICT:
    ✗ icon in red
    "@{username} is already taken on this mesh"
    "← Choose another name" button → back to Step 2
    Show the taken username crossed out in red

  NO_MESH (timeout — no peers found):
    Info icon in amber
    "No mesh found nearby. Your name will be claimed
     when you join a mesh."
    "Continue anyway →" button
    (username marked as pending claim, re-asserted on next connection)

Prompt T-5 — Network Screen (Transport Toggle UI)
Plan Mode
In app/ui/network/, update NetworkScreen to show both node IDs
and a live transport switcher.

─── NetworkViewModel.kt (update) ──────────────────────────────
Add to existing NetworkViewModel:
  val transportMode: StateFlow<TransportMode> =
    dualTransportManager.modeFlow.stateIn(
      viewModelScope, SharingStarted.WhileSubscribed(), TransportMode.BLUETOOTH
    )

  val btNodeId: String get() = identity.btNodeId
  val wifiNodeId: String get() = identity.wifiNodeId

  fun switchTransport(mode: TransportMode) {
    viewModelScope.launch {
      dualTransportManager.switchMode(mode)
    }
  }

─── NetworkScreen.kt (update) ─────────────────────────────────
Add these two sections to the existing Network screen:

SECTION 1 — Transport switcher (place below room code, above stats card):

  Label: "CONNECTION TYPE"

  Three-option SegmentedButton (Material 3):
  ┌────────────┬────────────┬────────────┐
  │ 🔵 BT      │ 📶 WiFi    │ ◉ Both     │
  └────────────┴────────────┴────────────┘
  Active segment: primary color fill
  Inactive: surface color

  Below the switcher show active transport status:
    BLUETOOTH: "P2P Star · Short range · Power saving"
    WIFI:      "P2P Cluster · Full mesh · High speed"
    BOTH:      "Dual active · Maximum coverage"

  Switching animation: short 300ms crossfade of the stats card
  Show a SnackBar on switch: "Switched to WiFi — reconnecting..."

SECTION 2 — Dual node ID card (place at bottom of screen):

  Card with two rows:
  ┌──────────────────────────────────────────┐
  │ YOUR DEVICE IDs                          │
  │                                          │
  │ 🔵 Bluetooth   BT-A3F2-9D12     [copy]  │
  │ 📶 WiFi       WIFI-C8B1-4E33    [copy]  │
  │                                          │
  │ Username: @Keshav  ✓ Claimed            │
  └──────────────────────────────────────────┘

  - Both IDs in Share Tech Mono font
  - Copy icon taps → copies full ID to clipboard + shows Snackbar "Copied!"
  - Username row: green ✓ if claimed, amber clock if pending claim
  - Tap username row → shows bottom sheet with claim status detail

  If transport is BLUETOOTH: highlight BT row, dim WiFi row
  If transport is WIFI: highlight WiFi row, dim BT row
  If transport is BOTH: both highlighted

─── Peer cards update ─────────────────────────────────────────
Each peer card in the list now shows which transport they
are connected via:
  [🔵 Direct · 1 hop]   ← connected via Bluetooth
  [📶 Direct · 1 hop]   ← connected via WiFi
  [📶🔵 · 2 hops]       ← reachable on both

Show the peer's username (@Arjun) not just their display name.

Prompt T-6 — Settings Screen (Transport + Identity)
Fast Mode
In app/ui/settings/, update SettingsScreen with transport
preferences and identity management for the dual-ID system.

─── New IDENTITY section (add above MESH section) ─────────────
Using Material 3 ListItem composable throughout.

IDENTITY section:
  Row 1 — Username
    Icon: person outline
    Headline: "@{username}"
    Supporting: "Claimed · permanent"
    Trailing: green ✓ badge if claimed, amber ⏳ if pending
    Not tappable (username cannot be changed without reset)

  Row 2 — Bluetooth Node ID
    Icon: bluetooth icon
    Headline: "{btNodeId}"  ← Share Tech Mono font
    Supporting: "Bluetooth identity"
    Trailing: copy icon → copies to clipboard

  Row 3 — WiFi Node ID
    Icon: wifi icon
    Headline: "{wifiNodeId}"  ← Share Tech Mono font
    Supporting: "WiFi identity"
    Trailing: copy icon → copies to clipboard

─── Updated MESH section ─────────────────────────────────────
  Row 1 — Default Transport
    Headline: "Default connection type"
    Trailing: SegmentedButton BT / WiFi / Both
    Persisted to DataStore — used on next app launch

  Row 2 — Max Hops (existing, keep)

  Row 3 — Room Code (existing, keep)

─── Updated DANGER ZONE section ──────────────────────────────
  Row 1 — Clear all messages (existing)

  Row 2 — Reset identity  ← update this
    Tap → AlertDialog:
      Title: "Reset your identity?"
      Body:  "This will release @{username} from the mesh,
              generate new Bluetooth and WiFi node IDs, and
              erase all your messages. This cannot be undone."
      Buttons: "Cancel" | "Reset Everything" (red, destructive)
    On confirm:
      1. Broadcast USERNAME_RELEASE packet to mesh
      2. Delete EncryptedSharedPreferences entry
      3. Generate new btNodeId + wifiNodeId
      4. Navigate to SetupScreen fresh

Prompt T-7 — Presence Packet Update
Fast Mode
In core/, update the PRESENCE packet to carry both node IDs
and the username so peers can display the right identity
regardless of which transport they connected via.

─── PresencePayload.kt (update) ───────────────────────────────
@Serializable
data class PresencePayload(
  val username: String,          // "@Keshav"
  val btNodeId: String,          // "BT-A3F2-9D12"
  val wifiNodeId: String,        // "WIFI-C8B1-4E33"
  val avatarSeed: String,
  val activeTransport: TransportMode,  // what they're currently on
  val timestamp: Long,
  val usernameClaimed: Boolean
)

─── PresenceManager.kt (update) ───────────────────────────────
Update PeerInfo to hold both IDs:
  data class PeerInfo(
    val username: String,
    val btNodeId: String,
    val wifiNodeId: String,
    val avatarSeed: String,
    val isOnline: Boolean,
    val activeTransport: TransportMode,
    val hopDistance: Int,
    val lastSeen: Long,
    val usernameClaimed: Boolean
  )

  // Lookup by either ID:
  fun getPeerByBtId(btNodeId: String): PeerInfo?
  fun getPeerByWifiId(wifiNodeId: String): PeerInfo?
  fun getPeerByUsername(username: String): PeerInfo?

─── ConversationId strategy ───────────────────────────────────
Conversations are keyed by USERNAME not nodeId:
  conversationId = "dm_${myUsername}_${peerUsername}".lowercase()
    .split("_").sorted().joinToString("_")

This means:
  - Chat history persists even if peer switches BT ↔ WiFi
  - Chat history persists even if peer reinstalls (same username)
  - New nodeIds after a reset = new conversation (username changed)

Update ChatRepository to use username-based conversationId.

Prompt T-8 — Permission handling for both transports
Fast Mode
In app/, handle the different runtime permissions required for
Bluetooth vs WiFi transport modes.

─── PermissionManager.kt ──────────────────────────────────────
class PermissionManager(private val activity: ComponentActivity) {

  fun getRequiredPermissions(mode: TransportMode): List<String> =
    when (mode) {
      TransportMode.BLUETOOTH -> buildList {
        if (Build.VERSION.SDK_INT >= 31) {
          add(Manifest.permission.BLUETOOTH_SCAN)
          add(Manifest.permission.BLUETOOTH_CONNECT)
          add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
          add(Manifest.permission.BLUETOOTH)
          add(Manifest.permission.BLUETOOTH_ADMIN)
          add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
      }
      TransportMode.WIFI -> buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
          add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
      }
      TransportMode.BOTH -> (
        getRequiredPermissions(TransportMode.BLUETOOTH) +
        getRequiredPermissions(TransportMode.WIFI)
      ).distinct()
    }

  fun arePermissionsGranted(mode: TransportMode): Boolean =
    getRequiredPermissions(mode).all {
      ContextCompat.checkSelfPermission(activity, it) ==
        PackageManager.PERMISSION_GRANTED
    }
}

─── Permission UI ─────────────────────────────────────────────
In SetupScreen Step 1 (transport selection):
  After user selects transport and taps Next:
    1. Check PermissionManager.arePermissionsGranted(selectedMode)
    2. If false: show PermissionRationaleSheet BEFORE requesting:
       Bottom sheet with:
         Icon for transport type
         Title: "MeshChat needs {Bluetooth / WiFi} access"
         Body:  explain WHY in plain language (not legal text)
         "Grant Access" button → launches permission request
         "Not now" → keeps mode but shows warning banner

In NetworkScreen transport switcher:
  Tapping a mode that lacks permissions:
    → Show SnackBar: "Grant Bluetooth permission to use this mode"
      with "Grant" action → navigates to app settings
    → Do NOT switch mode until permissions are granted

─── AndroidManifest.xml update ────────────────────────────────
Add all required permission declarations for both transports.
Use maxSdkVersion where applicable to limit over-requesting:

  <!-- Bluetooth classic (pre-API 31) -->
  <uses-permission android:name="...BLUETOOTH"
    android:maxSdkVersion="30"/>
  <uses-permission android:name="...BLUETOOTH_ADMIN"
    android:maxSdkVersion="30"/>

  <!-- Bluetooth modern (API 31+) -->
  <uses-permission android:name="...BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s"/>
  <uses-permission android:name="...BLUETOOTH_CONNECT"/>
  <uses-permission android:name="...BLUETOOTH_ADVERTISE"/>

  <!-- WiFi Direct -->
  <uses-permission android:name="...ACCESS_FINE_LOCATION"/>
  <uses-permission android:name="...CHANGE_NETWORK_STATE"/>
  <uses-permission android:name="...NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="tiramisu"/>

Run Order in Antigravity
T-1   Dual NodeIdentity (btNodeId + wifiNodeId + username)
T-2   Username claim protocol (mesh consensus, no server)
T-3   DualTransportManager + BT/WiFi Nearby transports
T-4   Setup screen — transport picker + claim flow UI
T-5   Network screen — transport toggle + dual ID card
T-6   Settings screen — identity section + reset flow
T-7   Presence packet — username-keyed conversations
T-8   Permission handling for both transport modes

Then re-run all 7 buffer/deadlock fix prompts (F-1 to F-7)
against the updated transport layer — the new DualTransportManager
must use the same Channel(BUFFERED, DROP_OLDEST) pattern.Can we add sharing on image and videoArchitected media transfer strategy addressing chunking, storage, and progress trackingArchitected media transfer strategy addressing chunking, storage, and progress trackingGood addition but this is the most technically complex feature in the whole app — large binary payloads over a mesh with no guaranteed delivery need a completely different pipeline from text. Let me map every risk first.SShow widgetHere are all the prompts:

Prompt M-1 — Media Packet Types + Domain Models
Plan Mode
In core/, add all media transfer types alongside existing
chat packets. Media uses a completely separate packet pipeline
from text — never mixed.

─── Add to PacketType enum ────────────────────────────────────
enum class PacketType {
  // existing...
  MEDIA_HEADER,    // sender announces a new media transfer
  MEDIA_CHUNK,     // one encrypted chunk of the file
  MEDIA_CHUNK_ACK, // receiver confirms chunk received (backpressure)
  MEDIA_EOF,       // sender signals transfer complete
  MEDIA_ACK,       // receiver confirms full file verified OK
  MEDIA_CANCEL,    // either side cancels mid-transfer
  MEDIA_AVAILABLE, // broadcast: "I sent a media to X, others can request"
}

─── MediaType.kt ──────────────────────────────────────────────
enum class MediaType { IMAGE, VIDEO }

─── MediaHeaderPayload.kt ─────────────────────────────────────
@Serializable
data class MediaHeaderPayload(
  val transferId: String,     // UUID — unique per transfer
  val mediaType: MediaType,
  val filename: String,       // sanitized, no path separators
  val mimeType: String,       // "image/jpeg" | "video/mp4"
  val totalSizeBytes: Long,
  val totalChunks: Int,
  val chunkSizeBytes: Int,    // negotiated per transport
  val sha256Checksum: String, // hex — whole file before chunking
  val thumbnailBase64: String,// JPEG thumbnail ≤ 8KB, always present
  val senderUsername: String,
  val conversationId: String,
  val timestamp: Long
)

─── MediaChunkPayload.kt ──────────────────────────────────────
@Serializable
data class MediaChunkPayload(
  val transferId: String,
  val chunkIndex: Int,        // 0-based
  val totalChunks: Int,
  val data: String,           // base64 AES-GCM encrypted chunk bytes
  val chunkHmac: String,      // HMAC of this chunk only (early tamper detect)
)

─── MediaTransfer.kt (domain model) ───────────────────────────
data class MediaTransfer(
  val transferId: String,
  val mediaType: MediaType,
  val mimeType: String,
  val totalSizeBytes: Long,
  val bytesTransferred: Long,
  val status: MediaTransferStatus,
  val localUri: String?,          // null until transfer completes
  val thumbnailBase64: String,    // always available from header
  val senderUsername: String,
  val conversationId: String,
  val timestamp: Long,
  val isOutgoing: Boolean,
)

sealed class MediaTransferStatus {
  object Pending      : MediaTransferStatus()  // header received, not started
  data class Progress(
    val percent: Int,
    val chunksReceived: Int,
    val totalChunks: Int,
  )                   : MediaTransferStatus()
  object Verifying    : MediaTransferStatus()  // SHA-256 in progress
  object Complete     : MediaTransferStatus()  // file ready to display
  data class Failed(val reason: String) : MediaTransferStatus()
  object Cancelled    : MediaTransferStatus()
}

─── ChunkSize constants ───────────────────────────────────────
object ChunkSizes {
  const val BLUETOOTH_BYTES = 512        // safe BLE ATT MTU limit
  const val WIFI_BYTES      = 65_536     // 64KB — WiFi Direct optimal
  const val BOTH_BYTES      = 65_536     // use larger when WiFi available

  fun forMode(mode: TransportMode) = when (mode) {
    TransportMode.BLUETOOTH -> BLUETOOTH_BYTES
    TransportMode.WIFI      -> WIFI_BYTES
    TransportMode.BOTH      -> BOTH_BYTES
  }
}

─── Size limits ───────────────────────────────────────────────
object MediaLimits {
  const val IMAGE_MAX_BYTES_BT   = 5 * 1024 * 1024    // 5 MB
  const val IMAGE_MAX_BYTES_WIFI = 15 * 1024 * 1024   // 15 MB
  const val VIDEO_MAX_BYTES      = 30 * 1024 * 1024   // 30 MB
  const val VIDEO_MAX_SECONDS    = 30
  const val THUMBNAIL_MAX_BYTES  = 8 * 1024            // 8 KB
  const val IMAGE_MAX_DIMENSION  = 1920                // px, longer edge

  // Video is forbidden on Bluetooth — too slow
  fun isVideoAllowed(mode: TransportMode) =
    mode != TransportMode.BLUETOOTH
}

Prompt M-2 — MediaProcessor (Compress + Chunk)
Plan Mode
In data/media/, implement MediaProcessor that prepares media
files for mesh transfer: compress, thumbnail, chunk, encrypt.

─── MediaProcessor.kt ─────────────────────────────────────────
class MediaProcessor @Inject constructor(
  private val crypto: CryptoRepository,
  private val context: Context,
) {
  // Step 1 — Compress image
  suspend fun compressImage(uri: Uri): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatching {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
          val src = ImageDecoder.createSource(context.contentResolver, uri)
          ImageDecoder.decodeBitmap(src)
        } else {
          @Suppress("DEPRECATION")
          MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }

        // Scale down keeping aspect ratio
        val scaled = scaleBitmap(bitmap, MediaLimits.IMAGE_MAX_DIMENSION)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
      }
    }

  private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxDimension && h <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(
      bitmap, (w * scale).toInt(), (h * scale).toInt(), true
    )
  }

  // Step 2 — Generate thumbnail (always JPEG, max 8KB)
  suspend fun generateThumbnail(
    data: ByteArray,
    mediaType: MediaType
  ): String = withContext(Dispatchers.IO) {
    val bitmap = when (mediaType) {
      MediaType.IMAGE -> {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
        scaleBitmap(bmp, 200)  // 200px thumbnail
      }
      MediaType.VIDEO -> {
        // Extract first frame
        val tmpFile = File(context.cacheDir, "thumb_tmp.mp4")
        tmpFile.writeBytes(data)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(tmpFile.absolutePath)
        val frame = retriever.getFrameAtTime(0) ?: Bitmap.createBitmap(
          200, 200, Bitmap.Config.ARGB_8888
        )
        retriever.release()
        tmpFile.delete()
        scaleBitmap(frame, 200)
      }
    }
    val out = ByteArrayOutputStream()
    var quality = 85
    do {
      out.reset()
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      quality -= 10
    } while (out.size() > MediaLimits.THUMBNAIL_MAX_BYTES && quality > 20)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  // Step 3 — Validate video duration
  suspend fun validateVideo(uri: Uri): Result<ByteArray> =
    withContext(Dispatchers.IO) {
      runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(
          MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        retriever.release()

        if (durationMs > MediaLimits.VIDEO_MAX_SECONDS * 1000L) {
          throw IllegalArgumentException(
            "Video too long: ${durationMs/1000}s max ${MediaLimits.VIDEO_MAX_SECONDS}s"
          )
        }
        context.contentResolver.openInputStream(uri)!!.readBytes()
      }
    }

  // Step 4 — Chunk + encrypt file bytes
  fun chunkAndEncrypt(
    data: ByteArray,
    chunkSize: Int,
    key: SecretKey,
  ): List<MediaChunkPayload> {
    val transferId = UUID.randomUUID().toString()
    val totalChunks = ceil(data.size.toDouble() / chunkSize).toInt()

    return data.toList()
      .chunked(chunkSize)
      .mapIndexed { index, chunkBytes ->
        val raw = chunkBytes.toByteArray()
        val encrypted = crypto.encrypt(raw, key)
        val hmac = crypto.hmacSign(raw, key)
        MediaChunkPayload(
          transferId = transferId,
          chunkIndex = index,
          totalChunks = totalChunks,
          data = Base64.encodeToString(encrypted.toBytes(), Base64.NO_WRAP),
          chunkHmac = hmac,
        )
      }
  }

  // Step 5 — SHA-256 checksum for whole-file verification
  fun sha256(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data)
      .joinToString("") { "%02x".format(it) }
  }
}

Prompt M-3 — MediaTransferEngine (Send + Receive with Backpressure)
Plan Mode
In core/, implement the MediaTransferEngine that manages the
send and receive lifecycle for media. Includes backpressure to
prevent buffer overflow on Bluetooth.

─── MediaTransferEngine.kt ────────────────────────────────────
class MediaTransferEngine(
  private val meshNode: MeshNode,
  private val processor: MediaProcessor,
  private val crypto: CryptoRepository,
  private val fileStorage: MediaFileStorage,
) {
  // Active outgoing transfers
  private val outgoing = ConcurrentHashMap<String, OutgoingTransfer>()

  // Active incoming transfers
  private val incoming = ConcurrentHashMap<String, IncomingTransfer>()

  private val _transferFlow = MutableSharedFlow<MediaTransfer>(
    replay = 0,
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  val transferFlow: Flow<MediaTransfer> = _transferFlow.asSharedFlow()

  // ─── SEND ──────────────────────────────────────────────────
  suspend fun sendMedia(
    uri: Uri,
    mediaType: MediaType,
    dstUsername: String,
    mode: TransportMode,
    key: SecretKey,
  ): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      // 1. Validate size
      val data = when (mediaType) {
        MediaType.IMAGE -> processor.compressImage(uri).getOrThrow()
        MediaType.VIDEO -> {
          if (!MediaLimits.isVideoAllowed(mode)) {
            throw IllegalStateException(
              "Video not supported over Bluetooth. Switch to WiFi."
            )
          }
          processor.validateVideo(uri).getOrThrow()
        }
      }

      val sizeLimit = when {
        mediaType == MediaType.VIDEO -> MediaLimits.VIDEO_MAX_BYTES
        mode == TransportMode.BLUETOOTH -> MediaLimits.IMAGE_MAX_BYTES_BT
        else -> MediaLimits.IMAGE_MAX_BYTES_WIFI
      }
      if (data.size > sizeLimit) {
        throw IllegalArgumentException(
          "File too large: ${data.size / 1024}KB limit ${sizeLimit / 1024}KB"
        )
      }

      // 2. Prepare
      val transferId = UUID.randomUUID().toString()
      val thumbnail = processor.generateThumbnail(data, mediaType)
      val checksum = processor.sha256(data)
      val chunkSize = ChunkSizes.forMode(mode)
      val chunks = processor.chunkAndEncrypt(data, chunkSize, key)

      // 3. Send HEADER
      meshNode.send(
        dst = dstUsername,
        payload = Json.encodeToString(MediaHeaderPayload(
          transferId = transferId,
          mediaType = mediaType,
          filename = "media_${transferId.take(8)}.${if(mediaType==MediaType.IMAGE) "jpg" else "mp4"}",
          mimeType = if (mediaType == MediaType.IMAGE) "image/jpeg" else "video/mp4",
          totalSizeBytes = data.size.toLong(),
          totalChunks = chunks.size,
          chunkSizeBytes = chunkSize,
          sha256Checksum = checksum,
          thumbnailBase64 = thumbnail,
          senderUsername = meshNode.localUsername,
          conversationId = buildConversationId(meshNode.localUsername, dstUsername),
          timestamp = System.currentTimeMillis()
        )).encodeToByteArray(),
        type = PacketType.MEDIA_HEADER
      )

      // 4. Send chunks WITH backpressure
      val transfer = OutgoingTransfer(
        transferId = transferId,
        chunks = chunks,
        chunkAckChannel = Channel(capacity = 4)  // window of 4 unacked chunks
      )
      outgoing[transferId] = transfer

      for ((index, chunk) in chunks.withIndex()) {
        if (transfer.cancelled) break

        // Emit progress
        _transferFlow.tryEmit(MediaTransfer(
          transferId = transferId,
          mediaType = mediaType,
          mimeType = "image/jpeg",
          totalSizeBytes = data.size.toLong(),
          bytesTransferred = (index * chunkSize).toLong().coerceAtMost(data.size.toLong()),
          status = MediaTransferStatus.Progress(
            percent = (index * 100) / chunks.size,
            chunksReceived = index,
            totalChunks = chunks.size,
          ),
          localUri = null,
          thumbnailBase64 = thumbnail,
          senderUsername = meshNode.localUsername,
          conversationId = buildConversationId(meshNode.localUsername, dstUsername),
          timestamp = System.currentTimeMillis(),
          isOutgoing = true,
        ))

        // Send chunk
        meshNode.send(
          dst = dstUsername,
          payload = Json.encodeToString(chunk).encodeToByteArray(),
          type = PacketType.MEDIA_CHUNK
        )

        // BACKPRESSURE: wait for CHUNK_ACK before sending next
        // (skip on WiFi — only strict on BT to prevent buffer overflow)
        if (mode == TransportMode.BLUETOOTH) {
          withTimeout(10_000L) {
            transfer.chunkAckChannel.receive()  // blocks until ACK
          }
        }
      }

      // 5. Send EOF
      if (!transfer.cancelled) {
        meshNode.send(
          dst = dstUsername,
          payload = Json.encodeToString(
            mapOf("transferId" to transferId, "checksum" to checksum)
          ).encodeToByteArray(),
          type = PacketType.MEDIA_EOF
        )
      }

      outgoing.remove(transferId)
      transferId
    }
  }

  // Cancel outgoing
  fun cancelOutgoing(transferId: String) {
    outgoing[transferId]?.cancelled = true
    outgoing.remove(transferId)
  }

  // ─── RECEIVE ───────────────────────────────────────────────
  suspend fun onHeaderReceived(header: MediaHeaderPayload) {
    // Allocate temp file
    val tmpFile = fileStorage.createTempFile(header.transferId)
    incoming[header.transferId] = IncomingTransfer(
      header = header,
      tmpFile = tmpFile,
      receivedChunks = mutableListOf(),
      expectedChunks = header.totalChunks,
    )
    // Show pending bubble immediately using thumbnail
    _transferFlow.tryEmit(MediaTransfer(
      transferId = header.transferId,
      mediaType = header.mediaType,
      mimeType = header.mimeType,
      totalSizeBytes = header.totalSizeBytes,
      bytesTransferred = 0L,
      status = MediaTransferStatus.Pending,
      localUri = null,
      thumbnailBase64 = header.thumbnailBase64,
      senderUsername = header.senderUsername,
      conversationId = header.conversationId,
      timestamp = header.timestamp,
      isOutgoing = false,
    ))
  }

  suspend fun onChunkReceived(chunk: MediaChunkPayload, key: SecretKey) {
    val transfer = incoming[chunk.transferId] ?: return

    // Decrypt + verify chunk HMAC
    val encrypted = Base64.decode(chunk.data, Base64.NO_WRAP)
    val decrypted = runCatching {
      crypto.decrypt(EncryptedBlob.fromBytes(encrypted), key)
    }.getOrElse {
      // Bad chunk — request retransmit (future enhancement)
      return
    }
    if (!crypto.hmacVerify(decrypted, chunk.chunkHmac, key)) return

    // Write chunk to temp file at correct offset
    withContext(Dispatchers.IO) {
      transfer.tmpFile.outputStream().use { out ->
        out.channel.position(chunk.chunkIndex.toLong() * transfer.header.chunkSizeBytes)
        out.write(decrypted)
      }
    }
    transfer.receivedChunks.add(chunk.chunkIndex)

    // Send CHUNK_ACK back to sender (backpressure signal)
    meshNode.send(
      dst = transfer.header.senderUsername,
      payload = Json.encodeToString(
        mapOf("transferId" to chunk.transferId, "ackIndex" to chunk.chunkIndex)
      ).encodeToByteArray(),
      type = PacketType.MEDIA_CHUNK_ACK
    )

    // Emit progress
    val progress = (transfer.receivedChunks.size * 100) / transfer.expectedChunks
    _transferFlow.tryEmit(transfer.toProgressEvent(progress))
  }

  suspend fun onEofReceived(transferId: String, checksum: String, key: SecretKey) {
    val transfer = incoming[transferId] ?: return

    // Emit verifying state
    _transferFlow.tryEmit(transfer.toVerifyingEvent())

    // SHA-256 whole file
    val fileBytes = withContext(Dispatchers.IO) {
      transfer.tmpFile.readBytes()
    }
    val actualChecksum = processor.sha256(fileBytes)

    if (actualChecksum != checksum) {
      _transferFlow.tryEmit(transfer.toFailedEvent("Checksum mismatch — file corrupted"))
      fileStorage.deleteTempFile(transferId)
      incoming.remove(transferId)
      return
    }

    // Move temp → permanent
    val finalUri = withContext(Dispatchers.IO) {
      fileStorage.savePermanent(
        transferId = transferId,
        data = fileBytes,
        mediaType = transfer.header.mediaType,
        filename = transfer.header.filename,
      )
    }

    // Send MEDIA_ACK to sender
    meshNode.send(
      dst = transfer.header.senderUsername,
      payload = Json.encodeToString(mapOf("transferId" to transferId))
        .encodeToByteArray(),
      type = PacketType.MEDIA_ACK
    )

    _transferFlow.tryEmit(transfer.toCompleteEvent(finalUri))
    incoming.remove(transferId)
    fileStorage.deleteTempFile(transferId)
  }

  private data class OutgoingTransfer(
    val transferId: String,
    val chunks: List<MediaChunkPayload>,
    val chunkAckChannel: Channel<Int>,
    var cancelled: Boolean = false,
  )

  private data class IncomingTransfer(
    val header: MediaHeaderPayload,
    val tmpFile: File,
    val receivedChunks: MutableList<Int>,
    val expectedChunks: Int,
  )
}

Prompt M-4 — MediaFileStorage
Fast Mode
In data/media/, implement safe app-specific file storage
for media. No external storage, no gallery access.

─── MediaFileStorage.kt ───────────────────────────────────────
class MediaFileStorage @Inject constructor(
  @ApplicationContext private val context: Context
) {
  // Temp dir for in-progress transfers
  private val tempDir: File
    get() = File(context.cacheDir, "media_incoming").also { it.mkdirs() }

  // Permanent dirs
  private val imageDir: File
    get() = File(context.filesDir, "media/images").also { it.mkdirs() }
  private val videoDir: File
    get() = File(context.filesDir, "media/videos").also { it.mkdirs() }

  fun createTempFile(transferId: String): File {
    return File(tempDir, "${transferId}.tmp")
      .also { if (it.exists()) it.delete() }
  }

  fun deleteTempFile(transferId: String) {
    File(tempDir, "${transferId}.tmp").delete()
  }

  suspend fun savePermanent(
    transferId: String,
    data: ByteArray,
    mediaType: MediaType,
    filename: String,
  ): String = withContext(Dispatchers.IO) {
    // Sanitize filename — strip all path separators
    val safeName = filename
      .replace(Regex("[/\\\\:*?\"<>|]"), "_")
      .take(64)

    val dir = if (mediaType == MediaType.IMAGE) imageDir else videoDir
    val dest = File(dir, safeName)

    // Atomic write: write to .tmp first, then rename
    val tmp = File(dir, "${safeName}.writing")
    tmp.writeBytes(data)
    tmp.renameTo(dest)  // atomic on same filesystem

    dest.absolutePath
  }

  // FileProvider URI for sharing outside app (optional future feature)
  fun getFileProviderUri(filePath: String): Uri {
    return FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      File(filePath)
    )
  }

  // Cleanup: delete transfers older than 7 days from temp dir
  suspend fun cleanupStale() = withContext(Dispatchers.IO) {
    val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
    tempDir.listFiles()
      ?.filter { it.lastModified() < cutoff }
      ?.forEach { it.delete() }
  }

  // Get all stored media for a conversation
  fun getMediaForConversation(conversationId: String): List<File> {
    return (imageDir.listFiles() ?: emptyArray<File>()).toList() +
      (videoDir.listFiles() ?: emptyArray<File>()).toList()
  }
}

─── Room entity update ────────────────────────────────────────
Update MessageEntity to support media messages:

@Entity(tableName = "messages")
data class MessageEntity(
  @PrimaryKey val id: String,
  val conversationId: String,
  val senderId: String,
  val senderName: String,
  val text: String?,              // null for media-only messages
  val mediaTransferId: String?,   // non-null for media messages
  val mediaType: String?,         // "IMAGE" | "VIDEO" | null
  val mediaThumbnailBase64: String?, // always stored for quick display
  val mediaLocalPath: String?,    // null until transfer completes
  val mediaSizeBytes: Long,
  val mediaStatus: String,        // mirrors MediaTransferStatus name
  val timestamp: Long,
  val status: String,
  val hopCount: Int,
  val isOutgoing: Boolean,
)

Add MediaDao:
  suspend fun insertMedia(msg: MessageEntity)
  fun observeMediaForConversation(id: String): Flow<List<MessageEntity>>
  suspend fun updateMediaStatus(transferId: String, status: String, localPath: String?)

Prompt M-5 — Chat Screen: Media Bubbles + Picker
Plan Mode
In app/ui/chat/, update ChatScreen and ChatViewModel to support
picking, sending, and displaying image and video messages.

─── ChatViewModel update ──────────────────────────────────────
Add to ChatViewModel:

  @Inject lateinit var mediaEngine: MediaTransferEngine
  @Inject lateinit var processor: MediaProcessor
  @Inject lateinit var transport: DualTransportManager

  // Collect incoming media transfer events
  init {
    viewModelScope.launch {
      mediaEngine.transferFlow
        .filter { it.conversationId == conversationId }
        .collect { transfer ->
          // Update message in Room with new status
          chatRepo.updateMediaTransfer(transfer)
        }
    }
  }

  fun onImagePicked(uri: Uri) {
    viewModelScope.launch {
      // Validate size before sending
      val mode = transport.modeFlow.value
      val sizeLimit = if (mode == TransportMode.BLUETOOTH)
        MediaLimits.IMAGE_MAX_BYTES_BT else MediaLimits.IMAGE_MAX_BYTES_WIFI

      val size = context.contentResolver
        .openAssetFileDescriptor(uri, "r")?.length ?: 0L

      if (size > sizeLimit) {
        _uiState.update { it.copy(
          error = "Image too large for ${mode.name}. Max ${sizeLimit/1024/1024}MB"
        )}
        return@launch
      }

      mediaEngine.sendMedia(
        uri = uri,
        mediaType = MediaType.IMAGE,
        dstUsername = peerUsername,
        mode = mode,
        key = cryptoRepo.getCurrentKey(),
      )
    }
  }

  fun onVideoPicked(uri: Uri) {
    viewModelScope.launch {
      if (!MediaLimits.isVideoAllowed(transport.modeFlow.value)) {
        _uiState.update { it.copy(
          error = "Video sharing requires WiFi. Switch in Network screen."
        )}
        return@launch
      }
      mediaEngine.sendMedia(
        uri = uri,
        mediaType = MediaType.VIDEO,
        dstUsername = peerUsername,
        mode = transport.modeFlow.value,
        key = cryptoRepo.getCurrentKey(),
      )
    }
  }

  fun onCancelTransfer(transferId: String) {
    mediaEngine.cancelOutgoing(transferId)
  }

─── ChatScreen.kt updates ─────────────────────────────────────

1. Attach button in input bar:
   Replace single send button row with:
   [Attach 📎] [TextField message...] [Send ↑]

   Attach icon taps → show ModalBottomSheet:
     ┌─────────────────────────────┐
     │  📷  Photo                  │
     │  🎥  Video                  │  ← grayed + tooltip if BT mode
     │  🖼️  Choose from gallery    │
     └─────────────────────────────┘
   Use ActivityResultContracts.PickVisualMedia() (Photo Picker API)
   for gallery. Use TakePicture for camera.

2. Media bubble composable (MediaMessageBubble):
   Replaces TextBubble when message has mediaTransferId.

   OUTGOING / status = Progress:
     ┌──────────────────────────┐
     │ [blurred thumbnail]      │
     │ ████████░░░░  68%        │ ← LinearProgressIndicator
     │ Sending... [Cancel ✕]   │
     └──────────────────────────┘

   INCOMING / status = Pending or Progress:
     ┌──────────────────────────┐
     │ [blurred thumbnail]      │
     │ ░░░░░░████████  42%      │
     │ Receiving...             │
     └──────────────────────────┘

   status = Verifying:
     Show thumbnail + "Verifying..." + spinner

   status = Complete + IMAGE:
     ┌──────────────────────────┐
     │ [sharp thumbnail 200dp]  │  ← tap → full screen viewer
     └──────────────────────────┘
     Under bubble: timestamp + delivery ticks

   status = Complete + VIDEO:
     ┌──────────────────────────┐
     │ [thumbnail]   ▶          │  ← tap → VideoPlayer screen
     │               0:24       │
     └──────────────────────────┘

   status = Failed:
     ┌──────────────────────────┐
     │ [grey placeholder]       │
     │ Transfer failed  ↺ Retry │
     └──────────────────────────┘

   status = Cancelled:
     Single line: "Transfer cancelled"  (dim, italic)

3. Thumbnail loading:
   Use remembered state — decode base64 thumbnail to Bitmap
   ONCE and cache in remember(transferId) {} block.
   Never decode in composition — always in LaunchedEffect.

   val thumbnailBitmap by produceState<Bitmap?>(null, transferId) {
     value = withContext(Dispatchers.Default) {
       val bytes = Base64.decode(thumbnailBase64, Base64.NO_WRAP)
       BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
     }
   }

Prompt M-6 — Full Screen Viewer + Video Player
Fast Mode
In app/ui/media/, build the full-screen image viewer and
video player screens.

─── ImageViewerScreen.kt ──────────────────────────────────────
@Composable
fun ImageViewerScreen(
  localPath: String,
  senderName: String,
  timestamp: Long,
  onBack: () -> Unit,
  onSaveToGallery: () -> Unit,
) {
  // Edge-to-edge, black background
  Box(Modifier.fillMaxSize().background(Color.Black)) {

    // Zoomable image using Modifier.graphicsLayer + transformable
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
      scale.value = (scale.value * zoomChange).coerceIn(0.5f, 5f)
      offset.value += panChange
    }

    AsyncImage(    // use Coil
      model = ImageRequest.Builder(LocalContext.current)
        .data(File(localPath))
        .crossfade(true)
        .build(),
      contentDescription = "Shared image from $senderName",
      modifier = Modifier
        .fillMaxSize()
        .transformable(state = state)
        .graphicsLayer(
          scaleX = scale.value, scaleY = scale.value,
          translationX = offset.value.x, translationY = offset.value.y
        ),
      contentScale = ContentScale.Fit,
    )

    // Top bar (fades on double-tap)
    TopAppBar(
      title = {
        Column {
          Text(senderName, style = MaterialTheme.typography.titleMedium,
            color = Color.White)
          Text(formatTimestamp(timestamp), style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f))
        }
      },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
        }
      },
      actions = {
        IconButton(onClick = onSaveToGallery) {
          Icon(Icons.Filled.Download, "Save to gallery", tint = Color.White)
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Black.copy(alpha = 0.5f)
      )
    )
  }
}

─── VideoPlayerScreen.kt ──────────────────────────────────────
Use AndroidView with ExoPlayer
(androidx.media3:media3-exoplayer + media3-ui):

@Composable
fun VideoPlayerScreen(
  localPath: String,
  senderName: String,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val exoPlayer = remember {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(Uri.fromFile(File(localPath))))
      prepare()
      playWhenReady = true
    }
  }
  DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        PlayerView(ctx).apply {
          player = exoPlayer
          useController = true
          setShowNextButton(false)
          setShowPreviousButton(false)
        }
      }
    )
    IconButton(
      onClick = onBack,
      modifier = Modifier.padding(WindowInsets.statusBars.asPaddingValues())
        .align(Alignment.TopStart)
    ) {
      Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
    }
  }
}

─── Save to gallery ───────────────────────────────────────────
fun saveImageToGallery(context: Context, localPath: String): Result<Unit> {
  return runCatching {
    val file = File(localPath)
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeshChat")
    }
    val uri = context.contentResolver.insert(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
    ) ?: throw IOException("Failed to create MediaStore entry")
    context.contentResolver.openOutputStream(uri)?.use { out ->
      file.inputStream().copyTo(out)
    }
  }
}
Note: saving to gallery requires READ_MEDIA_IMAGES (Android 13+)
or WRITE_EXTERNAL_STORAGE (pre-13). Request only when user taps
"Save" — never proactively.

Prompt M-7 — Permissions + Limits UX
Fast Mode
In app/, add all media-related permissions and limit guardrails.

─── AndroidManifest.xml additions ─────────────────────────────
  <!-- Camera -->
  <uses-permission android:name="android.permission.CAMERA"/>

  <!-- Gallery read (Android 13+) -->
  <uses-permission android:name="android.permission.READ_MEDIA_IMAGES"
    tools:targetApi="tiramisu"/>
  <uses-permission android:name="android.permission.READ_MEDIA_VIDEO"
    tools:targetApi="tiramisu"/>

  <!-- Gallery read (pre-13) -->
  <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>

  <!-- Save to gallery (pre-29) -->
  <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28"/>

  <!-- ExoPlayer / media3 -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"
    android:foregroundServiceType="mediaPlayback"/>

  <!-- FileProvider for exports -->
  <provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
      android:name="android.support.FILE_PROVIDER_PATHS"
      android:resource="@xml/file_provider_paths"/>
  </provider>

res/xml/file_provider_paths.xml:
  <paths>
    <files-path name="media" path="media/"/>
    <cache-path name="media_cache" path="media_incoming/"/>
  </paths>

─── Limit guardrail UX ────────────────────────────────────────
When user picks a file that exceeds limits, show a descriptive
SnackBar (not a dialog — non-blocking):

  Image too large on BT:
    "Image is 8MB. Max 5MB on Bluetooth. Switch to WiFi or choose
     a smaller image."  [Switch to WiFi]

  Video on BT:
    "Video sharing requires WiFi. Switch in the Network screen."
     [Switch Now]

  Video too long:
    "Video is 45s. Max 30 seconds allowed."

  File picker: use MimeType filter so user only sees valid types:
    PickVisualMedia.ImageAndVideo for WiFi mode
    PickVisualMedia.ImageOnly for Bluetooth mode

─── WorkManager cleanup job ───────────────────────────────────
Schedule weekly cleanup of stale temp files:

class MediaCleanupWorker(ctx: Context, params: WorkerParameters)
  : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    mediaFileStorage.cleanupStale()
    return Result.success()
  }
}

// In Application.onCreate():
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
  "media_cleanup",
  ExistingPeriodicWorkPolicy.KEEP,
  PeriodicWorkRequestBuilder<MediaCleanupWorker>(7, TimeUnit.DAYS).build()
)

Prompt M-8 — Buffer + Deadlock fixes for media pipeline
Fast Mode
Apply all concurrency fixes from prompts F-1 through F-7
specifically to the new media transfer pipeline.

─── MediaTransferEngine backpressure audit ────────────────────
1. chunkAckChannel must use capacity=1 for strict BT backpressure
   (not 4 — one outstanding chunk at a time prevents overflow):
     val chunkAckChannel = Channel<Int>(capacity = 1)

2. onChunkReceived() runs on binder thread via PayloadCallback.
   It MUST NOT suspend. Use trySend() not send():
     chunkTransfer.chunkAckChannel.trySend(chunk.chunkIndex)
     // if full: sender timeout handles retry

3. incoming map — use ConcurrentHashMap (same fix as F-2):
     private val incoming = ConcurrentHashMap<String, IncomingTransfer>()
     private val outgoing = ConcurrentHashMap<String, OutgoingTransfer>()

4. tmpFile write — use FileChannel with explicit position to support
   out-of-order chunks safely:
     RandomAccessFile(transfer.tmpFile, "rw").use { raf ->
       raf.seek(chunk.chunkIndex.toLong() * header.chunkSizeBytes)
       raf.write(decrypted)
     }
   Wrap in Dispatchers.IO — never on Main or Default.

5. SHA-256 verification — runs on Dispatchers.IO only:
     val checksum = withContext(Dispatchers.IO) {
       processor.sha256(transfer.tmpFile.readBytes())
     }
   File can be large — never block coroutine on Default dispatcher.

6. Scope for MediaTransferEngine — tie to MeshForegroundService
   scope exactly like MeshNode (from F-7):
     fun start(scope: CoroutineScope) {
       scope.launch { meshNode.mediaPacketFlow.collect { onPacket(it) } }
     }
   No internal CoroutineScope — borrows service scope.

7. transferFlow buffer — use DROP_OLDEST for progress events
   (losing a 68% progress event is acceptable — losing 100% is not):
     private val _transferFlow = MutableSharedFlow<MediaTransfer>(
       replay = 1,              // replay last status for new collectors
       extraBufferCapacity = 64,
       onBufferOverflow = BufferOverflow.DROP_OLDEST
     )

─── Tests ─────────────────────────────────────────────────────
- testChunkDeliveryOrderIndependence()
    Send 50 chunks out of order via FakeTransport, verify file matches
- testTransferCancelMidway()
    Cancel at chunk 25 of 50, verify temp file deleted
- testChecksumMismatchRejected()
    Corrupt chunk 10, verify FAILED status emitted
- testBtBackpressureBlocksSender()
    Verify sender suspends waiting for ACK on BT mode
- testWifiSendsAllChunksParallel()
    Verify WiFi mode does NOT wait for per-chunk ACK

Full build order for media feature
M-1   Packet types + domain models
M-2   MediaProcessor (compress, thumbnail, chunk, encrypt)
M-3   MediaTransferEngine (send + receive + backpressure)
M-4   MediaFileStorage + Room entity update
M-5   Chat screen: media bubbles + picker UI
M-6   Full screen image viewer + video player
M-7   Permissions + size limits + cleanup worker
M-8   Concurrency fixes for media pipeline

Dependencies to add to libs.versions.toml:
  io.coil-kt:coil-compose              ← image loading
  androidx.media3:media3-exoplayer     ← video playback
  androidx.media3:media3-ui            ← PlayerView
  androidx.work:work-runtime-ktx       ← cleanup workerAlso a voice talky featureArchitected real-time voice streaming with codec compression and latency optimizationArchitected real-time voice streaming with codec compression and latency optimizationVoice over mesh is the hardest feature in the whole app — real-time audio has zero tolerance for the buffering strategies we used for media. Every design decision is different. Let me map the full pipeline first.Created a file, read a fileCreated a file, read a fileFull pipeline diagram above. Now the prompts:

Prompt V-1 — Voice Packet Types + Domain Models
Plan Mode
In core/, add all voice packet types. Voice uses a completely
separate packet pipeline from text and media — lower latency,
no ACK, no retry, no encryption overhead on frame path.

─── Add to PacketType enum ────────────────────────────────────
  VOICE_START,   // sender begins PTT — init decoder on receiver
  VOICE_FRAME,   // one 20ms Opus-encoded audio frame
  VOICE_END,     // sender releases PTT — drain jitter buffer
  VOICE_BUSY,    // peer is already transmitting — reject new PTT

─── VoiceStartPayload.kt ──────────────────────────────────────
@Serializable
data class VoiceStartPayload(
  val sessionId: String,     // UUID — unique per PTT press
  val senderUsername: String,
  val senderNodeId: String,
  val conversationId: String,
  val sampleRate: Int = 16000,
  val channels: Int = 1,
  val opusBitrate: Int,      // 8000 for BT, 24000 for WiFi
  val timestamp: Long,
)

─── VoiceFramePayload.kt ──────────────────────────────────────
@Serializable
data class VoiceFramePayload(
  val sessionId: String,
  val seq: Int,              // monotonically increasing per session
  val timestampMs: Int,      // RTP-style: samples since session start
  val opusData: String,      // base64 Opus-encoded frame bytes
  // NO authTag on voice frames — latency budget forbids it
  // Session-level auth done in VOICE_START only
)

─── VoiceEndPayload.kt ────────────────────────────────────────
@Serializable
data class VoiceEndPayload(
  val sessionId: String,
  val finalSeq: Int,         // highest seq sent — receiver knows if gap
  val durationMs: Long,
)

─── VoiceSession.kt (domain model) ────────────────────────────
data class VoiceSession(
  val sessionId: String,
  val senderUsername: String,
  val conversationId: String,
  val state: VoiceSessionState,
  val durationMs: Long,
  val timestamp: Long,
  val isOutgoing: Boolean,
)

sealed class VoiceSessionState {
  object Transmitting : VoiceSessionState()  // PTT held
  object Receiving    : VoiceSessionState()  // peer transmitting
  data class Completed(val durationMs: Long) : VoiceSessionState()
  object Busy         : VoiceSessionState()  // channel occupied
}

─── VoiceBitratePolicy.kt ─────────────────────────────────────
object VoiceBitratePolicy {
  const val SAMPLE_RATE       = 16_000   // 16kHz — wideband voice
  const val FRAME_SIZE_SAMPLES = 320     // 20ms at 16kHz
  const val FRAME_SIZE_BYTES  = 640      // 16-bit PCM per frame
  const val CHANNELS          = 1        // mono

  fun opusBitrate(mode: TransportMode) = when (mode) {
    TransportMode.BLUETOOTH -> 8_000    // 8kbps — fits BT MTU easily
    TransportMode.WIFI      -> 24_000   // 24kbps — clear wideband
    TransportMode.BOTH      -> 24_000
  }

  const val JITTER_MIN_MS     = 60      // 3 frames minimum buffer
  const val JITTER_MAX_MS     = 120     // 6 frames maximum
  const val PACKET_TIMEOUT_MS = 200     // drop if older than this
  const val MAX_HOP_VOICE     = 1       // voice never relays > 1 hop
}

Prompt V-2 — Opus Codec Integration
Plan Mode
In core/, integrate the Opus audio codec for voice encoding
and decoding. Use Android's built-in MediaCodec for Opus — no
native .so library needed on API 29+.

─── Add to libs.versions.toml ─────────────────────────────────
No new dependency needed — MediaCodec is part of Android framework.
However add for fallback testing:
  // Only if targeting API < 29:
  // com.github.theeasiestway:android-opus-codec:1.0.5

─── OpusEncoder.kt ────────────────────────────────────────────
class OpusEncoder(private val bitrate: Int) {

  private var codec: MediaCodec? = null
  private val inputBuffers = mutableListOf<Pair<Int, ByteArray>>()

  fun start() {
    codec = MediaCodec.createEncoderByType("audio/opus").apply {
      val format = MediaFormat.createAudioFormat(
        "audio/opus", VoiceBitratePolicy.SAMPLE_RATE,
        VoiceBitratePolicy.CHANNELS
      ).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
          VoiceBitratePolicy.FRAME_SIZE_BYTES * 2)
        setInteger(MediaFormat.KEY_LATENCY, 0)  // low-latency mode
      }
      configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      start()
    }
  }

  // Encode one 20ms PCM frame → Opus bytes
  // Called from AudioRecord thread — must be fast, no suspend
  fun encodePcmFrame(pcm: ByteArray): ByteArray? {
    val c = codec ?: return null
    val inIdx = c.dequeueInputBuffer(0L)  // non-blocking
    if (inIdx < 0) return null
    val inBuf = c.getInputBuffer(inIdx) ?: return null
    inBuf.clear()
    inBuf.put(pcm)
    c.queueInputBuffer(inIdx, 0, pcm.size,
      System.nanoTime() / 1000, 0)

    val info = MediaCodec.BufferInfo()
    val outIdx = c.dequeueOutputBuffer(info, 0L)
    if (outIdx < 0) return null
    val outBuf = c.getOutputBuffer(outIdx) ?: return null
    val encoded = ByteArray(info.size)
    outBuf.get(encoded)
    c.releaseOutputBuffer(outIdx, false)
    return encoded
  }

  fun stop() {
    codec?.stop()
    codec?.release()
    codec = null
  }
}

─── OpusDecoder.kt ────────────────────────────────────────────
class OpusDecoder {

  private var codec: MediaCodec? = null

  fun start(format: MediaFormat) {
    codec = MediaCodec.createDecoderByType("audio/opus").apply {
      configure(format, null, null, 0)
      start()
    }
  }

  // Decode Opus bytes → PCM frame
  // Returns silence (zero bytes) if opusData is null — PLC simulation
  fun decodeFrame(opusData: ByteArray?): ByteArray {
    val c = codec ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)

    if (opusData == null) {
      // Packet Loss Concealment: return previous frame repeated
      // or zero-filled silence — simple PLC for prototype
      return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
    }

    val inIdx = c.dequeueInputBuffer(0L)
    if (inIdx < 0) return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
    val inBuf = c.getInputBuffer(inIdx) ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
    inBuf.clear()
    inBuf.put(opusData)
    c.queueInputBuffer(inIdx, 0, opusData.size,
      System.nanoTime() / 1000, 0)

    val info = MediaCodec.BufferInfo()
    val outIdx = c.dequeueOutputBuffer(info, 2L)  // 2ms max wait
    if (outIdx < 0) return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
    val outBuf = c.getOutputBuffer(outIdx) ?: return ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)
    val pcm = ByteArray(info.size)
    outBuf.get(pcm)
    c.releaseOutputBuffer(outIdx, false)
    return pcm
  }

  fun stop() {
    codec?.stop()
    codec?.release()
    codec = null
  }
}

─── Unit tests ────────────────────────────────────────────────
- testEncodeThenDecodeRoundtrip()
    Generate sine wave PCM → encode → decode → verify shape preserved
- testDecodeNullReturnsSilence()
    Null input to decoder returns non-null ByteArray of correct size
- testEncoderProducesSmallFrames()
    Encoded frame < 100 bytes at 8kbps, < 200 bytes at 24kbps

Prompt V-3 — JitterBuffer
Plan Mode
In core/, implement the adaptive jitter buffer that smooths
out-of-order and late voice frame delivery.

─── JitterBuffer.kt ───────────────────────────────────────────
class JitterBuffer(
  private val minDepthMs: Int = VoiceBitratePolicy.JITTER_MIN_MS,
  private val maxDepthMs: Int = VoiceBitratePolicy.JITTER_MAX_MS,
) {
  // Sorted by seq number
  private val mutex = Mutex()
  private val buffer = TreeMap<Int, VoiceFramePayload>()
  private var nextExpectedSeq = 0
  private var targetDepthMs = minDepthMs
  private var consecutiveLate = 0
  private var consecutiveOnTime = 0

  // Push incoming frame — called from network receive thread
  // Returns true if buffer has enough frames to start playback
  suspend fun push(frame: VoiceFramePayload): Boolean = mutex.withLock {
    val ageMs = System.currentTimeMillis() - (frame.timestampMs.toLong())
    if (ageMs > VoiceBitratePolicy.PACKET_TIMEOUT_MS) {
      return@withLock false   // too old — discard silently
    }
    buffer[frame.seq] = frame

    // Adaptive: if frames arriving late, increase buffer depth
    if (frame.seq < nextExpectedSeq) {
      consecutiveLate++
      consecutiveOnTime = 0
      if (consecutiveLate > 3) {
        targetDepthMs = (targetDepthMs + 20).coerceAtMost(maxDepthMs)
        consecutiveLate = 0
      }
    } else {
      consecutiveOnTime++
      consecutiveLate = 0
      if (consecutiveOnTime > 10) {
        targetDepthMs = (targetDepthMs - 10).coerceAtLeast(minDepthMs)
        consecutiveOnTime = 0
      }
    }

    val bufferedFrames = buffer.size
    val bufferedMs = bufferedFrames * 20  // 20ms per frame
    return@withLock bufferedMs >= targetDepthMs
  }

  // Pull next frame for playback — called from AudioTrack thread
  // Returns null (→ PLC) if next seq missing
  suspend fun pull(): ByteArray? = mutex.withLock {
    val frame = buffer.remove(nextExpectedSeq)
    nextExpectedSeq++
    return@withLock frame?.let {
      Base64.decode(it.opusData, Base64.NO_WRAP)
    }
    // null triggers Packet Loss Concealment in decoder
  }

  // How many frames currently buffered
  suspend fun size(): Int = mutex.withLock { buffer.size }

  suspend fun clear() = mutex.withLock {
    buffer.clear()
    nextExpectedSeq = 0
  }
}

Prompt V-4 — VoiceEngine (Capture + Playback)
Plan Mode
In core/, implement VoiceEngine — the central class that manages
AudioRecord capture, Opus encode, packet send, Opus decode,
jitter buffer, and AudioTrack playback.

─── VoiceEngine.kt ────────────────────────────────────────────
class VoiceEngine @Inject constructor(
  private val meshNode: MeshNode,
  private val transport: DualTransportManager,
) {
  private val _sessionFlow = MutableSharedFlow<VoiceSession>(
    replay = 1, extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )
  val sessionFlow: Flow<VoiceSession> = _sessionFlow.asSharedFlow()

  // Current active session
  private var activeSession: String? = null
  private var isTransmitting = false

  // ─── TRANSMIT (PTT pressed) ─────────────────────────────
  suspend fun startTransmitting(
    dstUsername: String,
    conversationId: String,
    scope: CoroutineScope,
  ) {
    if (isTransmitting) return
    if (activeSession != null) {
      // Someone else is speaking — reject
      _sessionFlow.tryEmit(VoiceSession(
        sessionId = "", senderUsername = "",
        conversationId = conversationId,
        state = VoiceSessionState.Busy,
        durationMs = 0, timestamp = System.currentTimeMillis(),
        isOutgoing = false,
      ))
      return
    }

    val sessionId = UUID.randomUUID().toString()
    val mode = transport.modeFlow.value
    val bitrate = VoiceBitratePolicy.opusBitrate(mode)
    isTransmitting = true
    activeSession = sessionId

    // Send VOICE_START
    meshNode.send(
      dst = dstUsername,
      payload = Json.encodeToString(VoiceStartPayload(
        sessionId = sessionId,
        senderUsername = meshNode.localUsername,
        senderNodeId = meshNode.localNodeId,
        conversationId = conversationId,
        opusBitrate = bitrate,
        timestamp = System.currentTimeMillis(),
      )).encodeToByteArray(),
      type = PacketType.VOICE_START,
    )

    val encoder = OpusEncoder(bitrate).also { it.start() }

    // Request RECORD_AUDIO focus
    val audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      VoiceBitratePolicy.SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      VoiceBitratePolicy.FRAME_SIZE_BYTES * 4,
    )

    val startTime = System.currentTimeMillis()
    var seq = 0
    val pcmBuffer = ByteArray(VoiceBitratePolicy.FRAME_SIZE_BYTES)

    _sessionFlow.tryEmit(VoiceSession(
      sessionId = sessionId, senderUsername = meshNode.localUsername,
      conversationId = conversationId, state = VoiceSessionState.Transmitting,
      durationMs = 0, timestamp = startTime, isOutgoing = true,
    ))

    // Capture loop — runs on dedicated audio thread
    scope.launch(Dispatchers.Default + CoroutineName("VoiceCapture")) {
      audioRecord.startRecording()
      try {
        while (isTransmitting && isActive) {
          val read = audioRecord.read(pcmBuffer, 0,
            VoiceBitratePolicy.FRAME_SIZE_BYTES)
          if (read <= 0) continue

          val opus = encoder.encodePcmFrame(pcmBuffer) ?: continue
          val timestampMs = (System.currentTimeMillis() - startTime).toInt()

          // Fire and forget — no ACK, no buffer wait
          meshNode.send(
            dst = dstUsername,
            payload = Json.encodeToString(VoiceFramePayload(
              sessionId = sessionId, seq = seq++,
              timestampMs = timestampMs,
              opusData = Base64.encodeToString(opus, Base64.NO_WRAP),
            )).encodeToByteArray(),
            type = PacketType.VOICE_FRAME,
          )
        }
      } finally {
        audioRecord.stop()
        audioRecord.release()
        encoder.stop()
      }
    }
  }

  // PTT released
  suspend fun stopTransmitting(dstUsername: String, conversationId: String) {
    isTransmitting = false
    val sessionId = activeSession ?: return
    val duration = System.currentTimeMillis()

    meshNode.send(
      dst = dstUsername,
      payload = Json.encodeToString(VoiceEndPayload(
        sessionId = sessionId, finalSeq = -1,
        durationMs = duration,
      )).encodeToByteArray(),
      type = PacketType.VOICE_END,
    )

    activeSession = null
    _sessionFlow.tryEmit(VoiceSession(
      sessionId = sessionId, senderUsername = meshNode.localUsername,
      conversationId = conversationId,
      state = VoiceSessionState.Completed(duration),
      durationMs = duration, timestamp = System.currentTimeMillis(),
      isOutgoing = true,
    ))
  }

  // ─── RECEIVE ────────────────────────────────────────────
  private val jitterBuffer = JitterBuffer()
  private val decoder = OpusDecoder()
  private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null

  suspend fun onVoiceStart(payload: VoiceStartPayload, scope: CoroutineScope) {
    activeSession = payload.sessionId
    jitterBuffer.clear()
    decoder.start(MediaFormat.createAudioFormat(
      "audio/opus", payload.sampleRate, payload.channels
    ).apply { setInteger(MediaFormat.KEY_BIT_RATE, payload.opusBitrate) })

    // Request AudioFocus
    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build())
      .setAudioFormat(AudioFormat.Builder()
        .setSampleRate(VoiceBitratePolicy.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build())
      .setBufferSizeInBytes(
        VoiceBitratePolicy.FRAME_SIZE_BYTES * 4
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()

    _sessionFlow.tryEmit(VoiceSession(
      sessionId = payload.sessionId,
      senderUsername = payload.senderUsername,
      conversationId = payload.conversationId,
      state = VoiceSessionState.Receiving,
      durationMs = 0, timestamp = payload.timestamp, isOutgoing = false,
    ))

    // Wait for jitter buffer to fill before starting playback
    playbackJob = scope.launch(Dispatchers.Default) {
      while (!jitterBuffer.push(
        VoiceFramePayload(payload.sessionId, 0, 0, "")) && isActive
      ) { delay(5) }
      startPlayback(scope)
    }
  }

  suspend fun onVoiceFrame(frame: VoiceFramePayload) {
    jitterBuffer.push(frame)
  }

  suspend fun onVoiceEnd(payload: VoiceEndPayload, conversationId: String) {
    // Drain remaining buffer
    delay(jitterBuffer.size() * 20L)
    playbackJob?.cancel()
    audioTrack?.stop()
    audioTrack?.release()
    audioTrack = null
    decoder.stop()
    jitterBuffer.clear()
    activeSession = null

    _sessionFlow.tryEmit(VoiceSession(
      sessionId = payload.sessionId, senderUsername = "",
      conversationId = conversationId,
      state = VoiceSessionState.Completed(payload.durationMs),
      durationMs = payload.durationMs,
      timestamp = System.currentTimeMillis(), isOutgoing = false,
    ))
  }

  private fun startPlayback(scope: CoroutineScope) {
    val track = audioTrack ?: return
    track.play()
    scope.launch(Dispatchers.Default + CoroutineName("VoicePlayback")) {
      while (isActive && activeSession != null) {
        val opusBytes = jitterBuffer.pull()      // null = PLC
        val pcm = decoder.decodeFrame(opusBytes) // handles null
        track.write(pcm, 0, pcm.size)
      }
    }
  }
}

Prompt V-5 — Voice UI (PTT Button + Waveform)
Plan Mode
In app/ui/chat/, add voice/PTT UI to ChatScreen.

─── ChatViewModel update ──────────────────────────────────────
Add to ChatViewModel:
  @Inject lateinit var voiceEngine: VoiceEngine

  val voiceSession: StateFlow<VoiceSession?> =
    voiceEngine.sessionFlow
      .filter { it.conversationId == conversationId }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

  fun onPttDown() {
    viewModelScope.launch {
      voiceEngine.startTransmitting(
        dstUsername = peerUsername,
        conversationId = conversationId,
        scope = viewModelScope,
      )
    }
  }

  fun onPttUp() {
    viewModelScope.launch {
      voiceEngine.stopTransmitting(
        dstUsername = peerUsername,
        conversationId = conversationId,
      )
    }
  }

─── ChatScreen PTT UI ─────────────────────────────────────────
Replace the text input bar on long-press mic icon:

Input bar layout:
  [📎 Attach] [TextField...] [🎤 Mic] [↑ Send]

Mic button behavior:
  Tap: nothing (show tooltip "Hold to talk")
  Long-press down: → onPttDown()
  Release:         → onPttUp()

  Use Modifier.pointerInput(Unit) {
    detectTapGestures(
      onLongPress = { viewModel.onPttDown() },
      onPress = {
        awaitRelease()
        viewModel.onPttUp()
      }
    )
  }

─── PTT Active overlay (full-width, above input bar) ──────────
When voiceSession?.state == Transmitting:

  Animated bar slides up from input area:
  ┌─────────────────────────────────────────┐
  │  🔴  TRANSMITTING  ▌▌▌▌▌▌░░▌▌▌  0:03  │
  │  Slide up to cancel                     │
  └─────────────────────────────────────────┘

  - Red pulsing dot (animation: pulse 1s infinite)
  - Live waveform bars: 8 vertical bars whose heights animate
    from random values sampled from AudioRecord amplitude
    Use LaunchedEffect(Transmitting) to poll AudioRecord.maxAmplitude
    every 50ms and animate bar heights
  - Live timer: updates every second
  - Slide up gesture → cancel transmission

When voiceSession?.state == Receiving:

  Green bar:
  ┌─────────────────────────────────────────┐
  │  🟢  Arjun  ▌░▌▌░▌▌░▌  receiving...   │
  └─────────────────────────────────────────┘

  - Waveform animates randomly (can't access peer's amplitude)
  - Mic is DISABLED (half-duplex lock)
  - Show "Hold to queue" tooltip on mic

When voiceSession?.state == Busy:
  Snackbar: "Channel busy — Arjun is speaking"

─── Voice bubble in message list ──────────────────────────────
Completed voice session appears as a message bubble:

  Incoming (left):
  ┌──────────────────────────────┐
  │ 🎤  ▶  ────────────  0:12  │  ← tap ▶ to replay
  └──────────────────────────────┘
  (Voice sessions are NOT saved for replay in v1 —
   tap shows "Voice messages can't be replayed" toast.
   Add recording in v2.)

  Outgoing (right):
  ┌──────────────────────────────┐
  │        0:12  ────────────  🎤│
  │                            ✓✓│
  └──────────────────────────────┘

  VoiceMessageBubble composable:
  @Composable
  fun VoiceMessageBubble(session: VoiceSession, isOutgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(8.dp).widthIn(max = 220.dp)) {
      Icon(Icons.Filled.Mic, contentDescription = "Voice message",
        tint = if (isOutgoing) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      // Waveform placeholder: static decorative bars
      repeat(12) { i ->
        val h = (4 + (i * 7 % 16)).dp
        Box(Modifier.width(2.dp).height(h)
          .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
        Spacer(Modifier.width(2.dp))
      }
      Spacer(Modifier.width(8.dp))
      Text(formatDuration(session.durationMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
  }

Prompt V-6 — Group Voice (Broadcast Channel)
Fast Mode
In app/ui/broadcast/, add a group voice channel to the
Broadcast screen — all nodes hear all transmissions.

─── BroadcastViewModel update ─────────────────────────────────
Add to BroadcastViewModel:
  fun onGroupPttDown() {
    viewModelScope.launch {
      voiceEngine.startTransmitting(
        dstUsername = "BROADCAST",
        conversationId = "group_broadcast",
        scope = viewModelScope,
      )
    }
  }
  fun onGroupPttUp() {
    viewModelScope.launch {
      voiceEngine.stopTransmitting("BROADCAST", "group_broadcast")
    }
  }

─── BroadcastScreen update ────────────────────────────────────
Add large PTT button above the text input:

  ┌────────────────────────────────────────────┐
  │                                            │
  │         🎤  Hold to talk to all           │
  │         (5 nodes on mesh)                  │
  │                                            │
  └────────────────────────────────────────────┘

  Large circular button (80dp):
    Normal:      outlined circle, mic icon, textDim
    Pressed:     filled red circle, mic icon white, pulsing
    Receiving:   green pulse border, mic icon green (locked)

  Modifier.pointerInput — same PTT gesture as ChatScreen

  When someone on the mesh transmits to BROADCAST:
    Show floating banner at top of screen:
      "📡 Arjun is speaking..." with live dot
    All other nodes' PTT buttons are disabled (half-duplex)

  Voice log: show completed voice sessions inline
  in the broadcast message list as VoiceMessageBubble
  with sender name: "[🎤 Arjun — 0:08]"

─── Half-duplex enforcement across ALL screens ────────────────
VoiceEngine must expose:
  val isChannelBusy: StateFlow<Boolean>
    = sessionFlow
        .map { it?.state == VoiceSessionState.Receiving ||
               it?.state == VoiceSessionState.Transmitting }
        .stateIn(...)

Any screen that has a PTT button checks isChannelBusy and
disables the button while true. This prevents two transmissions
simultaneously across any conversation.

Prompt V-7 — AudioFocus + Permissions + Background
Fast Mode
In app/, handle AudioFocus, permissions, and background
audio service for voice transmission.

─── AndroidManifest.xml additions ─────────────────────────────
  <uses-permission android:name="android.permission.RECORD_AUDIO"/>
  <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>

  Update MeshForegroundService to declare mediaPlayback type:
  <service
    android:name=".MeshForegroundService"
    android:foregroundServiceType="microphone|mediaPlayback"
    android:exported="false"/>

─── AudioFocusManager.kt ──────────────────────────────────────
class AudioFocusManager(private val context: Context) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private var focusRequest: AudioFocusRequest? = null

  fun requestVoiceTransmitFocus(onLoss: () -> Unit): Boolean {
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build())
      .setOnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
          onLoss()  // another app (phone call) took focus → stop PTT
        }
      }
      .build()
    focusRequest = req
    return audioManager.requestAudioFocus(req) ==
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  fun abandonFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }
}

─── Speaker mode ──────────────────────────────────────────────
In VoiceEngine.onVoiceStart():
  // Switch to speaker for PTT (walky-talky style)
  audioManager.isSpeakerphoneOn = true
  // Earpiece fallback: user can toggle in Settings

In VoiceEngine.onVoiceEnd():
  audioManager.isSpeakerphoneOn = false

─── RECORD_AUDIO permission request ───────────────────────────
In ChatScreen, when user long-presses mic for FIRST time:
  1. Check RECORD_AUDIO permission
  2. If denied: show rationale sheet:
     "MeshChat needs microphone access to send voice messages
      over the mesh. Audio never leaves your local network."
     [Grant Access] button
  3. If permanently denied: SnackBar with [Open Settings]
  4. Only after granted: proceed with PTT

─── Background voice ──────────────────────────────────────────
If user backgrounds app WHILE TRANSMITTING:
  MeshForegroundService notification updates to:
    "🎤 Transmitting voice to Arjun..."
    [Stop] action button → calls voiceEngine.stopTransmitting()

If user backgrounds WHILE RECEIVING:
  Notification:
    "📡 Arjun is speaking on mesh"
    Play audio through speaker even in background
    (AudioTrack continues via foreground service)

Prompt V-8 — Concurrency Fixes for Voice
Fast Mode
Voice pipeline has unique concurrency requirements different from
text and media. Apply targeted fixes.

─── AudioRecord thread isolation ──────────────────────────────
AudioRecord.read() is a BLOCKING call. It MUST run on a
dedicated thread — not Dispatchers.Default (shared pool).

  Create a named single-thread dispatcher:
    val audioDispatcher = Executors.newSingleThreadExecutor {
      Thread(it, "MeshChat-AudioCapture").also {
        it.priority = Thread.MAX_PRIORITY  // audio needs priority
      }
    }.asCoroutineDispatcher()

  Use this dispatcher for the capture loop:
    scope.launch(audioDispatcher) {
      while (isTransmitting && isActive) {
        audioRecord.read(pcmBuffer, 0, FRAME_SIZE_BYTES)
        ...
      }
    }

─── AudioTrack thread isolation ───────────────────────────────
AudioTrack.write() is also blocking.
  val playbackDispatcher = Executors.newSingleThreadExecutor {
    Thread(it, "MeshChat-AudioPlayback").also {
      it.priority = Thread.MAX_PRIORITY
    }
  }.asCoroutineDispatcher()

  scope.launch(playbackDispatcher) {
    while (isActive) {
      val pcm = jitterBuffer.pull()
      val decoded = decoder.decodeFrame(pcm)
      track.write(decoded, 0, decoded.size)  // blocking
    }
  }

─── Frame channel (AudioRecord → Opus encoder → sender) ───────
Do NOT encode in the capture loop — encoding adds latency jitter.
Use a Channel between capture and encode:

  val captureChannel = Channel<ByteArray>(
    capacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST  // drop old audio, not new
  )

  // Capture coroutine: read PCM → trySend
  scope.launch(audioDispatcher) {
    while (isTransmitting) {
      audioRecord.read(pcmBuffer, 0, FRAME_SIZE_BYTES)
      captureChannel.trySend(pcmBuffer.copyOf())
    }
    captureChannel.close()
  }

  // Encode + send coroutine: receive PCM → encode → fire
  scope.launch(Dispatchers.Default) {
    for (pcm in captureChannel) {
      val opus = encoder.encodePcmFrame(pcm) ?: continue
      meshNode.send(...VOICE_FRAME...)
    }
  }

─── JitterBuffer thread safety ────────────────────────────────
JitterBuffer.push() called from: network receive coroutine
JitterBuffer.pull() called from: playbackDispatcher thread

Both use suspend fun with Mutex — already safe.
But pull() is called from blocking AudioTrack loop:
  MUST use runBlocking { jitterBuffer.pull() } on playback thread.

  Change playback loop to:
    scope.launch(playbackDispatcher) {
      while (isActive) {
        val opusBytes = jitterBuffer.pull()   // suspend — OK on coroutine
        val pcm = decoder.decodeFrame(opusBytes)
        withContext(playbackDispatcher) {
          track.write(pcm, 0, pcm.size)
        }
      }
    }

─── Session state machine — prevent race ──────────────────────
activeSession is accessed from multiple coroutines.
Replace with a StateFlow + atomic operation:

  private val _activeSession = MutableStateFlow<String?>(null)

  // Only one session can be active at a time — compareAndSet:
  fun tryStartSession(sessionId: String): Boolean {
    return _activeSession.compareAndSet(null, sessionId)
  }
  fun clearSession() { _activeSession.value = null }

─── Tests ─────────────────────────────────────────────────────
- testJitterBufferReordersOutOfOrderFrames()
- testCaptureChannelDropsOldFramesUnderLoad()
- testHalfDuplexPreventsSimultaneousTransmit()
- testAudioFocusLossCancelsTransmission()
- testSessionClearedOnPttRelease()

Full build order for voice feature
V-1  Packet types + domain models
V-2  Opus encoder + decoder (MediaCodec)
V-3  JitterBuffer (adaptive, out-of-order safe)
V-4  VoiceEngine (capture + playback + session management)
V-5  Chat screen PTT button + waveform + voice bubble
V-6  Group voice on Broadcast screen
V-7  AudioFocus + RECORD_AUDIO permission + background service
V-8  Concurrency fixes (audio thread isolation + channels)

Add to libs.versions.toml:
  Nothing new — uses Android framework only:
    AudioRecord, AudioTrack, MediaCodec (Opus), AudioManager

Combined feature stack — full build order
── FOUNDATION ──────────────────────────────
Prompt 1–7     Core architecture + security
Prompt T-1–8   Dual transport + username claim
F-1–7          Buffer + deadlock fixes

── MEDIA ───────────────────────────────────
M-1  Packet types
M-2  MediaProcessor
M-3  MediaTransferEngine
M-4  FileStorage + Room
M-5  Chat media bubbles
M-6  Image viewer + video player
M-7  Permissions + cleanup
M-8  Media concurrency fixes

── VOICE ───────────────────────────────────
V-1  Voice packet types
V-2  Opus codec
V-3  JitterBuffer
V-4  VoiceEngine
V-5  PTT button + waveform UI
V-6  Group voice
V-7  AudioFocus + permissions
V-8  Voice concurrency fixes