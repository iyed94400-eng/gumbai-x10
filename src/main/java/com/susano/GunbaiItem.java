package com.susano;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.Ingredient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Le Gunbai de Madara : eventail de guerre. Plus puissant que le Netherite (degats 12 contre 8, 5000 de durabilite).
 * Clic droit : rafale de vent qui repousse tout devant toi. Clic droit accroupi : tourbillon qui repousse tout autour.
 */
public class GunbaiItem extends SwordItem {
    private static final double GUST_RANGE = 24.0;
    private static final double WHIRL_RANGE = 15.0;
    private static final int GUST_CD = 30, WHIRL_CD = 80;

    /** Materiau : meilleur que le Netherite (attaque 4 / 2031 usures). */
    private static final ToolMaterial MATERIAL = new ToolMaterial() {
        @Override public int getDurability() { return 5000; }
        @Override public float getMiningSpeedMultiplier() { return 10.0f; }
        @Override public float getAttackDamage() { return 8.0f; }
        @Override public int getMiningLevel() { return 4; }
        @Override public int getEnchantability() { return 25; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.ofItems(Items.NETHERITE_INGOT); }
    };

    public GunbaiItem(Settings settings) {
        // degats = 1 (main) + 8 (materiau) + 3 = 12 ; vitesse 1,6
        super(MATERIAL, 3, -2.4f, settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (user.getItemCooldownManager().isCoolingDown(this)) {
            return TypedActionResult.fail(stack);
        }
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            boolean whirl = sp.isSneaking();
            if (whirl) {
                whirlwind(sp);
            } else {
                gust(sp);
            }
            user.getItemCooldownManager().set(this, whirl ? WHIRL_CD : GUST_CD);
            stack.damage(1, user, p -> p.sendToolBreakStatus(hand));
        }
        user.swingHand(hand);
        return TypedActionResult.success(stack, world.isClient);
    }

    /** Chaque coup repousse aussi la cible d'un souffle de vent. */
    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        double dx = attacker.getX() - target.getX();
        double dz = attacker.getZ() - target.getZ();
        target.takeKnockback(1.6, dx, dz);
        target.velocityModified = true;
        return super.postHit(stack, target, attacker);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("tooltip.susanoo.gunbai.1"));
        tooltip.add(Text.translatable("tooltip.susanoo.gunbai.2"));
    }

    // ---------------------------------------------------------------- rafale de vent (devant)

    private static void gust(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        Box box = new Box(eye, eye).expand(GUST_RANGE);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> SusanooManager.canTarget(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2.0, 0).subtract(eye);
            double d = to.length();
            if (d > GUST_RANGE || d < 1.0e-3 || to.normalize().dotProduct(look) < 0.5) continue;
            double k = 1.0 - d / GUST_RANGE;
            t.damage(w.getDamageSources().playerAttack(p), (float) (10.0 + 10.0 * k));
            t.addVelocity(look.x * (2.0 + 3.5 * k), 0.5 + 0.7 * k, look.z * (2.0 + 3.5 * k));
            t.velocityModified = true;
            t.extinguish();
        }
        // les projectiles sont renvoyes a l'expediteur
        for (ProjectileEntity pr : w.getEntitiesByClass(ProjectileEntity.class, box, x -> x.getOwner() != p)) {
            Vec3d to = pr.getPos().subtract(eye);
            if (to.length() > GUST_RANGE || to.normalize().dotProduct(look) < 0.5) continue;
            pr.setOwner(p);
            pr.setVelocity(look.x * 2.5, look.y * 2.5, look.z * 2.5);
            pr.velocityModified = true;
        }
        // le vent arrache les plantes, torches, neige et feuilles
        int budget = 120;
        BlockPos c = p.getBlockPos();
        int r = (int) GUST_RANGE;
        for (int dx = -r; dx <= r && budget > 0; dx++) {
            for (int dy = -4; dy <= 8 && budget > 0; dy++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len < 1.5 || len > GUST_RANGE) continue;
                    if ((dx * look.x + dy * look.y + dz * look.z) / len < 0.6) continue;
                    if (SusanooManager.destroy(w, p, c.add(dx, dy, dz), 0.3f)) budget--;
                }
            }
        }
        // particules : un cone de vent
        for (double d = 1.5; d <= GUST_RANGE; d += 1.5) {
            Vec3d at = eye.add(look.multiply(d));
            double spread = 0.4 + d * 0.28;
            SusanooManager.fx(w, ParticleTypes.CLOUD, at, 6, spread, spread * 0.6, spread, 0.15);
            if (((int) (d / 1.5)) % 2 == 0) {
                SusanooManager.fx(w, ParticleTypes.POOF, at, 3, spread, spread * 0.5, spread, 0.2);
            }
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 4f, 0.5f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 2f, 0.7f);
    }

    // ---------------------------------------------------------------- tourbillon (tout autour)

    private static void whirlwind(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        Box box = new Box(c, c).expand(WHIRL_RANGE);
        for (LivingEntity t : w.getEntitiesByClass(LivingEntity.class, box, x -> SusanooManager.canTarget(p, x))) {
            double d = t.getPos().distanceTo(c);
            if (d > WHIRL_RANGE) continue;
            double k = 1.0 - d / WHIRL_RANGE;
            Vec3d out = new Vec3d(t.getX() - c.x, 0, t.getZ() - c.z);
            out = out.lengthSquared() < 1.0e-4 ? new Vec3d(0, 0, 1) : out.normalize();
            t.damage(w.getDamageSources().playerAttack(p), (float) (12.0 + 8.0 * k));
            t.addVelocity(out.x * (2.5 + 3.0 * k), 0.7 + 0.8 * k, out.z * (2.5 + 3.0 * k));
            t.velocityModified = true;
            t.extinguish();
        }
        for (ProjectileEntity pr : w.getEntitiesByClass(ProjectileEntity.class, box, x -> x.getOwner() != p)) {
            Vec3d out = new Vec3d(pr.getX() - c.x, 0, pr.getZ() - c.z);
            out = out.lengthSquared() < 1.0e-4 ? new Vec3d(0, 0, 1) : out.normalize();
            pr.setOwner(p);
            pr.setVelocity(out.x * 2.5, 0.1, out.z * 2.5);
            pr.velocityModified = true;
        }
        for (double r = 2; r <= WHIRL_RANGE; r += 3) {
            int n = (int) (8 + r * 2);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                SusanooManager.fx(w, i % 2 == 0 ? ParticleTypes.CLOUD : ParticleTypes.POOF,
                        new Vec3d(c.x + Math.cos(a) * r, c.y + 0.5 + r * 0.08, c.z + Math.sin(a) * r), 1, 0.2, 0.3, 0.2, 0.15);
            }
        }
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 3f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 5f, 0.4f);
    }
}
