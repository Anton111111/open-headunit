package com.andrerinas.openheadunit.aap

/**
 * What the projection screen does when it opens and there is no session at all.
 *
 * It used to treat that as a stale replay and wait, so a screen opened with nothing connected sat
 * black under "Android Auto is starting…" until the user backed out (#1147). That happens when
 * Android brings the task back after the app was killed, when a unit reopens the last app at
 * power-on, or from a notification left over from a session. Waiting only makes sense when the
 * caller is about to start one, and even then it is bounded by [ReconnectGracePolicy].
 *
 * Leaving loses nothing: a session that comes up later raises this screen again on its own.
 */
object ProjectionColdOpenPolicy {

    enum class Action {
        /** Something is bringing a session up; hold the loading screen under the reconnect grace. */
        WAIT,
        /** Nothing is on its way; go back to the home screen. */
        LEAVE,
    }

    /**
     * @param callerExpectsSession the launch said a session is about to start (Self Mode opens this
     *   screen first because Android 10+ blocks the later launch from the background).
     * @param settingsRestart the last session ended to apply settings and is being brought back.
     */
    fun onOpenedWithoutSession(callerExpectsSession: Boolean, settingsRestart: Boolean): Action =
        if (callerExpectsSession || settingsRestart) Action.WAIT else Action.LEAVE
}
