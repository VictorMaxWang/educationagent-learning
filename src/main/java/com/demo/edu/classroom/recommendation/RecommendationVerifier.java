package com.demo.edu.classroom.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import com.demo.edu.classroom.recommendation.RecommendationOptimizer.*;

/** M001 方法适配：使用独立约束检查、十进制重算与递归遍历，避免用选择器自证正确。 */
public final class RecommendationVerifier {
    private static final int SCALE = 6;
    private RecommendationVerifier() {}

    public record Verification(boolean valid, boolean optimal, String reason, List<String> independentlyBestIds,
                               Score independentlyBestScore, int evaluatedCombinations) {
        public Verification { independentlyBestIds = List.copyOf(independentlyBestIds); }
    }

    /** 复核选择分数及确定性最优项。支持 20 项小池，不借助 Optimizer 的 evaluate/select。 */
    public static Verification verify(Input input, Selection claimed) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(claimed, "claimed");
        Search search = new Search(input);
        if (!input.targetGoals().isEmpty()) search.visit(0, new ArrayList<>());
        Score zero = new Score(0, 0, 0, 0, 0, 0, 0);
        List<String> bestIds = search.bestIds == null ? List.of() : search.bestIds;
        Score bestScore = search.bestScore == null ? zero : search.bestScore;
        Score actual = claimed.ids().isEmpty() ? (search.bestIds == null ? zero : null)
                : recompute(input, claimed.ids()).orElse(null);
        boolean valid = actual != null && actual.equals(claimed.score());
        boolean optimal = valid && bestIds.equals(claimed.ids()) && bestScore.equals(claimed.score());
        String reason = !valid ? "INFEASIBLE_OR_SCORE_MISMATCH" : optimal ? "VERIFIED_FINITE_MODEL_OPTIMUM" : "VALID_BUT_NOT_CANONICAL_OPTIMUM";
        return new Verification(valid, optimal, reason, bestIds, bestScore, search.count);
    }

    /** 可独立验证复用的非最优组合；返回 empty 表示约束不成立。 */
    public static Optional<Score> recompute(Input input, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 3 || input.targetGoals().isEmpty()) return Optional.empty();
        Set<String> seen = new HashSet<>(), groups = new HashSet<>(), coverage = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        long seconds = 0;
        BigDecimal relevance = BigDecimal.ZERO, complementarity = BigDecimal.ZERO, preference = BigDecimal.ZERO;
        for (String id : ids) {
            if (id == null || !seen.add(id)) return Optional.empty();
            Candidate found = null;
            for (Candidate row : input.candidates()) if (row.id().equals(id)) { found = row; break; }
            if (found == null || !found.eligible() || found.durationSeconds() < 1) return Optional.empty();
            if (!found.duplicateGroup().isEmpty() && !groups.add(found.duplicateGroup())) return Optional.empty();
            seconds += found.durationSeconds();
            if (seconds > input.budgetSeconds()) return Optional.empty();
            for (String goal : found.goals()) if (input.targetGoals().contains(goal)) coverage.add(goal);
            relevance = relevance.add(BigDecimal.valueOf(found.relevance()));
            complementarity = complementarity.add(BigDecimal.valueOf(found.complementarity()));
            preference = preference.add(BigDecimal.valueOf(found.preference()));
            candidates.add(found);
        }
        BigDecimal c = quotient(BigDecimal.valueOf(coverage.size()), input.targetGoals().size());
        BigDecimal r = quotient(relevance, candidates.size()), m = quotient(complementarity, candidates.size());
        BigDecimal p = input.preferenceAvailable() ? quotient(preference, candidates.size()) : BigDecimal.ZERO;
        BigDecimal d = BigDecimal.ZERO;
        int pairCount = 0;
        for (int right = 1; right < candidates.size(); right++) for (int left = 0; left < right; left++) {
            pairCount++;
            Candidate a = candidates.get(left), b = candidates.get(right);
            if (!a.purpose().equals(b.purpose())) continue;
            String key = a.id().compareTo(b.id()) < 0 ? a.id() + "\n" + b.id() : b.id() + "\n" + a.id();
            Double supplied = input.pairSimilarities().get(key);
            if (supplied != null) d = d.add(BigDecimal.valueOf(supplied));
            else {
                Set<String> union = new HashSet<>(a.goals()); union.addAll(b.goals());
                long overlap = a.goals().stream().filter(b.goals()::contains).count();
                BigDecimal fallback = union.isEmpty() ? BigDecimal.ONE : BigDecimal.valueOf(overlap)
                        .divide(BigDecimal.valueOf(union.size()), 4, RoundingMode.HALF_UP);
                d = d.add(fallback);
            }
        }
        d = pairCount == 0 ? BigDecimal.ZERO : quotient(d, pairCount);
        BigDecimal numerator = c.multiply(BigDecimal.valueOf(4)).add(r.multiply(BigDecimal.valueOf(3)))
                .add(m.multiply(BigDecimal.valueOf(2)));
        BigDecimal total;
        if (input.preferenceAvailable()) total = numerator.add(p).subtract(d.multiply(BigDecimal.valueOf(2)))
                .divide(BigDecimal.TEN, SCALE, RoundingMode.HALF_UP);
        else total = numerator.multiply(BigDecimal.valueOf(5)).subtract(d.multiply(BigDecimal.valueOf(9)))
                .divide(BigDecimal.valueOf(45), SCALE, RoundingMode.HALF_UP);
        return Optional.of(new Score(total.doubleValue(), c.doubleValue(), r.doubleValue(), m.doubleValue(),
                p.doubleValue(), d.doubleValue(), (int) seconds));
    }

    private static BigDecimal quotient(BigDecimal numerator, int denominator) {
        return numerator.divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }
    private static final class Search {
        final Input input;
        final List<String> ids;
        int count;
        List<String> bestIds;
        Score bestScore;
        Search(Input input) { this.input = input; ids = input.candidates().stream().map(Candidate::id).sorted().toList(); }
        void visit(int start, List<String> chosen) {
            if (!chosen.isEmpty()) {
                count++;
                recompute(input, chosen).ifPresent(score -> {
                    if (bestScore == null || compare(score, chosen) < 0) {
                        bestIds = List.copyOf(chosen); bestScore = score;
                    }
                });
            }
            if (chosen.size() == 3) return;
            for (int index = start; index < ids.size(); index++) {
                chosen.add(ids.get(index)); visit(index + 1, chosen); chosen.removeLast();
            }
        }
        int compare(Score score, List<String> chosen) {
            int result = Double.compare(bestScore.total(), score.total());
            if (result == 0) result = Integer.compare(score.durationSeconds(), bestScore.durationSeconds());
            if (result == 0) result = Double.compare(score.redundancy(), bestScore.redundancy());
            if (result == 0) {
                for (int i = 0; i < Math.min(chosen.size(), bestIds.size()); i++) {
                    result = chosen.get(i).compareTo(bestIds.get(i));
                    if (result != 0) break;
                }
                if (result == 0) result = Integer.compare(chosen.size(), bestIds.size());
            }
            return result;
        }
    }
}
