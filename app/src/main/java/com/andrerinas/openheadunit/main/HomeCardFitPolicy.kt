package com.andrerinas.openheadunit.main

/**
 * Which lines of the main button step aside, in order, when the card is too short for all of
 * them. Otherwise it is the button at the bottom that gets cut.
 *
 * While a connection comes up the step ("Waking your phone") is what the card is there for, so the
 * kicker and the generic title go before it. Pure, so the order is a unit test.
 */
object HomeCardFitPolicy {

    enum class Line { DETAIL, SUBTITLE, KICKER, TITLE }

    fun stepAsideOrder(stepShown: Boolean): List<Line> =
        if (stepShown) {
            listOf(Line.DETAIL, Line.KICKER, Line.TITLE)
        } else {
            // The network line is only there during a step, so it never has to go here.
            listOf(Line.SUBTITLE, Line.KICKER)
        }
}
