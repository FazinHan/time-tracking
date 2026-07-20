# KimaiTimer

A minimal Android app for tracking time against a personal [Kimai](https://www.kimai.org/) instance. Start and stop timers, resume recent entries, and create activities on the fly — without opening the Kimai web UI.

## Features

- **One-tap start/stop** for the currently running timesheet
- **Activity picker** scoped to a single configured project
- **Resume recent entries**, carrying over their description and tags
- **Create activities** in-app
- **Guided setup** — enter your server URL and API token, then pick a customer and project
- Supports both **Bearer** (Kimai 2.x API token) and **legacy** (`X-AUTH` header) authentication

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- MVVM (`AndroidViewModel` + `StateFlow`)
- Retrofit + Moshi + OkHttp for the Kimai REST API
- Settings persisted via `SharedPreferences`

## Project structure

```
app/src/main/java/com/fizaan/kimaitimer/
├── MainActivity.kt        # Compose entry point; switches between Setup and Main
├── MainViewModel.kt       # UI + setup state, all API orchestration
├── data/
│   ├── KimaiApi.kt        # Retrofit interface + client provider
│   ├── AuthInterceptor.kt # Injects Bearer / legacy auth headers
│   ├── Models.kt          # API data classes
│   └── Prefs.kt           # SharedPreferences wrapper
└── ui/
    ├── MainScreen.kt      # Timer, activity picker, create dialog
    ├── SetupScreen.kt     # Credentials + customer/project selection
    └── Theme.kt           # Material 3 theme
```

## Requirements

- Android Studio (or Gradle) with Android SDK 35
- A device/emulator running **Android 8.0 (API 26)** or newer
- A reachable Kimai server and an API token

> **Note:** This repo's Android Gradle Plugin requires **JDK 17–21**. Building with a newer JDK (e.g. Java 25) will fail. Point `JAVA_HOME` / `org.gradle.java.home` at a JDK 21 install if your system default is newer.

## Setup

1. Clone the repo.
2. Create `local.properties` pointing at your Android SDK:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
3. Build and install:
   ```bash
   ./gradlew installDebug
   ```
4. Launch the app and complete the in-app setup:
   - **Server URL** — e.g. `http://192.168.0.110:8000`
   - **API token** — from Kimai under *User → API Access*
   - Pick a **customer** and **project** to track against.

Cleartext HTTP is enabled so the app can talk to a Kimai instance on your LAN.

## License

Personal project — no license specified.
