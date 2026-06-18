package com.atw.levelhead.data;

import java.util.UUID;

public class LevelTag {
    private static final String NICKED_FOOTER = "&c&lNICKED";

    private final UUID owner;
    private final String header;
    private final String footer;
    private final boolean nicked;

    public LevelTag(UUID owner, String header, String footer) {
        this(owner, header, footer, false);
    }

    private LevelTag(UUID owner, String header, String footer, boolean nicked) {
        this.owner = owner;
        this.header = normalize(header);
        this.footer = normalize(footer);
        this.nicked = nicked;
    }

    public static LevelTag nicked(UUID owner) {
        return new LevelTag(owner, "", NICKED_FOOTER, true);
    }

    public static LevelTag bedwarsNicked(UUID owner, String footer) {
        return new LevelTag(owner, "", footer, true);
    }

    public UUID getOwner() {
        return owner;
    }

    public String getHeader() {
        return header;
    }

    public String getFooter() {
        return footer;
    }

    public String getText() {
        return header + footer;
    }

    public boolean isNicked() {
        return nicked || "NICKED".equalsIgnoreCase(stripFormatting(getText()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('&', '\u00a7');
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7.", "");
    }
}
