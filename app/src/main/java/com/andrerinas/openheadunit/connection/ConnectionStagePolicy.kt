package com.andrerinas.openheadunit.connection

/**
 * Whether a reported stage should replace the one the pill is showing.
 *
 * Ranks only move forward within one attempt. The WiFi Direct group/credentials/poke cycle fires
 * three or four times per group, so a same-rank re-delivery is accepted as a no-op while a
 * lower-rank one is dropped rather than dragging the display backwards. A genuine restart goes
 * through ConnectionStageTracker.beginAttempt, which is not subject to this rule.
 */
object ConnectionStagePolicy {

    fun shouldApply(current: ConnectionStage?, candidate: ConnectionStage): Boolean =
        current == null || candidate.rank >= current.rank

    /**
     * Whether a step that ended without result may drop the display back. Only honest from the
     * stage that asked: anything higher means the phone did answer while the step was running.
     */
    fun shouldRetreat(current: ConnectionStage?, from: ConnectionStage): Boolean = current == from

    /**
     * Where the display goes when the unit gives up on its own network (#1147). After a refused
     * group the pill used to keep "Creating the WiFi network" under "Android Auto is starting…"
     * for as long as the app stayed open. It falls back to waiting for the phone, and the
     * connection banner says what went wrong. A new try reports its step again. A later step
     * means the phone got further, so it stays.
     */
    fun afterNetworkAbandoned(current: ConnectionStage?): ConnectionStage? = when (current) {
        ConnectionStage.PREPARING_NETWORK, ConnectionStage.CREATING_NETWORK -> ConnectionStage.ARMED
        else -> current
    }
}
