package ai.xyaa.assistant

import java.util.concurrent.atomic.AtomicLong

/** Lightweight timing counters for measuring first-audio latency and synthesis/playback gaps. */
class VoicePerformance {
    private val requestStarted = AtomicLong(0L)
    private val firstAudioAt = AtomicLong(0L)
    private val lastPlaybackEnd = AtomicLong(0L)
    private val totalGapMs = AtomicLong(0L)
    private val chunks = AtomicLong(0L)

    fun beginRequest() {
        requestStarted.set(System.nanoTime())
        firstAudioAt.set(0L)
        lastPlaybackEnd.set(0L)
        totalGapMs.set(0L)
        chunks.set(0L)
    }

    fun markFirstAudioReady(): Long {
        val now = System.nanoTime()
        firstAudioAt.compareAndSet(0L, now)
        return elapsedMs(requestStarted.get(), firstAudioAt.get())
    }

    fun markPlaybackStarted(): Long {
        val now = System.nanoTime()
        val previousEnd = lastPlaybackEnd.get()
        if (previousEnd > 0L && now > previousEnd) totalGapMs.addAndGet((now - previousEnd) / 1_000_000L)
        chunks.incrementAndGet()
        return elapsedMs(requestStarted.get(), now)
    }

    fun markPlaybackEnded() { lastPlaybackEnd.set(System.nanoTime()) }

    fun snapshot(): Snapshot = Snapshot(
        firstAudioMs = elapsedMs(requestStarted.get(), firstAudioAt.get()),
        accumulatedInterChunkGapMs = totalGapMs.get(),
        chunks = chunks.get()
    )

    data class Snapshot(val firstAudioMs: Long, val accumulatedInterChunkGapMs: Long, val chunks: Long)

    private fun elapsedMs(start: Long, end: Long): Long =
        if (start <= 0L || end <= 0L || end < start) -1L else (end - start) / 1_000_000L
}
