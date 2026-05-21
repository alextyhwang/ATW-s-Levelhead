package com.atw.levelhead.data;

import java.text.DecimalFormat;

public final class BedwarsStatFormatter {
    private static final DecimalFormat FKDR_FORMAT = new DecimalFormat("0.00");

    private static final String DARK_GREEN = "\u00a72";
    private static final String DARK_AQUA = "\u00a73";
    private static final String DARK_RED = "\u00a74";
    private static final String DARK_PURPLE = "\u00a75";
    private static final String GOLD = "\u00a76";
    private static final String GRAY = "\u00a77";
    private static final String DARK_GRAY = "\u00a78";
    private static final String BLUE = "\u00a79";
    private static final String GREEN = "\u00a7a";
    private static final String AQUA = "\u00a7b";
    private static final String RED = "\u00a7c";
    private static final String LIGHT_PURPLE = "\u00a7d";
    private static final String YELLOW = "\u00a7e";
    private static final String WHITE = "\u00a7f";

    private static final String[] RAINBOW_COLORS = {
            RED, GOLD, YELLOW, GREEN, AQUA, LIGHT_PURPLE, DARK_PURPLE
    };
    private static final String[] PRESTIGE_COLORS = {
            WHITE, YELLOW, AQUA, GREEN, DARK_AQUA, RED, LIGHT_PURPLE, BLUE, DARK_PURPLE, DARK_GRAY
    };

    private BedwarsStatFormatter() {
    }

    public static String formatStars(int star) {
        String starString = Integer.toString(star);
        if (star < 1000) {
            return starColor(star) + starString + "\u272b";
        }

        String[] colors = colorsForHighPrestige(star);
        String starIcon = star < 2000 ? "\u272a" : "\u269d";
        String first = starString.substring(0, 1);
        String rest = starString.length() > 1 ? starString.substring(1) : "";
        int colorAmount = star < 1100 ? 7 : star < 2000 ? 5 : 6;

        return colors[4 % colorAmount] + first + colors[3 % colorAmount] + rest + starIcon;
    }

    public static String formatFkdr(int finalKills, int finalDeaths) {
        double fkdr = finalDeaths <= 0 ? finalKills : finalKills / (double) finalDeaths;
        String formatted = FKDR_FORMAT.format(fkdr);
        return fkdrColor(fkdr) + formatted;
    }

    private static String starColor(int star) {
        if (star < 100) {
            return GRAY;
        } else if (star < 200) {
            return WHITE;
        } else if (star < 300) {
            return GOLD;
        } else if (star < 400) {
            return AQUA;
        } else if (star < 500) {
            return DARK_GREEN;
        } else if (star < 600) {
            return DARK_AQUA;
        } else if (star < 700) {
            return DARK_RED;
        } else if (star < 800) {
            return LIGHT_PURPLE;
        } else if (star < 900) {
            return BLUE;
        }
        return DARK_PURPLE;
    }

    private static String fkdrColor(double fkdr) {
        if (fkdr < 1.5D) {
            return GRAY;
        } else if (fkdr < 3.5D) {
            return WHITE;
        } else if (fkdr < 5.0D) {
            return GOLD;
        } else if (fkdr < 10.0D) {
            return DARK_GREEN;
        } else if (fkdr < 20.0D) {
            return RED;
        } else if (fkdr < 50.0D) {
            return DARK_RED;
        } else if (fkdr < 100.0D) {
            return LIGHT_PURPLE;
        }
        return DARK_PURPLE;
    }

    private static String[] colorsForHighPrestige(int star) {
        String[] colors = RAINBOW_COLORS.clone();
        if (star < 1100) {
            fill(colors, WHITE, 0, 4);
        } else if (star < 2000) {
            fill(colors, highPrestigeBaseColor(star), 0, 4);
        } else if (star < 2100) {
            copy(PRESTIGE_COLORS, colors, 0, 0, 6);
        } else if (star < 2200) {
            fill(colors, WHITE, 0, 2);
            fill(colors, YELLOW, 2, 4);
        } else if (star < 2300) {
            copy(PRESTIGE_COLORS, colors, 0, 0, 5);
        } else if (star < 2400) {
            copy(PRESTIGE_COLORS, colors, 0, 1, 6);
        } else if (star < 2500) {
            fill(colors, AQUA, 0, 2);
            fill(colors, WHITE, 2, 4);
        } else if (star < 2600) {
            fill(colors, WHITE, 0, 2);
            fill(colors, GREEN, 2, 4);
        } else if (star < 2700) {
            copy(PRESTIGE_COLORS, colors, 0, 2, 7);
        } else if (star < 2800) {
            fill(colors, YELLOW, 0, 2);
            fill(colors, WHITE, 2, 4);
        } else if (star < 2900) {
            copy(PRESTIGE_COLORS, colors, 0, 3, 8);
        } else if (star < 3000) {
            copy(PRESTIGE_COLORS, colors, 0, 4, 9);
        } else {
            copy(PRESTIGE_COLORS, colors, 0, 5, 10);
        }
        return colors;
    }

    private static String highPrestigeBaseColor(int star) {
        if (star < 1200) {
            return WHITE;
        } else if (star < 1300) {
            return YELLOW;
        } else if (star < 1400) {
            return AQUA;
        } else if (star < 1500) {
            return GREEN;
        } else if (star < 1600) {
            return DARK_AQUA;
        } else if (star < 1700) {
            return RED;
        } else if (star < 1800) {
            return LIGHT_PURPLE;
        } else if (star < 1900) {
            return BLUE;
        }
        return DARK_PURPLE;
    }

    private static void fill(String[] colors, String color, int start, int end) {
        for (int i = start; i < end && i < colors.length; i++) {
            colors[i] = color;
        }
    }

    private static void copy(String[] source, String[] target, int targetStart, int sourceStart, int sourceEnd) {
        int targetIndex = targetStart;
        for (int sourceIndex = sourceStart; sourceIndex < sourceEnd && targetIndex < target.length; sourceIndex++) {
            target[targetIndex++] = source[sourceIndex];
        }
    }
}
