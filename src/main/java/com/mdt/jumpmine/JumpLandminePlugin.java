package com.mdt.jumpmine;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import com.mdt.jumpmine.config.PluginConfig;
import com.mdt.jumpmine.model.MapMineConfig.MineBinding;
import com.mdt.jumpmine.service.JumpLandmineService;
import com.mdt.jumpmine.service.JumpLandmineService.RenderedMine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.ui.Menus;

public class JumpLandminePlugin extends Plugin {
    private static final Path DATA_ROOT = Paths.get("config", "mods", "config", "mdt-jump-landmine");

    private final Map<String, RenderedMine> menuSelections = new HashMap<String, RenderedMine>();
    private final Map<String, String> playerRegionKeys = new HashMap<String, String>();

    private PluginConfig config;
    private JumpLandmineService service;
    private int menuId;

    @Override
    public void init() {
        try {
            config = PluginConfig.load(DATA_ROOT);
            service = new JumpLandmineService(DATA_ROOT, config);
            menuId = Menus.registerMenu(this::handleMenuSelection);

            Events.on(WorldLoadEvent.class, event -> service.onWorldLoad());
            Events.on(ResetEvent.class, event -> service.onReset());
            Events.on(PlayerJoin.class, event -> service.renderNow());
            Events.on(TapEvent.class, event -> {
                if (event.tile != null) {
                    handleTap(event.player, event.tile.x, event.tile.y);
                }
            });
            Events.run(mindustry.game.EventType.Trigger.update, () -> {
                service.update();
                handleStandingDispatch();
            });

            Log.info("跳板地雷插件已加载: @", service.summary());
        } catch (Exception exception) {
            throw new RuntimeException("初始化跳板地雷插件失败。", exception);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("jumpmine-transit-top", "[limit]", "查看中转目标排行榜。", args ->
            Log.info(service.transitLeaderboard(parseLimit(args, 10)))
        );
        handler.register("jumpmine-status", "查看跳板地雷插件状态。", args ->
            Log.info(service.summary())
        );
        handler.register("jumpmine-reload", "重新加载跳板地雷配置与绑定。", args -> {
            service.reloadConfig();
            config = PluginConfig.load(DATA_ROOT);
            Log.info("跳板地雷插件已重新加载: @", service.summary());
        });
        handler.register("jumpmine-rescan", "重新扫描当前地图的地雷槽位。", args -> {
            service.rescanMap();
            Log.info("跳板地雷地图重扫完成: @", service.summary());
        });
        handler.register("jumpmine-sync", "立即同步远程状态并重绘标签。", args -> {
            service.syncNow();
            service.renderNow();
            Log.info("跳板地雷同步完成: @", service.summary());
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("jumpmine", "查看跳板地雷插件状态。", (args, player) ->
            player.sendMessage("[accent]跳板地雷[] " + service.summary())
        );
        handler.<Player>register("jumpminetop", "[limit]", "查看中转目标排行榜。", (args, player) ->
            player.sendMessage(service.transitLeaderboard(parseLimit(args, 5)).replace("\n", "\n[lightgray]"))
        );
    }

    private void handleTap(Player player, int x, int y) {
        if (player == null) return;

        RenderedMine mine = service.findRenderedMine(x, y);
        if (mine == null) return;

        String uuid = resolveUuid(player);
        if (uuid == null) return;

        menuSelections.put(uuid, mine);
        String[][] options = new String[][]{
            {"跳转", "复制目标"},
            {"刷新", "关闭"}
        };
        Call.menu(player.con, menuId, "[accent]脉冲地雷[]", buildMenuMessage(mine), options);
    }

    private void handleMenuSelection(Player player, int option) {
        if (player == null) return;
        String uuid = resolveUuid(player);
        if (uuid == null) return;

        RenderedMine mine = menuSelections.get(uuid);
        if (mine == null) return;

        if (option == 0) {
            if (!dispatchTarget(player, mine, false)) {
                player.sendMessage("[scarlet]该中转槽位暂无可用目标。");
            } else {
                service.recordTransit(uuid, player.name, mine);
            }
            return;
        }

        if (option == 1) {
            String uri = buildClipboardValue(mine);
            if (uri.isEmpty()) {
                player.sendMessage("[scarlet]该中转槽位没有可复制的目标。");
                return;
            }
            Call.copyToClipboard(player.con, uri);
            player.sendMessage("[accent]已复制目标到剪贴板。");
            return;
        }

        if (option == 2) {
            service.syncNow();
            service.renderNow();
            player.sendMessage("[accent]中转槽位状态已刷新。");
        }
    }

    private String buildMenuMessage(RenderedMine mine) {
        StringBuilder builder = new StringBuilder();
        builder.append("坐标: ").append(mine.x).append(", ").append(mine.y);
        builder.append("\n目标: ").append(mine.target.displayKey());
        builder.append("\n类型: ").append(mine.target.targetType);
        builder.append("\n分类: ").append(emptyAsDash(mine.target.category));
        MineBinding binding = service.findMapBinding(mine.x, mine.y);
        if (binding != null) {
            builder.append("\n槽位类型: ").append(emptyAsDash(binding.slotType));
            builder.append("\n核心优先级: ").append(binding.corePriority);
            builder.append("\n地板方块: ").append(emptyAsDash(binding.floorBlock));
            builder.append("\n地板分类: ").append(emptyAsDash(binding.floorCategory));
        }
        builder.append("\n渲染尺寸: ").append(mine.renderWidth).append("x").append(mine.renderHeight);
        builder.append("\n窗口尺寸: ").append(mine.windowColumns).append("x").append(mine.windowRows);
        builder.append("\n渲染范围: ").append(mine.minTileX).append(",").append(mine.minTileY)
            .append(" -> ").append(mine.maxTileX).append(",").append(mine.maxTileY);
        builder.append("\n渲染行数: ").append(mine.placements.size());
        builder.append("\n跳转目标: ").append(buildClipboardValue(mine));
        if (mine.status != null) {
            builder.append("\n在线状态: ").append(mine.status.online ? "在线" : "离线");
            if (mine.status.players >= 0) {
                builder.append("\n玩家人数: ").append(mine.status.players).append("/").append(Math.max(mine.status.playerLimit, 0));
            }
            if (mine.status.ping >= 0) {
                builder.append("\n延迟: ").append(mine.status.ping).append("ms");
            }
            if (mine.status.versionLabel != null && !mine.status.versionLabel.isEmpty()) {
                builder.append("\n服务器版本: ").append(mine.status.versionLabel);
            }
            if (mine.status.buildLabel != null && !mine.status.buildLabel.isEmpty()) {
                builder.append("\n构建: ").append(mine.status.buildLabel);
            }
            if (mine.status.description != null && !mine.status.description.isEmpty()) {
                builder.append("\n简介: ").append(mine.status.description);
            }
        }
        builder.append("\n").append(buildMiniMap(mine));
        return buildFixedWindow(builder.toString(), mine.windowColumns, mine.windowRows);
    }

    private void handleStandingDispatch() {
        List<Player> players = new ArrayList<Player>(mindustry.gen.Groups.player.size());
        for (Player player : mindustry.gen.Groups.player) {
            players.add(player);
        }

        for (Player player : players) {
            if (player == null || player.con == null) continue;
            RenderedMine mine = findMineForPlayer(player.tileX(), player.tileY());
            String uuid = resolveUuid(player);
            if (uuid == null) continue;

            if (mine == null) {
                playerRegionKeys.remove(uuid);
                continue;
            }

            String currentKey = mine.x + ":" + mine.y;
            String previousKey = playerRegionKeys.get(uuid);
            if (currentKey.equals(previousKey)) continue;

            playerRegionKeys.put(uuid, currentKey);
            if (dispatchTarget(player, mine, true)) {
                service.recordTransit(uuid, player.name, mine);
            }
        }
    }

    private RenderedMine findMineForPlayer(int tileX, int tileY) {
        RenderedMine exact = service.findRenderedMine(tileX, tileY);
        if (exact != null) return exact;

        for (RenderedMine mine : service.renderedMines()) {
            if (tileX >= mine.minTileX && tileX <= mine.maxTileX
                && tileY >= mine.minTileY && tileY <= mine.maxTileY) {
                return mine;
            }
        }
        return null;
    }

    private boolean dispatchTarget(Player player, RenderedMine mine, boolean automatic) {
        if (mine == null || player == null) return false;

        if (mine.isServerTarget()) {
            if (mine.target.host == null || mine.target.host.trim().isEmpty()) return false;
            if (mine.status == null || !mine.status.online) {
                player.sendMessage("[scarlet]目标服务器当前离线，无法中转。");
                return false;
            }
            if (automatic) {
                player.sendMessage("[accent]你进入了中转槽位，正在为你跳转...");
            } else {
                player.sendMessage("[accent]正在跳转到目标服务器...");
            }
            Call.connect(player.con, mine.target.host.trim(), mine.target.port <= 0 ? 6567 : mine.target.port);
            return true;
        }

        String uri = mine.launchUri(service.defaultServerUriScheme());
        if (uri.isEmpty()) return false;
        if (automatic) {
            player.sendMessage("[accent]你进入了中转槽位，正在打开目标链接...");
        } else {
            player.sendMessage("[accent]正在向客户端发送打开链接请求...");
        }
        Call.openURI(player.con, uri);
        return true;
    }

    private String buildClipboardValue(RenderedMine mine) {
        if (mine == null) return "";
        if (mine.isServerTarget()) {
            return mine.target.host.trim() + ":" + mine.target.port;
        }
        return mine.launchUri(service.defaultServerUriScheme());
    }

    private String buildMiniMap(RenderedMine mine) {
        StringBuilder builder = new StringBuilder();
        builder.append("地图预览:");
        for (int y = mine.maxTileY; y >= mine.minTileY; y--) {
            builder.append("\n");
            for (int x = mine.minTileX; x <= mine.maxTileX; x++) {
                if (x == mine.x && y == mine.y) {
                    builder.append("[accent]X[]");
                } else {
                    builder.append("[gray]o[]");
                }
            }
        }
        return builder.toString();
    }

    private String buildFixedWindow(String content, int columns, int rows) {
        int innerWidth = Math.max(3, columns * 2);
        int innerRows = Math.max(1, rows);
        String horizontal = repeat("=", innerWidth);
        String[] rawLines = content.split("\\n");
        StringBuilder builder = new StringBuilder();
        builder.append("[").append(horizontal).append("]");

        int lineIndex = 0;
        for (int row = 0; row < innerRows; row++) {
            builder.append("\n|");
            String line = lineIndex < rawLines.length ? rawLines[lineIndex++] : "";
            builder.append(padVisible(line, innerWidth));
            builder.append("|");
        }

        builder.append("\n[").append(horizontal).append("]");
        return builder.toString();
    }

    private String padVisible(String value, int width) {
        String safe = value == null ? "" : value;
        String plain = safe.replaceAll("\\[[^\\]]*\\]", "");
        if (!containsMarkupOrSpecialGlyph(safe) && plain.length() > width) {
            int trimLength = Math.max(0, width - 3);
            safe = safe.substring(0, Math.min(safe.length(), trimLength)) + "...";
            plain = plain.substring(0, Math.min(plain.length(), trimLength)) + "...";
        }
        StringBuilder builder = new StringBuilder(safe);
        while (plain.length() < width) {
            builder.append(' ');
            plain += " ";
        }
        return builder.toString();
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private String emptyAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    private boolean containsMarkupOrSpecialGlyph(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.contains("[#") || value.contains("[]")) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\uE000' && c <= '\uF8FF') {
                return true;
            }
        }
        return false;
    }

    private String resolveUuid(Player player) {
        try {
            Method method = player.getClass().getMethod("uuid");
            Object value = method.invoke(player);
            if (value != null) return value.toString();
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Field field = player.getClass().getField("uuid");
            Object value = field.get(player);
            if (value != null) return value.toString();
        } catch (ReflectiveOperationException ignored) {
        }

        return null;
    }

    private int parseLimit(String[] args, int fallback) {
        if (args == null || args.length == 0) return fallback;
        try {
            int value = Integer.parseInt(args[0]);
            return value <= 0 ? fallback : value;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
