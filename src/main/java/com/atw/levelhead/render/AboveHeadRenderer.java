package com.atw.levelhead.render;

import com.atw.levelhead.ATWLevelHead;
import com.atw.levelhead.data.LevelTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.scoreboard.Team;
import net.weavemc.loader.api.event.RenderLivingEvent;
import org.lwjgl.opengl.GL11;

public class AboveHeadRenderer {
    private static final int HEADER_COLOR = 0x55FFFF;
    private static final int FOOTER_COLOR = 0xFFFF55;
    private static final int NICKED_COLOR = 0xFF5555;

    private final ATWLevelHead mod;

    public AboveHeadRenderer(ATWLevelHead mod) {
        this.mod = mod;
    }

    public void render(RenderLivingEvent.Post event) {
        if (!mod.isHypixel() || Minecraft.getMinecraft().gameSettings.hideGUI) {
            return;
        }

        EntityLivingBase entity = event.getEntity();
        if (!(entity instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer player = (EntityPlayer) entity;
        if (!shouldRender(player)) {
            return;
        }

        LevelTag tag = mod.getTag(player.getUniqueID());
        if (tag == null) {
            mod.queuePlayer(player);
            return;
        }

        double yOffset = 0.32D;
        if (player.worldObj != null
                && player.worldObj.getScoreboard().getObjectiveInDisplaySlot(2) != null
                && Minecraft.getMinecraft().thePlayer != null
                && player.getDistanceSqToEntity(Minecraft.getMinecraft().thePlayer) < 100.0D) {
            yOffset = 0.62D;
        }

        drawName(tag, player, event.getX(), event.getY() + yOffset, event.getZ());
    }

    private boolean shouldRender(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || player == mc.thePlayer || mod.isSelf(player) || mod.isHypixelNpc(player)) {
            return false;
        }
        if (player.isInvisible() || player.isInvisibleToPlayer(mc.thePlayer) || player.isSneaking()) {
            return false;
        }
        if (player.isPotionActive(Potion.invisibility)) {
            return false;
        }
        if (player.riddenByEntity != null || player.getDisplayName() == null || player.getDisplayName().getFormattedText().contains("\u00a7k")) {
            return false;
        }
        if (player.getDistanceSqToEntity(mc.thePlayer) > 4096.0D) {
            return false;
        }
        return respectsTeamVisibility(player);
    }

    private boolean respectsTeamVisibility(EntityPlayer player) {
        Team team = player.getTeam();
        Team selfTeam = Minecraft.getMinecraft().thePlayer == null ? null : Minecraft.getMinecraft().thePlayer.getTeam();
        if (team == null) {
            return true;
        }

        Team.EnumVisible visible = team.getNameTagVisibility();
        if (visible == Team.EnumVisible.NEVER) {
            return false;
        }
        if (visible == Team.EnumVisible.HIDE_FOR_OTHER_TEAMS) {
            return selfTeam == null || team.isSameTeam(selfTeam);
        }
        if (visible == Team.EnumVisible.HIDE_FOR_OWN_TEAM) {
            return selfTeam == null || !team.isSameTeam(selfTeam);
        }
        return true;
    }

    private void drawName(LevelTag tag, EntityPlayer player, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fontRenderer = mc.fontRendererObj;
        String text = tag.getText();
        float scale = 0.016666668F * 1.6F;
        int width = fontRenderer.getStringWidth(text) / 2;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + player.height + 0.5F, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1), 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        if (mod.getConfig().isBackgroundEnabled()) {
            drawBackground(-width - 2, -1, width + 2, 9);
        }
        drawText(fontRenderer, tag, -width, 0x35, false);

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        drawText(fontRenderer, tag, -width, 0xFF, true);

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private void drawBackground(int left, int top, int right, int bottom) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GlStateManager.disableTexture2D();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        renderer.pos(left, bottom, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        renderer.pos(right, bottom, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        renderer.pos(right, top, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        renderer.pos(left, top, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    private void drawText(FontRenderer fontRenderer, LevelTag tag, int x, int alpha, boolean shadow) {
        if (tag.isNicked()) {
            fontRenderer.drawString(tag.getText(), x, 0, withAlpha(NICKED_COLOR, alpha), shadow);
            return;
        }

        fontRenderer.drawString(tag.getHeader(), x, 0, withAlpha(HEADER_COLOR, alpha), shadow);
        fontRenderer.drawString(tag.getFooter(), x + fontRenderer.getStringWidth(tag.getHeader()), 0, withAlpha(FOOTER_COLOR, alpha), shadow);
    }

    private int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
