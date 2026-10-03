/*
 * This file is part of the alpha budget app's port of bd-sms-parsers,
 * licensed under the GNU AGPL v3. See LICENSE for the full text.
 */
package me.shovon.bdparser

/**
 * A plain calendar date. Stands in for java.time.LocalDate, which Android only
 * ships from API 26 (Android 8); the app targets API 24.
 */
data class SimpleDate(val year: Int, val month: Int, val day: Int) {
    companion object {
        /** Returns null for impossible dates (month 13, 30 February, ...), as LocalDate.of would throw. */
        fun ofOrNull(year: Int, month: Int, day: Int): SimpleDate? {
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val maxDay = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                in 1..12 -> 31
                else -> return null
            }
            return if (day in 1..maxDay) SimpleDate(year, month, day) else null
        }
    }
}
