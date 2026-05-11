package com.mdt.jumpmine.service;

import arc.Core;
import arc.util.Log;
import arc.util.serialization.Json;
import com.mdt.jumpmine.config.PluginConfig;
import com.mdt.jumpmine.model.BridgeStatus;
import com.mdt.jumpmine.model.BridgeTarget;
import com.mdt.jumpmine.model.MapMineConfig;
import com.mdt.jumpmine.model.MapMineConfig.MineBinding;
import com.mdt.jumpmine.model.ServerLayoutSnapshot;
import com.mdt.jumpmine.model.ServerLayoutSnapshot.ServerSlot;
import com.mdt.jumpmine.model.ServerStatusCache;
import com.mdt.jumpmine.model.SlotExportConfig;
import com.mdt.jumpmine.model.SlotExportConfig.SlotEntry;
import com.mdt.jumpmine.model.TransitRecordBook;
import com.mdt.jumpmine.model.TransitRecordBook.TransitRecord;
import com.mdt.jumpmine.model.TransitSummary;
import com.mdt.jumpmine.model.TransitSummary.TargetStat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Host;
import mindustry.world.Block;
import mindustry.world.Tile;

public class JumpLandmineService {
    private final Path root;
    private final Path mapsRoot;
    private final Path snapshotsRoot;
    private final Path slotExportsRoot;
    private final Path transitRecordPath;
    private final Path transitSummaryPath;
    private final Path statusCachePath;
    private final Json json = new Json();
    private final ExecutorService templateExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, BridgeStatus> statuses = new ConcurrentHashMap<String, BridgeStatus>();
    private final List<MineSlot> orderedMineSlots = new ArrayList<MineSlot>();
    private final Map<String, RenderedMine> renderedByTile = new LinkedHashMap<String, RenderedMine>();
    private final List<BridgeTarget> templateEntries = new ArrayList<BridgeTarget>();

    private PluginConfig config;
    private MapMineConfig currentMapConfig;
    private Path currentMapConfigPath;
    private Path currentSnapshotPath;
    private long lastSyncAt;
    private long lastRenderAt;
    private int syncSerial;
    private int lastConflictCount;
    private boolean templateSyncPending;
    private TransitRecordBook transitBook = new TransitRecordBook();

    public JumpLandmineService(Path root, PluginConfig config) {
        this.root = root;
        this.mapsRoot = root.resolve("maps");
        this.snapshotsRoot = root.resolve("server-layouts");
        this.slotExportsRoot = root.resolve("slot-exports");
        this.transitRecordPath = root.resolve("transit-records.json");
        this.transitSummaryPath = root.resolve("transit-summary.json");
        this.statusCachePath = root.resolve("server-status-cache.json");
        this.config = config;
        this.json.setIgnoreUnknownFields(true);
        this.json.setUsePrototypes(false);
        loadTransitBook();
        loadStatusCache();
    }

    public void onWorldLoad() {
        scanMineSlots();
        loadOrCreateMapConfig();
        syncNow();
        renderNow();
    }

    public void onReset() {
        orderedMineSlots.clear();
        renderedByTile.clear();
        lastConflictCount = 0;
        templateSyncPending = false;
    }

    public void update() {
        long now = System.currentTimeMillis();
        if (now - lastSyncAt >= config.syncIntervalSeconds * 1000L) {
            syncNow();
        }
        if (config.renderEnabled && now - lastRenderAt >= config.renderRefreshSeconds * 1000L) {
            renderNow();
        }
    }

    public void reloadConfig() {
        this.config = PluginConfig.load(root);
        scanMineSlots();
        loadOrCreateMapConfig();
        syncNow();
        renderNow();
    }

    public void rescanMap() {
        scanMineSlots();
        loadOrCreateMapConfig();
        rebuildRendered();
        renderNow();
    }

    public String summary() {
        return "mode=" + config.mode
            + " sort=" + config.sortMode
            + " renderSize=" + config.renderSizeMode
            + " windowSize=" + config.windowSizeMode
            + " proxy=" + config.templateProxyEnabled
            + " mines=" + orderedMineSlots.size()
            + " rendered=" + renderedByTile.size()
            + " conflicts=" + lastConflictCount
            + " syncing=" + templateSyncPending
            + " map=" + (currentMapConfig == null ? "none" : currentMapConfig.mapName)
            + " config=" + (currentMapConfigPath == null ? "none" : currentMapConfigPath.getFileName().toString());
    }

    public String transitLeaderboard(int limit) {
        TransitSummary summary = buildTransitSummary();
        int resolvedLimit = limit <= 0 ? 10 : Math.min(limit, 50);

        StringBuilder builder = new StringBuilder();
        builder.append("transit-total=").append(summary.total)
            .append(" today=").append(summary.today)
            .append(" sevenDays=").append(summary.sevenDays)
            .append(" thirtyDays=").append(summary.thirtyDays);

        if (summary.topTargets == null || summary.topTargets.isEmpty()) {
            builder.append("\nno transit records");
            return builder.toString();
        }

        int count = Math.min(resolvedLimit, summary.topTargets.size());
        for (int i = 0; i < count; i++) {
            TargetStat stat = summary.topTargets.get(i);
            builder.append("\n")
                .append(i + 1)
                .append(". ")
                .append(firstNonBlank(stat.targetName, formatTargetAddress(stat.host, stat.port), stat.targetKey))
                .append(" -> ")
                .append(stat.count);
        }
        return builder.toString();
    }

    public RenderedMine findRenderedMine(int x, int y) {
        return renderedByTile.get(tileKey(x, y));
    }

    public Iterable<RenderedMine> renderedMines() {
        return renderedByTile.values();
    }

    public MineBinding findMapBinding(int x, int y) {
        if (currentMapConfig == null || currentMapConfig.mines == null) return null;
        for (MineBinding binding : currentMapConfig.mines) {
            if (binding.x == x && binding.y == y) return binding;
        }
        return null;
    }

    public String defaultServerUriScheme() {
        return config.defaultServerUriScheme;
    }

    public void syncNow() {
        lastSyncAt = System.currentTimeMillis();
        syncSerial++;

        if (config.isTemplateMode()) {
            templateSyncPending = true;
            loadTemplateAsync(syncSerial);
            refreshTargetStatuses(new ArrayList<BridgeTarget>(templateEntries), syncSerial);
            rebuildRendered();
            return;
        }

        refreshTargetStatuses(resolveSourceTargets(), syncSerial);
        rebuildRendered();
    }

    public void renderNow() {
        lastRenderAt = System.currentTimeMillis();
        if (!config.renderEnabled || renderedByTile.isEmpty()) return;

        for (Player player : Groups.player) {
            if (player == null || player.con == null) continue;
            for (RenderedMine mine : renderedByTile.values()) {
                for (LabelPlacement placement : mine.placements) {
                    Call.label(player.con, placement.text, placement.labelId, config.labelDurationSeconds, placement.worldX, placement.worldY);
                }
            }
            CoreStatsLabel statsLabel = buildCoreStatsLabel();
            if (statsLabel != null) {
                Call.label(player.con, statsLabel.text, statsLabel.labelId, config.labelDurationSeconds, statsLabel.worldX, statsLabel.worldY);
            }
        }
    }

    public void recordTransit(String playerUuid, String playerName, RenderedMine mine) {
        TransitRecord record = new TransitRecord();
        record.timestamp = System.currentTimeMillis();
        record.playerUuid = playerUuid == null ? "" : playerUuid;
        record.playerName = playerName == null ? "" : playerName;
        record.targetKey = mine == null || mine.target == null ? "" : mine.target.identityKey();
        record.targetName = mine == null || mine.target == null ? "" : mine.target.displayKey();
        record.targetType = mine == null || mine.target == null ? "" : mine.target.targetType;
        record.host = mine == null || mine.target == null ? "" : mine.target.host;
        record.port = mine == null || mine.target == null ? 6567 : mine.target.port;
        transitBook.records.add(record);
        saveTransitBook();
    }

    private void refreshTargetStatuses(List<BridgeTarget> targets, int expectedSerial) {
        List<BridgeTarget> serverTargets = new ArrayList<BridgeTarget>();
        for (BridgeTarget target : targets) {
            if (!target.enabled || !target.hasTarget()) continue;

            if (isServerTarget(target)) {
                serverTargets.add(target);
            } else {
                statuses.put(target.identityKey(), BridgeStatus.staticEntry(
                    target.displayName,
                    target.description,
                    target.buildLabel,
                    target.versionLabel
                ));
            }
        }

        if (serverTargets.isEmpty()) {
            saveStatusCache();
            rebuildRendered();
            return;
        }

        final int currentSerial = expectedSerial;
        final AtomicInteger remaining = new AtomicInteger(serverTargets.size());
        for (BridgeTarget target : serverTargets) {
            final String key = target.identityKey();
            Vars.net.pingHost(target.host, target.port, host -> {
                if (currentSerial != syncSerial) return;
                statuses.put(key, fromHost(target, host));
                onStatusRefreshComplete(remaining, currentSerial);
            }, error -> {
                if (currentSerial != syncSerial) return;
                if (!statuses.containsKey(key)) {
                    statuses.put(key, BridgeStatus.offline(target.buildLabel, target.versionLabel));
                }
                onStatusRefreshComplete(remaining, currentSerial);
            });
        }
    }

    private void onStatusRefreshComplete(AtomicInteger remaining, int currentSerial) {
        if (remaining.decrementAndGet() == 0 && currentSerial == syncSerial) {
            saveStatusCache();
            rebuildRendered();
        }
    }

    private void scanMineSlots() {
        orderedMineSlots.clear();

        int coreX = config.coreX >= 0 ? config.coreX : resolveDefaultCoreX();
        int coreY = config.coreY >= 0 ? config.coreY : resolveDefaultCoreY();

        for (int x = 0; x < Vars.world.width(); x++) {
            for (int y = 0; y < Vars.world.height(); y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null || tile.block() != Blocks.shockMine || !tile.isCenter()) continue;
                orderedMineSlots.add(new MineSlot(x, y, distanceSquared(x, y, coreX, coreY)));
            }
        }

        Collections.sort(orderedMineSlots, new Comparator<MineSlot>() {
            @Override
            public int compare(MineSlot left, MineSlot right) {
                int cmp = Float.compare(left.distance, right.distance);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(left.y, right.y);
                if (cmp != 0) return cmp;
                return Integer.compare(left.x, right.x);
            }
        });
    }

    private void loadOrCreateMapConfig() {
        try {
            Files.createDirectories(mapsRoot);
            Files.createDirectories(snapshotsRoot);
            Files.createDirectories(slotExportsRoot);
            String mapName = Vars.state.map == null ? "unknown-map" : Vars.state.map.name();
            currentMapConfigPath = mapsRoot.resolve(safeFileName(mapName) + ".json");
            currentSnapshotPath = snapshotsRoot.resolve(safeFileName(mapName) + "-servers.json");

            MapMineConfig existing = Files.exists(currentMapConfigPath)
                ? json.fromJson(MapMineConfig.class, readString(currentMapConfigPath))
                : new MapMineConfig();
            if (existing == null) existing = new MapMineConfig();

            currentMapConfig = mergeMapConfig(mapName, existing);
            Files.write(currentMapConfigPath, json.prettyPrint(currentMapConfig).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new RuntimeException("加载跳板地雷地图配置失败。", exception);
        }
    }

    private MapMineConfig mergeMapConfig(String mapName, MapMineConfig existing) {
        MapMineConfig merged = new MapMineConfig();
        merged.mapName = mapName;
        merged.generatedAt = System.currentTimeMillis();

        Map<String, MineBinding> existingByKey = new LinkedHashMap<String, MineBinding>();
        if (existing != null && existing.mines != null) {
            for (MineBinding binding : existing.mines) {
                existingByKey.put(tileKey(binding.x, binding.y), binding);
            }
        }

        for (int i = 0; i < orderedMineSlots.size(); i++) {
            MineSlot slot = orderedMineSlots.get(i);
            MineBinding binding = existingByKey.get(tileKey(slot.x, slot.y));
            if (binding == null) {
                binding = new MineBinding();
                binding.x = slot.x;
                binding.y = slot.y;
                binding.enabled = true;
                binding.fixedIndex = i;
                binding.targetType = "server";
            }
            binding.corePriority = i;
            Tile tile = Vars.world.tile(slot.x, slot.y);
            if (tile != null && tile.floor() != null) {
                binding.floorBlock = tile.floor().name;
                if (binding.floorCategory == null || binding.floorCategory.trim().isEmpty()) {
                    binding.floorCategory = resolveFloorCategory(tile.floor());
                }
            }
            binding.slotType = resolveSlotType(binding.corePriority, binding.floorCategory);
            merged.mines.add(binding);
        }
        return merged;
    }

    private void rebuildRendered() {
        Map<String, RenderedMine> nextRendered = new LinkedHashMap<String, RenderedMine>();
        lastConflictCount = 0;
        if (orderedMineSlots.isEmpty()) {
            renderedByTile.clear();
            return;
        }

        Set<String> occupiedTiles = new LinkedHashSet<String>();
        int renderOrder = 0;

        if (!config.isLatencySort() && !config.isTemplateMode()) {
            if (currentMapConfig == null || currentMapConfig.mines == null) return;
            applyManualSlotOverrides();
            for (MineBinding binding : currentMapConfig.mines) {
                if (binding.enabled && binding.hasTarget()) {
                    if (!addRenderedMine(nextRendered, binding.x, binding.y, binding, occupiedTiles, renderOrder++)) {
                        lastConflictCount++;
                    }
                }
            }
            renderedByTile.clear();
            renderedByTile.putAll(nextRendered);
            updateMapConfigAssignments();
            writeServerLayoutSnapshot();
            writeSlotExports();
            return;
        }

        List<BridgeTarget> filtered = new ArrayList<BridgeTarget>();
        for (BridgeTarget target : resolveSourceTargets()) {
            if (target.enabled && target.hasTarget()) {
                filtered.add(target);
            }
        }

        applyManualSlotOverrides();
        Collections.sort(filtered, buildScoreComparator());
        renderOrder = assignTargetsWithPersistence(filtered, nextRendered, occupiedTiles, renderOrder);

        renderOrder = renderReservedManualSlots(nextRendered, occupiedTiles, renderOrder, "vip");
        renderOrder = renderReservedManualSlots(nextRendered, occupiedTiles, renderOrder, "custom");

        renderedByTile.clear();
        renderedByTile.putAll(nextRendered);
        updateMapConfigAssignments();
        writeServerLayoutSnapshot();
        writeSlotExports();
    }

    private int assignTargetsWithPersistence(List<BridgeTarget> sortedTargets, Map<String, RenderedMine> nextRendered, Set<String> occupiedTiles, int renderOrder) {
        List<BridgeTarget> autoTargets = new ArrayList<BridgeTarget>();
        for (BridgeTarget target : sortedTargets) {
            if (!isReservedCategory(target.category) && isRenderableAutoTarget(target)) {
                autoTargets.add(target);
            }
        }
        Set<String> usedTileKeys = new LinkedHashSet<String>();
        Set<String> usedTargetKeys = new LinkedHashSet<String>();

        List<MineSlot> remainingSlots = new ArrayList<MineSlot>();
        for (MineSlot slot : orderedMineSlots) {
            MineBinding binding = findMapBinding(slot.x, slot.y);
            if (binding != null && !"auto".equalsIgnoreCase(binding.slotType)) continue;
            remainingSlots.add(slot);
        }

        for (MineSlot slot : new ArrayList<MineSlot>(remainingSlots)) {
            BridgeTarget matched = findCategoryMatchedTarget(slot, autoTargets, usedTargetKeys);
            if (matched == null) continue;
            if (addRenderedMine(nextRendered, slot.x, slot.y, matched, occupiedTiles, renderOrder)) {
                String targetKey = matched.identityKey();
                usedTileKeys.add(tileKey(slot.x, slot.y));
                usedTargetKeys.add(targetKey);
                remainingSlots.remove(slot);
                renderOrder++;
            } else {
                lastConflictCount++;
            }
        }

        for (BridgeTarget target : autoTargets) {
            String targetKey = target.identityKey();
            if (usedTargetKeys.contains(targetKey)) continue;
            if (remainingSlots.isEmpty()) break;

            MineSlot slot = remainingSlots.remove(0);
            if (addRenderedMine(nextRendered, slot.x, slot.y, target, occupiedTiles, renderOrder)) {
                usedTileKeys.add(tileKey(slot.x, slot.y));
                usedTargetKeys.add(targetKey);
                renderOrder++;
            } else {
                lastConflictCount++;
            }
        }

        return renderOrder;
    }

    private boolean isRenderableAutoTarget(BridgeTarget target) {
        if (target == null || !target.enabled || !target.hasTarget()) return false;
        if (!isServerTarget(target)) return true;

        BridgeStatus status = statuses.get(target.identityKey());
        return status != null && status.online;
    }

    private int renderReservedManualSlots(Map<String, RenderedMine> nextRendered, Set<String> occupiedTiles, int renderOrder, String slotType) {
        SlotExportConfig export = loadSlotExport(slotType);
        if (export == null || export.slots == null) return renderOrder;

        for (SlotEntry entry : export.slots) {
            if (entry == null || !entry.enabled || !entry.hasTarget()) continue;
            if (!containsMineSlot(entry.x, entry.y)) continue;
            if (nextRendered.containsKey(tileKey(entry.x, entry.y))) continue;

            if (addRenderedMine(nextRendered, entry.x, entry.y, entry, occupiedTiles, renderOrder)) {
                renderOrder++;
            } else {
                lastConflictCount++;
            }
        }
        return renderOrder;
    }

    private void applyManualSlotOverrides() {
        applyManualSlotOverride("vip");
        applyManualSlotOverride("custom");
    }

    private void applyManualSlotOverride(String slotType) {
        SlotExportConfig export = loadSlotExport(slotType);
        if (export == null || export.slots == null || currentMapConfig == null || currentMapConfig.mines == null) return;

        Map<String, MineBinding> byKey = new LinkedHashMap<String, MineBinding>();
        for (MineBinding binding : currentMapConfig.mines) {
            byKey.put(tileKey(binding.x, binding.y), binding);
        }

        for (SlotEntry entry : export.slots) {
            if (entry == null) continue;
            MineBinding binding = byKey.get(tileKey(entry.x, entry.y));
            if (binding == null) continue;

            binding.slotType = slotType;
            binding.floorBlock = firstNonBlank(entry.floorBlock, binding.floorBlock);
            binding.floorCategory = firstNonBlank(entry.floorCategory, binding.floorCategory);
            binding.displayName = firstNonBlank(entry.displayName, binding.displayName);
            binding.description = firstNonBlank(entry.description, binding.description);
            binding.targetType = firstNonBlank(entry.targetType, binding.targetType);
            binding.targetUrl = firstNonBlank(entry.targetUrl, binding.targetUrl);
            binding.host = firstNonBlank(entry.host, binding.host);
            binding.port = entry.port > 0 ? entry.port : binding.port;
            binding.buildLabel = firstNonBlank(entry.buildLabel, binding.buildLabel);
            binding.versionLabel = firstNonBlank(entry.versionLabel, binding.versionLabel);
            binding.category = firstNonBlank(entry.category, binding.category, slotType);
            binding.renderWidth = entry.renderWidth > 0 ? entry.renderWidth : binding.renderWidth;
            binding.renderHeight = entry.renderHeight > 0 ? entry.renderHeight : binding.renderHeight;
            binding.windowColumns = entry.windowColumns > 0 ? entry.windowColumns : binding.windowColumns;
            binding.windowRows = entry.windowRows > 0 ? entry.windowRows : binding.windowRows;
            binding.enabled = entry.enabled;
        }
    }

    private BridgeTarget findCategoryMatchedTarget(MineSlot slot, List<BridgeTarget> sortedTargets, Set<String> usedTargetKeys) {
        MineBinding binding = findMapBinding(slot.x, slot.y);
        String floorCategory = binding == null ? "" : safeLower(binding.floorCategory);
        if (floorCategory.isEmpty()) return null;

        for (BridgeTarget target : sortedTargets) {
            if (usedTargetKeys.contains(target.identityKey())) continue;
            if (floorCategory.equals(safeLower(target.category))) {
                return target;
            }
        }
        return null;
    }

    private boolean addRenderedMine(Map<String, RenderedMine> targetMap, int x, int y, BridgeTarget target, Set<String> occupiedTiles, int renderOrder) {
        BridgeStatus status = statuses.containsKey(target.identityKey())
            ? statuses.get(target.identityKey())
            : BridgeStatus.offline(target.buildLabel, target.versionLabel);
        int renderWidth = resolveRenderWidth(target);
        int renderHeight = resolveRenderHeight(target);
        RenderRegion region = buildRegion(x, y, renderWidth, renderHeight);

        if (conflicts(region, occupiedTiles)) {
            Log.warn("跳板地雷渲染区域冲突: 坐标=@,@ 尺寸=@x@ 目标=@", x, y, renderWidth, renderHeight, target.displayKey());
            return false;
        }

        List<String> lines = buildLabelLinesCn(target, status, renderWidth, renderHeight);
        List<LabelPlacement> placements = buildPlacements(region, lines, renderOrder);
        if (placements.isEmpty()) {
            return false;
        }

        occupy(region, occupiedTiles);

        RenderedMine mine = new RenderedMine();
        mine.x = x;
        mine.y = y;
        mine.target = target;
        mine.status = status;
        mine.renderWidth = renderWidth;
        mine.renderHeight = renderHeight;
        mine.windowColumns = resolveWindowColumns(target);
        mine.windowRows = resolveWindowRows(target);
        mine.minTileX = region.minX;
        mine.maxTileX = region.maxX;
        mine.minTileY = region.minY;
        mine.maxTileY = region.maxY;
        mine.placements.addAll(placements);
        targetMap.put(tileKey(x, y), mine);
        return true;
    }

    private void updateMapConfigAssignments() {
        if (currentMapConfig == null || currentMapConfig.mines == null || currentMapConfigPath == null) return;

        Map<String, MineBinding> byKey = new LinkedHashMap<String, MineBinding>();
        for (MineBinding binding : currentMapConfig.mines) {
            byKey.put(tileKey(binding.x, binding.y), binding);
        }

        for (RenderedMine mine : renderedByTile.values()) {
            MineBinding binding = byKey.get(tileKey(mine.x, mine.y));
            if (binding == null) continue;
            binding.displayName = mine.target.displayName;
            binding.description = mine.target.description;
            binding.targetType = mine.target.targetType;
            binding.targetUrl = mine.target.targetUrl;
            binding.host = mine.target.host;
            binding.port = mine.target.port;
            binding.buildLabel = mine.target.buildLabel;
            binding.versionLabel = mine.target.versionLabel;
            binding.category = mine.target.category;
            binding.renderWidth = mine.target.renderWidth;
            binding.renderHeight = mine.target.renderHeight;
            binding.windowColumns = mine.target.windowColumns;
            binding.windowRows = mine.target.windowRows;
            binding.slotType = resolveSlotType(binding.corePriority, binding.floorCategory);
        }

        try {
            Files.write(currentMapConfigPath, json.prettyPrint(currentMapConfig).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.err("保存跳板地雷坐标配置失败: @", exception.getMessage());
        }
    }

    private void writeServerLayoutSnapshot() {
        if (currentSnapshotPath == null) return;

        ServerLayoutSnapshot snapshot = new ServerLayoutSnapshot();
        snapshot.mapName = currentMapConfig == null ? "" : currentMapConfig.mapName;
        snapshot.generatedAt = System.currentTimeMillis();

        for (RenderedMine mine : renderedByTile.values()) {
            ServerSlot slot = new ServerSlot();
            slot.x = mine.x;
            slot.y = mine.y;
            slot.enabled = mine.target.enabled;
            slot.fixedIndex = mine.target.fixedIndex;
            slot.displayName = mine.target.displayName;
            slot.description = mine.target.description;
            slot.targetType = mine.target.targetType;
            slot.targetUrl = mine.target.targetUrl;
            slot.host = mine.target.host;
            slot.port = mine.target.port;
            slot.buildLabel = mine.target.buildLabel;
            slot.versionLabel = mine.target.versionLabel;
            slot.category = mine.target.category;
            slot.renderWidth = mine.target.renderWidth;
            slot.renderHeight = mine.target.renderHeight;
            slot.windowColumns = mine.target.windowColumns;
            slot.windowRows = mine.target.windowRows;
            MineBinding binding = findMapBinding(mine.x, mine.y);
            if (binding != null) {
                slot.floorBlock = binding.floorBlock;
                slot.floorCategory = binding.floorCategory;
                slot.slotType = binding.slotType;
                slot.corePriority = binding.corePriority;
            }
            snapshot.slots.add(slot);
        }

        try {
            Files.createDirectories(snapshotsRoot);
            Files.write(currentSnapshotPath, json.prettyPrint(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.err("保存跳板地雷服务器布局失败: @", exception.getMessage());
        }
    }

    private ServerLayoutSnapshot loadServerLayoutSnapshot() {
        if (currentSnapshotPath == null || !Files.exists(currentSnapshotPath)) return null;
        try {
            ServerLayoutSnapshot snapshot = json.fromJson(ServerLayoutSnapshot.class, readString(currentSnapshotPath));
            return snapshot == null ? new ServerLayoutSnapshot() : snapshot;
        } catch (Exception exception) {
            Log.err("加载跳板地雷服务器布局失败: @", exception.getMessage());
            return null;
        }
    }

    private boolean containsMineSlot(int x, int y) {
        for (MineSlot slot : orderedMineSlots) {
            if (slot.x == x && slot.y == y) return true;
        }
        return false;
    }

    private void writeSlotExports() {
        if (currentMapConfig == null || currentMapConfig.mines == null) return;
        writeSlotExport("vip");
        writeSlotExport("custom");
    }

    private void writeSlotExport(String slotType) {
        SlotExportConfig export = new SlotExportConfig();
        export.mapName = currentMapConfig == null ? "" : currentMapConfig.mapName;
        export.generatedAt = System.currentTimeMillis();
        export.slotType = slotType;

        for (MineBinding binding : currentMapConfig.mines) {
            if (!slotType.equalsIgnoreCase(binding.slotType)) continue;
            SlotEntry entry = new SlotEntry();
            entry.x = binding.x;
            entry.y = binding.y;
            entry.floorBlock = binding.floorBlock;
            entry.floorCategory = binding.floorCategory;
            entry.slotType = binding.slotType;
            entry.corePriority = binding.corePriority;
            entry.enabled = binding.enabled;
            entry.displayName = binding.displayName;
            entry.description = binding.description;
            entry.targetType = binding.targetType;
            entry.targetUrl = binding.targetUrl;
            entry.host = binding.host;
            entry.port = binding.port;
            entry.buildLabel = binding.buildLabel;
            entry.versionLabel = binding.versionLabel;
            entry.category = binding.category;
            entry.renderWidth = binding.renderWidth;
            entry.renderHeight = binding.renderHeight;
            entry.windowColumns = binding.windowColumns;
            entry.windowRows = binding.windowRows;
            export.slots.add(entry);
        }

        Path path = slotExportsRoot.resolve((currentMapConfig == null ? "unknown-map" : safeFileName(currentMapConfig.mapName)) + "-" + slotType + ".json");
        try {
            Files.write(path, json.prettyPrint(export).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.err("保存跳板地雷分类槽位失败: @", exception.getMessage());
        }
    }

    private SlotExportConfig loadSlotExport(String slotType) {
        if (currentMapConfig == null) return null;
        Path path = slotExportsRoot.resolve((safeFileName(currentMapConfig.mapName)) + "-" + slotType + ".json");
        if (!Files.exists(path)) return null;
        try {
            SlotExportConfig export = json.fromJson(SlotExportConfig.class, readString(path));
            return export == null ? new SlotExportConfig() : export;
        } catch (Exception exception) {
            Log.err("加载跳板地雷槽位配置失败: @", exception.getMessage());
            return null;
        }
    }

    private void loadTransitBook() {
        if (!Files.exists(transitRecordPath)) {
            transitBook = new TransitRecordBook();
            saveTransitSummary();
            return;
        }
        try {
            TransitRecordBook loaded = json.fromJson(TransitRecordBook.class, readString(transitRecordPath));
            transitBook = loaded == null ? new TransitRecordBook() : loaded;
            saveTransitSummary();
        } catch (Exception exception) {
            transitBook = new TransitRecordBook();
            Log.err("加载中转记录失败: @", exception.getMessage());
        }
    }

    private void saveTransitBook() {
        try {
            Files.createDirectories(root);
            Files.write(transitRecordPath, json.prettyPrint(transitBook).getBytes(StandardCharsets.UTF_8));
            saveTransitSummary();
        } catch (IOException exception) {
            Log.err("保存中转记录失败: @", exception.getMessage());
        }
    }

    private void saveTransitSummary() {
        try {
            TransitSummary summary = buildTransitSummary();
            Files.write(transitSummaryPath, json.prettyPrint(summary).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.err("保存中转统计失败: @", exception.getMessage());
        }
    }

    private TransitSummary buildTransitSummary() {
        TransitSummary summary = new TransitSummary();
        summary.generatedAt = System.currentTimeMillis();

        long now = System.currentTimeMillis();
        long todayStart = now - 24L * 60L * 60L * 1000L;
        long weekStart = now - 7L * 24L * 60L * 60L * 1000L;
        long monthStart = now - 30L * 24L * 60L * 60L * 1000L;

        Map<String, TargetStat> statsByTarget = new LinkedHashMap<String, TargetStat>();
        summary.total = transitBook.records.size();
        for (TransitRecord record : transitBook.records) {
            if (record.timestamp >= todayStart) summary.today++;
            if (record.timestamp >= weekStart) summary.sevenDays++;
            if (record.timestamp >= monthStart) summary.thirtyDays++;

            String key = firstNonBlank(record.targetKey, formatTargetAddress(record.host, record.port), record.targetName);
            if (key.isEmpty()) continue;

            TargetStat stat = statsByTarget.get(key);
            if (stat == null) {
                stat = new TargetStat();
                stat.targetKey = key;
                stat.targetName = firstNonBlank(record.targetName, formatTargetAddress(record.host, record.port));
                stat.targetType = firstNonBlank(record.targetType, "server");
                stat.host = firstNonBlank(record.host, "");
                stat.port = record.port > 0 ? record.port : 6567;
                statsByTarget.put(key, stat);
            }
            stat.count++;
        }

        summary.topTargets.addAll(statsByTarget.values());
        Collections.sort(summary.topTargets, new Comparator<TargetStat>() {
            @Override
            public int compare(TargetStat left, TargetStat right) {
                int byCount = Integer.compare(right.count, left.count);
                if (byCount != 0) return byCount;
                return firstNonBlank(left.targetName, left.targetKey).compareToIgnoreCase(firstNonBlank(right.targetName, right.targetKey));
            }
        });

        if (summary.topTargets.size() > 50) {
            summary.topTargets = new ArrayList<TargetStat>(summary.topTargets.subList(0, 50));
        }
        return summary;
    }

    private void loadStatusCache() {
        if (!Files.exists(statusCachePath)) return;
        try {
            ServerStatusCache cache = json.fromJson(ServerStatusCache.class, readString(statusCachePath));
            if (cache != null && cache.statuses != null) {
                statuses.putAll(cache.statuses);
            }
        } catch (Exception exception) {
            Log.err("加载服务器状态缓存失败: @", exception.getMessage());
        }
    }

    private void saveStatusCache() {
        try {
            ServerStatusCache cache = new ServerStatusCache();
            cache.statuses.putAll(statuses);
            Files.write(statusCachePath, json.prettyPrint(cache).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.err("保存服务器状态缓存失败: @", exception.getMessage());
        }
    }

    private CoreStatsLabel buildCoreStatsLabel() {
        if (Vars.state == null || Vars.state.rules == null || Vars.state.rules.defaultTeam == null) return null;
        if (Vars.state.rules.defaultTeam.cores().isEmpty()) return null;
        TransitSummary summary = buildTransitSummary();

        CoreStatsLabel label = new CoreStatsLabel();
        label.labelId = 990001;
        label.text = "[accent]中转统计[]\n"
            + "[white]今日[] " + summary.today + "\n"
            + "[white]7天[] " + summary.sevenDays + "\n"
            + "[white]30天[] " + summary.thirtyDays + "\n"
            + "[white]总计[] " + summary.total;
        label.worldX = Vars.state.rules.defaultTeam.cores().first().tileX() * Vars.tilesize;
        label.worldY = Vars.state.rules.defaultTeam.cores().first().tileY() * Vars.tilesize + Vars.tilesize * 4.5f;
        return label;
    }

    private RenderRegion buildRegion(int centerX, int centerY, int renderWidth, int renderHeight) {
        int left = renderWidth / 2;
        int right = renderWidth - left - 1;
        int down = renderHeight / 2;
        int up = renderHeight - down - 1;

        RenderRegion region = new RenderRegion();
        region.minX = centerX - left;
        region.maxX = centerX + right;
        region.minY = centerY - down;
        region.maxY = centerY + up;
        return region;
    }

    private boolean conflicts(RenderRegion region, Set<String> occupiedTiles) {
        for (int tileX = region.minX; tileX <= region.maxX; tileX++) {
            for (int tileY = region.minY; tileY <= region.maxY; tileY++) {
                if (occupiedTiles.contains(tileKey(tileX, tileY))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void occupy(RenderRegion region, Set<String> occupiedTiles) {
        for (int tileX = region.minX; tileX <= region.maxX; tileX++) {
            for (int tileY = region.minY; tileY <= region.maxY; tileY++) {
                occupiedTiles.add(tileKey(tileX, tileY));
            }
        }
    }

    private List<LabelPlacement> buildPlacements(RenderRegion region, List<String> lines, int renderOrder) {
        List<LabelPlacement> placements = new ArrayList<LabelPlacement>();
        if (lines.isEmpty()) return placements;

        int regionHeight = region.maxY - region.minY + 1;
        int regionWidth = region.maxX - region.minX + 1;
        int usedLines = Math.min(lines.size(), regionHeight);
        float verticalGap = Vars.tilesize * 0.95f;
        float centerX = ((region.minX + region.maxX) / 2f) * Vars.tilesize;
        float topY = region.maxY * Vars.tilesize + Vars.tilesize * config.renderOffsetTiles;
        int startIndex = Math.max(0, (lines.size() - usedLines) / 2);

        for (int i = 0; i < usedLines; i++) {
            LabelPlacement placement = new LabelPlacement();
            placement.labelId = 100000 + renderOrder * 100 + i;
            placement.text = lines.get(startIndex + i);
            placement.worldX = centerX;
            placement.worldY = topY - i * verticalGap;
            placement.regionWidth = regionWidth;
            placement.regionHeight = regionHeight;
            placements.add(placement);
        }

        return placements;
    }

    private List<BridgeTarget> resolveSourceTargets() {
        if (config.isTemplateMode()) {
            return new ArrayList<BridgeTarget>(templateEntries);
        }

        List<BridgeTarget> targets = new ArrayList<BridgeTarget>();
        if (currentMapConfig != null && currentMapConfig.mines != null) {
            targets.addAll(currentMapConfig.mines);
        }
        return targets;
    }

    private void loadTemplateAsync(final int expectedSerial) {
        if (config.templateUrl == null || config.templateUrl.trim().isEmpty()) {
            templateEntries.clear();
            rebuildRendered();
            return;
        }

        templateExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final TemplateResponse response = fetchTemplate(config.templateUrl.trim());
                    Core.app.post(new Runnable() {
                        @Override
                        public void run() {
                            if (expectedSerial != syncSerial) return;
                            templateSyncPending = false;
                            templateEntries.clear();
                            if (response != null && response.entries != null) {
                                templateEntries.addAll(response.entries);
                            }
                            refreshTargetStatuses(new ArrayList<BridgeTarget>(templateEntries), expectedSerial);
                            rebuildRendered();
                        }
                    });
                } catch (Exception exception) {
                    templateSyncPending = false;
                    Log.err("拉取跳板地雷模板失败: @", exception.getMessage());
                }
            }
        });
    }

    private TemplateResponse fetchTemplate(String url) throws IOException {
        String normalizedUrl = normalizeTemplateUrl(url);
        HttpURLConnection connection = openTemplateConnection(normalizedUrl);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(config.templateConnectTimeoutMillis);
        connection.setReadTimeout(config.templateReadTimeoutMillis);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "mdt-jump-landmine/1.0");
        connection.connect();
        try (InputStream stream = connection.getInputStream()) {
            String body = readFully(stream);
            return parseTemplateResponse(body);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openTemplateConnection(String normalizedUrl) throws IOException {
        URL target = new URL(normalizedUrl);
        if (!config.templateProxyEnabled || config.templateProxyHost == null || config.templateProxyHost.trim().isEmpty()) {
            return (HttpURLConnection) target.openConnection();
        }

        Proxy.Type proxyType = "socks".equalsIgnoreCase(config.templateProxyType)
            ? Proxy.Type.SOCKS
            : Proxy.Type.HTTP;
        Proxy proxy = new Proxy(proxyType, new InetSocketAddress(config.templateProxyHost.trim(), config.templateProxyPort));
        return (HttpURLConnection) target.openConnection(proxy);
    }

    private String normalizeTemplateUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.startsWith("https://github.com/") && trimmed.contains("/blob/")) {
            return trimmed.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/");
        }
        return trimmed;
    }

    private TemplateResponse parseTemplateResponse(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) {
            OfficialServerListEntry[] entries = json.fromJson(OfficialServerListEntry[].class, trimmed);
            return convertOfficialServerList(entries);
        }

        TemplateResponse response = json.fromJson(TemplateResponse.class, trimmed);
        return response == null ? new TemplateResponse() : response;
    }

    private TemplateResponse convertOfficialServerList(OfficialServerListEntry[] sourceEntries) {
        TemplateResponse response = new TemplateResponse();
        if (sourceEntries == null) return response;

        for (OfficialServerListEntry source : sourceEntries) {
            if (source == null || source.address == null) continue;
            for (int i = 0; i < source.address.size(); i++) {
                String address = source.address.get(i);
                if (address == null || address.trim().isEmpty()) continue;

                BridgeTarget target = new BridgeTarget();
                target.enabled = true;
                target.targetType = "server";
                target.displayName = buildOfficialDisplayName(source.name, i, source.address.size());
                target.description = "官方服务器列表";
                target.buildLabel = "官方列表";
                target.category = firstNonBlank(source.category, "");

                HostAndPort parsed = parseHostAndPort(address.trim());
                target.host = parsed.host;
                target.port = parsed.port;

                response.entries.add(target);
            }
        }

        return response;
    }

    private String buildOfficialDisplayName(String baseName, int index, int total) {
        String safeName = firstNonBlank(baseName, "服务器");
        if (total <= 1) return safeName;
        return safeName + " #" + (index + 1);
    }

    private HostAndPort parseHostAndPort(String address) {
        HostAndPort parsed = new HostAndPort();
        int split = address.lastIndexOf(':');
        if (split > 0 && split < address.length() - 1) {
            String maybePort = address.substring(split + 1).trim();
            try {
                parsed.host = address.substring(0, split).trim();
                parsed.port = Integer.parseInt(maybePort);
                return parsed;
            } catch (NumberFormatException ignored) {
            }
        }

        parsed.host = address.trim();
        parsed.port = 6567;
        return parsed;
    }

    private String resolveFloorCategory(Block floor) {
        if (floor == null || floor.name == null) return "";
        String name = floor.name;
        if ("metal-floor-4".equals(name)) return "custom";
        if ("metal-tiles-4".equals(name)) return "custom";
        if ("hotrock".equals(name)) return "pvp";
        if ("bluemat".equals(name)) return "attack";
        if ("grass".equals(name)) return "survival";
        return "";
    }

    private String resolveSlotType(int corePriority, String floorCategory) {
        if (corePriority >= 0 && corePriority < 4) {
            return "vip";
        }
        if ("custom".equalsIgnoreCase(floorCategory)) {
            return "custom";
        }
        return "auto";
    }

    private boolean isReservedCategory(String category) {
        String normalized = safeLower(category);
        return "vip".equals(normalized) || "custom".equals(normalized);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private Comparator<BridgeTarget> buildComparator() {
        return new Comparator<BridgeTarget>() {
            @Override
            public int compare(BridgeTarget left, BridgeTarget right) {
                if (!config.isLatencySort()) {
                    int leftIndex = left.fixedIndex < 0 ? Integer.MAX_VALUE : left.fixedIndex;
                    int rightIndex = right.fixedIndex < 0 ? Integer.MAX_VALUE : right.fixedIndex;
                    int cmp = Integer.compare(leftIndex, rightIndex);
                    if (cmp != 0) return cmp;
                    return left.displayKey().compareToIgnoreCase(right.displayKey());
                }

                BridgeStatus leftStatus = statuses.get(left.identityKey());
                BridgeStatus rightStatus = statuses.get(right.identityKey());
                int leftPing = leftStatus != null && leftStatus.online && leftStatus.ping >= 0 ? leftStatus.ping : Integer.MAX_VALUE;
                int rightPing = rightStatus != null && rightStatus.online && rightStatus.ping >= 0 ? rightStatus.ping : Integer.MAX_VALUE;
                int cmp = Integer.compare(leftPing, rightPing);
                if (cmp != 0) return cmp;
                return left.displayKey().compareToIgnoreCase(right.displayKey());
            }
        };
    }

    private Comparator<BridgeTarget> buildScoreComparator() {
        return new Comparator<BridgeTarget>() {
            @Override
            public int compare(BridgeTarget left, BridgeTarget right) {
                boolean leftOnline = isOnlineTarget(left);
                boolean rightOnline = isOnlineTarget(right);
                if (leftOnline != rightOnline) {
                    return leftOnline ? -1 : 1;
                }

                int cmp = Integer.compare(scoreTarget(right), scoreTarget(left));
                if (cmp != 0) return cmp;
                cmp = Integer.compare(versionWeight(right), versionWeight(left));
                if (cmp != 0) return cmp;
                return left.displayKey().compareToIgnoreCase(right.displayKey());
            }
        };
    }

    private int scoreTarget(BridgeTarget target) {
        BridgeStatus status = statuses.get(target.identityKey());
        if (status == null || !status.online) return Integer.MIN_VALUE / 4;

        int score = 0;
        int ping = status.ping >= 0 ? status.ping : Integer.MAX_VALUE;
        if (ping <= 24) score += 100;
        else if (ping <= 50) score += 70;
        else if (ping <= 100) score += 45;
        else if (ping <= 150) score += 40;
        else if (ping <= 200) score += 30;
        else if (ping <= 300) score += 20;
        else if (ping <= 400) score -= 10;

        if (status.players > 0) {
            score += status.players * 3;
        }
        return score;
    }

    private boolean isOnlineTarget(BridgeTarget target) {
        BridgeStatus status = statuses.get(target.identityKey());
        return status != null && status.online;
    }

    private int versionWeight(BridgeTarget target) {
        BridgeStatus status = statuses.get(target.identityKey());
        String version = status != null ? firstNonBlank(status.versionLabel, target.versionLabel) : target.versionLabel;
        if (version == null || version.trim().isEmpty()) return 0;

        int value = 0;
        String[] parts = version.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < parts.length && i < 3; i++) {
            try {
                value = value * 1000 + Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                value *= 1000;
            }
        }
        return value;
    }

    private BridgeStatus fromHost(BridgeTarget target, Host host) {
        BridgeStatus status = new BridgeStatus();
        status.online = true;
        status.ping = host.ping;
        status.players = host.players;
        status.playerLimit = host.playerLimit;
        status.name = firstNonBlank(host.name, target.displayName);
        status.description = firstNonBlank(host.description, target.description);
        status.buildLabel = firstNonBlank(target.buildLabel, host.versionType);
        status.versionLabel = firstNonBlank(target.versionLabel, String.valueOf(host.version));
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    private boolean isServerTarget(BridgeTarget target) {
        return target != null
            && target.host != null
            && !target.host.trim().isEmpty()
            && !"web".equalsIgnoreCase(target.targetType)
            && !"http".equalsIgnoreCase(target.targetType)
            && !"https".equalsIgnoreCase(target.targetType);
    }

    private int resolveRenderWidth(BridgeTarget target) {
        if (config.isManualRenderSize() && target.renderWidth > 0) {
            return target.renderWidth;
        }
        return target.renderWidth > 0 && !config.isManualRenderSize()
            ? target.renderWidth
            : config.defaultRenderWidth;
    }

    private int resolveRenderHeight(BridgeTarget target) {
        if (config.isManualRenderSize() && target.renderHeight > 0) {
            return target.renderHeight;
        }
        return target.renderHeight > 0 && !config.isManualRenderSize()
            ? target.renderHeight
            : config.defaultRenderHeight;
    }

    private int resolveWindowColumns(BridgeTarget target) {
        if (config.isManualWindowSize() && target.windowColumns > 0) {
            return target.windowColumns;
        }
        return target.windowColumns > 0 && !config.isManualWindowSize()
            ? target.windowColumns
            : config.defaultWindowColumns;
    }

    private int resolveWindowRows(BridgeTarget target) {
        if (config.isManualWindowSize() && target.windowRows > 0) {
            return target.windowRows;
        }
        return target.windowRows > 0 && !config.isManualWindowSize()
            ? target.windowRows
            : config.defaultWindowRows;
    }

    private List<String> buildLabelLines(BridgeTarget target, BridgeStatus status, int renderWidth, int renderHeight) {
        int titleMax = Math.max(12, renderWidth * 7);
        int descMax = Math.max(14, renderWidth * 8);
        int metaMax = Math.max(10, renderWidth * 5);
        List<String> lines = new ArrayList<String>();

        if (status == null || !status.online) {
            lines.add("[scarlet]空位[]");
            lines.add("[gray]服务器离线[]");
            lines.add("[gold]版本 " + trimToLength(firstNonBlank(status == null ? "" : status.versionLabel, target.versionLabel, "-"), metaMax) + "[]");
            lines.add("[darkgray]区域 " + renderWidth + "x" + renderHeight + "[]");
            return lines;
        }

        lines.add("[accent]" + trimToLength(firstNonBlank(status.name, target.displayName, target.host, target.targetUrl), titleMax) + "[]");

        String desc = trimToLength(firstNonBlank(status.description, target.description, target.targetType), descMax);
        if (!desc.isEmpty()) {
            lines.add("[lightgray]" + desc + "[]");
        }

        if (status.online) {
            String players = status.players >= 0 ? status.players + "/" + Math.max(status.playerLimit, 0) : "-/-";
            String ping = status.ping >= 0 ? status.ping + "ms" : "--";
            lines.add("[white]" + players + "[] [sky]" + ping + "[]");
        } else {
            lines.add("[scarlet]离线[]");
        }

        lines.add("[gray]"
            + trimToLength(firstNonBlank(status.buildLabel, target.buildLabel, "unknown"), metaMax)
            + " / "
            + trimToLength(firstNonBlank(status.versionLabel, target.versionLabel, "-"), metaMax)
            + "[]");

        lines.add("[gold]版本 "
            + trimToLength(firstNonBlank(status.versionLabel, target.versionLabel, "-"), metaMax)
            + "[]");

        lines.add("[darkgray]区域 " + renderWidth + "x" + renderHeight + "[]");

        int maxLines = Math.max(1, renderHeight);
        if (lines.size() <= maxLines) {
            return lines;
        }

        List<String> condensed = new ArrayList<String>();
        condensed.add(lines.get(0));
        if (maxLines >= 2) condensed.add(lines.get(1));
        if (maxLines >= 3) condensed.add(lines.get(2));
        if (maxLines >= 4) condensed.add(lines.get(3));
        if (maxLines >= 5) condensed.add(lines.get(4));
        while (condensed.size() > maxLines) {
            condensed.remove(condensed.size() - 2);
        }
        return condensed;
    }

    private List<String> buildLabelLinesCn(BridgeTarget target, BridgeStatus status, int renderWidth, int renderHeight) {
        int titleMax = Math.max(12, renderWidth * 7);
        int descMax = Math.max(14, renderWidth * 8);
        int metaMax = Math.max(10, renderWidth * 5);
        List<String> lines = new ArrayList<String>();

        if (status == null || !status.online) {
            lines.add("[scarlet]空槽位[]");
            lines.add("[gray]服务器离线[]");
            lines.add("[gold]版本 " + trimToLength(firstNonBlank(status == null ? "" : status.versionLabel, target.versionLabel, "-"), metaMax) + "[]");
            lines.add("[darkgray]区域 " + renderWidth + "x" + renderHeight + "[]");
            return lines;
        }

        lines.add("[accent]" + trimToLength(firstNonBlank(status.name, target.displayName, target.host, target.targetUrl), titleMax) + "[]");

        String desc = trimToLength(firstNonBlank(status.description, target.description, target.targetType), descMax);
        if (!desc.isEmpty()) {
            lines.add("[lightgray]" + desc + "[]");
        }

        String players = status.players >= 0 ? status.players + "/" + Math.max(status.playerLimit, 0) : "-/-";
        String ping = status.ping >= 0 ? status.ping + "ms" : "--";
        lines.add("[white]" + players + "[] [sky]" + ping + "[]");
        lines.add("[gold]版本 " + trimToLength(firstNonBlank(status.versionLabel, target.versionLabel, "-"), metaMax) + "[]");
        lines.add("[gray]构建 " + trimToLength(firstNonBlank(status.buildLabel, target.buildLabel, "unknown"), metaMax) + "[]");
        lines.add("[darkgray]区域 " + renderWidth + "x" + renderHeight + "[]");

        int maxLines = Math.max(1, renderHeight);
        if (lines.size() <= maxLines) {
            return lines;
        }

        List<String> condensed = new ArrayList<String>();
        condensed.add(lines.get(0));
        if (maxLines >= 2) condensed.add(lines.get(1));
        if (maxLines >= 3) condensed.add(lines.get(2));
        if (maxLines >= 4) condensed.add(lines.get(3));
        if (maxLines >= 5) condensed.add(lines.get(4));
        while (condensed.size() > maxLines) {
            condensed.remove(condensed.size() - 2);
        }
        return condensed;
    }

    private int resolveDefaultCoreX() {
        return Vars.state.rules.defaultTeam.cores().isEmpty()
            ? Vars.world.width() / 2
            : Vars.state.rules.defaultTeam.cores().first().tileX();
    }

    private int resolveDefaultCoreY() {
        return Vars.state.rules.defaultTeam.cores().isEmpty()
            ? Vars.world.height() / 2
            : Vars.state.rules.defaultTeam.cores().first().tileY();
    }

    private float distanceSquared(int x, int y, int coreX, int coreY) {
        int dx = x - coreX;
        int dy = y - coreY;
        return dx * dx + dy * dy;
    }

    private String tileKey(int x, int y) {
        return x + ":" + y;
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private String formatTargetAddress(String host, int port) {
        if (host == null || host.trim().isEmpty()) return "";
        int resolvedPort = port > 0 ? port : 6567;
        return host.trim() + ":" + resolvedPort;
    }

    private String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private String trimToLength(String value, int max) {
        if (value == null) return "";
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        if (containsMarkupOrSpecialGlyph(normalized)) {
            return normalized;
        }
        if (normalized.length() <= max) return normalized;
        return normalized.substring(0, Math.max(0, max - 3)) + "...";
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

    public static class RenderedMine {
        public int x;
        public int y;
        public int renderWidth;
        public int renderHeight;
        public int windowColumns;
        public int windowRows;
        public int minTileX;
        public int maxTileX;
        public int minTileY;
        public int maxTileY;
        public BridgeTarget target;
        public BridgeStatus status;
        public final List<LabelPlacement> placements = new ArrayList<LabelPlacement>();

        public String launchUri(String defaultScheme) {
            return target.resolveLaunchUri(defaultScheme);
        }

        public boolean isServerTarget() {
            return target.host != null && !target.host.trim().isEmpty()
                && !"web".equalsIgnoreCase(target.targetType)
                && !"http".equalsIgnoreCase(target.targetType)
                && !"https".equalsIgnoreCase(target.targetType);
        }
    }

    public static class LabelPlacement {
        public int labelId;
        public float worldX;
        public float worldY;
        public String text;
        public int regionWidth;
        public int regionHeight;
    }

    private static class RenderRegion {
        int minX;
        int maxX;
        int minY;
        int maxY;
    }

    private static class MineSlot {
        final int x;
        final int y;
        final float distance;

        MineSlot(int x, int y, float distance) {
            this.x = x;
            this.y = y;
            this.distance = distance;
        }
    }

    private static class TemplateResponse {
        public List<BridgeTarget> entries = new ArrayList<BridgeTarget>();
    }

    private static class OfficialServerListEntry {
        public String name;
        public String category;
        public List<String> address = new ArrayList<String>();
    }

    private static class HostAndPort {
        String host = "";
        int port = 6567;
    }

    private static class CoreStatsLabel {
        int labelId;
        float worldX;
        float worldY;
        String text;
    }
}
