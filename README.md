# 🎵 True Shuffle for Spotify

**True Shuffle** is an Android application designed to solve Spotify's repetitive and biased native shuffle algorithm. It creates 100% truly random shuffle playlists without track repetitions until a full cycle of your Liked Songs, Playlists, or Albums is completed.

---

## ✨ Features

- 💚 **Spotify Integration:** Full support for Liked Songs (All), Created/Saved Playlists, and Saved Albums.
- 🔀 **True Random Shuffling:** Generates a dedicated Spotify playlist where each track is played exactly once per cycle.
- 📻 **5x1 Home Screen Widget:**
  - Displays album art, track title, artist, and current cycle progress (e.g., `24 / 120`).
  - Interactive player controls (Previous, Play/Pause, Next) operable directly from the Home Screen.
  - Dynamic background color extraction matching album cover colors ([Palette API](https://developer.android.com/develop/ui/views/graphics/palette)).
- 📌 **One-Tap Widget Addition:** Built-in button to pin the Widget directly to your home screen using native Android APIs (`requestPinAppWidget`).
- 🔄 **Background Synchronization:** Background service (`NotificationListenerService`) that detects track changes in Spotify and updates cycle progress and the Widget in real-time.

---

## ☕ Support & Donations

If you enjoy using **True Shuffle** and want to support its ongoing development, consider buying me a coffee or sending a donation!

- ☕ **Buy Me a Coffee / Ko-fi:** `[Your Link Here]`
- 💖 **GitHub Sponsors:** `[Your Link Here]`
- ⚡ **Pix (Brasil):** `[Sua Chave Pix Aqui]`

Your support helps keep the project alive and free for everyone!

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin
- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) & Material 3
- **App Widgets:** [Jetpack Glance](https://developer.android.com/jetpack/compose/glance)
- **Local Persistence:** [Room Database](https://developer.android.com/training/data-storage/room)
- **Networking:** [Retrofit 2](https://square.github.io/retrofit/) + OkHttp 4 + [Spotify Web API](https://developer.spotify.com/documentation/web-api)
- **Playback Control:** Spotify App Remote SDK & MediaController
- **Architecture:** ServiceLocator Pattern & Repository Data Access

---

## 🚀 How to Build & Run

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or higher
- JDK 17
- Android Device or Emulator running API 26+ (Android 8.0+)
- Official Spotify application installed on the device

### Build Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/TrueShuffle.git
   cd TrueShuffle
   ```
2. Open the project in **Android Studio**.
3. Build and run on your device or generate an APK via terminal:
   ```bash
   ./gradlew assembleDebug
   ```
4. The generated APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📱 How to Use

1. **Step 1:** Connect your Spotify account inside the application using secure OAuth 2.0 PKCE login.
2. **Step 2:** Select your music source (Liked Songs, Playlist, or Album) and tap **"Generate New Cycle"**.
3. **Step 3:** Enable **Notification Access** permission to allow the app to track playback in the background.
4. **Step 4:** Tap **"Add Widget to Home Screen"** to control your music playback directly from your home screen.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** - see the [LICENSE](LICENSE) file for details.

Under this license, you are free to study, modify, and distribute the software, provided that any derivative works remain 100% free and open-source under the same GPLv3 terms.
