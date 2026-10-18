package com.hermes.mobile.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.ui.LocalShowMessage
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.NoInsets
import com.hermes.mobile.ui.glass.GlassTopBar
import com.hermes.mobile.ui.glass.TransparentBarColors
import com.hermes.mobile.ui.main.LocalBottomBarSpace
import com.hermes.mobile.ui.theme.LocalHermesColors
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun JobsScreen(repository: HermesRepository, onEditJob: (String?) -> Unit) {
    val jobs by repository.jobs.collectAsState()
    val available = AppFeature.Jobs in repository.features.collectAsState().value
    val showMessage = LocalShowMessage.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val bottomSpace = LocalBottomBarSpace.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = NoInsets,
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(backdrop) {
                LargeTopAppBar(title = { Text("定时任务") }, colors = TransparentBarColors(), scrollBehavior = scrollBehavior)
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = bottomSpace + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!available) {
                item(key = "not-yet") {
                    EmptyState(Icons.Rounded.Schedule, "定时任务还没接入", "连接真实 Hermes 后的定时任务管理会在后续阶段完成。")
                }
            } else {
                item(key = "summary") { JobsSummary(jobs) }
            }
            if (available && jobs.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.Rounded.Schedule, "还没有定时任务", "让 Hermes 每天早上发简报、定时检查服务器，都可以在这里设置。")
                }
            }
            items(jobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    onToggle = { repository.setJobEnabled(job.id, it) },
                    onRunNow = {
                        repository.runJobNow(job.id)
                        showMessage("已触发「${job.name}」，完成后会通知你")
                    },
                    onEdit = { onEditJob(job.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun JobsSummary(jobs: List<CronJob>) {
    val active = jobs.count { it.enabled }
    val failed = jobs.count { it.lastResult == JobRunResult.Failed }
    val next = jobs.filter { it.enabled }.minByOrNull { it.nextRunLabel }?.nextRunLabel
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SummaryCell("$active", "运行中", Modifier.weight(1f))
            SummaryCell("$failed", "上次失败", Modifier.weight(1f), if (failed > 0) MaterialTheme.colorScheme.error else null)
            SummaryCell(next ?: "—", "下次运行", Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun SummaryCell(value: String, label: String, modifier: Modifier, color: Color? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JobCard(
    job: CronJob,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(onClick = onEdit, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Rounded.Schedule,
                    container = if (job.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = if (job.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.name, style = MaterialTheme.typography.titleMedium)
                    Text(job.scheduleLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Switch(checked = job.enabled, onCheckedChange = onToggle)
            }
            Text(
                job.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .alpha(if (job.enabled) 1f else 0.6f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetaItem(Icons.Rounded.Event, if (job.enabled) "下次 ${job.nextRunLabel}" else "已暂停")
                MetaItem(Icons.Rounded.NotificationsActive, job.deliverTo)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LastResult(job)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑")
                }
                FilledTonalButton(onClick = onRunNow, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即运行")
                }
            }
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LastResult(job: CronJob) {
    val (icon, color, text) = when (job.lastResult) {
        JobRunResult.Success -> Triple(Icons.Rounded.CheckCircle, LocalHermesColors.current.online, "${job.lastRunLabel} 成功")
        JobRunResult.Failed -> Triple(Icons.Rounded.Error, MaterialTheme.colorScheme.error, "${job.lastRunLabel} 失败")
        JobRunResult.Never -> Triple(Icons.Rounded.RemoveCircleOutline, MaterialTheme.colorScheme.outline, "还没运行过")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
