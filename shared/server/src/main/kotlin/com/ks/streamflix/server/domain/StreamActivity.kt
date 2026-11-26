package com.ks.streamflix.server.domain

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether someone is watching from this server: a stream answer is being sent, or one ended less
 * than [graceMillis] ago (players fetch in bursts, with pauses while their buffer is full). The
 * desktop app keeps the computer awake while this is true (specs/plan-per-platform.md).
 */
class StreamActivity(
    private val graceMillis: Long = 3 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val inFlight = AtomicInteger()
    private val lastEnd = AtomicLong(Long.MIN_VALUE / 2)

    /** Runs [block] (sending a stream answer) counted as streaming. */
    suspend fun <T> serving(block: suspend () -> T): T {
        inFlight.incrementAndGet()
        try {
            return block()
        } finally {
            lastEnd.set(clock())
            inFlight.decrementAndGet()
        }
    }

    val isActive: Boolean get() = inFlight.get() > 0 || clock() - lastEnd.get() < graceMillis
}
