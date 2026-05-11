package com.mdt.jumpmine.model;

import java.util.ArrayList;
import java.util.List;

public class TransitRecordBook {
    public List<TransitRecord> records = new ArrayList<TransitRecord>();

    public static class TransitRecord {
        public long timestamp;
        public String playerUuid = "";
        public String playerName = "";
        public String targetKey = "";
        public String targetName = "";
        public String targetType = "";
        public String host = "";
        public int port = 6567;
    }
}
