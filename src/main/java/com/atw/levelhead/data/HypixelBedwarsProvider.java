package com.atw.levelhead.data;

import com.atw.levelhead.ATWLevelHead;
import com.atw.levelhead.config.LevelHeadConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HypixelBedwarsProvider implements LevelheadProvider {
    private static final String API_KEY = "9d91c41e-ea19-4df5-b95e-cc9fce1fae1a";

    private final HttpJsonClient http = new HttpJsonClient("ATWLevelHead/0.1.0");
    private final JsonParser parser = new JsonParser();

    private volatile String status = "ready";

    public HypixelBedwarsProvider(LevelHeadConfig config) {
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public void reset() {
        status = "ready";
    }

    @Override
    public Map<UUID, LevelTag> fetch(List<UUID> uuids) throws Exception {
        Map<UUID, LevelTag> tags = new HashMap<>();
        for (UUID uuid : uuids) {
            LevelTag tag = fetchOne(uuid);
            if (tag != null) {
                tags.put(uuid, tag);
            }
        }

        status = "ok";
        return tags;
    }

    private LevelTag fetchOne(UUID uuid) throws Exception {
        String body = http.get(
                "https://api.hypixel.net/v2/player?uuid=" + uuid.toString().replace("-", ""),
                "API-Key",
                API_KEY
        );
        JsonObject root = parser.parse(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            status = "hypixel-failed:" + root;
            ATWLevelHead.log(status);
            return null;
        }
        if (!root.has("player") || root.get("player").isJsonNull()) {
            return new LevelTag(uuid, "", "? | 0.00 FKDR");
        }

        JsonObject player = root.getAsJsonObject("player");
        int star = getInt(path(player, "achievements"), "bedwars_level");
        JsonObject bedwars = path(player, "stats", "Bedwars");
        int finalKills = getInt(bedwars, "final_kills_bedwars");
        int finalDeaths = getInt(bedwars, "final_deaths_bedwars");
        String stars = BedwarsStatFormatter.formatStars(star);
        String fkdr = BedwarsStatFormatter.formatFkdr(finalKills, finalDeaths);
        return new LevelTag(uuid, "", stars + " \u00a77| " + fkdr + " \u00a77FKDR");
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
}
