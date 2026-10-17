package com.susano;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Le Rinnegan : donne les six voies de Pain. Les pouvoirs s'utilisent depuis la barre de pouvoirs (touche Y). */
public class RinneganItem extends Item {
    public RinneganItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            sp.sendMessage(net.minecraft.text.Text.translatable("message.susanoo.bar_hint"), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }
}
