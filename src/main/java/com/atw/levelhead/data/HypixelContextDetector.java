package com.atw.levelhead.data;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HypixelContextDetector {
    public enum Context {
        NOT_HYPIXEL,
        BEDWARS_LOBBY,
        BEDWARS_GAME,
        HYPIXEL_LOBBY,
        OTHER_HYPIXEL,
        UNKNOWN
    }

    public static Context detect(boolean hypixel) {
        if (!hypixel) {
            return Context.NOT_HYPIXEL;
        }

        Snapshot snapshot = snapshot();
        if (snapshot.title.isEmpty() && snapshot.lines.isEmpty()) {
            return Context.UNKNOWN;
        }

        String title = clean(snapshot.title);
        List<String> lines = cleanLines(snapshot.lines);
        boolean bedwarsTitle = title.contains("BED WARS") || title.contains("BEDWARS");
        boolean bedwarsLine = containsAny(lines, "BED WARS", "BEDWARS");

        if (bedwarsTitle || bedwarsLine) {
            if (containsAny(lines, "LOBBY:", "YOUR LEVEL", "PROGRESS:", "LOOT CHESTS", "QUEST MASTER", "CLICK TO PLAY")) {
                return Context.BEDWARS_LOBBY;
            }
            if (containsAny(lines, "KILLS:", "FINAL KILLS:", "BEDS BROKEN:", "DIAMOND", "EMERALD", "MAP:", "MODE:", "TEAMS:")) {
                return Context.BEDWARS_GAME;
            }
            return Context.BEDWARS_GAME;
        }

        if (containsAny(lines, "LOBBY:", "HYPIXEL LEVEL", "QUEST MASTER", "CLICK TO PLAY")) {
            return Context.HYPIXEL_LOBBY;
        }

        return Context.OTHER_HYPIXEL;
    }

    public static boolean isBedwarsWaitingRoom(boolean hypixel) {
        if (!hypixel) {
            return false;
        }

        Snapshot snapshot = snapshot();
        String title = clean(snapshot.title);
        List<String> lines = cleanLines(snapshot.lines);
        if (!isBedwars(title, lines)) {
            return false;
        }

        return !hasBedwarsInGameMarkers(lines)
                && containsAny(lines, "WAITING", "STARTING IN", "PLAYERS:", "MAP:", "MODE:", "TEAMS:");
    }

    public static boolean isBedwarsGameInProgress(boolean hypixel) {
        if (!hypixel) {
            return false;
        }

        Snapshot snapshot = snapshot();
        String title = clean(snapshot.title);
        List<String> lines = cleanLines(snapshot.lines);
        return isBedwars(title, lines) && hasBedwarsInGameMarkers(lines);
    }

    public static String summary(boolean hypixel) {
        Snapshot snapshot = snapshot();
        return "context=" + detect(hypixel)
                + ", sidebarTitle='" + clean(snapshot.title) + "'"
                + ", sidebarLines=" + cleanLines(snapshot.lines);
    }

    public static void logDebug(boolean hypixel) {
        Snapshot snapshot = snapshot();
        System.out.println("[ATW LevelHead] Context debug: hypixel=" + hypixel
                + ", detected=" + detect(hypixel)
                + ", rawTitle='" + snapshot.title + "'"
                + ", cleanTitle='" + clean(snapshot.title) + "'");
        for (int i = 0; i < snapshot.lines.size(); i++) {
            String line = snapshot.lines.get(i);
            System.out.println("[ATW LevelHead] Context debug line " + i + ": raw='" + line + "', clean='" + clean(line) + "'");
        }
    }

    public static Snapshot snapshot() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return new Snapshot("", Collections.<String>emptyList());
        }

        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return new Snapshot("", Collections.<String>emptyList());
        }

        List<Score> scores = new ArrayList<>();
        Collection<Score> sortedScores = scoreboard.getSortedScores(objective);
        for (Score score : sortedScores) {
            if (score != null && score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) {
                scores.add(score);
            }
        }

        if (scores.size() > 15) {
            scores = scores.subList(scores.size() - 15, scores.size());
        }

        Collections.sort(scores, new Comparator<Score>() {
            @Override
            public int compare(Score left, Score right) {
                return Integer.compare(right.getScorePoints(), left.getScorePoints());
            }
        });

        List<String> lines = new ArrayList<>();
        for (Score score : scores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            lines.add(ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()));
        }

        return new Snapshot(objective.getDisplayName(), lines);
    }

    private static List<String> cleanLines(List<String> lines) {
        List<String> cleanLines = new ArrayList<>();
        for (String line : lines) {
            cleanLines.add(clean(line));
        }
        return cleanLines;
    }

    private static boolean containsAny(List<String> lines, String... needles) {
        for (String line : lines) {
            for (String needle : needles) {
                if (line.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBedwars(String title, List<String> lines) {
        return title.contains("BED WARS") || title.contains("BEDWARS")
                || containsAny(lines, "BED WARS", "BEDWARS");
    }

    private static boolean hasBedwarsInGameMarkers(List<String> lines) {
        return containsAny(lines,
                "DIAMOND", "EMERALD",
                "RED:", "BLUE:", "GREEN:", "YELLOW:", "AQUA:", "WHITE:", "PINK:", "GRAY:",
                "KILLS:", "FINAL KILLS:", "BEDS BROKEN:");
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\u00a7.", "").trim().toUpperCase();
    }

    public static class Snapshot {
        public final String title;
        public final List<String> lines;

        private Snapshot(String title, List<String> lines) {
            this.title = title == null ? "" : title;
            this.lines = lines;
        }
    }
}
