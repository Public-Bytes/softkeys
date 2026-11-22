# Dictation model benchmark

SoftKeys 1.12 recommends NVIDIA Parakeet TDT 0.6B v2 (int8) as the High accuracy model. On the harder LibriSpeech test-other recordings, it made 3.08% word errors, compared with 5.74% for the streaming FastConformer model from 1.11, so it made about 46% fewer errors. With 4 threads on the test machine, it turned a 10-second recording into text 0.90 seconds after Done. The streaming model stays available as the Fast model, because it needs a fifth of the storage and about a third of the memory, and it finishes almost at once.

These numbers come from a Linux desktop, not from a phone. A recent phone with a Snapdragon 8-class processor should be in the same range, but that is not measured.

## Method

`bench/dictation/DictationBench.java` runs each model through the same Java binding classes that the app uses (`app/src/main/java/com/k2fsa/sherpa/onnx/`), with the sherpa-onnx 2.0.2 Linux JNI library. The models run the way the app runs them:

- The streaming model gets audio in 100 ms chunks with the app's endpoint rules, as in `OnDeviceDictation`. Only the final step, one second of added silence and the last decode, happens after Done, because the earlier audio was decoded while the person spoke.
- The offline models decode the whole recording after Done, as in `BatchDictation`. For the long recording, the `segmented` mode splits the audio with `SpeechSegmenter`, as `BatchDictation` does, so only the last part is decoded after Done.

The recordings come from [LibriSpeech](https://www.openslr.org/12) (CC BY 4.0). `bench/dictation/prepare.py` took every 13th utterance of test-clean (202 utterances, 25.3 minutes) and every 15th utterance of test-other (196 utterances, 22.3 minutes), converted them to 16 kHz WAV, and picked the utterances closest to 5, 10, and 15 seconds for the delay measurements. It also joined consecutive utterances of one chapter into a 124-second recording with 0.6-second pauses.

The word error rate counts substituted, missing, and extra words, divided by the number of words in the reference. Both texts are lowercased, and punctuation other than apostrophes is removed, so Parakeet's punctuation and capitals do not count against it. British and American spellings, such as "colourists" and "colorists", count as errors.

Definitions:

- RTF (real-time factor) is decoding time divided by audio length. An RTF of 0.09 means 10 seconds of audio take 0.9 seconds.
- Load is the time to create the recognizer from files that are already in the operating system's file cache. A first load from storage takes longer.
- Done to text is the median of five runs of the time from Done until the text is ready, without cleanup, which takes under a millisecond.
- Memory is the resident memory of the whole Java process from `/proc/self/status`, right after loading and at its peak. It includes about 60 MB for the Java runtime.

The test machine is an Intel Xeon E5-2687W v3 (2014, 3.1 GHz, AVX2) with 20 threads, shared with other work. Its load average was about 5 during the runs, so the times can vary by about 10%.

## Results

| Model | Size | Threads | test-clean WER | test-other WER | RTF | Load | Done to text, 5 s | 10 s | 15 s | Memory after load, peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Fast: FastConformer streaming 480 ms, int8 | 137 MB | 2 | 3.05% | 5.74% | 0.12 | 1.8 s | 0.12 s | 0.12 s | 0.12 s | 260 MB, 460 MB |
| | | 4 | | | 0.08 to 0.09 | 1.7 s | 0.08 s | 0.08 s | 0.08 s | |
| High accuracy: Parakeet TDT 0.6B v2, int8 | 661 MB | 2 | 2.23% | 3.08% | 0.13 to 0.14 | 3.4 to 3.6 s | 0.66 to 0.71 s | 1.29 to 1.34 s | 1.97 to 2.02 s | 840 MB, 1,420 to 1,470 MB |
| | | 4 | | | 0.09 | 3.1 to 3.3 s | 0.44 to 0.45 s | 0.84 to 0.90 s | 1.31 to 1.34 s | |
| Parakeet TDT 110M, int8 (not offered) | 136 MB | 2 | 2.46% | 4.85% | 0.04 | 1.5 s | 0.18 s | 0.35 s | 0.53 s | 620 MB peak |
| | | 4 | | | 0.03 | 1.4 s | 0.14 s | 0.26 to 0.28 s | 0.39 to 0.44 s | |

The word error rates do not depend on the thread count. Ranges show the test-clean and test-other runs. The full output is below.

Long dictation, the 124-second recording with 296 words:

| Model | Threads | WER | Done to text | Notes |
| --- | --- | --- | --- | --- |
| Parakeet 0.6B, segmented | 2 | 1.01% | 2.41 s | Split at 21, 41, 64, 86, and 106 seconds. Each part decoded in 1.7 to 1.9 s with 4 threads, while the person would still be talking. |
| | 4 | 1.01% | 1.54 s | |
| FastConformer streaming | 2 | 7.77% | 0.11 s | |
| | 4 | 7.77% | 0.09 s | |

## Choice

- **High accuracy (Parakeet TDT 0.6B v2) is the recommended download.** It was the most accurate on both sets, and it writes punctuation and capitals. Its Done-to-text delay for a 10-second recording was 0.84 to 0.90 s with 4 threads, under the 1.5-second target. Its cost is a 661 MB download and about 0.9 to 1.4 GB of memory while loaded, which a phone with 8 GB or more can hold. Because Android may close the keyboard to free memory, SoftKeys loads the model again when the dictation keyboard next appears, and the microphone records during that load.
- **Fast (FastConformer streaming) stays available.** It decodes while the person speaks, so Done to text is about 0.1 s for any length, and it uses less storage and memory.
- **Parakeet TDT 110M is not offered.** On test-clean it was nearly as accurate as the 0.6B model, but on test-other it made 57% more errors than the 0.6B model. Its int8 files are published only as a GitHub release archive, and SoftKeys downloads models only from pinned Hugging Face revisions.

LibriSpeech is read audiobook speech, and these NVIDIA models were trained on data that includes LibriSpeech, so the absolute error rates are lower than they will be for casual speech on a phone microphone. The comparison between the models is the useful part. Hex also uses Parakeet TDT 0.6B, but its decoding runs on Apple hardware, so this benchmark does not show that SoftKeys matches Hex's speed.

## Running the benchmark

The models, the recordings, and the Linux library are large and are not in the repository. Keep them in a separate work folder.

1. Download and unpack the [sherpa-onnx v2.0.2 Linux JNI release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2) (`sherpa-onnx-v2.0.2-linux-x64-jni.tar.bz2`). Its `lib` folder holds `libsherpa-onnx-jni.so` and `libonnxruntime.so`.
2. Download the model files listed in `SpeechModel.java` from the pinned Hugging Face revisions, each model into its own folder.
3. Download and unpack LibriSpeech `test-clean.tar.gz` and `test-other.tar.gz`, then build the lists. The script needs Python 3 and ffmpeg.

   ```sh
   python3 -I bench/dictation/prepare.py WORK/LibriSpeech/test-clean WORK/clean 13
   python3 -I bench/dictation/prepare.py WORK/LibriSpeech/test-other WORK/other 15
   ```

4. Run each model with 2 and 4 threads. Run from the work folder, because the lists hold relative paths.

   ```sh
   bench/dictation/run.sh WORK/lib WORK/results clean/list.tsv clean/latency.tsv \
     online:models/fastconformer-480ms offline:models/parakeet-v2-int8
   bench/dictation/run.sh WORK/lib WORK/long clean/long.tsv clean/long.tsv segmented:models/parakeet-v2-int8
   ```

Each run prints one line. The utterances with errors, with the reference and the recognized text, go to `WORK/results/<model>-<threads>.errors.txt`.

## Full output

test-clean subset:

```
fastconformer   mode=online threads=2 load=1.85 utterances=202 audio=1518.3 busy=179.0 rtf=0.118 words=4027 errors=123 wer=3.05% done_5.0s=0.124 done_10.0s=0.124 done_15.0s=0.123 peak_rss_mb=457
fastconformer   mode=online threads=4 load=1.70 utterances=202 audio=1518.3 busy=121.5 rtf=0.080 words=4027 errors=123 wer=3.05% done_5.0s=0.082 done_10.0s=0.081 done_15.0s=0.081 peak_rss_mb=458
parakeet-0.6b   mode=offline threads=2 load=3.63 utterances=202 audio=1518.3 busy=200.9 rtf=0.132 words=4027 errors=90 wer=2.23% done_5.0s=0.662 done_10.0s=1.291 done_15.0s=1.966 peak_rss_mb=1465
parakeet-0.6b   mode=offline threads=4 load=3.32 utterances=202 audio=1518.3 busy=135.7 rtf=0.089 words=4027 errors=90 wer=2.23% done_5.0s=0.442 done_10.0s=0.842 done_15.0s=1.343 peak_rss_mb=1457
parakeet-110m   mode=offline threads=2 load=1.49 utterances=202 audio=1518.3 busy=57.6 rtf=0.038 words=4027 errors=99 wer=2.46% done_5.0s=0.180 done_10.0s=0.354 done_15.0s=0.527 peak_rss_mb=620
parakeet-110m   mode=offline threads=4 load=1.38 utterances=202 audio=1518.3 busy=41.1 rtf=0.027 words=4027 errors=99 wer=2.46% done_5.0s=0.137 done_10.0s=0.262 done_15.0s=0.388 peak_rss_mb=621
```

test-other subset:

```
fastconformer   mode=online threads=2 load=1.80 utterances=196 audio=1340.2 busy=165.3 rtf=0.123 words=3673 errors=211 wer=5.74% done_5.0s=0.129 done_10.0s=0.126 done_15.0s=0.127 peak_rss_mb=432
fastconformer   mode=online threads=4 load=1.72 utterances=196 audio=1340.2 busy=120.3 rtf=0.090 words=3673 errors=211 wer=5.74% done_5.0s=0.093 done_10.0s=0.091 done_15.0s=0.091 peak_rss_mb=432
parakeet-0.6b   mode=offline threads=2 load=3.42 utterances=196 audio=1340.2 busy=188.2 rtf=0.140 words=3673 errors=113 wer=3.08% done_5.0s=0.714 done_10.0s=1.338 done_15.0s=2.020 peak_rss_mb=1417
parakeet-0.6b   mode=offline threads=4 load=3.14 utterances=196 audio=1340.2 busy=119.9 rtf=0.089 words=3673 errors=113 wer=3.08% done_5.0s=0.450 done_10.0s=0.900 done_15.0s=1.312 peak_rss_mb=1409
parakeet-110m   mode=offline threads=2 load=1.50 utterances=196 audio=1340.2 busy=51.9 rtf=0.039 words=3673 errors=178 wer=4.85% done_5.0s=0.179 done_10.0s=0.352 done_15.0s=0.525 peak_rss_mb=618
parakeet-110m   mode=offline threads=4 load=1.42 utterances=196 audio=1340.2 busy=37.0 rtf=0.028 words=3673 errors=178 wer=4.85% done_5.0s=0.139 done_10.0s=0.284 done_15.0s=0.443 peak_rss_mb=621
```

124-second recording:

```
parakeet-0.6b   mode=segmented threads=2 load=3.06 utterances=1 audio=124.0 busy=17.1 rtf=0.138 words=296 errors=3 wer=1.01% done_124.0s=2.414 load_rss_mb=844 peak_rss_mb=1430
parakeet-0.6b   mode=segmented threads=4 load=3.08 utterances=1 audio=124.0 busy=10.7 rtf=0.086 words=296 errors=3 wer=1.01% done_124.0s=1.543 load_rss_mb=837 peak_rss_mb=1424
fastconformer   mode=online threads=2 load=1.81 utterances=1 audio=124.0 busy=12.8 rtf=0.104 words=296 errors=23 wer=7.77% done_124.0s=0.109 load_rss_mb=258 peak_rss_mb=350
fastconformer   mode=online threads=4 load=1.83 utterances=1 audio=124.0 busy=13.2 rtf=0.106 words=296 errors=23 wer=7.77% done_124.0s=0.087 load_rss_mb=258 peak_rss_mb=351
```

The model folder names were shortened in this output. The first two tables ran before the `load_rss_mb` field was added.
