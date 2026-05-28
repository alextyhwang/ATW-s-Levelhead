package com.atw.levelhead.data;

import com.atw.levelhead.ATWLevelHead;
import com.atw.levelhead.config.LevelHeadConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.EnumChatFormatting;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class HypixelBedwarsProvider implements LevelheadProvider {
    private static final String API_KEY_ENV = "ATW_LEVELHEAD_HYPIXEL_API_KEY";
    private static final String API_KEY_PROPERTY = "atw.levelhead.hypixelApiKey";
    private static final String LOCAL_API_KEY_RESOURCE = "/atw-levelhead-local.properties";
    private static final long THROTTLE_RETRY_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final DecimalFormat RATIO_FORMAT = new DecimalFormat("0.00");

    private final HttpJsonClient http = new HttpJsonClient("ATWLevelHead/0.2.0");
    private final JsonParser parser = new JsonParser();
    private final LevelHeadConfig config;
    private volatile String apiKey;

    private volatile String status = "ready";
    private volatile long throttledUntilMillis;

    public HypixelBedwarsProvider(LevelHeadConfig config) {
        this.config = config;
        apiKey = loadApiKey(config);
    }

    public void useConfiguredApiKey() {
        apiKey = config == null ? "" : config.getHypixelApiKey().trim();
        status = isReady() ? "ready" : "missing-hypixel-api-key";
        throttledUntilMillis = 0L;
    }

    @Override
    public boolean isReady() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String getStatus() {
        return isReady() ? status : "missing-hypixel-api-key";
    }

    @Override
    public void reset() {
        status = "ready";
        throttledUntilMillis = 0L;
    }

    public String validateApiKeyWarning() {
        if (!isReady()) {
            status = "missing-hypixel-api-key";
            return EnumChatFormatting.RED + "Hypixel API key is missing. BedWars stats will not load.";
        }

        try {
            fetchPlayer(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
            status = "ok";
            return null;
        } catch (HypixelThrottleException exception) {
            status = "hypixel-throttled:" + exception.getMessage();
            return null;
        } catch (HypixelApiException exception) {
            String cause = exception.getMessage() == null ? "unknown" : exception.getMessage();
            status = "hypixel-failed:" + cause;
            return EnumChatFormatting.RED + "Hypixel API key looks invalid: "
                    + EnumChatFormatting.GRAY + cause
                    + EnumChatFormatting.RED + ". Update the key in atw-levelhead.json.";
        } catch (Exception exception) {
            status = "hypixel-validation-error:" + exception.getClass().getSimpleName();
            ATWLevelHead.log("Hypixel API key validation failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return null;
        }
    }

    @Override
    public Map<UUID, LevelTag> fetch(List<UUID> uuids) throws Exception {
        return fetchBedwars(uuids).getTags();
    }

    public BedwarsFetchResult fetchBedwars(List<UUID> uuids) throws Exception {
        Map<UUID, String> namesByUuid = new HashMap<>();
        for (UUID uuid : uuids) {
            namesByUuid.put(uuid, "");
        }
        return fetchBedwars(namesByUuid);
    }

    public BedwarsFetchResult fetchBedwars(Map<UUID, String> namesByUuid) throws Exception {
        Map<UUID, LevelTag> tags = new HashMap<>();
        Map<UUID, BedwarsPlayerStats> stats = new HashMap<>();
        if (!isReady()) {
            status = "missing-hypixel-api-key";
            return new BedwarsFetchResult(tags, stats);
        }
        if (isThrottled()) {
            status = "hypixel-throttled-wait:" + Math.max(1L, (throttledUntilMillis - System.currentTimeMillis()) / 1000L) + "s";
            return new BedwarsFetchResult(tags, stats);
        }

        boolean hadError = false;
        for (Map.Entry<UUID, String> request : namesByUuid.entrySet()) {
            UUID uuid = request.getKey();
            try {
                BedwarsPlayerStats playerStats = fetchOneStats(uuid, uuid);
                if (playerStats == null && isUsablePlayerName(request.getValue())) {
                    playerStats = fetchOneStatsByName(uuid, request.getValue());
                }
                if (playerStats == null) {
                    tags.put(uuid, LevelTag.nicked(uuid));
                    stats.put(uuid, BedwarsPlayerStats.nicked(uuid, fallbackName(request.getValue())));
                } else {
                    stats.put(uuid, playerStats);
                    tags.put(uuid, playerStats.toLevelTag());
                }
            } catch (HypixelThrottleException exception) {
                hadError = true;
                throttledUntilMillis = System.currentTimeMillis() + THROTTLE_RETRY_DELAY_MILLIS;
                status = "hypixel-throttled:" + exception.getMessage();
                ATWLevelHead.log(status + "; skipping remaining BedWars API requests in this batch.");
                break;
            } catch (Exception exception) {
                hadError = true;
                status = "hypixel-error:" + exception.getClass().getSimpleName();
                ATWLevelHead.log("BedWars fetch failed for one player: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }

        if (!hadError) {
            status = "ok";
        }
        return new BedwarsFetchResult(tags, stats);
    }

    private boolean isThrottled() {
        return throttledUntilMillis > System.currentTimeMillis();
    }

    private BedwarsPlayerStats fetchOneStats(UUID ownerUuid, UUID lookupUuid) throws Exception {
        JsonObject player = fetchPlayer(lookupUuid);
        if (player == null) {
            return null;
        }

        return statsFromPlayer(ownerUuid, player, lookupUuid.toString());
    }

    private BedwarsPlayerStats fetchOneStatsByName(UUID ownerUuid, String playerName) throws Exception {
        JsonObject player = fetchPlayer(playerName);
        if (player == null) {
            return null;
        }

        return statsFromPlayer(ownerUuid, player, playerName);
    }

    private BedwarsPlayerStats statsFromPlayer(UUID ownerUuid, JsonObject player, String fallbackName) {
        String name = getString(player, "displayname", fallbackName);
        int star = getInt(path(player, "achievements"), "bedwars_level");
        JsonObject bedwars = path(player, "stats", "Bedwars");
        int finalKills = getInt(bedwars, "final_kills_bedwars");
        int finalDeaths = getInt(bedwars, "final_deaths_bedwars");
        return new BedwarsPlayerStats(ownerUuid, name, star, finalKills, finalDeaths);
    }

    public String fetchDetailedStats(UUID uuid, String fallbackName) {
        return String.join(" ", fetchDetailedStatsLines(uuid, fallbackName));
    }

    public List<String> fetchDetailedStatsLines(UUID uuid, String fallbackName) {
        if (!isReady()) {
            return Collections.singletonList(EnumChatFormatting.RED + "Hypixel API key is missing.");
        }
        if (uuid == null) {
            return fetchDetailedStatsLines(fallbackName);
        }

        try {
            JsonObject player = fetchPlayer(uuid);
            if (player == null) {
                BedwarsPlayerStats nicked = BedwarsPlayerStats.nicked(uuid, fallbackName);
                return Collections.singletonList(EnumChatFormatting.YELLOW + fallbackName
                        + EnumChatFormatting.GRAY + " appears to be nicked or unavailable. "
                        + EnumChatFormatting.GRAY + "Threat " + BedwarsStatFormatter.formatThreat(nicked.threatScore()) + EnumChatFormatting.GRAY + "/10 estimate");
            }

            String name = getString(player, "displayname", fallbackName == null ? uuid.toString() : fallbackName);
            JsonObject bedwars = path(player, "stats", "Bedwars");
            int star = getInt(path(player, "achievements"), "bedwars_level");

            int finalKills = getInt(bedwars, "final_kills_bedwars");
            int finalDeaths = getInt(bedwars, "final_deaths_bedwars");
            int wins = getInt(bedwars, "wins_bedwars");
            int losses = getInt(bedwars, "losses_bedwars");
            int bedsBroken = getInt(bedwars, "beds_broken_bedwars");
            int bedsLost = getInt(bedwars, "beds_lost_bedwars");
            int winstreak = getInt(bedwars, "winstreak");
            BedwarsPlayerStats threatStats = new BedwarsPlayerStats(uuid, name, star, finalKills, finalDeaths);

            List<String> lines = new ArrayList<>();
            lines.add(EnumChatFormatting.DARK_GRAY + "---------------- " + EnumChatFormatting.AQUA + name
                    + EnumChatFormatting.DARK_GRAY + " ----------------");
            lines.add(EnumChatFormatting.GRAY + "BedWars " + BedwarsStatFormatter.formatStars(star)
                    + EnumChatFormatting.DARK_GRAY + " | " + EnumChatFormatting.GRAY + "WS " + EnumChatFormatting.WHITE + winstreak);
            lines.add(EnumChatFormatting.GRAY + "Threat " + BedwarsStatFormatter.formatThreat(threatStats.threatScore()) + EnumChatFormatting.GRAY + "/10"
                    + EnumChatFormatting.DARK_GRAY + " | " + EnumChatFormatting.GRAY + "FKDR " + BedwarsStatFormatter.formatFkdr(finalKills, finalDeaths)
                    + EnumChatFormatting.DARK_GRAY + " (" + EnumChatFormatting.GREEN + finalKills
                    + EnumChatFormatting.DARK_GRAY + "/" + EnumChatFormatting.RED + finalDeaths + EnumChatFormatting.DARK_GRAY + ")");
            lines.add(EnumChatFormatting.GRAY + "WLR "
                    + EnumChatFormatting.WHITE + ratio(wins, losses)
                    + EnumChatFormatting.DARK_GRAY + " (" + EnumChatFormatting.GREEN + wins
                    + EnumChatFormatting.DARK_GRAY + "/" + EnumChatFormatting.RED + losses + EnumChatFormatting.DARK_GRAY + ")");
            lines.add(EnumChatFormatting.GRAY + "BBLR " + EnumChatFormatting.WHITE + ratio(bedsBroken, bedsLost)
                    + EnumChatFormatting.DARK_GRAY + " (" + EnumChatFormatting.GREEN + bedsBroken
                    + EnumChatFormatting.DARK_GRAY + "/" + EnumChatFormatting.RED + bedsLost + EnumChatFormatting.DARK_GRAY + ")");
            return lines;
        } catch (HypixelThrottleException exception) {
            throttledUntilMillis = System.currentTimeMillis() + THROTTLE_RETRY_DELAY_MILLIS;
            status = "hypixel-throttled:" + exception.getMessage();
            return Collections.singletonList(EnumChatFormatting.RED + "Hypixel API throttled: " + exception.getMessage());
        } catch (HypixelApiException exception) {
            status = "hypixel-failed:" + exception.getMessage();
            return Collections.singletonList(EnumChatFormatting.RED + "Hypixel API failed: " + exception.getMessage());
        } catch (Exception exception) {
            status = "hypixel-error:" + exception.getClass().getSimpleName();
            ATWLevelHead.log("Detailed BedWars stats fetch failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return Collections.singletonList(EnumChatFormatting.RED + "Failed to fetch stats: " + exception.getClass().getSimpleName());
        }
    }

    public String fetchDetailedStats(String playerName) {
        return String.join(" ", fetchDetailedStatsLines(playerName));
    }

    public List<String> fetchDetailedStatsLines(String playerName) {
        if (!isReady()) {
            return Collections.singletonList(EnumChatFormatting.RED + "Hypixel API key is missing.");
        }
        if (playerName == null || playerName.trim().isEmpty()) {
            return Collections.singletonList(EnumChatFormatting.RED + "Missing player name.");
        }

        try {
            UUID uuid = fetchMojangUuid(playerName.trim());
            if (uuid == null) {
                return Collections.singletonList(EnumChatFormatting.YELLOW + playerName + EnumChatFormatting.GRAY + " could not be resolved to a Minecraft account.");
            }
            return fetchDetailedStatsLines(uuid, playerName.trim());
        } catch (Exception exception) {
            status = "mojang-error:" + exception.getClass().getSimpleName();
            ATWLevelHead.log("Mojang profile lookup failed for " + playerName + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return Collections.singletonList(EnumChatFormatting.RED + "Failed to resolve " + playerName + ": " + exception.getClass().getSimpleName());
        }
    }

    private JsonObject fetchPlayer(UUID uuid) throws Exception {
        String body = http.get(
                "https://api.hypixel.net/v2/player?uuid=" + uuid.toString().replace("-", ""),
                "API-Key",
                apiKey
        );
        return parsePlayerResponse(body);
    }

    private JsonObject fetchPlayer(String playerName) throws Exception {
        String body = http.get(
                "https://api.hypixel.net/v2/player?name=" + HttpJsonClient.urlEncode(playerName),
                "API-Key",
                apiKey
        );
        return parsePlayerResponse(body);
    }

    private JsonObject parsePlayerResponse(String body) throws Exception {
        JsonObject root = parser.parse(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            String cause = getString(root, "cause", "unknown");
            status = "hypixel-failed:" + cause;
            ATWLevelHead.log(status);
            if (isThrottle(root)) {
                throw new HypixelThrottleException(cause);
            }
            throw new HypixelApiException(cause);
        }
        if (!root.has("player") || root.get("player").isJsonNull()) {
            return null;
        }

        return root.getAsJsonObject("player");
    }

    private static boolean isUsablePlayerName(String name) {
        return name != null
                && name.matches("[A-Za-z0-9_]{3,16}")
                && !ATWLevelHead.isHypixelNpcName(name)
                && !"NICKED".equalsIgnoreCase(name);
    }

    private static String fallbackName(String name) {
        return isUsablePlayerName(name) ? name : "Nicked";
    }

    private UUID fetchMojangUuid(String playerName) throws Exception {
        String body = http.get("https://api.mojang.com/users/profiles/minecraft/" + HttpJsonClient.urlEncode(playerName));
        JsonObject root = parser.parse(body).getAsJsonObject();
        if (!root.has("id") || root.get("id").isJsonNull()) {
            return null;
        }
        return parseUuid(root.get("id").getAsString());
    }

    private static JsonObject path(JsonObject object, String first, String second) {
        JsonObject child = path(object, first);
        if (child == null || !child.has(second) || !child.get(second).isJsonObject()) {
            return null;
        }
        return child.getAsJsonObject(second);
    }

    private static JsonObject path(JsonObject object, String first) {
        if (object == null || !object.has(first) || !object.get(first).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(first);
    }

    private static int getInt(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(field).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double getDouble(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return 0.0D;
        }
        try {
            return object.get(field).getAsDouble();
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static String ratio(int positive, int negative) {
        double value = negative <= 0 ? positive : positive / (double) negative;
        return RATIO_FORMAT.format(value);
    }

    private static double networkLevel(double experience) {
        if (experience <= 0.0D) {
            return 1.0D;
        }
        return 1.0D - 3.5D + Math.sqrt(12.25D + 0.0008D * experience) / 0.0008D;
    }

    private static boolean isThrottle(JsonObject root) {
        if (root.has("throttle") && !root.get("throttle").isJsonNull()) {
            try {
                if (root.get("throttle").getAsBoolean()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        String cause = getString(root, "cause", "").toLowerCase();
        return cause.contains("throttle") || cause.contains("rate") || cause.contains("429");
    }

    private static String getString(JsonObject object, String field, String fallback) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String loadApiKey(LevelHeadConfig config) {
        String propertyValue = System.getProperty(API_KEY_PROPERTY);
        if (propertyValue != null && !propertyValue.trim().isEmpty()) {
            return propertyValue.trim();
        }

        String envValue = System.getenv(API_KEY_ENV);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim();
        }

        if (config != null && !config.getHypixelApiKey().trim().isEmpty()) {
            return config.getHypixelApiKey().trim();
        }

        return loadBundledApiKey();
    }

    private static String loadBundledApiKey() {
        try (InputStream inputStream = HypixelBedwarsProvider.class.getResourceAsStream(LOCAL_API_KEY_RESOURCE)) {
            if (inputStream == null) {
                return "";
            }

            Properties properties = new Properties();
            properties.load(inputStream);
            String value = properties.getProperty("hypixelApiKey", "");
            return value == null ? "" : value.trim();
        } catch (Exception exception) {
            ATWLevelHead.log("Failed to load bundled Hypixel API key: " + exception.getMessage());
            return "";
        }
    }

    private static UUID parseUuid(String uuid) {
        String normalized = uuid == null ? "" : uuid.replace("-", "");
        if (normalized.length() != 32) {
            return null;
        }
        normalized = normalized.substring(0, 8) + "-"
                + normalized.substring(8, 12) + "-"
                + normalized.substring(12, 16) + "-"
                + normalized.substring(16, 20) + "-"
                + normalized.substring(20);
        return UUID.fromString(normalized);
    }

    private static class HypixelThrottleException extends Exception {
        private HypixelThrottleException(String message) {
            super(message);
        }
    }

    private static class HypixelApiException extends Exception {
        private HypixelApiException(String message) {
            super(message);
        }
    }
}
