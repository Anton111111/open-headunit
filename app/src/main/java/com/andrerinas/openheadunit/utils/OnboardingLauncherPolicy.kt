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
        /** The system granted the home role: home status confirmed. */
        CONFIRMED,
        /** The role is not yet held: launcher mode remains enabled and prompt button is offered. */
        UNCONFIRMED
    }

    /**
     * What to do with a home-role request at a point where its answer is due: when the wizard
     * comes back to the foreground, or when the user leaves the step or the wizard.
     */
    fun resolve(requestPending: Boolean, roleHeld: Boolean): Outcome = when {
        !requestPending -> Outcome.NONE
        roleHeld -> Outcome.CONFIRMED
        else -> Outcome.UNCONFIRMED
    }

    /**
     * Whether launcher mode is on when the wizard opens. Picking this app as home can end the
     * wizard before it hears back (a wizard from Settings shares the main screen's task, which the
     * Home intent clears; the first-run one has its own task but can still lose its process), so
     * a home role already held counts as launcher on.
     */
    fun launcherModeOnOpen(stored: Boolean, roleHeld: Boolean): Boolean = stored || roleHeld

    /**
     * Whether the launcher alias may be synced with the stored setting, when the wizard finishes
     * or the main screen starts. It must not drop the alias while the app holds the home role:
     * the setting can still be off because the wizard that would have stored it was cleared away,
     * and dropping the alias would hand Home back to the stock launcher, the opposite of what the
     * user picked. Turning launcher mode off in Settings drops the alias itself.
     */
    fun syncsAlias(stored: Boolean, roleHeld: Boolean): Boolean = stored || !roleHeld

    /**
     * Whether to tell the user, under the launcher switch, that reopen on reconnection does not
     * apply while the app is the home app. Only when they turned reopen on and could see it.
     */
    fun showsReopenNote(launcherSwitchOn: Boolean, reopenOn: Boolean, reopenOffered: Boolean): Boolean =
        launcherSwitchOn && reopenOn && reopenOffered
}
