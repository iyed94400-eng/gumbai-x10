package com.susano;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.EnumMap;
import java.util.Map;

public class SusanooMod implements ModInitializer {
    public static final String MOD_ID = "susanoo";
    /** Paquet client -> serveur : un entier d'action. */
    public static final Identifier ACTION = new Identifier(MOD_ID, "action");
    /** Paquet serveur -> client : (voie, recharge en ticks) pour la barre de pouvoirs. */
    public static final Identifier RIN_CD = new Identifier(MOD_ID, "rin_cd");
    public static final int ACT_TOGGLE = 0, ACT_FORM_UP = 1, ACT_FORM_DOWN = 2, ACT_ATTACK = 3, ACT_SPECIAL = 4,
            ACT_STOMP = 5, ACT_KICK = 6, ACT_METEOR = 7, ACT_AMATERASU = 8, ACT_RIN_USE = 9, ACT_RIN_NEXT = 10, ACT_RIN_PREV = 11, ACT_RIN_USE_AT = 12;

    public static EntityType<SusanooEntity> SUSANOO;
    public static EntityType<MeteorEntity> METEOR;
    public static Item RINNEGAN;
    public static Item GUNBAI;
    public static Item NINJA_EGG;
    public static final Map<SusanooColor, Item> ITEMS = new EnumMap<>(SusanooColor.class);

    @Override
    public void onInitialize() {
        SUSANOO = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "susanoo"),
                EntityType.Builder.<SusanooEntity>create(SusanooEntity::new, SpawnGroup.MISC)
                        .setDimensions(0.6f, 1.8f)
                        .maxTrackingRange(12)
                        .trackingTickInterval(1)
                        .makeFireImmune()
                        .build("susanoo"));

        METEOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "meteor"),
                EntityType.Builder.<MeteorEntity>create(MeteorEntity::new, SpawnGroup.MISC)
                        .setDimensions(1.0f, 1.0f)
                        .maxTrackingRange(32)
                        .trackingTickInterval(1)
                        .makeFireImmune()
                        .build("meteor"));

        for (SusanooColor c : SusanooColor.values()) {
            Item item = Registry.register(Registries.ITEM, new Identifier(MOD_ID, c.itemName),
                    new SusanooItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), c));
            ITEMS.put(c, item);
        }
        RINNEGAN = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "rinnegan"),
                new RinneganItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));
        GUNBAI = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "gunbai"),
                new GunbaiItem(new Item.Settings().rarity(Rarity.EPIC).fireproof()));
        NINJA_EGG = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "ninja_egg"),
                new NinjaEggItem(new Item.Settings().maxCount(16).rarity(Rarity.UNCOMMON)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            ITEMS.values().forEach(entries::add);
            entries.add(RINNEGAN);
            entries.add(GUNBAI);
            entries.add(NINJA_EGG);
        });

        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, sender) -> {
            int action = buf.readVarInt();
            int arg = buf.isReadable() ? buf.readVarInt() : 0;
            server.execute(() -> handle(player, action, arg));
        });
        ServerTickEvents.END_SERVER_TICK.register(SusanooManager::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p)
                        || (RinneganManager.pretaAbsorb(p, source, amount) && SusanooManager.absorb(p, source, amount)));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p) || RinneganManager.allowDeath(p));
        ServerTickEvents.END_SERVER_TICK.register(RinneganManager::tick);
        ServerTickEvents.END_SERVER_TICK.register(AmbushManager::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("susanoo_ambush")
                        .then(CommandManager.literal("on").executes(ctx -> {
                            AmbushManager.enabled = true;
                            ctx.getSource().sendFeedback(() -> Text.translatable("message.susanoo.ambush_on"), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            AmbushManager.enabled = false;
                            ctx.getSource().sendFeedback(() -> Text.translatable("message.susanoo.ambush_off"), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("now").executes(ctx -> {
                            AmbushManager.spawn(ctx.getSource().getPlayerOrThrow());
                            return 1;
                        }))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SusanooManager.dismiss(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SusanooManager.clear(server);
            RinneganManager.clear();
            AmbushManager.clear();
        });
    }

    private static void handle(ServerPlayerEntity p, int action, int arg) {
        switch (action) {
            case ACT_TOGGLE -> SusanooManager.toggleKey(p);
            case ACT_FORM_UP -> SusanooManager.changeForm(p, 1);
            case ACT_FORM_DOWN -> SusanooManager.changeForm(p, -1);
            case ACT_ATTACK -> SusanooManager.attack(p);
            case ACT_SPECIAL -> SusanooManager.special(p);
            case ACT_STOMP -> SusanooManager.legAttack(p, SusanooEntity.KIND_STOMP);
            case ACT_KICK -> SusanooManager.legAttack(p, SusanooEntity.KIND_KICK);
            case ACT_METEOR -> SusanooManager.meteor(p);
            case ACT_AMATERASU -> SusanooManager.amaterasu(p);
            case ACT_RIN_USE -> RinneganManager.use(p);
            case ACT_RIN_NEXT -> RinneganManager.cycle(p, 1);
            case ACT_RIN_PREV -> RinneganManager.cycle(p, -1);
            case ACT_RIN_USE_AT -> RinneganManager.useAt(p, arg);
            default -> { }
        }
    }
}
