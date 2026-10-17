package com.hermes.mobile.ui.jobs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.JobRunResult

private enum class Frequency(val label: String) { Hourly("每小时"), Daily("每天"), Weekly("每周"), Custom("自定义") }

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private val timePresets = listOf("07:00", "08:00", "09:30", "12:00", "18:00", "22:00")
private val deliveryTargets = listOf("App 通知", "Telegram", "仅记录")

@Composable
fun JobEditorScreen(repository: HermesRepository, jobId: String?, onClose: () -> Unit) {
    val jobs by repository.jobs.collectAsState()
    val existing = remember(jobId) { jobs.firstOrNull { it.id == jobId } }

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(existing?.prompt.orEmpty()) }
    var frequency by rememberSaveable { mutableIntStateOf(if (existing?.cron?.endsWith("* * 1") == true) 2 else 1) }
    var time by rememberSaveable { mutableStateOf("08:00") }
    var days by rememberSaveable { mutableStateOf(setOf(0)) }
    var cron by rememberSaveable { mutableStateOf(existing?.cron ?: "0 8 * * *") }
    var deliverTo by rememberSaveable { mutableStateOf(existing?.deliverTo ?: deliveryTargets.first()) }

    val freq = Frequency.entries[frequency]
    val scheduleLabel = when (freq) {
        Frequency.Hourly -> "每小时整点"
        Frequency.Daily -> "每天 $time"
        Frequency.Weekly -> "每周${days.sorted().joinToString("、") { weekdays[it] }} $time"
        Frequency.Custom -> "cron: $cron"
    }

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
                                    id = existing?.id ?: "j-${name.hashCode()}",
                                    name = name.ifBlank { "未命名任务" },
                                    prompt = prompt,
                                    scheduleLabel = scheduleLabel,
                                    cron = cron,
                                    nextRunLabel = existing?.nextRunLabel ?: "待计算",
                                    lastResult = existing?.lastResult ?: JobRunResult.Never,
                                    lastRunLabel = existing?.lastRunLabel,
                                    enabled = existing?.enabled ?: true,
                                    deliverTo = deliverTo,
                                ),
                            )
                            onClose()
                        },
                        enabled = prompt.isNotBlank(),
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
                        ) { Text(item.label) }
                    }
                }
            }

            AnimatedVisibility(freq == Frequency.Weekly) {
                Field("星期") {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        weekdays.forEachIndexed { index, label ->
                            val selected = index in days
                            Surface(
                                onClick = { days = if (selected && days.size > 1) days - index else days + index },
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

            AnimatedVisibility(freq == Frequency.Daily || freq == Frequency.Weekly) {
                Field("时间") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        timePresets.forEach { preset ->
                            FilterChip(selected = time == preset, onClick = { time = preset }, label = { Text(preset) })
                        }
                        FilterChip(selected = time !in timePresets, onClick = { /* 占位：弹出 TimePicker */ }, label = { Text("其他时间…") })
                    }
                }
            }

            AnimatedVisibility(freq == Frequency.Custom) {
                OutlinedTextField(
                    value = cron,
                    onValueChange = { cron = it },
                    label = { Text("cron 表达式") },
                    supportingText = { Text("分 时 日 月 周，例如 0 8 * * 1-5 表示工作日 8 点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(scheduleLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            "接下来：明天 08:00 · 后天 08:00 · 周日 08:00（占位）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Field("结果发送到") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    deliveryTargets.forEach { target ->
                        FilterChip(selected = deliverTo == target, onClick = { deliverTo = target }, label = { Text(target) })
                    }
                }
            }

            if (existing != null) {
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
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
