package com.atw.levelhead.command;

import com.atw.levelhead.ATWLevelHead;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import org.jetbrains.annotations.NotNull;

public class LevelHeadCommand extends Command {
    private final ATWLevelHead mod;

    public LevelHeadCommand(ATWLevelHead mod) {
        super("atwlevelhead", "atwlh");
        this.mod = mod;
    }

    @Override
    public void handle(@NotNull String[] args) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (subcommand) {
            case "background":
            case "bg":
                handleBackground(args);
                break;
            case "mode":
                handleMode(args);
                break;
            case "reload":
                mod.reload();
                mod.sendChat(EnumChatFormatting.GREEN + "Reloaded.");
                break;
            case "clearcache":
                mod.clearCache();
                mod.sendChat(EnumChatFormatting.GREEN + "Cache cleared.");
                break;
            case "status":
            default:
                mod.sendChat(
                        EnumChatFormatting.GRAY + "hypixel=" + mod.isHypixel()
                                + ", mode=" + mod.displayMode()
                                + ", ready=" + mod.isAuthenticated()
                                + ", cache=" + mod.cacheSize()
                                + ", diskCache=" + mod.diskCacheSize()
                                + ", queue=" + mod.queueSize()
                                + ", background=" + mod.getConfig().isBackgroundEnabled()
                                + ", provider=" + mod.providerStatus()
                );
                break;
        }
    }

    private void handleBackground(String[] args) {
        boolean enabled;
        if (args.length < 2 || "toggle".equalsIgnoreCase(args[1])) {
            enabled = !mod.getConfig().isBackgroundEnabled();
        } else if ("on".equalsIgnoreCase(args[1]) || "true".equalsIgnoreCase(args[1])) {
            enabled = true;
        } else if ("off".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1])) {
            enabled = false;
        } else {
            mod.sendChat(EnumChatFormatting.RED + "Usage: /atwlh background <on|off|toggle>");
            return;
        }

        mod.getConfig().setBackgroundEnabled(enabled);
        mod.getConfig().save();
        mod.sendChat(EnumChatFormatting.GREEN + "Background " + (enabled ? "enabled." : "disabled."));
    }

    private void handleMode(String[] args) {
        if (args.length < 2) {
            mod.sendChat(EnumChatFormatting.YELLOW + "Mode is " + mod.displayMode() + ". Use /atwlh mode <level|bedwars>.");
            return;
        }

        if ("level".equalsIgnoreCase(args[1]) || "network".equalsIgnoreCase(args[1])) {
            mod.setDisplayMode("level");
            mod.sendChat(EnumChatFormatting.GREEN + "Mode set to Hypixel network level.");
        } else if ("bedwars".equalsIgnoreCase(args[1]) || "bw".equalsIgnoreCase(args[1])) {
            mod.setDisplayMode("bedwars");
            mod.sendChat(EnumChatFormatting.GREEN + "Mode set to BedWars star + FKDR.");
        } else {
            mod.sendChat(EnumChatFormatting.RED + "Usage: /atwlh mode <level|bedwars>");
        }
    }

}
