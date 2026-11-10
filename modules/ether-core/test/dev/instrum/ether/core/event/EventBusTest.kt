package dev.instrum.ether.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventBusTest {

    private class Ping : Event
    private class Pong : Event

    @Test
    fun publishDeliversToListenerOfExactType() {
        val bus = EventBus()
        var received = 0
        bus.subscribe(Ping::class) { received++ }
        bus.publish(Ping())
        assertEquals(1, received)
    }

    @Test
    fun publishDoesNotDeliverToUnrelatedTypes() {
        val bus = EventBus()
        var pings = 0
        var pongs = 0
        bus.subscribe(Ping::class) { pings++ }
        bus.subscribe(Pong::class) { pongs++ }

        bus.publish(Ping())

        assertEquals(1, pings)
        assertEquals(0, pongs)
    }

    @Test
    fun publishReturnsNumberOfDeliveredListeners() {
        val bus = EventBus()
        bus.subscribe(Ping::class) { }
        bus.subscribe(Ping::class) { }
        assertEquals(2, bus.publish(Ping()))
        assertEquals(0, bus.publish(Pong()))
    }

    @Test
    fun listenersAreInvokedInRegistrationOrder() {
        val bus = EventBus()
        val order = mutableListOf<Int>()
        bus.subscribe(Ping::class) { order += 1 }
        bus.subscribe(Ping::class) { order += 2 }
        bus.subscribe(Ping::class) { order += 3 }

        bus.publish(Ping())

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun cancelledSubscriptionStopsReceivingEvents() {
        val bus = EventBus()
        var received = 0
        val subscription = bus.subscribe(Ping::class) { received++ }

        bus.publish(Ping())
        subscription.cancel()
        bus.publish(Ping())

        assertEquals(1, received)
        assertFalse(subscription.isActive)
    }

    @Test
    fun cancellationBeforeDispatchSkipsInactiveListener() {
        val bus = EventBus()
        var first = 0
        var second = 0
        val secondSubscription = bus.subscribe(Ping::class) { second++ }
        bus.subscribe(Ping::class) { first++ }
        secondSubscription.cancel()

        val delivered = bus.publish(Ping())

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(1, delivered)
    }

    @Test
    fun cancellingDuringDispatchDoesNotDisturbIteration() {
        val bus = EventBus()
        val received = mutableListOf<String>()
        lateinit var subscription: EventSubscription
        subscription = bus.subscribe(Ping::class) {
            received += "first"
            subscription.cancel()
        }
        bus.subscribe(Ping::class) { received += "second" }

        bus.publish(Ping())

        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun nestedPublishIsDeliveredSynchronously() {
        val bus = EventBus()
        val received = mutableListOf<String>()
        bus.subscribe(Ping::class) {
            received += "ping"
            bus.publish(Pong())
        }
        bus.subscribe(Pong::class) { received += "pong" }

        bus.publish(Ping())

        assertEquals(listOf("ping", "pong"), received)
    }

    @Test
    fun clearRemovesEveryListener() {
        val bus = EventBus()
        var received = 0
        bus.subscribe(Ping::class) { received++ }
        bus.clear()
        assertEquals(0, bus.publish(Ping()))
        assertEquals(0, received)
    }

    @Test
    fun subscriptionIsActiveUntilCancelled() {
        val bus = EventBus()
        val subscription = bus.subscribe(Ping::class) { }
        assertTrue(subscription.isActive)
        subscription.cancel()
        assertFalse(subscription.isActive)
    }

    @Test
    fun cancellingTwiceIsIdempotent() {
        val bus = EventBus()
        val subscription = bus.subscribe(Ping::class) { }
        subscription.cancel()
        subscription.cancel()
        assertFalse(subscription.isActive)
    }
}
