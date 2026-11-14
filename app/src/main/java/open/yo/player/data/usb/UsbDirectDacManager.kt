package open.yo.player.data.usb

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
import open.yo.player.data.preferences.UserPreferencesRepository
import open.yo.player.di.AppScope
import timber.log.Timber
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
    @AppScope private val scope: CoroutineScope
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _dacState = MutableStateFlow<UsbDacState>(UsbDacState.Idle)
    val dacState: StateFlow<UsbDacState> = _dacState.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeAudioInterface: UsbInterface? = null
    private var activeEndpoint: UsbEndpoint? = null

    private var isReceiverRegistered = false
    private var isNativeLibLoaded = false
    private var onPausePlayerCallback: (() -> Unit)? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "open.yo.player.USB_PERMISSION"
        private const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO
        private const val UAC_SET_CUR = 0x01
        private const val SAMPLING_FREQ_CONTROL = 0x01
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device: UsbDevice? = getUsbDeviceFromIntent(intent)
                            val granted = intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false
                            )
                            if (granted && device != null) {
                                claimAndInitializeDac(device)
                            } else {
                                Timber.w("USB permission denied for device: ${device?.deviceName}")
                                _dacState.value = UsbDacState.Error("USB permission denied")
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = getUsbDeviceFromIntent(intent)
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
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_AUDIO) {
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outInterface = iface
                            outEndpoint = ep
                            break
                        }
                    }
                }
                if (outEndpoint != null) break
            }

            if (outInterface != null && connection.claimInterface(outInterface, true)) {
                activeDevice = device
                activeConnection = connection
                activeAudioInterface = outInterface
                activeEndpoint = outEndpoint

                val nativeFd = connection.fileDescriptor
                val ep = outEndpoint
                if (isNativeLibLoaded && ep != null) {
                    try {
                        initNativeDac(nativeFd, ep.endpointNumber, ep.maxPacketSize)
                    } catch (t: Throwable) {
                        Timber.w(t, "Native DAC init skipped")
                    }
                }

                _dacState.value = UsbDacState.Connected(
                    deviceName = device.productName ?: "USB Audio DAC",
                    sampleRate = 44100,
                    bitDepth = 16,
                    channels = 2
                )
                Timber.i("Direct USB DAC connected: ${device.productName} (FD: $nativeFd)")
            } else {
                connection.close()
                _dacState.value = UsbDacState.Error("Failed to claim USB audio interface")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error initializing USB DAC")
            _dacState.value = UsbDacState.Error(e.message ?: "DAC init error")
        }
    }

    fun configureSampleRateAndFormat(sampleRate: Int, bitDepth: Int, channels: Int) {
        val connection = activeConnection ?: return
        val iface = activeAudioInterface ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val data = byteArrayOf(
                    (sampleRate and 0xFF).toByte(),
                    ((sampleRate shr 8) and 0xFF).toByte(),
                    ((sampleRate shr 16) and 0xFF).toByte()
                )
                connection.controlTransfer(
                    0x22,
                    UAC_SET_CUR,
                    (SAMPLING_FREQ_CONTROL shl 8) or 0x01,
                    iface.id,
                    data,
                    data.size,
                    500
                )
                if (isNativeLibLoaded) {
                    try {
                        setNativeFormat(sampleRate, bitDepth, channels)
                    } catch (t: Throwable) {
                        Timber.w(t, "Native setFormat skipped")
                    }
                }

                activeDevice?.let { dev ->
                    _dacState.value = UsbDacState.Connected(
                        deviceName = dev.productName ?: "USB Audio DAC",
                        sampleRate = sampleRate,
                        bitDepth = bitDepth,
                        channels = channels
                    )
                }
            } catch (e: Throwable) {
                Timber.e(e, "Failed to set DAC sample rate: $sampleRate")
            }
        }
    }

    fun handleDisconnect() {
        onPausePlayerCallback?.invoke()
        
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
}
