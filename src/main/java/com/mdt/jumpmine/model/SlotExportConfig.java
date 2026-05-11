package com.mdt.jumpmine.model;

import java.util.ArrayList;
import java.util.List;

public class SlotExportConfig {
    public String mapName = "";
    public long generatedAt = 0L;
    public String slotType = "";
    public List<SlotEntry> slots = new ArrayList<SlotEntry>();

    public static class SlotEntry extends BridgeTarget {
        public int x;
        public int y;
        public String floorBlock = "";
        public String floorCategory = "";
        public String slotType = "";
        public int corePriority = -1;
    }
}
