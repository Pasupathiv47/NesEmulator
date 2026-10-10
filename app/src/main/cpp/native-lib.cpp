#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("v0.1 (stub)");
}
