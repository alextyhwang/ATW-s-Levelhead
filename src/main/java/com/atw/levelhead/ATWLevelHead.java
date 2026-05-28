package com.atw.levelhead;

import com.atw.levelhead.command.LevelHeadCommand;
import com.atw.levelhead.config.LevelHeadConfig;
import com.atw.levelhead.data.BedwarsFetchResult;
import com.atw.levelhead.data.BedwarsPlayerStats;
import com.atw.levelhead.data.BedwarsStatFormatter;
import com.atw.levelhead.data.HypixelBedwarsProvider;
import com.atw.levelhead.data.HypixelContextDetector;
import com.atw.levelhead.data.LevelTagDiskCache;
import com.atw.levelhead.data.LevelTag;
import com.atw.levelhead.data.LevelheadProvider;
import com.atw.levelhead.data.Sk1erLevelheadProvider;
import com.atw.levelhead.render.AboveHeadRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.ModInitializer;
import net.weavemc.loader.api.command.CommandBus;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.ChatSentEvent;
import net.weavemc.loader.api.event.EntityListEvent;
import net.weavemc.loader.api.event.EventBus;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.RenderLivingEvent;
import net.weavemc.loader.api.event.ServerConnectEvent;
import net.weavemc.loader.api.event.WorldEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ATWLevelHead implements ModInitializer {
    public static final String PREFIX = EnumChatFormatting.AQUA + "[ATW LevelHead] " + EnumChatFormatting.RESET;
    private static final Pattern HYPIXEL_NPC_NAME = Pattern.compile("[a-z][0-9]{2}n[0-9]{6}");
    private static final Pattern CHAT_SPEAKER = Pattern.compile("^.*?(?:\\[[^\\]]+\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*(?::|»|>)\\s+.*$");
    private static final Pattern BEDWARS_JOIN_COUNT = Pattern.compile("^.+ has joined \\((\\d+)/(\\d+)\\)!$");
    private static final Pattern PLAYER_NAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int LEVEL_FETCH_BATCH_SIZE = 80;
    private static final long LEVEL_REQUEST_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long LEVEL_DRAIN_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long RECENT_CHAT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long LOBBY_CHAT_STATS_COOLDOWN_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private static ATWLevelHead instance;

    private final ConcurrentHashMap<String, LevelTag> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> queued = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRequested = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RecentSpeaker> recentChatSpeakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lobbyChatStatsLookups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> visibleNamesByUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BedwarsGamePlayer> bedwarsGamePlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BedwarsPlayerStats> bedwarsStats = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> pending = new ConcurrentLinkedQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ATW-LevelHead-Worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ATW-LevelHead-Scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private final LevelHeadConfig config = LevelHeadConfig.load();
    private final LevelTagDiskCache diskCache = LevelTagDiskCache.load();
    private final Sk1erLevelheadProvider sk1erProvider = new Sk1erLevelheadProvider();
    private final HypixelBedwarsProvider bedwarsProvider = new HypixelBedwarsProvider(config);
    private final AboveHeadRenderer renderer = new AboveHeadRenderer(this);

    private volatile boolean hypixel;
    private volatile boolean authAttempted;
    private volatile boolean hypixelApiValidationStarted;
    private volatile boolean hypixelApiWarningShown;
    private volatile boolean bedwarsGameStarted;
    private volatile boolean bedwarsOverviewSent;
    private volatile int bedwarsOverviewAttempts;
    private volatile int bedwarsExpectedPlayers;
    private volatile long nextDrainAt;
    private volatile String networkFetchStatus = "idle";

    public static ATWLevelHead getInstance() {
        return instance;
    }

    @Override
    public void preInit() {
        instance = this;
        log("Loading ATW LevelHead for Weave.");
        CommandBus.register(new LevelHeadCommand(this));
        EventBus.subscribe(RenderLivingEvent.Post.class, renderer::render);
        EventBus.subscribe(WorldEvent.Load.class, this::onWorldLoad);
        EventBus.subscribe(WorldEvent.Unload.class, this::onWorldUnload);
        EventBus.subscribe(EntityListEvent.Add.class, this::onEntityAdd);
        EventBus.subscribe(ServerConnectEvent.class, this::onServerConnect);
        EventBus.subscribe(PacketEvent.Send.class, this::onPacketSend);
        EventBus.subscribe(ChatReceivedEvent.class, this::onChatReceived);
    }

    public LevelTag getTag(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return cache.get(cacheKey(cacheMode(), uuid));
    }

    public LevelTag getCachedTag(UUID uuid) {
        if (!shouldShowTabTags()) {
            return null;
        }

        String mode = cacheMode();
        LevelTag tag = uuid == null ? null : cache.get(cacheKey(mode, uuid));
        if (tag != null || uuid == null) {
            return tag;
        }

        tag = diskCache.getFresh(mode, uuid);
        if (tag == null && !activeProvider().isReady()) {
            tag = diskCache.getAny(mode, uuid);
        }
        if (tag != null && mode.startsWith("bedwars") && tag.isNicked()) {
            tag = null;
        }
        if (tag != null) {
            cache.put(cacheKey(mode, uuid), tag);
        }
        return tag;
    }

    public boolean isHypixel() {
        return hypixel;
    }

    public int cacheSize() {
        return cache.size();
    }

    public int diskCacheSize() {
        return diskCache.size();
    }

    public int queueSize() {
        return pending.size();
    }

    public boolean isAuthenticated() {
        return activeProvider().isReady();
    }

    public String providerStatus() {
        return activeProvider().getStatus();
    }

    public String hypixelContext() {
        return HypixelContextDetector.detect(hypixel).name();
    }

    public String configuredMode() {
        return config.isBedwarsMode() ? "bedwars" : "level";
    }

    public boolean shouldShowTabTags() {
        return isBedwarsStatsActive();
    }

    public String networkFetchStatus() {
        return networkFetchStatus;
    }

    public boolean isBedwarsGameStarted() {
        return bedwarsGameStarted;
    }

    public String hypixelContextSummary() {
        return HypixelContextDetector.summary(hypixel);
    }

    public void logHypixelContextDebug() {
        HypixelContextDetector.logDebug(hypixel);
    }

    public LevelHeadConfig getConfig() {
        return config;
    }

    public String displayMode() {
        return isBedwarsStatsActive() ? "bedwars" : "level";
    }

    public void setDisplayMode(String mode) {
        String normalized = "bedwars".equalsIgnoreCase(mode) || "bw".equalsIgnoreCase(mode) ? "bedwars" : "level";
        config.setDisplayMode(normalized);
        config.save();
        reload();
    }

    public void setHypixelApiKey(String apiKey) {
        config.setHypixelApiKey(apiKey);
        config.save();
        bedwarsProvider.useConfiguredApiKey();
        hypixelApiValidationStarted = false;
        hypixelApiWarningShown = false;
        networkFetchStatus = "api-key-updated";
    }

    public void clearHypixelApiKey() {
        setHypixelApiKey("");
    }

    public boolean hasHypixelApiKey() {
        return !config.getHypixelApiKey().trim().isEmpty();
    }

    public String hypixelApiKeySummary() {
        String apiKey = config.getHypixelApiKey().trim();
        if (apiKey.isEmpty()) {
            return "missing";
        }
        if (apiKey.length() <= 8) {
            return "saved (" + apiKey.length() + " chars)";
        }
        return "saved (..."
                + apiKey.substring(apiKey.length() - 4)
                + ", " + apiKey.length() + " chars)";
    }

    public void testHypixelApiKey() {
        executor.execute(() -> {
            String warning = bedwarsProvider.validateApiKeyWarning();
            if (warning == null) {
                sendChatOnClientThread(EnumChatFormatting.GREEN + "Hypixel API key works.");
            } else {
                sendChatOnClientThread(warning);
            }
        });
    }

    public void reload() {
        clearSessionCache();
        activeProvider().reset();
        authAttempted = false;
        detectCurrentServer();
        authenticateIfNeeded();
        queueWorldPlayersIfAutomaticLookupsAllowed();
        drainQueueSoon();
    }

    public void clearCache() {
        clearSessionCache();
        diskCache.clear();
    }

    public void clearSessionCache() {
        cache.clear();
        queued.clear();
        pending.clear();
        lastRequested.clear();
        recentChatSpeakers.clear();
        lobbyChatStatsLookups.clear();
        visibleNamesByUuid.clear();
        bedwarsGamePlayers.clear();
        bedwarsStats.clear();
        nextDrainAt = 0L;
        bedwarsGameStarted = false;
        bedwarsOverviewSent = false;
        bedwarsOverviewAttempts = 0;
        bedwarsExpectedPlayers = 0;
    }

    public void queuePlayer(EntityPlayer player) {
        if (player == null || !hypixel || isHypixelNpc(player)) {
            return;
        }

        queueUuid(player.getUniqueID(), player.getDisplayName() == null ? player.getName() : player.getDisplayName().getFormattedText());
    }

    public void queuePlayer(UUID uuid) {
        queueUuid(uuid, null);
    }

    public void queuePlayer(UUID uuid, String visibleName) {
        queueUuid(uuid, visibleName);
    }

    private void queueUuid(UUID uuid, String visibleName) {
        if (uuid == null) {
            return;
        }
        boolean visibleNameChanged = rememberVisibleName(uuid, visibleName, null);

        String mode = cacheMode();
        if (shouldBlockAutomaticBedwarsLookup()) {
            networkFetchStatus = "bedwars-waiting";
            return;
        }

        if (cache.containsKey(cacheKey(mode, uuid))) {
            return;
        }

        LevelTag cachedTag = getCachedDiskTag(mode, uuid, !activeProvider().isReady());
        if (cachedTag != null) {
            cache.put(cacheKey(mode, uuid), cachedTag);
            return;
        }

        Long lastAttempt = lastRequested.get(uuid);
        long now = System.currentTimeMillis();
        if (lastAttempt != null && now - lastAttempt < requestCooldownMillis() && !visibleNameChanged) {
            return;
        }

        if (queued.putIfAbsent(uuid, Boolean.TRUE) != null) {
            return;
        }

        pending.add(uuid);
        drainQueueSoon();
    }

    public boolean isSelf(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && player.getUniqueID() != null && player.getUniqueID().equals(mc.thePlayer.getUniqueID());
    }

    public boolean isHypixelNpc(EntityPlayer player) {
        if (player == null) {
            return false;
        }

        return isHypixelNpcName(player.getName())
                || player.getDisplayName() != null && isHypixelNpcDisplayName(player.getDisplayName().getFormattedText());
    }

    public static boolean isHypixelNpcName(String name) {
        return name != null && HYPIXEL_NPC_NAME.matcher(name).matches();
    }

    public static boolean isHypixelNpcDisplayName(String displayName) {
        return displayName != null && displayName.replaceAll("\u00a7.", "").startsWith("[NPC]");
    }

    public void sendChat(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    public void requestChatStats(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            sendChat(EnumChatFormatting.RED + "Usage: /atwlh stats <player>");
            return;
        }

        PlayerTarget target = resolvePlayer(playerName.trim(), false);
        if (target == null) {
            sendChat(EnumChatFormatting.RED + "Couldn't find a recent chat speaker or tab player matching '" + playerName.trim() + "'.");
            return;
        }

        sendChat(EnumChatFormatting.GRAY + "Loading stats for " + target.name + "...");
        executor.execute(() -> sendChatLinesOnClientThread(formatStatsLines(target)));
    }

    public void requestRecentChatStats() {
        List<PlayerTarget> targets = recentChatTargets();
        if (targets.isEmpty()) {
            sendChat(EnumChatFormatting.YELLOW + "No players have spoken in chat in the last 2 minutes.");
            return;
        }

        sendChat(EnumChatFormatting.GRAY + "Loading stats for " + targets.size() + " recent chat player" + (targets.size() == 1 ? "..." : "s..."));
        executor.execute(() -> {
            for (PlayerTarget target : targets) {
                sendChatLinesOnClientThread(formatStatsLines(target));
            }
        });
    }

    private void onServerConnect(ServerConnectEvent event) {
        bedwarsGameStarted = false;
        hypixel = looksLikeHypixel(event.getIp());
        log("Server connect: " + event.getIp() + ":" + event.getPort() + ", hypixel=" + hypixel);
        if (hypixel) {
            authenticateIfNeeded();
        }
    }

    private void onWorldLoad(WorldEvent.Load event) {
        clearSessionCache();
        detectCurrentServer();
        if (hypixel) {
            authenticateIfNeeded();
            queueWorldPlayersIfAutomaticLookupsAllowed();
        }
    }

    private void onWorldUnload(WorldEvent.Unload event) {
        clearSessionCache();
    }

    private void onEntityAdd(EntityListEvent.Add event) {
        Entity entity = event.getEntity();
        if (entity instanceof EntityPlayer) {
            queuePlayer((EntityPlayer) entity);
        }
    }

    private void onPacketSend(PacketEvent.Send event) {
        Packet<?> packet = event.getPacket();
        if (!(packet instanceof C01PacketChatMessage)) {
            return;
        }

        String message = ((C01PacketChatMessage) packet).getMessage();
        if (message == null || !message.startsWith("/")) {
            return;
        }

        ChatSentEvent chatSentEvent = new ChatSentEvent(message);
        EventBus.callEvent(chatSentEvent);
        if (chatSentEvent.isCancelled()) {
            event.setCancelled(true);
            log("Cancelled outgoing command packet after Weave handled: " + commandName(message));
        }
    }

    private void onChatReceived(ChatReceivedEvent event) {
        if (!hypixel || event.getMessage() == null) {
            return;
        }

        String message = stripFormatting(event.getMessage().getFormattedText());
        updateBedwarsJoinCount(message);
        updateBedwarsStartState(message);
        java.util.regex.Matcher matcher = CHAT_SPEAKER.matcher(message);
        if (!matcher.matches()) {
            return;
        }

        String speakerName = matcher.group(1);
        PlayerTarget target = resolveExactPlayer(speakerName);
        if (target == null) {
            target = new PlayerTarget(speakerName, null);
        }
        recentChatSpeakers.put(target.name.toLowerCase(Locale.ROOT), new RecentSpeaker(target, System.currentTimeMillis()));
        maybeAutoLookupLobbyChatStats(target);
    }

    private void maybeAutoLookupLobbyChatStats(PlayerTarget target) {
        if (target == null || target.name == null || !isBedwarsWaitingForStart()) {
            return;
        }

        String key = target.name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long lastLookup = lobbyChatStatsLookups.get(key);
        if (lastLookup != null && now - lastLookup < LOBBY_CHAT_STATS_COOLDOWN_MILLIS) {
            return;
        }

        lobbyChatStatsLookups.put(key, now);
        executor.execute(() -> sendChatLinesOnClientThread(formatStatsLines(target)));
    }

    private void updateBedwarsJoinCount(String message) {
        if (!config.isBedwarsMode() || message == null) {
            return;
        }

        java.util.regex.Matcher matcher = BEDWARS_JOIN_COUNT.matcher(message.trim());
        if (!matcher.matches()) {
            return;
        }

        try {
            bedwarsExpectedPlayers = Integer.parseInt(matcher.group(2));
        } catch (Exception ignored) {
        }
    }

    private void updateBedwarsStartState(String message) {
        if (!config.isBedwarsMode() || message == null) {
            return;
        }

        String clean = message.trim();
        String normalized = clean.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.matches("THE GAME STARTS IN \\d+ SECONDS?!")) {
            bedwarsGameStarted = false;
            bedwarsOverviewSent = false;
            bedwarsOverviewAttempts = 0;
            bedwarsGamePlayers.clear();
            bedwarsStats.clear();
            clearFetchQueue();
            networkFetchStatus = "bedwars-waiting";
            return;
        }

        if (isBedwarsStartMessage(normalized)) {
            onBedwarsGameStarted();
        }
    }

    private boolean isBedwarsStartMessage(String normalizedMessage) {
        return "BED WARS".equals(normalizedMessage)
                || normalizedMessage.contains("PROTECT YOUR BED")
                || normalizedMessage.contains("DESTROY THE ENEMY BEDS")
                || normalizedMessage.contains("CROSS-TEAMING IS NOT ALLOWED");
    }

    private void onBedwarsGameStarted() {
        if (bedwarsGameStarted) {
            return;
        }

        bedwarsGameStarted = true;
        bedwarsOverviewSent = false;
        bedwarsOverviewAttempts = 0;
        bedwarsGamePlayers.clear();
        bedwarsStats.clear();
        clearFetchQueue();
        networkFetchStatus = "bedwars-starting";
        int queuedPlayers = queueTabPlayers();
        queueWorldPlayers();
        log("Detected BedWars game start; queued " + queuedPlayers + " tab player" + (queuedPlayers == 1 ? "" : "s") + " for BedWars stats.");
        nextDrainAt = 0L;
        drainQueueSoon();
    }

    private void detectCurrentServer() {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData data = mc.getCurrentServerData();
        hypixel = data != null && looksLikeHypixel(data.serverIP);
    }

    private boolean looksLikeHypixel(String ip) {
        if (ip == null) {
            return false;
        }
        String normalized = ip.toLowerCase(Locale.ROOT);
        return normalized.contains("hypixel.net") || normalized.contains("hypixel.io");
    }

    private String commandName(String message) {
        String withoutSlash = message.length() > 1 ? message.substring(1) : "";
        int space = withoutSlash.indexOf(' ');
        return space == -1 ? withoutSlash : withoutSlash.substring(0, space);
    }

    private void queueWorldPlayers() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }

        for (Object object : mc.theWorld.playerEntities) {
            if (object instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) object;
                rememberBedwarsGamePlayer(player);
                queuePlayer(player);
            }
        }
    }

    private void queueWorldPlayersIfAutomaticLookupsAllowed() {
        if (shouldBlockAutomaticBedwarsLookup()) {
            networkFetchStatus = "bedwars-waiting";
            return;
        }
        queueWorldPlayers();
    }

    private int queueTabPlayers() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) {
            return 0;
        }

        int queuedPlayers = 0;
        for (Object object : mc.getNetHandler().getPlayerInfoMap()) {
            if (!(object instanceof net.minecraft.client.network.NetworkPlayerInfo)) {
                continue;
            }

            NetworkPlayerInfo info = (NetworkPlayerInfo) object;
            if (info.getGameProfile() == null || info.getGameProfile().getId() == null) {
                continue;
            }

            String playerName = info.getGameProfile().getName();
            if (playerName == null || isHypixelNpcName(playerName)) {
                continue;
            }

            bedwarsGamePlayers.merge(info.getGameProfile().getId(), BedwarsGamePlayer.from(info), BedwarsGamePlayer::preferKnownTeam);
            queueUuid(info.getGameProfile().getId(), displayNameFor(info));
            queuedPlayers++;
        }
        return queuedPlayers;
    }

    private boolean rememberVisibleName(UUID uuid, String visibleName, String fallbackName) {
        if (uuid == null) {
            return false;
        }

        String normalized = extractPlayerName(visibleName, fallbackName);
        if (normalized == null || normalized.trim().isEmpty()) {
            return false;
        }

        String previous = visibleNamesByUuid.put(uuid, normalized);
        boolean changed = previous == null || !previous.equalsIgnoreCase(normalized);
        if (changed) {
            lastRequested.remove(uuid);
        }
        return changed;
    }

    private String displayNameFor(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null) {
            return "";
        }
        if (info.getDisplayName() != null) {
            return info.getDisplayName().getFormattedText();
        }
        return ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), info.getGameProfile().getName());
    }

    private void clearFetchQueue() {
        queued.clear();
        pending.clear();
        lastRequested.clear();
        nextDrainAt = 0L;
    }

    private void authenticateIfNeeded() {
        if (config.isBedwarsMode()) {
            return;
        }

        if (authAttempted || sk1erProvider.isAuthenticated()) {
            return;
        }

        authAttempted = true;
        executor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                sk1erProvider.authenticate();
                log("Sk1er auth completed in " + (System.currentTimeMillis() - startedAt) + "ms.");
                drainQueueSoon();
            } catch (Exception exception) {
                sk1erProvider.fail("Auth exception: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                exception.printStackTrace();
                scheduleDrainAfter(TimeUnit.SECONDS.toMillis(2));
            }
        });
    }

    private void validateHypixelApiKeySoon() {
        if (!config.isBedwarsMode() || hypixelApiValidationStarted) {
            return;
        }

        hypixelApiValidationStarted = true;
        scheduler.schedule(() -> executor.execute(() -> {
            String warning = bedwarsProvider.validateApiKeyWarning();
            if (warning != null && !hypixelApiWarningShown) {
                hypixelApiWarningShown = true;
                sendChatOnClientThread(warning);
            }
        }), 1500L, TimeUnit.MILLISECONDS);
    }

    private void drainQueueSoon() {
        long now = System.currentTimeMillis();
        if (now < nextDrainAt) {
            return;
        }

        nextDrainAt = now + TimeUnit.SECONDS.toMillis(1);
        executor.execute(this::drainQueue);
    }

    private void drainQueue() {
        if (!hypixel) {
            networkFetchStatus = "not-hypixel";
            return;
        }
        if (shouldBlockAutomaticBedwarsLookup()) {
            networkFetchStatus = "bedwars-waiting";
            return;
        }

        authenticateIfNeeded();
        String mode = cacheMode();
        LevelheadProvider provider = activeProvider();
        if (!provider.isReady()) {
            networkFetchStatus = "provider-not-ready";
            scheduleDrainAfter(TimeUnit.SECONDS.toMillis(2));
            return;
        }

        List<UUID> batch = new ArrayList<>();
        List<UUID> fetchBatch = new ArrayList<>();
        UUID uuid;
        while (batch.size() < fetchBatchSize() && (uuid = pending.poll()) != null) {
            batch.add(uuid);
            queued.remove(uuid);
            LevelTag cachedTag = getCachedDiskTag(mode, uuid, false);
            if (cachedTag != null) {
                cache.put(cacheKey(mode, uuid), cachedTag);
                rememberBedwarsStatsFromTag(mode, uuid, cachedTag);
            } else {
                fetchBatch.add(uuid);
                lastRequested.put(uuid, System.currentTimeMillis());
            }
        }

        if (batch.isEmpty()) {
            networkFetchStatus = "idle";
            return;
        }

        if (fetchBatch.isEmpty()) {
            networkFetchStatus = "cache-only";
            log("Served " + batch.size() + " LevelHead entr" + (batch.size() == 1 ? "y" : "ies") + " from disk cache.");
            maybeSendBedwarsOverview();
            scheduleNextDrainIfNeeded();
            return;
        }

        try {
            networkFetchStatus = "fetching:" + fetchBatch.size();
            long startedAt = System.currentTimeMillis();
            java.util.Map<UUID, LevelTag> fetched;
            if (mode.startsWith("bedwars") && provider == bedwarsProvider) {
                BedwarsFetchResult result = bedwarsProvider.fetchBedwars(fetchNames(fetchBatch));
                fetched = result.getTags();
                int realStats = 0;
                for (java.util.Map.Entry<UUID, BedwarsPlayerStats> entry : result.getStats().entrySet()) {
                    bedwarsStats.put(entry.getKey(), entry.getValue());
                    if (!entry.getValue().isNickedEstimate()) {
                        realStats++;
                    }
                }
                log("BedWars batch stats: real=" + realStats
                        + ", nickedEstimate=" + (result.getStats().size() - realStats)
                        + ", requested=" + fetchBatch.size() + ".");
            } else {
                fetched = provider.fetch(fetchBatch);
            }
            for (java.util.Map.Entry<UUID, LevelTag> entry : fetched.entrySet()) {
                if (!isUncacheableBedwarsTag(mode, entry.getValue())) {
                    cache.put(cacheKey(mode, entry.getKey()), entry.getValue());
                }
            }
            diskCache.putAll(mode, cacheableTags(mode, fetched));
            networkFetchStatus = "ok:" + fetched.size() + "/" + fetchBatch.size();
            log("Fetched " + fetched.size() + "/" + fetchBatch.size() + " LevelHead entr"
                    + (fetchBatch.size() == 1 ? "y" : "ies")
                    + " in " + (System.currentTimeMillis() - startedAt) + "ms.");
            maybeSendBedwarsOverview();
        } catch (Exception exception) {
            networkFetchStatus = "exception:" + exception.getClass().getSimpleName();
            log("Fetch exception: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            exception.printStackTrace();
        }

        scheduleNextDrainIfNeeded();
    }

    private void scheduleNextDrainIfNeeded() {
        if (pending.isEmpty()) {
            return;
        }

        long delay = drainIntervalMillis();
        scheduleDrainAfter(delay);
    }

    private java.util.Map<UUID, LevelTag> cacheableTags(String mode, java.util.Map<UUID, LevelTag> fetched) {
        if (mode == null || !mode.startsWith("bedwars")) {
            return fetched;
        }

        java.util.Map<UUID, LevelTag> cacheable = new HashMap<>();
        for (java.util.Map.Entry<UUID, LevelTag> entry : fetched.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isNicked()) {
                cacheable.put(entry.getKey(), entry.getValue());
            }
        }
        return cacheable;
    }

    private java.util.Map<UUID, String> fetchNames(List<UUID> uuids) {
        java.util.Map<UUID, String> names = new HashMap<>();
        for (UUID uuid : uuids) {
            String name = visibleNamesByUuid.get(uuid);
            if (name != null && !name.trim().isEmpty()) {
                names.put(uuid, name);
            }
        }
        return names;
    }

    private boolean isUncacheableBedwarsTag(String mode, LevelTag tag) {
        return mode != null && mode.startsWith("bedwars") && tag != null && tag.isNicked();
    }

    private LevelTag getCachedDiskTag(UUID uuid, boolean allowStale) {
        return getCachedDiskTag(cacheMode(), uuid, allowStale);
    }

    private LevelTag getCachedDiskTag(String mode, UUID uuid, boolean allowStale) {
        LevelTag cachedTag = diskCache.getFresh(mode, uuid);
        if (cachedTag == null && allowStale) {
            cachedTag = diskCache.getAny(mode, uuid);
        }
        if (cachedTag != null && mode != null && mode.startsWith("bedwars") && cachedTag.isNicked()) {
            return null;
        }
        return cachedTag;
    }

    private List<String> formatStatsLines(PlayerTarget target) {
        return target.uuid == null
                ? bedwarsProvider.fetchDetailedStatsLines(target.name)
                : bedwarsProvider.fetchDetailedStatsLines(target.uuid, target.name);
    }

    private LevelTag getOrFetchLevelTag(String mode, UUID uuid, LevelheadProvider provider) {
        String key = cacheKey(mode, uuid);
        LevelTag tag = cache.get(key);
        if (tag != null) {
            return tag;
        }

        tag = diskCache.getFresh(mode, uuid);
        if (tag != null) {
            cache.put(key, tag);
            return tag;
        }

        try {
            if ("level".equals(mode) && !sk1erProvider.isAuthenticated()) {
                sk1erProvider.authenticate();
            }
            if (!provider.isReady()) {
                return diskCache.getAny(mode, uuid);
            }

            java.util.Map<UUID, LevelTag> fetched = provider.fetch(java.util.Collections.singletonList(uuid));
            diskCache.putAll(mode, fetched);
            tag = fetched.get(uuid);
            if (tag != null) {
                cache.put(key, tag);
                return tag;
            }
        } catch (Exception exception) {
            log("Chat stats fetch failed for " + mode + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        return diskCache.getAny(mode, uuid);
    }

    private void rememberBedwarsStatsFromTag(String mode, UUID uuid, LevelTag tag) {
        if (mode == null || !mode.startsWith("bedwars")) {
            return;
        }

        BedwarsGamePlayer player = bedwarsGamePlayers.get(uuid);
        String name = player == null ? null : player.name;
        BedwarsPlayerStats stats = BedwarsPlayerStats.fromTag(uuid, name, tag);
        if (stats != null) {
            bedwarsStats.put(uuid, stats);
        }
    }

    private void maybeSendBedwarsOverview() {
        if (!bedwarsGameStarted || bedwarsOverviewSent || !pending.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        mc.addScheduledTask(() -> {
            if (bedwarsOverviewSent) {
                return;
            }

            refreshBedwarsGamePlayers();
            int realStats = realStatsCount();
            int desiredRealStats = bedwarsExpectedPlayers > 0 ? Math.min(3, bedwarsExpectedPlayers) : 3;
            if ((realStats < desiredRealStats || knownTeamCount() < 2) && bedwarsOverviewAttempts < 12) {
                bedwarsOverviewAttempts++;
                scheduler.schedule(() -> executor.execute(this::maybeSendBedwarsOverview), 1500L, TimeUnit.MILLISECONDS);
                return;
            }
            if (realStats == 0) {
                log("Skipping BedWars threat overview because every fetched player still looks nicked/obfuscated.");
                return;
            }

            List<String> lines = buildBedwarsOverviewLines();
            if (lines.isEmpty()) {
                return;
            }

            bedwarsOverviewSent = true;
            for (String message : lines) {
                sendChat(message);
            }
        });
    }

    private List<String> buildBedwarsOverviewLines() {
        if (bedwarsStats.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        Map<String, TeamThreat> teams = new HashMap<>();
        for (BedwarsPlayerStats stats : bedwarsStats.values()) {
            BedwarsGamePlayer player = bedwarsGamePlayers.get(stats.getUuid());
            if (stats.isNickedEstimate() && (player == null || player.isUnknownTeam())) {
                continue;
            }
            String key = player == null || player.isUnknownTeam() ? "unknown:" + stats.getUuid() : player.teamKey;
            TeamThreat team = teams.get(key);
            if (team == null) {
                boolean unknownTeam = player == null || player.isUnknownTeam();
                String fallbackName = stats.getName() == null ? "Unknown" : stats.getName();
                team = new TeamThreat(unknownTeam ? fallbackName : player.teamName,
                        player == null ? EnumChatFormatting.WHITE : player.teamColor);
                teams.put(key, team);
            }
            team.add(stats);
        }

        List<TeamThreat> ranked = new ArrayList<>(teams.values());
        java.util.Collections.sort(ranked, (left, right) -> Double.compare(right.averageThreat(), left.averageThreat()));
        int limit = Math.min(3, ranked.size());
        if (limit == 0) {
            return java.util.Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        int totalPlayers = bedwarsExpectedPlayers > 0 ? bedwarsExpectedPlayers : Math.max(bedwarsStats.size(), participantCount());
        lines.add(EnumChatFormatting.AQUA + "ATW LevelHead Overview"
                + EnumChatFormatting.GRAY + " (" + bedwarsStats.size() + "/" + totalPlayers + " players)");
        for (int index = 0; index < limit; index++) {
            TeamThreat team = ranked.get(index);
            BedwarsPlayerStats top = team.topPlayer;
            String topName = top.isNickedEstimate() ? "NICKED" : top.getName() == null ? "unknown" : top.getName();
            String topDetail = top.isNickedEstimate()
                    ? BedwarsStatFormatter.formatThreat(top.threatScore()) + EnumChatFormatting.GRAY + "/10 estimate"
                    : BedwarsStatFormatter.formatThreat(top.threatScore()) + EnumChatFormatting.GRAY + "/10, " + formatTwo(top.getFkdr()) + " FKDR, " + top.getStar() + "\u272b";
            lines.add(EnumChatFormatting.YELLOW + "#" + (index + 1) + " "
                    + team.color + team.name
                    + EnumChatFormatting.GRAY + " (" + team.playerCount + "p)"
                    + EnumChatFormatting.GRAY + " | Threat: " + BedwarsStatFormatter.formatThreat(team.averageThreat()) + EnumChatFormatting.GRAY + "/10"
                    + EnumChatFormatting.GRAY + " | Avg FKDR: " + EnumChatFormatting.WHITE + formatTwo(team.averageFkdr())
                    + EnumChatFormatting.GRAY + " | Top: " + EnumChatFormatting.WHITE + topName
                    + EnumChatFormatting.GRAY + " " + topDetail);
        }
        return lines;
    }

    private void refreshBedwarsGamePlayers() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            for (Object object : mc.theWorld.playerEntities) {
                if (object instanceof EntityPlayer) {
                    rememberBedwarsGamePlayer((EntityPlayer) object);
                }
            }
        }

        if (mc.getNetHandler() != null) {
            for (Object object : mc.getNetHandler().getPlayerInfoMap()) {
                if (!(object instanceof NetworkPlayerInfo)) {
                    continue;
                }

                NetworkPlayerInfo info = (NetworkPlayerInfo) object;
                if (info.getGameProfile() == null || info.getGameProfile().getId() == null) {
                    continue;
                }

                String playerName = info.getGameProfile().getName();
                if (playerName == null || isHypixelNpcName(playerName)) {
                    continue;
                }

                bedwarsGamePlayers.merge(info.getGameProfile().getId(), BedwarsGamePlayer.from(info), BedwarsGamePlayer::preferKnownTeam);
            }
        }
    }

    private void rememberBedwarsGamePlayer(EntityPlayer player) {
        if (player == null || player.getUniqueID() == null || isHypixelNpc(player)) {
            return;
        }

        String formattedName = player.getDisplayName() == null ? "" : player.getDisplayName().getFormattedText();
        rememberVisibleName(player.getUniqueID(), formattedName, player.getName());
        BedwarsGamePlayer livePlayer = BedwarsGamePlayer.from(player.getName(), formattedName);
        bedwarsGamePlayers.merge(player.getUniqueID(), livePlayer, BedwarsGamePlayer::preferKnownTeam);
    }

    private int knownTeamCount() {
        java.util.HashSet<String> knownTeams = new java.util.HashSet<>();
        for (BedwarsPlayerStats stats : bedwarsStats.values()) {
            BedwarsGamePlayer player = bedwarsGamePlayers.get(stats.getUuid());
            if (player != null && !player.isUnknownTeam()) {
                knownTeams.add(player.teamKey);
            }
        }
        return knownTeams.size();
    }

    private int participantCount() {
        int count = 0;
        for (BedwarsGamePlayer player : bedwarsGamePlayers.values()) {
            if (!player.isUnknownTeam()) {
                count++;
            }
        }
        return count == 0 ? bedwarsGamePlayers.size() : count;
    }

    private int realStatsCount() {
        int count = 0;
        for (BedwarsPlayerStats stats : bedwarsStats.values()) {
            if (!stats.isNickedEstimate()) {
                count++;
            }
        }
        return count;
    }

    private void sendChatOnClientThread(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> sendChat(message));
    }

    private void sendChatLinesOnClientThread(List<String> messages) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || messages == null || messages.isEmpty()) {
            return;
        }
        mc.addScheduledTask(() -> {
            for (String message : messages) {
                sendChat(message);
            }
        });
    }

    private PlayerTarget resolvePlayer(String name, boolean recentOnly) {
        purgeOldRecentChatSpeakers();
        List<PlayerTarget> matches = new ArrayList<>();
        String normalized = name.toLowerCase(Locale.ROOT);
        for (RecentSpeaker speaker : recentChatSpeakers.values()) {
            String speakerName = speaker.target.name.toLowerCase(Locale.ROOT);
            if (speakerName.equals(normalized) || speakerName.startsWith(normalized)) {
                matches.add(speaker.target);
            }
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            sendChat(EnumChatFormatting.YELLOW + "Multiple recent matches: " + joinNames(matches));
            return null;
        }
        if (recentOnly) {
            return null;
        }

        PlayerTarget exact = resolveExactPlayer(name);
        if (exact != null) {
            return exact;
        }

        return resolveTabPlayer(name);
    }

    private PlayerTarget resolveExactPlayer(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) {
            return null;
        }

        net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(name);
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null) {
            return null;
        }
        if (isHypixelNpcName(info.getGameProfile().getName())) {
            return null;
        }
        return new PlayerTarget(info.getGameProfile().getName(), info.getGameProfile().getId());
    }

    private PlayerTarget resolveTabPlayer(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null || name == null) {
            return null;
        }

        String normalized = name.toLowerCase(Locale.ROOT);
        List<PlayerTarget> matches = new ArrayList<>();
        for (Object object : mc.getNetHandler().getPlayerInfoMap()) {
            if (!(object instanceof net.minecraft.client.network.NetworkPlayerInfo)) {
                continue;
            }

            net.minecraft.client.network.NetworkPlayerInfo info = (net.minecraft.client.network.NetworkPlayerInfo) object;
            if (info.getGameProfile() == null || info.getGameProfile().getId() == null) {
                continue;
            }

            String playerName = info.getGameProfile().getName();
            if (playerName == null || isHypixelNpcName(playerName)) {
                continue;
            }

            String tabName = playerName.toLowerCase(Locale.ROOT);
            if (tabName.equals(normalized) || tabName.startsWith(normalized)) {
                matches.add(new PlayerTarget(playerName, info.getGameProfile().getId()));
            }
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            sendChat(EnumChatFormatting.YELLOW + "Multiple tab matches: " + joinNames(matches));
        }
        return null;
    }

    private List<PlayerTarget> recentChatTargets() {
        purgeOldRecentChatSpeakers();
        List<RecentSpeaker> speakers = new ArrayList<>(recentChatSpeakers.values());
        java.util.Collections.sort(speakers, (left, right) -> Long.compare(right.lastSeenMillis, left.lastSeenMillis));
        List<PlayerTarget> targets = new ArrayList<>();
        for (RecentSpeaker speaker : speakers) {
            targets.add(speaker.target);
        }
        return targets;
    }

    private void purgeOldRecentChatSpeakers() {
        long cutoff = System.currentTimeMillis() - RECENT_CHAT_WINDOW_MILLIS;
        for (Map.Entry<String, RecentSpeaker> entry : recentChatSpeakers.entrySet()) {
            if (entry.getValue().lastSeenMillis < cutoff) {
                recentChatSpeakers.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7.", "");
    }

    private static String extractPlayerName(String visibleName, String fallbackName) {
        String stripped = stripFormatting(visibleName).replaceAll("[^A-Za-z0-9_]+", " ").trim();
        String best = null;
        java.util.regex.Matcher matcher = PLAYER_NAME_TOKEN.matcher(stripped);
        while (matcher.find()) {
            String token = matcher.group();
            if (isUsablePlayerName(token)) {
                best = token;
            }
        }
        if (best != null) {
            return best;
        }
        return isUsablePlayerName(fallbackName) ? fallbackName : null;
    }

    private static boolean isUsablePlayerName(String name) {
        return name != null
                && PLAYER_NAME_TOKEN.matcher(name).matches()
                && !isHypixelNpcName(name)
                && !"NICKED".equalsIgnoreCase(name)
                && !"FKDR".equalsIgnoreCase(name)
                && !"Threat".equalsIgnoreCase(name);
    }

    private static String formatTwo(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatOne(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatWhole(double value) {
        return String.format(Locale.US, "%.0f", value);
    }

    private String joinNames(List<PlayerTarget> targets) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < targets.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(targets.get(index).name);
        }
        return builder.toString();
    }

    private boolean isBedwarsStatsActive() {
        return hypixel && config.isBedwarsMode() && bedwarsGameStarted;
    }

    private boolean isBedwarsWaitingForStart() {
        return hypixel && config.isBedwarsMode()
                && !bedwarsGameStarted
                && HypixelContextDetector.isBedwarsWaitingRoom(hypixel);
    }

    private boolean shouldBlockAutomaticBedwarsLookup() {
        return hypixel && config.isBedwarsMode() && !bedwarsGameStarted;
    }

    private String cacheMode() {
        return "bedwars".equals(displayMode()) ? bedwarsCacheMode() : "level";
    }

    private String bedwarsCacheMode() {
        return "bedwars-v5";
    }

    private static String cacheKey(String mode, UUID uuid) {
        return mode + ":" + uuid.toString();
    }

    private int fetchBatchSize() {
        return LEVEL_FETCH_BATCH_SIZE;
    }

    private long requestCooldownMillis() {
        return LEVEL_REQUEST_COOLDOWN_MILLIS;
    }

    private long drainIntervalMillis() {
        return LEVEL_DRAIN_INTERVAL_MILLIS;
    }

    private void scheduleDrainAfter(long delayMillis) {
        if (pending.isEmpty()) {
            return;
        }

        nextDrainAt = System.currentTimeMillis() + delayMillis;
        scheduler.schedule(() -> executor.execute(this::drainQueue), delayMillis, TimeUnit.MILLISECONDS);
    }

    public static void log(String message) {
        System.out.println("[ATW LevelHead] " + message);
    }

    private LevelheadProvider activeProvider() {
        return providerForMode(displayMode());
    }

    private LevelheadProvider providerForMode(String mode) {
        return mode != null && mode.startsWith("bedwars") ? bedwarsProvider : sk1erProvider;
    }

    private static class PlayerTarget {
        private final String name;
        private final UUID uuid;

        private PlayerTarget(String name, UUID uuid) {
            this.name = name;
            this.uuid = uuid;
        }
    }

    private static class RecentSpeaker {
        private final PlayerTarget target;
        private final long lastSeenMillis;

        private RecentSpeaker(PlayerTarget target, long lastSeenMillis) {
            this.target = target;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private static class BedwarsGamePlayer {
        private final String name;
        private final String teamKey;
        private final String teamName;
        private final EnumChatFormatting teamColor;

        private BedwarsGamePlayer(String name, String teamKey, String teamName, EnumChatFormatting teamColor) {
            this.name = name;
            this.teamKey = teamKey;
            this.teamName = teamName;
            this.teamColor = teamColor;
        }

        private static BedwarsGamePlayer from(NetworkPlayerInfo info) {
            String name = info.getGameProfile().getName();
            ScorePlayerTeam team = info.getPlayerTeam();
            EnumChatFormatting color = team == null ? null : team.getChatFormat();
            if (color == null || !color.isColor()) {
                color = firstColor(info.getDisplayName() == null ? "" : info.getDisplayName().getFormattedText());
            }

            String teamName = colorTeamName(color);
            String teamKey = teamName.toLowerCase(Locale.ROOT);
            if ("unknown".equals(teamKey) && team != null && team.getRegisteredName() != null) {
                teamKey = team.getRegisteredName().toLowerCase(Locale.ROOT);
                teamName = readableTeamName(team.getRegisteredName());
            }
            return new BedwarsGamePlayer(name, teamKey, teamName, color == null ? EnumChatFormatting.WHITE : color);
        }

        private static BedwarsGamePlayer from(String name, String formattedName) {
            EnumChatFormatting color = firstColor(formattedName);
            String teamName = colorTeamName(color);
            return new BedwarsGamePlayer(name, teamName.toLowerCase(Locale.ROOT), teamName, color == null ? EnumChatFormatting.WHITE : color);
        }

        private static BedwarsGamePlayer preferKnownTeam(BedwarsGamePlayer existing, BedwarsGamePlayer replacement) {
            if (existing == null) {
                return replacement;
            }
            if (replacement == null) {
                return existing;
            }
            if (existing.isUnknownTeam() && !replacement.isUnknownTeam()) {
                return replacement;
            }
            return existing;
        }

        private boolean isUnknownTeam() {
            return teamKey == null || "unknown".equals(teamKey);
        }

        private static EnumChatFormatting firstColor(String formattedText) {
            if (formattedText == null) {
                return null;
            }
            for (int index = 0; index < formattedText.length() - 1; index++) {
                if (formattedText.charAt(index) != '\u00a7') {
                    continue;
                }
                EnumChatFormatting formatting = colorByCode(formattedText.charAt(index + 1));
                if (formatting != null && formatting.isColor()) {
                    return formatting;
                }
            }
            return null;
        }

        private static EnumChatFormatting colorByCode(char code) {
            switch (Character.toLowerCase(code)) {
                case '0':
                    return EnumChatFormatting.BLACK;
                case '1':
                    return EnumChatFormatting.DARK_BLUE;
                case '2':
                    return EnumChatFormatting.DARK_GREEN;
                case '3':
                    return EnumChatFormatting.DARK_AQUA;
                case '4':
                    return EnumChatFormatting.DARK_RED;
                case '5':
                    return EnumChatFormatting.DARK_PURPLE;
                case '6':
                    return EnumChatFormatting.GOLD;
                case '7':
                    return EnumChatFormatting.GRAY;
                case '8':
                    return EnumChatFormatting.DARK_GRAY;
                case '9':
                    return EnumChatFormatting.BLUE;
                case 'a':
                    return EnumChatFormatting.GREEN;
                case 'b':
                    return EnumChatFormatting.AQUA;
                case 'c':
                    return EnumChatFormatting.RED;
                case 'd':
                    return EnumChatFormatting.LIGHT_PURPLE;
                case 'e':
                    return EnumChatFormatting.YELLOW;
                case 'f':
                    return EnumChatFormatting.WHITE;
                default:
                    return null;
            }
        }

        private static String colorTeamName(EnumChatFormatting color) {
            if (color == EnumChatFormatting.RED || color == EnumChatFormatting.DARK_RED) {
                return "Red";
            } else if (color == EnumChatFormatting.BLUE || color == EnumChatFormatting.DARK_BLUE) {
                return "Blue";
            } else if (color == EnumChatFormatting.GREEN || color == EnumChatFormatting.DARK_GREEN) {
                return "Green";
            } else if (color == EnumChatFormatting.YELLOW || color == EnumChatFormatting.GOLD) {
                return "Yellow";
            } else if (color == EnumChatFormatting.AQUA || color == EnumChatFormatting.DARK_AQUA) {
                return "Aqua";
            } else if (color == EnumChatFormatting.WHITE) {
                return "White";
            } else if (color == EnumChatFormatting.LIGHT_PURPLE || color == EnumChatFormatting.DARK_PURPLE) {
                return "Pink";
            } else if (color == EnumChatFormatting.GRAY || color == EnumChatFormatting.DARK_GRAY) {
                return "Gray";
            }
            return "Unknown";
        }

        private static String readableTeamName(String value) {
            if (value == null || value.trim().isEmpty()) {
                return "Unknown";
            }
            String clean = value.replaceAll("^[0-9_\\-]+", "").replace('_', ' ').replace('-', ' ').trim();
            if (clean.isEmpty()) {
                return value;
            }
            return clean.substring(0, 1).toUpperCase(Locale.ROOT) + clean.substring(1);
        }
    }

    private static class TeamThreat {
        private final String name;
        private final EnumChatFormatting color;
        private int playerCount;
        private double threatScore;
        private double totalFkdr;
        private BedwarsPlayerStats topPlayer;

        private TeamThreat(String name, EnumChatFormatting color) {
            this.name = name;
            this.color = color;
        }

        private void add(BedwarsPlayerStats stats) {
            playerCount++;
            threatScore += stats.threatScore();
            totalFkdr += stats.getFkdr();
            if (topPlayer == null || stats.threatScore() > topPlayer.threatScore()) {
                topPlayer = stats;
            }
        }

        private double averageThreat() {
            return playerCount <= 0 ? 0.0D : threatScore / playerCount;
        }

        private double averageFkdr() {
            return playerCount <= 0 ? 0.0D : totalFkdr / playerCount;
        }
    }
}
