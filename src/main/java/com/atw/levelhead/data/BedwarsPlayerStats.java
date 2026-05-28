package com.atw.levelhead.data;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BedwarsPlayerStats {
    private static final Pattern FKDR_PATTERN = Pattern.compile("FKDR\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final double NICKED_THREAT_SCORE = 7.5D;

    private final UUID uuid;
    private final String name;
    private final int star;
    private final int finalKills;
    private final int finalDeaths;
    private final double fkdr;
    private final boolean nickedEstimate;

    public BedwarsPlayerStats(UUID uuid, String name, int star, int finalKills, int finalDeaths) {
        this(uuid, name, star, finalKills, finalDeaths, ratio(finalKills, finalDeaths), false);
    }

    public BedwarsPlayerStats(UUID uuid, String name, int star, int finalKills, int finalDeaths, double fkdr) {
        this(uuid, name, star, finalKills, finalDeaths, fkdr, false);
    }

    private BedwarsPlayerStats(UUID uuid, String name, int star, int finalKills, int finalDeaths, double fkdr, boolean nickedEstimate) {
        this.uuid = uuid;
        this.name = name;
        this.star = Math.max(0, star);
        this.finalKills = Math.max(0, finalKills);
        this.finalDeaths = Math.max(0, finalDeaths);
        this.fkdr = Math.max(0.0D, fkdr);
        this.nickedEstimate = nickedEstimate;
    }

    public static BedwarsPlayerStats nicked(UUID uuid, String name) {
        return new BedwarsPlayerStats(uuid, name, 0, 0, 0, 0.0D, true);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public int getStar() {
        return star;
    }

    public int getFinalKills() {
        return finalKills;
    }

    public int getFinalDeaths() {
        return finalDeaths;
    }

    public double getFkdr() {
        return fkdr;
    }

    public boolean isNickedEstimate() {
        return nickedEstimate;
    }

    public LevelTag toLevelTag() {
        String stars = BedwarsStatFormatter.formatStars(star);
        String lifetimeFkdr = BedwarsStatFormatter.formatFkdr(finalKills, finalDeaths);
        return new LevelTag(uuid, "", padVisibleRight(stars, 5)
                + "\u00a77| FKDR " + padVisibleLeft(lifetimeFkdr, 6));
    }

    public static BedwarsPlayerStats fromTag(UUID uuid, String name, LevelTag tag) {
        if (uuid == null || tag == null) {
            return null;
        }
        if (tag.isNicked()) {
            return nicked(uuid, name);
        }

        String text = stripFormatting(tag.getText()).trim();
        int star = parseLeadingInt(text);
        Matcher matcher = FKDR_PATTERN.matcher(text);
        if (star < 0 || !matcher.find()) {
            return null;
        }

        try {
            double fkdr = Double.parseDouble(matcher.group(1));
            return new BedwarsPlayerStats(uuid, name, star, 0, 0, fkdr);
        } catch (Exception ignored) {
            return null;
        }
    }

    public double threatScore() {
        if (nickedEstimate) {
            return NICKED_THREAT_SCORE;
        }

        double fkdrScore = 10.0D * (1.0D - Math.exp(-fkdr / 10.0D));
        double levelBonus = 4.0D / (1.0D + Math.exp(-(star - 350.0D) / 35.0D));
        return Math.min(10.0D, fkdrScore + levelBonus);
    }

    private static double ratio(int positive, int negative) {
        return negative <= 0 ? positive : positive / (double) negative;
    }

    private static int parseLeadingInt(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }

        int index = 0;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        if (index == 0) {
            return -1;
        }

        try {
            return Integer.parseInt(value.substring(0, index));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String padVisibleRight(String value, int width) {
        StringBuilder builder = new StringBuilder(value == null ? "" : value);
        while (visibleLength(builder.toString()) < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String padVisibleLeft(String value, int width) {
        StringBuilder builder = new StringBuilder(value == null ? "" : value);
        while (visibleLength(builder.toString()) < width) {
            builder.insert(0, ' ');
        }
        return builder.toString();
    }

    private static int visibleLength(String value) {
        return stripFormatting(value).length();
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7.", "");
    }
}
