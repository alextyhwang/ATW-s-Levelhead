package com.atw.levelhead.config;

import com.atw.levelhead.ATWLevelHead;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class LevelHeadConfig {
    private static final JsonParser PARSER = new JsonParser();

    private boolean backgroundEnabled = false;
    private String displayMode = "bedwars";
    private String hypixelApiKey = "";

    public boolean isBackgroundEnabled() {
        return backgroundEnabled;
    }

    public void setBackgroundEnabled(boolean backgroundEnabled) {
        this.backgroundEnabled = backgroundEnabled;
    }

    public String getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(String displayMode) {
        this.displayMode = displayMode == null || displayMode.trim().isEmpty() ? "level" : displayMode;
    }

    public boolean isBedwarsMode() {
        return "bedwars".equalsIgnoreCase(displayMode) || "bw".equalsIgnoreCase(displayMode);
    }

    public String getHypixelApiKey() {
        return hypixelApiKey;
    }

    public void setHypixelApiKey(String hypixelApiKey) {
        this.hypixelApiKey = hypixelApiKey == null ? "" : hypixelApiKey.trim();
    }

    public static LevelHeadConfig load() {
        LevelHeadConfig config = new LevelHeadConfig();
        File file = configFile();
        if (!file.exists()) {
            config.save();
            return config;
        }

        try (FileInputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }

            JsonObject object = PARSER.parse(outputStream.toString(StandardCharsets.UTF_8.name())).getAsJsonObject();
            if (object.has("backgroundEnabled")) {
                config.backgroundEnabled = object.get("backgroundEnabled").getAsBoolean();
            }
            if (object.has("displayMode")) {
                config.displayMode = object.get("displayMode").getAsString();
            }
            if (object.has("hypixelApiKey")) {
                config.hypixelApiKey = object.get("hypixelApiKey").getAsString();
            }
        } catch (Exception exception) {
            ATWLevelHead.log("Failed to load config, using defaults: " + exception.getMessage());
        }

        return config;
    }

    public void save() {
        File file = configFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        JsonObject object = new JsonObject();
        object.addProperty("backgroundEnabled", backgroundEnabled);
        object.addProperty("displayMode", displayMode);
        object.addProperty("hypixelApiKey", hypixelApiKey);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(object.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            ATWLevelHead.log("Failed to save config: " + exception.getMessage());
        }
    }

    private static File configFile() {
        return new File(new File(System.getProperty("user.home"), ".weave"), "atw-levelhead.json");
    }
}
