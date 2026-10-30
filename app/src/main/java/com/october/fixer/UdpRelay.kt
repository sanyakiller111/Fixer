package com.october.fixer

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends UDP (Roblox game traffic, STUN, ...) straight to its destination from our own sockets,
 * bypassing tun2socks/byedpi, and writes the replies back into the tun as IP packets.
 */
class UdpRelay(
    private val protect: (DatagramSocket) -> Boolean,
    private val write: (ByteArray) -> Unit,
    /** Called once per new Roblox game server flow with its address and measured round trip. */
    private val onRobloxServer: ((ip: ByteArray, port: Int, rttMs: Int) -> Unit)? = null,
) {
    private class Flow(val sock: DatagramSocket, val req: UdpDns) {
        @Volatile var last = System.currentTimeMillis()
        val rtt: RttProbe? = if (Mm2.isRobloxServer(req.dst)) RttProbe() else null
    }

    private val flows = ConcurrentHashMap<String, Flow>()
    @Volatile private var closed = false

    fun send(pkt: UdpDns) {
        if (closed) return
        val key = "${pkt.src.contentToString()}:${pkt.srcPort}>${pkt.dst.contentToString()}:${pkt.dstPort}"
        val f = flows[key] ?: open(key, pkt) ?: return
        f.last = System.currentTimeMillis()
        f.rtt?.sent(System.nanoTime())
        try {
            f.sock.send(DatagramPacket(pkt.payload, pkt.payload.size))
        } catch (_: Exception) {
        }
    }

    private fun open(key: String, pkt: UdpDns): Flow? {
        if (flows.size >= MAX_FLOWS) return null
        val s = try {
            DatagramSocket()
        } catch (_: Exception) {
            return null
        }
        return try {
            protect(s)
            s.soTimeout = 5000
            s.connect(InetSocketAddress(InetAddress.getByAddress(pkt.dst), pkt.dstPort))
            val f = Flow(s, pkt)
            flows[key] = f
            Thread({ receive(key, f) }, "udp-relay").apply { isDaemon = true }.start()
            f
        } catch (_: Exception) {
            s.close()
            null
        }
    }

    private fun receive(key: String, f: Flow) {
        val buf = ByteArray(65535)
        while (!closed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                f.sock.receive(p)
                f.last = System.currentTimeMillis()
                f.rtt?.received(System.nanoTime())?.let { onRobloxServer?.invoke(f.req.dst, f.req.dstPort, it) }
                write(PacketUtils.buildReply(f.req, buf.copyOf(p.length)))
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() - f.last > IDLE_MS) break
            } catch (_: PortUnreachableException) {
                // a stray ICMP error; keep the flow (and its source port) alive
            } catch (_: Exception) {
                break
            }
        }
        flows.remove(key, f)
        f.sock.close()
    }

    /** Drops all flows; their sockets are bound to the old network after a Wi-Fi/LTE switch. */
    fun reset() {
        flows.values.forEach { it.sock.close() }
        flows.clear()
    }

    fun close() {
        closed = true
        reset()
    }

    private companion object {
        const val MAX_FLOWS = 512
        const val IDLE_MS = 120_000L
    }
}

/**
 * Round trip of a Roblox connection: time from the last packet we sent to the server's first reply.
 * The handshake goes client burst, silence, server reply; measured on the phone it gave 84-86 ms where
 * Roblox's own NetworkPing showed 102. Later packets flow both ways at once, so pairing them gives ~0 ms.
 */
class RttProbe {
    @Volatile private var lastSent = 0L
    @Volatile private var anySent = false // nanoTime can be any value, 0 included
    private var done = false

    fun sent(now: Long) {
        lastSent = now
        anySent = true
    }

    /** The round trip in ms on the first reply, exactly once; null otherwise. */
    @Synchronized
    fun received(now: Long): Int? {
        if (done || !anySent) return null
        done = true
        return ((now - lastSent) / 1_000_000).toInt()
    }
}
