package com.example.hiderecents.rc

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网发现：被控端监听 UDP 广播，控制端广播探测。
 *
 * 比手输 IP 直接，也不依赖 mDNS（后者在部分路由器与 AP 隔离下会失效）。
 * 探测会同时发往 255.255.255.255 和各网卡的子网广播地址 ——
 * 有些机型只认后者，只发前者会一直搜不到。
 */
object RcDiscovery {

    private const val TAG = "RcDiscovery"
    const val PORT = 27185
    private const val PROBE = "RC_DISCOVER_V1"
    private const val REPLY = "RC_HOST_V1"
    private const val PROBE_INTERVAL_MS = 700L

    class Found(val address: String, val port: Int, val name: String)

    /** 被控端：收到探测就用自己的端口回应。返回停止函数。 */
    fun advertise(servicePort: Int, deviceName: String): () -> Unit {
        val running = AtomicBoolean(true)
        Thread({
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }.use { socket ->
                    socket.soTimeout = 800
                    val buf = ByteArray(256)
                    Log.i(TAG, "advertising on $PORT")
                    while (running.get()) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (e: Throwable) {
                            continue
                        }
                        val text = String(packet.data, 0, packet.length)
                        if (!text.startsWith(PROBE)) continue
                        val reply = "$REPLY|$servicePort|$deviceName".toByteArray()
                        runCatching {
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "advertise failed", it) }
        }, "rc-advertise").start()
        return { running.set(false) }
    }

    /** 控制端：反复广播探测，收集应答。 */
    fun scan(timeoutMs: Long = 2500, onFound: (Found) -> Unit) {
        Thread({
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val probe = PROBE.toByteArray()
                    val targets = broadcastTargets()
                    Log.i(TAG, "scan targets=" + targets.size)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var nextProbe = 0L
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val now = System.currentTimeMillis()
                        if (now >= nextProbe) {
                            targets.forEach { addr ->
                                runCatching {
                                    socket.send(DatagramPacket(probe, probe.size, addr, PORT))
                                }
                            }
                            nextProbe = now + PROBE_INTERVAL_MS
                        }
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (e: Throwable) {
                            continue
                        }
                        val parts = String(packet.data, 0, packet.length).split('|')
                        if (parts.size < 3 || parts[0] != REPLY) continue
                        val port = parts[1].toIntOrNull() ?: RcProtocol.PORT
                        val name = parts[2].ifBlank { "未命名设备" }
                        val address = packet.address?.hostAddress ?: continue
                        onFound(Found(address, port, name))
                    }
                }
            }.onFailure { Log.w(TAG, "scan failed", it) }
        }, "rc-scan").start()
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement() ?: continue
                runCatching {
                    if (!nif.isUp || nif.isLoopback) return@runCatching
                    nif.interfaceAddresses.forEach { ia ->
                        ia.broadcast?.let { out.add(it) }
                    }
                }
            }
        }
        return out.distinct()
    }
}