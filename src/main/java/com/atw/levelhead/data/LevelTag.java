package com.atw.levelhead.data;

import java.util.UUID;

public class LevelTag {
    private static final String NICKED_FOOTER = "&c&lNICKED";

    private final UUID owner;
    private final String header;
    private final String footer;

    public LevelTag(UUID owner, String header, String footer) {
        this.owner = owner;
        this.header = normalize(header);
        this.footer = normalize(footer);
    }

    public static LevelTag nicked(UUID owner) {
        return new LevelTag(owner, "", NICKED_FOOTER);
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
        return "NICKED".equalsIgnoreCase(stripFormatting(getText()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('&', '\u00a7');
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7.", "");
    }
}
