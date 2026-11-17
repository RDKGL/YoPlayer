#include <jni.h>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <poll.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/mman.h>
#include <pthread.h>
#include <sched.h>
#include <linux/usbdevice_fs.h>
#include "ring_buffer.h"

#define LOG_TAG "UsbDacNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static const size_t RING_BUFFER_SIZE = 1024 * 1024; // 1 MB high-capacity lock-free buffer
static LockFreeRingBuffer g_ringBuffer(RING_BUFFER_SIZE);

static std::atomic<bool> g_isRunning{false};
static std::atomic<bool> g_isPaused{false};
static std::atomic<int64_t> g_playedFrames{0};
static std::thread g_workerThread;
static std::atomic<int> g_nativeFd{-1};
static std::atomic<int> g_epOut{0};       // endpoint address (e.g. 0x01 or 0x81 — OUT = bit7 clear)
static std::atomic<int> g_maxPacketSize{512};
static std::atomic<int> g_sampleRate{44100};
static std::atomic<int> g_bitDepth{16};
static std::atomic<int> g_channels{2};

// ---------------------------------------------------------------------------
// Isochronous USB audio transfer loop
//
// UAC1 / UAC2 DACs use Isochronous OUT endpoints — not Bulk.
// Full-Speed ISO: one packet per 1 ms frame, max 1023 bytes.
// High-Speed ISO: one packet per 125 µs micro-frame, max 1024 bytes.
//
// Flow:
//   1. Build a usbdevfs_urb with type ISO, fill iso_frame_desc[].
//   2. USBDEVFS_SUBMITURB  — hand the URB to the kernel.
//   3. poll(fd, POLLOUT)   — wait for the kernel to signal completion.
//   4. USBDEVFS_REAPURBNDELAY — harvest the completed URB and check status.
// ---------------------------------------------------------------------------
static void audio_transfer_loop() {
    pthread_setname_np(pthread_self(), "YoPlayerIso");

    // Realtime optimizations inspired by tsrossa:
    // 1. Minimum timer slack (1ns) prevents kernel from coalescing wakeups when demoted to background
    prctl(PR_SET_TIMERSLACK, 1, 0, 0, 0);

    // 2. High priority nice level (-20) ensures realtime delivery without stutter
    setpriority(PRIO_PROCESS, 0, -20);
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    if (param.sched_priority > 2) param.sched_priority = 2;
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);

    // 3. Pin to all online CPU cores so Android cpuset/cgroup migration never starves this audio transport
    long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (num_cpus > 0) {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        for (int c = 0; c < num_cpus; c++) CPU_SET(c, &cpuset);
        sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
    }

    int initFd = g_nativeFd.load();
    int initEp = g_epOut.load();
    int initMaxPkt = g_maxPacketSize.load();
    LOGI("ISO audio transfer thread started (FD=%d, EP=0x%02X, maxPacketSize=%d)",
         initFd, initEp, initMaxPkt);

    // Each URB carries exactly one ISO packet (simplest correct approach).
    const int NUM_ISO_PACKETS = 1;
    const size_t MAX_ISO_BUFFER = 2048;

    const size_t urbSize = sizeof(struct usbdevfs_urb)
                           + NUM_ISO_PACKETS * sizeof(struct usbdevfs_iso_packet_desc);
    std::vector<uint8_t> urbBuf(urbSize, 0);
    std::vector<uint8_t> pcmBuf(MAX_ISO_BUFFER, 0);

    // Lock PCM and URB buffers in RAM so Linux page pager never causes swap latency stutters
    mlock(urbBuf.data(), urbSize);
    mlock(pcmBuf.data(), MAX_ISO_BUFFER);

    struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(urbBuf.data());

    int accumulatedSamples = 0;

    while (g_isRunning.load(std::memory_order_relaxed)) {
        int fd = g_nativeFd.load(std::memory_order_relaxed);
        int ep = g_epOut.load(std::memory_order_relaxed);
        if (fd < 0 || ep == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        int sr = g_sampleRate.load(std::memory_order_relaxed);
        int bd = g_bitDepth.load(std::memory_order_relaxed);
        int ch = g_channels.load(std::memory_order_relaxed);
        if (sr <= 0) sr = 44100;
        if (bd <= 0) bd = 16;
        if (ch <= 0) ch = 2;

        int bytesPerSample = (bd <= 16) ? 2 : ((bd <= 24) ? 3 : 4);
        int bytesPerFrame = ch * bytesPerSample;

        accumulatedSamples += sr;
        int samplesThisPacket = accumulatedSamples / 1000;
        accumulatedSamples %= 1000;

        int currentPktSize = samplesThisPacket * bytesPerFrame;
        int maxPkt = g_maxPacketSize.load(std::memory_order_relaxed);
        if (maxPkt <= 0 || maxPkt > 2048) maxPkt = 1024;
        if (currentPktSize > maxPkt) currentPktSize = maxPkt;
        if (currentPktSize <= 0) currentPktSize = bytesPerFrame;

        // Pull PCM from ring buffer or fill with silence if paused.
        if (g_isPaused.load(std::memory_order_relaxed)) {
            memset(pcmBuf.data(), 0, currentPktSize);
        } else {
            size_t to_read = g_ringBuffer.read(pcmBuf.data(), currentPktSize);
            if (to_read > 0) {
                int frames = static_cast<int>(to_read / bytesPerFrame);
                g_playedFrames.fetch_add(frames, std::memory_order_relaxed);
            }
        }

        // Fill the ISO URB.
        memset(urbBuf.data(), 0, urbSize);
        urb->type            = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint        = static_cast<unsigned char>(ep & 0xFF);
        urb->buffer          = pcmBuf.data();
        urb->buffer_length   = currentPktSize;
        urb->number_of_packets = NUM_ISO_PACKETS;
        urb->iso_frame_desc[0].length = currentPktSize;

        // Submit URB to the Linux kernel USB subsystem.
        int ret = ioctl(fd, USBDEVFS_SUBMITURB, urb);
        if (ret < 0) {
            int err = errno;
            if (err == ENODEV || err == ENOENT || err == ESHUTDOWN) {
                LOGE("SUBMITURB: device gone (errno=%d), stopping", err);
                break;
            }
            LOGW("SUBMITURB failed: errno=%d, sleeping", err);
            std::this_thread::sleep_for(std::chrono::microseconds(500));
            continue;
        }

        // Wait for the kernel to signal the URB is done.
        // poll() with POLLOUT means "URB completed" on a usbfs fd.
        struct pollfd pfd = { fd, POLLOUT, 0 };
        int pollRet = poll(&pfd, 1, /*timeout_ms=*/50);
        if (pollRet <= 0) {
            // Timeout or error — discard URB to avoid a stuck kernel queue.
            ioctl(fd, USBDEVFS_DISCARDURB, urb);
            // Drain the completed URB slot so the kernel doesn't leak it.
            struct usbdevfs_urb* reaped = nullptr;
            ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
            continue;
        }

        // Reap the completed URB.
        struct usbdevfs_urb* reaped = nullptr;
        ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (ret < 0) {
            int err = errno;
            if (err == EAGAIN) {
                // Should not happen after poll succeeded, but handle gracefully.
                continue;
            }
            if (err == ENODEV || err == ESHUTDOWN) {
                LOGE("REAPURB: device gone");
                break;
            }
        }
    }

    LOGI("ISO audio transfer thread stopped");
}

// ---------------------------------------------------------------------------
// JNI implementation
// ---------------------------------------------------------------------------

static jboolean impl_initNativeDac(JNIEnv*, jobject, jint fd, jint epOut, jint maxPacketSize) {
    // Stop any running thread first.
    if (g_isRunning.load()) {
        g_isRunning.store(false);
        if (g_workerThread.joinable()) {
            g_workerThread.join();
        }
    }

    g_nativeFd.store(fd);
    g_epOut.store(epOut);
    g_maxPacketSize.store(maxPacketSize);
    g_ringBuffer.reset();
    g_playedFrames.store(0, std::memory_order_relaxed);
    g_isPaused.store(false, std::memory_order_relaxed);

    g_isRunning.store(true);
    g_workerThread = std::thread(audio_transfer_loop);
    LOGI("Native DAC initialized: FD=%d, EP=0x%02X, maxPkt=%d", fd, epOut, maxPacketSize);
    return JNI_TRUE;
}

static jboolean impl_setNativeFormat(JNIEnv*, jobject, jint sampleRate, jint bitDepth, jint channels) {
    g_sampleRate.store(sampleRate);
    g_bitDepth.store(bitDepth);
    g_channels.store(channels);
    LOGI("Native DAC format: %d Hz, %d-bit, %dch", sampleRate, bitDepth, channels);
    return JNI_TRUE;
}

static jboolean impl_stopNativeDac(JNIEnv*, jobject) {
    g_isRunning.store(false);
    if (g_workerThread.joinable()) {
        g_workerThread.join();
    }
    g_ringBuffer.reset();
    g_playedFrames.store(0, std::memory_order_relaxed);
    g_isPaused.store(false, std::memory_order_relaxed);
    g_nativeFd.store(-1);
    g_epOut.store(0);
    LOGI("Native DAC stopped");
    return JNI_TRUE;
}

static void impl_clearNativeRingBuffer(JNIEnv*, jobject) {
    g_ringBuffer.reset();
    g_playedFrames.store(0, std::memory_order_relaxed);
}

static jlong impl_getNativePlayedFrames(JNIEnv*, jobject) {
    return static_cast<jlong>(g_playedFrames.load(std::memory_order_relaxed));
}

static jint impl_getNativeRingBufferAvailable(JNIEnv*, jobject) {
    return static_cast<jint>(g_ringBuffer.available());
}

static void impl_setNativePaused(JNIEnv*, jobject, jboolean paused) {
    g_isPaused.store(paused);
}

static size_t process_and_write_pcm(
    const uint8_t* src,
    size_t length,
    int pcmEncoding,
    int targetBitDepth
) {
    if (!g_isRunning.load(std::memory_order_relaxed) || length == 0 || src == nullptr) {
        return 0;
    }

    if (targetBitDepth <= 0) targetBitDepth = 16;

    if (pcmEncoding == 2) {
        // Source is 16-bit integer PCM
        if (targetBitDepth <= 16) {
            return g_ringBuffer.write(src, length);
        } else {
            // Target 24-bit (3 bytes per sample)
            const int16_t* in16 = reinterpret_cast<const int16_t*>(src);
            size_t numSamples = length / sizeof(int16_t);
            size_t samplesProcessed = 0;
            uint8_t tempBuf[3072];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    int16_t s = in16[samplesProcessed + i];
                    tempBuf[3 * i + 0] = 0;
                    tempBuf[3 * i + 1] = static_cast<uint8_t>(s & 0xFF);
                    tempBuf[3 * i + 2] = static_cast<uint8_t>((s >> 8) & 0xFF);
                }
                size_t written = g_ringBuffer.write(tempBuf, chunk * 3);
                size_t sw = written / 3;
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * sizeof(int16_t);
        }
    } else if (pcmEncoding == 4) {
        // Source is IEEE 754 Float32 PCM
        const float* fsrc = reinterpret_cast<const float*>(src);
        size_t numSamples = length / sizeof(float);
        size_t samplesProcessed = 0;

        if (targetBitDepth <= 16) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    float v = fsrc[samplesProcessed + i];
                    if (v > 1.0f) v = 1.0f;
                    else if (v < -1.0f) v = -1.0f;
                    tempBuf[i] = static_cast<int16_t>(v * 32767.0f);
                }
                size_t written = g_ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
        } else {
            // Target 24-bit
            uint8_t tempBuf[3072];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    float v = fsrc[samplesProcessed + i];
                    if (v > 1.0f) v = 1.0f;
                    else if (v < -1.0f) v = -1.0f;
                    int32_t val = static_cast<int32_t>(v * 8388607.0f);
                    tempBuf[3 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    tempBuf[3 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    tempBuf[3 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                size_t written = g_ringBuffer.write(tempBuf, chunk * 3);
                size_t sw = written / 3;
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
        }
        return samplesProcessed * sizeof(float);
    } else if (pcmEncoding == 21 || pcmEncoding == 0x20000000) {
        // Source is 24-bit packed PCM (3 bytes per sample)
        size_t numSamples = length / 3;
        size_t samplesProcessed = 0;
        if (targetBitDepth <= 16) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    uint8_t mid = src[3 * (samplesProcessed + i) + 1];
                    uint8_t msb = src[3 * (samplesProcessed + i) + 2];
                    tempBuf[i] = static_cast<int16_t>(mid | (msb << 8));
                }
                size_t written = g_ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * 3;
        } else {
            return g_ringBuffer.write(src, length);
        }
    } else if (pcmEncoding == 22 || pcmEncoding == 0x30000000) {
        // Source is 32-bit int PCM (4 bytes per sample)
        size_t numSamples = length / 4;
        size_t samplesProcessed = 0;
        if (targetBitDepth <= 16) {
            int16_t tempBuf[1024];
            while (samplesProcessed < numSamples) {
                size_t chunk = std::min(numSamples - samplesProcessed, (size_t)1024);
                for (size_t i = 0; i < chunk; ++i) {
                    uint8_t mid = src[4 * (samplesProcessed + i) + 2];
                    uint8_t msb = src[4 * (samplesProcessed + i) + 3];
                    tempBuf[i] = static_cast<int16_t>(mid | (msb << 8));
                }
                size_t written = g_ringBuffer.write(reinterpret_cast<const uint8_t*>(tempBuf), chunk * sizeof(int16_t));
                size_t sw = written / sizeof(int16_t);
                samplesProcessed += sw;
                if (sw < chunk) break;
            }
            return samplesProcessed * 4;
        } else {
            return g_ringBuffer.write(src, length);
        }
    } else {
        return g_ringBuffer.write(src, length);
    }
}

static jint impl_writePcmData(JNIEnv* env, jobject, jbyteArray pcmData, jint offset, jint length) {
    if (!g_isRunning.load() || length <= 0) return 0;

    jbyte* bytes = env->GetByteArrayElements(pcmData, nullptr);
    if (!bytes) return 0;

    size_t written = g_ringBuffer.write(
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length)
    );

    env->ReleaseByteArrayElements(pcmData, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

static jint impl_writeDirectBuffer(
    JNIEnv* env, jobject, jobject byteBuf, jint pos, jint len, jint pcmEncoding, jint targetBitDepth
) {
    if (!byteBuf || len <= 0) return 0;
    uint8_t* base = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuf));
    if (!base) return 0;
    return static_cast<jint>(process_and_write_pcm(base + pos, static_cast<size_t>(len), pcmEncoding, targetBitDepth));
}

static jint impl_writeByteArrayFormat(
    JNIEnv* env, jobject, jbyteArray pcmData, jint offset, jint length, jint pcmEncoding, jint targetBitDepth
) {
    if (!pcmData || length <= 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(pcmData, nullptr);
    if (!bytes) return 0;
    size_t processed = process_and_write_pcm(
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length),
        pcmEncoding,
        targetBitDepth
    );
    env->ReleaseByteArrayElements(pcmData, bytes, JNI_ABORT);
    return static_cast<jint>(processed);
}

// ---------------------------------------------------------------------------
// JNI exports — two package names (com.yoopen.player and open.yo.player)
// ---------------------------------------------------------------------------

#define EXPORT_PAIR(pkg) \
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_initNativeDac(JNIEnv* e, jobject t, jint fd, jint ep, jint pkt) \
{ return impl_initNativeDac(e, t, fd, ep, pkt); } \
\
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_setNativeFormat(JNIEnv* e, jobject t, jint sr, jint bd, jint ch) \
{ return impl_setNativeFormat(e, t, sr, bd, ch); } \
\
extern "C" JNIEXPORT jboolean JNICALL \
Java_##pkg##_UsbDirectDacManager_stopNativeDac(JNIEnv* e, jobject t) \
{ return impl_stopNativeDac(e, t); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_writePcmData(JNIEnv* e, jobject t, jbyteArray d, jint off, jint len) \
{ return impl_writePcmData(e, t, d, off, len); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_clearNativeRingBuffer(JNIEnv* e, jobject t) \
{ impl_clearNativeRingBuffer(e, t); } \
\
extern "C" JNIEXPORT jlong JNICALL \
Java_##pkg##_UsbDirectDacManager_getNativePlayedFrames(JNIEnv* e, jobject t) \
{ return impl_getNativePlayedFrames(e, t); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_getNativeRingBufferAvailable(JNIEnv* e, jobject t) \
{ return impl_getNativeRingBufferAvailable(e, t); } \
\
extern "C" JNIEXPORT void JNICALL \
Java_##pkg##_UsbDirectDacManager_setNativePaused(JNIEnv* e, jobject t, jboolean p) \
{ impl_setNativePaused(e, t, p); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_writeDirectBuffer(JNIEnv* e, jobject t, jobject b, jint pos, jint len, jint enc, jint targetBd) \
{ return impl_writeDirectBuffer(e, t, b, pos, len, enc, targetBd); } \
\
extern "C" JNIEXPORT jint JNICALL \
Java_##pkg##_UsbDirectDacManager_writeByteArrayFormat(JNIEnv* e, jobject t, jbyteArray d, jint off, jint len, jint enc, jint targetBd) \
{ return impl_writeByteArrayFormat(e, t, d, off, len, enc, targetBd); }

EXPORT_PAIR(com_yoopen_player_data_usb)
EXPORT_PAIR(open_yo_player_data_usb)
