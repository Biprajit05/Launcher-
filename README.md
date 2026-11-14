# Simple Launcher

A small Android home-screen launcher in Kotlin (AndroidX, no Compose, no third-party libraries).
minSdk 26 · targetSdk 33.

## Features
- Home grid (4 or 5 columns) + 4-app dock, system wallpaper behind it
- Swipe up anywhere for the app drawer (follows your finger, decelerate ~400 ms, search bar); swipe down or Back to close
- Tap an icon: the app expands out of the icon while the home screen scales to 0.92 / 50 % alpha, and animates back on return
- Long-press an icon (home, drawer or dock): dark popup with Edit icon, Rename, Add to / Remove from home, Uninstall and an (i) App info button. Long-press empty space: Settings
- Icon packs (ADW / Nova / Apex / Go / Lawnchair compatible): Settings -> Icon pack. Per app: Edit icon -> gallery image or an icon from a pack. Rename changes the label everywhere (and in search)
- Settings: open animation (Scale from icon / Fade / Slide up), speed (250 / 450 / 800 ms), columns (4 / 5)
- Back never closes the launcher; light + dark mode; refreshes on app install/uninstall

Note: "Scale from icon" uses `makeScaleUpAnimation`, whose duration is controlled by Android itself,
so the speed setting drives the launcher's own animations (home scale/fade, drawer) and the Fade / Slide up styles.

## Structure
`MainActivity` (home, drawer, gestures, launch animations) · `AppAdapter` · `AppRepository` · `SettingsActivity` · `Prefs` · `AppPopup` (long-press menu) · `IconManager` (icon packs, custom icons, names) · `IconDialogs`

## Build
- **Android Studio:** open the folder, let it sync (it creates the Gradle wrapper), then Run.
- **Command line:** with Gradle 8.4+ and JDK 17: `gradle assembleDebug`
  -> `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub:** push the project; Actions -> "Build debug APK" -> download the `launcher-debug-apk` artifact.

## Install the APK and set it as default launcher
1. Copy `app-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager/browser if asked), or run `adb install app-debug.apk`.
2. Press the **Home** button. Android asks which home app to use: choose **Simple Launcher** -> **Always**.
3. If no prompt appears: **Settings -> Apps -> Default apps -> Home app** (name varies by brand) -> **Simple Launcher**.
4. To go back, pick your old launcher in the same place.
