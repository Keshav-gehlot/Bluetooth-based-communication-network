# MeshChat (Bluetooth-Based Communication Network)

MeshChat is a decentralized, offline-first communication platform built for Android. It leverages Google's Nearby Connections API to create a resilient, peer-to-peer (P2P) mesh network using Bluetooth and Wi-Fi Direct, entirely without the need for cellular data or an internet connection.

## 🚀 Key Features

*   **100% Offline Communication:** Communicate in environments with zero internet access, such as remote locations, disaster zones, or crowded events.
*   **Decentralized Mesh Networking:** Devices automatically discover each other and form a self-healing mesh topology. 
*   **Multi-Hop Routing:** Messages can hop across intermediate devices to reach peers that are out of direct Bluetooth range, effectively extending the network's physical reach.
*   **End-to-End Encryption (E2EE):** Secure communication using robust cryptographic standards to ensure privacy across the decentralized network.
*   **Direct & Broadcast Messaging:** Support for one-on-one direct messages and multi-user broadcast rooms.
*   **Intrusion Detection System (IDS):** Built-in traffic monitoring to detect anomalies, flooding, and suspicious behavior to keep the mesh safe.
*   **Modern UI:** Built fully with Jetpack Compose featuring a beautiful Material Design 3 interface.

## 🏗️ Architecture & Tech Stack

MeshChat adheres to strict **Clean Architecture** principles and uses modern Android development standards. The project is modularized into several layers:

*   **`app` (Presentation Layer):** Contains the Jetpack Compose UI, ViewModels, and navigation logic.
*   **`core` (Mesh Networking Engine):** Contains the `MeshNode` router, which handles multi-hop packet routing, deduplication, and anomaly detection. 
*   **`data` (Data Layer):** Handles local persistence using Room (configured with Write-Ahead Logging for high concurrency), repository implementations, and cryptographic abstractions.
*   **`domain` (Domain Layer):** Pure Kotlin models and UseCases governing the business logic of the messaging network.
*   **`transports` (Hardware Layer):** Implements the physical transport protocols. Currently backed by Google's Nearby Connections API, utilizing highly concurrent Kotlin Coroutines to manage parallel peer transmissions safely.

### Technologies Used
*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material 3)
*   **Concurrency:** Kotlin Coroutines & StateFlow/SharedFlow
*   **Dependency Injection:** Dagger Hilt
*   **Local Database:** Room (SQLite)
*   **P2P Framework:** Google Nearby Connections API

## 🛠️ Concurrency & Resilience

The mesh network relies heavily on asynchronous operations. MeshChat incorporates hardened concurrency patterns to prevent deadlocks and blockages:
*   **Parallel Fan-Outs:** Broadcasts are fanned out asynchronously with strict coroutine timeouts (`withTimeout`), ensuring that a slow or disconnected peer cannot bottleneck the rest of the network.
*   **Reactive Pipelines:** Data and anomaly events stream through non-blocking Kotlin `Channels` bridging background binder threads safely into the Coroutine world.
*   **Thread-Safe State:** Internal network graphs and peer listings use `ConcurrentHashMap` and thread-safe mutexes to prevent concurrent modification exceptions.

## 📝 License

This project is open-source and available under the terms of the MIT License. See the [LICENSE](LICENSE) file for details.