package com.andrerinas.openheadunit.main

import android.view.KeyEvent
import com.andrerinas.openheadunit.main.HomePagesHintKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePagesHintKeyPolicyTest {

    private fun key(code: Int, up: Boolean, focused: Boolean) = HomePagesHintKeyPolicy.onKey(code, up, focused)

    @Test
    fun `any arrow or tab brings focus to Got it and then keeps it there`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB,
        ).forEach { code ->
            assertEquals(Action.FOCUS_BUTTON, key(code, up = false, focused = false))
            assertEquals(Action.CONSUME, key(code, up = false, focused = true))
            assertEquals(Action.CONSUME, key(code, up = true, focused = true))
        }
    }

    @Test
    fun `centre and enter press Got it when it has focus, on release`() {
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach { code ->
            assertEquals(Action.CONSUME, key(code, up = false, focused = true))
            assertEquals(Action.CONFIRM, key(code, up = true, focused = true))
        }
    }

    @Test
    fun `centre without focus only brings focus to Got it, so a press is never blind`() {
        assertEquals(Action.FOCUS_BUTTON, key(KeyEvent.KEYCODE_DPAD_CENTER, up = false, focused = false))
        assertEquals(Action.CONSUME, key(KeyEvent.KEYCODE_DPAD_CENTER, up = true, focused = false))
    }

    @Test
    fun `back closes the hint on release`() {
        assertEquals(Action.CONSUME, key(KeyEvent.KEYCODE_BACK, up = false, focused = false))
        assertEquals(Action.BACK, key(KeyEvent.KEYCODE_BACK, up = true, focused = true))
    }

    @Test
    fun `other keys go through`() {
        assertEquals(Action.PASS, key(KeyEvent.KEYCODE_VOLUME_UP, up = false, focused = true))
        assertEquals(Action.PASS, key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, up = true, focused = false))
    }
}
