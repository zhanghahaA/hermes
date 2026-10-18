package com.hermes.mobile.ui.jobs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.data.model.JobSchedule
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.friendlyTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 编辑界面里的五种“什么时候跑”。 */
private enum class Frequency(val label: String) { Daily("每天"), Weekly("每周"), Interval("间隔"), Once("一次"), Custom("自定义") }

private val timePresets = listOf(7 to 0, 8 to 0, 9 to 30, 12 to 0, 18 to 0, 22 to 0)
private val intervalPresets = listOf(15, 30, 60, 120, 360, 720)

/** 结果发到哪：Hermes 的 deliver 值 → 显示名。其它平台要先在 Hermes 上配好。 */
private val deliveryTargets = listOf("local" to "在 App 里查看", "telegram" to "Telegram", "discord" to "Discord", "slack" to "Slack")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobEditorScreen(repository: HermesRepository, jobId: String?, onClose: () -> Unit, onOpenChat: (String) -> Unit = {}) {
    val jobs by repository.jobs.collectAsState()
    val existing = remember(jobId) { jobs.firstOrNull { it.id == jobId } }
    val initial = existing?.schedule ?: JobSchedule.Daily(8, 0)

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(existing?.prompt.orEmpty()) }
    var frequency by rememberSaveable {
        mutableIntStateOf(
            when (initial) {
                is JobSchedule.Daily -> 0
                is JobSchedule.Weekly -> 1
                is JobSchedule.Interval -> 2
                is JobSchedule.Once -> 3
                is JobSchedule.Custom -> 4
            },
        )
    }
    var hour by rememberSaveable { mutableIntStateOf((initial as? JobSchedule.Daily)?.hour ?: (initial as? JobSchedule.Weekly)?.hour ?: (initial as? JobSchedule.Once)?.at?.hour ?: 8) }
    var minute by rememberSaveable { mutableIntStateOf((initial as? JobSchedule.Daily)?.minute ?: (initial as? JobSchedule.Weekly)?.minute ?: (initial as? JobSchedule.Once)?.at?.minute ?: 0) }
    var days by rememberSaveable { mutableStateOf((initial as? JobSchedule.Weekly)?.days ?: setOf(1, 2, 3, 4, 5)) }
    var intervalMinutes by rememberSaveable { mutableIntStateOf((initial as? JobSchedule.Interval)?.minutes ?: 60) }
    var onceDate by rememberSaveable { mutableStateOf(((initial as? JobSchedule.Once)?.at?.toLocalDate() ?: LocalDate.now().plusDays(1)).toString()) }
    var custom by rememberSaveable { mutableStateOf((initial as? JobSchedule.Custom)?.text ?: existing?.cron ?: "0 8 * * 1-5") }
    var deliver by rememberSaveable { mutableStateOf(existing?.deliver ?: "local") }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showIntervalInput by remember { mutableStateOf(false) }

    val schedule: JobSchedule = when (Frequency.entries[frequency]) {
        Frequency.Daily -> JobSchedule.Daily(hour, minute)
        Frequency.Weekly -> JobSchedule.Weekly(days, hour, minute)
        Frequency.Interval -> JobSchedule.Interval(intervalMinutes)
        Frequency.Once -> JobSchedule.Once(LocalDate.parse(onceDate).atTime(hour, minute))
        Frequency.Custom -> JobSchedule.Custom(custom)
    }
    val canSave = prompt.isNotBlank() && (schedule !is JobSchedule.Custom || custom.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "关闭") } },
                title = { Text(if (existing == null) "新建任务" else "编辑任务") },
                actions = {
                    Button(
                        onClick = {
                            repository.saveJob(
                                CronJob(
                                    id = existing?.id ?: "",
                                    name = name.ifBlank { prompt.lineSequence().first().take(20) },
                                    prompt = prompt.trim(),
                                    scheduleLabel = schedule.label(),
                                    cron = schedule.toHermes(),
                                    nextRunLabel = existing?.nextRunLabel ?: "—",
                                    lastResult = existing?.lastResult ?: JobRunResult.Never,
                                    lastRunLabel = existing?.lastRunLabel,
                                    enabled = existing?.enabled ?: true,
                                    deliverTo = deliveryTargets.firstOrNull { it.first == deliver }?.second ?: deliver,
                                    deliver = deliver,
                                    schedule = schedule,
                                ),
                            )
                            onClose()
                        },
                        enabled = canSave,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("任务名称") },
                placeholder = { Text("例如：早间简报") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("让 Hermes 做什么") },
                placeholder = { Text("例如：整理今天的日程和天气，用三段话发给我") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Field("频率") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Frequency.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = frequency == index,
                            onClick = { frequency = index },
                            shape = SegmentedButtonDefaults.itemShape(index, Frequency.entries.size),
                        ) { Text(item.label, maxLines = 1) }
                    }
                }
            }

            AnimatedVisibility(Frequency.entries[frequency] == Frequency.Weekly) {
                Field("星期") {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        JobSchedule.WeekdayNames.forEachIndexed { index, label ->
                            val day = index + 1
                            val selected = day in days
                            Surface(
                                onClick = { days = if (selected && days.size > 1) days - day else days + day },
                                shape = CircleShape,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text(label, textAlign = TextAlign.Center) }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(Frequency.entries[frequency] == Frequency.Once) {
                Field("日期") {
                    FilterChip(selected = true, onClick = { showDatePicker = true }, label = { Text(LocalDate.parse(onceDate).let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }) })
                }
            }

            AnimatedVisibility(Frequency.entries[frequency] in setOf(Frequency.Daily, Frequency.Weekly, Frequency.Once)) {
                Field("时间") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        timePresets.forEach { (h, m) ->
                            FilterChip(
                                selected = hour == h && minute == m,
                                onClick = { hour = h; minute = m },
                                label = { Text("%02d:%02d".format(h, m)) },
                            )
                        }
                        val other = timePresets.none { it == hour to minute }
                        FilterChip(
                            selected = other,
                            onClick = { showTimePicker = true },
                            label = { Text(if (other) "%02d:%02d".format(hour, minute) else "其他时间…") },
                        )
                    }
                }
            }

            AnimatedVisibility(Frequency.entries[frequency] == Frequency.Interval) {
                Field("间隔") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervalPresets.forEach { m ->
                            FilterChip(
                                selected = intervalMinutes == m,
                                onClick = { intervalMinutes = m },
                                label = { Text(JobSchedule.Interval(m).label().removePrefix("每 ")) },
                            )
                        }
                        val other = intervalMinutes !in intervalPresets
                        FilterChip(
                            selected = other,
                            onClick = { showIntervalInput = true },
                            label = { Text(if (other) JobSchedule.Interval(intervalMinutes).label().removePrefix("每 ") else "自定义…") },
                        )
                    }
                }
            }

            AnimatedVisibility(Frequency.entries[frequency] == Frequency.Custom) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Hermes 的时间写法") },
                    supportingText = {
                        Text("cron 表达式（分 时 日 月 周，例如 0 8 * * 1-5），或 every 30m、every monday 9am")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            NextRunsCard(schedule)

            Field("结果发送到") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    deliveryTargets.forEach { (value, label) ->
                        FilterChip(selected = deliver == value, onClick = { deliver = value }, label = { Text(label) })
                    }
                    if (deliveryTargets.none { it.first == deliver }) {
                        FilterChip(selected = true, onClick = {}, label = { Text(deliver) })
                    }
                }
                Text(
                    if (deliver == "local") "每次运行的结果是一个新对话，出现在聊天列表里（来源“定时任务”）"
                    else "需要 Hermes 那边已经配好这个平台；聊天列表里同样能看到每次运行的对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (existing != null) {
                existing.lastError?.takeIf { existing.lastResult == JobRunResult.Failed }?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            "上次运行失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        )
                    }
                }
                RecentRuns(repository, existing, onOpenChat)
                OutlinedButton(
                    onClick = {
                        repository.deleteJob(existing.id)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Text("删除任务", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour
                    minute = state.minute
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }

    if (showDatePicker) {
        val utcMillis = LocalDate.parse(onceDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onceDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showIntervalInput) {
        var text by remember { mutableStateOf(intervalMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showIntervalInput = false },
            title = { Text("每隔多少分钟") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    suffix = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        text.toIntOrNull()?.takeIf { it > 0 }?.let { intervalMinutes = it }
                        showIntervalInput = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showIntervalInput = false }) { Text("取消") } },
        )
    }
}

/** 按手机时区预估接下来几次运行（实际以 Hermes 的时区为准，保存后任务卡片上显示 Hermes 算出的时间）。 */
@Composable
private fun NextRunsCard(schedule: JobSchedule) {
    val now = LocalDateTime.now()
    val zone = ZoneId.systemDefault()
    val next = schedule.nextRuns(now)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.padding(start = 12.dp)) {
                Text(schedule.label().ifBlank { "还没填时间" }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    when {
                        schedule is JobSchedule.Custom -> "保存后由 Hermes 计算下次运行时间"
                        next.isEmpty() -> "这个时间已经过去了"
                        else -> "接下来：" + next.joinToString(" · ") { friendlyTime(it.atZone(zone).toInstant()) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 这个任务最近几次运行出来的对话，点开直接看结果。 */
@Composable
private fun RecentRuns(repository: HermesRepository, job: CronJob, onOpenChat: (String) -> Unit) {
    val runs by remember(job.id) { repository.jobRuns(job) }.collectAsState(initial = emptyList())
    Field("最近运行") {
        if (runs.isEmpty()) {
            Text("还没有运行记录。点任务卡片上的“立即运行”可以马上试一次。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        runs.take(10).forEach { session -> RunRow(session) { onOpenChat(session.id) } }
    }
}

@Composable
private fun RunRow(session: Session, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(friendlyTime(Instant.ofEpochMilli(session.updatedAt)), style = MaterialTheme.typography.bodyMedium)
            if (session.preview.isNotBlank()) {
                Text(session.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
