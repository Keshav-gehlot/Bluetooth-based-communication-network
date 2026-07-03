Prompt 1 — Project Scaffold (Official Architecture)
Plan Mode
Create "MeshChat" as a multi-module Android project following
the official Android app architecture guide exactly.

─── Layer Structure (enforce strictly) ───────────────────────
UI Layer      app/         Jetpack Compose + ViewModels + UDF
Domain Layer  domain/      UseCases, domain models, interfaces
Data Layer    data/        Repositories, Room, DataStore, Nearby
Core          core/        Pure Kotlin/KMP — Packet, Node, Crypto, IDS
Transports    transports/  NearbyConnectionsTransport, BleTransport

─── Architecture Rules (from developer.android.com/topic/architecture) ───
- Unidirectional Data Flow in every ViewModel: events flow down,
  state flows up. No two-way bindings.
- Every screen has exactly ONE UiState sealed class:
    sealed class ScreenUiState {
      object Loading : ScreenUiState()
      data class Success(...) : ScreenUiState()
      data class Error(val message: String) : ScreenUiState()
      object Empty : ScreenUiState()   ← required by core app quality
    }
- ViewModels expose StateFlow<ScreenUiState> only — no LiveData.
- No Android imports in domain/ or core/
- All business logic lives in UseCases in domain/ not in ViewModels
- Repositories in data/ are the single source of truth
- ViewModels only call UseCases — never access repositories directly

─── Modules ───────────────────────────────────────────────────
core/        → Kotlin Multiplatform (commonMain + androidMain)
domain/      → Pure Kotlin (no Android) — UseCases + interfaces
data/        → Android — Room, DataStore, repository impls
transports/  → Android — Nearby, BLE
app/         → Android — Compose UI, ViewModels, Hilt, Navigation

─── Key dependencies (libs.versions.toml) ─────────────────────
androidx.compose.material3              ← Material 3 only (no M2)
androidx.navigation:navigation-compose
androidx.hilt:hilt-navigation-compose
com.google.dagger:hilt-android
androidx.room:room-ktx
androidx.datastore:datastore-preferences
androidx.security:security-crypto        ← EncryptedSharedPreferences
androidx.biometric:biometric-ktx         ← BiometricPrompt
com.google.android.gms:play-services-nearby
androidx.lifecycle:lifecycle-viewmodel-compose
kotlinx.coroutines
kotlinx.serialization.json
androidx.core:core-splashscreen          ← official splash API
androidx.compose.ui:ui-tooling           ← previews

─── AndroidManifest.xml ───────────────────────────────────────
Permissions — declare ONLY what is required (minimum permissions
principle from security docs):
  BLUETOOTH_CONNECT   (runtime, API 31+)
  BLUETOOTH_SCAN      (runtime, API 31+)
  NEARBY_WIFI_DEVICES (runtime, API 33+)
  ACCESS_FINE_LOCATION (runtime, required by Nearby on <API33)

Use android:maxSdkVersion where applicable to limit permission scope.
Add <uses-permission android:name="...BLUETOOTH"
  android:maxSdkVersion="30"/> for backwards compat.

Enable edge-to-edge in MainActivity:
  WindowCompat.setDecorFitsSystemWindows(window, false)
  enableEdgeToEdge()

Handle predictive back gesture:
  Add android:enableOnBackInvokedCallback="true" in manifest.

Prompt 2 — Security Layer (Android Keystore)
Plan Mode
Implement the security layer for MeshChat following the official
Android security checklist at developer.android.com/privacy-and-security/security-tips

─── Key Storage (CRITICAL — use Android Keystore, NOT DataStore) ──
KeyManager.kt (in data/security/):

  Do NOT store cryptographic keys in:
    ✗ SharedPreferences
    ✗ DataStore
    ✗ Room database
    ✗ Any file in app storage

  DO use the Android Keystore System:
    - Generate AES-256-GCM key inside Android Keystore:
        val keyGenerator = KeyGenerator.getInstance(
          KeyProperties.KEY_ALGORITHM_AES,
          "AndroidKeyStore"
        )
        keyGenerator.init(
          KeyGenParameterSpec.Builder(
            "meshchat_mesh_key",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .setUserAuthenticationRequired(false)  // mesh key, not user key
          .build()
        )

    - Derive the room-code key using PBKDF2 and wrap it with the
      Keystore-stored KEK (key-encrypting key) before storing to
      EncryptedSharedPreferences

  RoomCodeManager.kt:
    - Store derived mesh key as encrypted blob in
      EncryptedSharedPreferences (androidx.security:security-crypto)
    - Never log key material (enforce with custom Timber tree that
      redacts anything matching base64 pattern in DEBUG builds)
    - Never put key material in Bundle or Intent extras

─── BiometricPrompt for room code change ──────────────────────
  When user taps "Change Room Code" in Settings:
    - Prompt with BiometricPrompt (fingerprint/face/PIN fallback)
    - Only allow key rotation after successful biometric auth
    - Use BiometricManager.canAuthenticate() to check availability
    - Fallback: device PIN via setDeviceCredentialAllowed(true)

─── Logging rules (from security docs) ────────────────────────
  - In production builds: use no-op Timber tree
  - In debug builds: custom tree that redacts:
      * Any string matching [A-Za-z0-9+/]{32,}={0,2} (base64 keys)
      * nodeId values in sensitive operations
      * Packet payloads
  - Never log: payloads, auth tags, keys, peer locations

─── EncryptedSharedPreferences setup ──────────────────────────
  val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

  val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "meshchat_secure_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
  )
  Store here: wrapped mesh key blob, node identity, room code hash

─── IPC / Intent security ─────────────────────────────────────
  - All BroadcastReceivers for internal mesh events: use
    LocalBroadcastManager or explicit Intents (not implicit)
  - No exported components except MainActivity
  - Add android:exported="false" to all Services and Receivers

Prompt 3 — Domain Layer (UseCases)
Plan Mode
In domain/, implement all business logic as UseCases following
the official Android architecture guide.

Rule: ViewModels may NOT contain business logic. Every operation
goes through a UseCase. UseCases are pure Kotlin, testable with
JUnit5 alone (no Android deps, no Mockito needed).

UseCases to implement:

─── Messaging ─────────────────────────────────────────────────
SendMessageUseCase(
  private val chatRepo: ChatRepository,
  private val cryptoRepo: CryptoRepository
):
  suspend operator fun invoke(text: String, dst: String): Result<Unit>
  - Validates: text not blank, text.length <= 2000
  - Builds ChatPayload, encrypts via cryptoRepo
  - Delegates to chatRepo.sendMessage()
  - Returns Result.success or Result.failure(ValidationException)

SendBroadcastUseCase(chatRepo, cryptoRepo):
  suspend operator fun invoke(text: String): Result<Unit>

ObserveConversationUseCase(chatRepo):
  operator fun invoke(conversationId: String): Flow<List<Message>>

ObserveConversationsUseCase(chatRepo):
  operator fun invoke(): Flow<List<Conversation>>

─── Peers ─────────────────────────────────────────────────────
ObservePeersUseCase(peerRepo):
  operator fun invoke(): Flow<List<Peer>>

─── Identity ──────────────────────────────────────────────────
GetIdentityUseCase(identityRepo):
  operator fun invoke(): Flow<UserIdentity>

UpdateDisplayNameUseCase(identityRepo):
  suspend operator fun invoke(name: String): Result<Unit>
  - Validates: name.length in 1..24, not blank, no special chars

ChangeRoomCodeUseCase(identityRepo, cryptoRepo):
  suspend operator fun invoke(newCode: String): Result<Unit>
  - Validates: code is exactly 6 alphanumeric chars
  - Triggers key rotation via cryptoRepo

─── Domain Models (no Android imports) ───────────────────────
data class Message(
  val id: String, val text: String, val senderId: String,
  val senderName: String, val timestamp: Long,
  val isOutgoing: Boolean, val status: MessageStatus,
  val hopCount: Int, val conversationId: String
)
enum class MessageStatus { SENDING, SENT, DELIVERED, FAILED }

data class Conversation(
  val id: String, val peerId: String, val peerName: String,
  val lastMessage: String, val lastMessageTime: Long, val unreadCount: Int
)

data class Peer(
  val nodeId: String, val displayName: String, val avatarSeed: String,
  val isOnline: Boolean, val hopDistance: Int, val lastSeen: Long
)

data class UserIdentity(
  val nodeId: String, val displayName: String, val avatarSeed: String
)

─── Repository Interfaces (in domain — implemented in data/) ──
interface ChatRepository {
  suspend fun sendMessage(payload: EncryptedPacket): Result<Unit>
  suspend fun sendBroadcast(payload: EncryptedPacket): Result<Unit>
  fun observeConversation(id: String): Flow<List<Message>>
  fun observeAllConversations(): Flow<List<Conversation>>
  suspend fun markConversationRead(conversationId: String)
}

interface PeerRepository {
  fun observePeers(): Flow<List<Peer>>
}

interface IdentityRepository {
  fun observeIdentity(): Flow<UserIdentity>
  suspend fun saveIdentity(identity: UserIdentity)
  suspend fun updateDisplayName(name: String)
}

interface CryptoRepository {
  fun encryptMessage(text: String): EncryptedPacket
  fun decryptMessage(packet: EncryptedPacket): Result<String>
  suspend fun rotateRoomKey(newCode: String)
}

Prompt 4 — Compose UI (Material 3 + Edge-to-Edge)
Plan Mode
In app/, implement all Compose screens following:
  - developer.android.com/design/ui/mobile (Material 3)
  - developer.android.com/docs/quality-guidelines/core-app-quality

─── Theme (MeshChatTheme.kt) ──────────────────────────────────
Use Material 3 only. No Material 2 imports anywhere.

val MeshChatColorScheme = darkColorScheme(
  primary        = Color(0xFF00E87A),   // green
  onPrimary      = Color(0xFF000000),
  primaryContainer    = Color(0xFF00843F),
  surface        = Color(0xFF111411),
  surfaceVariant = Color(0xFF1E241E),
  background     = Color(0xFF090C0A),
  onBackground   = Color(0xFFF0F7F0),
  secondary      = Color(0xFF00D4FF),
  tertiary       = Color(0xFFFFAA00),
  error          = Color(0xFFFF3355),
)

Typography: use Material 3 Typography with:
  displayLarge  → Orbitron 900  (app name only)
  titleLarge    → DM Sans 700
  bodyLarge     → DM Sans 400
  labelSmall    → Share Tech Mono (node IDs only)

Apply theme in MainActivity with:
  MeshChatTheme(darkTheme = true, dynamicColor = false) { ... }

─── Navigation ────────────────────────────────────────────────
Use NavigationBar (Material 3) not BottomNavigation (M2).
4 top-level destinations: Chats, Group, Network, Settings.
Use NavHost with typed routes (Kotlin Serializable objects).

Handle predictive back: use BackHandler where needed.
Use Scaffold with contentWindowInsets = WindowInsets.safeDrawing.

─── Screen Quality Requirements (core app quality) ────────────
EVERY screen must handle ALL four states:
  Loading  → CircularProgressIndicator centered, no content
  Success  → actual content
  Error    → full-screen error UI with retry button
  Empty    → illustrated empty state with CTA

Minimum touch targets: 48.dp for ALL interactive elements.
(Use Modifier.minimumInteractiveComponentSize() from Material 3)

─── Accessibility ─────────────────────────────────────────────
ALL Icon buttons: contentDescription non-null
ALL custom composables: semantics { } block
Messages: semantics role = Role.None, stateDescription for status
Avatar composables: contentDescription = "$name's avatar"

─── Configuration Changes ─────────────────────────────────────
ViewModels survive config changes — never store UI state in
Activity or composable local var that isn't rememberSaveable.
Use rememberSaveable for: scroll position, input text, selected tab.

─── Screens to implement ──────────────────────────────────────

1. ChatsScreen
   ViewModel: ChatsViewModel
     state: StateFlow<ChatsUiState>
     UseCases: ObserveConversationsUseCase, ObservePeersUseCase
   UiState:
     Loading / Empty("No peers nearby yet") / Success(conversations, onlinePeers) / Error

   UI:
     Scaffold + TopAppBar (CenterAlignedTopAppBar M3)
     "Chats" + online peer count badge
     LazyColumn of ConversationCard composables
     Each card: Avatar, name, last message, time, unread badge, hop badge
     Peer online dot: primary color (1 hop), tertiary (2 hops), outline (offline)
     All touch targets >= 48.dp

2. ChatScreen(conversationId, peerId, peerName)
   ViewModel: ChatViewModel (SavedStateHandle)
     state: StateFlow<ChatUiState>
     UseCases: ObserveConversationUseCase, SendMessageUseCase
   UiState:
     Loading / Success(messages, isTyping, peerOnline, peerHops) / Error

   UI:
     TopAppBar with back arrow + peer avatar + peer name + hop status
     Encryption chip below TopAppBar
     LazyColumn (reverse=false, scroll-to-bottom on new message)
     Outgoing bubble: surfaceVariant with primary border
     Incoming bubble: surfaceVariant
     Delivery status: single tick (sent), double tick in secondary (delivered)
     "Via N hops" label in labelSmall
     Typing indicator: 3 animated dots
     TextField + send IconButton (>=48dp tap target)

3. BroadcastScreen
   ViewModel: BroadcastViewModel
     UseCases: ObserveBroadcastsUseCase, SendBroadcastUseCase
   UI similar to ChatScreen but left-bordered cards

4. NetworkScreen
   ViewModel: NetworkViewModel
     UseCases: ObservePeersUseCase, GetIdentityUseCase
   Large peer count stat + peer list + encryption badge
   Room code chip with copy-to-clipboard

5. SettingsScreen
   ViewModel: SettingsViewModel
     UseCases: GetIdentityUseCase, UpdateDisplayNameUseCase,
               ChangeRoomCodeUseCase
   Sections using Material 3 ListItem composable:
     PROFILE / MESH / SECURITY / ABOUT / DANGER ZONE
   IDS toggle: Switch (M3)
   Max Hops: SegmentedButton (M3)
   BiometricPrompt triggered on "Change Room Code"

Prompt 5 — Kotlin Multiplatform Core
Plan Mode
Migrate core/ to Kotlin Multiplatform following
developer.android.com/kotlin/multiplatform

This lets the mesh protocol logic run on Android AND the Python
bridge side via Kotlin/JVM, making both use the same logic.

─── KMP Module Setup ──────────────────────────────────────────
core/build.gradle.kts:
  plugins { kotlin("multiplatform"); kotlin("plugin.serialization") }

  kotlin {
    androidTarget()
    jvm()           // for Python-bridge JVM runner and unit tests

    sourceSets {
      commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime")
      }
      androidMain.dependencies {
        // Android Keystore usage lives in data/, NOT here
        // core/ stays platform-free
      }
      jvmMain.dependencies {
        // JVM crypto for bridge node
      }
    }
  }

─── expect/actual for Crypto ──────────────────────────────────
In commonMain:
  expect class PlatformCrypto() {
    fun generateKey(): ByteArray
    fun encrypt(data: ByteArray, key: ByteArray): EncryptedBlob
    fun decrypt(blob: EncryptedBlob, key: ByteArray): ByteArray
    fun hmacSign(data: ByteArray, key: ByteArray): ByteArray
    fun hmacVerify(data: ByteArray, tag: ByteArray, key: ByteArray): Boolean
  }

In androidMain:
  actual class PlatformCrypto() {
    // Uses javax.crypto AES/GCM/NoPadding
    // Key wrapping done OUTSIDE this class by Android Keystore in data/
  }

In jvmMain:
  actual class PlatformCrypto() {
    // Uses javax.crypto — same implementation as androidMain
    // For bridge node running on JVM (Raspberry Pi)
  }

─── commonMain classes (fully shared) ────────────────────────
Keep these 100% in commonMain (no expect/actual needed):
  - Packet.kt            (kotlinx.serialization)
  - MeshNode.kt          (coroutines Flow)
  - DeduplicationCache.kt
  - IdsMonitor.kt
  - NodeConfig.kt
  - AnomalyEvent.kt
  - TransportAdapter.kt  (interface)

─── Unit tests in commonTest ─────────────────────────────────
Tests in commonTest run on BOTH androidTest and jvmTest:
  MeshNodeTest:
    - testDeduplicationDropsDuplicate()
    - testHopLimitDropsPacket()
    - testReplayWindowRejectsReused()
    - testRateLimitTriggersAnomaly()
    - testFloodingReachesAllPeers()
  All use FakeTransport from commonTest fixtures.

Prompt 6 — Adaptive Layout + Widget
Plan Mode
Add adaptive layout support and a home screen widget following:
  - developer.android.com/docs/quality-guidelines/adaptive-app-quality
  - developer.android.com/design/ui/widget

─── Adaptive Layout ───────────────────────────────────────────
The app must work on phones AND tablets/foldables.

In app/, use WindowSizeClass from androidx.compose.material3.adaptive:
  val windowSizeClass = calculateWindowSizeClass(activity)

  when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Compact  → single-pane: standard bottom nav
    WindowWidthSizeClass.Medium   → two-pane: NavigationRail + content
    WindowWidthSizeClass.Expanded → two-pane: NavigationDrawer + master-detail
      (Chats list + Chat detail side by side on tablet)
  }

Use NavigationSuiteScaffold (Material 3 adaptive) which automatically
switches between NavigationBar, NavigationRail, NavigationDrawer
based on screen size. Do not hard-code NavigationBar.

Handle foldable hinge: use WindowInfoTracker to detect fold position.
If folded: treat as two phones — show different content per pane.

─── Predictive Back Gesture ──────────────────────────────────
In ChatScreen:
  BackHandler(enabled = true) {
    // animate message list before navigating back
    navController.navigateUp()
  }

Register OnBackPressedCallback for any screen with custom
back behavior (e.g. discard message dialog).

─── Home Screen Widget (MeshStatusWidget) ────────────────────
Create a Glance widget following developer.android.com/design/ui/widget

Widget shows:
  - App icon + "MeshChat" label
  - Online peer count: "● 4 nodes nearby"
  - Last message preview (sender + truncated text, max 40 chars)
  - Mesh status: "Active" (green) or "Searching..." (dim)
  - Tap → opens ChatsScreen

Implementation:
  MeshStatusWidget.kt using androidx.glance:glance-appwidget

  @Composable
  fun MeshStatusWidgetContent(state: MeshWidgetState) {
    GlanceTheme {
      Column(modifier = GlanceModifier
        .fillMaxSize()
        .background(ColorProvider(Color(0xFF111411)))
        .padding(12.dp)
        .cornerRadius(16.dp)
        .appWidgetBackground()
        .clickable(actionStartActivity<MainActivity>())
      ) {
        Text("MeshChat", style = TextStyle(color = ColorProvider(Color(0xFF00E87A))))
        Text("● ${state.onlineCount} nodes nearby", ...)
        Text(state.lastMessagePreview, ...)
      }
    }
  }

  Widget sizes: define res/xml/mesh_widget_info.xml
    minWidth=110dp minHeight=40dp targetCellWidth=2 targetCellHeight=1
    (supports resizable widget: minResizeWidth=110dp)

  Update widget via WorkManager periodic task every 15 minutes
  (minimum interval for PeriodicWorkRequest).

Prompt 7 — Quality Checklist Implementation
Fast Mode
Implement all items from the Android Core App Quality checklist
(developer.android.com/docs/quality-guidelines/core-app-quality)
that apply to MeshChat.

─── Stability ─────────────────────────────────────────────────
No ANRs:
  - All Nearby Connections callbacks: dispatch to Dispatchers.IO
  - Room queries: always on Dispatchers.IO
  - Never do I/O on Main thread — enforce with StrictMode in debug:
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
      )

Crash prevention:
  - Global exception handler in Application class:
      Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception")
        // log to local file only, no network reporting
      }
  - All Result<T> returns handled — no uncaught exceptions from UseCases

─── Performance ───────────────────────────────────────────────
- LazyColumn items: use stable keys (msgId string)
- Avatar composables: remember { avatarColor(seed) }
- No recompositions from non-stable classes: mark all UiState
  data classes with @Stable or @Immutable
- Baseline profiles: generate with Macrobenchmark for
  ChatScreen navigation and message send

─── Permissions ───────────────────────────────────────────────
- All runtime permissions requested with rationale dialog:
    if (shouldShowRequestPermissionRationale(...)) {
      // show AlertDialog explaining WHY before requesting
    }
- Never request permissions on app launch before user action
- Request Bluetooth/Nearby only when user taps "Start Chatting"
- Handle denial gracefully: show SnackBar with "Go to Settings" action

─── Notifications (future-proofing) ──────────────────────────
- Request POST_NOTIFICATIONS permission (Android 13+) only after
  user explicitly enables "message notifications" in Settings
- Use NotificationCompat with CATEGORY_MESSAGE
- Add Person object to notification for conversation shortcuts
- Support notification reply action (RemoteInput)

─── Background Work ───────────────────────────────────────────
- Nearby Connections runs in a foreground Service with
  notification: "MeshChat is maintaining your mesh connection"
  This prevents the OS from killing the mesh mid-session.
  MeshForegroundService.kt:
    startForeground(NOTIF_ID, buildMeshNotification())

- Widget updates: PeriodicWorkRequest via WorkManager (15 min)
- Message delivery retry: OneTimeWorkRequest with backoff

─── Data & Storage ────────────────────────────────────────────
- App-specific storage only (no external storage permissions)
- No sensitive data in app's cache dir (only export files go there temporarily)
- Clear old messages: implement automatic pruning of messages
  older than 30 days via Room scheduled cleanup in WorkManager

─── Empty / Error / Loading states ────────────────────────────
Implement for EVERY screen:

EmptyChatsState:
  Animated radar SVG + "No peers nearby" + "Make sure others
  have MeshChat open and are within Bluetooth range"

ErrorState(message, onRetry):
  Error icon + message text + "Retry" FilledTonalButton (M3)

LoadingState:
  CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
  centered in Box(Modifier.fillMaxSize())

