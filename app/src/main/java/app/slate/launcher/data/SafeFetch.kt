package app.slate.launcher.data

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Slate's one way of fetching something from the internet, shared by the Continue watching
 * posters and the screensaver's Picture of the Day.
 *
 *  - Only from an https address with a plain host name, on the standard port, that is not on
 *    this TV or its local network; with [allowedHosts], only from those hosts. Redirects are
 *    followed a few times, each checked the same way. Nothing is sent but the request itself:
 *    no cookies, no identifiers, a fixed client name ([userAgent]).
 *  - The fetch is done here, byte by byte, not by the system's web client: every part of the
 *    answer, from its first line on, is read under a fixed size ([maxBody] for the body), and
 *    the whole exchange under a fixed time after which the connection is cut. A server cannot
 *    keep a fetch open, nor make it hold more than those few megabytes.
 *
 * [scope] runs the timers that cut connections that overrun. [allowedHosts] are lower case.
 */
internal class SafeFetch(
    private val scope: CoroutineScope,
    private val maxBody: Long,
    private val accept: String,
    private val userAgent: String = "Slate",
    private val allowedHosts: Set<String>? = null,
    maxLookups: Int = 6,
) {

    /**
     * Where host names are looked up, so that a lookup that hangs does not hold a fetch with it.
     * A few at a time, none waiting in line: one more than there is room for is refused.
     */
    private val lookups = ThreadPoolExecutor(0, maxLookups, 30L, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "slate-lookup").apply { isDaemon = true }
    }

    /** Owners a lookup for which is still running, whether or not anybody still waits for it. Guarded by itself. */
    private val lookingUp = HashSet<String>()

    /** Connections in use, so that [cutAll] can close them. Guarded by itself. */
    private val connections = HashSet<Socket>()

    /** Closes every connection in use. The fetches they belong to end with an [IOException]. */
    fun cutAll() {
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
    }

    /**
     * [address] as something Slate would fetch, or null: plain characters only, https, a host
     * name (not a number), the standard port, no user name or password.
     */
    fun checked(address: String): URI? = try {
        val uri = if (address.all { it.code in 0x21..0x7E }) URI(address) else null
        val host = uri?.host
        val ok = uri != null && uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !host.isNullOrEmpty() && host.length <= 253 && host.all { it in HOST_CHARACTERS } &&
            // A name with a dot in it whose last part starts with a letter: not an address in figures.
            host.contains('.') && host.substringAfterLast('.').firstOrNull()?.isLetter() == true &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (allowedHosts == null || host!!.lowercase() in allowedHosts)
        if (ok) uri else null
    } catch (e: Exception) {
        null
    }

    /** Not this device, not its local network, not anything else that is no public address. */
    private fun public(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val b = address.address.map { it.toInt() and 0xFF }
        return when (address) {
            is Inet4Address -> !(
                b[0] == 0 || b[0] >= 240 ||
                    (b[0] == 100 && b[1] in 64..127) || // shared address space
                    (b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2)) || // protocol assignments, documentation
                    (b[0] == 192 && b[1] == 88 && b[2] == 99) || // 6to4 relays
                    (b[0] == 198 && b[1] in 18..19) || // benchmarking
                    (b[0] == 198 && b[1] == 51 && b[2] == 100) || (b[0] == 203 && b[1] == 0 && b[2] == 113)
                )
            // Only the range public addresses are given out from (2000::/3), less the parts of
            // it that stand for something else.
            is Inet6Address -> (b[0] and 0xE0) == 0x20 && !(
                (b[0] == 0x20 && b[1] == 0x02) || // 6to4
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) || // Teredo
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8) // documentation
                )
            else -> false
        }
    }

    /** What one request brought back: the status, where it points if it is a redirect, the body if it is a 200. */
    private class Reply(val status: Int, val location: String?, val body: ByteArray?)

    /** The server answered, and the answer is no use: not something to ask for again soon. */
    class Refused(message: String) : Exception(message)

    /**
     * The bytes at [start], following a few redirects, or null if the answer is not usable.
     * Throws [IOException] if the address could not be reached at all, and [Refused] if the
     * server's answer broke the rules. [owner] is whoever the fetch is for (an app, say): it
     * gets one lookup at a time. [active] is asked along the way; once it says no, the fetch
     * gives up. [status], if given, receives the status of the last answer (0 if none came).
     *
     * Call off the main thread: this blocks for up to [FETCH_TIME_MS].
     */
    fun download(start: String, owner: String, active: () -> Boolean, status: IntArray? = null): ByteArray? {
        status?.set(0, 0)
        val deadline = SystemClock.elapsedRealtime() + FETCH_TIME_MS
        var uri = checked(start) ?: return null
        for (hop in 0..MAX_REDIRECTS) {
            if (!active()) return null
            val reply = exchange(uri, deadline, owner, active)
            status?.set(0, reply.status)
            when (reply.status) {
                200 -> return reply.body
                301, 302, 303, 307, 308 -> {
                    val next = reply.location ?: return null
                    if (next.length > MAX_URL_LENGTH) return null
                    uri = checked(runCatching { uri.resolve(next).toString() }.getOrNull() ?: return null) ?: return null
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * The public addresses of [host], looked up within the time [left] on behalf of [pkg].
     *
     * The system's own lookup cannot be interrupted, so it runs aside and is waited for only so
     * long. An app with a lookup still running gets no second one, so a host name that never
     * resolves ties up one of the few lookups at a time for that app, not all of them.
     */
    private fun lookUp(host: String, left: Long, pkg: String): List<InetAddress> {
        if (!synchronized(lookingUp) { lookingUp.add(pkg) }) throw IOException("an earlier lookup is still running")
        val lookup = try {
            lookups.submit(
                Callable {
                    try {
                        InetAddress.getAllByName(host).toList()
                    } finally {
                        synchronized(lookingUp) { lookingUp -= pkg }
                    }
                },
            )
        } catch (e: Throwable) {
            // Too many running, or no thread to be had.
            synchronized(lookingUp) { lookingUp -= pkg }
            throw IOException("no lookup could be started")
        }
        // (Not cancelled if it takes too long: it cannot be interrupted, and marking it done
        // would only hide that it is still running.)
        val all = try {
            lookup.get(left, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw IOException("host not found in time")
        }
        val usable = all.filter(::public)
        // A router that answers every name with itself while the line is down gives this too,
        // so it is worth another go soon.
        if (usable.isEmpty()) throw IOException("no public address")
        // The address the system puts first, then one of the other kind, then the rest: a
        // network on which one kind goes nowhere still gets to try the other.
        val first = usable.first()
        val other = usable.firstOrNull { it.javaClass != first.javaClass }
        return (listOfNotNull(first, other) + usable).distinct().take(MAX_ADDRESSES)
    }

    /**
     * One request and its answer, over a connection of its own that is closed afterwards, and
     * cut from outside if it is not done by [deadline]. Nothing is read without a limit.
     */
    private fun exchange(uri: URI, deadline: Long, pkg: String, active: () -> Boolean): Reply {
        val host = uri.host
        val addresses = lookUp(host, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L), pkg)
        val left = deadline - SystemClock.elapsedRealtime()
        if (left <= 0) throw IOException("out of time")

        val opened = ArrayList<Socket>()
        val expired = BooleanArray(1)
        // Closing the connection from here is what ends a read that a server keeps alive.
        val watchdog = scope.launch {
            delay(left)
            synchronized(opened) {
                expired[0] = true
                opened.toList()
            }.forEach { runCatching { it.close() } }
        }
        var tls: SSLSocket? = null
        try {
            // The first address that answers; a network that advertises one it cannot reach
            // (IPv6 without a route, say) must not cost the picture.
            var socket: Socket? = null
            var failure: IOException? = null
            for (address in addresses) {
                val time = min(CONNECT_TIME_MS.toLong(), deadline - SystemClock.elapsedRealtime())
                if (time <= 0 || !active()) break
                val candidate = Socket()
                // Not after the deadline has passed and the watchdog has already been round.
                val late = synchronized(opened) {
                    if (!expired[0]) opened += candidate
                    expired[0]
                }
                if (late) {
                    runCatching { candidate.close() }
                    break
                }
                synchronized(connections) { connections += candidate }
                try {
                    candidate.connect(InetSocketAddress(address, 443), time.toInt())
                    socket = candidate
                    break
                } catch (e: IOException) {
                    failure = e
                    runCatching { candidate.close() }
                }
            }
            if (socket == null) throw failure ?: IOException("out of time")
            socket.soTimeout = READ_TIME_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val secure = factory.createSocket(socket, host, 443, true) as SSLSocket
            tls = secure
            // The certificate must be for this host: checked by the handshake, and once more below.
            secure.sslParameters = secure.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                runCatching { serverNames = listOf(SNIHostName(host)) }
            }
            // No protocol versions older than these, which some Android versions would still offer.
            secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
                .takeIf { it.isNotEmpty() }?.let { secure.enabledProtocols = it.toTypedArray() }
            secure.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, secure.session)) {
                throw Refused("certificate is not for this host")
            }

            val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            // The address is plain characters that have passed java.net.URI, which admits no
            // spaces or line breaks.
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: $userAgent\r\n" +
                "Accept: $accept\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n\r\n"
            secure.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            secure.outputStream.flush()

            val input = Limited(BufferedInputStream(secure.inputStream, 16 * 1024), maxBody + MAX_HEAD_BYTES)
            var status = 0
            var location: String? = null
            var length = -1L
            var chunked = false
            var plain = true
            // An answer may be preceded by a few provisional ones ("100", "103"), which carry nothing.
            for (head in 0..MAX_PROVISIONAL) {
                val parts = input.line().split(' ')
                if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw Refused("not an answer")
                status = parts[1].toIntOrNull() ?: throw Refused("not an answer")
                location = null
                length = -1L
                chunked = false
                plain = true
                var lines = 0
                while (true) {
                    val line = input.line()
                    if (line.isEmpty()) break
                    if (++lines > MAX_HEADER_LINES) throw Refused("too many header lines")
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    when (name) {
                        "location" -> location = value
                        "content-length" -> length = value.toLongOrNull() ?: throw Refused("bad length")
                        "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
                        "content-encoding" -> plain = value.isEmpty() || value.equals("identity", ignoreCase = true)
                    }
                }
                if (status !in 100..199) break
            }
            if (status != 200) return Reply(status, location, null)
            // Asked for as it is; anything packed would not be a picture as far as the cleaning goes.
            if (!plain || length > maxBody) return Reply(status, null, null)

            val body = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            fun take(count: Long) {
                var remaining = count
                while (remaining > 0) {
                    val read = input.read(buffer, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("cut short")
                    if (body.size() + read > maxBody) throw Refused("too large")
                    body.write(buffer, 0, read)
                    remaining -= read
                }
            }
            when {
                chunked -> while (true) {
                    val size = input.line().substringBefore(';').trim().toLongOrNull(16) ?: throw Refused("bad chunk")
                    if (size < 0) throw Refused("bad chunk")
                    if (size == 0L) break
                    take(size)
                    if (input.line().isNotEmpty()) throw Refused("bad chunk")
                }
                length >= 0 -> take(length)
                else -> while (true) {
                    val read = input.read(buffer, buffer.size)
                    if (read < 0) break
                    if (body.size() + read > maxBody) throw Refused("too large")
                    body.write(buffer, 0, read)
                }
            }
            return Reply(status, null, body.toByteArray())
        } finally {
            watchdog.cancel()
            val all = synchronized(opened) { opened.toList() }
            synchronized(connections) { connections -= all.toSet() }
            all.forEach { runCatching { it.close() } }
            runCatching { tls?.close() }
        }
    }

    /** Reads from [input], refusing to pass on more than [left] bytes in all, or a line longer than a few kilobytes. */
    private class Limited(private val input: InputStream, private var left: Long) {
        fun read(buffer: ByteArray, most: Int): Int {
            if (left <= 0) throw Refused("answer too large")
            val read = input.read(buffer, 0, min(most.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        /** One line of the answer's head, without its line ending. */
        fun line(): String {
            val line = StringBuilder()
            while (true) {
                if (left <= 0) throw Refused("answer too large")
                val byte = input.read()
                if (byte < 0) throw IOException("cut short")
                left--
                if (byte == '\n'.code) break
                if (line.length >= MAX_LINE_LENGTH) throw Refused("line too long")
                line.append((byte and 0xFF).toChar())
            }
            return if (line.endsWith('\r')) line.substring(0, line.length - 1) else line.toString()
        }
    }

    companion object {
        const val MAX_URL_LENGTH = 2_000
        const val FETCH_TIME_MS = 20_000L
        private const val HOST_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-"
        private const val MAX_ADDRESSES = 4
        private const val MAX_PROVISIONAL = 3
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIME_MS = 2_500
        private const val READ_TIME_MS = 8_000
        /** Everything an answer may send besides its body: its head and the framing around the body. */
        private const val MAX_HEAD_BYTES = 512L * 1024
        private const val MAX_LINE_LENGTH = 8 * 1024
        private const val MAX_HEADER_LINES = 100
    }
}
