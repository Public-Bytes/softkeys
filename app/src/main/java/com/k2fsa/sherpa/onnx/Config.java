// Java versions of the configuration classes from the sherpa-onnx Kotlin API,
// https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/sherpa-onnx/kotlin-api
// Copyright (c) 2024 Xiaomi Corporation. Licensed under the Apache License 2.0.
//
// libsherpa-onnx-jni.so reads these fields by name and type through JNI, so the
// names, types, and package must match the library version used in app/build.gradle.
// Every object and string field must be non-null.
package com.k2fsa.sherpa.onnx;

final class FeatureConfig {
    int sampleRate=16000;
    int featureDim=80;
    float dither=0f;
}

final class EndpointRule {
    boolean mustContainNonSilence;
    float minTrailingSilence;
    float minUtteranceLength;
    EndpointRule(boolean mustContainNonSilence,float minTrailingSilence,float minUtteranceLength){
        this.mustContainNonSilence=mustContainNonSilence;this.minTrailingSilence=minTrailingSilence;this.minUtteranceLength=minUtteranceLength;
    }
}

final class EndpointConfig {
    EndpointRule rule1=new EndpointRule(false,2.4f,0f);
    EndpointRule rule2=new EndpointRule(true,1.4f,0f);
    EndpointRule rule3=new EndpointRule(false,0f,20f);
}

final class QnnConfig {
    String backendLib="";
    String contextBinary="";
    String systemLib="";
    boolean htpPerfMode;
}

final class OnlineTransducerModelConfig {
    String encoder="";
    String decoder="";
    String joiner="";
    QnnConfig qnnConfig=new QnnConfig();
}

final class OnlineParaformerModelConfig {
    String encoder="";
    String decoder="";
}

final class OnlineZipformer2CtcModelConfig {String model="";}

final class OnlineNeMoCtcModelConfig {String model="";}

final class OnlineToneCtcModelConfig {String model="";}

final class OnlineModelConfig {
    OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
    OnlineParaformerModelConfig paraformer=new OnlineParaformerModelConfig();
    OnlineZipformer2CtcModelConfig zipformer2Ctc=new OnlineZipformer2CtcModelConfig();
    OnlineNeMoCtcModelConfig neMoCtc=new OnlineNeMoCtcModelConfig();
    OnlineToneCtcModelConfig toneCtc=new OnlineToneCtcModelConfig();
    String tokens="";
    int numThreads=1;
    boolean debug;
    String provider="cpu";
    String modelType="";
    String modelingUnit="";
    String bpeVocab="";
}

final class OnlineLMConfig {
    String model="";
    float scale=0.5f;
}

final class OnlineCtcFstDecoderConfig {
    String graph="";
    int maxActive=3000;
}

final class HomophoneReplacerConfig {
    String dictDir="";
    String lexicon="";
    String ruleFsts="";
}

final class OnlineRecognizerConfig {
    FeatureConfig featConfig=new FeatureConfig();
    OnlineModelConfig modelConfig=new OnlineModelConfig();
    OnlineLMConfig lmConfig=new OnlineLMConfig();
    OnlineCtcFstDecoderConfig ctcFstDecoderConfig=new OnlineCtcFstDecoderConfig();
    HomophoneReplacerConfig hr=new HomophoneReplacerConfig();
    EndpointConfig endpointConfig=new EndpointConfig();
    boolean enableEndpoint=true;
    String decodingMethod="greedy_search";
    int maxActivePaths=4;
    String hotwordsFile="";
    float hotwordsScore=1.5f;
    String ruleFsts="";
    String ruleFars="";
    float blankPenalty=0f;
}

/** Created by the native getResult call with the constructor (String, String[], float[], float[]). */
final class OnlineRecognizerResult {
    final String text;
    OnlineRecognizerResult(String text,String[] tokens,float[] timestamps,float[] ysProbs){this.text=text==null?"":text;}
}
