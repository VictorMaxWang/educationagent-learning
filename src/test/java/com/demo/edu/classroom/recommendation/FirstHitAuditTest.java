package com.demo.edu.classroom.recommendation;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class FirstHitAuditTest {
    @Test void identicalRankingsShareUnknownLabelsAndAlwaysHaveZeroDifference() {
        var result = FirstHitAudit.audit(List.of("a", "b", "c"), List.of("a", "b", "c"), Map.of(), false);
        assertEquals(FirstHitAudit.Status.COMPUTED, result.status());
        assertEquals(0, result.minCostDifference());
        assertEquals(0, result.maxCostDifference());
        assertEquals(8, result.evaluatedAssignments());
        assertEquals(1, result.noHitLeftAssignments());
        assertEquals(1, result.noHitRightAssignments());
    }

    @Test void sharedUnknownAssignmentProducesExactPermutationBoundsAndWitnesses() {
        var result = FirstHitAudit.audit(List.of("a", "b"), List.of("b", "a"), Map.of(), false);
        assertEquals(-1, result.minCostDifference());
        assertEquals(1, result.maxCostDifference());
        assertEquals(4, result.evaluatedAssignments());
        assertEquals(Map.of("a", true, "b", false), result.minWitness());
        assertEquals(Map.of("a", false, "b", true), result.maxWitness());
    }

    @Test void knownLabelsAndExplicitNullRemainDistinct() {
        Map<String, Boolean> labels = new HashMap<>();
        labels.put("a", true); labels.put("b", null); labels.put("c", false);
        var result = FirstHitAudit.audit(List.of("a", "b", "c"), List.of("c", "b", "a"), labels, false);
        assertEquals(-2, result.minCostDifference());
        assertEquals(-1, result.maxCostDifference());
        assertEquals(2, result.evaluatedAssignments());
        assertEquals(0, result.noHitLeftAssignments());
        assertEquals(0, result.noHitRightAssignments());
    }

    @Test void noHitUsesFullScanCostAndIsReportedSeparately() {
        var result = FirstHitAudit.audit(List.of("a", "b"), List.of("a"), Map.of("a", false, "b", false), false);
        assertEquals(1, result.minCostDifference());
        assertEquals(1, result.maxCostDifference());
        assertEquals(1, result.noHitLeftAssignments());
        assertEquals(1, result.noHitRightAssignments());
    }

    @Test void sourceFailureNeverBecomesIrrelevanceEvidence() {
        var result = FirstHitAudit.audit(List.of("a"), List.of("a"), Map.of("a", false), true);
        assertEquals(FirstHitAudit.Status.NOT_COMPUTED, result.status());
        assertEquals("SOURCE_FAILURE", result.reason());
        assertNull(result.minCostDifference());
        assertNull(result.maxCostDifference());
        assertEquals(0, result.evaluatedAssignments());
    }

    @Test void twelveUnknownsAreEnumeratedButThirteenRemainNotComputed() {
        List<String> ids = IntStream.range(0, 13).mapToObj(i -> "id" + i).toList();
        var twelve = FirstHitAudit.audit(ids.subList(0, 12), ids.subList(0, 12), Map.of(), false);
        assertEquals(4096, twelve.evaluatedAssignments());
        var thirteen = FirstHitAudit.audit(ids, ids, Map.of(), false);
        assertEquals(FirstHitAudit.Status.NOT_COMPUTED, thirteen.status());
        assertEquals("UNKNOWN_LIMIT_EXCEEDED", thirteen.reason());
        assertNull(thirteen.minCostDifference());
    }

    @Test void emptyRankingsAreExplicitZeroCostComparison() {
        var result = FirstHitAudit.audit(List.of(), List.of(), Map.of(), false);
        assertEquals(FirstHitAudit.Status.COMPUTED, result.status());
        assertEquals(0, result.minCostDifference());
        assertEquals(1, result.evaluatedAssignments());
        assertEquals(1, result.noHitLeftAssignments());
    }

    @Test void duplicatesAndBlankRankingIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FirstHitAudit.audit(List.of("a", "a"), List.of(), Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> FirstHitAudit.audit(List.of(" "), List.of(), Map.of(), false));
    }
}
