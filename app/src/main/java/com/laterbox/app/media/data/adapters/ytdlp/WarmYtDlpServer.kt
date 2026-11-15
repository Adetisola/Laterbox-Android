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
    OVERALL_DEADLINE_TIMEOUT,
    PREMATURE_EOF,
    PROTOCOL_MISMATCH,
    EXTRACTOR_ERROR,
    UNEXPECTED_ERROR
}

sealed interface WarmExecuteOutcome {
    val elapsedMs: Long
    val warmHealthyAtStart: Boolean
    val startupMs: Long
    val handshakeMs: Long
    val socketWaitMs: Long
    val extractMs: Long
    val effectiveClients: List<String>
    val jsChallengesSolved: Int
    val heartbeatCount: Int
    val debugLogs: List<String>

    data class Success(
        val output: String,
        val warnings: String? = null,
        override val elapsedMs: Long = 0L,
        override val warmHealthyAtStart: Boolean = false,
        override val startupMs: Long = 0L,
        override val handshakeMs: Long = 0L,
        override val socketWaitMs: Long = 0L,
        override val extractMs: Long = 0L,
        override val effectiveClients: List<String> = emptyList(),
        override val jsChallengesSolved: Int = 0,
        override val heartbeatCount: Int = 0,
        override val debugLogs: List<String> = emptyList()
    ) : WarmExecuteOutcome

    data class ExtractorError(
        val message: String,
        val warnings: String? = null,
        override val elapsedMs: Long = 0L,
        override val warmHealthyAtStart: Boolean = false,
        override val startupMs: Long = 0L,
        override val handshakeMs: Long = 0L,
        override val socketWaitMs: Long = 0L,
        override val extractMs: Long = 0L,
        override val effectiveClients: List<String> = emptyList(),
        override val jsChallengesSolved: Int = 0,
        override val heartbeatCount: Int = 0,
        override val debugLogs: List<String> = emptyList()
    ) : WarmExecuteOutcome

    data class MechanismFailure(
        val reason: WarmFailureReason,
        val detail: String,
        override val elapsedMs: Long = 0L,
        override val warmHealthyAtStart: Boolean = false,
        val cause: Throwable? = null,
        override val startupMs: Long = 0L,
        override val handshakeMs: Long = 0L,
        override val socketWaitMs: Long = 0L,
        override val extractMs: Long = 0L,
        override val effectiveClients: List<String> = emptyList(),
        override val jsChallengesSolved: Int = 0,
        override val heartbeatCount: Int = 0,
        override val debugLogs: List<String> = emptyList()
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
    },
    private val inactivityTimeoutMs: Long = DEFAULT_INACTIVITY_TIMEOUT_MS
) {
    constructor(
        appContext: Context,
        userPreferencesRepository: UserPreferencesRepository
    ) : this(
        appContext = appContext,
        userPreferencesRepository = userPreferencesRepository,
        handshakeTimeoutMs = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        inactivityTimeoutMs = DEFAULT_INACTIVITY_TIMEOUT_MS
    )

    private data class DaemonSession(
        val sessionId: Long,
        val process: Process,
        val port: Int,
        val token: String,
        val ytdlpSignature: String
    )

    private data class ActiveRequestHandle(
        val processId: String,
        val socket: Socket,
        val session: DaemonSession
    )

    private sealed interface StartDaemonResult {
        data class Ready(val session: DaemonSession, val startupMs: Long = 0L, val handshakeMs: Long = 0L) : StartDaemonResult
        data class Failed(val reason: WarmFailureReason, val detail: String, val startupMs: Long = 0L) : StartDaemonResult
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

    private val ownedRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val activeRequests = ConcurrentHashMap<String, ActiveRequestHandle>()
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<String>()

    private val daemonScriptContent = """
import sys
import os
import re
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
HEARTBEAT_INTERVAL_SEC = 2.5

server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server_sock.bind(("127.0.0.1", 0))
port = server_sock.getsockname()[1]
server_sock.listen(8)

print(f"READY port={port} token={TOKEN}", flush=True)

last_active = time.time()
active_reqs = set()
cancel_events = {}
lock = threading.Lock()
running = True

class _CancelledByClientException(Exception):
    pass

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

CLIENT_RE = re.compile(r"Downloading\s+([a-z0-9_]+)\s+player\s+API", re.IGNORECASE)

class YtDlpCustomLogger:
    def __init__(self, cancel_event):
        self.warnings = []
        self.errors = []
        self.debug_logs = []
        self.clients = []
        self.js_challenges = 0
        self.stage = "extracting_metadata"
        self.cancel_event = cancel_event

    def _check_cancel(self):
        if self.cancel_event is not None and self.cancel_event.is_set():
            raise _CancelledByClientException("Cancelled by client")

    def debug(self, msg):
        self._check_cancel()
        s = str(msg)
        low = s.lower()
        if "solving js challenge" in low or "ejs" in low:
            self.stage = "js_challenge"
            if "solving js challenge" in low:
                self.js_challenges += 1
        elif "downloading m3u8" in low or "playlist" in low:
            self.stage = "playlist_fetch"
        m = CLIENT_RE.search(s)
        if m:
            cli = m.group(1)
            if cli not in self.clients:
                self.clients.append(cli)
        if len(self.debug_logs) < 60 and any(k in low for k in ("[youtube]", "client", "challenge", "po token", "subtitles", "player", "error", "warning")):
            self.debug_logs.append(s[:220])

    def warning(self, msg):
        self._check_cancel()
        if len(self.warnings) < 40:
            self.warnings.append(str(msg)[:300])

    def error(self, msg):
        if len(self.errors) < 40:
            self.errors.append(str(msg)[:300])

def _compact_captions_dict(caps):
    if not isinstance(caps, dict):
        return caps
    out = {}
    for lang, items in caps.items():
        if str(lang).lower() == "live_chat" or not isinstance(items, list):
            continue
        seen_exts = set()
        kept = []
        for item in items:
            if not isinstance(item, dict):
                continue
            ext = str(item.get("ext") or "").lower().strip()
            url = str(item.get("url") or "").strip()
            if not ext or not url or ext in seen_exts:
                continue
            seen_exts.add(ext)
            kept.append({k: item[k] for k in ("ext", "url", "name", "__yt_dlp_client") if k in item})
        if kept:
            out[lang] = kept
    return out

def _collect_clients_from_info(info, logger_clients):
    clients = list(logger_clients)
    for bucket_name in ("formats",):
        for item in (info.get(bucket_name) or []):
            if isinstance(item, dict):
                c = item.get("__yt_dlp_client")
                if c and c not in clients:
                    clients.append(c)
    for bucket_name in ("subtitles", "automatic_captions"):
        bucket = info.get(bucket_name) or {}
        if isinstance(bucket, dict):
            for items in bucket.values():
                if isinstance(items, list) and items and isinstance(items[0], dict):
                    c = items[0].get("__yt_dlp_client")
                    if c and c not in clients:
                        clients.append(c)
                if len(clients) >= 6:
                    break
    return clients

def _build_compact_transcript_info(sanitized, effective_clients):
    if not isinstance(sanitized, dict) or "entries" in sanitized:
        return sanitized
    compact = {}
    for k in ("id", "title", "channel", "uploader", "duration", "thumbnail", "webpage_url", "extractor_key", "requested_subtitles"):
        if k in sanitized and sanitized[k] is not None:
            compact[k] = sanitized[k]
    if "subtitles" in sanitized:
        compact["subtitles"] = _compact_captions_dict(sanitized["subtitles"])
    if "automatic_captions" in sanitized:
        compact["automatic_captions"] = _compact_captions_dict(sanitized["automatic_captions"])
    if effective_clients:
        compact["_laterbox_clients"] = effective_clients
    return compact

def run_extraction(req_id, url, options, transcript_compact, logger, cancel_event):
    start = time.time()
    try:
        args = list(options)
        if url:
            args.append(url)
        parser, opts, all_urls, ydl_opts = yt_dlp.parse_options(args)
        ydl_opts["logger"] = logger
        ydl_opts["quiet"] = False
        ydl_opts["no_color"] = True

        target_url = all_urls[0] if all_urls else url
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            if cancel_event.is_set():
                raise _CancelledByClientException("Cancelled")
            logger.stage = "sanitizing"
            if info is None or not isinstance(info, dict):
                elapsed_ms = int((time.time() - start) * 1000)
                err_msg = "yt-dlp returned no metadata or null info for URL"
                if logger.errors:
                    err_msg = "\n".join(logger.errors)
                return {
                    "id": req_id,
                    "type": "result",
                    "success": False,
                    "error": err_msg,
                    "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                    "debug_logs": logger.debug_logs,
                    "effective_clients": logger.clients,
                    "js_challenges": logger.js_challenges,
                    "elapsed_ms": elapsed_ms
                }
            effective_clients = _collect_clients_from_info(info, logger.clients)
            sanitized = ydl.sanitize_info(info)
            if sanitized is None or not isinstance(sanitized, dict):
                elapsed_ms = int((time.time() - start) * 1000)
                return {
                    "id": req_id,
                    "type": "result",
                    "success": False,
                    "error": "Failed to sanitize extractor metadata",
                    "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                    "debug_logs": logger.debug_logs,
                    "effective_clients": effective_clients,
                    "js_challenges": logger.js_challenges,
                    "elapsed_ms": elapsed_ms
                }
            if transcript_compact:
                sanitized = _build_compact_transcript_info(sanitized, effective_clients)
            out_json = json.dumps(sanitized)
            elapsed_ms = int((time.time() - start) * 1000)
            return {
                "id": req_id,
                "type": "result",
                "success": True,
                "output": out_json,
                "warnings": "\n".join(logger.warnings) if logger.warnings else None,
                "debug_logs": logger.debug_logs,
                "effective_clients": effective_clients,
                "js_challenges": logger.js_challenges,
                "elapsed_ms": elapsed_ms
            }
    except _CancelledByClientException:
        elapsed_ms = int((time.time() - start) * 1000)
        return {
            "id": req_id,
            "type": "result",
            "success": False,
            "cancelled": True,
            "error": "Request cancelled",
            "elapsed_ms": elapsed_ms
        }
    except Exception as e:
        elapsed_ms = int((time.time() - start) * 1000)
        err_msg = str(e)
        if not err_msg and logger.errors:
            err_msg = "\n".join(logger.errors)
        return {
            "id": req_id,
            "type": "result",
            "success": False,
            "error": err_msg or "Unknown extractor failure",
            "warnings": "\n".join(logger.warnings) if logger.warnings else None,
            "debug_logs": logger.debug_logs,
            "effective_clients": logger.clients,
            "js_challenges": logger.js_challenges,
            "elapsed_ms": elapsed_ms
        }

def client_worker(conn):
    global last_active
    try:
        r = conn.makefile("r", encoding="utf-8")
        w = conn.makefile("w", encoding="utf-8")
        write_lock = threading.Lock()
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
                with write_lock:
                    w.write(json.dumps({"id": req.get("id"), "type": "result", "success": False, "error": "Unauthorized token"}) + "\n")
                    w.flush()
                continue

            action = req.get("action", "inspect")
            req_id = req.get("id")

            if action == "ping":
                with write_lock:
                    w.write(json.dumps({"id": req_id, "type": "result", "success": True, "pong": True}) + "\n")
                    w.flush()
            elif action == "shutdown":
                with write_lock:
                    w.write(json.dumps({"id": req_id, "type": "result", "success": True, "status": "bye"}) + "\n")
                    w.flush()
                os._exit(0)
            elif action == "cancel":
                with lock:
                    active_reqs.discard(req_id)
                    ev = cancel_events.get(req_id)
                    if ev is not None:
                        ev.set()
                with write_lock:
                    w.write(json.dumps({"id": req_id, "type": "result", "success": True, "cancelled": True}) + "\n")
                    w.flush()
            elif action == "inspect":
                url = req.get("url")
                opts = req.get("options", [])
                transcript_compact = bool(req.get("transcript_compact", False))
                cancel_event = threading.Event()
                logger = YtDlpCustomLogger(cancel_event)
                done_event = threading.Event()
                req_start = time.time()

                with lock:
                    active_reqs.add(req_id)
                    cancel_events[req_id] = cancel_event

                def heartbeat_loop():
                    while not done_event.wait(HEARTBEAT_INTERVAL_SEC):
                        if cancel_event.is_set():
                            break
                        try:
                            pkt = json.dumps({
                                "id": req_id,
                                "type": "heartbeat",
                                "stage": logger.stage,
                                "elapsed_ms": int((time.time() - req_start) * 1000)
                            })
                            with write_lock:
                                if not done_event.is_set():
                                    w.write(pkt + "\n")
                                    w.flush()
                        except Exception:
                            break

                hb_thread = threading.Thread(target=heartbeat_loop, daemon=True)
                hb_thread.start()
                try:
                    res = run_extraction(req_id, url, opts, transcript_compact, logger, cancel_event)
                    done_event.set()
                    if not cancel_event.is_set():
                        with write_lock:
                            w.write(json.dumps(res) + "\n")
                            w.flush()
                finally:
                    done_event.set()
                    with lock:
                        active_reqs.discard(req_id)
                        cancel_events.pop(req_id, None)
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

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private class OverallDeadlineTimeoutException(
        val budgetMs: Long,
        val heartbeats: Int
    ) : SocketTimeoutException("Warm daemon exceeded overall deadline (${budgetMs}ms after $heartbeats heartbeats)")

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null || arr.length() == 0) return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) list.add(v)
        }
        return list
    }

    private fun pruneCancelledIdsIfNeeded() {
        if (cancelledRequestIds.size > 128) {
            val activeKeys = activeRequests.keys.toHashSet()
            val ownedKeys = ownedRequestIds.toHashSet()
            cancelledRequestIds.removeIf { it !in activeKeys && it !in ownedKeys }
        }
    }

    private fun invalidateSessionIfSame(
        expected: DaemonSession,
        excludeProcessId: String? = null,
        destroyProcess: Boolean = true
    ) {
        var procToDestroy: Process? = null
        synchronized(sessionLock) {
            val hasSiblings = activeRequests.values.any {
                it.session.sessionId == expected.sessionId && it.processId != excludeProcessId
            }
            if (activeSession?.sessionId == expected.sessionId) {
                if (!hasSiblings) {
                    procToDestroy = activeSession?.process
                }
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
                    return@withContext StartDaemonResult.Ready(current, startupMs = 0L, handshakeMs = 0L)
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
            val launchStartedMono = monotonicNowMs()
            try {
                val scriptFile = ensureDaemonScript()
                newProc = processLauncher(env, scriptFile)
                val afterLaunchMono = monotonicNowMs()
                val startupMs = (afterLaunchMono - launchStartedMono).coerceAtLeast(0L)
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
                val handshakeMs = (monotonicNowMs() - afterLaunchMono).coerceAtLeast(0L)
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
                    return@withContext StartDaemonResult.Failed(
                        reason = WarmFailureReason.HANDSHAKE_TIMEOUT,
                        detail = detail,
                        startupMs = startupMs + handshakeMs
                    )
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
                        Log.i(tag, "Warm yt-dlp daemon started on port $parsedPort (startup=${startupMs}ms, handshake=${handshakeMs}ms)")
                        return@withContext StartDaemonResult.Ready(session, startupMs = startupMs, handshakeMs = handshakeMs)
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
                    detail = failDetail,
                    startupMs = startupMs + handshakeMs
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
                StartDaemonResult.Failed(
                    reason = WarmFailureReason.STARTUP_FAILED,
                    detail = msg,
                    startupMs = (monotonicNowMs() - launchStartedMono).coerceAtLeast(0L)
                )
            }
        }
    }

    /**
     * Executes inspection via warm loopback daemon and reports detailed mechanism vs extractor outcomes.
     * Supports streaming heartbeat/progress packets bounded by per-inactivity + overall monotonic deadlines,
     * and preserves coroutine cancellation cleanly without masking it as a transport failure or fallback.
     */
    suspend fun executeDetailed(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long,
        compactTranscript: Boolean = false
    ): WarmExecuteOutcome = withContext(Dispatchers.IO) {
        val startedMono = monotonicNowMs()
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

        ownedRequestIds.add(processId)
        try {
            concurrencySemaphore.withPermit {
                currentCoroutineContext().ensureActive()
                if (cancelledRequestIds.contains(processId)) {
                    throw CancellationException("Warm request $processId was cancelled prior to daemon start")
                }

                val healthyAtStart = isHealthy()
                var startupMs = 0L
                var handshakeMs = 0L
                val session = when (val startResult = startDaemon()) {
                    is StartDaemonResult.Ready -> {
                        startupMs = startResult.startupMs
                        handshakeMs = startResult.handshakeMs
                        startResult.session
                    }
                    is StartDaemonResult.Failed -> {
                        return@withContext WarmExecuteOutcome.MechanismFailure(
                            reason = startResult.reason,
                            detail = startResult.detail,
                            elapsedMs = (monotonicNowMs() - startedMono).coerceAtLeast(0L),
                            warmHealthyAtStart = healthyAtStart,
                            startupMs = startResult.startupMs
                        )
                    }
                }

                currentCoroutineContext().ensureActive()
                var socket: Socket? = null
                var cancelDisposable: kotlinx.coroutines.DisposableHandle? = null
                var heartbeatCount = 0
                var waitStartedMono = monotonicNowMs()

                try {
                    val sock = Socket()
                    socket = sock
                    activeRequests[processId] = ActiveRequestHandle(processId, sock, session)
                    cancelDisposable = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                        if (cause is CancellationException) {
                            cancel(processId)
                        }
                    }

                    if (cancelledRequestIds.contains(processId)) {
                        throw CancellationException("Warm request $processId was cancelled")
                    }

                    val overallBudgetMs = timeoutMs.coerceAtLeast(1000L)
                    val deadlineMono = monotonicNowMs() + overallBudgetMs
                    val effectiveInactivityMs = minOf(overallBudgetMs, inactivityTimeoutMs).coerceAtLeast(1000L)

                    sock.connect(InetSocketAddress("127.0.0.1", session.port), 2000)

                    val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), "UTF-8"), true)
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))

                    val reqObj = JSONObject().apply {
                        put("id", processId)
                        put("token", session.token)
                        put("action", "inspect")
                        put("url", url)
                        put("transcript_compact", compactTranscript)
                        val optsArr = JSONArray()
                        options.forEach { optsArr.put(it) }
                        put("options", optsArr)
                    }

                    waitStartedMono = monotonicNowMs()
                    writer.println(reqObj.toString())

                    var respObj: JSONObject
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelledRequestIds.contains(processId)) {
                            throw CancellationException("Warm request $processId was cancelled")
                        }
                        val remainingOverall = deadlineMono - monotonicNowMs()
                        if (remainingOverall <= 0L) {
                            throw OverallDeadlineTimeoutException(overallBudgetMs, heartbeatCount)
                        }
                        sock.soTimeout = minOf(effectiveInactivityMs, remainingOverall).coerceAtLeast(1L).toInt()

                        val packetLine = reader.readLine()
                            ?: throw EOFException("Premature EOF from warm daemon")
                        val packet = try {
                            JSONObject(packetLine)
                        } catch (je: Throwable) {
                            invalidateSessionIfSame(session, excludeProcessId = processId)
                            return@withContext WarmExecuteOutcome.MechanismFailure(
                                reason = WarmFailureReason.PROTOCOL_MISMATCH,
                                detail = "Warm daemon returned invalid JSON packet",
                                elapsedMs = (monotonicNowMs() - startedMono).coerceAtLeast(0L),
                                warmHealthyAtStart = healthyAtStart,
                                cause = je,
                                startupMs = startupMs,
                                handshakeMs = handshakeMs,
                                socketWaitMs = (monotonicNowMs() - waitStartedMono).coerceAtLeast(0L),
                                heartbeatCount = heartbeatCount
                            )
                        }

                        val pktId = packet.optString("id")
                        if (pktId.isNotBlank() && pktId != processId) {
                            invalidateSessionIfSame(session, excludeProcessId = processId)
                            return@withContext WarmExecuteOutcome.MechanismFailure(
                                reason = WarmFailureReason.PROTOCOL_MISMATCH,
                                detail = "Warm daemon returned mismatched response id (expected $processId, got $pktId)",
                                elapsedMs = (monotonicNowMs() - startedMono).coerceAtLeast(0L),
                                warmHealthyAtStart = healthyAtStart,
                                startupMs = startupMs,
                                handshakeMs = handshakeMs,
                                socketWaitMs = (monotonicNowMs() - waitStartedMono).coerceAtLeast(0L),
                                heartbeatCount = heartbeatCount
                            )
                        }

                        val pktType = packet.optString("type").lowercase()
                        if (pktType == "heartbeat" || pktType == "progress") {
                            heartbeatCount++
                            continue
                        }

                        respObj = packet
                        break
                    }

                    if (respObj.optBoolean("cancelled", false) && cancelledRequestIds.contains(processId)) {
                        throw CancellationException("Warm request $processId cancelled")
                    }

                    val nowMono = monotonicNowMs()
                    val elapsed = (nowMono - startedMono).coerceAtLeast(0L)
                    val socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L)
                    val extractMs = respObj.optLong("elapsed_ms", 0L).coerceAtLeast(0L)
                    val effectiveClients = jsonArrayToStringList(respObj.optJSONArray("effective_clients"))
                    val jsChallenges = respObj.optInt("js_challenges", 0).coerceAtLeast(0)
                    val debugLogs = jsonArrayToStringList(respObj.optJSONArray("debug_logs"))
                    val warn = respObj.optString("warnings").takeIf { it.isNotBlank() }
                    val success = respObj.optBoolean("success", false)
                    if (success) {
                        val out = respObj.optString("output")
                        if (out.isBlank() || out.trim() == "null") {
                            return@withContext WarmExecuteOutcome.ExtractorError(
                                message = "Warm extractor returned empty or null metadata",
                                warnings = warn,
                                elapsedMs = elapsed,
                                warmHealthyAtStart = healthyAtStart,
                                startupMs = startupMs,
                                handshakeMs = handshakeMs,
                                socketWaitMs = socketWaitMs,
                                extractMs = extractMs,
                                effectiveClients = effectiveClients,
                                jsChallengesSolved = jsChallenges,
                                heartbeatCount = heartbeatCount,
                                debugLogs = debugLogs
                            )
                        }
                        WarmExecuteOutcome.Success(
                            output = out,
                            warnings = warn,
                            elapsedMs = elapsed,
                            warmHealthyAtStart = healthyAtStart,
                            startupMs = startupMs,
                            handshakeMs = handshakeMs,
                            socketWaitMs = socketWaitMs,
                            extractMs = extractMs,
                            effectiveClients = effectiveClients,
                            jsChallengesSolved = jsChallenges,
                            heartbeatCount = heartbeatCount,
                            debugLogs = debugLogs
                        )
                    } else {
                        val error = respObj.optString("error", "Unknown extraction error")
                        WarmExecuteOutcome.ExtractorError(
                            message = error,
                            warnings = warn,
                            elapsedMs = elapsed,
                            warmHealthyAtStart = healthyAtStart,
                            startupMs = startupMs,
                            handshakeMs = handshakeMs,
                            socketWaitMs = socketWaitMs,
                            extractMs = extractMs,
                            effectiveClients = effectiveClients,
                            jsChallengesSolved = jsChallenges,
                            heartbeatCount = heartbeatCount,
                            debugLogs = debugLogs
                        )
                    }
                } catch (ce: CancellationException) {
                    cancel(processId)
                    throw ce
                } catch (ode: OverallDeadlineTimeoutException) {
                    currentCoroutineContext().ensureActive()
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.OVERALL_DEADLINE_TIMEOUT,
                        detail = ode.message ?: "Warm daemon exceeded overall deadline (${timeoutMs}ms)",
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = ode,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } catch (te: SocketTimeoutException) {
                    currentCoroutineContext().ensureActive()
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    val reason = if (heartbeatCount > 0 && (nowMono - waitStartedMono) >= timeoutMs.coerceAtLeast(1000L)) {
                        WarmFailureReason.OVERALL_DEADLINE_TIMEOUT
                    } else {
                        WarmFailureReason.SOCKET_TIMEOUT
                    }
                    WarmExecuteOutcome.MechanismFailure(
                        reason = reason,
                        detail = te.message ?: "Warm daemon read timed out after ${timeoutMs}ms",
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = te,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } catch (eof: EOFException) {
                    if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                        throw CancellationException("Warm request $processId interrupted by cancellation", eof)
                    }
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.PREMATURE_EOF,
                        detail = eof.message ?: "Warm daemon closed connection prematurely",
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = eof,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } catch (ceConn: ConnectException) {
                    currentCoroutineContext().ensureActive()
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.SOCKET_CONNECT_FAILED,
                        detail = ceConn.message ?: "Could not connect to warm daemon socket",
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = ceConn,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } catch (se: SocketException) {
                    if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                        throw CancellationException("Warm request socket closed due to cancellation", se)
                    }
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.PREMATURE_EOF,
                        detail = se.message ?: "Warm daemon socket error",
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = se,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } catch (t: Throwable) {
                    if (cancelledRequestIds.contains(processId) || !currentCoroutineContext().isActive) {
                        throw CancellationException("Warm request aborted due to cancellation", t)
                    }
                    invalidateSessionIfSame(session, excludeProcessId = processId)
                    val nowMono = monotonicNowMs()
                    WarmExecuteOutcome.MechanismFailure(
                        reason = WarmFailureReason.UNEXPECTED_ERROR,
                        detail = t.message ?: t.javaClass.simpleName,
                        elapsedMs = (nowMono - startedMono).coerceAtLeast(0L),
                        warmHealthyAtStart = healthyAtStart,
                        cause = t,
                        startupMs = startupMs,
                        handshakeMs = handshakeMs,
                        socketWaitMs = (nowMono - waitStartedMono).coerceAtLeast(0L),
                        heartbeatCount = heartbeatCount
                    )
                } finally {
                    cancelDisposable?.dispose()
                    activeRequests.remove(processId)
                    try {
                        socket?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        } finally {
            ownedRequestIds.remove(processId)
            cancelledRequestIds.remove(processId)
            pruneCancelledIdsIfNeeded()
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
                WarmFailureReason.SOCKET_TIMEOUT,
                WarmFailureReason.OVERALL_DEADLINE_TIMEOUT ->
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
        if (!ownedRequestIds.contains(processId) && !activeRequests.containsKey(processId)) {
            return
        }

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
            shouldDestroyDaemon = activeRequests.isEmpty() && removedHandle != null
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
        ownedRequestIds.clear()
        cancelledRequestIds.clear()
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
        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 15_000L

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
