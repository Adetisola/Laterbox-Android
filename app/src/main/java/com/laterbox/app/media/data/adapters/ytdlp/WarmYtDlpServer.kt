package com.laterbox.app.media.data.adapters.ytdlp

import android.content.Context
import android.util.Log
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class WarmExecutionResult(
    val output: String,
    val warnings: String? = null
)

enum class WarmFailureReason {
    DISABLED,
    RUNTIME_NOT_READY,
    STARTUP_FAILED,
    HANDSHAKE_FAILED,
    HANDSHAKE_TIMEOUT,
    SOCKET_CONNECT_FAILED,
    SOCKET_TIMEOUT,
    PREMATURE_EOF,
    PROTOCOL_MISMATCH,
    EXTRACTOR_ERROR,
    UNEXPECTED_ERROR
}

sealed interface WarmExecuteOutcome {
    data class Success(
        val output: String,
        val warnings: String? = null,
        val elapsedMs: Long = 0L,
        val warmHealthyAtStart: Boolean = false
    ) : WarmExecuteOutcome

    data class ExtractorError(
        val message: String,
        val warnings: String? = null,
        val elapsedMs: Long = 0L,
        val warmHealthyAtStart: Boolean = false
    ) : WarmExecuteOutcome

    data class MechanismFailure(
        val reason: WarmFailureReason,
        val detail: String,
        val elapsedMs: Long = 0L,
        val warmHealthyAtStart: Boolean = false,
        val cause: Throwable? = null
    ) : WarmExecuteOutcome
}

internal data class DaemonRuntimeEnv(
    val pythonPath: File,
    val ytdlpPath: File,
    val binDir: File? = null,
    val envLd: String? = null,
    val envSsl: String? = null,
    val envPyHome: String? = null,
    val tmpDir: String? = null
) {
    val ytdlpSignature: String
        get() = "${ytdlpPath.absolutePath}:${ytdlpPath.lastModified()}"
}

class WarmYtDlpServer internal constructor(
    private val appContext: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val runtimeEnvProvider: () -> DaemonRuntimeEnv? = { resolveDefaultRuntimeEnv() },
    private val processLauncher: (DaemonRuntimeEnv, File) -> Process = { env, script ->
        launchDefaultProcess(env, script)
    }
) {
    constructor(
        appContext: Context,
        userPreferencesRepository: UserPreferencesRepository
    ) : this(
        appContext = appContext,
        userPreferencesRepository = userPreferencesRepository,
        handshakeTimeoutMs = DEFAULT_HANDSHAKE_TIMEOUT_MS
    )

    private data class DaemonSession(
        val sessionId: Long,
        val process: Process,
        val port: Int,
        val token: String,
        val ytdlpSignature: String
    )

    private data class ActiveRequestHandle(
        val socket: Socket,
        val session: DaemonSession
    )

    private sealed interface StartDaemonResult {
        data class Ready(val session: DaemonSession) : StartDaemonResult
        data class Failed(val reason: WarmFailureReason, val detail: String) : StartDaemonResult
    }

    private val tag = "WarmYtDlpServer"
    private val startMutex = Mutex()
    private val concurrencySemaphore = Semaphore(2)
    private val sessionLock = Any()
    private val sessionIdCounter = AtomicLong(0L)

    @Volatile private var daemonProcess: Process? = null
    @Volatile private var serverPort: Int? = null
    @Volatile private var serverToken: String? = null
    @Volatile private var activeSession: DaemonSession? = null
    private val isRunning = AtomicBoolean(false)

    private val activeRequests = ConcurrentHashMap<String, ActiveRequestHandle>()
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<String>()

    private val daemonScriptContent = """
import sys
import os
import json
import socket
import threading
import time
import secrets

ytdlp_path = sys.argv[1] if len(sys.argv) > 1 else None
if ytdlp_path and ytdlp_path not in sys.path:
    sys.path.insert(0, ytdlp_path)

try:
    import yt_dlp
except Exception as e:
    sys.stderr.write(f"FATAL: cannot import yt_dlp from {ytdlp_path}: {e}\n")
    sys.stderr.flush()
    sys.exit(1)

TOKEN = secrets.token_hex(16)
IDLE_TIMEOUT_SEC = 180

server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server_sock.bind(("127.0.0.1", 0))
port = server_sock.getsockname()[1]
server_sock.listen(5)

print(f"READY port={port} token={TOKEN}", flush=True)

last_active = time.time()
active_reqs = set()
lock = threading.Lock()
running = True

def idle_checker():
    global running
    while running:
        time.sleep(5)
        with lock:
            if not active_reqs and (time.time() - last_active > IDLE_TIMEOUT_SEC):
                running = False
                try:
                    server_sock.close()
                except:
                    pass
                os._exit(0)

threading.Thread(target=idle_checker, daemon=True).start()

class YtDlpCustomLogger:
    def __init__(self):
        self.warnings = []
        self.errors = []
    def debug(self, msg):
        pass
    def warning(self, msg):
        self.warnings.append(str(msg))
    def error(self, msg):
        self.errors.append(str(msg))

def run_extraction(req_id, url, options):
    start = time.time()
    logger = YtDlpCustomLogger()
    try:
        args = list(options)
        if url:
            args.append(url)
        parser, opts, all_urls, ydl_opts = yt_dlp.parse_options(args)
        ydl_opts["logger"] = logger
        ydl_opts["quiet"] = True
        ydl_opts["no_color"] = True

        target_url = all_urls[0] if all_urls else url
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            elapsed_ms = int((time.time() - start) * 1000)
            if info is None or not isinstance(info, dict):
                err_msg = "yt-dlp returned no metadata or null info for URL"
                if logger.errors:
                    err_msg = "\n".join(logger.errors)
                return {
                    "id": req_id,
                    "success": False,
                    "error": err_msg,
                    "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                    "elapsed_ms": elapsed_ms
                }
            sanitized = ydl.sanitize_info(info)
            if sanitized is None or not isinstance(sanitized, dict):
                return {
                    "id": req_id,
                    "success": False,
                    "error": "Failed to sanitize extractor metadata",
                    "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                    "elapsed_ms": elapsed_ms
                }
            return {
                "id": req_id,
                "success": True,
                "output": json.dumps(sanitized),
                "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                "elapsed_ms": elapsed_ms
            }
    except Exception as e:
        elapsed_ms = int((time.time() - start) * 1000)
        err_msg = str(e)
        if not err_msg and logger.errors:
            err_msg = "\n".join(logger.errors)
        return {
            "id": req_id,
            "success": False,
            "error": err_msg or "Unknown extractor failure",
            "warnings": "\n".join(logger.warnings) if logger.warnings else None,
            "elapsed_ms": elapsed_ms
        }

def client_worker(conn):
    global last_active
    try:
        r = conn.makefile("r", encoding="utf-8")
        w = conn.makefile("w", encoding="utf-8")
        for line in r:
            line = line.strip()
            if not line:
                continue
            with lock:
                last_active = time.time()
            try:
                req = json.loads(line)
            except Exception:
                continue

            if req.get("token") != TOKEN:
                w.write(json.dumps({"id": req.get("id"), "success": False, "error": "Unauthorized token"}) + "\n")
                w.flush()
                continue

            action = req.get("action", "inspect")
            req_id = req.get("id")

            if action == "ping":
                w.write(json.dumps({"id": req_id, "success": True, "pong": True}) + "\n")
                w.flush()
            elif action == "shutdown":
                w.write(json.dumps({"id": req_id, "success": True, "status": "bye"}) + "\n")
                w.flush()
                os._exit(0)
            elif action == "cancel":
                with lock:
                    active_reqs.discard(req_id)
                w.write(json.dumps({"id": req_id, "success": True, "cancelled": True}) + "\n")
                w.flush()
            elif action == "inspect":
                url = req.get("url")
                opts = req.get("options", [])
                with lock:
                    active_reqs.add(req_id)
                try:
                    res = run_extraction(req_id, url, opts)
                    w.write(json.dumps(res) + "\n")
                    w.flush()
                finally:
                    with lock:
                        active_reqs.discard(req_id)
                        last_active = time.time()
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except:
            pass

while running:
    try:
        c, _ = server_sock.accept()
        threading.Thread(target=client_worker, args=(c,), daemon=True).start()
    except:
        if not running:
            break
""".trimIndent()

    suspend fun isFastEngineEnabled(): Boolean {
        return try {
            userPreferencesRepository.userPreferencesFlow.first().fastEngineEnabled
        } catch (_: Throwable) {
            true
        }
    }

    fun isHealthy(): Boolean {
        synchronized(sessionLock) {
            val session = activeSession ?: return false
            return isRunning.get() &&
                session.process.isAlive &&
                serverPort == session.port &&
                serverToken == session.token
        }
    }

    private fun ensureDaemonScript(): File {
        val scriptFile = File(appContext.filesDir, "ytdlp_daemon.py")
        if (!scriptFile.exists() || scriptFile.length() == 0L || scriptFile.readText() != daemonScriptContent) {
            scriptFile.writeText(daemonScriptContent)
        }
        return scriptFile
    }

    private fun setSessionLocked(session: DaemonSession?) {
        activeSession = session
        daemonProcess = session?.process
        serverPort = session?.port
        serverToken = session?.token
        isRunning.set(session != null)
    }

    private fun invalidateSessionIfSame(expected: DaemonSession, destroyProcess: Boolean = true) {
        var procToDestroy: Process? = null
        synchronized(sessionLock) {
            if (activeSession?.sessionId == expected.sessionId) {
                procToDestroy = activeSession?.process
                setSessionLocked(null)
            }
        }
        if (destroyProcess && procToDestroy != null) {
            try {
                procToDestroy?.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun startDaemon(): StartDaemonResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            currentCoroutineContext().ensureActive()

            val env = runtimeEnvProvider()
                ?: return@withContext StartDaemonResult.Failed(
                    WarmFailureReason.RUNTIME_NOT_READY,
                    "pythonPath or ytdlpPath is not initialized"
                )
            val expectedSig = env.ytdlpSignature

            synchronized(sessionLock) {
                val current = activeSession
                if (current != null &&
                    current.process.isAlive &&
                    isRunning.get() &&
                    current.ytdlpSignature == expectedSig
                ) {
                    return@withContext StartDaemonResult.Ready(current)
                }
                if (current != null) {
                    val staleProc = current.process
                    setSessionLocked(null)
                    try {
                        staleProc.destroyForcibly()
                    } catch (_: Throwable) {
                    }
                }
            }

            var newProc: Process? = null
            try {
                val scriptFile = ensureDaemonScript()
                newProc = processLauncher(env, scriptFile)
                val proc = newProc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))

                val recentStderr = ArrayDeque<String>(12)
                Thread({
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { errReader ->
                            var errLine: String?
                            while (errReader.readLine().also { errLine = it } != null) {
                                val line = errLine ?: continue
                                synchronized(recentStderr) {
                                    if (recentStderr.size >= 10) recentStderr.removeFirst()
                                    recentStderr.addLast(line)
                                }
                                Log.d(tag, "daemon stderr: $line")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }, "ytdlp-daemon-stderr").start()

                var firstStdoutLine: String? = null
                val handshakeLatch = CountDownLatch(1)
                Thread({
                    try {
                        firstStdoutLine = reader.readLine()
                    } catch (_: Throwable) {
                    } finally {
                        handshakeLatch.countDown()
                    }
                    // Continue draining stdout so the pipe never fills up and deadlocks Python
                    try {
                        while (reader.readLine() != null) {
                            // Drain
                        }
                    } catch (_: Throwable) {
                    }
                }, "ytdlp-daemon-stdout").start()

                val completedHandshake = handshakeLatch.await(handshakeTimeoutMs, TimeUnit.MILLISECONDS)
                val readyLine = firstStdoutLine
                if (!completedHandshake) {
                    val detail = synchronized(recentStderr) {
                        recentStderr.joinToString("; ").takeIf { it.isNotBlank() }
                    } ?: "Warm daemon handshake timed out after ${handshakeTimeoutMs}ms"
                    Log.w(tag, detail)
                    try {
                        proc.destroyForcibly()
                    } catch (_: Throwable) {
                    }
                    return@withContext StartDaemonResult.Failed(WarmFailureReason.HANDSHAKE_TIMEOUT, detail)
                }

                if (readyLine != null && readyLine.startsWith("READY ")) {
                    val parts = readyLine.substring(6).split(" ")
                    var parsedPort: Int? = null
                    var parsedToken: String? = null
                    for (part in parts) {
                        if (part.startsWith("port=")) {
                            parsedPort = part.substring(5).toIntOrNull()
                        } else if (part.startsWith("token=")) {
                            parsedToken = part.substring(6)
                        }
                    }
                    if (parsedPort != null && parsedPort > 0 && !parsedToken.isNullOrBlank()) {
                        val session = DaemonSession(
                            sessionId = sessionIdCounter.incrementAndGet(),
                            process = proc,
                            port = parsedPort,
                            token = parsedToken,
                            ytdlpSignature = expectedSig
                        )
                        synchronized(sessionLock) {
                            setSessionLocked(session)
                        }
                        Log.i(tag, "Warm yt-dlp daemon started on port $parsedPort")
                        return@withContext StartDaemonResult.Ready(session)
                    }
                }

                val stderrSummary = synchronized(recentStderr) {
                    recentStderr.joinToString("; ").takeIf { it.isNotBlank() }
                }
                val failDetail = "Daemon handshake failed: readyLine='${readyLine ?: "<eof>"}'" +
                    (stderrSummary?.let { " ($it)" } ?: "")
                Log.w(tag, failDetail)
                try {
                    proc.destroyForcibly()
                } catch (_: Throwable) {
                }
                StartDaemonResult.Failed(
                    reason = if (readyLine == null) WarmFailureReason.STARTUP_FAILED else WarmFailureReason.HANDSHAKE_FAILED,
                    detail = failDetail
                )
            } catch (ce: CancellationException) {
                try {
                    newProc?.destroyForcibly()
                } catch (_: Throwable) {
                }
                throw ce
            } catch (t: Throwable) {
                try {
                    newProc?.destroyForcibly()
                } catch (_: Throwable) {
                }
                val msg = "Failed to start warm daemon: ${t.message ?: t.javaClass.simpleName}"
                Log.w(tag, msg, t)
                StartDaemonResult.Failed(WarmFailureReason.STARTUP_FAILED, msg)
            }
        }
    }

    /**
     * Executes inspection via warm loopback daemon and reports detailed mechanism vs extractor outcomes.
     * Preserves coroutine cancellation cleanly without masking it as a transport failure or fallback.
     */
    suspend fun executeDetailed(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long
    ): WarmExecuteOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        currentCoroutineContext().ensureActive()

        if (!isFastEngineEnabled()) {
            return@withContext WarmExecuteOutcome.MechanismFailure(
                reason = WarmFailureReason.DISABLED,
                detail = "Warm yt-dlp engine is disabled in preferences",
                elapsedMs = 0L,
                warmHealthyAtStart = false
            )
        }

        if (cancelledRequestIds.contains(processId)) {
            throw CancellationException("Warm request $processId was cancelled before execution")
        }

        concurrencySemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            if (cancelledRequestIds.contains(processId)) {
                throw CancellationException("Warm request $processId was cancelled prior to daemon start")
            }

            val healthyAtStart = isHealthy()
            val session = when (val startResult = startDaemon()) {
                is StartDaemonResult.Ready -> startResult.session
                is StartDaemonResult.Failed -> {
                    return@withContext WarmExecuteOutcome.MechanismFailure(
                        reason = startResult.reason,
                        detail = startResult.detail,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        warmHealthyAtStart = healthyAtStart
                    )
                }
            }

            currentCoroutineContext().ensureActive()
            var socket: Socket? = null
            var cancelDisposable: kotlinx.coroutines.DisposableHandle? = null

            try {
                val sock = Socket()
                socket = sock
                activeRequests[processId] = ActiveRequestHandle(sock, session)
                cancelDisposable = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        cancel(processId)
                    }
                }

                if (cancelledRequestIds.contains(processId)) {
                    throw CancellationException("Warm request $processId was cancelled")
                }

                sock.connect(InetSocketAddress("127.0.0.1", session.port), 2000)
                sock.soTimeout = timeoutMs.coerceAtLeast(1000L).toInt()

                val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))

                val reqObj = JSONObject().apply {
                    put("id", processId)
                    put("token", session.token)
                    put("action", "inspect")
                    put("url", url)
                    val optsArr = JSONArray()
                    options.forEach { optsArr.put(it) }
                    put("options", optsArr)
                }

                writer.println(reqObj.toString())

                val respLine = reader.readLine()
                    ?: throw EOFException("Premature EOF from warm daemon")
                val respObj = JSONObject(respLine)
                val respId = respObj.optString("id")
                if (respId.isNotBlank() && respId != processId) {
                    invalidateSessionIfSame(session)
                    return@withContext WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.PROTOCOL_MISMATCH,
                        detail = "Warm daemon returned mismatched response id (expected $processId, got $respId)",
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        warmHealthyAtStart = healthyAtStart
                    )
                }

                val elapsed = System.currentTimeMillis() - startedAt
                val warn = respObj.optString("warnings").takeIf { it.isNotBlank() }
                val success = respObj.optBoolean("success", false)
                if (success) {
                    val out = respObj.optString("output")
                    if (out.isBlank() || out.trim() == "null") {
                        return@withContext WarmExecuteOutcome.ExtractorError(
                            message = "Warm extractor returned empty or null metadata",
                            warnings = warn,
                            elapsedMs = elapsed,
                            warmHealthyAtStart = healthyAtStart
                        )
                    }
                    WarmExecuteOutcome.Success(
                        output = out,
                        warnings = warn,
                        elapsedMs = elapsed,
                        warmHealthyAtStart = healthyAtStart
                    )
                } else {
                    val error = respObj.optString("error", "Unknown extraction error")
                    WarmExecuteOutcome.ExtractorError(
                        message = error,
                        warnings = warn,
                        elapsedMs = elapsed,
                        warmHealthyAtStart = healthyAtStart
                    )
                }
            } catch (ce: CancellationException) {
                cancel(processId)
                throw ce
            } catch (te: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                invalidateSessionIfSame(session)
                WarmExecuteOutcome.MechanismFailure(
                    reason = WarmFailureReason.SOCKET_TIMEOUT,
                    detail = te.message ?: "Warm daemon read timed out after ${timeoutMs}ms",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    warmHealthyAtStart = healthyAtStart,
                    cause = te
                )
            } catch (eof: EOFException) {
                if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                    throw CancellationException("Warm request $processId interrupted by cancellation", eof)
                }
                invalidateSessionIfSame(session)
                WarmExecuteOutcome.MechanismFailure(
                    reason = WarmFailureReason.PREMATURE_EOF,
                    detail = eof.message ?: "Warm daemon closed connection prematurely",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    warmHealthyAtStart = healthyAtStart,
                    cause = eof
                )
            } catch (ceConn: ConnectException) {
                currentCoroutineContext().ensureActive()
                invalidateSessionIfSame(session)
                WarmExecuteOutcome.MechanismFailure(
                    reason = WarmFailureReason.SOCKET_CONNECT_FAILED,
                    detail = ceConn.message ?: "Could not connect to warm daemon socket",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    warmHealthyAtStart = healthyAtStart,
                    cause = ceConn
                )
            } catch (se: SocketException) {
                if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                    throw CancellationException("Warm request socket closed due to cancellation", se)
                }
                invalidateSessionIfSame(session)
                WarmExecuteOutcome.MechanismFailure(
                    reason = WarmFailureReason.PREMATURE_EOF,
                    detail = se.message ?: "Warm daemon socket error",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    warmHealthyAtStart = healthyAtStart,
                    cause = se
                )
            } catch (t: Throwable) {
                if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                    throw CancellationException("Warm request aborted due to cancellation", t)
                }
                invalidateSessionIfSame(session)
                WarmExecuteOutcome.MechanismFailure(
                    reason = WarmFailureReason.UNEXPECTED_ERROR,
                    detail = t.message ?: t.javaClass.simpleName,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    warmHealthyAtStart = healthyAtStart,
                    cause = t
                )
            } finally {
                cancelDisposable?.dispose()
                activeRequests.remove(processId)
                cancelledRequestIds.remove(processId)
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Backwards-compatible execution entrypoint for general inspection callers.
     * Returns WarmExecutionResult if successful, or null if warm engine is disabled/unavailable (to trigger fallback).
     * Throws SocketTimeoutException on socket timeout and YoutubeDLException on extractor error.
     */
    suspend fun execute(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long
    ): WarmExecutionResult? {
        return when (val outcome = executeDetailed(url, options, processId, timeoutMs)) {
            is WarmExecuteOutcome.Success -> WarmExecutionResult(outcome.output, outcome.warnings)
            is WarmExecuteOutcome.ExtractorError -> throw YoutubeDLException(outcome.message)
            is WarmExecuteOutcome.MechanismFailure -> when (outcome.reason) {
                WarmFailureReason.SOCKET_TIMEOUT ->
                    throw (outcome.cause as? SocketTimeoutException ?: SocketTimeoutException(outcome.detail))
                else -> {
                    if (outcome.reason != WarmFailureReason.DISABLED) {
                        Log.w(tag, "Warm daemon unavailable (${outcome.reason}): ${outcome.detail}. Triggering fallback.")
                    }
                    null
                }
            }
        }
    }

    fun cancel(processId: String) {
        cancelledRequestIds.add(processId)
        val removedHandle = activeRequests.remove(processId)
        try {
            removedHandle?.socket?.close()
        } catch (_: Throwable) {
        }

        val portSnapshot: Int?
        val tokenSnapshot: String?
        val legacyProcToDestroy: Process?
        val shouldDestroyDaemon: Boolean

        synchronized(sessionLock) {
            portSnapshot = activeSession?.port ?: serverPort
            tokenSnapshot = activeSession?.token ?: serverToken
            shouldDestroyDaemon = activeRequests.isEmpty()
            if (shouldDestroyDaemon) {
                legacyProcToDestroy = activeSession?.process ?: daemonProcess
                setSessionLocked(null)
            } else {
                legacyProcToDestroy = null
            }
        }

        if (shouldDestroyDaemon) {
            try {
                legacyProcToDestroy?.destroyForcibly()
            } catch (_: Throwable) {
            }
        }

        if (portSnapshot != null && tokenSnapshot != null) {
            Thread({
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress("127.0.0.1", portSnapshot), 500)
                        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true)
                        val cancelReq = JSONObject().apply {
                            put("id", processId)
                            put("token", tokenSnapshot)
                            put("action", "cancel")
                        }
                        writer.println(cancelReq.toString())
                    }
                } catch (_: Throwable) {
                }
            }, "ytdlp-daemon-cancel").start()
        }
    }

    fun shutdown() {
        val portSnapshot: Int?
        val tokenSnapshot: String?
        val procToDestroy: Process?
        synchronized(sessionLock) {
            portSnapshot = activeSession?.port ?: serverPort
            tokenSnapshot = activeSession?.token ?: serverToken
            procToDestroy = activeSession?.process ?: daemonProcess
            setSessionLocked(null)
        }

        val socketsToClose = activeRequests.values.map { it.socket }
        activeRequests.clear()
        socketsToClose.forEach { sock ->
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }

        if (portSnapshot != null && tokenSnapshot != null) {
            Thread({
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress("127.0.0.1", portSnapshot), 1000)
                        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true)
                        writer.println(JSONObject().put("action", "shutdown").put("token", tokenSnapshot).toString())
                    }
                } catch (_: Throwable) {
                }
            }, "ytdlp-daemon-shutdown").start()
        }

        try {
            procToDestroy?.destroyForcibly()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000L

        internal fun resolveDefaultRuntimeEnv(): DaemonRuntimeEnv? {
            val pythonPath = try {
                val field = YoutubeDL::class.java.getDeclaredField("pythonPath")
                field.isAccessible = true
                field.get(null) as? File
            } catch (_: Throwable) { null }

            val ytdlpPath = try {
                val field = YoutubeDL::class.java.getDeclaredField("ytdlpPath")
                field.isAccessible = true
                field.get(null) as? File
            } catch (_: Throwable) { null }

            if (pythonPath == null || ytdlpPath == null) return null

            val binDir = try {
                val field = YoutubeDL::class.java.getDeclaredField("binDir")
                field.isAccessible = true
                field.get(null) as? File
            } catch (_: Throwable) { null }

            val envLd = try {
                val field = YoutubeDL::class.java.getDeclaredField("ENV_LD_LIBRARY_PATH")
                field.isAccessible = true
                field.get(null) as? String
            } catch (_: Throwable) { null }

            val envSsl = try {
                val field = YoutubeDL::class.java.getDeclaredField("ENV_SSL_CERT_FILE")
                field.isAccessible = true
                field.get(null) as? String
            } catch (_: Throwable) { null }

            val envPyHome = try {
                val field = YoutubeDL::class.java.getDeclaredField("ENV_PYTHONHOME")
                field.isAccessible = true
                field.get(null) as? String
            } catch (_: Throwable) { null }

            val tmpDir = try {
                val field = YoutubeDL::class.java.getDeclaredField("TMPDIR")
                field.isAccessible = true
                field.get(null) as? String
            } catch (_: Throwable) { null }

            return DaemonRuntimeEnv(
                pythonPath = pythonPath,
                ytdlpPath = ytdlpPath,
                binDir = binDir,
                envLd = envLd,
                envSsl = envSsl,
                envPyHome = envPyHome,
                tmpDir = tmpDir
            )
        }

        internal fun launchDefaultProcess(env: DaemonRuntimeEnv, scriptFile: File): Process {
            val pb = ProcessBuilder(
                listOf(env.pythonPath.absolutePath, scriptFile.absolutePath, env.ytdlpPath.absolutePath)
            )
            val procEnv = pb.environment()
            if (env.envLd != null) procEnv["LD_LIBRARY_PATH"] = env.envLd
            if (env.envSsl != null) procEnv["SSL_CERT_FILE"] = env.envSsl
            if (env.envPyHome != null) {
                procEnv["PYTHONHOME"] = env.envPyHome
                procEnv["HOME"] = env.envPyHome
            }
            if (env.tmpDir != null) procEnv["TMPDIR"] = env.tmpDir
            if (env.binDir != null) {
                val path = System.getenv("PATH") ?: ""
                procEnv["PATH"] = "$path:${env.binDir.absolutePath}"
            }
            return pb.start()
        }
    }
}
