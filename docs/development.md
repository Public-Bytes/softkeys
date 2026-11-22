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
| `app/src/main/java/dev/softkeys/ios/IosKeyboardService.java` | Input connection handling, keyboard layout, the dictation panel, drawing, touch input, cursor movement, and feedback. |
| `app/src/main/java/dev/softkeys/ios/DictationMode.java` | Decisions for the dictation keyboard: which fields show it, which engine runs, the timer text, and the level meter. It uses no Android classes. |
| `app/src/main/java/dev/softkeys/ios/DictationEngine.java` | Interface and listener shared by the dictation engines. |
| `app/src/main/java/dev/softkeys/ios/DictationSession.java` | Dictation through the phone speech service, with segmented recognition and legacy requests. |
| `app/src/main/java/dev/softkeys/ios/DictationAudio.java` | Continuous PCM microphone capture and a pipe to the speech provider. No audio files. |
| `app/src/main/java/dev/softkeys/ios/RecordedDictation.java` | Microphone capture and level reports shared by the two on-device engines. Audio stays in memory. |
| `app/src/main/java/dev/softkeys/ios/BatchDictation.java` | High accuracy dictation with Parakeet TDT, decoded after Done, with long recordings split at pauses. |
| `app/src/main/java/dev/softkeys/ios/OnDeviceDictation.java` | Fast dictation with the streaming FastConformer model, decoded while the user speaks. |
| `app/src/main/java/dev/softkeys/ios/SpeechSegmenter.java` | Finds pauses where `BatchDictation` splits long recordings. It uses no Android classes. |
| `app/src/main/java/dev/softkeys/ios/SpeechEngine.java` | Loads one on-device recognizer at a time, preloads it for the dictation keyboard, and releases it when idle or when Android asks for memory. |
| `app/src/main/java/dev/softkeys/ios/SpeechModel.java` | Downloads, verifies, and deletes the two speech models. It is the only code that uses the network. |
| `app/src/main/java/dev/softkeys/ios/DictationCleanup.java` | The Clean and Verbatim styles: filler removal, repeated words, spoken punctuation, word replacements, spacing, and capitals. It uses no Android classes. |
| `app/src/main/java/com/k2fsa/sherpa/onnx/` | Java versions of the sherpa-onnx binding classes that `libsherpa-onnx-jni.so` reads through JNI, for streaming and offline recognizers. |
| `tests/DictationCleanupTest.java` | Offline checks for `DictationCleanup`. |
| `tests/DictationModeTest.java` | Offline checks for `DictationMode` and `SpeechSegmenter`. |
| `bench/dictation/` | Host benchmark for the speech models. See [dictation-benchmark.md](dictation-benchmark.md). |
| `app/src/main/java/dev/softkeys/ios/MicrophonePermissionActivity.java` | Microphone explanation, runtime permission, and settings recovery after denial. |
| `app/src/main/java/dev/softkeys/ios/MainActivity.java` | Keyboard setup, appearance, typing settings, and sample field. |
| `app/src/main/java/dev/softkeys/ios/KeyboardPreferences.java` | Shared preferences, appearance migration, and hold duration bounds. |
| `app/src/main/java/dev/softkeys/ios/EnglishDictionary.java` | Local suggestions, spelling correction, and glide decoding. It uses no Android classes. |
| `app/src/main/assets/lexicon.tsv` | Additions to the word list. It holds contractions, capital letters for names, extra words, and removals. |
| `bench/` | Offline accuracy checks for `EnglishDictionary`. |
| `app/src/main/java/dev/softkeys/ios/EmojiCatalog.java` | Emoji categories, search, variants, and recent selections. |
| `app/src/main/assets/` | English dictionary, Unicode emoji data, and third-party licenses. `licenses/NOTICE.txt` lists the speech library components and the model license. |
| `probe/` | Separate development app with text, number, password, and search fields. |

## Input handling

The keyboard retains its view and cached context when an editor restarts the same input connection. Dictionary work and editor context reads run on background executors. Revision counters discard outdated results after edits or selection changes.

Ordinary Backspace uses Android delete-key events for compatibility with custom editors. Compound emoji use whole-grapheme deletion when the editor supports it. Cursor control uses arrow-key events so the editor handles character boundaries and line layout.

Autocorrect, glide typing, and field privacy are handled separately. Changes to input-type handling should preserve password restrictions and avoid silently rewriting commands or address syntax.

## Dictionary engine

`EnglishDictionary.analyze` returns the suggestions for the typed letters and the replacement that a word boundary applies. It scores each candidate word as the log of its count minus an edit cost. A substitution costs less when the tap was close to the other key. Swapped, missing, and doubled letters have fixed costs. An unknown typed word is replaced when the best score passes a threshold, and the threshold is higher for words of two to four letters. A known typed word is replaced only by a different spelling of the same letters with an apostrophe, by a far more common word of the same length, or by the same letters with one letter doubled.

The service records the touch position of each letter in the current word and passes the positions to `analyze`. Positions are dropped when they no longer match the letters before the cursor.

`EnglishDictionary.glide` works in units of one key width. It resamples the path, then matches the points to the keys of each candidate word in order. The cost adds the distance of the path from each key, the distance of each point from the straight line between two neighboring keys, a charge for sharp turns that are far from the keys, and the difference between the path length and the expected length. The weights were fitted on recorded swipes.

Learned words are saved in the `learnedWords` preference and passed to `setUserWords`. A learned word is never corrected, and it can appear in suggestions and glide results.

## Accuracy checks

`bench/TypingBench.java` checks a list of fixed corrections, simulated tap typing, unknown words, and recorded swipes. It needs only a JDK.

```sh
mkdir -p build/bench-out
javac -d build/bench-out bench/*.java app/src/main/java/dev/softkeys/ios/EnglishDictionary.java
java -cp build/bench-out BenchMain . --quick
```

The command exits with an error when a fixed correction fails. GitHub Actions runs it on every build. Add `-v` to list the words that went wrong, and drop `--quick` for larger samples.

The swipe checks use the [How We Swipe dataset](https://osf.io/sj67f/) by Leiva, Kim, Cui, Bi, and Oulasvirta, which is MIT licensed. The data is not part of the repository or the app. To use it, download `swipelogs.zip` from that page, unpack it, and convert the logs.

```sh
python3 bench/convert_swipes.py path/to/swipelogs build/bench-data/swipes.tsv
```

The glide weights were fitted on swipes from the first 40,000 lines of that file. Run the checks with `--holdout` to measure on the lines after them.

Run the checks before and after any change to the costs, thresholds, glide weights, or `lexicon.tsv`, and compare the numbers.

`tests/DictationCleanupTest.java` checks the dictation cleanup rules against fixed inputs and expected outputs. It also needs only a JDK, and GitHub Actions runs it on every build.

```sh
mkdir -p build/cleanup-test
javac -d build/cleanup-test tests/DictationCleanupTest.java app/src/main/java/dev/softkeys/ios/DictationCleanup.java
java -cp build/cleanup-test DictationCleanupTest
```

Add a case to the test for each change to the cleanup rules.

`tests/DictationModeTest.java` checks which fields show the dictation keyboard, the engine choice, the timer, the level meter, and where long recordings are split. GitHub Actions runs it on every build.

```sh
mkdir -p build/mode-test
javac -d build/mode-test tests/DictationModeTest.java app/src/main/java/dev/softkeys/ios/DictationMode.java app/src/main/java/dev/softkeys/ios/SpeechSegmenter.java
java -cp build/mode-test DictationModeTest
```

The speech model benchmark in `bench/dictation/` needs the sherpa-onnx Linux JNI library, the models, and LibriSpeech, so it does not run in GitHub Actions. [dictation-benchmark.md](dictation-benchmark.md) explains how to run it and lists the results.

## Dictation

### Dictation keyboard

The microphone key turns the keyboard into the dictation keyboard and starts recording. The dictation keyboard is a panel drawn by the same `KeyboardView`. It is shorter than the letter keyboard and has three rows:

1. A hint on the left and the Clean or Verbatim style on the right. While recording, the hint becomes a red dot and a timer.
2. A recessed well. When idle, it holds a row of dots and the Dictate button. While recording, it holds Cancel (×), the level meter, and Done. While transcribing, Done shows a spinner. A message with a button replaces the well's contents when the microphone permission is missing, when no engine can run, or after an error.
3. The keyboard button, labeled "Show keyboard" for accessibility, followed by Space, Delete, and Return. Space, Delete, and Return insert a plain space, delete like Backspace with the same repeat on hold, and run the editor's action or insert a line break. They do nothing while a recording is open, so dictated text lands where the user started. The keyboard button works at any time.

The `dictationKeyboard` preference saves whether the panel is on. `DictationMode.showPanel` shows the panel only when that preference is on and `DictationMode.fieldAllowsDictation` allows the field. Password, PIN, number, phone, and date fields, fields without predictions, and fields with `IME_FLAG_NO_PERSONALIZED_LEARNING` show the keys and never record, and the preference is not changed. `onStartInput` and `onStartInputView` recompute the panel, because the field, the microphone permission, and the downloaded models can change while the keyboard is hidden. Changing fields does not start recording. The Dictate button starts the next recording.

The keyboard button turns the preference off and shows the keys at once. A recording that is open is finished, not discarded. Its text is inserted when it is ready, and keys typed in the meantime wait in `pendingActions` and run after it, in order. This is called a handoff in the code. As in earlier releases, moving the cursor from the app, changing fields, and hiding the keyboard discard an open recording.

The level meter draws one bar every 50 ms with `DictationMode.Meter`, which rises quickly and falls slowly, and the bars scroll smoothly between steps. The on-device engines report the RMS of each 50 ms chunk. The phone speech service reports `onRmsChanged`. The panel redraws its top two rows on every frame only while a recording is open. Colors come from the current theme.

### Engines

`DictationMode.engine` chooses the engine for each recording. The `dictationEngine` preference selects on-device dictation or the phone speech service, and the other one is used when the preferred one cannot run. On-device dictation uses the model in the `speechModel` preference when it is downloaded, and the other downloaded model otherwise. All engines report through `DictationEngine.Listener`.

| Model | Engine | Size | Decoding |
| --- | --- | --- | --- |
| High accuracy, NVIDIA Parakeet TDT 0.6B v2, int8 | `BatchDictation` | 661,288,526 bytes | The whole recording after Done, with an offline transducer |
| Fast, NVIDIA FastConformer streaming, 480 ms, int8 | `OnDeviceDictation` | 136,883,545 bytes | Streaming while the user speaks |

Both on-device engines extend `RecordedDictation`. It records 16 kHz mono PCM with `AudioRecord` in 50 ms chunks into an in-memory queue as soon as recording starts, before the model is loaded, and reports the level of each chunk. Audio is never written to a file.

`BatchDictation` keeps the audio in memory. `SpeechSegmenter` looks for a pause of 300 ms after 20 seconds of audio, or splits at the quietest 50 ms of the last 5 seconds when a part reaches 28 seconds. Each finished part is decoded while the user keeps talking, so Done waits only for the last part. The last part gets 0.25 seconds of added silence. The recognizer loads on its own thread, and the decode thread returns it to `SpeechEngine` whether the session finishes, fails, or is cancelled during loading. A Done that takes more than 60 seconds to finish reports an error.

`OnDeviceDictation` feeds each chunk to an `OnlineStream` and starts a new segment when the endpoint rules see 1.2 seconds of silence after speech or 20 seconds of speech. Done ends capture. The decoder then adds one second of silence, finishes the stream, and returns the joined segments. A Done that takes more than 30 seconds reports an error.

`SpeechEngine` holds one loaded recognizer, either kind. When the panel appears with an on-device engine, `SpeechEngine.preload` starts loading the model on a background thread, and `SpeechEngine.keep` stops the idle timer until the keyboard is hidden. A session that starts during loading waits for the load. Otherwise the recognizer is released 90 seconds after the last session, when the model is deleted, or when `onTrimMemory` reports `TRIM_MEMORY_UI_HIDDEN` or higher while no session is open. The recognizers use half the processor cores, at most four.

### Native library and models

`app/build.gradle` downloads `sherpa-onnx-static-link-onnxruntime-2.0.2.aar` from the sherpa-onnx GitHub release, checks its SHA-256, and copies `jni/<abi>/libsherpa-onnx-jni.so` into `build/generated/sherpa-onnx/jniLibs`. The AAR is cached in the Gradle user home under `caches/softkeys`. The AAR's Kotlin classes are not used. The classes in `com.k2fsa.sherpa.onnx` are Java versions with the same field names, because the native code reads the configuration objects by field name. The offline configuration includes the classes for model families that SoftKeys does not use, because `offline-recognizer.cc` reads all of them. Update the classes, the checksum, and the version together. Release builds include only `arm64-v8a`. Debug builds also include `x86_64` for the emulator. The library is compressed in the APK with legacy packaging, so Android extracts it on install, and `SpeechEngine.supported` checks for the extracted file.

`SpeechModel` downloads four files for each model from a pinned revision on Hugging Face into `noBackupFilesDir/speech/.download-<id>`. It follows redirects only to HTTPS hosts under `huggingface.co` and `hf.co`, sends a fixed user agent, and checks each file's size and SHA-256. It then renames the directory to its final name, so a partial download is never used. The revisions are `84b478d541cdbf0c296a7ab5f1e20a6f8f998114` of `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` and `df8ed95e44a70924450381e610770f9d656d1e15` of `csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8`. The digest of each `tokens.txt` was computed from the downloaded file, because Hugging Face lists digests only for large files.

### Text styles

The service applies the style to on-device text when it inserts it, so a style change during a recording applies to that recording. The `dictationCleanup` preference holds the style, and the panel's style button and settings both change it. Clean runs `DictationCleanup.clean`. Verbatim runs `DictationCleanup.verbatim`, which keeps every word and fixes only spacing and capitals. Both lowercase an ordinary capitalized first word when the cursor is in the middle of a sentence, because Parakeet starts every recording with a capital. The service passes whether the cursor follows the start of a sentence, the spoken punctuation switch, the parsed replacement rules, the vocabulary from `DictationCleanup.parseVocabulary`, and `EnglishDictionary.contains` as the test for known words. After the exact rules, Clean replaces any unknown word of 4 or more letters whose sound key is within one edit of a vocabulary word's key, or two edits for a key of 7 or more symbols. `DictationCleanup.soundKey` builds the key, e.g. "Joksich", "Yoxich", and "Yocksage" all give the key of "Yaksich". A lowercase replacement gets a capital when the word it replaces had one. Phone speech service text is inserted as the provider returns it.

### Phone speech service

`DictationSession` uses Android `SpeechRecognizer` on the main thread and does not start a recognition activity or switch input methods. The manifest declares `RECORD_AUDIO` and queries `android.speech.RecognitionService` for package visibility. The permission activity is not exported, and permission approval does not start recording automatically.

The service captures the current input connection and editor revision. Done commits the accumulated transcript once if the same editor remains active. Cursor movement from the app, keyboard dismissal, changing fields, or service destruction cancels and destroys the recognizer. Cancellation discards the transcript, and late callbacks from old requests are ignored. Completed segments are retained if a microphone or provider error interrupts the session.

There is no session timer. On API 33 and later, `DictationAudio` records 16 kHz mono PCM through `AudioRecord` and pipes it into `EXTRA_AUDIO_SOURCE`. The request sets `EXTRA_SEGMENTED_SESSION` to `EXTRA_AUDIO_SOURCE`, following the [Android audio source contract](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE). Supported providers return successive segments while the audio stays open. Done stops microphone capture and closes the stream, which tells the provider to finish. Audio is never saved to a file.

Older Android versions and providers that reject or end the segmented request use repeated standard recognition requests. Final results, no-match errors, and silence timeouts start another request within the same user session. The fallback can have brief microphone gaps while the provider restarts. Silence does not finish the user session. An eight-second recovery deadline handles a legacy provider that reports an utterance boundary and then fails to return a result. A separate eight-second deadline after the user taps Done preserves available partial text if finalization stalls. Neither deadline limits active dictation time. The phone's recognition provider controls language availability and whether audio is processed locally or remotely.

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
| `learnedWords` | empty | Words the user kept after a correction. At most 2000 words, never from password fields. |
| `dictationKeyboard` | `false` | Show the dictation keyboard in place of the keys. The microphone key turns it on and the keyboard button turns it off. |
| `dictationEngine` | `system` | `device` for on-device dictation when a model is downloaded, or `system` for the phone speech service. |
| `speechModel` | `accurate` | `accurate` for Parakeet TDT or `fast` for the streaming FastConformer model, when both are downloaded. |
| `dictationCleanup` | `true` | The style for on-device dictation. `true` is Clean and `false` is Verbatim. |
| `spokenPunctuation` | `true` | Turn spoken punctuation words into marks in the Clean style. |
| `dictationReplacements` | empty | Word replacement rules, one per line, written as `spoken => written`. A line with one word adds that word to the sound matching vocabulary. |

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

Source, Gradle configuration, documentation, and selected sample screenshots are tracked. The sherpa-onnx library, the speech models, and the benchmark recordings are downloaded and are not tracked. Build output, signing files, old copies, local emulator tools, and temporary download hosting remain outside version control. Third-party data keeps its existing notices and licenses.
