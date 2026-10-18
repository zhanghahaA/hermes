package com.hermes.mobile.data.model

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobScheduleTest {
    @Test
    fun roundTripsCommonSchedules() {
        val cases = listOf(
            JobSchedule.Daily(8, 0) to "0 8 * * *",
            JobSchedule.Weekly(setOf(1, 2, 3, 4, 5), 9, 30) to "30 9 * * 1,2,3,4,5",
            JobSchedule.Weekly(setOf(7), 20, 0) to "0 20 * * 0",
            JobSchedule.Interval(30) to "every 30m",
            JobSchedule.Interval(120) to "every 2h",
        )
        for ((schedule, text) in cases) {
            assertEquals(text, schedule.toHermes())
            assertEquals(schedule, JobSchedule.parse("cron", text, null, null), text)
        }
    }

    @Test
    fun readsWhatHermesStores() {
        assertEquals(JobSchedule.Interval(45), JobSchedule.parse("interval", "every 45m", 45, null))
        assertEquals(JobSchedule.Weekly(setOf(1, 2, 3, 4, 5), 8, 0), JobSchedule.parse("cron", "0 8 * * 1-5", null, null))
        assertEquals(JobSchedule.Weekly(setOf(1, 3, 5), 7, 15), JobSchedule.parse("cron", "15 7 * * MON,WED,FRI", null, null))
        val once = JobSchedule.parse("once", null, null, "2026-10-20T08:00:00+00:00", ZoneOffset.UTC)
        assertEquals(JobSchedule.Once(LocalDateTime.of(2026, 10, 20, 8, 0)), once)
        assertTrue(JobSchedule.parse("cron", "*/5 9-17 * * 1-5", null, null) is JobSchedule.Custom, "复杂写法原样保留")
    }

    @Test
    fun labelsAreReadable() {
        assertEquals("工作日 08:00", JobSchedule.Weekly(setOf(1, 2, 3, 4, 5), 8, 0).label())
        assertEquals("每周一、三 07:15", JobSchedule.Weekly(setOf(1, 3), 7, 15).label())
        assertEquals("每 2 小时", JobSchedule.Interval(120).label())
        assertEquals("10月20日 08:00（一次）", JobSchedule.Once(LocalDateTime.of(2026, 10, 20, 8, 0)).label())
    }

    @Test
    fun nextRunsAreComputed() {
        val now = LocalDateTime.of(2026, 10, 18, 9, 0) // 周日
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 0), JobSchedule.Daily(8, 0).nextRuns(now).first())
        assertEquals(LocalDateTime.of(2026, 10, 18, 22, 0), JobSchedule.Daily(22, 0).nextRuns(now).first())
        assertEquals(
            listOf(LocalDateTime.of(2026, 10, 19, 8, 0), LocalDateTime.of(2026, 10, 21, 8, 0)),
            JobSchedule.Weekly(setOf(1, 3), 8, 0).nextRuns(now, 2),
        )
    }
}
