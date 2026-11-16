# Development

SoftKeys is a native Java Android input method. The application ID is `dev.softkeys.ios`, and the minimum supported Android API level is 26.

## Requirements

Use Java 17, Android SDK platform 36, and Android SDK Build Tools 36.0.0. The project includes Gradle 8.14.3 and uses Android Gradle Plugin 8.13.0.

Set `ANDROID_HOME` to your Android SDK directory, or create an untracked `local.properties` file with an `sdk.dir` entry. Use the Gradle wrapper for builds.

```sh
./gradlew :app:assembleDebug --console=plain
./gradlew :app:assembleRelease --console=plain
```

Debug builds use a development certificate and cannot update an installed signed release. Release builds are unsigned until a separate signing step is performed.

## Source layout

| Location | Purpose |
| --- | --- |
| `app/src/main/java/dev/softkeys/ios/IosKeyboardService.java` | Input connection handling, keyboard layout, drawing, touch input, cursor movement, and feedback. |
| `app/src/main/java/dev/softkeys/ios/MainActivity.java` | Keyboard setup, appearance, typing settings, and sample field. |
| `app/src/main/java/dev/softkeys/ios/KeyboardPreferences.java` | Shared preferences, appearance migration, and hold duration bounds. |
| `app/src/main/java/dev/softkeys/ios/EnglishDictionary.java` | Local suggestions, spelling correction, and glide decoding. |
| `app/src/main/java/dev/softkeys/ios/EmojiCatalog.java` | Emoji categories, search, variants, and recent selections. |
| `app/src/main/assets/` | English dictionary, Unicode emoji data, and third-party licenses. |
| `probe/` | Separate development app with text, number, password, and search fields. |

## Input handling

The keyboard retains its view and cached context when an editor restarts the same input connection. Dictionary work and editor context reads run on background executors. Revision counters discard outdated results after edits or selection changes.

Ordinary Backspace uses Android delete-key events for compatibility with custom editors. Compound emoji use whole-grapheme deletion when the editor supports it. Cursor control uses arrow-key events so the editor handles character boundaries and line layout.

Autocorrect, glide typing, and field privacy are handled separately. Changes to input-type handling should preserve password restrictions and avoid silently rewriting commands or address syntax.

## Preferences

| Key | Default | Purpose |
| --- | --- | --- |
| `theme` | `3` | Reference appearance. Dark is 0, Lavender is 1, and System is 2. |
| `tabletNumberRow` | `true` | Dedicated digits on layouts at least 600 dp wide. Also retained on symbol pages. |
| `autocorrect` | `true` | Automatic spelling replacements. |
| `glide` | `true` | Gesture word entry. |
| `autocaps` | `true` | Capitalization requested by the editor. |
| `doubleSpacePeriod` | `true` | Replace a second space after a word with a period and space. |
| `haptic` | `true` | Key vibration and hold feedback. |
| `vibrationDuration` | `12` | Keypress pulse in milliseconds, bounded to 1 through 100. Hold feedback scales with it. |
| `clipboardSuggestions` | `true` | Recent copied text in the suggestion strip, with a two-minute lifetime and no saved history. |
| `keyPopups` | `true` | Enlarged character preview. |
| `holdDuration` | `300` | Hold delay in milliseconds, bounded to 150 through 1000. |

Clipboard suggestions use `ClipDescription.getTimestamp()` to reject old items and a delayed callback to clear eligible text. Pasting, dismissing, or typing clears the in-memory text and remembers only the dismissed copy timestamp for the service lifetime. The same item is not offered again during that service session. No clipboard content or history is written to preferences or files. Clipboard updates use existing header keys with adjusted drawing, hit areas, and accessibility bounds, without rebuilding the letter rows.

## Sample editor

The `probe` module is a separate app for inspecting input behavior. It is not bundled into the SoftKeys APK.

```sh
./gradlew :probe:assembleDebug --console=plain
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
```

Use sample text when capturing screenshots. The probe shows entered text on screen and should not be used for private text.

## Release builds

Increment `versionCode` and `versionName` in `app/build.gradle` for each new installable update. Build the release APK, align it with `zipalign`, and sign it with the existing release key using `apksigner`. Verify the signature, alignment, version, and SHA-256 before attaching it to a GitHub Release.

Keep the signing key and passwords outside the repository. The release certificate must stay the same so users can update existing installations. GitHub Actions checks an unsigned release build and does not have the signing key. Uploading the unsigned APK is an option on manual workflow runs and requires available Actions artifact storage.

Attach the signed APK and its checksum to a release tagged at the source commit. Record the build and review results in the release notes, including any editor compatibility that remains unverified.

## Repository contents

Source, Gradle configuration, documentation, and selected sample screenshots are tracked. Build output, signing files, old copies, local emulator tools, and temporary download hosting remain outside version control. Third-party data keeps its existing notices and licenses.
