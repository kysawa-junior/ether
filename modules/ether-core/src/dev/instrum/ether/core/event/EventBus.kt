package dev.instrum.ether.core.event

import kotlin.reflect.KClass

/**
 * The base marker interface for engine events.
 *
 * Events are immutable data objects dispatched through an [EventBus]. They carry no behaviour
 * beyond conveying information.
 */
public interface Event

/**
 * A subscription to an event type that can be cancelled.
 */
public interface EventSubscription {
    /** Whether this subscription is still active. */
    public val isActive: Boolean

    /** Cancels this subscription so that it no longer receives events. */
    public fun cancel()
}

/**
 * A synchronous, type-keyed event dispatcher.
 *
 * Listeners are invoked in registration order on the thread that publishes the event. Publishing
 * from within a listener is supported; the re-entrant event is delivered to the listeners that are
 * registered at the time of the nested publish.
 *
 * The bus is not synchronized. Each instance is intended to be owned by a single thread, matching
 * the engine's main-thread update model.
 */
public class EventBus {
    private val listeners = mutableMapOf<KClass<out Event>, MutableList<Registration>>()

    /**
     * Registers [listener] for events of type [eventType].
     *
     * @param eventType the class of events to receive; matching is by exact runtime class.
     * @param listener the callback invoked with each matching event.
     * @return a subscription that cancels this registration.
     */
    public fun <T : Event> subscribe(
        eventType: KClass<T>,
        listener: (T) -> Unit,
    ): EventSubscription {
        val registration = Registration(eventType) { event ->
            @Suppress("UNCHECKED_CAST")
            listener(event as T)
        }
        listeners.getOrPut(eventType) { mutableListOf() }.add(registration)
        return registration
    }

    /**
     * Dispatches [event] to every listener registered for its exact runtime class.
     *
     * @return the number of listeners that were invoked.
     */
    public fun publish(event: Event): Int {
        val registration = listeners[event::class] ?: return 0
        var delivered = 0
        // Iterate over a snapshot so that a listener cancelling itself does not disturb the loop.
        val snapshot = registration.toList()
        for (entry in snapshot) {
            if (!entry.isActive) continue
            entry.listener(event)
            delivered++
        }
        return delivered
    }

    /** Removes every listener registration. */
    public fun clear() {
        listeners.clear()
    }

    private class Registration(
        val eventType: KClass<out Event>,
        val listener: (Event) -> Unit,
    ) : EventSubscription {
        private var active = true

        override val isActive: Boolean
            get() = active

        override fun cancel() {
            active = false
        }
    }
}
