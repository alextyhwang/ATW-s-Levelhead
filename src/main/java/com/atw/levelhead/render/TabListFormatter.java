package com.atw.levelhead.render;

import com.atw.levelhead.ATWLevelHead;
import com.atw.levelhead.data.LevelTag;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class TabListFormatter {
    private static final AtomicInteger totalCalls = new AtomicInteger();
    private static final AtomicInteger taggedRows = new AtomicInteger();
    private static final AtomicInteger missingCacheRows = new AtomicInteger();
    private static final AtomicInteger npcRows = new AtomicInteger();
    private static final AtomicInteger selfRows = new AtomicInteger();
    private static final AtomicInteger disabledRows = new AtomicInteger();
    private static volatile String lastPlayerName = "";
    private static volatile String lastTaggedName = "";

    public static String appendLevelTag(String playerName, NetworkPlayerInfo playerInfo) {
        totalCalls.incrementAndGet();
        lastPlayerName = stripFormatting(playerName);
        if (playerName == null || playerInfo == null) {
            return playerName;
        }

        ATWLevelHead mod = ATWLevelHead.getInstance();
        if (mod == null) {
            return playerName;
        }
        if (!mod.shouldShowTabTags()) {
            disabledRows.incrementAndGet();
            return playerName;
        }

        GameProfile profile = playerInfo.getGameProfile();
        if (profile == null) {
            return playerName;
        }

        if (ATWLevelHead.isHypixelNpcDisplayName(playerName) || ATWLevelHead.isHypixelNpcName(profile.getName())) {
            npcRows.incrementAndGet();
            return playerName;
        }

        UUID uuid = profile.getId();
        if (isSelf(uuid)) {
            selfRows.incrementAndGet();
        }

        LevelTag tag = uuid == null ? null : mod.getCachedTag(uuid);
        if (tag == null) {
            mod.queuePlayer(uuid, playerName);
            missingCacheRows.incrementAndGet();
            return playerName;
        }

        taggedRows.incrementAndGet();
        lastTaggedName = "[" + stripFormatting(tag.getText()) + "] " + stripFormatting(playerName);
        return "\u00a78[" + tag.getText() + "\u00a78] " + playerName;
    }

    public static String debugStatus() {
        return "tabCalls=" + totalCalls.get()
                + ", tabTagged=" + taggedRows.get()
                + ", tabMissingCache=" + missingCacheRows.get()
                + ", tabNpcSkipped=" + npcRows.get()
                + ", tabSelfRows=" + selfRows.get()
                + ", tabDisabled=" + disabledRows.get()
                + ", lastTab='" + lastPlayerName + "'"
                + ", lastTagged='" + lastTaggedName + "'";
    }

    private static boolean isSelf(UUID uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        return uuid != null && mc.thePlayer != null && uuid.equals(mc.thePlayer.getUniqueID());
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7.", "");
    }

}
