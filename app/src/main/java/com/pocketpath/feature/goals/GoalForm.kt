package com.pocketpath.feature.goals

import com.pocketpath.domain.calculation.SavingsPace
import com.pocketpath.domain.calculation.calculateSavingsPace
import com.pocketpath.domain.model.Goal
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

enum class GoalField { NAME, TARGET, START_DATE, DEADLINE, FIRST_CONTRIBUTION_DATE }

data class GoalForm(
    val name: String = "",
    val target: String = "",
    val startDate: String = "",
    val deadline: String = "",
    val firstContributionDate: String = "",
) {
    fun edited(field: GoalField, value: String): GoalForm = when (field) {
        GoalField.NAME -> copy(name = value)
        GoalField.TARGET -> copy(target = value)
        GoalField.START_DATE -> copy(startDate = value)
        GoalField.DEADLINE -> copy(deadline = value)
        GoalField.FIRST_CONTRIBUTION_DATE -> copy(firstContributionDate = value)
    }
}

fun newGoalForm(today: LocalDate): GoalForm = GoalForm(
    startDate = today.toString(),
    deadline = today.plusMonths(12).minusDays(1).toString(),
    firstContributionDate = today.toString(),
)

/** A hypothetical draft, never a saved goal or proof of an allocation. */
data class GoalDraftPreview(
    val name: String,
    val targetLkrMinor: Long,
    val startDate: LocalDate,
    val deadline: LocalDate,
    val pace: SavingsPace,
)

sealed interface GoalFormResult {
    data class Invalid(val errors: Map<GoalField, String>) : GoalFormResult
    data class Valid(val preview: GoalDraftPreview) : GoalFormResult
}

/** Preserve the chosen day across short months: Jan 31, Feb 28, Mar 31. */
fun monthlyGoalDates(first: LocalDate, deadline: LocalDate): List<LocalDate> {
    require(first <= deadline)
    val dates = mutableListOf<LocalDate>()
    var month = YearMonth.from(first)
    val lastMonth = YearMonth.from(deadline)
    while (month <= lastMonth) {
        val date = month.atDay(minOf(first.dayOfMonth, month.lengthOfMonth()))
        if (date <= deadline) dates += date
        month = month.plusMonths(1)
    }
    return dates.toList()
}

fun validateGoalForm(form: GoalForm, today: LocalDate): GoalFormResult {
    val errors = mutableMapOf<GoalField, String>()
    val name = form.name.trim()
    if (name.isEmpty()) errors[GoalField.NAME] = "Enter a goal name."

    val amountError = "Enter a positive LKR amount up to 1,000,000,000, " +
        "with at most two decimal places and no commas."
    val target = try {
        EntryAmount(Money.parse(form.target.trim(), FiatCurrency.LKR))
    } catch (_: IllegalArgumentException) {
        errors[GoalField.TARGET] = amountError
        null
    } catch (_: ArithmeticException) {
        errors[GoalField.TARGET] = amountError
        null
    }

    fun date(text: String, field: GoalField): LocalDate? {
        val value = text.trim()
        if (value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
            try {
                return LocalDate.parse(value)
            } catch (_: DateTimeParseException) {
                // Show the same field error for malformed and impossible dates.
            }
        }
        errors[field] = "Enter a valid date as yyyy-MM-dd."
        return null
    }

    val start = date(form.startDate, GoalField.START_DATE)
    val deadline = date(form.deadline, GoalField.DEADLINE)
    val first = date(form.firstContributionDate, GoalField.FIRST_CONTRIBUTION_DATE)
    if (start != null && deadline != null && deadline < start) {
        errors[GoalField.DEADLINE] = "Deadline must be on or after the start date."
    }
    if (first != null && start != null && deadline != null &&
        (first < today || first < start || first > deadline)
    ) {
        errors[GoalField.FIRST_CONTRIBUTION_DATE] =
            "Choose a remaining date from the goal start through its deadline."
    }
    if (errors.isNotEmpty()) return GoalFormResult.Invalid(errors.toMap())

    val goal = Goal(
        id = "preview-only",
        name = name,
        target = requireNotNull(target),
        startDate = requireNotNull(start),
        deadline = requireNotNull(deadline),
    )
    val pace = calculateSavingsPace(
        goal = goal,
        reservedLkrMinor = 0L, // Explicit assumption for this unsaved estimate only.
        asOfDate = today,
        remainingContributionDates = monthlyGoalDates(requireNotNull(first), deadline),
    )
    return GoalFormResult.Valid(
        GoalDraftPreview(name, goal.target.money.minor, start, deadline, pace),
    )
}
