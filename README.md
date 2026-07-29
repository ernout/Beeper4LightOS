# LightBeeper

> [!WARNING]
> **IMPORTANT LOGIN INSTRUCTIONS**: Do **NOT** use the interactive verification process to verify your session, as it is currently unstable. Please use a **Recovery Code** instead to log in successfully.

## Disclaimer & Support
- **LightPhone Support**: In order for this application to build, several third-party dependencies (such as the Trixnity Matrix SDK and Koin) had to be manually added to the Light SDK's `ALLOWED_DEPENDENCIES` list. Because this bypasses their standard security allowlist, **this app is strictly unofficial and may not be supported by the LightPhone team.**
- **Beeper Support**: This is an independent, community-driven client. **It is not officially affiliated with or supported by Beeper.** Use it at your own discretion.

## Known Issues
- **Interactive Verification:** Does not work correctly. Please use a Recovery Code to verify your session.
- **Voice Typing:** Currently does not work.
- **New Message Indicator:** The UI currently lacks a visual indicator for new/unread messages.

LightBeeper is an unofficial, open-source matrix client application tailored for the LightOS environment. It is designed to work with Beeper's infrastructure to provide unified messaging on LightOS devices. 

## Features
- Connects to your Beeper/Matrix account seamlessly using your Beeper credentials.
- Supports viewing and navigating your Beeper chat list with a clean "☰" hamburger menu.
- Supports reading text messages, image attachments, and replies.
- Custom message drafting area optimized for compact screens with automatic typing line centering, comfortable `t.fine` text sizing, and full visual scroll bar support.
- Ergonomic keyboard spacing and clean version number display.
- Provides robust offline support (message queuing and retry on failure).
- Uses the Trixnity Matrix client SDK for reliable communication with Matrix servers.

## Prerequisites
- Android Studio or IntelliJ IDEA with the Android plugin.
- LightOS SDK environment setup.
- A valid Beeper account.

## Getting Started

1. **Clone the repositories:**
   Since LightBeeper depends on the Light SDK, you must clone both repositories into the same parent directory:
   ```bash
   git clone https://github.com/lightphone/light-sdk.git
   git clone https://github.com/ironfeet/Beeper4LightOS.git
   ```

   This app targets Light SDK **0.0.11**; newer light-sdk revisions ship a
   changed lp3keyboard API and stricter plugin checks that currently break the
   build. Pin the SDK checkout accordingly:
   ```bash
   cd light-sdk && git checkout -b beeper4lightos-pin 0866754
   ```
   Then add the extra dependencies this app needs to `ALLOWED_DEPENDENCIES` in
   `light-sdk/plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`:
   `"net.folivo"`, `"io.github.oshai:kotlin-logging"`, `"io.insert-koin"` and
   `"com.thelightphone"`. Building also requires JDK 21 (Android Studio's
   bundled JBR works: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`)
   and a GitHub token with `read:packages` scope exported as
   `GH_PACKAGES_USER`/`GH_PACKAGES_TOKEN` for the lp3keyboard package.

2. **Configure Local Properties:**
   Ensure your `local.properties` file in the `Beeper4LightOS` root directory contains the path to your Android SDK:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   ```
   *(Note: You may also need to set `GH_PACKAGES_USER` and `GH_PACKAGES_TOKEN` environment variables if you are fetching private packages from GitHub).*

3. **Build the Project:**
   Navigate into the project directory:
   ```bash
   cd Beeper4LightOS
   ```
   Open the project in Android Studio, sync the Gradle files, and build the project.
   Alternatively, you can build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on Emulator/Device:**
   Run the app on a LightOS emulator or compatible device:
   ```bash
   ./gradlew app:installDebug
   ```
   *(Tip: To test with full LightOS system permissions (`uid 1000`) on Linux or macOS, follow the emulator system app setup instructions in `light-sdk/docs/system_app/README.md` using an AOSP `default` system image built with `test-keys`).*

## Architecture
The application uses Kotlin and Jetpack Compose, built atop the Light Phone SDK.
- **BeeperRepository:** Handles Matrix authentication, token storage, and the `Trixnity` matrix client instance.
- **BeeperChatListScreen / ViewModel:** Displays the user's active chats (rooms).
- **BeeperChatRoomScreen / ViewModel:** Displays the timeline of messages for a specific room and handles sending messages, media, and replies.
- **BeeperBackgroundSync:** Manages background syncs to keep messages up to date when the app is minimized.

## Acknowledgements
- Built with [Trixnity](https://gitlab.com/trixnity/trixnity) for Matrix protocol support.
- Built for the [LightOS Ecosystem](https://www.thelightphone.com/).

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
