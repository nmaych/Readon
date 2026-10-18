package com.readon.reader.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.readon.reader.reader.ReaderHud
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * What the reader's own status line needs: the clock and the battery.
 *
 * ## Why this exists
 *
 * The reader hides both system bars, so the phone's clock and battery go with them.
 * Drawing them back inside the page is the whole point of the reader's top and bottom
 * lines (see [ReaderHud]); this is the piece that reads them.
 *
 * ## Why the battery is a broadcast and the clock is a timer
 *
 * The battery changes rarely and unpredictably, so it is read from the sticky
 * `ACTION_BATTERY_CHANGED` broadcast: registering for it delivers the current state
 * immediately and then only on a change, which is both cheap and always correct. The
 * clock changes on a schedule, so it is polled -- but on a timer aligned to the next
 * minute rather than every second: a second-resolution tick would wake the composition
 * sixty times as often to redraw text that changes once.
 *
 * Both are torn down on dispose. A receiver left registered would keep firing into a
 * composition that no longer exists, which on Android is a leak the platform reports as
 * "receiver leaked" only after the fact.
 */
@Immutable
data class StatusReadout(
    /** `14:05`, or null before the first tick has landed. */
    val clock: String? = null,
    /** 0..100. */
    val batteryLevel: Int = 0,
    /** True while plugged in, which suppresses the low-battery warning. */
    val charging: Boolean = false,
)

/**
 * Observe the clock and the battery.
 *
 * Deliberately not lifecycle-aware beyond the composition: the reader is a full-screen
 * destination that is composed and disposed with the book, so "while this composable is
 * in the tree" is exactly the interval in which the information is wanted.
 */
@Composable
fun rememberStatusReadout(): StatusReadout {
    val context = LocalContext.current
    var clock by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf(0) }
    var charging by remember { mutableStateOf(false) }

    // The clock. Re-armed for the *next* minute boundary each time rather than every
    // 60 s from an arbitrary start: starting at :37 and ticking every 60 s would show
    // :37 for up to a minute after the real clock said :38.
    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            clock = ReaderHud.clockText(
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
            )
            val millisIntoMinute = now.get(Calendar.SECOND) * 1000L +
                now.get(Calendar.MILLISECOND)
            delay(60_000L - millisIntoMinute + CLOCK_SLACK_MS)
        }
    }

    // The battery, from the sticky broadcast: it replays its last value on
    // registration, so the first reading arrives without a separate query.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (rawLevel >= 0 && scale > 0) {
                    level = (rawLevel * 100 / scale).coerceIn(0, 100)
                }
                charging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING,
                    BatteryManager.BATTERY_STATUS_FULL,
                    -> true
                    else -> false
                }
            }
        }
        // `registerReceiver` with a null receiver in newer SDKs is an error rather
        // than a no-op, so a context that refuses the registration simply leaves the
        // battery at its default rather than crashing the reader.
        val registered = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.isSuccess
        onDispose {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return StatusReadout(clock = clock, batteryLevel = level, charging = charging)
}

/**
 * Wake just after the minute boundary.
 *
 * Firing exactly on it races the platform's own clock update and can read the previous
 * minute; a few milliseconds of slack makes the tick land after the boundary has passed.
 */
private const val CLOCK_SLACK_MS = 250L
