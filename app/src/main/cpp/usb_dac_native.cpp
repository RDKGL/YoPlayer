#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>
#include "ring_buffer.h"

#define LOG_TAG "UsbDacNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const size_t RING_BUFFER_SIZE = 512 * 1024; // 512 KB lock-free buffer
static LockFreeRingBuffer g_ringBuffer(RING_BUFFER_SIZE);

static std::atomic<bool> g_isRunning{false};
static std::thread g_workerThread;
static int g_epOut = 0;
static int g_maxPacketSize = 512;
static int g_sampleRate = 44100;
static int g_bitDepth = 16;
static int g_channels = 2;

static void audio_transfer_loop() {
    LOGI("Audio transfer thread started");
    std::vector<uint8_t> packetBuffer(g_maxPacketSize > 0 ? g_maxPacketSize : 512);

    while (g_isRunning.load(std::memory_order_relaxed)) {
        size_t bytesNeeded = packetBuffer.size();
        g_ringBuffer.read(packetBuffer.data(), bytesNeeded);

        // Isochronous/Bulk stream timing simulation for DAC transfer
        std::this_thread::sleep_for(std::chrono::microseconds(125));
    }
    LOGI("Audio transfer thread stopped");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_open_yo_player_data_usb_UsbDirectDacManager_initNativeDac(
        JNIEnv* env, jobject thiz, jint fd, jint epOut, jint maxPacketSize) {
    g_epOut = epOut;
    g_maxPacketSize = maxPacketSize;
    g_ringBuffer.reset();

    if (g_isRunning.load()) {
        g_isRunning.store(false);
        if (g_workerThread.joinable()) {
            g_workerThread.join();
        }
    }

    g_isRunning.store(true);
    g_workerThread = std::thread(audio_transfer_loop);
    LOGI("Native DAC initialized with FD: %d, EP: %d, MaxPacketSize: %d", fd, epOut, maxPacketSize);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_open_yo_player_data_usb_UsbDirectDacManager_setNativeFormat(
        JNIEnv* env, jobject thiz, jint sampleRate, jint bitDepth, jint channels) {
    g_sampleRate = sampleRate;
    g_bitDepth = bitDepth;
    g_channels = channels;
    LOGI("Native DAC format configured: %d Hz, %d-bit, %d ch", sampleRate, bitDepth, channels);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_open_yo_player_data_usb_UsbDirectDacManager_stopNativeDac(
        JNIEnv* env, jobject thiz) {
    g_isRunning.store(false);
    if (g_workerThread.joinable()) {
        g_workerThread.join();
    }
    g_ringBuffer.reset();
    LOGI("Native DAC stopped");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_open_yo_player_data_usb_UsbDirectDacManager_writePcmData(
        JNIEnv* env, jobject thiz, jbyteArray pcmData, jint offset, jint length) {
    if (!g_isRunning.load() || length <= 0) return 0;

    jbyte* bufferBytes = env->GetByteArrayElements(pcmData, nullptr);
    if (!bufferBytes) return 0;

    size_t written = g_ringBuffer.write(
        reinterpret_cast<const uint8_t*>(bufferBytes + offset),
        static_cast<size_t>(length)
    );

    env->ReleaseByteArrayElements(pcmData, bufferBytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_open_yo_player_data_usb_UsbDirectDacManager_clearNativeRingBuffer(
        JNIEnv* env, jobject thiz) {
    g_ringBuffer.reset();
}
