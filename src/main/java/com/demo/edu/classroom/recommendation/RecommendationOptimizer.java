package com.demo.edu.classroom.recommendation;

import java.util.*;

/**
 * P017/CN119025756A 的多维评价适配：只覆盖本课目标，并将时长改为硬约束。
 * CN119336991A 的教学用途用于区分互补与重复。本实现穷举有限组合，不复现群智能更新公式。
 */
public final class RecommendationOptimizer {
    public static final String ALGORITHM_VERSION = "classroom-combination-v1";
    public static final int MAX_CANDIDATES = 20;
    public static final int MAX_SELECTION = 3;
    private static final long SCORE_SCALE = 1_000_000L;

    private RecommendationOptimizer() {}

    public record Candidate(String id, int durationSeconds, Set<String> goals, String purpose,
                            double relevance, double complementarity, double preference,
                            boolean eligible, String duplicateGroup) {
        public Candidate {
            requireToken(id, "candidate id");
            requireToken(purpose, "purpose");
            if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
            goals = tokens(goals, "candidate goals");
            relevance = feature(relevance);
            complementarity = feature(complementarity);
            preference = feature(preference);
            duplicateGroup = duplicateGroup == null || duplicateGroup.isBlank() ? "" : duplicateGroup;
            if (!duplicateGroup.isEmpty()) requireToken(duplicateGroup, "duplicateGroup");
        }
    }

    public record Input(List<Candidate> candidates, Set<String> targetGoals, int budgetSeconds,
                        boolean preferenceAvailable, Map<String, Double> pairSimilarities) {
        public Input {
            if (candidates == null || candidates.size() > MAX_CANDIDATES)
                throw new IllegalArgumentException("candidate count must be between 0 and 20");
            if (candidates.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("null candidate");
            candidates = List.copyOf(candidates);
            Set<String> ids = new HashSet<>();
            for (Candidate candidate : candidates) {
                if (!ids.add(candidate.id())) throw new IllegalArgumentException("duplicate candidate id");
            }
            targetGoals = tokens(targetGoals, "targetGoals");
            if (budgetSeconds <= 0) throw new IllegalArgumentException("budgetSeconds must be positive");
            if (pairSimilarities == null) throw new IllegalArgumentException("pairSimilarities is required");
            Map<String, Double> pairs = new TreeMap<>();
            for (var entry : pairSimilarities.entrySet()) {
                String key = entry.getKey();
                if (key == null) throw new IllegalArgumentException("null pair key");
                String[] parts = key.split("\n", -1);
                if (parts.length != 2 || parts[0].compareTo(parts[1]) >= 0
                        || !ids.contains(parts[0]) || !ids.contains(parts[1]))
                    throw new IllegalArgumentException("pair key must name two existing ids in ascending order");
                if (entry.getValue() == null) throw new IllegalArgumentException("null similarity");
                pairs.put(key, feature(entry.getValue()));
            }
            pairSimilarities = Collections.unmodifiableMap(pairs);
        }
    }

    public record Score(double total, double coverage, double relevance, double complementarity,
                        double preference, double redundancy, int durationSeconds) {}

    public record Selection(List<String> ids, Score score, int evaluatedCombinations, String algorithmVersion) {
        public Selection {
            ids = List.copyOf(ids);
            Objects.requireNonNull(score, "score");
        }
    }

    public record ReuseDecision(boolean reused, List<String> ids, double gap, Score selectedScore,
                                Selection optimum, String reason) {
        public ReuseDecision { ids = List.copyOf(ids); }
    }

    /** 在全部 1..3 项组合中求最优；无目标或无可行项返回零分空结果，不产生占位推荐。 */
    public static Selection select(Input input) {
        Objects.requireNonNull(input, "input");
        if (input.targetGoals().isEmpty()) return emptySelection(0);
        List<Candidate> pool = input.candidates().stream().sorted(Comparator.comparing(Candidate::id)).toList();
        List<String> winner = List.of();
        Score winnerScore = null;
        int evaluated = 0;
        for (int i = 0; i < pool.size(); i++) {
            List<String> one = List.of(pool.get(i).id());
            Score score = evaluate(input, one).orElse(null);
            evaluated++;
            if (better(one, score, winner, winnerScore)) { winner = one; winnerScore = score; }
            for (int j = i + 1; j < pool.size(); j++) {
                List<String> two = List.of(pool.get(i).id(), pool.get(j).id());
                score = evaluate(input, two).orElse(null);
                evaluated++;
                if (better(two, score, winner, winnerScore)) { winner = two; winnerScore = score; }
                for (int k = j + 1; k < pool.size(); k++) {
                    List<String> three = List.of(pool.get(i).id(), pool.get(j).id(), pool.get(k).id());
                    score = evaluate(input, three).orElse(null);
                    evaluated++;
                    if (better(three, score, winner, winnerScore)) { winner = three; winnerScore = score; }
                }
            }
        }
        return winnerScore == null ? emptySelection(evaluated)
                : new Selection(winner, winnerScore, evaluated, ALGORITHM_VERSION);
    }

    /**
     * 验证一个非空组合并重算分解分数。未知资源、重复、超时、未知时长或不适用返回 empty。
     * 输入结构本身不合法由 Input 构造器拒绝，不将坏输入伪装成正常的“无推荐”。
     */
    public static Optional<Score> evaluate(Input input, List<String> ids) {
        Objects.requireNonNull(input, "input");
        if (ids == null || ids.isEmpty() || ids.size() > MAX_SELECTION || input.targetGoals().isEmpty())
            return Optional.empty();
        Set<String> unique = new HashSet<>(ids);
        if (unique.size() != ids.size() || unique.contains(null)) return Optional.empty();
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate candidate : input.candidates()) byId.put(candidate.id(), candidate);
        List<Candidate> chosen = new ArrayList<>();
        Set<String> duplicateGroups = new HashSet<>();
        Set<String> covered = new HashSet<>();
        long seconds = 0;
        long sumRelevance = 0, sumComplementarity = 0, sumPreference = 0;
        for (String id : new TreeSet<>(ids)) {
            Candidate candidate = byId.get(id);
            if (candidate == null || !candidate.eligible() || candidate.durationSeconds() <= 0) return Optional.empty();
            if (!candidate.duplicateGroup().isEmpty() && !duplicateGroups.add(candidate.duplicateGroup()))
                return Optional.empty();
            seconds += candidate.durationSeconds();
            if (seconds > input.budgetSeconds()) return Optional.empty();
            chosen.add(candidate);
            covered.addAll(candidate.goals());
            sumRelevance += units(candidate.relevance());
            sumComplementarity += units(candidate.complementarity());
            sumPreference += units(candidate.preference());
        }
        covered.retainAll(input.targetGoals());
        long coverage = divide(covered.size() * SCORE_SCALE, input.targetGoals().size());
        long relevance = divide(sumRelevance, chosen.size());
        long complementarity = divide(sumComplementarity, chosen.size());
        long preference = input.preferenceAvailable() ? divide(sumPreference, chosen.size()) : 0;
        long pairSum = 0;
        int pairs = 0;
        for (int i = 0; i < chosen.size(); i++) for (int j = i + 1; j < chosen.size(); j++) {
            pairs++;
            Candidate a = chosen.get(i), b = chosen.get(j);
            if (a.purpose().equals(b.purpose())) pairSum += units(similarity(input, a, b));
        }
        // 分母为全部无序对；不同教学用途的对贡献 0，允许同知识点的示范与练习互补。
        long redundancy = pairs == 0 ? 0 : divide(pairSum, pairs);
        long total = input.preferenceAvailable()
                ? divide(4 * coverage + 3 * relevance + 2 * complementarity + preference - 2 * redundancy, 10)
                : divide(5 * (4 * coverage + 3 * relevance + 2 * complementarity) - 9 * redundancy, 45);
        return Optional.of(new Score(value(total), value(coverage), value(relevance), value(complementarity),
                value(preference), value(redundancy), (int) seconds));
    }

    /**
     * M001/CN120296243A 方法适配：在同一当前输入下复核旧组合与有限模型最优值之差。
     * 调用者仍须绑定课程内容与算法版本；这里的 gap 不是教学成效损失上界。
     */
    public static ReuseDecision reuse(Input input, List<String> oldIds, double epsilon) {
        if (!Double.isFinite(epsilon) || epsilon < 0) throw new IllegalArgumentException("epsilon must be finite and nonnegative");
        Selection optimum = select(input);
        Optional<Score> old = evaluate(input, oldIds);
        if (old.isEmpty()) return new ReuseDecision(false, optimum.ids(), 0, optimum.score(), optimum, "OLD_SELECTION_INFEASIBLE");
        long gap = Math.max(0, units(optimum.score().total()) - units(old.get().total()));
        if (gap <= Math.floor(epsilon * SCORE_SCALE + 1e-9))
            return new ReuseDecision(true, oldIds.stream().sorted().toList(), value(gap), old.get(), optimum, "WITHIN_MODEL_GAP_BUDGET");
        return new ReuseDecision(false, optimum.ids(), value(gap), optimum.score(), optimum, "MODEL_GAP_EXCEEDED");
    }

    /** 统一相似度键；id 不允许换行，因此不会产生键碰撞。 */
    public static String pairKey(String left, String right) {
        requireToken(left, "left id"); requireToken(right, "right id");
        if (left.equals(right)) throw new IllegalArgumentException("a pair needs distinct ids");
        return left.compareTo(right) < 0 ? left + "\n" + right : right + "\n" + left;
    }

    private static double similarity(Input input, Candidate a, Candidate b) {
        Double supplied = input.pairSimilarities().get(pairKey(a.id(), b.id()));
        if (supplied != null) return supplied;
        // 相似度缺失不等于证实“不重复”；回退到目标集合 Jaccard，并在业务证据中注明来源。
        Set<String> union = new HashSet<>(a.goals()); union.addAll(b.goals());
        if (union.isEmpty()) return 1;
        Set<String> overlap = new HashSet<>(a.goals()); overlap.retainAll(b.goals());
        return feature(overlap.size() / (double) union.size());
    }

    private static boolean better(List<String> ids, Score score, List<String> currentIds, Score current) {
        if (score == null) return false;
        if (current == null) return true;
        int order = Long.compare(units(score.total()), units(current.total()));
        if (order != 0) return order > 0;
        order = Integer.compare(score.durationSeconds(), current.durationSeconds());
        if (order != 0) return order < 0;
        order = Long.compare(units(score.redundancy()), units(current.redundancy()));
        if (order != 0) return order < 0;
        return String.join("\n", ids).compareTo(String.join("\n", currentIds)) < 0;
    }

    private static Selection emptySelection(int evaluated) {
        return new Selection(List.of(), new Score(0, 0, 0, 0, 0, 0, 0), evaluated, ALGORITHM_VERSION);
    }
    private static Set<String> tokens(Set<String> values, String label) {
        if (values == null) throw new IllegalArgumentException(label + " is required");
        TreeSet<String> result = new TreeSet<>();
        for (String token : values) { requireToken(token, label); result.add(token); }
        return Collections.unmodifiableSet(result);
    }
    private static void requireToken(String value, String label) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + " must be nonblank without control characters");
    }
    private static double feature(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("features must be finite numbers in [0, 1]");
        return Math.round(value * 10_000d) / 10_000d;
    }
    private static long units(double value) { return Math.round(value * SCORE_SCALE); }
    private static double value(long units) { return units / (double) SCORE_SCALE; }
    private static long divide(long numerator, long denominator) {
        // 整数 HALF_UP：评分不依赖候选遍历顺序或浮点累计误差。
        return numerator >= 0 ? (numerator + denominator / 2) / denominator
                : -((-numerator + denominator / 2) / denominator);
    }
}
