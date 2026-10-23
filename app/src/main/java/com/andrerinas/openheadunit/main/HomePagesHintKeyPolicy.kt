package com.andrerinas.openheadunit.main

import android.view.KeyEvent

/**
 * What a key does while the pages hint is up. "Got it" is the one thing that can hold focus: the
 * arrows bring focus to it and keep it there, the centre and Enter keys press it, and Back closes
 * the hint the same way.
 *
 * Pure, so a rotary knob or a D-pad is a unit test rather than a drive.
 */
object HomePagesHintKeyPolicy {

    enum class Action {
        /** Not the hint's key: let it through. */
        PASS,
        /** Taken, nothing else to do (the down half of a key acted on when released). */
        CONSUME,
        /** Move focus to "Got it". */
        FOCUS_BUTTON,
        /** Close the hint as "Got it" does. */
        CONFIRM,
        /** Close the hint as Back does. */
        BACK,
    }

    private val moves = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_TAB,
    )

    private val presses = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
    )

    fun onKey(keyCode: Int, isUp: Boolean, buttonFocused: Boolean): Action = when {
        keyCode == KeyEvent.KEYCODE_BACK -> if (isUp) Action.BACK else Action.CONSUME
        keyCode in moves -> if (buttonFocused) Action.CONSUME else Action.FOCUS_BUTTON
        keyCode in presses -> when {
            !buttonFocused -> if (isUp) Action.CONSUME else Action.FOCUS_BUTTON
            isUp -> Action.CONFIRM
            else -> Action.CONSUME
        }
        else -> Action.PASS
    }
}
