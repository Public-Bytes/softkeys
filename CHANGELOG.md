# Release notes

## 1.11

Added on-device dictation. Choose On-device (Hex-style) in the new Dictation section of SoftKeys settings, then choose Download speech model. The model is NVIDIA's FastConformer streaming English model, in the int8 version that the sherpa-onnx project published, and SoftKeys runs it with sherpa-onnx 2.0.2. The download is about 137 MB and comes from a fixed revision on Hugging Face. SoftKeys checks the size and SHA-256 of each file, keeps the model in app storage that is excluded from backups, and shows the progress and the size in settings. Delete speech model removes it.

On-device dictation keeps the same strip, Stop, and Cancel as before. Partial text appears while you speak, and speech during model loading is kept. Audio stays in memory on the phone and is never sent or saved. The app now has the `INTERNET` permission, which is used only for the model download. When On-device is selected and the model is not downloaded, a short notice appears, and SoftKeys uses the phone speech service. The phone speech service works as in 1.10 and stays the default.

Added cleanup for on-device dictation. It removes "um", "uh", and similar sounds, "you know" and "like" when commas set them apart, stutters, and repeated words. It turns spoken "comma", "period", "question mark", "new line", and similar words into punctuation, fixes spacing, and capitalizes sentences. Word replacements in settings change spoken phrases into your own spelling. Switches turn off cleanup and spoken punctuation. The preview uses lighter rules.

The APK includes the sherpa-onnx library for 64-bit ARM phones, so it grew from 0.4 MB to about 9.7 MB. Other phones use the phone speech service. The new licenses are listed in the app's license screen.

The release build completed, and the new offline cleanup check passed 52 of 52 cases. The dictionary check in `bench/` passed. On a Linux desktop, the same Java binding classes with the sherpa-onnx Linux library transcribed the model's two English test recordings exactly. An emulator check was attempted, but the build machine has no hardware acceleration, and Android did not finish starting in 19 minutes. The settings screen, the model download, model loading, and dictation were not run on Android. On-device recognition was not tested on a physical phone, so speed, memory use, and accuracy on real speech are not verified.

## 1.10

Changed the glide trail to a translucent stroke that is widest under the finger and narrows toward the older end. Only the recent part of the path is drawn. The trail is gray on the light and lavender themes and white on the dark theme.

Emoji pages now slide. The grid follows a sideways drag, and the neighboring page moves in beside it. Lifting after a drag of a fifth of the width, or after a quick flick, moves to that page, and a shorter drag returns to the current page. The page arrows and vertical swipes use the same slide.

The release build completed. A screen recording on the emulator's narrow phone layout showed the trail and the page slide on the light theme. The dark and lavender themes, wide layouts, and physical devices were not reviewed.

## 1.9

Rewrote autocorrect. It now compares the typed letters with every dictionary word using the position of each tap, so a tap near the edge of a key can count as the neighboring letter. It also handles swapped, missing, and doubled letters. The middle suggestion is highlighted when a space or punctuation mark will insert it, and your own spelling appears in quotes on the left.

Autocorrect is more careful with words it does not know. Short unknown words, acronyms, and mixed case text are left alone unless the evidence is strong. In the offline check, 1.8 changed 52 of 100 correctly typed words that were missing from the dictionary. 1.9 changes 8 of 100.

Restored contractions. The bundled word list had fragments such as "didn" and "doesn" in place of "didn't" and "doesn't". The dictionary now holds contractions, capital letters for days, months, places and common names, and common texting and technology words.

SoftKeys now learns the words you keep. Restoring your spelling with Backspace keeps the word until the keyboard restarts. Restoring it twice, or tapping your spelling in the suggestions, saves it on the phone. Reset learned words in settings clears the list.

Rewrote glide decoding. The decoder matches the whole path to the keys of each word in order, and it uses sharp turns and the path length. On recorded human swipes, the first choice matched the intended word in 84 of 100 swipes, up from 74 in 1.8. Backspace right after a glide removes the whole word. A glide after punctuation starts with a space and a capital letter when a sentence begins.

Fixed keyboard behavior:

- A space after a number or symbol returns to the letters. An apostrophe does the same. Holding 123, sliding to a symbol, and lifting types that symbol and returns to the letters.
- Punctuation typed from the symbols layout now corrects the word before it. Punctuation after a glide or a chosen suggestion replaces the added space.
- Taps in the side margins and beside the A and L keys now type the nearest key. They were ignored before.
- Held Backspace removes whole words after the first characters. The double-space period allows 700 ms between the spaces.

The release build completed. Emulator review on a narrow phone layout covered the items above. Wide layouts, physical devices, Chrome, and Terminus were not reviewed. The offline check in `bench/` runs in GitHub Actions without the recorded swipes.

## 1.8

Removed the 45-second dictation cutoff. Pausing no longer finishes the dictation session. Tap Stop or the microphone again when finished, or Cancel to discard the session.

On Android 13 and later, supported providers receive a continuous PCM audio stream and return successive speech segments. SoftKeys keeps the stream open through silence. Stop stops microphone capture and closes the stream so the provider can finish. Audio stays in memory and is not saved to a file.

Older Android versions and providers without segmented audio support use repeated recognition requests within the same dictation session. Silence timeouts and ordinary final results start another request automatically. The fallback can have brief microphone gaps while the provider restarts. The installed provider must support segmented audio for uninterrupted capture across utterances.

The transcript is accumulated until Stop. Cancel discards it. Completed speech segments are preserved if a microphone or provider error interrupts dictation. Typing, cursor movement, changing fields, or hiding SoftKeys still cancels dictation.

The release build completed. Manual emulator review confirmed one uninterrupted microphone session through 76 seconds of silence, followed by microphone release after explicit Stop. Cancel and field changes were also reviewed. The emulator has no audio input, so spoken transcription accuracy and the fallback on older providers remain unverified. No automated tests were added or run.

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
