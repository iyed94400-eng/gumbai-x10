package com.susano;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Embuscades : des ninjas deserteurs armes apparaissent au hasard autour des joueurs qui ont des pouvoirs
 * (Mangekyo, Rinnegan ou Susanoo) et les attaquent. Desactivable : /susanoo_ambush off.
 */
public final class AmbushManager {
    public static boolean enabled = true;

    /** Delai minimum entre deux embuscades pour un joueur (ticks). */
    private static final long MIN_GAP = 3600;
    /** Une fois le delai passe : en moyenne une embuscade toutes les N secondes. */
    private static final int AVG_SECONDS = 150;
    private static final int MAX_ALIVE = 14;
    private static final long LIFETIME = 6000;

    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static final List<Enemy> ENEMIES = new ArrayList<>();

    private record Enemy(MobEntity mob, long until) { }

    private AmbushManager() { }

    public static void clear() {
        ENEMIES.clear();
        LAST.clear();
    }

    public static void tick(MinecraftServer server) {
        Iterator<Enemy> it = ENEMIES.iterator();
        while (it.hasNext()) {
            Enemy e = it.next();
            if (e.mob().isRemoved() || !e.mob().isAlive()) {
                it.remove();
            } else if (e.mob().getWorld().getTime() >= e.until()) {
                ServerWorld w = (ServerWorld) e.mob().getWorld();
                w.spawnParticles(ParticleTypes.POOF, e.mob().getX(), e.mob().getY() + 1, e.mob().getZ(), 15, 0.4, 0.6, 0.4, 0.05);
                e.mob().discard();
                it.remove();
            }
        }
        if (!enabled || server.getTicks() % 20 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!eligible(p)) continue;
            long now = p.getServerWorld().getTime();
            Long last = LAST.putIfAbsent(p.getUuid(), now);
            if (last == null) last = now;
            if (now - last < MIN_GAP || ENEMIES.size() >= MAX_ALIVE) continue;
            if (p.getServerWorld().random.nextInt(AVG_SECONDS) != 0) continue;
            spawn(p);
        }
    }

    private static boolean eligible(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        if (!p.isAlive() || p.isCreative() || p.isSpectator()) return false;
        if (w.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (!w.getDimension().hasSkyLight() || w.getDimension().hasCeiling()) return false;
        if (SusanooManager.get(p) != null) return true;
        for (ItemStack s : p.getInventory().main) {
            Item it = s.getItem();
            if (it == SusanooMod.RINNEGAN || SusanooMod.ITEMS.containsValue(it)) return true;
        }
        return false;
    }

    /** Fait apparaitre une bande de ninjas armes autour du joueur (aussi utilise par /susanoo_ambush now). */
    public static void spawn(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        LAST.put(p.getUuid(), w.getTime());
        int count = 3 + w.random.nextInt(3) + (w.getDifficulty() == Difficulty.HARD ? 1 : 0);
        int made = 0;
        for (int i = 0; i < count; i++) {
            MobEntity m = makeNinja(w, i);
            if (m == null || !place(w, p, m)) continue;
            m.setTarget(p);
            ENEMIES.add(new Enemy(m, w.getTime() + LIFETIME));
            made++;
        }
        // un chef de temps en temps
        if (w.random.nextInt(4) == 0) {
            MobEntity boss = makeBoss(w);
            if (boss != null && place(w, p, boss)) {
                boss.setTarget(p);
                ENEMIES.add(new Enemy(boss, w.getTime() + LIFETIME));
                made++;
            }
        }
        if (made > 0) {
            p.sendMessage(Text.translatable("message.susanoo.ambush"), true);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 4f, 0.8f);
        }
    }

    /** Pour l'oeuf de ninja : fait apparaitre des ninjas (ou un chef) autour de `base`, tournes contre le joueur. */
    public static int spawnAt(ServerPlayerEntity p, BlockPos base, boolean boss) {
        ServerWorld w = p.getServerWorld();
        int count = boss ? 1 : 3;
        int made = 0;
        for (int i = 0; i < count; i++) {
            MobEntity m = boss ? makeBoss(w) : makeNinja(w, i);
            if (m == null) continue;
            boolean ok = false;
            for (int a = 0; a < 8 && !ok; a++) {
                double ox = (w.random.nextDouble() - 0.5) * 4.0, oz = (w.random.nextDouble() - 0.5) * 4.0;
                m.refreshPositionAndAngles(base.getX() + 0.5 + ox, base.getY(), base.getZ() + 0.5 + oz, w.random.nextFloat() * 360f, 0f);
                if (w.isSpaceEmpty(m)) {
                    w.spawnEntity(m);
                    ok = true;
                }
            }
            if (ok) {
                m.setTarget(p);
                ENEMIES.add(new Enemy(m, w.getTime() + LIFETIME));
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 1, m.getZ(), 15, 0.3, 0.7, 0.3, 0.02);
                made++;
            }
        }
        return made;
    }

    private static MobEntity makeNinja(ServerWorld w, int i) {
        MobEntity m;
        switch (i % 3) {
            case 0 -> {
                m = EntityType.VINDICATOR.create(w);
                if (m != null) m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(w.random.nextBoolean() ? Items.DIAMOND_SWORD : Items.IRON_AXE));
            }
            case 1 -> {
                m = EntityType.PILLAGER.create(w);
                if (m != null) m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            }
            default -> {
                m = EntityType.WITHER_SKELETON.create(w);
                if (m != null) m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
        }
        if (m == null) return null;
        blackArmor(m);
        m.setCustomName(Text.translatable("entity.susanoo.ninja"));
        m.setPersistent();
        m.setCanPickUpLoot(false);
        m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SPEED, 1200, 0));
        m.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE).setBaseValue(48.0);
        for (EquipmentSlot s : EquipmentSlot.values()) m.setEquipmentDropChance(s, 0f);
        return m;
    }

    private static MobEntity makeBoss(ServerWorld w) {
        MobEntity m = EntityType.VINDICATOR.create(w);
        if (m == null) return null;
        m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        m.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        m.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        m.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        m.equipStack(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        m.setCustomName(Text.translatable("entity.susanoo.ninja_boss"));
        m.setCustomNameVisible(true);
        m.setPersistent();
        m.setCanPickUpLoot(false);
        m.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(80.0);
        m.setHealth(80f);
        m.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE).setBaseValue(48.0);
        m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.STRENGTH, 6000, 1));
        m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SPEED, 6000, 0));
        for (EquipmentSlot s : EquipmentSlot.values()) m.setEquipmentDropChance(s, 0f);
        return m;
    }

    /** Tenue de ninja : cuir teint en noir. */
    private static void blackArmor(MobEntity m) {
        Item[] pieces = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < pieces.length; i++) {
            ItemStack s = new ItemStack(pieces[i]);
            ((DyeableItem) pieces[i]).setColor(s, 0x101010);
            m.equipStack(slots[i], s);
        }
    }

    /** Place le mob a 22-30 blocs du joueur, sur le sol. */
    private static boolean place(ServerWorld w, ServerPlayerEntity p, MobEntity m) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double a = w.random.nextDouble() * Math.PI * 2;
            double r = 22 + w.random.nextDouble() * 8;
            int x = (int) Math.floor(p.getX() + Math.cos(a) * r);
            int z = (int) Math.floor(p.getZ() + Math.sin(a) * r);
            if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!w.getFluidState(pos.down()).isEmpty() || !w.getBlockState(pos).isAir()) continue;
            m.refreshPositionAndAngles(x + 0.5, y, z + 0.5, w.random.nextFloat() * 360f, 0f);
            if (!w.isSpaceEmpty(m)) continue;
            w.spawnEntity(m);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, x + 0.5, y + 1, z + 0.5, 20, 0.4, 0.8, 0.4, 0.02);
            return true;
        }
        return false;
    }
}
