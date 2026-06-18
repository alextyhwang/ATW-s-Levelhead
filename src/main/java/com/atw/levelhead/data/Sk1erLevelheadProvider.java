package com.atw.levelhead.data;

import com.atw.levelhead.ATWLevelHead;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Sk1erLevelheadProvider implements LevelheadProvider {
    private static final String MOD_ID = "level_head";
    private static final String LEVELHEAD_VERSION = "8.2.3";
    private static final long RATE_LIMIT_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final HttpJsonClient http = new HttpJsonClient("Mozilla/4.76 (SK1ER LEVEL HEAD V" + LEVELHEAD_VERSION + ")");
    private final JsonParser parser = new JsonParser();

    private volatile boolean authenticated;
    private volatile String hash = "";
    private volatile String accessKey = "";
    private volatile String status = "not-authenticated";
    private volatile long throttledUntilMillis;

    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public boolean isReady() {
        return authenticated && !isThrottled();
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public void reset() {
        authenticated = false;
        hash = "";
        accessKey = "";
        status = "not-authenticated";
        throttledUntilMillis = 0L;
    }

    public void fail(String message) {
        authenticated = false;
        status = message == null ? "failed" : message;
        ATWLevelHead.log(status);
    }

    public void authenticate() throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        Session session = mc.getSession();
        UUID uuid = session.getProfile().getId();

        String beginUrl = "https://api.sk1er.club/auth/begin?uuid=" + uuid
                + "&mod=" + MOD_ID
                + "&ver=" + LEVELHEAD_VERSION;
        JsonObject begin = parseObject(http.get(beginUrl));
        if (!begin.has("success") || !begin.get("success").getAsBoolean()) {
            fail("Auth begin failed: " + begin);
            return;
        }

        hash = begin.get("hash").getAsString();
        int joinStatus = joinServer(session.getToken(), uuid.toString().replace("-", ""), hash);
        if (joinStatus != 204) {
            fail("Mojang session join failed: HTTP " + joinStatus);
            return;
        }

        JsonObject finished = parseObject(http.get(
                "https://api.sk1er.club/auth/final?hash=" + hash
                        + "&name=" + HttpJsonClient.urlEncode(session.getUsername())
        ));
        if (finished.has("success") && finished.get("success").getAsBoolean()) {
            accessKey = finished.has("access_key") ? finished.get("access_key").getAsString() : "";
            authenticated = true;
            status = "authenticated";
            ATWLevelHead.log("Authenticated with Sk1er Levelhead API.");
        } else {
            fail("Auth final failed: " + finished);
        }
    }

    @Override
    public Map<UUID, LevelTag> fetch(List<UUID> uuids) throws Exception {
        Map<UUID, LevelTag> tags = new HashMap<>();
        if (!authenticated || uuids.isEmpty() || isThrottled()) {
            return tags;
        }

        Minecraft mc = Minecraft.getMinecraft();
        String selfUuid = mc.getSession().getProfile().getId().toString().replace("-", "");
        JsonObject request = new JsonObject();
        JsonArray requests = new JsonArray();
        for (UUID uuid : uuids) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", uuid.toString().replace("-", ""));
            item.addProperty("display", "head1");
            item.addProperty("allowOverride", true);
            item.addProperty("type", "LEVEL");
            requests.add(item);
        }
        request.add("requests", requests);

        JsonObject response = parseObject(http.postJson(
                "https://api.sk1er.club/levelheadv8?auth=" + hash + "&uuid=" + selfUuid,
                request.toString()
        ));
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            if (isRateLimitResponse(response)) {
                throttledUntilMillis = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MILLIS;
                status = "rate-limited:" + retryDelayMillis() / 1000L + "s";
                ATWLevelHead.log("Sk1er Levelhead rate limited; retrying queued players in "
                        + Math.max(1L, retryDelayMillis() / 1000L) + "s.");
                return tags;
            }
            fail("Fetch failed: " + response);
            return tags;
        }

        JsonArray results = response.getAsJsonArray("results");
        if (results == null) {
            return tags;
        }

        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            UUID uuid = parseUuid(result.get("uuid").getAsString());
            if (isUnknownNickResult(result)) {
                tags.put(uuid, LevelTag.nicked(uuid));
                continue;
            }

            String header = result.has("headerString") ? result.get("headerString").getAsString() + ": " : "Level: ";
            String footer = result.has("footerString") && !sameString(result, "footerString", "value")
                    ? result.get("footerString").getAsString()
                    : result.has("value") ? result.get("value").getAsString() : "?";
            tags.put(uuid, new LevelTag(uuid, header, footer));
        }
        status = "ok";
        return tags;
    }

    public boolean isThrottled() {
        return throttledUntilMillis > System.currentTimeMillis();
    }

    public long retryDelayMillis() {
        return Math.max(0L, throttledUntilMillis - System.currentTimeMillis());
    }

    private int joinServer(String token, String uuid, String serverHash) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", token);
        body.addProperty("selectedProfile", uuid);
        body.addProperty("serverId", serverHash);
        return http.postForStatus("https://sessionserver.mojang.com/session/minecraft/join", body.toString());
    }

    private JsonObject parseObject(String body) {
        return parser.parse(body).getAsJsonObject();
    }

    private static boolean sameString(JsonObject object, String left, String right) {
        return object.has(left) && object.has(right) && object.get(left).getAsString().equals(object.get(right).getAsString());
    }

    private static boolean isUnknownNickValue(String value) {
        if (value == null) {
            return false;
        }

        String stripped = value.replaceAll("\u00a7.", "").trim();
        return stripped.length() >= 3 && stripped.matches("\\?+");
    }

    private static boolean isUnknownNickResult(JsonObject result) {
        return getUnknownNickField(result, "headerString")
                || getUnknownNickField(result, "footerString")
                || getUnknownNickField(result, "value");
    }

    private static boolean getUnknownNickField(JsonObject result, String field) {
        return result.has(field) && !result.get(field).isJsonNull() && isUnknownNickValue(result.get(field).getAsString());
    }

    private static boolean isRateLimitResponse(JsonObject response) {
        if (response == null || !response.has("cause") || response.get("cause").isJsonNull()) {
            return false;
        }
        return response.get("cause").getAsString().toLowerCase().contains("too many requests");
    }

    private static UUID parseUuid(String uuid) {
        String normalized = uuid.replace("-", "");
        if (normalized.length() == 32) {
            normalized = normalized.substring(0, 8) + "-"
                    + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        return UUID.fromString(normalized);
    }
}
