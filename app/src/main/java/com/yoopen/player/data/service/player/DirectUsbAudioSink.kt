package com.yoopen.player.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.yoopen.player.data.preferences.UserPreferencesRepository
import com.yoopen.player.data.usb.UsbDacState
import com.yoopen.player.data.usb.UsbDirectDacManager
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class DirectUsbAudioSink(
    delegate: AudioSink,
    private val usbDacManager: UsbDirectDacManager,
    private val preferences: UserPreferencesRepository
) : ForwardingAudioSink(delegate) {

    private var currentSampleRate = 44100
    private var currentChannels = 2
    private var currentBitDepth = 16
    private var currentEncoding = C.ENCODING_PCM_16BIT

    private var startPresentationTimeUs: Long = C.TIME_UNSET
    private var handledEndOfStream = false

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        currentSampleRate = if (inputFormat.sampleRate != Format.NO_VALUE) inputFormat.sampleRate else 44100
        currentChannels = if (inputFormat.channelCount != Format.NO_VALUE) inputFormat.channelCount else 2
        currentEncoding = inputFormat.pcmEncoding
        currentBitDepth = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        if (usbDacManager.isExclusiveActive) {
            usbDacManager.configureSampleRateAndFormat(
                currentSampleRate,
                currentBitDepth,
                currentChannels
            )
        }

        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (startPresentationTimeUs == C.TIME_UNSET) {
            startPresentationTimeUs = presentationTimeUs
        }

        if (!buffer.hasRemaining()) {
            return true
        }

        val dacState = usbDacManager.dacState.value
        if (dacState is UsbDacState.Connected) {
            if (dacState.sampleRate != currentSampleRate || dacState.bitDepth != currentBitDepth) {
                usbDacManager.configureSampleRateAndFormat(
                    currentSampleRate,
                    currentBitDepth,
                    currentChannels
                )
            }
        }

        val targetBd = (usbDacManager.dacState.value as? UsbDacState.Connected)?.bitDepth ?: 16
        usbDacManager.writeBuffer(
            buffer = buffer,
            pcmEncoding = currentEncoding,
            targetBitDepth = targetBd
        )

        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usbDacManager.isExclusiveActive) {
            return super.getCurrentPositionUs(sourceEnded)
        }

        if (startPresentationTimeUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }

        val playedFrames = usbDacManager.getNativePlayedFrames()
        val sampleRate = if (currentSampleRate > 0) currentSampleRate else 44100
        val playedUs = (playedFrames * 1_000_000L) / sampleRate

        return startPresentationTimeUs + playedUs
    }

    override fun isEnded(): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.isEnded()
        }
        return handledEndOfStream && !hasPendingData()
    }

    override fun hasPendingData(): Boolean {
        if (!usbDacManager.isExclusiveActive) {
            return super.hasPendingData()
        }
        return usbDacManager.getNativeRingBufferAvailable() > 0
    }

    override fun playToEndOfStream() {
        if (!usbDacManager.isExclusiveActive) {
            super.playToEndOfStream()
            return
        }
        handledEndOfStream = true
    }

    override fun play() {
        if (usbDacManager.isExclusiveActive) {
            usbDacManager.setNativePaused(false)
        }
        super.play()
    }

    override fun pause() {
        if (usbDacManager.isExclusiveActive) {
            usbDacManager.setNativePaused(true)
        }
        super.pause()
    }

    override fun flush() {
        startPresentationTimeUs = C.TIME_UNSET
        handledEndOfStream = false
        usbDacManager.safeClearNativeRingBuffer()
        super.flush()
    }

    override fun reset() {
        startPresentationTimeUs = C.TIME_UNSET
        handledEndOfStream = false
        usbDacManager.safeClearNativeRingBuffer()
        super.reset()
    }
}
