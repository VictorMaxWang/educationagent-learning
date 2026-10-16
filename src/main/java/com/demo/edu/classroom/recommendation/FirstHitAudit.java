package com.demo.edu.classroom.recommendation;

import java.util.*;

/**
 * M002 联合首次命中相容性的离散方法适配：同一资源在两排序中共享一个相关性标签。
 * 枚举未知标签的相容赋值，审计固定候选排序的检查次数差；并非连续几何积分器或教学效果证书。
 */
public final class FirstHitAudit {
    public static final int MAX_UNKNOWN = 12;
    private FirstHitAudit() {}
    public enum Status { COMPUTED, NOT_COMPUTED }

    /** 差额定义为 left 检查次数减 right；无命中花费该排序的完整长度，并单独记录次数。 */
    public record AuditResult(Status status, Integer minCostDifference, Integer maxCostDifference,
                              int unknownCount, int evaluatedAssignments, int noHitLeftAssignments,
                              int noHitRightAssignments, Map<String, Boolean> minWitness,
                              Map<String, Boolean> maxWitness, String reason) {
        public AuditResult { minWitness = Map.copyOf(minWitness); maxWitness = Map.copyOf(maxWitness); }
    }

    /**
     * labels 的 true/false/null 分别为适合、不适合、未知；缺失标签同样为未知。
     * sourceFailure 必须由调用方明确传入；检索失败时整个比较不计算，不能把失败当负标签。
     */
    public static AuditResult audit(List<String> left, List<String> right, Map<String, Boolean> labels,
                                    boolean sourceFailure) {
        List<String> a = ranking(left), b = ranking(right);
        Objects.requireNonNull(labels, "labels");
        Set<String> union = new TreeSet<>(a); union.addAll(b);
        List<String> unknown = union.stream().filter(id -> labels.get(id) == null).toList();
        if (sourceFailure) return skipped(unknown.size(), "SOURCE_FAILURE");
        if (unknown.size() > MAX_UNKNOWN) return skipped(unknown.size(), "UNKNOWN_LIMIT_EXCEEDED");
        Map<String, Boolean> shared = new HashMap<>();
        for (String id : union) if (labels.get(id) != null) shared.put(id, labels.get(id));
        int assignments = 1 << unknown.size();
        int minimum = Integer.MAX_VALUE, maximum = Integer.MIN_VALUE, noHitA = 0, noHitB = 0;
        Map<String, Boolean> minWitness = Map.of(), maxWitness = Map.of();
        for (int mask = 0; mask < assignments; mask++) {
            for (int bit = 0; bit < unknown.size(); bit++) shared.put(unknown.get(bit), (mask & (1 << bit)) != 0);
            Scan aScan = scan(a, shared), bScan = scan(b, shared);
            if (!aScan.hit()) noHitA++;
            if (!bScan.hit()) noHitB++;
            int difference = aScan.cost() - bScan.cost();
            if (difference < minimum) { minimum = difference; minWitness = new TreeMap<>(shared); }
            if (difference > maximum) { maximum = difference; maxWitness = new TreeMap<>(shared); }
        }
        return new AuditResult(Status.COMPUTED, minimum, maximum, unknown.size(), assignments,
                noHitA, noHitB, minWitness, maxWitness, "EXACT_SHARED_LABEL_ENUMERATION");
    }

    private record Scan(int cost, boolean hit) {}
    private static Scan scan(List<String> ranking, Map<String, Boolean> labels) {
        for (int i = 0; i < ranking.size(); i++) if (Boolean.TRUE.equals(labels.get(ranking.get(i))))
            return new Scan(i + 1, true);
        return new Scan(ranking.size(), false);
    }
    private static List<String> ranking(List<String> values) {
        if (values == null) throw new IllegalArgumentException("ranking is required");
        Set<String> unique = new HashSet<>();
        for (String id : values) {
            if (id == null || id.isBlank() || !unique.add(id))
                throw new IllegalArgumentException("ranking ids must be unique and nonblank");
        }
        return List.copyOf(values);
    }
    private static AuditResult skipped(int count, String reason) {
        return new AuditResult(Status.NOT_COMPUTED, null, null, count, 0, 0, 0, Map.of(), Map.of(), reason);
    }
}
