package com.susano.client;

import com.susano.SusanooEntity;
import com.susano.SusanooMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public class SusanooClient implements ClientModInitializer {
    private static KeyBinding toggle, form, attack, special, stomp, kick, meteor, amaterasu, rinUse, barKey;
    /** Barre de pouvoirs du Rinnegan. */
    private static boolean barOpen;
    private static int barSlot, barPrevSlot;
    private static final long[] cdEnd = new long[8];
    private static final int[] cdTotal = new int[8];
    private static final String[] PATH_KEYS = {"deva", "banshou", "chibaku", "preta", "naraka", "animal", "human", "asura"};
    private static final net.minecraft.util.Identifier[] RIN_TEX = new net.minecraft.util.Identifier[8];
    private static SusanooEntity local;

    // FOV : un bonus par forme (adouci), plus un coup de pouce a chaque transformation / impact
    private static final float[] FOV_BONUS = {0f, 4f, 10f, 17f, 28f};
    private static float fovPrev, fovCur, fovKickPrev, fovKick;
    private static int lastForm, lastAttackTicks;
    /** Reglage : assombrit les bords de l'ecran en forme Armure / Parfaite. */
    private static final boolean VIGNETTE = true;

    /** Bonus de champ de vision (degres) a ajouter a celui du jeu ; appele par GameRendererMixin. */
    public static float fovBonus(float td) {
        return MathHelper.lerp(td, fovPrev, fovCur) + MathHelper.lerp(td, fovKickPrev, fovKick);
    }

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(SusanooMod.SUSANOO, SusanooRenderer::new);
        EntityRendererRegistry.register(SusanooMod.METEOR, MeteorRenderer::new);

        toggle = key("key.susanoo.toggle", GLFW.GLFW_KEY_G);
        form = key("key.susanoo.form", GLFW.GLFW_KEY_H);
        attack = key("key.susanoo.attack", GLFW.GLFW_KEY_R);
        special = key("key.susanoo.special", GLFW.GLFW_KEY_V);
        stomp = key("key.susanoo.stomp", GLFW.GLFW_KEY_C);
        kick = key("key.susanoo.kick", GLFW.GLFW_KEY_B);
        meteor = key("key.susanoo.meteor", GLFW.GLFW_KEY_M);
        amaterasu = key("key.susanoo.amaterasu", GLFW.GLFW_KEY_N);
        rinUse = key("key.susanoo.rin_use", GLFW.GLFW_KEY_J);
        barKey = key("key.susanoo.bar", GLFW.GLFW_KEY_Y);

        // recharges envoyees par le serveur
        ClientPlayNetworking.registerGlobalReceiver(SusanooMod.RIN_CD, (client, handler, buf, sender) -> {
            int path = buf.readVarInt();
            int ticks = buf.readVarInt();
            client.execute(() -> {
                if (path >= 0 && path < 8 && client.world != null) {
                    cdEnd[path] = client.world.getTime() + ticks;
                    cdTotal[path] = ticks;
                }
            });
        });

        // barre de pouvoirs : molette / touches 1-8 pour choisir, clic droit ou J pour utiliser
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null) {
                barOpen = false;
                return;
            }
            boolean has = hasRinnegan(mc);
            if (barOpen && !has) {
                barOpen = false;
            }
            while (barKey.wasPressed()) {
                if (barOpen) {
                    barOpen = false;
                } else if (has && mc.currentScreen == null) {
                    barOpen = true;
                } else if (!has) {
                    mc.player.sendMessage(Text.translatable("message.susanoo.need_rinnegan"), true);
                }
            }
            var inv = mc.player.getInventory();
            if (!barOpen || mc.currentScreen != null) {
                barPrevSlot = inv.selectedSlot;
                return;
            }
            // la molette change de pouvoir au lieu de changer d'objet
            if (inv.selectedSlot != barPrevSlot) {
                int d = Math.floorMod(inv.selectedSlot - barPrevSlot + 4, 9) - 4;
                barSlot = Math.floorMod(barSlot + d, 8);
                inv.selectedSlot = barPrevSlot;
            }
            for (int i = 0; i < 8; i++) {
                while (mc.options.hotbarKeys[i].wasPressed()) {
                    barSlot = i;
                }
            }
            boolean fire = false;
            while (mc.options.useKey.wasPressed()) {
                fire = true;
            }
            mc.options.useKey.setPressed(false);
            if (fire) {
                send(SusanooMod.ACT_RIN_USE_AT, barSlot);
            }
        });
        HudRenderCallback.EVENT.register(SusanooClient::drawPowerBar);

        // clic gauche = coup d'epee du Susanoo (remplace l'attaque normale tant qu'il est invoque)
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (local == null || local.isRemoved() || mc.player == null || mc.currentScreen != null || !mc.mouse.isCursorLocked()) {
                return;
            }
            KeyBinding atk = mc.options.attackKey;
            boolean held = atk.isPressed()
                    || GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (held) {
                send(SusanooMod.ACT_ATTACK);
            }
            // on vide l'attaque vanille : pas de coup de poing ni de minage pendant le Susanoo
            while (atk.wasPressed()) { /* vide */ }
            atk.setPressed(false);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.world == null) {
                local = null;
                fovPrev = fovCur = fovKickPrev = fovKick = 0f;
                lastForm = 0;
                lastAttackTicks = 0;
                return;
            }
            local = null;
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof SusanooEntity s && mc.player.getUuid().equals(s.getOwnerUuid())) {
                    local = s;
                    break;
                }
            }
            updateFov(mc);
            if (mc.currentScreen != null) {
                return;
            }
            while (toggle.wasPressed()) send(SusanooMod.ACT_TOGGLE);
            while (form.wasPressed()) send(Screen.hasShiftDown() ? SusanooMod.ACT_FORM_DOWN : SusanooMod.ACT_FORM_UP);
            while (attack.wasPressed()) { /* vide la file ; le maintien est gere ci-dessous */ }
            if (attack.isPressed()) send(SusanooMod.ACT_ATTACK);
            while (special.wasPressed()) send(SusanooMod.ACT_SPECIAL);
            while (stomp.wasPressed()) send(SusanooMod.ACT_STOMP);
            while (kick.wasPressed()) send(SusanooMod.ACT_KICK);
            while (meteor.wasPressed()) send(SusanooMod.ACT_METEOR);
            while (amaterasu.wasPressed()) send(SusanooMod.ACT_AMATERASU);
            while (rinUse.wasPressed()) send(SusanooMod.ACT_RIN_USE_AT, barSlot);
        });

        HudRenderCallback.EVENT.register(SusanooClient::drawHud);
    }

    /** FOV progressif + montee en hauteur (le geant parfait se tient debout sur ses jambes). */
    private static void updateFov(MinecraftClient mc) {
        SusanooEntity s = local;
        float target = s != null ? FOV_BONUS[s.getForm()] : 0f;
        fovPrev = fovCur;
        fovCur += (target - fovCur) * 0.07f;
        fovKickPrev = fovKick;
        fovKick *= 0.86f;
        if (s == null) {
            lastForm = 0;
            lastAttackTicks = 0;
            return;
        }
        int f = s.getForm();
        if (lastForm > 0 && f > lastForm) {
            fovKick += 8f + 3f * f;
        }
        lastForm = f;
        int at = s.getAttackTicks();
        if (at != lastAttackTicks && at > 0) {
            int kind = s.getAttackKind();
            int len = SusanooEntity.ANIM_LEN[f][kind];
            int elapsed = len - at;
            if ((kind == 2 && elapsed == (int) (len * 0.6)) || (kind == 1 && elapsed == len - 4)) {
                fovKick += kind == 2 ? 9f : 6f;
            }
        }
        lastAttackTicks = at;
        // forme parfaite : on pousse le joueur vers le haut jusqu'a la hauteur de marche des jambes
        if (f == 4 && mc.player != null && mc.player.getAbilities().flying) {
            double h = mc.player.getY() - s.groundY;
            if (h < SusanooEntity.HOVER_HEIGHT - 0.4) {
                mc.player.addVelocity(0, Math.min(0.30, (SusanooEntity.HOVER_HEIGHT - h) * 0.10), 0);
            }
        }
    }

    /** Bords de l'ecran assombris qui pulsent comme un battement de coeur. */
    private static void drawVignette(DrawContext ctx, int w, int h, SusanooEntity s, float td) {
        int f = s.getForm();
        if (f < 3) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        float time = (mc.world != null ? mc.world.getTime() : 0) + td;
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * (f == 4 ? 0.26f : 0.13f));
        float strength = (f == 4 ? 0.55f : 0.30f) * (0.7f + 0.3f * pulse);
        int[] c = com.susano.SusanooColor.byId(s.getColorId()).core;
        final int bands = 14;
        int thick = Math.max(1, (int) (Math.min(w, h) * 0.20f / bands));
        for (int i = 0; i < bands; i++) {
            float k = 1f - i / (float) bands;
            int a = (int) (255 * strength * k * k);
            int col = (a << 24) | ((c[0] / 3) << 16) | ((c[1] / 3) << 8) | (c[2] / 3);
            ctx.fill(0, i * thick, w, (i + 1) * thick, col);
            ctx.fill(0, h - (i + 1) * thick, w, h - i * thick, col);
            ctx.fill(0, i * thick, (i + 1) * thick, h, col);
            ctx.fill(w - (i + 1) * thick, 0, w - i * thick, h, col);
        }
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "key.categories.susanoo"));
    }

    private static boolean hasRinnegan(MinecraftClient mc) {
        if (mc.player == null) return false;
        if (mc.player.getOffHandStack().getItem() == SusanooMod.RINNEGAN) return true;
        for (ItemStack s : mc.player.getInventory().main) {
            if (s.getItem() == SusanooMod.RINNEGAN) return true;
        }
        return false;
    }

    private static void send(int action, int arg) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(action);
        buf.writeVarInt(arg);
        ClientPlayNetworking.send(SusanooMod.ACTION, buf);
    }

    /** La barre de pouvoirs du Rinnegan, au-dessus de la barre d'objets. */
    private static void drawPowerBar(DrawContext ctx, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!barOpen || mc.player == null || mc.world == null || mc.options.hudHidden) {
            return;
        }
        if (RIN_TEX[0] == null) {
            for (int i = 0; i < 8; i++) {
                RIN_TEX[i] = new net.minecraft.util.Identifier("susanoo", "textures/gui/rin_" + i + ".png");
            }
        }
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int slot = 22, n = 8, total = slot * n;
        int x0 = w / 2 - total / 2;
        int y0 = h - 74; // juste au-dessus des coeurs / de la faim
        long now = mc.world.getTime();
        ctx.fill(x0 - 3, y0 - 3, x0 + total + 3, y0 + slot + 3, 0xB0000000);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * slot;
            boolean sel = i == barSlot;
            ctx.fill(x, y0, x + slot, y0 + slot, sel ? 0xDD3A1060 : 0xAA14101E);
            int border = sel ? 0xFFD2A8FF : 0xFF4A3A66;
            int t = sel ? 2 : 1;
            ctx.fill(x, y0, x + slot, y0 + t, border);
            ctx.fill(x, y0 + slot - t, x + slot, y0 + slot, border);
            ctx.fill(x, y0, x + t, y0 + slot, border);
            ctx.fill(x + slot - t, y0, x + slot, y0 + slot, border);
            ctx.drawTexture(RIN_TEX[i], x + 3, y0 + 3, 0f, 0f, 16, 16, 16, 16);
            long rem = cdEnd[i] - now;
            if (rem > 0 && cdTotal[i] > 0) {
                int hh = (int) Math.ceil(16.0 * rem / cdTotal[i]);
                ctx.fill(x + 3, y0 + 19 - hh, x + 19, y0 + 19, 0xB0000000);
                ctx.drawCenteredTextWithShadow(mc.textRenderer, String.valueOf((rem + 19) / 20), x + 11, y0 + 7, 0xFFFFFF);
            }
            ctx.drawTextWithShadow(mc.textRenderer, String.valueOf(i + 1), x + 2, y0 + 1, 0xFFFFFF);
        }
        Text label = Text.translatable("path.susanoo." + PATH_KEYS[barSlot]);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, label, w / 2, y0 - 13, 0xE0C8FF);
    }

    private static void send(int action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(action);
        ClientPlayNetworking.send(SusanooMod.ACTION, buf);
    }

    private static void drawHud(DrawContext ctx, float td) {
        SusanooEntity s = local;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null || s.isRemoved() || mc.options.hudHidden) {
            return;
        }
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        if (VIGNETTE && mc.options.getPerspective().isFirstPerson()) {
            drawVignette(ctx, w, h, s, td);
        }
        int x = w / 2 - 91;
        int y = barOpen ? h - 104 : h - 70; // monte quand la barre de pouvoirs est ouverte
        float ratio = Math.max(0f, Math.min(1f, s.getShield() / s.getMaxShield()));
        int[] c = com.susano.SusanooColor.byId(s.getColorId()).glow;
        int argb = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
        ctx.fill(x - 1, y - 1, x + 183, y + 5, 0xAA000000);
        ctx.fill(x, y, x + (int) (182 * ratio), y + 4, argb);
        Text label = Text.translatable("hud.susanoo.form." + s.getForm());
        ctx.drawTextWithShadow(mc.textRenderer, label, x, y - 11, argb);
    }
}
