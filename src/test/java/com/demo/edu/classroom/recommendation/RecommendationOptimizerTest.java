package com.demo.edu.classroom.recommendation;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.demo.edu.classroom.recommendation.RecommendationOptimizer.*;

/** 固定输入工程评测；不把这些构造样本的得分解释为学生学习效果。 */
class RecommendationOptimizerTest {
    private static Candidate candidate(String id, int seconds, Set<String> goals, String purpose,
                                       double relevance, double complementarity, double preference, String duplicate) {
        return new Candidate(id, seconds, goals, purpose, relevance, complementarity, preference, true, duplicate);
    }
    private static Candidate simple(String id, int seconds, String goal) {
        return candidate(id, seconds, Set.of(goal), "示范", 1, 1, 0, "");
    }
    private static Input input(List<Candidate> pool, Set<String> goals, int budget) {
        return new Input(pool, goals, budget, false, Map.of());
    }

    @Test void objectiveUsesTargetIntersectionMeansAndSamePurposeRedundancy() {
        var a = candidate("a", 60, Set.of("g1", "g2", "unrelated"), "示范", .8, .5, .2, "");
        var b = candidate("b", 90, Set.of("g3"), "示范", .6, .9, .6, "");
        Input preference = new Input(List.of(a, b), Set.of("g1", "g2", "g3"), 600, true, Map.of("a\nb", .4));
        Score score = evaluate(preference, List.of("b", "a")).orElseThrow();
        assertEquals(new Score(.71, 1, .7, .7, .4, .4, 150), score);
        Input noPreference = new Input(preference.candidates(), preference.targetGoals(), 600, false, preference.pairSimilarities());
        assertEquals(.753333, evaluate(noPreference, List.of("a", "b")).orElseThrow().total());
        assertEquals(0, evaluate(noPreference, List.of("a", "b")).orElseThrow().preference());
        assertEquals(score, RecommendationVerifier.recompute(preference, List.of("a", "b")).orElseThrow());
    }

    @Test void unrelatedGoalsDoNotRaiseCoverageAndCrossPurposeDoesNotCountAsRedundancy() {
        var a = simple("a", 50, "other");
        var b = candidate("b", 50, Set.of("g"), "观察", 1, 1, 0, "");
        Input input = new Input(List.of(a, b), Set.of("g"), 100, false, Map.of("a\nb", 1d));
        assertEquals(0, evaluate(input, List.of("a")).orElseThrow().coverage());
        assertEquals(0, evaluate(input, List.of("a", "b")).orElseThrow().redundancy());
        assertEquals(List.of("b"), select(input).ids());
    }

    @Test void missingSamePurposeSimilarityUsesGoalJaccardInsteadOfClaimingNoOverlap() {
        var a = simple("a", 50, "g");
        var b = simple("b", 50, "g");
        Input input = input(List.of(a, b), Set.of("g"), 100);
        assertEquals(1, evaluate(input, List.of("a", "b")).orElseThrow().redundancy());
        assertEquals(List.of("a"), select(input).ids());
    }

    @Test void hardConstraintsExcludeIneligibleUnknownDurationDuplicateAndOverBudget() {
        var a = simple("a", 60, "a");
        var b = candidate("b", 50, Set.of("b"), "示范", 1, 1, 0, "same-video");
        var c = candidate("c", 50, Set.of("c"), "观察", 1, 1, 0, "same-video");
        var unknown = simple("unknown", 0, "unknown");
        var ineligible = new Candidate("blocked", 1, Set.of("a", "b", "c"), "示范", 1, 1, 0, false, "");
        Input input = input(List.of(a, b, c, unknown, ineligible), Set.of("a", "b", "c"), 100);
        for (List<String> invalid : List.of(List.of("a", "b"), List.of("b", "c"), List.of("unknown"),
                List.of("blocked"), List.of("a", "a"), List.of("absent"))) {
            assertTrue(evaluate(input, invalid).isEmpty());
            assertTrue(RecommendationVerifier.recompute(input, invalid).isEmpty());
        }
        assertTrue(RecommendationVerifier.verify(input, select(input)).optimal());
        assertTrue(select(input).score().durationSeconds() <= 100);
    }

    @Test void durationSumCannotOverflowIntoBudget() {
        Input input = input(List.of(simple("a", Integer.MAX_VALUE, "a"), simple("b", Integer.MAX_VALUE, "b")),
                Set.of("a", "b"), Integer.MAX_VALUE);
        assertTrue(evaluate(input, List.of("a", "b")).isEmpty());
        assertTrue(RecommendationVerifier.recompute(input, List.of("a", "b")).isEmpty());
    }

    @Test void emptyPoolEmptyTargetsAndNoFeasibleSelectionAreSafeEmptyResults() {
        for (Input input : List.of(input(List.of(), Set.of("g"), 600), input(List.of(simple("a", 50, "g")), Set.of(), 600),
                input(List.of(simple("a", 0, "g")), Set.of("g"), 600))) {
            Selection selection = select(input);
            assertTrue(selection.ids().isEmpty());
            assertEquals(0, selection.score().total());
            assertTrue(RecommendationVerifier.verify(input, selection).optimal());
            assertFalse(reuse(input, List.of(), .02).reused());
        }
    }

    @Test void maximumPoolEnumeratesExactly1350NonemptyCombinations() {
        List<Candidate> pool = IntStream.range(0, 20).mapToObj(i -> simple("v" + i, 10, "g" + i)).toList();
        Input input = input(pool, Set.of("g1", "g2", "g3"), 600);
        Selection result = select(input);
        assertEquals(1350, result.evaluatedCombinations());
        assertEquals(List.of("v1", "v2", "v3"), result.ids());
        var independent = RecommendationVerifier.verify(input, result);
        assertTrue(independent.optimal());
        assertEquals(1350, independent.evaluatedCombinations());
    }

    @Test void featureQuantizationMakesSubPrecisionChangesReproducible() {
        var a = candidate("a", 40, Set.of("g"), "示范", .800041, .700041, .500041, "");
        var b = candidate("b", 40, Set.of("g"), "示范", .800049, .700049, .500049, "");
        assertEquals(a.relevance(), b.relevance());
        Input input = input(List.of(b, a), Set.of("g"), 100);
        assertEquals(List.of("a"), select(input).ids());
    }

    @Test void tieBreakUsesShortestThenStableIdentifierAndSelectionOrderIsCanonical() {
        Input input = input(List.of(simple("z", 50, "g"), simple("c", 40, "g"), simple("a", 40, "g")), Set.of("g"), 100);
        assertEquals(List.of("a"), select(input).ids());
        assertTrue(RecommendationVerifier.verify(input, select(input)).optimal());
    }

    @Test void equalObjectiveAndTimePreferLowerRedundancyBeforeIdentifier() {
        var a = candidate("a", 10, Set.of("g1"), "示范", .6, .6, 0, "");
        var b = candidate("b", 10, Set.of("g2"), "示范", .6, .6, 0, "");
        var c = candidate("c", 10, Set.of("g1"), "示范", .3, .15, 0, "");
        var d = candidate("d", 10, Set.of("g2"), "示范", .3, .15, 0, "");
        Map<String, Double> pairs = Map.of("a\nb", 1d, "a\nc", 1d, "a\nd", 1d, "b\nc", 1d, "b\nd", 1d, "c\nd", 0d);
        Input input = new Input(List.of(a, b, c, d), Set.of("g1", "g2"), 20, false, pairs);
        assertEquals(evaluate(input, List.of("a", "b")).orElseThrow().total(), evaluate(input, List.of("c", "d")).orElseThrow().total());
        assertEquals(List.of("c", "d"), select(input).ids());
        assertTrue(RecommendationVerifier.verify(input, select(input)).optimal());
    }

    @Test void combinationSearchCanBeatBestSingleItemGreedyUnderTimeBudget() {
        var expensive = simple("a", 80, "g1");
        var b = candidate("b", 50, Set.of("g1"), "示范", .9, .9, 0, "");
        var c = candidate("c", 50, Set.of("g2"), "观察", .9, .9, 0, "");
        Input input = input(List.of(expensive, b, c), Set.of("g1", "g2"), 100);
        assertTrue(evaluate(input, List.of("a")).orElseThrow().total() > evaluate(input, List.of("b")).orElseThrow().total());
        assertEquals(List.of("b", "c"), select(input).ids());
        assertTrue(select(input).score().total() > evaluate(input, List.of("a")).orElseThrow().total());
    }

    @Test void reuseRecomputesOldFeasibilityAndAppliesGapInCurrentModel() {
        var old = candidate("old", 30, Set.of("g"), "示范", .95, 1, 0, "");
        Input input = input(List.of(old, simple("new", 30, "g")), Set.of("g"), 100);
        ReuseDecision accepted = reuse(input, List.of("old"), .02);
        assertTrue(accepted.reused());
        assertEquals(.016667, accepted.gap());
        assertEquals(List.of("old"), accepted.ids());
        ReuseDecision rejected = reuse(input, List.of("old"), .01);
        assertFalse(rejected.reused());
        assertEquals(List.of("new"), rejected.ids());
        assertFalse(reuse(input, List.of("removed-id"), .02).reused());
        Input expired = input(List.of(new Candidate("old", 30, Set.of("g"), "示范", .95, 1, 0, false, ""), simple("new", 30, "g")), Set.of("g"), 100);
        assertEquals("OLD_SELECTION_INFEASIBLE", reuse(expired, List.of("old"), 1).reason());
    }

    @Test void invalidInputAndPairKeysAreRejectedWithoutSilentClamping() {
        assertThrows(IllegalArgumentException.class, () -> simple("bad\nid", 1, "g"));
        assertThrows(IllegalArgumentException.class, () -> simple("a", -1, "g"));
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
            assertThrows(IllegalArgumentException.class, () -> candidate("a", 1, Set.of("g"), "示范", value, 1, 0, ""));
        }
        var a = simple("a", 1, "g"); var b = simple("b", 1, "g");
        assertThrows(IllegalArgumentException.class, () -> input(List.of(a, a), Set.of("g"), 10));
        assertThrows(IllegalArgumentException.class, () -> input(List.of(a), Set.of("g"), 0));
        assertThrows(IllegalArgumentException.class, () -> new Input(List.of(a, b), Set.of("g"), 10, false, Map.of("b\na", .5)));
        assertThrows(IllegalArgumentException.class, () -> new Input(List.of(a, b), Set.of("g"), 10, false, Map.of("a\nx", .5)));
        assertThrows(IllegalArgumentException.class, () -> input(IntStream.range(0, 21).mapToObj(i -> simple("v" + i, 1, "g")).toList(), Set.of("g"), 100));
        assertThrows(IllegalArgumentException.class, () -> reuse(input(List.of(a), Set.of("g"), 10), List.of("a"), Double.NaN));
    }

    @Test void recordsDoNotObserveCallerMutation() {
        Set<String> goals = new HashSet<>(Set.of("g"));
        Candidate candidate = simple("a", 10, "g");
        List<Candidate> pool = new ArrayList<>(List.of(candidate));
        Input input = new Input(pool, goals, 20, false, new HashMap<>());
        pool.clear(); goals.clear();
        assertEquals(List.of("a"), select(input).ids());
        assertThrows(UnsupportedOperationException.class, () -> input.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.targetGoals().clear());
    }

    @Test void independentVerifierRejectsFabricatedScoreAndNonOptimalClaim() {
        Input input = input(List.of(simple("a", 20, "g"), simple("b", 10, "g")), Set.of("g"), 50);
        Selection result = select(input);
        Selection forged = new Selection(result.ids(), new Score(42, 1, 1, 1, 0, 0, 10), 3, ALGORITHM_VERSION);
        assertFalse(RecommendationVerifier.verify(input, forged).valid());
        Selection slower = new Selection(List.of("a"), evaluate(input, List.of("a")).orElseThrow(), 3, ALGORITHM_VERSION);
        assertTrue(RecommendationVerifier.verify(input, slower).valid());
        assertFalse(RecommendationVerifier.verify(input, slower).optimal());
    }

    @Test void randomSmallPoolsMatchIndependentVerifierAndIgnoreInputPermutation() {
        Random random = new Random(20261016L);
        for (int trial = 0; trial < 120; trial++) {
            int count = 1 + random.nextInt(9);
            List<Candidate> pool = new ArrayList<>();
            Map<String, Double> pairs = new HashMap<>();
            for (int i = 0; i < count; i++) {
                Set<String> goals = new HashSet<>();
                for (int g = 0; g < 4; g++) if (random.nextBoolean()) goals.add("g" + g);
                pool.add(new Candidate("v" + i, random.nextInt(91), goals, "purpose" + random.nextInt(3),
                        random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextInt(8) != 0,
                        random.nextInt(4) == 0 ? "duplicate" : ""));
                for (int j = 0; j < i; j++) if (random.nextBoolean()) pairs.put("v" + j + "\nv" + i, random.nextDouble());
            }
            Input before = new Input(pool, Set.of("g0", "g1", "g2"), 100 + random.nextInt(80), random.nextBoolean(), pairs);
            Selection original = select(before);
            var verified = RecommendationVerifier.verify(before, original);
            assertTrue(verified.optimal(), "trial=" + trial + " " + verified);
            Collections.shuffle(pool, random);
            Input after = new Input(pool, before.targetGoals(), before.budgetSeconds(), before.preferenceAvailable(), pairs);
            assertEquals(original, select(after), "trial=" + trial);
        }
    }
}
