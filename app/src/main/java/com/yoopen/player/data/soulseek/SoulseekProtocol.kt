package com.yoopen.player.data.soulseek

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Low-level little-endian binary protocol helpers for the Soulseek P2P network.
 */
object SoulseekProtocol {

    // Server -> Client / Client -> Server message codes
    const val MSG_LOGIN = 1
    const val MSG_SET_WAIT_PORT = 2
    const val MSG_GET_PEER_ADDRESS = 3
    const val MSG_CONNECT_TO_PEER = 18
    const val MSG_FILE_SEARCH = 26
    const val MSG_PING = 32
    const val MSG_SHARED_FOLDERS_FILES = 35
    const val MSG_HAVE_NO_PARENTS = 71

    // Peer handshake 1-byte codes
    const val PEER_INIT_PIERCE_FIREWALL = 0
    const val PEER_INIT_HANDSHAKE = 1

    // Peer ("P") 4-byte message codes
    const val PEER_FILE_SEARCH_RESPONSE = 9
    const val PEER_TRANSFER_REQUEST = 40
    const val PEER_TRANSFER_RESPONSE = 41
    const val PEER_QUEUE_UPLOAD = 43
    const val PEER_PLACE_IN_QUEUE_RESPONSE = 44
    const val PEER_UPLOAD_FAILED = 46
    const val PEER_UPLOAD_DENIED = 50
    const val PEER_PLACE_IN_QUEUE_REQUEST = 51

    // File attribute codes inside FileSearchResponse
    const val ATTR_BITRATE = 0
    const val ATTR_DURATION = 1
    const val ATTR_VBR = 2
    const val ATTR_SAMPLE_RATE = 4
    const val ATTR_BIT_DEPTH = 5

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Soulseek server transmits IPv4 as a 32-bit little-endian integer whose MSB is the first octet.
     */
    fun intToIpAddress(ip: Int): String {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        val c = (ip ushr 8) and 0xFF
        val d = ip and 0xFF
        return "$a.$b.$c.$d"
    }

    fun decompressZlib(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data, offset, length)
            val out = ByteArrayOutputStream(length * 3)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, count)
                }
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    fun readMessageFrame(input: InputStream, maxLength: Int = 16 * 1024 * 1024): ByteArray? {
        val lenBytes = ByteArray(4)
        if (!readFully(input, lenBytes, 0, 4)) return null
        val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (len <= 0 || len > maxLength) return null
        val payload = ByteArray(len)
        if (!readFully(input, payload, 0, len)) return null
        return payload
    }

    fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    fun writeFramedInt32Message(code: Int, build: SlskWriter.() -> Unit): ByteArray {
        val body = SlskWriter().apply {
            writeInt(code)
            build()
        }.toByteArray()
        return SlskWriter().apply {
            writeInt(body.size)
            writeRaw(body)
        }.toByteArray()
    }

    fun writeFramedByteMessage(code: Int, build: SlskWriter.() -> Unit): ByteArray {
        val body = SlskWriter().apply {
            writeByte(code)
            build()
        }.toByteArray()
        return SlskWriter().apply {
            writeInt(body.size)
            writeRaw(body)
        }.toByteArray()
    }

    fun buildLoginPacket(username: String, password: String): ByteArray =
        writeFramedInt32Message(MSG_LOGIN) {
            writeString(username)
            writeString(password)
            writeInt(160) // Client version
            writeString(md5Hex(username + password))
            writeInt(1)   // Minor version
        }

    fun buildSetWaitPortPacket(port: Int): ByteArray =
        writeFramedInt32Message(MSG_SET_WAIT_PORT) {
            writeInt(port)
        }

    fun buildHaveNoParentsPacket(haveNoParents: Boolean): ByteArray =
        writeFramedInt32Message(MSG_HAVE_NO_PARENTS) {
            writeByte(if (haveNoParents) 1 else 0)
        }

    fun buildSharedFoldersFilesPacket(folders: Int, files: Int): ByteArray =
        writeFramedInt32Message(MSG_SHARED_FOLDERS_FILES) {
            writeInt(folders)
            writeInt(files)
        }

    fun buildPingPacket(): ByteArray = writeFramedInt32Message(MSG_PING) {}

    fun buildFileSearchPacket(token: Int, query: String): ByteArray =
        writeFramedInt32Message(MSG_FILE_SEARCH) {
            writeInt(token)
            writeString(query)
        }

    fun buildGetPeerAddressPacket(username: String): ByteArray =
        writeFramedInt32Message(MSG_GET_PEER_ADDRESS) {
            writeString(username)
        }

    fun buildConnectToPeerPacket(token: Int, username: String, type: String): ByteArray =
        writeFramedInt32Message(MSG_CONNECT_TO_PEER) {
            writeInt(token)
            writeString(username)
            writeString(type)
        }

    fun buildPierceFirewallPacket(token: Int): ByteArray =
        writeFramedByteMessage(PEER_INIT_PIERCE_FIREWALL) {
            writeInt(token)
        }

    fun buildPeerInitPacket(username: String, type: String, token: Int): ByteArray =
        writeFramedByteMessage(PEER_INIT_HANDSHAKE) {
            writeString(username)
            writeString(type)
            writeInt(token)
        }

    fun buildQueueUploadPacket(filename: String): ByteArray =
        writeFramedInt32Message(PEER_QUEUE_UPLOAD) {
            writeString(filename)
        }

    fun buildPlaceInQueueRequestPacket(filename: String): ByteArray =
        writeFramedInt32Message(PEER_PLACE_IN_QUEUE_REQUEST) {
            writeString(filename)
        }

    fun buildTransferResponsePacket(token: Int, allowed: Boolean, reason: String? = null): ByteArray =
        writeFramedInt32Message(PEER_TRANSFER_RESPONSE) {
            writeInt(token)
            writeByte(if (allowed) 1 else 0)
            if (!allowed && reason != null) {
                writeString(reason)
            }
        }
}

class SlskWriter {
    private val out = ByteArrayOutputStream()

    fun writeByte(v: Int) {
        out.write(v and 0xFF)
    }

    fun writeInt(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    fun writeLong(v: Long) {
        for (shift in 0 until 64 step 8) {
            out.write(((v ushr shift) and 0xFFL).toInt())
        }
    }

    fun writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        out.write(bytes)
    }

    fun writeRaw(bytes: ByteArray) {
        out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class SlskReader(private val data: ByteArray, startOffset: Int = 0) {
    private var pos = startOffset

    val remaining: Int
        get() = (data.size - pos).coerceAtLeast(0)

    fun readByte(): Int {
        if (pos + 1 > data.size) throw EOFException("Unexpected EOF reading byte")
        return data[pos++].toInt() and 0xFF
    }

    fun readInt(): Int {
        if (pos + 4 > data.size) throw EOFException("Unexpected EOF reading int")
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readLong(): Long {
        if (pos + 8 > data.size) throw EOFException("Unexpected EOF reading long")
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((data[pos + i].toLong() and 0xFFL) shl (i * 8))
        }
        pos += 8
        return v
    }

    fun readString(): String {
        val len = readInt()
        if (len < 0 || pos + len > data.size) throw EOFException("Invalid string length: $len")
        val str = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return str
    }

    fun readRemainingBytes(): ByteArray {
        val slice = data.copyOfRange(pos, data.size)
        pos = data.size
        return slice
    }
}
