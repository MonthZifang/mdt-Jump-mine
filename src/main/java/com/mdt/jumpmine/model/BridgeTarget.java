package com.mdt.jumpmine.model;

public class BridgeTarget {
    public boolean enabled = true;
    public int fixedIndex = -1;
    public String displayName = "";
    public String description = "";
    public String targetType = "server";
    public String targetUrl = "";
    public String host = "";
    public int port = 6567;
    public String buildLabel = "";
    public String versionLabel = "";
    public String category = "";
    public int renderWidth = 0;
    public int renderHeight = 0;
    public int windowColumns = 0;
    public int windowRows = 0;

    public boolean hasTarget() {
        return notBlank(targetUrl) || notBlank(host);
    }

    public String identityKey() {
        if (notBlank(host)) {
            return "server:" + host.trim().toLowerCase() + ":" + port;
        }
        return "uri:" + targetUrl.trim();
    }

    public String displayKey() {
        if (notBlank(displayName)) return displayName.trim();
        if (notBlank(host)) return host.trim() + ":" + port;
        if (notBlank(targetUrl)) return targetUrl.trim();
        return "unconfigured";
    }

    public String resolveLaunchUri(String defaultServerUriScheme) {
        if (notBlank(targetUrl)) return targetUrl.trim();
        if (notBlank(host)) return defaultServerUriScheme + host.trim() + ":" + port;
        return "";
    }

    protected boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
