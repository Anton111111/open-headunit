package com.andrerinas.openheadunit.connection

/**
 * What the home screen's status pill shows, and how it says it.
 *
 * The pill used to read "Android Auto is starting…" with a spinner in two cases where nothing was
 * starting (#1147): during a session, because the tracker keeps the last step until the session
 * ends, and while the stack only listens for the phone, which can last as long as the unit is on.
 * Pure, so both cases are unit tests rather than a head unit.
 */
object StatusPillPolicy {

    /** How the pill's first line reads. */
    enum class Title {
        /** "Android Auto is starting…" with the spinner: a connection is on its way. */
        STARTING,
        /** The text the auto-connect attempt asked for, such as the phone it is waking. */
        CUSTOM,
        /** The step itself, without the spinner: the stack is only waiting for the phone. */
        STEP,
    }

    /**
     * Steps where the stack listens and nothing is on its way. Attaching any USB device reports
     * one too, a flash drive included, so it is no promise of a phone either.
     */
    private val waitingStages = setOf(
        ConnectionStage.ARMED,
        ConnectionStage.WAITING_FOR_PHONE,
        ConnectionStage.SEARCHING,
        ConnectionStage.USB_ATTACHED,
    )

    fun isWaiting(stage: ConnectionStage): Boolean = stage in waitingStages

    /**
     * The step to show, or null to hide the pill. A session that is up has nothing left to bring
     * up, whatever step the tracker still holds.
     */
    fun shownStage(
        stage: ConnectionStage?,
        overlayOwnsScreen: Boolean,
        phoneLeftQuiet: Boolean,
        sessionLive: Boolean,
    ): ConnectionStage? = if (stage == null || overlayOwnsScreen || phoneLeftQuiet || sessionLive) null else stage

    /**
     * An auto-connect attempt in flight is a connection on its way even while the phone is being
     * waited for, so only a stack that waits on its own reads as the step.
     */
    fun title(stage: ConnectionStage, attemptInProgress: Boolean, hasCustomText: Boolean): Title = when {
        hasCustomText -> Title.CUSTOM
        !attemptInProgress && isWaiting(stage) -> Title.STEP
        else -> Title.STARTING
    }
}
