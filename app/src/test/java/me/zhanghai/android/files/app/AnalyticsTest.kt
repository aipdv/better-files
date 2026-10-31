package me.zhanghai.android.files.app

import com.posthog.PostHog
import com.posthog.PostHogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

class AnalyticsTest {

    @Test fun `an event that is not on the allowlist is dropped`() {
        assertNull(filterAnalyticsEvent("\$screen", mapOf("\$screen_name" to "FileListActivity")))
        assertNull(filterAnalyticsEvent("search_query", mapOf("query" to "tax 2024")))
    }

    @Test fun `only allowed and kept properties survive, with no location`() {
        val sent = filterAnalyticsEvent(
            "file_transfer_queued",
            mapOf(
                "file_count" to 2, "operation" to "copy", "path" to "/sdcard/Download/a.pdf",
                "distinct_id" to "anon", "\$process_person_profile" to false,
                "\$app_version" to "0.1.0", "\$os_version" to "16",
                "\$device_model" to "Pixel 7a", "\$device_manufacturer" to "Google",
                "\$locale" to "en-US", "\$timezone" to "Asia/Kolkata",
                "\$network_carrier" to "Jio", "\$user_agent" to "Dalvik", "\$session_id" to "s"
            )
        )
        assertEquals(
            mapOf(
                "file_count" to 2, "operation" to "copy", "distinct_id" to "anon",
                "\$process_person_profile" to false, "\$app_version" to "0.1.0",
                "\$os_version" to "16", "\$geoip_disable" to true
            ),
            sent
        )
    }

    @Test fun `lifecycle events keep their versions but drop the deep link`() {
        val sent = filterAnalyticsEvent(
            "Application Opened",
            mapOf("version" to "0.1.0", "build" to 40, "from_background" to false,
                "url" to "content://x/secret.pdf", "referring_application" to "com.x")
        )
        assertEquals(
            mapOf("version" to "0.1.0", "build" to 40, "from_background" to false,
                "\$geoip_disable" to true),
            sent
        )
    }

    @Test fun `the ask shows only from the 3rd launch, once, and only with a key`() {
        assertFalse(shouldAskAnalytics(available = true, asked = false, launchCount = 1))
        assertFalse(shouldAskAnalytics(available = true, asked = false, launchCount = 2))
        assertTrue(shouldAskAnalytics(available = true, asked = false, launchCount = 3))
        assertFalse(shouldAskAnalytics(available = true, asked = true, launchCount = 4))
        assertFalse(shouldAskAnalytics(available = false, asked = false, launchCount = 3))
    }

    @Test fun `the SDK starts only after opt-in in a build with a key`() {
        assertFalse(shouldStartAnalytics(available = true, enabled = false))
        assertFalse(shouldStartAnalytics(available = false, enabled = true))
        assertTrue(shouldStartAnalytics(available = true, enabled = true))
    }

    @Test fun `opting out sends nothing, not even a flags request`() {
        // A loopback stand-in for the PostHog host records every request the SDK makes.
        val requests = CopyOnWriteArrayList<String>()
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                while (true) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    socket.use {
                        requests += readRequest(it.getInputStream())
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()
                        )
                    }
                }
            }
            val config = PostHogConfig("test", "http://127.0.0.1:${server.localPort}").apply {
                remoteConfig = false
                preloadFeatureFlags = false
                storagePrefix = Files.createTempDirectory("posthog-test").toString()
            }
            val posthog = PostHog.with(config)
            try {
                Thread.sleep(500)
                requests.clear()

                stopAnalytics(posthog)
                Thread.sleep(2_000)

                assertEquals("Requests sent after opt-out", emptyList<String>(), requests.toList())
            } finally {
                posthog.close()
            }
        }
    }

    /** Request line plus body (gunzipped when needed), for the assert message. */
    private fun readRequest(input: InputStream): String {
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        var body = input.readNBytes(length)
        if (lines.any { it.equals("Content-Encoding: gzip", ignoreCase = true) }) {
            body = GZIPInputStream(body.inputStream()).readBytes()
        }
        return lines.first() + " " + body.toString(Charsets.UTF_8)
    }
}
