package com.core.rpgmaker

import com.core.tyrano.TyranoLocalHttpServer
import com.core.web.DkToolsCompat
import com.core.web.PluginFileIndexCache
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginIndexAsyncHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun slowScanDoesNotBlockIndexOrGameResourcesAndStopInterruptsIt() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder()
            File(root, "index.html").writeText("<html></html>")
            File(root, "resource.txt").writeText("available")
            val started = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            var worker = ""
            val cache = PluginFileIndexCache(build = {
                worker = Thread.currentThread().name
                started.countDown()
                try { release.await() } catch (_: InterruptedException) {
                    interrupted.countDown()
                    throw InterruptedIOException("cancelled test scan")
                }
                "{}".toByteArray()
            }, isLimitFailure = { false })
            val appends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())
            val modern = if (!legacy) RpgMakerLocalHttpServer(root, null, scriptAppends = appends) else null
            val old = if (legacy) TyranoLocalHttpServer(root, null, scriptAppends = appends) else null
            val server = modern ?: requireNotNull(old)
            // Replace only the slow I/O boundary; exercise production HTTP/executor/lifecycle behavior.
            server.javaClass.getDeclaredField("pluginFileIndex").apply { isAccessible = true }.set(server, cache)
            modern?.start(); old?.start()
            val port = modern?.port ?: requireNotNull(old).port
            fun request(path: String, cookie: String? = null): Pair<Int, String> {
                val connection = URI("http", null, "127.0.0.1", port, "/$path", null, null).toURL()
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                cookie?.let { connection.setRequestProperty("Cookie", it) }
                return try {
                    val status = connection.responseCode
                    status to if (path == "index.html") {
                        connection.inputStream.close()
                        connection.getHeaderField("Set-Cookie").substringBefore(';')
                    } else (if (status < 400) connection.inputStream else connection.errorStream)
                        .bufferedReader().use { it.readText() }
                } finally { connection.disconnect() }
            }
            try {
                val cookie = request("index.html").second
                assertEquals(202, request(RpgMakerFileIndex.RESOURCE_PATH, cookie).first)
                assertTrue(started.await(2, TimeUnit.SECONDS))
                repeat(3) { assertEquals(202, request(RpgMakerFileIndex.RESOURCE_PATH, cookie).first) }
                assertEquals(200 to "available", request("resource.txt", cookie))
                assertEquals("TyranorPluginIndex", worker)
                modern?.stop(); old?.stop()
                assertTrue("stop must interrupt the independent scan", interrupted.await(2, TimeUnit.SECONDS))
            } finally { release.countDown(); modern?.stop(); old?.stop() }
        }
    }

    @Test fun persistentIoFailureTransitionsFromPendingToTerminalHttpError() {
        for (legacy in listOf(false, true)) {
            val root = temporary.newFolder("persistent-io-$legacy")
            File(root, "index.html").writeText("<html></html>")
            val cache = PluginFileIndexCache(
                build = { throw java.io.IOException("unreadable root") },
                isLimitFailure = { it is RpgMakerFileIndex.LimitExceeded },
            )
            val appends = mapOf(DkToolsCompat.SCRIPT_PATH to byteArrayOf())
            val modern = if (!legacy) RpgMakerLocalHttpServer(root, null, scriptAppends = appends) else null
            val old = if (legacy) TyranoLocalHttpServer(root, null, scriptAppends = appends) else null
            val server = modern ?: requireNotNull(old)
            server.javaClass.getDeclaredField("pluginFileIndex").apply { isAccessible = true }.set(server, cache)
            modern?.start(); old?.start()
            val port = modern?.port ?: requireNotNull(old).port
            fun request(path: String, cookie: String? = null): Int {
                val connection = URI("http", null, "127.0.0.1", port, "/$path", null, null).toURL()
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                cookie?.let { connection.setRequestProperty("Cookie", it) }
                return try {
                    val status = connection.responseCode
                    if (status < 400) connection.inputStream.close() else connection.errorStream?.close()
                    status
                } finally { connection.disconnect() }
            }
            fun bootstrap(): String {
                val connection = URI("http", null, "127.0.0.1", port, "/index.html", null, null).toURL()
                    .openConnection() as HttpURLConnection
                return try {
                    assertEquals(200, connection.responseCode)
                    connection.inputStream.close()
                    connection.getHeaderField("Set-Cookie").substringBefore(';')
                } finally { connection.disconnect() }
            }
            try {
                val cookie = bootstrap()
                var terminalStatus: Int? = null
                val deadline = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < deadline) {
                    val status = request(RpgMakerFileIndex.RESOURCE_PATH, cookie)
                    if (status != 202) {
                        terminalStatus = status
                        break
                    }
                    Thread.sleep(20)
                }
                assertEquals("legacy=$legacy must terminate with 500 instead of looping 202", 500, terminalStatus)
            } finally { modern?.stop(); old?.stop() }
        }
    }
}
