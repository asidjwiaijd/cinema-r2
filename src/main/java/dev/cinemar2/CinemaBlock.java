package dev.cinemar2;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public final class CinemaBlock extends BlockWithEntity {
    public static final MapCodec<CinemaBlock> CODEC = createCodec(CinemaBlock::new);
    public CinemaBlock(Settings settings) { super(settings); setDefaultState(getStateManager().getDefaultState().with(Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.NORTH)); }
    protected MapCodec<? extends BlockWithEntity> getCodec() { return CODEC; }
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(Properties.HORIZONTAL_FACING); }
    public BlockState getPlacementState(ItemPlacementContext context) { return getDefaultState().with(Properties.HORIZONTAL_FACING, context.getHorizontalPlayerFacing().getOpposite()); }
    protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new CinemaEntity(pos, state); }
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient && placer instanceof PlayerEntity player && world.getBlockEntity(pos) instanceof CinemaEntity screen) { screen.owner = player.getUuid(); screen.markDirty(); }
    }
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (player instanceof ServerPlayerEntity sp && world.getBlockEntity(pos) instanceof CinemaEntity screen) screen.send(sp, false, "");
        return ActionResult.SUCCESS;
    }
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, CinemaMod.ENTITY, CinemaEntity::tick);
    }
}
