package com.ivanmalison.wearupdater

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PackageInstaller reports outcomes through a broadcast, so the in-process UI needs somewhere to
 * pick them up. [InstallResultReceiver] publishes here and the activity watches for the package
 * it is currently installing.
 */
object InstallEvents {
    data class Event(
        val packageName: String,
        val label: String,
        val outcome: Outcome,
        val message: String?,
    )

    enum class Outcome { PENDING_USER_ACTION, SUCCESS, FAILURE }

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    /** Returns true when at least one listener saw the event, so the receiver can skip a toast. */
    fun publish(event: Event): Boolean {
        listeners.forEach { it(event) }
        return listeners.isNotEmpty()
    }

    /** Start watching before committing a session, otherwise a fast result can be missed. */
    fun watch(packageName: String): Watch = Watch(packageName)

    class Watch internal constructor(private val packageName: String) : Closeable {
        private val events = LinkedBlockingQueue<Event>()
        private val listener: (Event) -> Unit = { if (it.packageName == packageName) events.put(it) }

        init {
            listeners.add(listener)
        }

        /**
         * Waits for a terminal outcome, reporting intermediate ones through [onUpdate]. The
         * timeout restarts after each intermediate event, since a confirmation prompt means the
         * user is being asked something rather than the install having stalled.
         */
        fun awaitOutcome(timeoutMillis: Long, onUpdate: (Event) -> Unit = {}): Event? {
            var deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                if (event.outcome != Outcome.PENDING_USER_ACTION) return event
                onUpdate(event)
                deadline = System.currentTimeMillis() + timeoutMillis
            }
        }

        override fun close() {
            listeners.remove(listener)
        }
    }
}
