package com.hermes.mobile.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 定时任务的“什么时候跑”。编辑界面用它，存到 Hermes 时转成 Hermes 认识的字符串：
 * cron 表达式（每天/每周）、`every 30m`（间隔）、ISO 时间（一次）、或者原样的自定义写法。
 */
sealed interface JobSchedule {
    data class Daily(val hour: Int, val minute: Int) : JobSchedule

    /** [days]：1 = 周一 … 7 = 周日。 */
    data class Weekly(val days: Set<Int>, val hour: Int, val minute: Int) : JobSchedule

    data class Interval(val minutes: Int) : JobSchedule

    data class Once(val at: LocalDateTime) : JobSchedule

    /** 其它写法（复杂 cron、`every monday 9am` 等），原样交给 Hermes。 */
    data class Custom(val text: String) : JobSchedule

    fun toHermes(): String = when (this) {
        is Daily -> "$minute $hour * * *"
        is Weekly -> "$minute $hour * * ${days.sorted().joinToString(",") { (it % 7).toString() }}"
        is Interval -> if (minutes % 60 == 0) "every ${minutes / 60}h" else "every ${minutes}m"
        is Once -> at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        is Custom -> text.trim()
    }

    fun label(): String = when (this) {
        is Daily -> "每天 ${hhmm(hour, minute)}"
        is Weekly -> when (days) {
            setOf(1, 2, 3, 4, 5) -> "工作日 ${hhmm(hour, minute)}"
            setOf(6, 7) -> "周末 ${hhmm(hour, minute)}"
            (1..7).toSet() -> "每天 ${hhmm(hour, minute)}"
            else -> "每周" + days.sorted().joinToString("、") { WeekdayNames[it - 1] } + " " + hhmm(hour, minute)
        }
        is Interval -> when {
            minutes % 1440 == 0 -> "每 ${minutes / 1440} 天"
            minutes % 60 == 0 -> "每 ${minutes / 60} 小时"
            else -> "每 $minutes 分钟"
        }
        is Once -> "${at.monthValue}月${at.dayOfMonth}日 ${hhmm(at.hour, at.minute)}（一次）"
        is Custom -> text.trim()
    }

    /** 接下来几次运行（按手机的时区算；自定义写法算不了，返回空）。 */
    fun nextRuns(from: LocalDateTime, count: Int = 3): List<LocalDateTime> = when (this) {
        is Daily -> generateSequence(atOrAfter(from, hour, minute)) { it.plusDays(1) }.take(count).toList()
        is Weekly -> generateSequence(from.toLocalDate()) { it.plusDays(1) }.take(15)
            .filter { it.dayOfWeek.value in days }
            .map { it.atTime(hour, minute) }
            .filter { it.isAfter(from) }
            .take(count).toList()
        is Interval -> generateSequence(from.plusMinutes(minutes.toLong())) { it.plusMinutes(minutes.toLong()) }.take(count).toList()
        is Once -> listOf(at).filter { it.isAfter(from) }
        is Custom -> emptyList()
    }

    companion object {
        val WeekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

        /** 读 Hermes 存的 schedule（kind + expr / minutes / run_at，或者原始字符串）还原成编辑用的样子。 */
        fun parse(kind: String?, expr: String?, minutes: Int?, runAt: String?, zone: ZoneId = ZoneId.systemDefault()): JobSchedule {
            when (kind) {
                "interval" -> if (minutes != null && minutes > 0) return Interval(minutes)
                "once" -> parseInstant(runAt)?.let { return Once(LocalDateTime.ofInstant(it, zone)) }
            }
            val text = expr?.trim().orEmpty()
            Regex("""^every\s+(\d+)\s*([mhd])$""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val n = m.groupValues[1].toInt()
                return Interval(n * when (m.groupValues[2].lowercase()) { "h" -> 60; "d" -> 1440; else -> 1 })
            }
            val parts = text.split(Regex("\\s+"))
            if (parts.size == 5) {
                val (min, hr, dom, mon, dow) = parts
                val minute = min.toIntOrNull()
                val hour = hr.toIntOrNull()
                if (minute != null && hour != null && dom == "*" && mon == "*") {
                    if (dow == "*") return Daily(hour, minute)
                    cronDays(dow)?.let { return Weekly(it, hour, minute) }
                }
            }
            if (text.isEmpty()) runAt?.let { r -> parseInstant(r)?.let { return Once(LocalDateTime.ofInstant(it, zone)) } }
            return Custom(text)
        }

        /** cron 的“周几”字段 → 1..7（周一..周日）。认 0-6、7、范围、逗号、MON-SUN。 */
        private fun cronDays(field: String): Set<Int>? {
            val names = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
            fun one(token: String): Int? {
                token.toIntOrNull()?.let { return if (it == 0 || it == 7) 7 else it.takeIf { d -> d in 1..6 } }
                return names.indexOf(token.uppercase()).takeIf { it >= 0 }?.plus(1)
            }
            val out = mutableSetOf<Int>()
            for (piece in field.split(',')) {
                if ('-' in piece) {
                    val a = one(piece.substringBefore('-')) ?: return null
                    val b = one(piece.substringAfter('-')) ?: return null
                    // 1-5 这类；5-0（周五到周日）也能处理
                    var d = a
                    while (true) {
                        out += d
                        if (d == b) break
                        d = d % 7 + 1
                        if (out.size > 7) return null
                    }
                } else {
                    out += one(piece) ?: return null
                }
            }
            return out.takeIf { it.isNotEmpty() }
        }

        private fun atOrAfter(from: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
            val today = from.toLocalDate().atTime(hour, minute)
            return if (today.isAfter(from)) today else today.plusDays(1)
        }
    }
}

private fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

/** Hermes 的时间（带时区的 ISO 字符串）。 */
fun parseInstant(text: String?): Instant? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(t) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
}

/** “今天 08:00” / “明天 08:00” / “周三 08:00” / “10月20日 08:00”。 */
fun friendlyTime(instant: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val t = ZonedDateTime.ofInstant(instant, zone)
    val today = LocalDate.ofInstant(now, zone)
    val clock = hhmm(t.hour, t.minute)
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, t.toLocalDate())
    return when (days) {
        0L -> "今天 $clock"
        1L -> "明天 $clock"
        -1L -> "昨天 $clock"
        in 2L..6L -> "周" + JobSchedule.WeekdayNames[t.dayOfWeek.value - 1] + " " + clock
        else -> if (t.year == today.year) "${t.monthValue}月${t.dayOfMonth}日 $clock" else "${t.year}年${t.monthValue}月${t.dayOfMonth}日 $clock"
    }
}

@Suppress("unused")
private val keepImports = listOf(DayOfWeek.MONDAY, LocalTime.MIDNIGHT)
