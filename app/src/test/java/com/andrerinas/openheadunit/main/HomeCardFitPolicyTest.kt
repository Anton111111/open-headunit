package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeCardFitPolicy.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeCardFitPolicyTest {

    @Test
    fun `the step is never the line that steps aside`() {
        assertFalse(Line.SUBTITLE in HomeCardFitPolicy.stepAsideOrder(stepShown = true))
    }

    @Test
    fun `during a step the network line goes first, then the kicker, then the title`() {
        assertEquals(listOf(Line.DETAIL, Line.KICKER, Line.TITLE), HomeCardFitPolicy.stepAsideOrder(stepShown = true))
    }

    @Test
    fun `without a step the title stays and the subtitle goes before the kicker`() {
        assertEquals(listOf(Line.SUBTITLE, Line.KICKER), HomeCardFitPolicy.stepAsideOrder(stepShown = false))
    }
}
