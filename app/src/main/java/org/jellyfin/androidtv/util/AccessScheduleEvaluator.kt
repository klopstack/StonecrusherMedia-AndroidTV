package org.jellyfin.androidtv.util

import android.content.Context
import android.text.format.DateFormat
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.AccessSchedule
import org.jellyfin.sdk.model.api.DynamicDayOfWeek
import org.jellyfin.sdk.model.api.UserPolicy
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Evaluates Jellyfin user access schedules using the same rules as the server
 * ([UserEntityExtensions.IsParentalScheduleAllowed](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Data/UserEntityExtensions.cs)).
 */
object AccessScheduleEvaluator {
	fun isAccessAllowed(policy: UserPolicy?, now: LocalDateTime = LocalDateTime.now()): Boolean {
		if (policy == null || policy.isAdministrator) return true

		val schedules = policy.accessSchedules
		if (schedules.isNullOrEmpty()) return true

		return schedules.any { schedule -> isScheduleAllowed(schedule, now) }
	}

	fun getNextAccessStart(policy: UserPolicy?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
		if (policy == null || policy.isAdministrator) return null
		val schedules = policy.accessSchedules ?: return null
		if (schedules.isEmpty()) return null
		if (isAccessAllowed(policy, now)) return null

		var earliest: LocalDateTime? = null
		for (dayOffset in 0..7) {
			val date = now.toLocalDate().plusDays(dayOffset.toLong())
			for (schedule in schedules) {
				if (!schedule.dayOfWeek.contains(date.dayOfWeek)) continue

				val start = date.atTime(hourFromDouble(schedule.startHour), minuteFromDouble(schedule.startHour))
				if (!start.isAfter(now)) continue
				if (earliest == null || start.isBefore(earliest)) {
					earliest = start
				}
			}
		}

		return earliest
	}

	/** Next time the allowed/denied status may change (schedule boundary). */
	fun getNextStatusChange(policy: UserPolicy?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
		if (policy == null || policy.isAdministrator) return null
		val schedules = policy.accessSchedules ?: return null
		if (schedules.isEmpty()) return null

		return if (isAccessAllowed(policy, now)) {
			getNextDenialTime(schedules, now)
		} else {
			getNextAccessStart(policy, now)
		}
	}

	private fun getNextDenialTime(schedules: List<AccessSchedule>, now: LocalDateTime): LocalDateTime? {
		val hour = now.hour + (now.minute / 60.0) + (now.second / 3600.0)
		var latestEnd: LocalDateTime? = null
		for (schedule in schedules) {
			if (!schedule.dayOfWeek.contains(now.dayOfWeek)) continue
			if (hour < schedule.startHour || hour > schedule.endHour) continue

			val end = now.toLocalDate()
				.atTime(hourFromDouble(schedule.endHour), minuteFromDouble(schedule.endHour))
				.plusMinutes(1)
			if (latestEnd == null || end.isAfter(latestEnd)) latestEnd = end
		}
		return latestEnd?.takeIf { it.isAfter(now) }
	}

	private fun hourFromDouble(hour: Double): Int = hour.toInt()

	private fun minuteFromDouble(hour: Double): Int = ((hour - hour.toInt()) * 60).toInt()

	fun formatNextAccessMessage(context: Context, nextStart: LocalDateTime?, now: LocalDateTime = LocalDateTime.now()): String? {
		if (nextStart == null) return null

		val time = DateFormat.getTimeFormat(context).format(
			Date.from(nextStart.atZone(ZoneId.systemDefault()).toInstant()),
		)
		return when {
			nextStart.toLocalDate() == now.toLocalDate() ->
				context.getString(R.string.access_schedule_resumes_today, time)
			nextStart.toLocalDate() == now.toLocalDate().plusDays(1) ->
				context.getString(R.string.access_schedule_resumes_tomorrow, time)
			else -> {
				val dayFormatter = DateTimeFormatter.ofPattern("EEEE")
				context.getString(R.string.access_schedule_resumes_on_day, nextStart.format(dayFormatter), time)
			}
		}
	}

	private fun isScheduleAllowed(schedule: AccessSchedule, now: LocalDateTime): Boolean {
		val hour = now.hour + (now.minute / 60.0) + (now.second / 3600.0)
		val dayOfWeek = now.dayOfWeek

		return schedule.dayOfWeek.contains(dayOfWeek)
			&& hour >= schedule.startHour
			&& hour <= schedule.endHour
	}

	private fun DynamicDayOfWeek.contains(dayOfWeek: DayOfWeek): Boolean = when (this) {
		DynamicDayOfWeek.EVERYDAY -> true
		DynamicDayOfWeek.WEEKDAY -> dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
		DynamicDayOfWeek.WEEKEND -> dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
		DynamicDayOfWeek.SUNDAY -> dayOfWeek == DayOfWeek.SUNDAY
		DynamicDayOfWeek.MONDAY -> dayOfWeek == DayOfWeek.MONDAY
		DynamicDayOfWeek.TUESDAY -> dayOfWeek == DayOfWeek.TUESDAY
		DynamicDayOfWeek.WEDNESDAY -> dayOfWeek == DayOfWeek.WEDNESDAY
		DynamicDayOfWeek.THURSDAY -> dayOfWeek == DayOfWeek.THURSDAY
		DynamicDayOfWeek.FRIDAY -> dayOfWeek == DayOfWeek.FRIDAY
		DynamicDayOfWeek.SATURDAY -> dayOfWeek == DayOfWeek.SATURDAY
	}
}
