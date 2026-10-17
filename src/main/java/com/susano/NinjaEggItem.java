package com.susano;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;

import java.util.List;

/** Oeuf de ninja : clic droit sur un bloc = 3 ninjas armes ; accroupi = un chef. Clic droit en l'air = embuscade autour de toi. */
public class NinjaEggItem extends Item {
    public NinjaEggItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        PlayerEntity user = ctx.getPlayer();
        if (world.isClient || !(user instanceof ServerPlayerEntity sp)) {
            return ActionResult.SUCCESS;
        }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            sp.sendMessage(Text.translatable("message.susanoo.peaceful"), true);
            return ActionResult.FAIL;
        }
        BlockPos base = ctx.getBlockPos().offset(ctx.getSide());
        int made = AmbushManager.spawnAt(sp, base, sp.isSneaking());
        if (made > 0 && !sp.isCreative()) {
            ctx.getStack().decrement(1);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            if (world.getDifficulty() == Difficulty.PEACEFUL) {
                sp.sendMessage(Text.translatable("message.susanoo.peaceful"), true);
                return TypedActionResult.fail(stack);
            }
            AmbushManager.spawn(sp);
            if (!sp.isCreative()) {
                stack.decrement(1);
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.susanoo.ninja_egg.1"));
        tooltip.add(Text.translatable("tooltip.susanoo.ninja_egg.2"));
    }
}
