package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWidgetsPolicyTest {

    @Test
    fun `a stored list keeps its order`() {
        assertEquals(listOf(7, 9), HomeWidgetsPolicy.decode("7,9"))
    }

    @Test
    fun `nonsense and blanks are ignored rather than breaking the row`() {
        assertEquals(listOf(7), HomeWidgetsPolicy.decode(" 7 ,, oops ,"))
    }

    @Test
    fun `a stored list longer than the row allows is cut`() {
        assertEquals(listOf(1, 2), HomeWidgetsPolicy.decode("1,2,3"))
    }

    @Test
    fun `nothing stored decodes to no widgets`() {
        assertEquals(emptyList<Int>(), HomeWidgetsPolicy.decode(""))
    }

    @Test
    fun `the second widget still fits and the third is refused`() {
        assertEquals(listOf(4, 5), HomeWidgetsPolicy.add(emptyList(), 4)?.let { HomeWidgetsPolicy.add(it, 5) })
        assertNull(HomeWidgetsPolicy.add(listOf(4, 5), 6))
    }

    @Test
    fun `adding one that is already there changes nothing`() {
        assertEquals(listOf(4), HomeWidgetsPolicy.add(listOf(4), 4))
    }

    @Test
    fun `removing one leaves the other in place`() {
        assertEquals(listOf(5), HomeWidgetsPolicy.remove(listOf(4, 5), 4))
    }

    @Test
    fun `encoding and decoding round trips`() {
        val ids = listOf(12, 34)
        assertEquals(ids, HomeWidgetsPolicy.decode(HomeWidgetsPolicy.encode(ids)))
    }

    @Test
    fun `a box is told to the widget in dp`() {
        // The MT50 case: density 1.5, a 506x110 px frame.
        assertEquals(337, HomeWidgetsPolicy.toDp(506, 1.5f))
        assertEquals(73, HomeWidgetsPolicy.toDp(110, 1.5f))
        assertEquals(240, HomeWidgetsPolicy.toDp(240, 1f))
    }

    @Test
    fun `a box that has not been laid out yet is never negative`() {
        assertEquals(0, HomeWidgetsPolicy.toDp(-12, 1.5f))
    }

    @Test
    fun `a density the system did not report leaves the size as it is`() {
        assertEquals(300, HomeWidgetsPolicy.toDp(300, 0f))
    }
}
