package com.mdt.jumpmine.model;

public class BridgeStatus {
    public boolean online;
    public int ping = -1;
    public int players = -1;
    public int playerLimit = -1;
    public String name = "";
    public String description = "";
    public String buildLabel = "";
    public String versionLabel = "";
    public long updatedAt;

    public static BridgeStatus offline(String buildLabel, String versionLabel) {
        BridgeStatus status = new BridgeStatus();
        status.online = false;
        status.buildLabel = safe(buildLabel);
        status.versionLabel = safe(versionLabel);
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    public static BridgeStatus staticEntry(String name, String description, String buildLabel, String versionLabel) {
        BridgeStatus status = new BridgeStatus();
        status.online = true;
        status.name = safe(name);
        status.description = safe(description);
        status.buildLabel = safe(buildLabel);
        status.versionLabel = safe(versionLabel);
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
