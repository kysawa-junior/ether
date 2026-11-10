package dev.instrum.ether.render.core

import dev.instrum.ether.render.core.error.RenderValidationException

/**
 * A generational handle table.
 *
 * Raw handles pack a 32-bit slot index in the low bits and a 32-bit generation in the high bits.
 * Freeing a slot increments its generation, so every handle issued for that slot becomes invalid
 * without the slot having to remain reserved. Handle value zero is never issued.
 *
 * Tables are single-threaded, matching the device owner model.
 */
internal class HandleTable(private val kindName: String) {
    private class Slot(
        var generation: Int = 1,
        var live: Boolean = false,
    )

    private val slots: MutableList<Slot> = ArrayList()
    private val freeSlots: MutableList<Int> = ArrayList()

    /** Number of currently live handles. */
    val liveCount: Int
        get() = slots.count { it.live }

    /** Allocates a new handle for a live resource. */
    fun allocate(): Long {
        val index = if (freeSlots.isEmpty()) {
            if (slots.size == Int.MAX_VALUE) {
                throw RenderValidationException("$kindName handle space is exhausted")
            }
            slots.add(Slot())
            slots.lastIndex
        } else {
            freeSlots.removeAt(freeSlots.lastIndex)
        }

        val slot = slots[index]
        check(!slot.live) { "Internal error: allocating a live $kindName slot" }
        slot.live = true
        return rawHandle(index, slot.generation)
    }

    /**
     * Frees [raw] if it is the current live handle for its slot.
     *
     * @return `true` when the handle was freed, `false` when it was stale or unknown.
     */
    fun free(raw: Long): Boolean {
        val index = slotIndex(raw)
        if (index !in slots.indices) return false
        val slot = slots[index]
        if (!slot.live || slot.generation != generation(raw)) return false
        slot.live = false
        slot.generation = nextGeneration(slot.generation)
        freeSlots.add(index)
        return true
    }

    /** Whether [raw] identifies the current live handle of its slot. */
    fun isLive(raw: Long): Boolean {
        val index = slotIndex(raw)
        if (index !in slots.indices) return false
        val slot = slots[index]
        return slot.live && slot.generation == generation(raw)
    }

    private companion object {
        fun rawHandle(index: Int, generation: Int): Long =
            (generation.toLong() shl 32) or (index.toLong() and 0xFFFF_FFFFL)

        fun slotIndex(raw: Long): Int = (raw and 0xFFFF_FFFFL).toInt()

        fun generation(raw: Long): Int = (raw ushr 32).toInt()

        fun nextGeneration(current: Int): Int =
            if (current == Int.MAX_VALUE) 1 else current + 1
    }
}
