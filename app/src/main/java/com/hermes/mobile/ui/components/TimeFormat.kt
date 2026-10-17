package com.hermes.mobile.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val md = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)

private fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** 聊天列表右侧的时间：今天显示 14:05，昨天显示“昨天”，一周内显示星期，再早显示日期。 */
fun listTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val days = ChronoUnit.DAYS.between(epochMillis.toDate(), now.toDate())
    return when {
        days <= 0L -> Instant.ofEpochMilli(epochMillis).atZone(zone).format(hm)
        days == 1L -> "昨天"
        days < 7L -> Instant.ofEpochMilli(epochMillis).atZone(zone).format(weekday)
        else -> Instant.ofEpochMilli(epochMillis).atZone(zone).format(md)
    }
}

/** 消息下方的小时间：14:05。 */
fun clockTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(hm)

/** 聊天里的日期分隔：今天 / 昨天 / 10月12日 星期日。 */
fun dayLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val date = epochMillis.toDate()
    val days = ChronoUnit.DAYS.between(date, now.toDate())
    return when (days) {
        0L -> "今天"
        1L -> "昨天"
        else -> "${date.format(md)} ${date.format(weekday)}"
    }
}

fun sameDay(a: Long, b: Long): Boolean = a.toDate() == b.toDate()
