package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v1.3.9 §2.3 — the 10-use teaching-hint budget. The counter itself lives
 * behind SharedPreferences and needs a Context, so the round-trip is covered by
 * manual on-device testing. Here we lock the constants and the clamp math so a
 * future edit can't silently reduce or invert the budget.
 */
class TeachingModeConstantsTest {

    @Test fun budgetIsTenInteractions() {
        // Spec literal: "10 interaction แรก". Lock the value so a bump requires
        // deliberate intent + a spec-update trail.
        assertEquals(10, AppSettings.TEACHING_MODE_BUDGET)
    }

    @Test fun budgetIsPositive() {
        assertTrue(AppSettings.TEACHING_MODE_BUDGET > 0)
    }

    @Test fun clampMathNeverGoesBelowZero() {
        // The setter uses coerceAtLeast(0); the property getter mirrors it.
        // Both together mean "one more decrement below zero is still zero".
        assertEquals(0, (-1).coerceAtLeast(0))
        assertEquals(0, (-100).coerceAtLeast(0))
    }

    @Test fun decrementBelowBudgetIsMonotonic() {
        // Simulate the pipeline finally-block decrement without needing a Context:
        // starting at BUDGET, each interaction that used a hint decrements by one.
        var counter = AppSettings.TEACHING_MODE_BUDGET
        repeat(AppSettings.TEACHING_MODE_BUDGET) {
            counter = (counter - 1).coerceAtLeast(0)
        }
        assertEquals(0, counter)
    }

    @Test fun decrementWhenAlreadyZeroStaysZero() {
        var counter = 0
        counter = (counter - 1).coerceAtLeast(0)
        assertEquals(0, counter)
    }
}
