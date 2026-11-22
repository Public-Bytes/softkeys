#!/bin/sh
# Runs DictationBench for each model and thread count. See docs/dictation-benchmark.md.
#
# Usage: bench/dictation/run.sh JNI_LIB_DIR WORK_DIR LIST LATENCY_LIST MODEL_SPEC...
#   JNI_LIB_DIR holds libsherpa-onnx-jni.so and libonnxruntime.so from the sherpa-onnx
#   v2.0.2 Linux JNI release. Each MODEL_SPEC is MODE:DIR, for example
#   online:models/fastconformer-480ms or offline:models/parakeet-v2-int8.
set -eu
repo=$(cd "$(dirname "$0")/../.." && pwd)
lib=$1 work=$2 list=$3 latency=$4
shift 4
mkdir -p "$work/classes"
javac -d "$work/classes" "$repo"/app/src/main/java/com/k2fsa/sherpa/onnx/*.java \
  "$repo/app/src/main/java/dev/softkeys/ios/SpeechSegmenter.java" "$repo/bench/dictation/DictationBench.java"
for spec in "$@"; do
  mode=${spec%%:*} dir=${spec#*:}
  for threads in 2 4; do
    printf '%s ' "$dir"
    java -Djava.library.path="$lib" -cp "$work/classes" DictationBench "$mode" "$dir" "$threads" "$list" "$latency" 2>"$work/$(basename "$dir")-$threads.errors.txt"
  done
done
