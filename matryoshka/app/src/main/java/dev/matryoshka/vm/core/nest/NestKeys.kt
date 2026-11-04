// SPDX-License-Identifier: Apache-2.0
package dev.matryoshka.vm.core.nest

import android.view.KeyEvent

/** Phone keys and characters as Linux key codes (input-event-codes.h) for the guest's keyboard. */
object NestKeys {
    private val letters = intArrayOf(
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44,
    )  // a..z
    private val digits = intArrayOf(11, 2, 3, 4, 5, 6, 7, 8, 9, 10)  // 0..9

    // US layout: character -> (key, shift)
    private val symbols: Map<Char, Pair<Int, Boolean>> = mapOf(
        ' ' to (57 to false), '\n' to (28 to false), '\t' to (15 to false),
        '-' to (12 to false), '_' to (12 to true), '=' to (13 to false), '+' to (13 to true),
        '[' to (26 to false), '{' to (26 to true), ']' to (27 to false), '}' to (27 to true),
        ';' to (39 to false), ':' to (39 to true), '\'' to (40 to false), '"' to (40 to true),
        '`' to (41 to false), '~' to (41 to true), '\\' to (43 to false), '|' to (43 to true),
        ',' to (51 to false), '<' to (51 to true), '.' to (52 to false), '>' to (52 to true),
        '/' to (53 to false), '?' to (53 to true),
        '!' to (2 to true), '@' to (3 to true), '#' to (4 to true), '$' to (5 to true), '%' to (6 to true),
        '^' to (7 to true), '&' to (8 to true), '*' to (9 to true), '(' to (10 to true), ')' to (11 to true),
    )

    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> letters[c - 'a'] to false
        in 'A'..'Z' -> letters[c - 'A'] to true
        in '0'..'9' -> digits[c - '0'] to false
        else -> symbols[c]
    }

    fun forAndroidKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> 14
        KeyEvent.KEYCODE_FORWARD_DEL -> 111
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 28
        KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_ESCAPE -> 1
        KeyEvent.KEYCODE_DPAD_UP -> 103
        KeyEvent.KEYCODE_DPAD_DOWN -> 108
        KeyEvent.KEYCODE_DPAD_LEFT -> 105
        KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_MOVE_HOME -> 102
        KeyEvent.KEYCODE_MOVE_END -> 107
        KeyEvent.KEYCODE_PAGE_UP -> 104
        KeyEvent.KEYCODE_PAGE_DOWN -> 109
        else -> null
    }
}
