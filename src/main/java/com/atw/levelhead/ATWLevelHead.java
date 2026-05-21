package com.atw.levelhead;

import com.atw.levelhead.command.LevelHeadCommand;
import com.atw.levelhead.config.LevelHeadConfig;
import com.atw.levelhead.data.HypixelBedwarsProvider;
import com.atw.levelhead.data.LevelTagDiskCache;
import com.atw.levelhead.data.LevelTag;
import com.atw.levelhead.data.LevelheadProvider;
import com.atw.levelhead.data.Sk1erLevelheadProvider;
import com.atw.levelhead.render.AboveHeadRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.ModInitializer;
import net.weavemc.loader.api.command.CommandBus;
import net.weavemc.loader.api.event.ChatSentEvent;
import net.weavemc.loader.api.event.EntityListEvent;
import net.weavemc.loader.api.event.EventBus;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.RenderLivingEvent;
import net.weavemc.loader.api.event.ServerConnectEvent;
import net.weavemc.loader.api.event.WorldEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ATWLevelHead implements ModInitializer {
    public static final String PREFIX = EnumChatFormatting.AQUA + "[ATW LevelHead] " + EnumChatFormatting.RESET;

    private static ATWLevelHead instance;

    private final ConcurrentHashMap<UUID, LevelTag> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> queued = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRequested = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> pending = new ConcurrentLinkedQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ATW-LevelHead-Worker");
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
    private volatile long nextDrainAt;

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
    }

    public LevelTag getTag(UUID uuid) {
        return cache.get(uuid);
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

    public LevelHeadConfig getConfig() {
        return config;
    }

    public String displayMode() {
        return config.isBedwarsMode() ? "bedwars" : "level";
    }

    public void setDisplayMode(String mode) {
        String normalized = "bedwars".equalsIgnoreCase(mode) || "bw".equalsIgnoreCase(mode) ? "bedwars" : "level";
        config.setDisplayMode(normalized);
        config.save();
        reload();
    }

    public void reload() {
        clearSessionCache();
        activeProvider().reset();
        authAttempted = false;
        detectCurrentServer();
        authenticateIfNeeded();
        queueWorldPlayers();
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
        nextDrainAt = 0L;
    }

    public void queuePlayer(EntityPlayer player) {
        if (player == null || !hypixel || isSelf(player)) {
            return;
        }

        UUID uuid = player.getUniqueID();
        if (uuid == null || cache.containsKey(uuid)) {
            return;
        }

        LevelTag cachedTag = diskCache.getFresh(displayMode(), uuid);
        if (cachedTag != null) {
            cache.put(uuid, cachedTag);
            return;
        }

        Long lastAttempt = lastRequested.get(uuid);
        long now = System.currentTimeMillis();
        if (lastAttempt != null && now - lastAttempt < TimeUnit.SECONDS.toMillis(30)) {
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

    public void sendChat(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(PREFIX + message));
        }
    }

    private void onServerConnect(ServerConnectEvent event) {
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
            queueWorldPlayers();
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
                queuePlayer((EntityPlayer) object);
            }
        }
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
            try {
                sk1erProvider.authenticate();
            } catch (Exception exception) {
                sk1erProvider.fail("Auth exception: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                exception.printStackTrace();
            }
        });
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
            return;
        }

        authenticateIfNeeded();
        LevelheadProvider provider = activeProvider();
        if (!provider.isReady()) {
            nextDrainAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);
            return;
        }

        List<UUID> batch = new ArrayList<>();
        List<UUID> fetchBatch = new ArrayList<>();
        UUID uuid;
        while (batch.size() < 20 && (uuid = pending.poll()) != null) {
            batch.add(uuid);
            queued.remove(uuid);
            LevelTag cachedTag = diskCache.getFresh(displayMode(), uuid);
            if (cachedTag != null) {
                cache.put(uuid, cachedTag);
            } else {
                fetchBatch.add(uuid);
                lastRequested.put(uuid, System.currentTimeMillis());
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        if (fetchBatch.isEmpty()) {
            log("Served " + batch.size() + " LevelHead entr" + (batch.size() == 1 ? "y" : "ies") + " from disk cache.");
            scheduleNextDrainIfNeeded();
            return;
        }

        try {
            java.util.Map<UUID, LevelTag> fetched = provider.fetch(fetchBatch);
            cache.putAll(fetched);
            diskCache.putAll(displayMode(), fetched);
            log("Fetched " + fetched.size() + "/" + fetchBatch.size() + " LevelHead entr" + (fetchBatch.size() == 1 ? "y." : "ies."));
        } catch (Exception exception) {
            log("Fetch exception: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            exception.printStackTrace();
        }

        scheduleNextDrainIfNeeded();
    }

    private void scheduleNextDrainIfNeeded() {
        if (pending.isEmpty()) {
            return;
        }

        nextDrainAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1);
        executor.execute(() -> {
            sleep(1000L);
            drainQueue();
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public static void log(String message) {
        System.out.println("[ATW LevelHead] " + message);
    }

    private LevelheadProvider activeProvider() {
        return config.isBedwarsMode() ? bedwarsProvider : sk1erProvider;
    }
}
