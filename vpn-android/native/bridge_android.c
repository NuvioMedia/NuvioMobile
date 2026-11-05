//go:build android

#include <jni.h>
#include <stdlib.h>
#include <pthread.h>
#include "_cgo_export.h"

static pthread_mutex_t playbackInit = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *playbackVm;
static jclass playbackClass;
static jmethodID playbackBarrier;

int nuvioPreparePlayback(const char *address, long long request) {
    pthread_mutex_lock(&playbackInit);
    JavaVM *vm = playbackVm;
    jclass type = playbackClass;
    jmethodID barrier = playbackBarrier;
    pthread_mutex_unlock(&playbackInit);
    if (vm == NULL || type == NULL || barrier == NULL) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    }
    jstring target = (*env)->NewStringUTF(env, address);
    jboolean allowed = JNI_FALSE;
    if (target != NULL) {
        allowed = (*env)->CallStaticBooleanMethod(env, type, barrier, target, (jlong)request);
        (*env)->DeleteLocalRef(env, target);
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); allowed = JNI_FALSE; }
    if (attached) (*vm)->DetachCurrentThread(vm);
    return allowed == JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_startNative(JNIEnv *env, jclass type, jstring input) {
    if (input == NULL) return 0;
    pthread_mutex_lock(&playbackInit);
    if (playbackClass == NULL) {
        if ((*env)->GetJavaVM(env, &playbackVm) != JNI_OK) { pthread_mutex_unlock(&playbackInit); return 0; }
        playbackClass = (*env)->NewGlobalRef(env, type);
        playbackBarrier = (*env)->GetStaticMethodID(env, type, "preparePlayback", "(Ljava/lang/String;J)Z");
    }
    int ready = playbackClass != NULL && playbackBarrier != NULL;
    pthread_mutex_unlock(&playbackInit);
    if (!ready) return 0;
    const char *text = (*env)->GetStringUTFChars(env, input, NULL);
    if (text == NULL) return 0;
    long long handle = nuvioStart((char *)text);
    (*env)->ReleaseStringUTFChars(env, input, text);
    return (jlong)handle;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_configureNative(JNIEnv *env, jclass type, jlong handle, jstring input) {
    if (input == NULL) return JNI_FALSE;
    const char *text = (*env)->GetStringUTFChars(env, input, NULL);
    if (text == NULL) return JNI_FALSE;
    int result = nuvioConfigure((long long)handle, (char *)text);
    (*env)->ReleaseStringUTFChars(env, input, text);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_stopNative(JNIEnv *env, jclass type, jlong handle) {
    nuvioStop((long long)handle);
}

JNIEXPORT jint JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_portNative(JNIEnv *env, jclass type, jlong handle) {
    return (jint)nuvioPort((long long)handle);
}

JNIEXPORT jlongArray JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_statsNative(JNIEnv *env, jclass type, jlong handle) {
    long long values[3];
    nuvioStats((long long)handle, values);
    jlong result[3] = {(jlong)values[0], (jlong)values[1], (jlong)values[2]};
    jlongArray array = (*env)->NewLongArray(env, 3);
    if (array != NULL) (*env)->SetLongArrayRegion(env, array, 0, 3, result);
    return array;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_probeNative(JNIEnv *env, jclass type, jlong handle) {
    return nuvioProbe((long long)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_egressNative(JNIEnv *env, jclass type, jlong handle) {
    char *address = nuvioEgress((long long)handle);
    if (address == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, address);
    free(address);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_vpn_android_HttpStreamTunnel_requestCancelledNative(JNIEnv *env, jclass type, jlong request) {
    return nuvioRequestCancelled((long long)request) ? JNI_TRUE : JNI_FALSE;
}
