# Release notes

## 1.4

### Typing controls

- Added independent switches for automatic capitalization, double-space periods, haptic feedback, and key popups.
- Added an adjustable touch and hold duration from 150 to 1000 ms. The default is 300 ms.
- Holding Space now starts cursor control. Drag horizontally to move through text, or vertically in multiline editors. Key labels disappear during cursor control.
- Haptic feedback now uses the device vibrator directly when enabled. The app requests the normal `VIBRATE` permission.

### Suggestions and glide typing

- Glide entry and suggestions no longer depend on an app's `NO_SUGGESTIONS` flag.
- Ordinary search words can be corrected while addresses, paths, and identifiers stay literal.
- Email fields and recognized terminal editors allow suggestions and deliberate glide entry without automatic spelling replacements.
- Removed the "English (US)" and "SoftKeys" placeholders from the prediction row. Changed the glide trail to warm white with a faint shadow.

### Review

The release build completed. The APK signature matches the earlier releases, and alignment and the downloaded file's SHA-256 were checked. Android captures were reviewed for settings, the enlarged key popup, and cursor mode. Physical vibration and Chrome/Terminus behavior on the user's device have not been verified. No automated tests were added or run for this release.

## 1.3

The default Reference appearance was adjusted to the supplied phone and tablet references. Narrow displays use light gray with white keys, and wide displays use lavender with shorter keys and smaller lettering. Both layouts use continuous rows and one blank space bar.

The bottom row contains Emoji, 123, Space, Return, and a microphone. Android supplies the keyboard collapse control. The microphone switches to an enabled voice keyboard, or opens Android's keyboard picker.

The update selects Reference appearance once for existing installations and preserves later theme choices. Android captures were reviewed at 1248 by 1972 and 1822 by 1376 pixels.
