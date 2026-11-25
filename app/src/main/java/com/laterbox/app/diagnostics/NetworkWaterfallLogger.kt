package com.laterbox.app.diagnostics

import android.util.Log
import androidx.tracing.Trace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight wall-clock waterfall tracer for Movies detail opens and backend call groups.
 * Never logs raw URLs, headers, tokens, or credentials.
 */
object NetworkWaterfallLogger {
    private const val TAG = "LaterboxWaterfall"
    private const val MAX_TRACES = 20

    fun nowWallMs(): Long = System.nanoTime() / 1_000_000L

    enum class CacheStatus {
        MEMORY_HIT,
        ROOM_HIT,
        HTTP_CACHE_HIT,
        NETWORK,
        NATIVE_DIRECT,
        FAILED
    }

    data class SpanRecord(
        val callGroup: String,
        val startOffsetMs: Long,
        val durationMs: Long,
        val cacheStatus: CacheStatus,
        val payloadBytes: Int,
        val blockingFirstPaint: Boolean,
        val firedBeforeNeeded: Boolean = false
    )

    data class DetailTraceSession(
        val traceId: Int,
        val titleKey: String,
        val startedElapsedMs: Long,
        @Volatile var ttidMs: Long? = null,
        @Volatile var ttfdMs: Long? = null,
        val spans: CopyOnWriteArrayList<SpanRecord> = CopyOnWriteArrayList()
    )

    private val traceCounter = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<Int, DetailTraceSession>()
    private val completedSessions = CopyOnWriteArrayList<DetailTraceSession>()

    @Volatile
    private var activeDetailTraceId: Int? = null

    fun beginDetailOpen(titleKey: String): Int {
        val traceId = traceCounter.getAndIncrement()
        val now = nowWallMs()
        val session = DetailTraceSession(
            traceId = traceId,
            titleKey = titleKey,
            startedElapsedMs = now
        )
        sessions[traceId] = session
        activeDetailTraceId = traceId
        runCatching {
            Trace.beginAsyncSection("LaterboxDetailTTID", traceId)
            Trace.beginAsyncSection("LaterboxDetailTTFD", traceId)
        }
        return traceId
    }

    fun markDetailFirstPaint(traceId: Int) {
        val session = sessions[traceId] ?: return
        if (session.ttidMs == null) {
            session.ttidMs = (nowWallMs() - session.startedElapsedMs).coerceAtLeast(0L)
            runCatching { Trace.endAsyncSection("LaterboxDetailTTID", traceId) }
        }
    }

    fun markDetailFullDisplay(traceId: Int) {
        val session = sessions[traceId] ?: return
        val elapsed = (nowWallMs() - session.startedElapsedMs).coerceAtLeast(0L)
        if (session.ttidMs == null) {
            session.ttidMs = elapsed
            runCatching { Trace.endAsyncSection("LaterboxDetailTTID", traceId) }
        }
        if (session.ttfdMs == null) {
            session.ttfdMs = elapsed
            runCatching { Trace.endAsyncSection("LaterboxDetailTTFD", traceId) }
            completedSessions.add(session)
            while (completedSessions.size > MAX_TRACES) {
                completedSessions.removeAt(0)
            }
            logSessionSummary(session)
        }
    }

    fun recordCall(
        callGroup: String,
        startElapsedMs: Long,
        durationMs: Long,
        cacheStatus: CacheStatus,
        payloadBytes: Int = 0,
        blockingFirstPaint: Boolean = false,
        firedBeforeNeeded: Boolean = false,
        explicitTraceId: Int? = activeDetailTraceId
    ) {
        val traceId = explicitTraceId ?: return
        val session = sessions[traceId] ?: return
        val offset = (startElapsedMs - session.startedElapsedMs).coerceAtLeast(0L)
        session.spans.add(
            SpanRecord(
                callGroup = callGroup,
                startOffsetMs = offset,
                durationMs = durationMs.coerceAtLeast(0L),
                cacheStatus = cacheStatus,
                payloadBytes = payloadBytes.coerceAtLeast(0),
                blockingFirstPaint = blockingFirstPaint,
                firedBeforeNeeded = firedBeforeNeeded
            )
        )
    }

    fun latestCompletedSessions(): List<DetailTraceSession> = completedSessions.toList()

    fun formatWaterfallReport(session: DetailTraceSession): String = buildString {
        append("trace=").append(session.traceId)
            .append(" ttidMs=").append(session.ttidMs ?: -1L)
            .append(" ttfdMs=").append(session.ttfdMs ?: -1L)
            .append(" spans=[")
        session.spans.forEachIndexed { idx, s ->
            if (idx > 0) append("; ")
            append(s.callGroup)
                .append("@+").append(s.startOffsetMs).append("ms")
                .append(" dur=").append(s.durationMs).append("ms")
                .append(" src=").append(s.cacheStatus.name)
                .append(" bytes=").append(s.payloadBytes)
                .append(" blockPaint=").append(s.blockingFirstPaint)
                .append(" early=").append(s.firedBeforeNeeded)
        }
        append("]")
    }

    fun resetForTest() {
        sessions.clear()
        completedSessions.clear()
        activeDetailTraceId = null
    }

    private fun logSessionSummary(session: DetailTraceSession) {
        runCatching { Log.i(TAG, formatWaterfallReport(session)) }
    }
}
