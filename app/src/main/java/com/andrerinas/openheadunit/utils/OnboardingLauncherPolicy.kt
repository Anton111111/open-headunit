package com.andrerinas.openheadunit.utils

/**
 * The wizard's car launcher switch. Turning it on only asks the system for the home role, and the
 * answer may never come back (the request can fail silently, or the user can decline), so the
 * setting is written only once the role is actually held. Until then the request is pending.
 */
object OnboardingLauncherPolicy {

    enum class Outcome {
        /** No request in flight: leave everything as it is. */
        NONE,
        /** The system granted the home role: store launcher mode. */
        COMMIT,
        /** The role is not held: turn the switch back off and drop the launcher alias. */
        REVERT
    }

    /**
     * What to do with a home-role request at a point where its answer is due: when the wizard
     * comes back to the foreground, or when the user leaves the step or the wizard.
     */
    fun resolve(requestPending: Boolean, roleHeld: Boolean): Outcome = when {
        !requestPending -> Outcome.NONE
        roleHeld -> Outcome.COMMIT
        else -> Outcome.REVERT
    }

    /**
     * Whether to tell the user, under the launcher switch, that reopen on reconnection does not
     * apply while the app is the home app. Only when they turned reopen on and could see it.
     */
    fun showsReopenNote(launcherSwitchOn: Boolean, reopenOn: Boolean, reopenOffered: Boolean): Boolean =
        launcherSwitchOn && reopenOn && reopenOffered
}
