package com.mdt.jumpmine.model;

import java.util.ArrayList;
import java.util.List;

public class MapMineConfig {
    public String mapName = "";
    public long generatedAt = 0L;
    public List<MineBinding> mines = new ArrayList<MineBinding>();

    public static class MineBinding extends BridgeTarget {
        public int x;
        public int y;
        public String floorBlock = "";
        public String floorCategory = "";
        public String slotType = "auto";
        public int corePriority = -1;
    }
}
