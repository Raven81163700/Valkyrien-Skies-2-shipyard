package org.valkyrienskies.mod.common.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Rotation.NONE
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.internal.ships.VsiServerShip
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.toBlockPos
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.world.ShipyardDimension
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.mod.util.relocateBlock
import org.valkyrienskies.mod.util.relocateBlockToDimension
import java.util.function.DoubleSupplier

class ShipCreatorItem(
    properties: Properties, private val scale: DoubleSupplier, private val minScaling: DoubleSupplier
) : Item(properties) {

    override fun isFoil(stack: ItemStack): Boolean {
        return true
    }

    @OptIn(VsBeta::class)
    override fun useOn(ctx: UseOnContext): InteractionResult {
        val level = ctx.level as? ServerLevel ?: return super.useOn(ctx)
        val blockPos = ctx.clickedPos
        val blockState: BlockState = level.getBlockState(blockPos)

        if (!level.isClientSide) {
            val parentShip = ctx.level.getShipManagingPos(blockPos)
            if (!blockState.isAir) {
                // Make a ship — store it in the dedicated shipyard dimension if enabled.
                // This keeps shipyard block coordinates small, avoiding float32 precision
                // loss in rendering ("distance phenomenon") at high coordinates.
                val dimensionId = if (VSGameConfig.SERVER.useShipyardDimension) {
                    ShipyardDimension.getDimensionId(level.server) ?: level.dimensionId
                } else {
                    level.dimensionId
                }

                val scale = scale.asDouble
                val minScaling = minScaling.asDouble

                val serverShip =
                    level.shipObjectWorld.createNewShipAtBlock(blockPos.toJOML(), false, scale, dimensionId)

                // Use the yRange of the dimension where the ship blocks actually live
                val shipLevel = if (VSGameConfig.SERVER.useShipyardDimension) {
                    ShipyardDimension.getLevel(level.server) ?: level
                } else {
                    level
                }
                val centerPos = serverShip.chunkClaim.getCenterBlockCoordinates(shipLevel.yRange).toBlockPos()

                // Move the block from the world to a ship.
                // When using the dedicated shipyard dimension, the block must be placed
                // in that dimension rather than the player's current dimension.
                if (shipLevel === level) {
                    level.relocateBlock(blockPos, centerPos, true, serverShip, NONE)
                } else {
                    level.relocateBlockToDimension(blockPos, shipLevel, centerPos, true, serverShip, NONE)
                }

                ctx.player?.sendSystemMessage(Component.translatable("command.valkyrienskies.shipify.success_one", serverShip.slug))
                if (parentShip != null) {
                    // Compute the ship transform
                    val newShipPosInWorld =
                        parentShip.shipToWorld.transformPosition(blockPos.toJOMLD().add(0.5, 0.5, 0.5))
                    val newShipPosInShipyard = blockPos.toJOMLD().add(0.5, 0.5, 0.5)
                    val newShipRotation = parentShip.transform.shipToWorldRotation
                    var newShipScaling = parentShip.transform.shipToWorldScaling.mul(scale, Vector3d())
                    if (newShipScaling.x() < minScaling) {
                        // Do not allow scaling to go below minScaling
                        newShipScaling = Vector3d(minScaling, minScaling, minScaling)
                    }


                    val newTransform = vsCore.newBodyTransform(
                        newShipPosInWorld,
                        newShipRotation,
                        newShipScaling,
                        newShipPosInShipyard,
                    )
                    (serverShip as VsiServerShip).unsafeSetTransform(newTransform)
                }
            }
        }

        return super.useOn(ctx)
    }
}
