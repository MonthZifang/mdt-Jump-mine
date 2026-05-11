package com.mdt.jumpmine.model;

import java.util.ArrayList;
import java.util.List;

public class TransitSummary {
    public long generatedAt;
    public int today;
    public int sevenDays;
    public int thirtyDays;
    public int total;
    public List<TargetStat> topTargets = new ArrayList<TargetStat>();

    public static class TargetStat {
        public String targetKey = "";
        public String targetName = "";
        public String targetType = "";
        public String host = "";
        public int port = 6567;
        public int count;
    }
}
