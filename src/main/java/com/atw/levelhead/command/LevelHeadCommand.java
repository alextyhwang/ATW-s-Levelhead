package com.atw.levelhead.command;

import com.atw.levelhead.ATWLevelHead;
import com.atw.levelhead.render.TabListFormatter;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

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
            case "debug":
                sendStatus();
                mod.sendChat(EnumChatFormatting.GRAY + TabListFormatter.debugStatus());
                break;
            case "debugcontext":
            case "context":
                sendStatus();
                mod.logHypixelContextDebug();
                mod.sendChat(EnumChatFormatting.GRAY + mod.hypixelContextSummary());
                break;
            case "stats":
                handleStats(args);
                break;
            case "api":
                handleApi(args);
                break;
            case "players":
            case "recent":
                mod.requestRecentChatStats();
                break;
            case "status":
            default:
                if ("status".equals(subcommand) && args.length >= 2) {
                    mod.requestChatStats(args[1]);
                } else {
                    sendStatus();
                }
                break;
        }
    }

    private void sendStatus() {
        mod.sendChat(
                EnumChatFormatting.GRAY + "hypixel=" + mod.isHypixel()
                        + ", mode=" + mod.configuredMode()
                        + ", effectiveMode=" + mod.displayMode()
                        + ", ready=" + mod.isAuthenticated()
                        + ", context=" + mod.hypixelContext()
                        + ", bwStarted=" + mod.isBedwarsGameStarted()
                        + ", cache=" + mod.cacheSize()
                        + ", diskCache=" + mod.diskCacheSize()
                        + ", queue=" + mod.queueSize()
                        + ", network=" + mod.networkFetchStatus()
                        + ", background=" + mod.getConfig().isBackgroundEnabled()
                        + ", provider=" + mod.providerStatus()
                        + ", " + TabListFormatter.debugStatus()
        );
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

    private void handleStats(String[] args) {
        if (args.length < 2) {
            mod.sendChat(EnumChatFormatting.RED + "Usage: /atwlh stats <player/prefix>");
            return;
        }

        mod.requestChatStats(args[1]);
    }

    private void handleApi(String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase();
        switch (action) {
            case "add":
            case "set":
            case "update":
                if (args.length < 3) {
                    mod.sendChat(EnumChatFormatting.RED + "Usage: /atwlh api add <hypixel-api-key>");
                    return;
                }
                String apiKey = args[2].trim();
                if (!looksLikeApiKey(apiKey)) {
                    mod.sendChat(EnumChatFormatting.RED + "That doesn't look like a Hypixel API key. Use /atwlh api add <key>.");
                    return;
                }
                mod.setHypixelApiKey(apiKey);
                mod.sendChat(EnumChatFormatting.GREEN + "Hypixel API key saved and BedWars stats refreshed.");
                mod.sendChat(EnumChatFormatting.GRAY + "Run /atwlh api test to check it now.");
                break;
            case "test":
                if (!mod.hasHypixelApiKey()) {
                    mod.sendChat(EnumChatFormatting.RED + "Hypixel API key is missing. Use /atwlh api add <key>.");
                    return;
                }
                mod.sendChat(EnumChatFormatting.GRAY + "Testing Hypixel API key...");
                mod.testHypixelApiKey();
                break;
            case "clear":
            case "remove":
                mod.clearHypixelApiKey();
                mod.sendChat(EnumChatFormatting.YELLOW + "Hypixel API key cleared.");
                break;
            case "status":
                mod.sendChat(EnumChatFormatting.GRAY + "Hypixel API key: " + mod.hypixelApiKeySummary()
                        + EnumChatFormatting.GRAY + "; provider=" + mod.providerStatus());
                break;
            default:
                mod.sendChat(EnumChatFormatting.RED + "Usage: /atwlh api <add|test|status|clear>");
                break;
        }
    }

    private boolean looksLikeApiKey(String apiKey) {
        try {
            UUID.fromString(apiKey);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

}
