package open.yo.player.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import open.yo.player.data.usb.UsbDirectDacManager
import open.yo.player.data.usb.UsbDacState
import open.yo.player.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private var isExclusiveUsbActive = false

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        currentSampleRate = if (inputFormat.sampleRate != Format.NO_VALUE) inputFormat.sampleRate else 44100
        currentChannels = if (inputFormat.channelCount != Format.NO_VALUE) inputFormat.channelCount else 2
        currentBitDepth = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        isExclusiveUsbActive = runBlocking {
            preferences.exclusiveUsbAudioEnabledFlow.first()
        } && usbDacManager.dacState.value is UsbDacState.Connected

        if (isExclusiveUsbActive) {
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
        val dacConnected = usbDacManager.dacState.value is UsbDacState.Connected

        if (isExclusiveUsbActive && dacConnected) {
            val remaining = buffer.remaining()
            if (remaining > 0) {
                val tempArray = ByteArray(remaining)
                val position = buffer.position()
                buffer.get(tempArray)
                buffer.position(position + remaining)

                usbDacManager.writePcmData(tempArray, 0, remaining)
            }
            return true
        }

        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        usbDacManager.clearNativeRingBuffer()
        super.flush()
    }

    override fun pause() {
        usbDacManager.clearNativeRingBuffer()
        super.pause()
    }

    override fun reset() {
        usbDacManager.clearNativeRingBuffer()
        super.reset()
    }
}
