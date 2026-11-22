// Java versions of the offline configuration classes from the sherpa-onnx Kotlin API,
// https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/sherpa-onnx/kotlin-api
// Copyright (c) 2024 Xiaomi Corporation. Licensed under the Apache License 2.0.
//
// libsherpa-onnx-jni.so reads every one of these fields by name and type through JNI
// (sherpa-onnx/jni/offline-recognizer.cc), including the fields for model families that
// SoftKeys does not use. The names, types, and package must match the library version in
// app/build.gradle, and every object and string field must be non-null.
package com.k2fsa.sherpa.onnx;

final class OfflineCtcFstDecoderConfig {
    String graph="";
    int maxActive=3000;
}

final class OfflineTransducerModelConfig {
    String encoder="";
    String decoder="";
    String joiner="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineParaformerModelConfig {
    String model="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineWhisperModelConfig {
    String encoder="";
    String decoder="";
    String language="en";
    String task="transcribe";
    int tailPaddings=1000;
    boolean enableTokenTimestamps;
    boolean enableSegmentTimestamps;
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineFireRedAsrModelConfig {
    String encoder="";
    String decoder="";
}

final class OfflineMoonshineModelConfig {
    String preprocessor="";
    String encoder="";
    String uncachedDecoder="";
    String cachedDecoder="";
    String mergedDecoder="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineSenseVoiceModelConfig {
    String model="";
    String language="";
    boolean useInverseTextNormalization=true;
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineNemoEncDecCtcModelConfig {
    String model="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineZipformerCtcModelConfig {
    String model="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OfflineWenetCtcModelConfig {String model="";}

final class OfflineOmnilingualAsrCtcModelConfig {String model="";}

final class OfflineMedAsrCtcModelConfig {String model="";}

final class OfflineFunAsrNanoModelConfig {
    String encoderAdaptor="";
    String llm="";
    String embedding="";
    String tokenizer="";
    String systemPrompt="";
    String userPrompt="";
    String language="";
    boolean itn=true;
    String hotwords="";
    int maxNewTokens=512;
    float temperature=1e-6f;
    float topP=0.8f;
    int seed=42;
}

final class OfflineQwen3AsrModelConfig {
    String convFrontend="";
    String encoder="";
    String decoder="";
    String tokenizer="";
    String hotwords="";
    int maxTotalLen=512;
    int maxNewTokens=128;
    float temperature=1e-6f;
    float topP=0.8f;
    int seed=42;
}

final class OfflineFireRedAsrCtcModelConfig {String model="";}

final class OfflineCanaryModelConfig {
    String encoder="";
    String decoder="";
    String srcLang="en";
    String tgtLang="en";
    boolean usePnc=true;
}

final class OfflineCohereTranscribeModelConfig {
    String encoder="";
    String decoder="";
    String language="";
    boolean usePunct=true;
    boolean useItn=true;
}

final class OfflineDolphinModelConfig {String model="";}

final class OfflineModelConfig {
    OfflineTransducerModelConfig transducer=new OfflineTransducerModelConfig();
    OfflineParaformerModelConfig paraformer=new OfflineParaformerModelConfig();
    OfflineWhisperModelConfig whisper=new OfflineWhisperModelConfig();
    OfflineFireRedAsrModelConfig fireRedAsr=new OfflineFireRedAsrModelConfig();
    OfflineMoonshineModelConfig moonshine=new OfflineMoonshineModelConfig();
    OfflineSenseVoiceModelConfig senseVoice=new OfflineSenseVoiceModelConfig();
    OfflineNemoEncDecCtcModelConfig nemo=new OfflineNemoEncDecCtcModelConfig();
    OfflineZipformerCtcModelConfig zipformerCtc=new OfflineZipformerCtcModelConfig();
    OfflineWenetCtcModelConfig wenetCtc=new OfflineWenetCtcModelConfig();
    OfflineOmnilingualAsrCtcModelConfig omnilingual=new OfflineOmnilingualAsrCtcModelConfig();
    OfflineMedAsrCtcModelConfig medasr=new OfflineMedAsrCtcModelConfig();
    OfflineFunAsrNanoModelConfig funasrNano=new OfflineFunAsrNanoModelConfig();
    OfflineQwen3AsrModelConfig qwen3Asr=new OfflineQwen3AsrModelConfig();
    OfflineFireRedAsrCtcModelConfig fireRedAsrCtc=new OfflineFireRedAsrCtcModelConfig();
    OfflineCanaryModelConfig canary=new OfflineCanaryModelConfig();
    OfflineCohereTranscribeModelConfig cohereTranscribe=new OfflineCohereTranscribeModelConfig();
    OfflineDolphinModelConfig dolphin=new OfflineDolphinModelConfig();
    String teleSpeech="";
    int numThreads=1;
    boolean debug;
    String provider="cpu";
    String modelType="";
    String tokens="";
    String modelingUnit="";
    String bpeVocab="";
}

final class OfflineRecognizerConfig {
    FeatureConfig featConfig=new FeatureConfig();
    OfflineModelConfig modelConfig=new OfflineModelConfig();
    HomophoneReplacerConfig hr=new HomophoneReplacerConfig();
    String decodingMethod="greedy_search";
    int maxActivePaths=4;
    String hotwordsFile="";
    float hotwordsScore=1.5f;
    String ruleFsts="";
    String ruleFars="";
    float blankPenalty=0f;
    OfflineCtcFstDecoderConfig ctcFstDecoderConfig=new OfflineCtcFstDecoderConfig();
}

/** Created by the native getResult call with the constructor (String, String[], float[], String, String, String, float[], int[]). */
final class OfflineRecognizerResult {
    final String text;
    OfflineRecognizerResult(String text,String[] tokens,float[] timestamps,String lang,String emotion,String event,float[] durations,int[] words){this.text=text==null?"":text;}
}
