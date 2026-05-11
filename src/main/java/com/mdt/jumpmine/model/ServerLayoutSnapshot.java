package com.mdt.jumpmine.model;

import java.util.ArrayList;
import java.util.List;

public class ServerLayoutSnapshot {
    public String mapName = "";
    public long generatedAt = 0L;
    public List<ServerSlot> slots = new ArrayList<ServerSlot>();

    public static class ServerSlot extends BridgeTarget {
        public int x;
        public int y;
        public String floorBlock = "";
        public String floorCategory = "";
        public String slotType = "auto";
        public int corePriority = -1;
    }
}
