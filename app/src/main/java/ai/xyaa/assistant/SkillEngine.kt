package ai.xyaa.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Offline-first intent router. Each skill returns null when it does not own the request. */
class SkillEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("xyaa_notes", Context.MODE_PRIVATE)

    fun execute(raw: String): String? {
        var text = raw.trim()
        repeat(2) {
            text = text.replace(Regex("(?i)^hey\\s+(?:xyaa|zyaa)[, ]+"), "")
                .replace(Regex("(?i)^(?:please\\s+|can you\\s+|could you\\s+|would you\\s+)+"), "")
        }
        val lower = text.lowercase(Locale.ROOT)

        if (lower.matches(Regex("^(hi|hello|hey|hey xyaa|hey zyaa)[!. ]*$"))) return "Hey! What can I help you with?"
        if (lower.contains("what time") || lower == "time" || lower == "current time") {
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        }
        if (lower.contains("what date") || lower.contains("today's date") || lower == "date" || lower.contains("what day")) {
            return "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."
        }

        val reminder = Regex(
            "^remind me (?:in (\\d+)\\s*(minutes?|mins?|hours?|hrs?|days?) to (.+)|to (.+) in (\\d+)\\s*(minutes?|mins?|hours?|hrs?|days?))$",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (reminder != null) {
            val amountText = reminder.groupValues[1].ifBlank { reminder.groupValues[5] }
            val unit = reminder.groupValues[2].ifBlank { reminder.groupValues[6] }.lowercase(Locale.ROOT)
            val detail = reminder.groupValues[3].ifBlank { reminder.groupValues[4] }.trim().take(500)
            val amount = amountText.toLongOrNull() ?: return "That reminder time is too large."
            val multiplier = when {
                unit.startsWith("day") -> 1440L
                unit.startsWith("hour") || unit.startsWith("hr") -> 60L
                else -> 1L
            }
            val delay = runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
                ?: return "That reminder time is too large."
            if (delay !in 1L..10080L) return "Choose a reminder between 1 minute and 7 days."
            if (detail.isBlank()) return "What should I remind you about?"
            return runCatching {
                BackgroundTaskScheduler(context).scheduleReminder(delay, detail)
                val notificationNote = if (BackgroundTaskScheduler(context).notificationsEnabled()) "" else
                    " Notifications are currently disabled for XYAA, so enable them in Android Settings to receive it."
                "Reminder scheduled for $amount $unit from now: $detail.$notificationNote"
            }.getOrElse { "I couldn't schedule that reminder: ${it.message ?: "unknown error"}" }
        }

        val note = Regex("^(?:remember|save|take) (?:a )?note(?: that)?[: ]+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (note != null) {
            val old = prefs.getString("notes", "").orEmpty()
            val updated = (old.lines().filter { it.isNotBlank() } + note.groupValues[1]).takeLast(50)
            prefs.edit().putString("notes", updated.joinToString("\n")).apply()
            return "Saved that note."
        }
        if (lower in listOf("show my notes", "read my notes", "list notes", "my notes")) {
            val notes = prefs.getString("notes", "").orEmpty()
            return if (notes.isBlank()) "You don't have any saved notes yet." else "Here are your notes: " + notes.lines().mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(". ")
        }
        if (lower in listOf("clear my notes", "delete my notes")) {
            prefs.edit().remove("notes").apply()
            return "Your saved notes have been cleared."
        }

        val calc = Regex("^(?:calculate|what is|what's)\\s+([0-9(). +*/%-]+)[? ]*$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^([0-9(). +*/%-]+)$").find(text)
        if (calc != null) {
            val expression = calc.groupValues[1].trim()
            val value = runCatching { Arithmetic.evaluate(expression) }.getOrNull()
            if (value != null && value.isFinite()) return "The answer is ${value.toString().removeSuffix(".0")}."
        }

        val timer = Regex("^(?:set )?(?:a )?timer for (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", RegexOption.IGNORE_CASE).find(text)
        if (timer != null) {
            val amount = timer.groupValues[1].toLongOrNull() ?: return "That timer duration is too large."
            val unit = timer.groupValues[2].lowercase()
            val multiplier = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> 3600L
                unit.startsWith("min") -> 60L
                else -> 1L
            }
            val seconds = runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
                ?: return "That timer duration is too large."
            if (seconds !in 1L..86400L) return "Choose a timer between 1 second and 24 hours."
            return launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt()).putExtra(AlarmClock.EXTRA_SKIP_UI, false), "Opening the timer.")
        }

        val alarm = Regex("^(?:set )?(?:an )?alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$", RegexOption.IGNORE_CASE).find(text)
        if (alarm != null) {
            var hour = alarm.groupValues[1].toInt()
            val minute = alarm.groupValues[2].ifBlank { "0" }.toInt()
            val suffix = alarm.groupValues[3].lowercase()
            if (suffix.isNotBlank() && hour !in 1..12) return "For AM or PM, choose an hour from 1 to 12."
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return "That alarm time doesn't look valid."
            return launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute), "Opening the alarm screen.")
        }

        val search = Regex("^(?:search(?: for)?|google) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (search != null) return webSearch(search.groupValues[1])
        val youtube = Regex("^(?:play|search youtube for|find on youtube) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (youtube != null) return launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(youtube.groupValues[1]))), "Searching YouTube.")
        val weather = Regex("^(?:weather|what's the weather|what is the weather)(?: in (.+))?$", RegexOption.IGNORE_CASE).find(text)
        if (weather != null) return webSearch("weather " + weather.groupValues[1])
        val map = Regex("^(?:directions to|navigate to|find on maps) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (map != null) return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(map.groupValues[1]))), "Opening directions.")
        val call = Regex("^(?:call|phone) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (call != null) {
            val target = call.groupValues[1].trim()
            val number = target.filter { it.isDigit() || it == '+' }
            return if (number.length >= 5 && target.all { it.isDigit() || it in "+- ()" }) {
                launch(Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:" + Uri.encode(number))), "Opening the dialler with the number. Review it and tap Call.")
            } else {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")), "Opening contacts. Choose $target there; XYAA will not place a call automatically.")
            }
        }
        if ((lower.startsWith("turn on ") || lower.startsWith("turn off ") || lower.startsWith("enable ") || lower.startsWith("disable ")) &&
            (lower.contains("wifi") || lower.contains("wi-fi"))) {
            return launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings. Android will let you confirm the change.")
        }
        if ((lower.startsWith("turn on ") || lower.startsWith("turn off ") || lower.startsWith("enable ") || lower.startsWith("disable ")) &&
            lower.contains("bluetooth")) {
            return launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings. Android will let you confirm the change.")
        }
        if (lower in listOf("open settings", "phone settings", "device settings")) return launch(Intent(Settings.ACTION_SETTINGS), "Opening device settings.")
        if (lower.contains("bluetooth settings")) return launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings.")
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings")) return launch(Intent(Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings.")
        if (lower.contains("display settings")) return launch(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Opening display settings.")
        if (lower.contains("sound settings") || lower.contains("volume settings")) return launch(Intent(Settings.ACTION_SOUND_SETTINGS), "Opening sound settings.")
        if (lower.contains("battery settings")) return launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "Opening battery settings.")
        if (lower in listOf("open calendar", "show calendar", "my calendar")) return launch(Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI), "Opening your calendar.")
        if (lower in listOf("open camera", "take a photo", "launch camera")) return launch(Intent("android.media.action.IMAGE_CAPTURE"), "Opening the camera.")
        if (lower in listOf("open email", "check email", "launch email")) return launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL), "Opening your email app.")
        if (lower in listOf("open contacts", "show contacts")) return launch(Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")), "Opening contacts.")
        if (lower in listOf("open calculator", "launch calculator")) return openApp("calculator")
        if (lower.startsWith("define ")) return webSearch("define " + text.removePrefix("define "))
        if (lower.startsWith("translate ")) return webSearch(text)
        if (lower.startsWith("news")) return webSearch("latest news")
        if (lower.startsWith("open website ")) {
            val host = text.substringAfter("open website ").trim()
            val url = if (host.startsWith("http")) host else "https://$host"
            return launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opening website.")
        }
        val open = Regex("^(?:please )?(?:open|launch|start) (.+?)[.!?]?$", RegexOption.IGNORE_CASE).find(text)
        if (open != null) return openApp(open.groupValues[1].trim().removeSurrounding("\\\"").removeSurrounding("\\'"))
        return null
    }

    private fun webSearch(query: String): String =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))), "Searching the web for $query.")

    private fun openApp(requested: String): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(requested)
        if (launchIntent != null) return launch(launchIntent, "Opening $requested.")
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(main, 0)
            .filter { it.loadLabel(context.packageManager).toString().contains(requested, true) }
            .distinctBy { it.activityInfo.packageName }
        if (matches.size == 1) {
            val label = matches.first().loadLabel(context.packageManager).toString()
            return launch(context.packageManager.getLaunchIntentForPackage(matches.first().activityInfo.packageName)!!, "Opening $label.")
        }
        if (matches.size > 1) return "I found several apps matching $requested. Please be more specific."
        return "I couldn't find an installed app named $requested."
    }

    private fun launch(intent: Intent, success: String): String = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) "That feature isn't available on this device."
        else { context.startActivity(intent); success }
    } catch (_: Exception) { "Android couldn't open that feature." }
}

private object Arithmetic {
    fun evaluate(source: String): Double = Parser(source.replace(" ", "")).parse()
    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = expression(); require(i == s.length); return v }
        private fun expression(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]; val r = factor()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        private fun factor(): Double {
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val sign = s[i++]; return (if (sign == '-') -1.0 else 1.0) * factor()
            }
            if (i < s.length && s[i] == '(') {
                i++; val v = expression(); require(i < s.length && s[i] == ')'); i++; return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
    }
}
