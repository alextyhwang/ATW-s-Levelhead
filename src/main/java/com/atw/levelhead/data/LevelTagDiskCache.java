package com.atw.levelhead.data;

import com.atw.levelhead.ATWLevelHead;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LevelTagDiskCache {
    private static final JsonParser PARSER = new JsonParser();
    private static final long LEVEL_TTL_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long BEDWARS_TTL_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final File file;

    private LevelTagDiskCache(File file) {
        this.file = file;
    }

    public static LevelTagDiskCache load() {
        LevelTagDiskCache cache = new LevelTagDiskCache(cacheFile());
        cache.loadFromDisk();
        return cache;
    }

    public LevelTag getFresh(String mode, UUID uuid) {
        Entry entry = entries.get(key(mode, uuid));
        if (entry == null || isExpired(mode, entry.updatedAtMillis)) {
            return null;
        }

        return new LevelTag(uuid, entry.header, entry.footer);
    }

    public LevelTag getAny(String mode, UUID uuid) {
        Entry entry = entries.get(key(mode, uuid));
        if (entry == null) {
            return null;
        }

        return new LevelTag(uuid, entry.header, entry.footer);
    }

    public synchronized void putAll(String mode, Map<UUID, LevelTag> tags) {
        long now = System.currentTimeMillis();
        for (LevelTag tag : tags.values()) {
            if (tag.getOwner() != null) {
                entries.put(key(mode, tag.getOwner()), new Entry(tag.getHeader(), tag.getFooter(), now));
            }
        }
        save();
    }

    public synchronized void clear() {
        entries.clear();
        if (file.exists() && !file.delete()) {
            ATWLevelHead.log("Failed to delete LevelHead disk cache: " + file.getAbsolutePath());
        }
    }

    public int size() {
        return entries.size();
    }

    private synchronized void loadFromDisk() {
        if (!file.exists()) {
            return;
        }

        try (FileInputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }

            JsonObject root = PARSER.parse(outputStream.toString(StandardCharsets.UTF_8.name())).getAsJsonObject();
            if (!root.has("entries") || !root.get("entries").isJsonObject()) {
                return;
            }

            JsonObject storedEntries = root.getAsJsonObject("entries");
            for (Map.Entry<String, JsonElement> stored : storedEntries.entrySet()) {
                if (!stored.getValue().isJsonObject()) {
                    continue;
                }

                JsonObject object = stored.getValue().getAsJsonObject();
                String header = object.has("header") ? object.get("header").getAsString() : "";
                String footer = object.has("footer") ? object.get("footer").getAsString() : "";
                long updatedAtMillis = object.has("updatedAtMillis") ? object.get("updatedAtMillis").getAsLong() : 0L;
                entries.put(stored.getKey(), new Entry(header, footer, updatedAtMillis));
            }

            ATWLevelHead.log("Loaded " + entries.size() + " cached LevelHead entr" + (entries.size() == 1 ? "y." : "ies."));
        } catch (Exception exception) {
            ATWLevelHead.log("Failed to load LevelHead disk cache: " + exception.getMessage());
        }
    }

    private synchronized void save() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject storedEntries = new JsonObject();
        for (Map.Entry<String, Entry> stored : entries.entrySet()) {
            Entry entry = stored.getValue();
            JsonObject object = new JsonObject();
            object.addProperty("header", entry.header);
            object.addProperty("footer", entry.footer);
            object.addProperty("updatedAtMillis", entry.updatedAtMillis);
            storedEntries.add(stored.getKey(), object);
        }
        root.add("entries", storedEntries);

        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            ATWLevelHead.log("Failed to save LevelHead disk cache: " + exception.getMessage());
        }
    }

    private boolean isExpired(String mode, long updatedAtMillis) {
        String normalizedMode = mode == null ? "level" : mode.toLowerCase(Locale.ROOT);
        long ttl = normalizedMode.startsWith("bedwars") ? BEDWARS_TTL_MILLIS : LEVEL_TTL_MILLIS;
        return updatedAtMillis <= 0L || System.currentTimeMillis() - updatedAtMillis > ttl;
    }

    private static String key(String mode, UUID uuid) {
        String normalizedMode = mode == null ? "level" : mode.toLowerCase(Locale.ROOT);
        if ("bedwars".equals(normalizedMode)) {
            normalizedMode = "bedwars-v2";
        }
        return normalizedMode + ":" + uuid.toString();
    }

    private static File cacheFile() {
        return new File(new File(System.getProperty("user.home"), ".weave"), "atw-levelhead-cache.json");
    }

    private static class Entry {
        private final String header;
        private final String footer;
        private final long updatedAtMillis;

        private Entry(String header, String footer, long updatedAtMillis) {
            this.header = header;
            this.footer = footer;
            this.updatedAtMillis = updatedAtMillis;
        }
    }
}
