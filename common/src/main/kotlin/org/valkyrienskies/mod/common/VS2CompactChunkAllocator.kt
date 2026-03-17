package org.valkyrienskies.mod.common

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import org.valkyrienskies.mod.util.logger

/**
 * A compact chunk allocator that assigns ship chunks to low coordinates to prevent
 * floating-point precision issues that occur when ships are stored at very high coordinates.
 *
 * Ships are allocated in a grid where each slot is [SLOT_SIZE_CHUNKS] × [SLOT_SIZE_CHUNKS]
 * chunks (i.e. [SLOT_SIZE_BLOCKS] × [SLOT_SIZE_BLOCKS] blocks). Slots are assigned in a
 * spiral order starting from (0, 0) to keep all coordinates small.
 *
 * At coordinates < 1,000,000 blocks, float32 precision is ≥ 0.0625 blocks, which avoids
 * the visible rendering gaps ("distance phenomenon") seen at coordinates > 10,000,000 blocks.
 */
object VS2CompactChunkAllocator {

    private val logger = logger("VS2CompactChunkAllocator").logger

    /** Each ship slot is this many chunks wide/deep (must match max ship size / 16). */
    const val SLOT_SIZE_CHUNKS = 16

    /** Each ship slot is this many blocks wide/deep (= SLOT_SIZE_CHUNKS * 16 = 256). */
    const val SLOT_SIZE_BLOCKS = SLOT_SIZE_CHUNKS * 16

    /**
     * Maximum slot index in each axis. 512 slots per axis = 512 × 256 = 131,072 blocks
     * maximum extent. At 131,072 blocks, float32 precision is ~0.0078 blocks, which is
     * far below the threshold for visible artifacts.
     */
    private const val MAX_SLOT_COORD = 512

    /** NBT key used for persistent storage. */
    private const val ALLOCATED_SLOTS_KEY = "vs2_compact_allocated_slots"

    // Set of allocated (slotX, slotZ) pairs, stored as interleaved ints for NBT serialization.
    private val allocatedSlots = mutableSetOf<Long>()

    /**
     * Encodes a (slotX, slotZ) pair as a single Long.
     * Uses upper 32 bits for slotX and lower 32 bits for slotZ.
     */
    private fun encodeSlot(slotX: Int, slotZ: Int): Long =
        (slotX.toLong() shl 32) or (slotZ.toLong() and 0xFFFFFFFFL)

    private fun decodeSlotX(encoded: Long): Int = (encoded ushr 32).toInt()
    private fun decodeSlotZ(encoded: Long): Int = encoded.toInt()

    /**
     * Allocates the next available compact ship slot and returns its (slotX, slotZ) index.
     * Slots are allocated in a spiral pattern starting from (0, 0) to keep coordinates
     * as small as possible.
     *
     * @return Pair(slotX, slotZ) identifying the allocated slot
     * @throws IllegalStateException if no slots are available (> MAX_SLOT_COORD²)
     */
    @Synchronized
    fun allocateSlot(): Pair<Int, Int> {
        // Walk the spiral until we find a free slot
        var r = 0
        var found: Pair<Int, Int>? = null

        outer@ while (r <= MAX_SLOT_COORD) {
            // Shell r of the spiral: ring of side (2r+1)
            if (r == 0) {
                val slot = Pair(0, 0)
                if (!allocatedSlots.contains(encodeSlot(0, 0))) {
                    found = slot
                    break@outer
                }
            } else {
                // Walk the four sides of the ring
                for (i in -r until r) {
                    val candidates = listOf(
                        Pair(i, -r), Pair(r, i), Pair(-i, r), Pair(-r, -i)
                    )
                    for (slot in candidates) {
                        if (!allocatedSlots.contains(encodeSlot(slot.first, slot.second))) {
                            found = slot
                            break@outer
                        }
                    }
                }
            }
            r++
        }

        if (found == null) {
            throw IllegalStateException(
                "VS2CompactChunkAllocator: all $MAX_SLOT_COORD × $MAX_SLOT_COORD slots are exhausted!"
            )
        }

        allocatedSlots.add(encodeSlot(found.first, found.second))
        logger.info(
            "VS2CompactChunkAllocator: allocated ship slot (${found.first}, ${found.second}) " +
                "= block coords (${found.first * SLOT_SIZE_BLOCKS}, ${found.second * SLOT_SIZE_BLOCKS})"
        )
        return found
    }

    /**
     * Releases a previously allocated slot so it can be reused.
     *
     * @param slotX The slot X index returned by [allocateSlot]
     * @param slotZ The slot Z index returned by [allocateSlot]
     */
    @Synchronized
    fun releaseSlot(slotX: Int, slotZ: Int) {
        if (allocatedSlots.remove(encodeSlot(slotX, slotZ))) {
            logger.debug("VS2CompactChunkAllocator: released ship slot ($slotX, $slotZ)")
        }
    }

    /**
     * Returns true if the given chunk coordinates (in the compact shipyard) fall within
     * any allocated ship slot.
     */
    @Synchronized
    fun isChunkInCompactShipyard(chunkX: Int, chunkZ: Int): Boolean {
        if (chunkX < 0 || chunkZ < 0) return false
        val slotX = chunkX / SLOT_SIZE_CHUNKS
        val slotZ = chunkZ / SLOT_SIZE_CHUNKS
        return allocatedSlots.contains(encodeSlot(slotX, slotZ))
    }

    /**
     * Converts a slot index to the centre block coordinates of that slot (in the shipyard
     * dimension).  The Y coordinate is set to 0; callers should adjust using the level's Y
     * range if needed.
     *
     * @param slotX The slot X index
     * @param slotZ The slot Z index
     * @return Triple(centreBlockX, 0, centreBlockZ)
     */
    fun slotToCentreBlock(slotX: Int, slotZ: Int): Triple<Int, Int, Int> {
        val blockX = slotX * SLOT_SIZE_BLOCKS + SLOT_SIZE_BLOCKS / 2
        val blockZ = slotZ * SLOT_SIZE_BLOCKS + SLOT_SIZE_BLOCKS / 2
        return Triple(blockX, 0, blockZ)
    }

    /**
     * Serialises the allocation state into an NBT [CompoundTag] for persistence.
     */
    @Synchronized
    fun save(): CompoundTag {
        val tag = CompoundTag()
        val slots = allocatedSlots.toList()
        val data = IntArray(slots.size * 2) { i ->
            if (i % 2 == 0) decodeSlotX(slots[i / 2]) else decodeSlotZ(slots[i / 2])
        }
        tag.put(ALLOCATED_SLOTS_KEY, IntArrayTag(data))
        return tag
    }

    /**
     * Restores the allocation state from a previously saved [CompoundTag].
     */
    @Synchronized
    fun load(tag: CompoundTag) {
        allocatedSlots.clear()
        val data = tag.getIntArray(ALLOCATED_SLOTS_KEY)
        var i = 0
        while (i + 1 < data.size) {
            allocatedSlots.add(encodeSlot(data[i], data[i + 1]))
            i += 2
        }
        logger.info("VS2CompactChunkAllocator: loaded ${allocatedSlots.size} allocated ship slots")
    }

    /**
     * Clears all allocated slots (used when a world is unloaded).
     */
    @Synchronized
    fun clear() {
        allocatedSlots.clear()
    }
}
