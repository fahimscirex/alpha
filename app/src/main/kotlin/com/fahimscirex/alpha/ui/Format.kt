package com.fahimscirex.alpha.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** "−৳320.00" / "+৳375.00" / "৳43,417.94"; minor units in, two decimals out. */
fun money(minor: Long, currency: String, signed: Boolean = false): String {
    val sign = if (!signed) (if (minor < 0) "−" else "") else if (minor < 0) "−" else "+"
    val symbol = if (currency == "BDT") "৳" else "$currency "
    return sign + symbol + String.format(Locale.US, "%,.2f", abs(minor) / 100.0)
}

fun label(provider: String, number: String) = if (number.isEmpty()) provider else "$provider ··$number"

val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
val dayHeaderFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/** Start and end of the month [offset] months from now, in local time. */
fun month(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.MONTH, offset)
    }
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to c.timeInMillis
}

fun dayKey(millis: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

// The date picker works in UTC days ("midnight UTC of the chosen date"), the app in local time.
private val utc = TimeZone.getTimeZone("UTC")
val pickerDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply { timeZone = utc }
private fun dayKeyIn(tz: TimeZone) = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }

/** A local day as the picker's UTC-midnight value (Dhaka after midnight is still yesterday in UTC). */
fun pickerDay(millis: Long): Long = dayKeyIn(utc).parse(dayKeyIn(TimeZone.getDefault()).format(Date(millis)))!!.time

/**
 * Timestamp for an entry on the picked day: now when it is today, so it counts as newer than
 * balances stated earlier today; otherwise local noon of that day.
 */
fun entryTime(picked: Long): Long {
    val now = System.currentTimeMillis()
    if (picked == pickerDay(now)) return now
    return dayKeyIn(TimeZone.getDefault()).parse(dayKeyIn(utc).format(Date(picked)))!!.time + 12 * 3_600_000L
}
