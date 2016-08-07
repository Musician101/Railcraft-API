/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2016

 This work (the API) is licensed under the "MIT" License,
 see LICENSE.md for details.
 -----------------------------------------------------------------------------*/

package mods.railcraft.api.tracks;

import mods.railcraft.api.core.items.IToolCrowbar;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static net.minecraft.block.BlockRailBase.EnumRailDirection.*;

/**
 * All ITrackKits should extend this class. It contains a number of default
 * functions and standard behavior for Tracks that should greatly simplify
 * implementing new Track Kits when using this API.
 *
 * @author CovertJaguar <http://www.railcraft.info>
 * @see ITrackKit
 * @see TrackRegistry
 * @see TrackKitSpec
 */
public abstract class TrackKit implements ITrackKit {

    @Nonnull
    private TileEntity tileEntity = new TileEntity() {
    };

    private BlockRailBase getBlock() {
        return (BlockRailBase) getTile().getBlockType();
    }

    @Override
    public void setTile(TileEntity tileEntity) {
        this.tileEntity = tileEntity;
    }

    @Override
    public TileEntity getTile() {
        return tileEntity;
    }

    @Override
    public BlockRailBase.EnumRailDirection getRailDirection(IBlockState state, @Nullable EntityMinecart cart) {
        return getRailDirection(state);
    }

    protected final BlockRailBase.EnumRailDirection getRailDirection() {
        World world = theWorldAsserted();
        IBlockState state = world.getBlockState(getPos());
        return getRailDirection(state);
    }

    protected static BlockRailBase.EnumRailDirection getRailDirection(IBlockState state) {
        if (state.getBlock() instanceof BlockRailBase)
            return state.getValue(((BlockRailBase) state.getBlock()).getShapeProperty());
        return NORTH_SOUTH;
    }

    @Override
    public boolean blockActivated(EntityPlayer player, EnumHand hand, @Nullable ItemStack heldItem) {
        if (this instanceof ITrackKitReversible) {
            if (heldItem != null && heldItem.getItem() instanceof IToolCrowbar) {
                IToolCrowbar crowbar = (IToolCrowbar) heldItem.getItem();
                if (crowbar.canWhack(player, hand, heldItem, getPos())) {
                    ITrackKitReversible track = (ITrackKitReversible) this;
                    track.setReversed(!track.isReversed());
                    markBlockNeedsUpdate();
                    crowbar.onWhack(player, hand, heldItem, getPos());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onBlockPlacedBy(IBlockState state, @Nullable EntityLivingBase placer, ItemStack stack) {
        if (placer != null && this instanceof ITrackKitReversible) {
            int dir = MathHelper.floor_double((double) ((placer.rotationYaw * 4F) / 360F) + 0.5D) & 3;
            ((ITrackKitReversible) this).setReversed(dir == 0 || dir == 1);
        }
        switchTrack(state, true);
        testPower(state);
        markBlockNeedsUpdate();
    }

    public void sendUpdateToClient() {
        ((ITrackTile) getTile()).sendUpdateToClient();
    }

    public void markBlockNeedsUpdate() {
        World world = theWorldAsserted();
        IBlockState state = world.getBlockState(getTile().getPos());
        world.notifyBlockUpdate(getTile().getPos(), state, state, 3);
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean isRailValid(World world, BlockPos pos, BlockRailBase.EnumRailDirection dir) {
        boolean valid = true;
        if (!world.isSideSolid(pos.down(), EnumFacing.UP))
            valid = false;
        if (dir == ASCENDING_EAST && !world.isSideSolid(pos.east(), EnumFacing.UP))
            valid = false;
        else if (dir == ASCENDING_WEST && !world.isSideSolid(pos.west(), EnumFacing.UP))
            valid = false;
        else if (dir == ASCENDING_NORTH && !world.isSideSolid(pos.north(), EnumFacing.UP))
            valid = false;
        else if (dir == ASCENDING_SOUTH && !world.isSideSolid(pos.south(), EnumFacing.UP))
            valid = false;
        return valid;
    }

    @Override
    public void onNeighborBlockChange(IBlockState state, @Nullable Block neighborBlock) {
        World world = theWorldAsserted();
        boolean valid = isRailValid(world, getPos(), state.getValue(((BlockRailBase) state.getBlock()).getShapeProperty()));
        if (!valid) {
            Block blockTrack = getBlock();
            blockTrack.dropBlockAsItem(world, getPos(), state, 0);
            world.setBlockToAir(getPos());
            return;
        }

//        if (neighborBlock != null && neighborBlock.getDefaultState().canProvidePower()
//                && isFlexibleRail() && TrackToolsAPI.countAdjacentTracks(world, getPos()) == 3)
//            switchTrack(state, false);
        testPower(state);
    }

    private void switchTrack(IBlockState state, boolean flag) {
        World world = theWorldAsserted();
        BlockPos pos = getTile().getPos();
        BlockRailBase blockTrack = getBlock();
        blockTrack.new Rail(world, pos, state).place(world.isBlockPowered(pos), flag);
    }

    protected final void testPower(IBlockState state) {
        if (!(this instanceof ITrackKitPowered))
            return;
        World world = theWorldAsserted();
        ITrackKitPowered r = (ITrackKitPowered) this;
        boolean powered = world.isBlockIndirectlyGettingPowered(getPos()) > 0 || testPowerPropagation(world, getPos(), getTrackKitSpec(), state, r.getPowerPropagation());
        if (powered != r.isPowered()) {
            r.setPowered(powered);
            Block blockTrack = getBlock();
            world.notifyNeighborsOfStateChange(getPos(), blockTrack);
            world.notifyNeighborsOfStateChange(getPos().down(), blockTrack);
            BlockRailBase.EnumRailDirection railDirection = state.getValue(((BlockRailBase) state.getBlock()).getShapeProperty());
            if (railDirection.isAscending())
                world.notifyNeighborsOfStateChange(getPos().up(), blockTrack);
            sendUpdateToClient();
            // System.out.println("Setting power [" + i + ", " + j + ", " + k + "]");
        }
    }

    private boolean testPowerPropagation(World world, BlockPos pos, TrackKitSpec baseSpec, IBlockState state, int maxDist) {
        return isConnectedRailPowered(world, pos, baseSpec, state, true, 0, maxDist) || isConnectedRailPowered(world, pos, baseSpec, state, false, 0, maxDist);
    }

    private boolean isConnectedRailPowered(World world, BlockPos pos, TrackKitSpec baseSpec, IBlockState state, boolean dir, int dist, int maxDist) {
        if (dist >= maxDist)
            return false;
        boolean powered = true;
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        BlockRailBase.EnumRailDirection railDirection = state.getValue(getBlock().getShapeProperty());
        switch (railDirection) {
            case NORTH_SOUTH: // '\0'
                if (dir)
                    z++;
                else
                    z--;
                break;

            case EAST_WEST: // '\001'
                if (dir)
                    x--;
                else
                    x++;
                break;

            case ASCENDING_EAST: // '\002'
                if (dir)
                    x--;
                else {
                    x++;
                    y++;
                    powered = false;
                }
                railDirection = EAST_WEST;
                break;

            case ASCENDING_WEST: // '\003'
                if (dir) {
                    x--;
                    y++;
                    powered = false;
                } else
                    x++;
                railDirection = EAST_WEST;
                break;

            case ASCENDING_NORTH: // '\004'
                if (dir)
                    z++;
                else {
                    z--;
                    y++;
                    powered = false;
                }
                railDirection = NORTH_SOUTH;
                break;

            case ASCENDING_SOUTH: // '\005'
                if (dir) {
                    z++;
                    y++;
                    powered = false;
                } else
                    z--;
                railDirection = NORTH_SOUTH;
                break;
        }
        pos = new BlockPos(x, y, z);
        return testPowered(world, pos, baseSpec, dir, dist, maxDist, railDirection) || (powered && testPowered(world, pos.down(), baseSpec, dir, dist, maxDist, railDirection));
    }

    private boolean testPowered(World world, BlockPos nextPos, TrackKitSpec baseSpec, boolean dir, int dist, int maxDist, BlockRailBase.EnumRailDirection prevOrientation) {
        // System.out.println("Testing Power at <" + nextPos + ">");
        IBlockState nextBlockState = world.getBlockState(nextPos);
        if (nextBlockState.getBlock() == getBlock()) {
            BlockRailBase.EnumRailDirection nextOrientation = nextBlockState.getValue(((BlockRailBase) nextBlockState.getBlock()).getShapeProperty());
            TileEntity nextTile = world.getTileEntity(nextPos);
            if (nextTile instanceof ITrackTile) {
                ITrackKit nextTrack = ((ITrackTile) nextTile).getTrackKit();
                if (!(nextTrack instanceof ITrackKitPowered) || nextTrack.getTrackKitSpec() != baseSpec || !((ITrackKitPowered) this).canPropagatePowerTo(nextTrack))
                    return false;
                if (prevOrientation == EAST_WEST && (nextOrientation == NORTH_SOUTH || nextOrientation == ASCENDING_NORTH || nextOrientation == ASCENDING_SOUTH))
                    return false;
                if (prevOrientation == NORTH_SOUTH && (nextOrientation == EAST_WEST || nextOrientation == ASCENDING_EAST || nextOrientation == ASCENDING_WEST))
                    return false;
                if (((ITrackKitPowered) nextTrack).isPowered())
                    return world.isBlockPowered(nextPos) || world.isBlockPowered(nextPos.up()) || isConnectedRailPowered(world, nextPos, baseSpec, nextBlockState, dir, dist + 1, maxDist);
            }
        }
        return false;
    }

    /**
     * Be careful where you call this function from.
     * Only call it if you have a reasonable assumption that the world can't be null,
     * otherwise the game will crash.
     */

    public World theWorldAsserted() throws NullPointerException {
        World world = theWorld();
        assert world != null;
//        if (world == null) throw new NullPointerException("World was null");
        return world;
    }
}