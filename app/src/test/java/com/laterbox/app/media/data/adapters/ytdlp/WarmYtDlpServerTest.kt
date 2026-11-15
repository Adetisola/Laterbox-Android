package com.laterbox.app.media.data.adapters.ytdlp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarmYtDlpServerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private class QueueInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<Int>()
        private val closed = AtomicBoolean(false)

        fun pushLine(line: String) {
            if (closed.get()) return
            for (b in "$line\n".toByteArray(Charsets.UTF_8)) {
                queue.offer(b.toInt() and 0xFF)
            }
        }

        fun closeEof() {
            if (closed.compareAndSet(false, true)) {
                queue.offer(-1)
            }
        }

        override fun read(): Int {
            if (closed.get() && queue.isEmpty()) return -1
            return queue.take()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first == -1) return -1
            b[off] = first.toByte()
            var count = 1
            while (count < len) {
                val next = queue.poll() ?: break
                if (next == -1) {
                    closed.set(true)
                    break
                }
                b[off + count] = next.toByte()
                count++
            }
            return count
        }

        override fun close() {
            closeEof()
        }
    }

    private class FakeDaemonProcess(
        val token: String = "test_token_${System.nanoTime()}",
        emitReadyOnStart: Boolean = true,
        immediateCrash: Boolean = false,
        stderrOnStart: String? = null,
        private val onInspectRequest: (req: JSONObject, socket: Socket, writer: PrintWriter) -> Unit = { req, _, writer ->
            val id = req.optString("id")
            writer.println(
                JSONObject()
                    .put("id", id)
                    .put("success", true)
                    .put("output", JSONObject().put("id", "vid1").put("title", "Warm OK").toString())
                    .toString()
            )
        }
    ) : Process() {
        private val stdout = QueueInputStream()
        private val stderr = QueueInputStream()
        private val alive = AtomicBoolean(!immediateCrash)
        private val serverSocket = if (!immediateCrash && emitReadyOnStart) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        } else null
        private val clientSockets = CopyOnWriteArrayList<Socket>()

        val observedActions = CopyOnWriteArrayList<String>()
        val forciblyDestroyed = AtomicBoolean(false)

        init {
            stderrOnStart?.let { stderr.pushLine(it) }
            if (immediateCrash) {
                stdout.closeEof()
                stderr.closeEof()
            } else if (serverSocket != null) {
                Thread({
                    while (alive.get() && !serverSocket.isClosed) {
                        try {
                            val client = serverSocket.accept()
                            clientSockets.add(client)
                            Thread({
                                try {
                                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                                    val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), "UTF-8"), true)
                                    var line: String?
                                    while (reader.readLine().also { line = it } != null) {
                                        val req = JSONObject(line!!)
                                        val action = req.optString("action", "inspect")
                                        observedActions.add("$action:${req.optString("id")}")
                                        when (action) {
                                            "shutdown" -> {
                                                writer.println(JSONObject().put("success", true).toString())
                                                destroyForcibly()
                                            }
                                            "cancel" -> {
                                                writer.println(JSONObject().put("id", req.optString("id")).put("success", true).put("cancelled", true).toString())
                                            }
                                            "inspect" -> {
                                                onInspectRequest(req, client, writer)
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    try {
                                        client.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }, "fake-daemon-worker").start()
                        } catch (_: Throwable) {
                            break
                        }
                    }
                }, "fake-daemon-accept").start()
                stdout.pushLine("READY port=${serverSocket.localPort} token=$token")
            }
        }

        override fun isAlive(): Boolean = alive.get()

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed.set(true)
            alive.set(false)
            stdout.closeEof()
            stderr.closeEof()
            try {
                serverSocket?.close()
            } catch (_: Throwable) {
            }
            clientSockets.forEach {
                try {
                    it.close()
                } catch (_: Throwable) {
                }
            }
            return this
        }

        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException("alive") else 0
        override fun getErrorStream(): InputStream = stderr
        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
    }

    private fun testRuntimeEnv(sigSuffix: Long = 1000L): DaemonRuntimeEnv {
        val dummyFile = File(context.cacheDir, "fake_ytdlp_$sigSuffix").apply {
            parentFile?.mkdirs()
            writeText("dummy")
            setLastModified(sigSuffix)
        }
        return DaemonRuntimeEnv(
            pythonPath = File(context.cacheDir, "fake_python"),
            ytdlpPath = dummyFile
        )
    }

    @Test
    fun youtubeDL_reflectionTargets_existOnClass() {
        val clazz = YoutubeDL::class.java

        val targetFields = listOf(
            "pythonPath",
            "ytdlpPath",
            "binDir",
            "ENV_LD_LIBRARY_PATH",
            "ENV_SSL_CERT_FILE",
            "ENV_PYTHONHOME",
            "TMPDIR"
        )

        for (fieldName in targetFields) {
            val field = try {
                clazz.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                null
            }
            assertNotNull("Field '$fieldName' should exist on YoutubeDL class", field)
            field?.isAccessible = true
        }
    }

    @Test
    fun cancel_untrackedIdIsNoOp_andDoesNotDestroyDaemonProcess() {
        val userPrefs = UserPreferencesRepository(context)
        val server = WarmYtDlpServer(context, userPrefs)

        val procField = WarmYtDlpServer::class.java.getDeclaredField("daemonProcess")
        procField.isAccessible = true

        var destroyedForcibly = false
        val mockProcess = object : Process() {
            override fun destroy() {}
            override fun destroyForcibly(): Process {
                destroyedForcibly = true
                return this
            }
            override fun exitValue(): Int = 0
            override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun waitFor(): Int = 0
        }

        procField.set(server, mockProcess)

        server.cancel("test_process_id")
        server.cancel("proc_1_std")

        assertFalse("cancel(untrackedProcessId) must be a strict no-op and NOT kill warm daemon", destroyedForcibly)
    }

    @Test
    fun executeDetailed_disabledInPreferences_returnsDisabledAndNeverStartsDaemon() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        var starts = 0
        userPrefs.setFastEngineEnabled(false)
        try {
            val server = WarmYtDlpServer(
                appContext = context,
                userPreferencesRepository = userPrefs,
                handshakeTimeoutMs = 500L,
                runtimeEnvProvider = { testRuntimeEnv() },
                processLauncher = { _, _ ->
                    starts++
                    FakeDaemonProcess()
                }
            )
            val outcome = server.executeDetailed("https://youtu.be/abc", listOf("--dump-single-json"), "p_disabled", 2000L)
            assertTrue(outcome is WarmExecuteOutcome.MechanismFailure)
            assertEquals(WarmFailureReason.DISABLED, (outcome as WarmExecuteOutcome.MechanismFailure).reason)
            assertEquals(0, starts)
        } finally {
            userPrefs.setFastEngineEnabled(true)
        }
    }

    @Test
    fun executeDetailed_normalSuccess_andResponseIdMismatch_andExtractorError() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        val procs = mutableListOf<FakeDaemonProcess>()
        val mode = AtomicInteger(0)
        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                val p = FakeDaemonProcess { req, _, writer ->
                    val id = req.optString("id")
                    when (mode.get()) {
                        0 -> writer.println(JSONObject().put("id", id).put("success", true).put("output", """{"id":"ok1","title":"Healthy"}""").toString())
                        1 -> writer.println(JSONObject().put("id", id).put("success", false).put("error", "ERROR: [youtube] Private video").toString())
                        2 -> writer.println(JSONObject().put("id", "wrong_other_id").put("success", true).put("output", "{}").toString())
                    }
                }
                procs.add(p)
                p
            }
        )

        try {
            // 1. Normal success -> daemon stays healthy
            val ok = server.executeDetailed("https://youtu.be/ok1", listOf("--dump-single-json"), "req_1", 2000L)
            assertTrue(ok is WarmExecuteOutcome.Success)
            assertTrue(server.isHealthy())
            assertEquals(1, procs.size)

            // 2. Authoritative extractor error -> ExtractorError, daemon stays healthy
            mode.set(1)
            val exErr = server.executeDetailed("https://youtu.be/priv", listOf("--dump-single-json"), "req_2", 2000L)
            assertTrue(exErr is WarmExecuteOutcome.ExtractorError)
            assertTrue((exErr as WarmExecuteOutcome.ExtractorError).message.contains("Private video"))
            assertTrue(server.isHealthy())
            assertEquals(1, procs.size)

            // 3. Mismatched response ID -> PROTOCOL_MISMATCH + invalidates daemon
            mode.set(2)
            val mismatch = server.executeDetailed("https://youtu.be/mis", listOf("--dump-single-json"), "req_3", 2000L)
            assertTrue(mismatch is WarmExecuteOutcome.MechanismFailure)
            assertEquals(WarmFailureReason.PROTOCOL_MISMATCH, (mismatch as WarmExecuteOutcome.MechanismFailure).reason)
            assertFalse("Daemon must be invalidated on protocol mismatch", server.isHealthy())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_startupCrashAndHandshakeTimeoutFailFast() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)

        val crashingServer = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 2000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess(immediateCrash = true, stderrOnStart = "ImportError: missing dependency")
            }
        )
        val crashOutcome = crashingServer.executeDetailed("https://youtu.be/a", emptyList(), "p_crash", 2000L)
        assertTrue(crashOutcome is WarmExecuteOutcome.MechanismFailure)
        assertEquals(WarmFailureReason.STARTUP_FAILED, (crashOutcome as WarmExecuteOutcome.MechanismFailure).reason)
        assertFalse(crashingServer.isHealthy())

        var hungProc: FakeDaemonProcess? = null
        val hungHandshakeServer = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 150L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess(emitReadyOnStart = false).also { hungProc = it }
            }
        )
        val hsTimeout = hungHandshakeServer.executeDetailed("https://youtu.be/b", emptyList(), "p_hs", 2000L)
        assertTrue(hsTimeout is WarmExecuteOutcome.MechanismFailure)
        assertEquals(WarmFailureReason.HANDSHAKE_TIMEOUT, (hsTimeout as WarmExecuteOutcome.MechanismFailure).reason)
        assertTrue("Hung startup process must be forcibly destroyed", hungProc?.forciblyDestroyed?.get() == true)
    }

    @Test
    fun executeDetailed_socketTimeoutAndPrematureEofInvalidateDaemonAndRecoverOnNextLaunch() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        val launchCount = AtomicInteger(0)

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                val num = launchCount.incrementAndGet()
                FakeDaemonProcess { req, sock, writer ->
                    val id = req.optString("id")
                    when (num) {
                        1 -> Thread.sleep(2000L) // Cause SocketTimeoutException on client (timeoutMs=1000)
                        2 -> sock.close() // Cause premature EOF
                        else -> writer.println(JSONObject().put("id", id).put("success", true).put("output", """{"id":"recovered"}""").toString())
                    }
                }
            }
        )

        try {
            val out1 = server.executeDetailed("https://youtu.be/1", emptyList(), "p_1", 1000L)
            assertTrue(out1 is WarmExecuteOutcome.MechanismFailure)
            assertEquals(WarmFailureReason.SOCKET_TIMEOUT, (out1 as WarmExecuteOutcome.MechanismFailure).reason)
            assertFalse("Daemon must be invalidated after socket timeout", server.isHealthy())

            val out2 = server.executeDetailed("https://youtu.be/2", emptyList(), "p_2", 1000L)
            assertTrue(out2 is WarmExecuteOutcome.MechanismFailure)
            assertEquals(WarmFailureReason.PREMATURE_EOF, (out2 as WarmExecuteOutcome.MechanismFailure).reason)
            assertFalse("Daemon must be invalidated after premature EOF", server.isHealthy())

            val out3 = server.executeDetailed("https://youtu.be/3", emptyList(), "p_3", 1500L)
            assertTrue(out3 is WarmExecuteOutcome.Success)
            assertTrue(server.isHealthy())
            assertEquals(3, launchCount.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_concurrentCancellationClosesRequestSocketWithoutKillingSiblingRequest() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        val reqAStarted = CountDownLatch(1)
        val reqBStarted = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        var proc: FakeDaemonProcess? = null

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess { req, _, writer ->
                    val id = req.optString("id")
                    if (id == "req_A") {
                        reqAStarted.countDown()
                        Thread.sleep(3000L)
                    } else if (id == "req_B") {
                        reqBStarted.countDown()
                        releaseB.await(3, TimeUnit.SECONDS)
                        writer.println(JSONObject().put("id", id).put("success", true).put("output", """{"id":"B_ok"}""").toString())
                    }
                }.also { proc = it }
            }
        )

        try {
            val jobA = async(Dispatchers.IO) {
                server.executeDetailed("https://youtu.be/A", emptyList(), "req_A", 5000L)
            }
            val jobB = async(Dispatchers.IO) {
                server.executeDetailed("https://youtu.be/B", emptyList(), "req_B", 5000L)
            }

            assertTrue(reqAStarted.await(2, TimeUnit.SECONDS))
            assertTrue(reqBStarted.await(2, TimeUnit.SECONDS))

            // Cancel request A only while B is still active!
            jobA.cancel(CancellationException("Cancel A"))
            var aCancelled = false
            try {
                jobA.await()
            } catch (_: CancellationException) {
                aCancelled = true
            }
            assertTrue("Cancelled request A must throw CancellationException", aCancelled)
            assertFalse("Sibling request B is still active so daemon must NOT be destroyed", proc?.forciblyDestroyed?.get() == true)

            releaseB.countDown()
            val outcomeB = jobB.await()
            assertTrue("Sibling request B must finish cleanly despite A being cancelled", outcomeB is WarmExecuteOutcome.Success)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_staleBinarySignatureRecyclesDaemon() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        val currentSig = AtomicInteger(1)
        val launchCount = AtomicInteger(0)

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv(sigSuffix = currentSig.get() * 1000L) },
            processLauncher = { _, _ ->
                launchCount.incrementAndGet()
                FakeDaemonProcess()
            }
        )

        try {
            assertTrue(server.executeDetailed("https://youtu.be/1", emptyList(), "sig_1", 2000L) is WarmExecuteOutcome.Success)
            assertEquals(1, launchCount.get())

            // Same signature reuses existing daemon
            assertTrue(server.executeDetailed("https://youtu.be/2", emptyList(), "sig_2", 2000L) is WarmExecuteOutcome.Success)
            assertEquals(1, launchCount.get())

            // Changed ytdlpPath signature (e.g. background update) recycles daemon automatically
            currentSig.set(2)
            assertTrue(server.executeDetailed("https://youtu.be/3", emptyList(), "sig_3", 2000L) is WarmExecuteOutcome.Success)
            assertEquals(2, launchCount.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_streamingHeartbeatsKeepSocketAliveAndPopulateMetadata() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        var capturedCompactFlag: Boolean? = null

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess { req, _, writer ->
                    val id = req.optString("id")
                    capturedCompactFlag = req.optBoolean("transcript_compact", false)
                    Thread.sleep(600L)
                    writer.println(JSONObject().put("id", id).put("type", "heartbeat").put("elapsed_ms", 600).toString())
                    Thread.sleep(600L)
                    writer.println(JSONObject().put("id", id).put("type", "heartbeat").put("elapsed_ms", 1200).toString())
                    Thread.sleep(200L)
                    writer.println(
                        JSONObject()
                            .put("id", id)
                            .put("success", true)
                            .put("elapsed_ms", 1400L)
                            .put("js_challenges", 2)
                            .put("effective_clients", org.json.JSONArray(listOf("android_vr", "tv")))
                            .put("output", """{"id":"hb1","title":"Heartbeat OK"}""")
                            .toString()
                    )
                }
            },
            inactivityTimeoutMs = 1000L
        )

        try {
            val outcome = server.executeDetailed(
                url = "https://youtu.be/hb1",
                options = listOf("--dump-single-json"),
                processId = "req_hb",
                timeoutMs = 3500L,
                compactTranscript = true
            )
            assertTrue("Heartbeats should keep socket alive beyond 1000ms inactivity window, got $outcome", outcome is WarmExecuteOutcome.Success)
            val ok = outcome as WarmExecuteOutcome.Success
            assertEquals(true, capturedCompactFlag)
            assertEquals(2, ok.heartbeatCount)
            assertEquals(2, ok.jsChallengesSolved)
            assertEquals(listOf("android_vr", "tv"), ok.effectiveClients)
            assertEquals(1400L, ok.extractMs)
            assertTrue(ok.output.contains("Heartbeat OK"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_continuousHeartbeatsPastOverallDeadline_returnsOverallDeadlineTimeout() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess { req, _, writer ->
                    val id = req.optString("id")
                    repeat(8) { idx ->
                        Thread.sleep(250L)
                        writer.println(JSONObject().put("id", id).put("type", "heartbeat").put("elapsed_ms", (idx + 1) * 250).toString())
                    }
                }
            },
            inactivityTimeoutMs = 1000L
        )

        try {
            val outcome = server.executeDetailed("https://youtu.be/dl", listOf("--dump-single-json"), "req_dl", 1200L)
            assertTrue("Expected MechanismFailure, got $outcome", outcome is WarmExecuteOutcome.MechanismFailure)
            val fail = outcome as WarmExecuteOutcome.MechanismFailure
            assertEquals(WarmFailureReason.OVERALL_DEADLINE_TIMEOUT, fail.reason)
            assertTrue("Expected heartbeatCount > 0 before deadline", fail.heartbeatCount > 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeDetailed_socketTimeoutWithActiveSibling_doesNotDestroySiblingDaemon() = runTest {
        val userPrefs = UserPreferencesRepository(context)
        userPrefs.setFastEngineEnabled(true)
        val bothStarted = CountDownLatch(2)
        val allowBFinish = CountDownLatch(1)
        var proc: FakeDaemonProcess? = null

        val server = WarmYtDlpServer(
            appContext = context,
            userPreferencesRepository = userPrefs,
            handshakeTimeoutMs = 1000L,
            runtimeEnvProvider = { testRuntimeEnv() },
            processLauncher = { _, _ ->
                FakeDaemonProcess { req, _, writer ->
                    val id = req.optString("id")
                    bothStarted.countDown()
                    if (id == "req_slow") {
                        Thread.sleep(2500L) // Exceeds 1000ms timeout
                    } else if (id == "req_fast_after") {
                        allowBFinish.await(3, TimeUnit.SECONDS)
                        writer.println(JSONObject().put("id", id).put("success", true).put("output", """{"id":"B_ok"}""").toString())
                    }
                }.also { proc = it }
            }
        )

        try {
            val slowJob = async(Dispatchers.IO) {
                server.executeDetailed("https://youtu.be/slow", emptyList(), "req_slow", 1000L)
            }
            val siblingJob = async(Dispatchers.IO) {
                server.executeDetailed("https://youtu.be/sib", emptyList(), "req_fast_after", 4000L)
            }

            assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
            val slowOutcome = slowJob.await()
            assertTrue(slowOutcome is WarmExecuteOutcome.MechanismFailure)
            assertEquals(WarmFailureReason.SOCKET_TIMEOUT, (slowOutcome as WarmExecuteOutcome.MechanismFailure).reason)
            assertFalse("Sibling request still active so daemon process must NOT be forcibly destroyed", proc?.forciblyDestroyed?.get() == true)

            allowBFinish.countDown()
            val siblingOutcome = siblingJob.await()
            assertTrue("Sibling request must succeed after sibling socket timeout", siblingOutcome is WarmExecuteOutcome.Success)
        } finally {
            server.shutdown()
        }
    }
}

