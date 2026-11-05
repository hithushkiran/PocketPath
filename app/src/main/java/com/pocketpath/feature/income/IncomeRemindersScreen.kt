package com.pocketpath.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.calculation.IncomeReminderAction
import com.pocketpath.domain.calculation.OccurrenceCalculator
import com.pocketpath.domain.model.AmountMode
import com.pocketpath.domain.model.Frequency
import com.pocketpath.domain.model.Occurrence
import com.pocketpath.domain.model.OccurrenceState
import com.pocketpath.domain.model.Recurrence
import com.pocketpath.domain.model.Schedule
import com.pocketpath.domain.model.ScheduleKind
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceBody
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.lkrText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val reminderZone = ZoneId.of("Asia/Colombo")

@Composable
fun IncomeRemindersScreen(
    state: IncomeRemindersUiState,
    income: ManualIncomeUiState,
    finance: FinanceUiState,
    onFinanceEvent: (FinanceEvent) -> Unit,
    onSave: (Schedule) -> Unit,
    onAct: (Occurrence, IncomeReminderAction) -> Unit,
    onRetry: () -> Unit,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(60_000)
        }
    }
    val today = now.atZone(reminderZone).toLocalDate()
    var adding by rememberSaveable { mutableStateOf(false) }
    FinanceBody(finance, onFinanceEvent) {
        Text("Recurring income", style = MaterialTheme.typography.headlineSmall)
        Text("Reminders appear here. Confirm a receipt to add it to your balance.")
        state.loadError?.let { FinanceMessage(it) }
        state.writeError?.let { FinanceMessage(it) }
        state.message?.let { FinanceMessage(it, error = false) }
        if (state.cached) Text("Showing cached reminders. You can edit a draft here and save when connected.")
        if (state.busy) Text("Saving…")
        if (state.retry) Button(onClick = onRetry) { Text("Retry save") }
        if (!state.ready && state.loadError == null && !state.cached) Text("Loading reminders…")

        Button(
            onClick = { adding = !adding },
            enabled = state.canEdit,
        ) { Text(if (adding) "Close setup" else "Add reminder") }
        if (adding) {
            ReminderScheduleForm(income, today, state.canEdit, state.canWrite) { schedule ->
                onSave(schedule)
                adding = false
            }
        }

        if (state.ready && state.schedules.isEmpty()) Text("No recurring income reminders yet.")
        val savedBySchedule = remember(state.occurrences) { state.occurrences.groupBy(Occurrence::scheduleId) }
        for (schedule in state.schedules.sortedBy(Schedule::title)) {
            val saved = savedBySchedule[schedule.id].orEmpty()
            val lastAmount = remember(saved) {
                saved.filter { it.state == OccurrenceState.CONFIRMED }
                    .maxWithOrNull(compareBy<Occurrence> { it.metadata.updatedAt }.thenBy { it.nominalDate })
                    ?.confirmedAmount
            }
            val visible = remember(schedule, saved, today, now) {
                visibleReminderOccurrences(schedule, saved, today, now)
            }
            Text(schedule.title, style = MaterialTheme.typography.titleLarge)
            Text("${schedule.recurrence.frequency.displayName()} · ${lkrText(schedule.expectedAmount.money.minor)} estimate")
            lastAmount?.let { Text("Last confirmed ${lkrText(it.money.minor)}") }
            for (occurrence in visible) {
                key(occurrence.id) {
                    ReminderOccurrenceCard(
                        occurrence, now, today, state.canEdit, state.canWrite,
                        suggestedAmount = if (occurrence.metadata.createdAt == null) lastAmount ?: occurrence.expectedAmount
                            else occurrence.expectedAmount,
                        onAct = { onAct(occurrence, it) },
                    )
                }
            }
        }
    }
}

private fun visibleReminderOccurrences(
    schedule: Schedule, saved: List<Occurrence>, today: LocalDate, now: Instant,
): List<Occurrence> {
    val savedIds = saved.mapTo(hashSetOf(), Occurrence::id)
    val generated = OccurrenceCalculator.incomeReminderOccurrences(schedule, today.minusMonths(2), today.plusYears(1))
        .filterNot { it.id in savedIds }
    val open = (saved.filter { it.state == OccurrenceState.OPEN } + generated)
        .sortedBy(Occurrence::effectiveDueDate)
    val active = open.filter { it.snoozedUntil == null || it.snoozedUntil <= now }
    val due = active.filter { it.effectiveDueDate <= today }.takeLast(10)
    val next = active.firstOrNull { it.effectiveDueDate > today }
    val snoozed = open.filter { it.snoozedUntil != null && it.snoozedUntil > now }.take(5)
    val closed = saved.filter { it.state != OccurrenceState.OPEN }.sortedByDescending { it.metadata.updatedAt }.take(2)
    return due + listOfNotNull(next) + snoozed + closed
}

@Composable
private fun ReminderScheduleForm(
    income: ManualIncomeUiState,
    today: LocalDate,
    editable: Boolean,
    canSave: Boolean,
    onSave: (Schedule) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var anchor by rememberSaveable { mutableStateOf(today.toString()) }
    var frequency by rememberSaveable { mutableStateOf(Frequency.MONTHLY.name) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val sources = income.selectableSources
    val accounts = income.selectableAccounts
    val sourceId = source ?: sources.firstOrNull()?.id
    val accountId = account ?: accounts.firstOrNull()?.id

    ReminderField("Reminder name", title, editable) { title = it }
    FinanceChoice("Income source", sourceId, sources.map { it.id to it.name }, editable) { source = it }
    FinanceChoice("Receiving account", accountId, accounts.map { it.id to it.name }, editable) { account = it }
    FinanceChoice("Repeat", frequency, Frequency.entries.map { it.name to it.displayName() }, editable) { frequency = it }
    ReminderField("First expected date (YYYY-MM-DD)", anchor, editable) { anchor = it }
    ReminderField("Estimated income (LKR)", amount, editable, decimal = true) { amount = it }
    if (sources.isEmpty() || accounts.isEmpty()) Text("Create an income source and an LKR account in Accounts first.")
    error?.let { FinanceMessage(it) }

    Button(onClick = {
        runCatching {
            val name = title.trim()
            require(name.isNotEmpty() && name.length <= 80) { "Enter a name of up to 80 characters." }
            val date = reminderDate(anchor)
            require(sources.any { it.id == sourceId } && accounts.any { it.id == accountId }) {
                "Select an available income source and account."
            }
            Schedule(
                UUID.randomUUID().toString(), ScheduleKind.INCOME_REMINDER, name,
                Recurrence(Frequency.valueOf(frequency), date), reminderZone,
                reminderAmount(amount), AmountMode.ESTIMATE, date,
                incomeSourceId = sourceId, preferredAccountId = accountId,
            )
        }.onSuccess(onSave).onFailure { error = it.message ?: "Check the reminder details." }
    }, enabled = canSave && sources.isNotEmpty() && accounts.isNotEmpty()) {
        Text("Save reminder")
    }
}

@Composable
private fun ReminderOccurrenceCard(
    occurrence: Occurrence,
    now: Instant,
    today: LocalDate,
    editable: Boolean,
    canSave: Boolean,
    suggestedAmount: EntryAmount,
    onAct: (IncomeReminderAction) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val snoozed = occurrence.snoozedUntil?.takeIf { it > now }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val status = when {
                occurrence.state == OccurrenceState.CONFIRMED -> "Confirmed"
                occurrence.state == OccurrenceState.SKIPPED -> "Skipped"
                snoozed != null -> "Snoozed until ${snoozed.atZone(reminderZone).toLocalDateTime().withSecond(0).withNano(0)}"
                occurrence.effectiveDueDate <= today -> "Due"
                else -> "Upcoming"
            }
            Text("$status · ${occurrence.effectiveDueDate}")
            Text(lkrText((occurrence.confirmedAmount ?: suggestedAmount).money.minor))
            if (occurrence.state == OccurrenceState.SKIPPED) {
                TextButton(onClick = { onAct(IncomeReminderAction.Reopen) }, enabled = canSave) {
                    Text("Reopen")
                }
            }
            if (occurrence.state == OccurrenceState.OPEN) {
                Row {
                    TextButton(onClick = { editing = "confirm" }, enabled = editable) { Text("Confirm") }
                    TextButton(onClick = { editing = "adjust" }, enabled = editable) { Text("Adjust") }
                }
                Row {
                    TextButton(
                        onClick = { onAct(IncomeReminderAction.Snooze(Instant.now().plusSeconds(86_400))) },
                        enabled = canSave,
                    ) { Text("Snooze 1 day") }
                    TextButton(onClick = { editing = "skip" }, enabled = canSave) { Text("Skip") }
                }
            }
        }
    }
    if (editing == "skip") {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Skip this occurrence?") },
            text = { Text("Future reminders stay scheduled.") },
            confirmButton = {
                TextButton(onClick = {
                    onAct(IncomeReminderAction.Skip)
                    editing = null
                }, enabled = canSave) {
                    Text("Skip")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    } else if (editing != null) {
        val confirm = editing == "confirm"
        ReminderAmountDialog(
            occurrence, suggestedAmount, today, confirm, editable, canSave, onDismiss = { editing = null },
            onSubmit = { action ->
                onAct(action)
                editing = null
            },
        )
    }
}

@Composable
private fun ReminderAmountDialog(
    occurrence: Occurrence,
    suggestedAmount: EntryAmount,
    today: LocalDate,
    confirm: Boolean,
    editable: Boolean,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (IncomeReminderAction) -> Unit,
) {
    var amount by rememberSaveable(occurrence.id, confirm) {
        mutableStateOf(suggestedAmount.money.major().toPlainString())
    }
    var date by rememberSaveable(occurrence.id, confirm) {
        mutableStateOf((if (confirm) today else occurrence.effectiveDueDate).toString())
    }
    var error by rememberSaveable(occurrence.id, confirm) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirm) "Confirm received income" else "Adjust this reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderField(if (confirm) "Received amount (LKR)" else "Expected amount (LKR)", amount, editable, true) {
                    amount = it
                }
                ReminderField(if (confirm) "Received on (YYYY-MM-DD)" else "Due on (YYYY-MM-DD)", date, editable) {
                    date = it
                }
                error?.let { FinanceMessage(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val parsedAmount = reminderAmount(amount)
                    val parsedDate = reminderDate(date)
                    if (confirm) {
                        require(parsedDate <= today) { "Use today or an earlier receipt date." }
                        IncomeReminderAction.Confirm(parsedAmount, parsedDate, Instant.now())
                    } else IncomeReminderAction.Adjust(parsedAmount, parsedDate)
                }.onSuccess(onSubmit).onFailure { error = it.message ?: "Check the amount and date." }
            }, enabled = canSave) { Text(if (confirm) "Record receipt" else "Save adjustment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReminderField(
    label: String, value: String, enabled: Boolean, decimal: Boolean = false, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = true, keyboardOptions = if (decimal) KeyboardOptions(keyboardType = KeyboardType.Decimal)
        else KeyboardOptions.Default,
    )
}

private fun reminderDate(text: String): LocalDate = LocalDate.parse(text.trim()).also {
    require(it.year in 1..9999) { "Use a date in YYYY-MM-DD format." }
}

private fun reminderAmount(text: String) = EntryAmount(Money.parse(text.trim(), FiatCurrency.LKR))

private fun Frequency.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
