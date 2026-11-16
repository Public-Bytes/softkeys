# Release notes

## 1.7

Replaced the microphone's keyboard switch with dictation inside SoftKeys. The keyboard stays visible during speech recognition. Listening status and partial text appear above the keys, with Stop and Cancel controls. Tap the microphone again to finish a request.

The first use asks for microphone permission and explains audio processing. Permission approval does not start recording automatically. Microphone access is also available in settings. Android's speech recognition provider handles audio and may use its network service. SoftKeys does not save audio or transcripts to files.

Final text is inserted once into the original editor. Typing, moving the cursor, hiding the keyboard, or changing fields cancels the request. Recording has a 45-second limit, with up to eight seconds to finish a result. Password and numeric fields keep dictation disabled. Errors appear briefly above the keys, and ordinary suggestions return after dismissal or typing.

The signed release build completed. Manual emulator review covered the permission flow, listening on narrow and wide layouts, Stop, Cancel, typing during recording, and field changes. SoftKeys remained the selected keyboard. The emulator has no audio input, so spoken transcription accuracy remains unverified. No automated tests were added or run.

## 1.6

Added configurable keypress vibration from 1 to 100 ms. The setting supports a slider, exact numeric entry, and previews while Haptic feedback is enabled. Save applies the value, Cancel keeps the previous setting, and Default selects 12 ms. Existing installations retain the previous 12 ms default. Hold feedback scales with the selected duration.

Added a Paste chip in the suggestion strip for recently copied text. The chip expires two minutes after the copy, and it is dismissed after pasting, closing the chip, or starting to type. Dismissal lasts for that copied item across editor changes. A separate switch turns clipboard suggestions off.

The clipboard itself is not changed, and no clipboard history is saved. Suggestions stay out of password fields and skip clips marked sensitive. Only the suggestion strip is repainted when clipboard suggestions change, so the keyboard rows retain their positions and touch state.

The release build completed. Android captures were reviewed for the exact 2 ms setting, the clipboard chip, and ordinary suggestions returning after a paste. No automated tests were added or run. Physical vibration strength remains dependent on the device.

## 1.5

Added a dedicated 1 through 0 number row on wide displays, including unfolded phones. The row is enabled by default and can be turned off with the Tablet number row switch. Digits stay visible on both symbol pages so switching between letters and symbols keeps the same keyboard height. Narrow phone layouts retain their existing row count.

Swapped the microphone and Enter positions on the letter and symbol layouts. Enter is now the wide key at the far right, and the microphone sits beside Space.

The microphone continues to switch to Android's enabled voice input keyboard. Devices using Google voice typing will show Google's dictation panel. SoftKeys does not implement its own speech recognition.

The release build completed, and native Android captures were reviewed at 1248 by 1972 and 1822 by 1376 pixels. The captures show the phone layout, the tablet number row, and the tablet symbol page. No automated tests were added or run.

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
