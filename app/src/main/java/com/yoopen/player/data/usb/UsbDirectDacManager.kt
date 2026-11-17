package com.yoopen.player.data.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.yoopen.player.data.logging.PlayerLogBuffer
import com.yoopen.player.data.preferences.UserPreferencesRepository
import com.yoopen.player.di.AppScope
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UsbDacState {
    data object Idle : UsbDacState
    data object PermissionRequested : UsbDacState
    data class Connected(
        val deviceName: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int
    ) : UsbDacState
    data class Error(val message: String) : UsbDacState
}

@Singleton
class UsbDirectDacManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    @AppScope private val scope: CoroutineScope,
    private val logBuffer: PlayerLogBuffer
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _dacState = MutableStateFlow<UsbDacState>(UsbDacState.Idle)
    val dacState: StateFlow<UsbDacState> = _dacState.asStateFlow()

    private val _dacVolumePercent = MutableStateFlow(100)
    val dacVolumePercent: StateFlow<Int> = _dacVolumePercent.asStateFlow()

    private val _isHardwareUacMode = MutableStateFlow(true)
    val isHardwareUacMode: StateFlow<Boolean> = _isHardwareUacMode.asStateFlow()

    private val _isHardwareVolumeControlActive = MutableStateFlow(true)
    val isHardwareVolumeControlActive: StateFlow<Boolean> = _isHardwareVolumeControlActive.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeAudioInterface: UsbInterface? = null
    private var activeEndpoint: UsbEndpoint? = null
    // Parsed from USB Audio Control descriptors in claimAndInitializeDac().
    // -1 means no Feature Unit was found — hardware volume won't be attempted.
    private var activeFeatureUnitId: Int = -1
    private var activeAcInterfaceId: Int = 0
    private var activeClockSourceId: Int = -1

    private var isReceiverRegistered = false
    private var isNativeLibLoaded = false
    private var onPausePlayerCallback: (() -> Unit)? = null

    fun setVolumeMode(isHardwareUac: Boolean) {
        _isHardwareUacMode.value = isHardwareUac
        setHardwareVolume(_dacVolumePercent.value)
    }

    fun setHardwareVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        _dacVolumePercent.value = clamped

        val connection = activeConnection ?: return

        if (_isHardwareUacMode.value) {
            scope.launch(Dispatchers.IO) {
                try {
                    // Convert linear 0-100 % to Q8.8 fixed-point dB for the DAC register.
                    // UAC spec stores volume as signed 16-bit Q8.8: 0x0000 = 0 dB, negative = attenuation.
                    val db = if (clamped == 0) -64.0 else 20.0 * kotlin.math.log10(clamped / 100.0)
                    val rawVolume = (db * 256.0).toInt().coerceIn(-16384, 0)
                    val data = byteArrayOf(
                        (rawVolume and 0xFF).toByte(),
                        ((rawVolume shr 8) and 0xFF).toByte()
                    )

                    // wValue: CS_VOLUME (0x02) << 8 | channel 0 (Master)
                    val wValue = (0x02 shl 8) or 0x00

                    var res = -1

                    if (activeFeatureUnitId >= 0) {
                        // Correct wIndex per USB Audio Class spec:
                        //   high byte = Feature Unit ID
                        //   low byte  = Audio Control interface number
                        val wIndex = (activeFeatureUnitId shl 8) or (activeAcInterfaceId and 0xFF)
                        res = connection.controlTransfer(
                            0x21, UAC_SET_CUR, wValue, wIndex, data, data.size, 500
                        )
                        Timber.d("HW volume SET_CUR FU=%d AC_iface=%d -> %d", activeFeatureUnitId, activeAcInterfaceId, res)
                    }

                    // Fallback: some simpler DACs accept wIndex = streaming interface id.
                    if (res < 0) {
                        val wIndexFallback = activeAudioInterface?.id ?: 0
                        res = connection.controlTransfer(
                            0x21, UAC_SET_CUR, wValue, wIndexFallback, data, data.size, 500
                        )
                        Timber.d("HW volume fallback wIndex=%d -> %d", wIndexFallback, res)
                    }

                    _isHardwareVolumeControlActive.value = res >= 0
                } catch (e: Throwable) {
                    Timber.w(e, "setHardwareVolume failed")
                    _isHardwareVolumeControlActive.value = false
                }
            }
        } else {
            _isHardwareVolumeControlActive.value = false
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.yoopen.player.USB_PERMISSION"
        private const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO
        private const val UAC_SET_CUR = 0x01
        private const val SAMPLING_FREQ_CONTROL = 0x01

        /**
         * Returns the extra descriptor bytes for a UsbInterface.
         * UsbInterface.extraBytes is only available from API 31.
         * On API 30 we fall back to reflection; if that fails we return null
         * and the Feature Unit will be undiscovered (hardware volume falls back gracefully).
         */
        fun getInterfaceExtraBytes(iface: UsbInterface): ByteArray? {
            return try {
                val method = iface.javaClass.getMethod("getExtraBytes")
                method.invoke(iface) as? ByteArray
            } catch (_: Exception) {
                try {
                    val field = UsbInterface::class.java.getDeclaredField("mExtraBytes")
                    field.isAccessible = true
                    field.get(iface) as? ByteArray
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val intentDevice: UsbDevice? = getUsbDeviceFromIntent(intent)
                            // Fallback: On some OEM Android 13/14 ROMs (MIUI/HyperOS, OneUI),
                            // EXTRA_DEVICE can be null in the broadcast intent due to classloader isolation.
                            val device: UsbDevice? = intentDevice ?: usbManager.deviceList.values.find { isAudioDevice(it) }
                            val granted = intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false
                            )
                            if (granted && device != null) {
                                claimAndInitializeDac(device)
                            } else {
                                Timber.w("USB permission denied for device: ${device?.deviceName}")
                                logBuffer.logDac("USB permission denied: ${device?.deviceName}")
                                _dacState.value = UsbDacState.Error("USB permission denied")
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val intentDevice: UsbDevice? = getUsbDeviceFromIntent(intent)
                        val device: UsbDevice? = intentDevice ?: usbManager.deviceList.values.find { isAudioDevice(it) }
                        if (device != null && isAudioDevice(device)) {
                            checkAndConnectDac(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                        if (device == activeDevice || activeDevice == null) {
                            handleDisconnect()
                        }
                    }
                }
            }
        }
    }

    init {
        try {
            System.loadLibrary("usb_dac_native")
            isNativeLibLoaded = true
        } catch (e: Throwable) {
            Timber.w(e, "Could not load usb_dac_native library")
            isNativeLibLoaded = false
        }

        scope.launch {
            preferences.exclusiveUsbAudioEnabledFlow.collect { enabled ->
                if (enabled) {
                    registerReceiverIfNeeded()
                    scanAndConnectAttachedDac()
                } else {
                    handleDisconnect()
                    unregisterReceiverIfNeeded()
                }
            }
        }
    }

    fun setOnPausePlayerCallback(callback: () -> Unit) {
        onPausePlayerCallback = callback
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverIfNeeded() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            isReceiverRegistered = false
        }
    }

    fun scanAndConnectAttachedDac() {
        val deviceList = usbManager.deviceList
        val audioDevice = deviceList.values.firstOrNull { isAudioDevice(it) }
        if (audioDevice != null) {
            checkAndConnectDac(audioDevice)
        }
    }

    private fun checkAndConnectDac(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            claimAndInitializeDac(device)
        } else {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        _dacState.value = UsbDacState.PermissionRequested
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flag)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun claimAndInitializeDac(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Could not open USB connection")

            var outInterface: UsbInterface? = null
            var outEndpoint: UsbEndpoint? = null

            // --- Parse USB Audio Class descriptors to find the Feature Unit ID ---
            // The raw descriptor bytes are in UsbInterface.extraBytes / UsbEndpoint.extraBytes.
            // We scan the Audio Control interface (subclass 1) for Feature Unit (subtype 0x06).
            // Per USB Audio Class spec:
            //   bDescriptorType = 0x24 (CS_INTERFACE)
            //   bDescriptorSubtype = 0x06 (FEATURE_UNIT)
            //   bUnitID = byte[3]
            //   bmaControls: bit 1 (0x02) set on master channel means Volume supported.
            var parsedFeatureUnitId = -1
            var parsedAcInterfaceId = 0
            var parsedClockSourceId = -1

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // Audio Control interface: class=1, subclass=1
                if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == 1) {
                    parsedAcInterfaceId = iface.id
                    val extra: ByteArray = getInterfaceExtraBytes(iface) ?: continue
                    var pos = 0
                    while (pos + 2 < extra.size) {
                        val len = extra[pos].toInt() and 0xFF
                        if (len < 3 || pos + len > extra.size) break
                        val dType    = extra[pos + 1].toInt() and 0xFF
                        val dSubtype = extra[pos + 2].toInt() and 0xFF
                        // CS_INTERFACE (0x24) + FEATURE_UNIT (0x06)
                        if (dType == 0x24 && dSubtype == 0x06 && len >= 5) {
                            val unitId = extra[pos + 3].toInt() and 0xFF
                            // bmaControls[0] = master channel controls (UAC1: 1 byte, UAC2: 4 bytes)
                            // Volume bit = 0x02 (UAC1) or bit 1-0 of bmControls word (UAC2)
                            val masterControls = if (pos + 5 < extra.size) extra[pos + 5].toInt() and 0xFF else 0
                            val hasVolume = (masterControls and 0x02) != 0
                            Timber.d("USB AC descriptor: Feature Unit id=%d masterCtrl=0x%02X hasVolume=%b",
                                unitId, masterControls, hasVolume)
                            // Prefer a Feature Unit that explicitly reports volume support,
                            // but accept any FU if none reports it (some DACs omit the bit).
                            if (parsedFeatureUnitId < 0 || hasVolume) {
                                parsedFeatureUnitId = unitId
                            }
                            if (hasVolume) break // found what we need
                        } else if (dType == 0x24 && dSubtype == 0x0A && len >= 5) {
                            // UAC 2.0 CLOCK_SOURCE (subtype 0x0A)
                            val clockId = extra[pos + 3].toInt() and 0xFF
                            if (parsedClockSourceId < 0) {
                                parsedClockSourceId = clockId
                                Timber.d("USB AC descriptor: Clock Source id=%d", clockId)
                            }
                        }
                        pos += len
                    }
                }
            }
            Timber.i("USB descriptor parse: Feature Unit ID=%d, AC interface=%d",
                parsedFeatureUnitId, parsedAcInterfaceId)

            // --- Find the streaming (OUT) interface and endpoint ---
            // On budget / generic USB DACs (Realtek ALC5686, Conexant CX31993, JL, KT, etc.),
            // alt-setting 0 is often zero-bandwidth (0 endpoints) while alt-setting 1..N has the streaming endpoint,
            // or the interface may declare vendor-specific or standard streaming subclasses.
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_AUDIO) {
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        // Look for OUT endpoint: isochronous (attributes & 0x03 == 1) or any OUT endpoint
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outInterface = iface
                            outEndpoint = ep
                            if ((ep.attributes.toInt() and 0x03) == 1) {
                                // Preferred: explicitly isochronous
                                break
                            }
                        }
                    }
                }
                if (outEndpoint != null && (outEndpoint.attributes.toInt() and 0x03) == 1) break
            }

            // Claim all audio interfaces like tsrossa to guarantee exclusive access and release kernel audio drivers
            val claimedInterfaceIds = mutableSetOf<Int>()
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_AUDIO && claimedInterfaceIds.add(iface.id)) {
                    try {
                        val claimed = connection.claimInterface(iface, true)
                        Timber.d("Claim audio interface %d: %b", iface.id, claimed)
                    } catch (t: Throwable) {
                        Timber.w(t, "Could not force claim audio interface %d", iface.id)
                    }
                }
            }

            if (outInterface != null) {
                // Activate the alternate setting that contains the audio streaming endpoint
                try {
                    connection.setInterface(outInterface)
                } catch (t: Throwable) {
                    Timber.w(t, "Failed to set alternate setting ${outInterface.alternateSetting}")
                }

                activeDevice          = device
                activeConnection      = connection
                activeAudioInterface  = outInterface
                activeEndpoint        = outEndpoint
                activeFeatureUnitId   = parsedFeatureUnitId
                activeAcInterfaceId   = parsedAcInterfaceId
                activeClockSourceId   = parsedClockSourceId

                val nativeFd = connection.fileDescriptor
                val ep = outEndpoint
                if (isNativeLibLoaded && ep != null) {
                    try {
                        // ep.address includes the direction bit (bit 7 = 0 for OUT).
                        // This is what the kernel endpoint field in usbdevfs_urb expects.
                        initNativeDac(nativeFd, ep.address, ep.maxPacketSize)
                    } catch (t: Throwable) {
                        Timber.w(t, "Native DAC init skipped")
                    }
                }

                val dacName = device.productName ?: "USB Audio DAC"
                _dacState.value = UsbDacState.Connected(
                    deviceName = dacName,
                    sampleRate = 44100,
                    bitDepth = 16,
                    channels = 2
                )
                logBuffer.logDac("DAC connected: $dacName (FD=$nativeFd, EP=0x${Integer.toHexString(ep?.address ?: 0)})")
                Timber.i("USB DAC connected: ${device.productName} FD=$nativeFd EP=0x%02X FU=$parsedFeatureUnitId",
                    ep?.address ?: 0)
            } else {
                connection.close()
                _dacState.value = UsbDacState.Error("Failed to claim USB audio interface")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error initializing USB DAC")
            _dacState.value = UsbDacState.Error(e.message ?: "DAC init error")
        }
    }

    val isExclusiveActive: Boolean
        get() = _dacState.value is UsbDacState.Connected

    fun configureSampleRateAndFormat(sampleRate: Int, bitDepth: Int, channels: Int) {
        if (isNativeLibLoaded) {
            try {
                setNativeFormat(sampleRate, bitDepth, channels)
            } catch (t: Throwable) {
                Timber.w(t, "Native setFormat skipped")
            }
        }

        activeDevice?.let { dev ->
            val dacName = dev.productName ?: "USB Audio DAC"
            _dacState.value = UsbDacState.Connected(
                deviceName = dacName,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels
            )
            logBuffer.logDac("DAC configure: $dacName -> ${sampleRate}Hz, ${bitDepth}-bit, ${channels}ch")
        }

        val connection = activeConnection ?: return
        val iface = activeAudioInterface ?: return
        val ep = activeEndpoint

        scope.launch(Dispatchers.IO) {
            try {
                // 3-byte sample rate format for UAC 1.0 (little-endian)
                val data3 = byteArrayOf(
                    (sampleRate and 0xFF).toByte(),
                    ((sampleRate shr 8) and 0xFF).toByte(),
                    ((sampleRate shr 16) and 0xFF).toByte()
                )

                val epAddress = ep?.address ?: 0x01
                // Try UAC 1.0 endpoint sampling frequency control (bmRequestType = 0x22, wIndex = epAddress)
                var res = connection.controlTransfer(
                    0x22,
                    UAC_SET_CUR,
                    (SAMPLING_FREQ_CONTROL shl 8) or 0x00,
                    epAddress,
                    data3,
                    data3.size,
                    500
                )

                // Fallback to interface-targeted control transfer if endpoint control was rejected
                if (res < 0) {
                    res = connection.controlTransfer(
                        0x21,
                        UAC_SET_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x01,
                        iface.id,
                        data3,
                        data3.size,
                        500
                    )
                }

                // If still rejected, try 4-byte UAC 2.0 clock source format
                if (res < 0) {
                    val data4 = byteArrayOf(
                        (sampleRate and 0xFF).toByte(),
                        ((sampleRate shr 8) and 0xFF).toByte(),
                        ((sampleRate shr 16) and 0xFF).toByte(),
                        ((sampleRate shr 24) and 0xFF).toByte()
                    )
                    // If Clock Source was resolved, use (clockSourceId << 8) | acInterfaceId per UAC2 spec
                    val wIndexUac2 = if (activeClockSourceId >= 0) {
                        (activeClockSourceId shl 8) or (activeAcInterfaceId and 0xFF)
                    } else {
                        iface.id
                    }
                    res = connection.controlTransfer(
                        0x21,
                        UAC_SET_CUR,
                        (SAMPLING_FREQ_CONTROL shl 8) or 0x01,
                        wIndexUac2,
                        data4,
                        data4.size,
                        500
                    )
                    if (res < 0 && wIndexUac2 != iface.id) {
                        // Fallback to streaming interface id
                        connection.controlTransfer(
                            0x21,
                            UAC_SET_CUR,
                            (SAMPLING_FREQ_CONTROL shl 8) or 0x01,
                            iface.id,
                            data4,
                            data4.size,
                            500
                        )
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Failed to set DAC sample rate: $sampleRate")
            }
        }
    }

    fun handleDisconnect() {
        scope.launch(Dispatchers.Main) {
            onPausePlayerCallback?.invoke()
        }
        
        try {
            if (isNativeLibLoaded) {
                try {
                    stopNativeDac()
                } catch (t: Throwable) {
                    Timber.w(t, "Native stopDac error")
                }
            }
            activeAudioInterface?.let { activeConnection?.releaseInterface(it) }
            activeConnection?.close()
        } catch (e: Throwable) {
            Timber.e(e, "Error releasing USB resources")
        } finally {
            logBuffer.logDac("DAC disconnected")
            activeDevice = null
            activeConnection = null
            activeAudioInterface = null
            activeEndpoint = null
            _dacState.value = UsbDacState.Idle
        }
    }

    fun safeWritePcmData(pcmData: ByteArray, offset: Int, length: Int): Int {
        if (!isNativeLibLoaded) return 0
        return try {
            writePcmData(pcmData, offset, length)
        } catch (t: Throwable) {
            0
        }
    }

    fun safeClearNativeRingBuffer() {
        if (!isNativeLibLoaded) return
        try {
            clearNativeRingBuffer()
        } catch (t: Throwable) {
            // Ignored safely
        }
    }

    fun getNativePlayedFrames(): Long {
        if (!isNativeLibLoaded) return 0L
        return try {
            getNativePlayedFrames()
        } catch (t: Throwable) {
            0L
        }
    }

    fun getNativeRingBufferAvailable(): Int {
        if (!isNativeLibLoaded) return 0
        return try {
            getNativeRingBufferAvailable()
        } catch (t: Throwable) {
            0
        }
    }

    fun setNativePaused(paused: Boolean) {
        if (!isNativeLibLoaded) return
        try {
            setNativePaused(paused)
        } catch (t: Throwable) {
            // Ignored
        }
    }

    fun writeBuffer(buffer: ByteBuffer, pcmEncoding: Int, targetBitDepth: Int): Int {
        if (!isNativeLibLoaded || !buffer.hasRemaining()) return 0
        val pos = buffer.position()
        val remaining = buffer.remaining()
        val consumed = if (buffer.isDirect) {
            try {
                writeDirectBuffer(buffer, pos, remaining, pcmEncoding, targetBitDepth)
            } catch (t: Throwable) {
                0
            }
        } else if (buffer.hasArray()) {
            try {
                writeByteArrayFormat(buffer.array(), buffer.arrayOffset() + pos, remaining, pcmEncoding, targetBitDepth)
            } catch (t: Throwable) {
                0
            }
        } else {
            val chunk = minOf(remaining, 16384)
            val temp = ByteArray(chunk)
            val mark = buffer.position()
            buffer.get(temp, 0, chunk)
            buffer.position(mark)
            try {
                writeByteArrayFormat(temp, 0, chunk, pcmEncoding, targetBitDepth)
            } catch (t: Throwable) {
                0
            }
        }
        if (consumed > 0) {
            buffer.position(pos + consumed)
        }
        return consumed
    }

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    // Native JNI bindings
    private external fun initNativeDac(fd: Int, epOut: Int, maxPacketSize: Int): Boolean
    private external fun setNativeFormat(sampleRate: Int, bitDepth: Int, channels: Int): Boolean
    private external fun stopNativeDac(): Boolean
    private external fun writePcmData(pcmData: ByteArray, offset: Int, length: Int): Int
    private external fun clearNativeRingBuffer()
    private external fun getNativePlayedFrames(): Long
    private external fun getNativeRingBufferAvailable(): Int
    private external fun setNativePaused(paused: Boolean)
    private external fun writeDirectBuffer(
        buffer: ByteBuffer,
        pos: Int,
        len: Int,
        pcmEncoding: Int,
        targetBitDepth: Int
    ): Int
    private external fun writeByteArrayFormat(
        data: ByteArray,
        offset: Int,
        length: Int,
        pcmEncoding: Int,
        targetBitDepth: Int
    ): Int
}
