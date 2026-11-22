// JNI entry points for dev.softkeys.ios.SmartEditor. See editor_core.h.
#include <jni.h>
#include "editor_core.h"

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_softkeys_ios_SmartEditor_nativeLoad(JNIEnv *env, jclass, jstring path, jint threads, jint context) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    softkeys::Editor *editor = softkeys::editor_load(chars, threads, context);
    env->ReleaseStringUTFChars(path, chars);
    return reinterpret_cast<jlong>(editor);
}

// Text crosses as UTF-8 bytes, because JNI's modified UTF-8 strings cannot hold emoji safely.
JNIEXPORT jbyteArray JNICALL Java_dev_softkeys_ios_SmartEditor_nativeEdit(JNIEnv *env, jclass, jlong handle, jbyteArray text, jint max_tokens, jlong timeout_ms) {
    auto *editor = reinterpret_cast<softkeys::Editor *>(handle);
    if (!editor) return nullptr;
    jsize size = env->GetArrayLength(text);
    std::string input(size, '\0');
    env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte *>(&input[0]));
    bool ok = false;
    std::string output = softkeys::editor_edit(editor, input, max_tokens, timeout_ms, ok);
    if (!ok) return nullptr;
    jbyteArray result = env->NewByteArray((jsize) output.size());
    env->SetByteArrayRegion(result, 0, (jsize) output.size(), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}

JNIEXPORT void JNICALL Java_dev_softkeys_ios_SmartEditor_nativeFree(JNIEnv *, jclass, jlong handle) {
    softkeys::editor_free(reinterpret_cast<softkeys::Editor *>(handle));
}

}
