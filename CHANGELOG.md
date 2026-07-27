# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.4] - 2026-07-27

### Added
- **Typing Line Centering:** When drafting multi-line messages, the view automatically scrolls to keep your active typing line centered in the visible screen area for better ergonomics and context.
- **Trailing Scroll Room:** Added bottom spacing in the message composer so even the very last line of a long message can reach the middle of the screen.

### Changed
- **Composer Font Size:** Reduced character font size in the message composer to `t.fine` (25sp), making text drafting much cleaner and more comfortable on compact screens.
- **Selection Behavior:** Selecting or tapping text to position the cursor no longer triggers automatic centering, preventing unexpected view jumps when editing or reviewing drafted messages.

### Fixed
- **Scrolled Cursor Positioning:** Fixed a bug where tapping a line in a scrolled textbox placed the cursor one or more lines above where you touched. Touch coordinates are now properly offset by the scroll state.

## [0.0.3] - 2026-07-27

### Added
- Added a visual scroll bar when drafting long messages so you can easily see your place.
- Added a new main menu (☰) on the Chat List to keep things organized.

### Changed
- The app is now called "LightBeeper" on your home screen instead of "Beeper4LightOS".
- Moved the Logout button and the app version number into the new menu to declutter the Chat List.
- Made the version number text larger and easier to read.
- Reduced the empty space at the top of the Chat List.

### Fixed
- Fixed an issue where the keyboard took up too much screen space. It has been lowered, giving you much more room to see what you are typing.

## [0.0.2] - 2026-07-20

### Added
- **Version Display:** Added a dynamic version number indicator (e.g., `v0.0.2`) to the bottom of the chat list screen.
- **BuildConfig Integration:** Configured `buildConfig` to dynamically inject the app version to bypass Light SDK's static analysis restrictions on Android framework classes.
- **CI Hardening:** Configured CodeQL to explicitly exclude the `light-sdk` submodule from its analysis, ensuring alerts only flag the Beeper4LightOS codebase.

### Fixed
- **Security:** Resolved a local information disclosure vulnerability by enforcing strict POSIX file permissions when creating temporary audio playback files.
- **CI/CD:** Fixed the GitHub Actions release workflow so that it correctly locates the `CHANGELOG.md` file when extracting release notes.

## [0.0.1] - 2026-07-20

### Added
- **Initial release:** First version of Beeper4LightOS!
