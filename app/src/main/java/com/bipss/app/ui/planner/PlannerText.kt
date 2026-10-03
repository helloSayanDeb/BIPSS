package com.bipss.app.ui.planner

import com.bipss.app.R
import com.bipss.app.localization.AppText
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A minute of the day in the user's clock style, e.g. 10:45 pm or 22:45. */
fun clockText(minuteOfDay: Int): String =
    LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** 490 minutes as "8h 10m"; under an hour, just "55m". */
fun durationText(minutes: Int): String =
    if (minutes < 60) AppText.text(R.string.copy_38950f3977f7, "%1\$sm", minutes)
    else AppText.text(R.string.copy_279d04514bff, "%1\$sh %2\$sm", minutes / 60, minutes % 60)
