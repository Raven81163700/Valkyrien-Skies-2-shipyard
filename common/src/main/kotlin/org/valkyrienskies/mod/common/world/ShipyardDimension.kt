package org.valkyrienskies.mod.common.world

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.dimensionId

/**
 * Manages the dedicated "shipyard" dimension (`valkyrienskies:shipyard`) used to store
 * ship block data at compact, low coordinates.
 *
 * Background
 * ----------
 * By default VS ships are stored in the same dimension as the player but at very high
 * X/Z coordinates (≈ 28 000 000 blocks from origin).  At those distances the 32-bit
 * floating-point precision of Minecraft's GPU rendering pipeline drops to roughly
 * ±2 blocks, which causes visible gaps between adjacent blocks ("distance phenomenon").
 *
 * Placing ships in a dedicated void dimension and using a compact grid allocator
 * ([org.valkyrienskies.mod.common.VS2CompactChunkAllocator]) keeps all shipyard block
 * coordinates below ~131 000 blocks from origin, where float32 precision is better
 * than 0.01 blocks — well below the threshold for visible artefacts.
 *
 * Dimension setup
 * ---------------
 * The dimension is defined in the mod's data-pack at:
 *   `data/valkyrienskies/dimension/shipyard.json`
 *   `data/valkyrienskies/dimension_type/shipyard.json`
 *
 * It uses a flat (void) world generator so no terrain is generated.
 * Ships are loaded and unloaded dynamically by VS Core's chunk-management system.
 */
object ShipyardDimension {

    /** The resource-key for the dedicated shipyard dimension. */
    @JvmField
    val DIMENSION_KEY: ResourceKey<Level> = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation("valkyrienskies", "shipyard")
    )

    /**
     * The [DimensionId] string used by VS Core to identify the shipyard dimension.
     * This matches the format `"<registryNamespace>:<registryName>:<namespace>:<name>"`.
     *
     * The value is only available once the server has started and the level exists.
     * Returns `null` if the dimension does not exist in the current server (e.g. the
     * feature is disabled, or the dimension hasn't been registered yet).
     */
    @JvmStatic
    fun getDimensionId(server: MinecraftServer): DimensionId? =
        server.getLevel(DIMENSION_KEY)?.dimensionId

    /**
     * Returns the [Level] for the shipyard dimension, or `null` if it is not loaded.
     */
    @JvmStatic
    fun getLevel(server: MinecraftServer): net.minecraft.server.level.ServerLevel? =
        server.getLevel(DIMENSION_KEY)

    /**
     * Returns true if the shipyard dimension level is currently loaded on [server].
     */
    @JvmStatic
    fun isAvailable(server: MinecraftServer): Boolean =
        server.getLevel(DIMENSION_KEY) != null
}
