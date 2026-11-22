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
| `app/src/main/java/dev/softkeys/ios/DictationEngine.java` | Interface and listener shared by both dictation engines. |
| `app/src/main/java/dev/softkeys/ios/DictationSession.java` | Dictation through the phone speech service, with segmented recognition and legacy requests. |
| `app/src/main/java/dev/softkeys/ios/DictationAudio.java` | Continuous PCM microphone capture and a pipe to the speech provider. No audio files. |
| `app/src/main/java/dev/softkeys/ios/OnDeviceDictation.java` | On-device dictation. Microphone capture and decoding run on two threads, and audio stays in memory. |
| `app/src/main/java/dev/softkeys/ios/SpeechEngine.java` | Loads the on-device recognizer, shares it between sessions, and releases it after 90 idle seconds. |
| `app/src/main/java/dev/softkeys/ios/SpeechModel.java` | Downloads, verifies, and deletes the speech model. It is the only code that uses the network. |
| `app/src/main/java/dev/softkeys/ios/DictationCleanup.java` | Filler removal, repeated words, spoken punctuation, word replacements, spacing, and capitals. It uses no Android classes. |
| `app/src/main/java/com/k2fsa/sherpa/onnx/` | Java versions of the sherpa-onnx binding classes that `libsherpa-onnx-jni.so` reads through JNI. |
| `tests/DictationCleanupTest.java` | Offline checks for `DictationCleanup`. |
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

## Dictation

The `dictationEngine` preference selects the engine when the microphone is tapped. The service creates an `OnDeviceDictation` when the value is `device`, the native library is installed, and `SpeechModel.installed` finds every model file with its expected size. Otherwise it shows a short toast and creates a `DictationSession`. Both engines report through `DictationEngine.Listener`, so the strip, Stop, Cancel, field checks, and insertion code are shared.

### On-device engine

`app/build.gradle` downloads `sherpa-onnx-static-link-onnxruntime-2.0.2.aar` from the sherpa-onnx GitHub release, checks its SHA-256, and copies `jni/<abi>/libsherpa-onnx-jni.so` into `build/generated/sherpa-onnx/jniLibs`. The AAR is cached in the Gradle user home under `caches/softkeys`. The AAR's Kotlin classes are not used. The classes in `com.k2fsa.sherpa.onnx` are Java versions with the same field names, because the native code reads the configuration objects by field name. Update the classes, the checksum, and the version together. Release builds include only `arm64-v8a`. Debug builds also include `x86_64` for the emulator. The library is compressed in the APK with legacy packaging, so Android extracts it on install, and `SpeechEngine.supported` checks for the extracted file.

`SpeechModel` downloads four files from a pinned revision of `csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8` on Hugging Face into `noBackupFilesDir/speech/.download`. It follows redirects only to HTTPS hosts under `huggingface.co` and `hf.co`, sends a fixed user agent, and checks each file's size and SHA-256. It then renames the directory to its final name, so a partial download is never used. The total is 136,883,545 bytes.

`OnDeviceDictation` records 16 kHz mono PCM with `AudioRecord` in 100 ms chunks and puts the samples in an in-memory queue. The decode thread gets the recognizer from `SpeechEngine`, which loads it on first use with two threads on most phones. The decoder feeds each chunk to an `OnlineStream`, updates the preview, and starts a new segment when the endpoint rules see 1.2 seconds of silence after speech or 20 seconds of speech. Stop ends capture. The decoder then adds one second of silence, finishes the stream, and returns the joined segments. Cancel discards the queue and interrupts the decoder. Only the decode thread touches the stream, and only one session uses the recognizer at a time. A Stop that takes more than 30 seconds to finish reports an error.

On a Linux desktop, the same Java classes with the sherpa-onnx Linux JNI library decoded the model's two test recordings exactly, at about a tenth of real time with two threads. That check is not part of the repository.

### Phone speech service

`DictationSession` uses Android `SpeechRecognizer` on the main thread and does not start a recognition activity or switch input methods. The manifest declares `RECORD_AUDIO` and queries `android.speech.RecognitionService` for package visibility. The permission activity is not exported, and permission approval does not start recording automatically.

The service captures the current input connection and editor revision. Partial results appear only in the existing suggestion strip. Stop commits the accumulated transcript once if the same editor remains active. New input, cursor movement, keyboard dismissal, or service destruction cancels and destroys the recognizer. Cancellation discards the transcript, and late callbacks from old requests are ignored. Completed segments are retained if a microphone or provider error interrupts the session.

There is no session timer. On API 33 and later, `DictationAudio` records 16 kHz mono PCM through `AudioRecord` and pipes it into `EXTRA_AUDIO_SOURCE`. The request sets `EXTRA_SEGMENTED_SESSION` to `EXTRA_AUDIO_SOURCE`, following the [Android audio source contract](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE). Supported providers return successive segments while the audio stays open. Stop stops microphone capture and closes the stream, which tells the provider to finish. Audio is never saved to a file.

Older Android versions and providers that reject or end the segmented request use repeated standard recognition requests. Final results, no-match errors, and silence timeouts start another request within the same user session. The fallback can have brief microphone gaps while the provider restarts. Silence does not finish the user session. An eight-second recovery deadline handles a legacy provider that reports an utterance boundary and then fails to return a result. A separate eight-second deadline after the user taps Stop preserves available partial text if finalization stalls. Neither deadline limits active dictation time.

`DictationCleanup` runs on the final on-device transcript before insertion and, with lighter rules, on each preview. The service passes whether the cursor follows the start of a sentence, the spoken punctuation switch, and the parsed replacement rules. Cleanup is skipped when `dictationCleanup` is off, and it never runs on phone speech service results.

Dictation temporarily replaces the clipboard and word suggestions above the keys. Status, Stop, and Cancel use matching visual, touch, and accessibility bounds without rebuilding the keyboard. Recognition errors appear in the strip for eight seconds and can be dismissed. The phone's recognition provider controls language availability and whether audio is processed locally or remotely.

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
| `dictationEngine` | `system` | `device` for on-device dictation when the model is downloaded, or `system` for the phone speech service. |
| `dictationCleanup` | `true` | Clean up on-device dictation before insertion. |
| `spokenPunctuation` | `true` | Turn spoken punctuation words into marks during cleanup. |
| `dictationReplacements` | empty | Word replacement rules, one per line, written as `spoken => written`. |

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

Source, Gradle configuration, documentation, and selected sample screenshots are tracked. The sherpa-onnx library and the speech model are downloaded and are not tracked. Build output, signing files, old copies, local emulator tools, and temporary download hosting remain outside version control. Third-party data keeps its existing notices and licenses.
