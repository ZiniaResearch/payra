# Android gateway folder structure

This is the expected production layout. It may change as we build and test the gateway.

```text
payra/                                             # Payra repository root
├── ui/                                            # Payra website
├── server/                                        # Rust API and planned scheduler
├── docs/android-gateway.md                        # Expected Android project layout
└── android-gateway/                               # Kotlin Android project
    ├── .gitignore                                 # Excludes local settings and build output from Git
    ├── settings.gradle.kts                        # Declares the app module and library repositories
    ├── gradlew                                    # Starts the pinned Gradle version on Linux and macOS
    ├── gradlew.bat                                # Starts the pinned Gradle version on Windows
    ├── gradle/wrapper/                            # Files that let the project start its own Gradle version
    │   ├── gradle-wrapper.jar                     # Starts Gradle and downloads it if needed
    │   └── gradle-wrapper.properties              # Pins the Gradle download and checksum
    └── app/                                       # The module that builds the gateway APK
        ├── build.gradle.kts                       # Android versions, dependencies, and build settings
        ├── gradle.lockfile                        # Exact library versions recorded by Gradle
        ├── schemas/                               # Room database snapshots for testing upgrades
        └── src/                                   # App code, resources, and tests
            ├── main/                              # Files shared by debug and release builds
            │   ├── AndroidManifest.xml            # Declares app components and permissions
            │   ├── res/                           # App text, icons, and theme
            │   └── kotlin/com/payra/gateway/      # Kotlin code under the gateway's base package
            │       ├── ui/                        # Screens and controls
            │       │   └── MainActivity.kt        # Shows setup, gateway status, and recent activity
            │       ├── service/                   # Background operation and recovery
            │       │   ├── GatewayService.kt      # Runs the gateway and coordinates sending
            │       │   ├── BootReceiver.kt        # Restores the enabled gateway after reboot when allowed
            │       │   └── ResultUploadWorker.kt  # Retries uploading saved results
            │       ├── network/                   # Communication with the Payra server
            │       │   ├── GatewaySocket.kt       # Listens for work over WebSocket and reconnects
            │       │   └── GatewayApi.kt          # Claims jobs and reports results over HTTP(S)
            │       ├── sms/                       # Sending SMS and receiving Android callbacks
            │       │   ├── SmsSender.kt           # Submits a message through the selected SIM
            │       │   └── SmsResultReceiver.kt   # Records send results and carrier delivery reports
            │       └── storage/                   # Data kept on the phone across restarts
            │           ├── GatewayDatabase.kt     # Room database for attempts and pending results
            │           ├── SendAttempt.kt         # Tracks a single SMS send attempt
            │           ├── PendingEvent.kt        # Holds a result that Payra still needs to acknowledge
            │           └── GatewaySettings.kt     # Stores the server address and selected SIM
            ├── debug/                             # Debug-only settings for local PC development
            ├── test/                              # Tests that run on the PC
            └── androidTest/                       # Tests that run on an Android device or emulator
```
