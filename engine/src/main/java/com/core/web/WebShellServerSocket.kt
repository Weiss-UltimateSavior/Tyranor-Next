package com.core.web

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Web 壳（Tyrano / WebOther / VN）本地 HTTP 服务的端口绑定。
 *
 * 归档域（origin）由 `http://localhost:<port>` 决定，WebView 的 localStorage/IndexedDB
 * 按域隔离——端口随机会导致每次启动存档不可见，因此调用方可传入固定端口。
 * 端口被占用时回退随机端口（保证游戏仍可启动），由调用方提示用户。
 */
internal object WebShellServerSocket {

    /** 期望端口（0 = 随机）。 */
    const val RANDOM_PORT = 0

    data class Bound(val socket: ServerSocket, val usedFallbackPort: Boolean)

    /** 绑定 127.0.0.1；[preferredPort] 非法或被占用时回退到随机端口。 */
    fun bind(preferredPort: Int): Bound {
        val address = InetAddress.getByName("127.0.0.1")
        if (preferredPort in 1..65535) {
            try {
                return Bound(ServerSocket(preferredPort, 50, address), usedFallbackPort = false)
            } catch (_: Throwable) {
                // 端口被占用/不可绑定时回退，交由调用方提示
            }
        }
        return Bound(ServerSocket(RANDOM_PORT, 50, address), usedFallbackPort = preferredPort in 1..65535)
    }
}
