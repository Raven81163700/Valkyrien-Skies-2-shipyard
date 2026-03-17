package org.valkyrienskies.mod.common

object VS2ChunkAllocator {
    /**
     * Returns true if the given chunk coordinates are part of any shipyard area.
     *
     * This checks both the VS Core's default high-coordinate shipyard zone AND the
     * mod's own compact low-coordinate shipyard zone (used when
     * [org.valkyrienskies.mod.common.config.VSGameConfig.Server.useCompactShipyard] is enabled).
     */
    fun isChunkInShipyardCompanion(chunkX: Int, chunkZ: Int): Boolean {
        return vsCore.isChunkInShipyard(chunkX, chunkZ) ||
            VS2CompactChunkAllocator.isChunkInCompactShipyard(chunkX, chunkZ)
    }
}
