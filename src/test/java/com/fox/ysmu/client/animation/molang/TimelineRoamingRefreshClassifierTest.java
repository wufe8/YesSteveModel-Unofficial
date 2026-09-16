package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The timeline scheduler's per-frame roaming refresh may only re-run instructions
 * that are provably idempotent. This classifier is the gate: the old runtime
 * replayed <em>every</em> instruction containing {@code roaming.} each frame,
 * which re-triggered random values, self-increments and particle spawns.
 */
class TimelineRoamingRefreshClassifierTest {

    @Test
    void aPlainRoamingCopyIsIdempotent() {
        assertTrue(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.bq_eye = v.roaming.bq_eye"));
    }

    @Test
    void multipleAssignmentsAreIdempotentWhenEveryStatementQualifies() {
        assertTrue(
            MolangInstructionExecutor
                .isIdempotentRoamingAssignment("v.a = v.roaming.a;\nv.b = v.roaming.b * 2"));
    }

    @Test
    void aSelfReferenceIsNotIdempotent() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = v.roaming.a + v.a"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = v.a - v.roaming.step"));
    }

    @Test
    void functionCallsAndSideEffectMarkersAreRejected() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = math.random(0, 1) + v.roaming.a"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = math.min(v.roaming.a, 1)"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.p = v.roaming.particle"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.s = v.roaming.sound_id"));
    }

    @Test
    void parenthesisedConditionalAssignmentsStayEligibleForTheRefresh() {
        // A conditional copy is the common 轮盘 visibility pattern and is idempotent;
        // parentheses alone must not disqualify it.
        assertTrue(
            MolangInstructionExecutor.isIdempotentRoamingAssignment("v.x = (v.roaming.a > 0) ? 1 : 0"));
        assertTrue(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.x = (v.roaming.a + 1) * 2"));
    }

    @Test
    void nonAssignmentStatementsDisqualifyTheWholeInstruction() {
        assertFalse(MolangInstructionExecutor
            .isIdempotentRoamingAssignment("v.a = v.roaming.a;\nysm.play_sound('x')"));
    }

    @Test
    void nonVariableTargetsAreRejected() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("q.x = v.roaming.a"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("temp.x = v.roaming.a"));
    }

    @Test
    void instructionsWithoutRoamingAreLeftToTheScheduler() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = v.b"));
    }

    @Test
    void uppercaseTargetsAreAcceptedBecauseExecutionLowercasesThem() {
        assertTrue(MolangInstructionExecutor.isIdempotentRoamingAssignment("V.BQ_EYE = V.ROAMING.BQ_EYE"));
    }

    @Test
    void aTrailingCommentDoesNotDisqualifyTheAssignment() {
        assertTrue(MolangInstructionExecutor.isIdempotentRoamingAssignment("v.a = v.roaming.a; // keep in sync"));
    }

    @Test
    void indirectCyclesNestedAssignmentsAndRoamingWritesAreNotReplayable() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(
            "v.a=v.b+v.roaming.step;v.b=v.a+1;"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(
            "v.a=v.roaming.step+(v.b=1);"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(
            "v.roaming.a=v.roaming.b;v.roaming.b=v.roaming.a+1;"));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(
            "v.a=v.roaming.step+fn.side_effect;"));
    }

    @Test
    void blankOrNullIsRejected() {
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(null));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment(""));
        assertFalse(MolangInstructionExecutor.isIdempotentRoamingAssignment("   "));
    }
}
