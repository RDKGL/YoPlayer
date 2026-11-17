package com.yoopen.player.data.soulseek

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yoopen.player.data.worker.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class SoulseekManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager
) {
    companion object {
        private const val TAG = "SoulseekManager"
        private const val SERVER_HOST = "server.slsknet.org"
        private const val SERVER_PORT = 2242
        private const val PREFS_NAME = "soulseek_prefs"
        private const val KEY_USERNAME = "slsk_username"
        private const val KEY_PASSWORD = "slsk_password"
        private const val MAX_SEARCH_RESULTS = 1200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val tokenCounter = AtomicInteger(Random.nextInt(10_000, 900_000))

    private val _connectionState = MutableStateFlow<SoulseekConnectionState>(SoulseekConnectionState.Disconnected)
    val connectionState: StateFlow<SoulseekConnectionState> = _connectionState.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SoulseekSearchResult>>(emptyList())
    val searchResults: StateFlow<List<SoulseekSearchResult>> = _searchResults.asStateFlow()

    private val _downloads = MutableStateFlow<List<SoulseekDownloadItem>>(emptyList())
    val downloads: StateFlow<List<SoulseekDownloadItem>> = _downloads.asStateFlow()

    private val serverWriteLock = Any()
    @Volatile
    private var serverSocket: Socket? = null
    @Volatile
    private var serverOut: OutputStream? = null
    @Volatile
    private var listenServerSocket: ServerSocket? = null
    @Volatile
    private var listenPort: Int = 0

    private var connectionJob: Job? = null
    private var pingJob: Job? = null
    private var listenJob: Job? = null
    private var searchTimeoutJob: Job? = null

    @Volatile
    private var activeSearchToken: Int = -1
    private val seenResultKeys = ConcurrentHashMap.newKeySet<String>()

    private val peerAddressWaiters = ConcurrentHashMap<String, CompletableDeferred<Pair<String, Int>>>()
    private val activeDownloadByKey = ConcurrentHashMap<String, String>()
    private val pendingTransferByToken = ConcurrentHashMap<Int, PendingTransfer>()
    private val activeDownloadSockets = ConcurrentHashMap<String, Socket>()

    private data class PendingTransfer(
        val downloadId: String,
        val username: String,
        val filename: String,
        val fileSize: Long
    )

    fun getUsername(): String {
        val saved = prefs.getString(KEY_USERNAME, null)
        if (!saved.isNullOrBlank()) return saved
        val generated = "yo_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val generatedPass = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit()
            .putString(KEY_USERNAME, generated)
            .putString(KEY_PASSWORD, generatedPass)
            .apply()
        return generated
    }

    private fun getPassword(): String {
        getUsername()
        return prefs.getString(KEY_PASSWORD, "") ?: ""
    }

    fun updateCredentials(username: String, password: String) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isBlank() || cleanPass.isBlank()) return
        prefs.edit()
            .putString(KEY_USERNAME, cleanUser)
            .putString(KEY_PASSWORD, cleanPass)
            .apply()
        disconnect()
        ensureConnected()
    }

    fun ensureConnected() {
        val current = _connectionState.value
        if (current is SoulseekConnectionState.Connected || current is SoulseekConnectionState.Connecting) {
            return
        }
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectLoop()
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        pingJob?.cancel()
        listenJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { listenServerSocket?.close() }
        serverSocket = null
        serverOut = null
        listenServerSocket = null
        _connectionState.value = SoulseekConnectionState.Disconnected
    }

    private fun startListenSocket() {
        listenJob?.cancel()
        runCatching { listenServerSocket?.close() }
        try {
            val ss = ServerSocket(0)
            listenServerSocket = ss
            listenPort = ss.localPort
            listenJob = scope.launch {
                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    scope.launch { handleIncomingPeerSocket(client) }
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to open local listen socket")
            listenPort = 2234
        }
    }

    private suspend fun connectLoop() {
        _connectionState.value = SoulseekConnectionState.Connecting
        val username = getUsername()
        val password = getPassword()

        try {
            startListenSocket()
            val socket = Socket()
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 12_000)
            serverSocket = socket
            serverOut = socket.getOutputStream()

            sendToServer(SoulseekProtocol.buildLoginPacket(username, password))

            val input = socket.getInputStream()
            while (scope.isActive && !socket.isClosed) {
                val frame = SoulseekProtocol.readMessageFrame(input) ?: break
                handleServerFrame(frame, username)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Soulseek server connection closed")
            if (_connectionState.value !is SoulseekConnectionState.Error) {
                _connectionState.value = SoulseekConnectionState.Error(t.message ?: "Ошибка подключения к Soulseek")
            }
        } finally {
            pingJob?.cancel()
            runCatching { serverSocket?.close() }
            serverSocket = null
            serverOut = null
            if (_connectionState.value is SoulseekConnectionState.Connected ||
                _connectionState.value is SoulseekConnectionState.Connecting
            ) {
                _connectionState.value = SoulseekConnectionState.Disconnected
            }
        }
    }

    private fun sendToServer(bytes: ByteArray): Boolean {
        return synchronized(serverWriteLock) {
            try {
                val out = serverOut ?: return false
                out.write(bytes)
                out.flush()
                true
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed to write to Soulseek server")
                false
            }
        }
    }

    private fun handleServerFrame(frame: ByteArray, username: String) {
        if (frame.size < 4) return
        val reader = SlskReader(frame)
        val code = reader.readInt()
        when (code) {
            SoulseekProtocol.MSG_LOGIN -> {
                val succeeded = reader.readByte() == 1
                if (succeeded) {
                    Timber.tag(TAG).i("Logged in to Soulseek as %s", username)
                    _connectionState.value = SoulseekConnectionState.Connected(username)
                    if (listenPort > 0) {
                        sendToServer(SoulseekProtocol.buildSetWaitPortPacket(listenPort))
                    }
                    sendToServer(SoulseekProtocol.buildHaveNoParentsPacket(true))
                    sendToServer(SoulseekProtocol.buildSharedFoldersFilesPacket(1, 25))

                    pingJob?.cancel()
                    pingJob = scope.launch {
                        while (isActive && _connectionState.value is SoulseekConnectionState.Connected) {
                            delay(55_000L)
                            sendToServer(SoulseekProtocol.buildPingPacket())
                        }
                    }
                } else {
                    val reason = runCatching { reader.readString() }.getOrDefault("Login failed")
                    Timber.tag(TAG).w("Soulseek login failed: %s", reason)
                    _connectionState.value = SoulseekConnectionState.Error("Ошибка входа: $reason")
                    runCatching { serverSocket?.close() }
                }
            }

            SoulseekProtocol.MSG_GET_PEER_ADDRESS -> {
                val peerUser = reader.readString()
                val ipInt = reader.readInt()
                val port = reader.readInt()
                val ip = SoulseekProtocol.intToIpAddress(ipInt)
                peerAddressWaiters.remove(peerUser)?.complete(ip to port)
            }

            SoulseekProtocol.MSG_CONNECT_TO_PEER -> {
                val peerUser = reader.readString()
                val connType = reader.readString()
                val ipInt = reader.readInt()
                val port = reader.readInt()
                val token = reader.readInt()
                val ip = SoulseekProtocol.intToIpAddress(ipInt)
                if (port in 1..65535 && ip != "0.0.0.0") {
                    scope.launch {
                        handleConnectToPeer(peerUser, connType, ip, port, token)
                    }
                }
            }
        }
    }

    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        scope.launch {
            if (_connectionState.value !is SoulseekConnectionState.Connected) {
                ensureConnected()
                var waited = 0
                while (_connectionState.value !is SoulseekConnectionState.Connected && waited < 8000) {
                    if (_connectionState.value is SoulseekConnectionState.Error) break
                    delay(150)
                    waited += 150
                }
            }

            if (_connectionState.value !is SoulseekConnectionState.Connected) {
                _isSearching.value = false
                return@launch
            }

            val token = tokenCounter.incrementAndGet()
            activeSearchToken = token
            seenResultKeys.clear()
            _searchResults.value = emptyList()
            _isSearching.value = true

            sendToServer(SoulseekProtocol.buildFileSearchPacket(token, query))
            Timber.tag(TAG).d("Sent Soulseek search token=%d query='%s'", token, query)

            searchTimeoutJob?.cancel()
            searchTimeoutJob = scope.launch {
                delay(18_000L)
                if (activeSearchToken == token) {
                    _isSearching.value = false
                }
            }
        }
    }

    private suspend fun handleIncomingPeerSocket(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val frame = SoulseekProtocol.readMessageFrame(input) ?: run {
                socket.close()
                return
            }
            if (frame.isEmpty()) {
                socket.close()
                return
            }
            val reader = SlskReader(frame)
            val initCode = reader.readByte()
            if (initCode == SoulseekProtocol.PEER_INIT_HANDSHAKE) {
                val peerUser = reader.readString()
                val connType = reader.readString()
                val token = reader.readInt()
                if (connType == "P") {
                    readPeerMessageLoop(socket, peerUser)
                } else if (connType == "F") {
                    handleFileTransferSocket(socket, peerUser, token)
                } else {
                    socket.close()
                }
            } else {
                socket.close()
            }
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleConnectToPeer(
        peerUser: String,
        connType: String,
        ip: String,
        port: Int,
        token: Int
    ) {
        if (connType != "P" && connType != "F") return
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, port), 8_000)
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            out.write(SoulseekProtocol.buildPierceFirewallPacket(token))
            out.flush()

            if (connType == "P") {
                readPeerMessageLoop(socket, peerUser)
            } else if (connType == "F") {
                handleFileTransferSocket(socket, peerUser, token)
            }
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    private suspend fun readPeerMessageLoop(socket: Socket, peerUser: String) {
        try {
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            while (scope.isActive && !socket.isClosed) {
                val frame = SoulseekProtocol.readMessageFrame(input) ?: break
                if (frame.size < 4) continue
                val reader = SlskReader(frame)
                val code = reader.readInt()

                when (code) {
                    SoulseekProtocol.PEER_FILE_SEARCH_RESPONSE -> {
                        val compressed = reader.readRemainingBytes()
                        parseSearchResponse(compressed)
                        if (activeDownloadByKey.keys.none { it.startsWith("$peerUser|") }) {
                            break
                        }
                    }

                    SoulseekProtocol.PEER_TRANSFER_REQUEST -> {
                        val direction = reader.readInt()
                        val transferToken = reader.readInt()
                        val filename = reader.readString()
                        val fileSize = if (direction == 1 && reader.remaining >= 8) reader.readLong() else 0L
                        if (direction == 1) {
                            val key = "$peerUser|$filename"
                            val downloadId = activeDownloadByKey[key]
                            if (downloadId != null) {
                                pendingTransferByToken[transferToken] = PendingTransfer(
                                    downloadId = downloadId,
                                    username = peerUser,
                                    filename = filename,
                                    fileSize = fileSize
                                )
                                updateDownload(downloadId) {
                                    it.copy(
                                        sizeBytes = if (fileSize > 0L) fileSize else it.sizeBytes,
                                        status = SoulseekDownloadStatus.CONNECTING
                                    )
                                }
                                out.write(SoulseekProtocol.buildTransferResponsePacket(transferToken, allowed = true))
                                out.flush()
                            } else {
                                out.write(SoulseekProtocol.buildTransferResponsePacket(transferToken, allowed = false, reason = "Cancelled"))
                                out.flush()
                            }
                        }
                    }

                    SoulseekProtocol.PEER_PLACE_IN_QUEUE_RESPONSE -> {
                        val filename = reader.readString()
                        val place = reader.readInt()
                        val downloadId = activeDownloadByKey["$peerUser|$filename"]
                        if (downloadId != null) {
                            updateDownload(downloadId) { it.copy(queuePlace = place) }
                        }
                    }

                    SoulseekProtocol.PEER_UPLOAD_FAILED -> {
                        val filename = reader.readString()
                        val key = "$peerUser|$filename"
                        val downloadId = activeDownloadByKey.remove(key)
                        if (downloadId != null) {
                            updateDownload(downloadId) {
                                it.copy(status = SoulseekDownloadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = "Сбой отправки у пира")
                            }
                        }
                    }

                    SoulseekProtocol.PEER_UPLOAD_DENIED -> {
                        val filename = reader.readString()
                        val reason = if (reader.remaining >= 4) runCatching { reader.readString() }.getOrDefault("Отклонено") else "Отклонено"
                        val key = "$peerUser|$filename"
                        val downloadId = activeDownloadByKey.remove(key)
                        if (downloadId != null) {
                            updateDownload(downloadId) {
                                it.copy(status = SoulseekDownloadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = reason)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Peer closed socket
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseSearchResponse(compressed: ByteArray) {
        try {
            val decompressed = SoulseekProtocol.decompressZlib(compressed)
            val r = SlskReader(decompressed)
            val peerUsername = r.readString()
            val token = r.readInt()
            if (token != activeSearchToken) return

            val fileCount = r.readInt().coerceAtMost(500)
            val rawFiles = ArrayList<SoulseekSearchResult>(fileCount)

            for (i in 0 until fileCount) {
                if (r.remaining < 15) break
                r.readByte() // code = 1
                val filename = r.readString()
                val size = r.readLong()
                val extRaw = r.readString()
                val attrCount = r.readInt().coerceAtMost(16)

                var bitrate = 0
                var duration = 0
                var sampleRate = 0
                var bitDepth = 0
                for (a in 0 until attrCount) {
                    if (r.remaining < 8) break
                    val attrCode = r.readInt()
                    val attrVal = r.readInt()
                    when (attrCode) {
                        SoulseekProtocol.ATTR_BITRATE -> bitrate = attrVal
                        SoulseekProtocol.ATTR_DURATION -> duration = attrVal
                        SoulseekProtocol.ATTR_SAMPLE_RATE -> sampleRate = attrVal
                        SoulseekProtocol.ATTR_BIT_DEPTH -> bitDepth = attrVal
                    }
                }

                val normalizedPath = filename.replace('/', '\\')
                val displayName = normalizedPath.substringAfterLast('\\')
                val fullFolderPath = normalizedPath.substringBeforeLast('\\', "")
                val folderName = fullFolderPath.substringAfterLast('\\', "").ifBlank { "Без папки" }
                val ext = extRaw.lowercase().ifBlank {
                    displayName.substringAfterLast('.', "").lowercase()
                }

                if (ext in SoulseekSearchResult.AUDIO_EXTENSIONS && size > 10_000L) {
                    val key = "$peerUsername|$filename"
                    if (seenResultKeys.add(key)) {
                        rawFiles.add(
                            SoulseekSearchResult(
                                id = key,
                                username = peerUsername,
                                filename = filename,
                                displayName = displayName,
                                folderName = folderName,
                                fullFolderPath = fullFolderPath,
                                extension = ext,
                                sizeBytes = size,
                                bitrateKbps = bitrate,
                                durationSec = duration,
                                sampleRateHz = sampleRate,
                                bitDepth = bitDepth
                            )
                        )
                    }
                }
            }

            if (rawFiles.isEmpty()) return

            val freeSlot = if (r.remaining >= 1) r.readByte() != 0 else false
            val uploadSpeed = if (r.remaining >= 4) r.readInt().toLong() and 0xFFFFFFFFL else 0L
            val queueLen = if (r.remaining >= 4) r.readInt() else 0

            val enriched = rawFiles.map {
                it.copy(
                    hasFreeSlot = freeSlot,
                    uploadSpeedBytesPerSec = uploadSpeed,
                    queueLength = queueLen
                )
            }

            _searchResults.update { current ->
                if (current.size >= MAX_SEARCH_RESULTS) return@update current
                (current + enriched).take(MAX_SEARCH_RESULTS)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).v(t, "Failed to parse search response")
        }
    }

    fun download(result: SoulseekSearchResult) {
        val key = "${result.username}|${result.filename}"
        val existingId = activeDownloadByKey[key]
        if (existingId != null) return

        val downloadId = UUID.randomUUID().toString()
        activeDownloadByKey[key] = downloadId

        val item = SoulseekDownloadItem(
            id = downloadId,
            username = result.username,
            filename = result.filename,
            displayName = result.displayName,
            folderName = result.folderName,
            sizeBytes = result.sizeBytes,
            status = SoulseekDownloadStatus.QUEUED
        )
        _downloads.update { current ->
            listOf(item) + current.filterNot { it.username == result.username && it.filename == result.filename }
        }

        scope.launch {
            initiateDownloadWithPeer(item)
        }
    }

    fun downloadFolder(results: List<SoulseekSearchResult>) {
        if (results.isEmpty()) return
        val first = results.first()
        val itemsToQueue = mutableListOf<SoulseekDownloadItem>()

        for (res in results) {
            val key = "${res.username}|${res.filename}"
            if (activeDownloadByKey.containsKey(key)) continue
            val downloadId = UUID.randomUUID().toString()
            activeDownloadByKey[key] = downloadId
            itemsToQueue.add(
                SoulseekDownloadItem(
                    id = downloadId,
                    username = res.username,
                    filename = res.filename,
                    displayName = res.displayName,
                    folderName = res.folderName,
                    sizeBytes = res.sizeBytes,
                    status = SoulseekDownloadStatus.QUEUED
                )
            )
        }

        if (itemsToQueue.isEmpty()) return
        _downloads.update { current ->
            val newKeys = itemsToQueue.mapTo(HashSet()) { "${it.username}|${it.filename}" }
            itemsToQueue + current.filterNot { "${it.username}|${it.filename}" in newKeys }
        }

        // Queue all files from the same peer over a single "P" connection (just like Seeker!)
        scope.launch {
            initiateBatchDownloadWithPeer(first.username, itemsToQueue)
        }
    }

    fun retryDownload(downloadId: String) {
        val failed = _downloads.value.find { it.id == downloadId } ?: return
        val key = "${failed.username}|${failed.filename}"
        activeDownloadByKey[key] = downloadId
        updateDownload(downloadId) {
            it.copy(
                downloadedBytes = 0L,
                speedBytesPerSec = 0L,
                queuePlace = 0,
                errorMessage = null,
                status = SoulseekDownloadStatus.QUEUED
            )
        }
        scope.launch {
            initiateDownloadWithPeer(failed.copy(status = SoulseekDownloadStatus.QUEUED, downloadedBytes = 0L))
        }
    }

    fun cancelDownload(downloadId: String) {
        activeDownloadSockets.remove(downloadId)?.let { runCatching { it.close() } }
        activeDownloadByKey.entries.removeIf { it.value == downloadId }
        pendingTransferByToken.entries.removeIf { it.value.downloadId == downloadId }
        _downloads.update { list -> list.filterNot { it.id == downloadId } }
    }

    fun cancelAllDownloads() {
        activeDownloadSockets.values.forEach { runCatching { it.close() } }
        activeDownloadSockets.clear()
        activeDownloadByKey.clear()
        pendingTransferByToken.clear()
        _downloads.value = emptyList()
    }

    fun clearCompletedDownloads() {
        _downloads.update { list ->
            list.filter {
                it.status != SoulseekDownloadStatus.COMPLETED && it.status != SoulseekDownloadStatus.FAILED
            }
        }
    }

    private suspend fun initiateDownloadWithPeer(item: SoulseekDownloadItem) {
        initiateBatchDownloadWithPeer(item.username, listOf(item))
    }

    private suspend fun initiateBatchDownloadWithPeer(peerUsername: String, items: List<SoulseekDownloadItem>) {
        try {
            items.forEach { item ->
                updateDownload(item.id) { it.copy(status = SoulseekDownloadStatus.CONNECTING) }
            }
            val peerAddress = lookupPeerAddress(peerUsername)
            if (peerAddress == null) {
                items.forEach { failDownload(it, "Не удалось получить адрес пира") }
                return
            }

            val (ip, port) = peerAddress
            if (port <= 0 || ip == "0.0.0.0") {
                items.forEach { failDownload(it, "Пир недоступен (порт закрыт)") }
                return
            }

            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, port), 10_000)
            socket.soTimeout = 60_000
            items.forEach { activeDownloadSockets[it.id] = socket }

            val out = socket.getOutputStream()
            val initToken = tokenCounter.incrementAndGet()
            out.write(SoulseekProtocol.buildPeerInitPacket(getUsername(), "P", initToken))
            for (item in items) {
                out.write(SoulseekProtocol.buildQueueUploadPacket(item.filename))
                out.write(SoulseekProtocol.buildPlaceInQueueRequestPacket(item.filename))
            }
            out.flush()

            items.forEach { item ->
                updateDownload(item.id) { it.copy(status = SoulseekDownloadStatus.QUEUED) }
            }
            readPeerMessageLoop(socket, peerUsername)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to initiate download with peer %s", peerUsername)
            for (item in items) {
                val current = _downloads.value.find { it.id == item.id }
                if (current != null &&
                    current.status != SoulseekDownloadStatus.DOWNLOADING &&
                    current.status != SoulseekDownloadStatus.COMPLETED
                ) {
                    failDownload(item, "Не удалось подключиться к пиру")
                }
            }
        }
    }

    private suspend fun lookupPeerAddress(username: String): Pair<String, Int>? {
        val deferred = CompletableDeferred<Pair<String, Int>>()
        peerAddressWaiters[username] = deferred
        if (!sendToServer(SoulseekProtocol.buildGetPeerAddressPacket(username))) {
            peerAddressWaiters.remove(username)
            return null
        }
        return withTimeoutOrNull(8_000L) { deferred.await() }
            .also { if (it == null) peerAddressWaiters.remove(username) }
    }

    private suspend fun handleFileTransferSocket(socket: Socket, peerUser: String, initToken: Int) {
        var transfer: PendingTransfer? = null
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            val tokenBytes = ByteArray(4)
            if (!SoulseekProtocol.readFully(input, tokenBytes, 0, 4)) {
                socket.close()
                return
            }
            val transferToken = ByteBuffer.wrap(tokenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            transfer = pendingTransferByToken.remove(transferToken)
                ?: pendingTransferByToken.remove(initToken)

            if (transfer == null) {
                socket.close()
                return
            }

            activeDownloadSockets[transfer.downloadId] = socket

            val offsetBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
            out.write(offsetBuf)
            out.flush()

            updateDownload(transfer.downloadId) {
                it.copy(
                    status = SoulseekDownloadStatus.DOWNLOADING,
                    sizeBytes = if (transfer.fileSize > 0L) transfer.fileSize else it.sizeBytes
                )
            }

            val savedOk = saveIncomingAudioStream(transfer, input)
            val key = "${transfer.username}|${transfer.filename}"
            activeDownloadByKey.remove(key)
            activeDownloadSockets.remove(transfer.downloadId)

            if (savedOk) {
                updateDownload(transfer.downloadId) {
                    it.copy(
                        downloadedBytes = it.sizeBytes,
                        speedBytesPerSec = 0L,
                        status = SoulseekDownloadStatus.COMPLETED
                    )
                }
                syncManager.forceRefresh()
            } else {
                updateDownload(transfer.downloadId) {
                    it.copy(status = SoulseekDownloadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = "Не удалось сохранить файл")
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "File transfer error")
            transfer?.let { tr ->
                activeDownloadByKey.remove("${tr.username}|${tr.filename}")
                activeDownloadSockets.remove(tr.downloadId)
                updateDownload(tr.downloadId) {
                    it.copy(status = SoulseekDownloadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = "Обрыв передачи")
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun saveIncomingAudioStream(transfer: PendingTransfer, input: InputStream): Boolean {
        val rawName = transfer.filename.replace('/', '\\').substringAfterLast('\\')
        val safeFileName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "track_${System.currentTimeMillis()}.flac" }
        val rawFolder = transfer.filename.replace('/', '\\').substringBeforeLast('\\', "").substringAfterLast('\\', "")
        val safeFolder = rawFolder.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val ext = safeFileName.substringAfterLast('.', "").lowercase()
        val mimeType = when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "alac", "aac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            else -> "audio/*"
        }

        val buffer = ByteArray(16 * 1024)
        var totalRead = 0L
        var lastUiUpdateMs = System.currentTimeMillis()
        var lastBytesSnapshot = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = if (safeFolder.isNotBlank()) {
                "${Environment.DIRECTORY_MUSIC}/YoPlayer/$safeFolder"
            } else {
                "${Environment.DIRECTORY_MUSIC}/YoPlayer"
            }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false

            return try {
                resolver.openOutputStream(uri)?.use { fileOut ->
                    while (true) {
                        val toRead = if (transfer.fileSize > 0L) {
                            minOf(buffer.size.toLong(), transfer.fileSize - totalRead).toInt()
                        } else {
                            buffer.size
                        }
                        if (toRead <= 0) break
                        val n = input.read(buffer, 0, toRead)
                        if (n < 0) break
                        fileOut.write(buffer, 0, n)
                        totalRead += n
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUiUpdateMs
                        if (elapsed >= 250L) {
                            val speed = ((totalRead - lastBytesSnapshot) * 1000L) / elapsed.coerceAtLeast(1L)
                            lastUiUpdateMs = now
                            lastBytesSnapshot = totalRead
                            updateDownload(transfer.downloadId) {
                                it.copy(downloadedBytes = totalRead, speedBytesPerSec = speed)
                            }
                        }
                    }
                    fileOut.flush()
                } ?: return false

                if (transfer.fileSize > 0L && totalRead < transfer.fileSize) {
                    resolver.delete(uri, null, null)
                    return false
                }

                val doneValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                true
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed saving to MediaStore")
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } else {
            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                if (safeFolder.isNotBlank()) "YoPlayer/$safeFolder" else "YoPlayer"
            )
            baseDir.mkdirs()
            val outFile = File(baseDir, safeFileName)
            return try {
                FileOutputStream(outFile).use { fileOut ->
                    while (true) {
                        val toRead = if (transfer.fileSize > 0L) {
                            minOf(buffer.size.toLong(), transfer.fileSize - totalRead).toInt()
                        } else {
                            buffer.size
                        }
                        if (toRead <= 0) break
                        val n = input.read(buffer, 0, toRead)
                        if (n < 0) break
                        fileOut.write(buffer, 0, n)
                        totalRead += n
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUiUpdateMs
                        if (elapsed >= 250L) {
                            val speed = ((totalRead - lastBytesSnapshot) * 1000L) / elapsed.coerceAtLeast(1L)
                            lastUiUpdateMs = now
                            lastBytesSnapshot = totalRead
                            updateDownload(transfer.downloadId) {
                                it.copy(downloadedBytes = totalRead, speedBytesPerSec = speed)
                            }
                        }
                    }
                    fileOut.flush()
                }
                if (transfer.fileSize > 0L && totalRead < transfer.fileSize) {
                    outFile.delete()
                    return false
                }
                MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(mimeType), null)
                true
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed saving file")
                runCatching { outFile.delete() }
                false
            }
        }
    }

    private fun failDownload(item: SoulseekDownloadItem, reason: String) {
        activeDownloadByKey.remove("${item.username}|${item.filename}")
        activeDownloadSockets.remove(item.id)
        updateDownload(item.id) {
            it.copy(status = SoulseekDownloadStatus.FAILED, speedBytesPerSec = 0L, errorMessage = reason)
        }
    }

    private fun updateDownload(downloadId: String, transform: (SoulseekDownloadItem) -> SoulseekDownloadItem) {
        _downloads.update { list ->
            list.map { if (it.id == downloadId) transform(it) else it }
        }
    }
}
