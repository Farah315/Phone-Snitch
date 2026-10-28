package com.farah.phonesnitch.domain

import com.farah.phonesnitch.domain.model.DetoxStage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Social media detox as a small state machine driven by "days since activation".
 *
 *  days 0-6   -> REDUCED           social apps are pushed to the bottom of the home page
 *  days 7-13  -> HIDDEN_FROM_HOME  social apps never appear on the home page
 *  day 14+    -> WEEKLY_BLOCK      hidden from home, and fully unreachable on BLOCKED_DAYS
 *
 * The thresholds and blocked days are my choice, the task description leaves them open.
 * Change the constants below to tune it.
 */
object DetoxPolicy {
    const val REDUCED_DAYS = 7L
    const val HIDDEN_DAYS = 14L

    /** One or two days per week. Rest days are where social media usage is usually highest. */
    val BLOCKED_DAYS: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)

    fun stage(enabled: Boolean, startDate: LocalDate?, today: LocalDate): DetoxStage {
        if (!enabled || startDate == null) return DetoxStage.OFF
        val daysSinceStart = ChronoUnit.DAYS.between(startDate, today)
        return when {
            daysSinceStart < REDUCED_DAYS -> DetoxStage.REDUCED
            daysSinceStart < HIDDEN_DAYS -> DetoxStage.HIDDEN_FROM_HOME
            else -> DetoxStage.WEEKLY_BLOCK
        }
    }

    fun isBlockedToday(stage: DetoxStage, today: LocalDate): Boolean =
        stage == DetoxStage.WEEKLY_BLOCK && today.dayOfWeek in BLOCKED_DAYS

    fun describe(stage: DetoxStage): String = when (stage) {
        DetoxStage.OFF -> "Detox is off."
        DetoxStage.REDUCED ->
            "Stage 1: social apps are pushed to the bottom of the home page, even if you use them a lot."
        DetoxStage.HIDDEN_FROM_HOME ->
            "Stage 2: social apps are gone from the home page. They are still in All apps."
        DetoxStage.WEEKLY_BLOCK ->
            "Stage 3: hidden from home, and fully blocked on " +
                BLOCKED_DAYS.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) } + "."
    }
}
