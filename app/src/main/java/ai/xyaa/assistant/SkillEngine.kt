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
        val text = raw.trim()
        val lower = text.lowercase(Locale.getDefault())

        if (lower.matches(Regex("^(hi|hello|hey|hey xyaa|hey zyaa)[!. ]*$"))) return "Hey! What can I help you with?"
        if (lower.contains("what time") || lower == "time" || lower == "current time") {
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        }
        if (lower.contains("what date") || lower.contains("today's date") || lower == "date" || lower.contains("what day")) {
            return "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."
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
            val amount = timer.groupValues[1].toIntOrNull() ?: return "That timer duration is too large."
            val unit = timer.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
                unit.startsWith("min") -> amount * 60
                else -> amount
            }
            if (seconds !in 1..86400) return "Choose a timer between 1 second and 24 hours."
            return launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, false), "Opening the timer.")
        }

        val alarm = Regex("^(?:set )?(?:an )?alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$", RegexOption.IGNORE_CASE).find(text)
        if (alarm != null) {
            var hour = alarm.groupValues[1].toInt()
            val minute = alarm.groupValues[2].ifBlank { "0" }.toInt()
            val suffix = alarm.groupValues[3].lowercase()
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return "That alarm time doesn't look valid."
            return launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute), "Opening the alarm screen.")
        }

        val open = Regex("^(?:please )?(?:open (?!website )|launch |start )(.+?)[.!?]?$", RegexOption.IGNORE_CASE).find(text)
        if (open != null) return openApp(open.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'"))

        val search = Regex("^(?:search(?: for)?|google) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (search != null) return webSearch(search.groupValues[1])
        val youtube = Regex("^(?:play|search youtube for|find on youtube) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (youtube != null) return launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(youtube.groupValues[1]))), "Searching YouTube.")
        val weather = Regex("^(?:weather|what's the weather|what is the weather)(?: in (.+))?$", RegexOption.IGNORE_CASE).find(text)
        if (weather != null) return webSearch("weather " + weather.groupValues[1])
        val map = Regex("^(?:directions to|navigate to|find on maps) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (map != null) return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(map.groupValues[1]))), "Opening directions.")
        val call = Regex("^(?:call|phone) (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (call != null) return launch(Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:")), "Opening the dialler. Choose the contact there to continue.")
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

    private class Parser(private val source: String) {
        private var index = 0
        fun parse(): Double {
            val value = expression()
            require(index == source.length)
            return value
        }
        private fun expression(): Double {
            var value = term()
            while (index < source.length && (source[index] == '+' || source[index] == '-')) {
                val operator = source[index++]
                val right = term()
                value = if (operator == '+') value + right else value - right
            }
            return value
        }
        private fun term(): Double {
            var value = factor()
            while (index < source.length && (source[index] == '*' || source[index] == '/' || source[index] == '%')) {
                val operator = source[index++]
                val right = factor()
                value = when (operator) { '*' -> value * right; '/' -> value / right; else -> value % right }
            }
            return value
        }
        private fun factor(): Double {
            if (index < source.length && (source[index] == '+' || source[index] == '-')) {
                val sign = source[index++]
                return (if (sign == '-') -1 else 1) * factor()
            }
            if (index < source.length && source[index] == '(') {
                index++
                val value = expression()
                require(index < source.length && source[index] == ')')
                index++
                return value
            }
            val begin = index
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            require(index > begin)
            return source.substring(begin, index).toDouble()
        }
    }
}
