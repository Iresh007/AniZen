package eu.kanade.tachiyomi.ui.player

import android.widget.Toast
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// ANZ -->
class PlayerObserver(val activity: PlayerActivity) :
    MPV.EventObserver,
    MPV.LogObserver {

    override fun eventProperty(property: String) {
        activity.runOnUiThread { activity.onObserverEvent(property) }
    }

    override fun eventProperty(property: String, value: Long) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun event(eventId: Int, data: MPVNode) {
        activity.runOnUiThread { activity.event(eventId, data) }
    }

    var httpError: String? = null

    override fun logMessage(prefix: String, level: Int, text: String) {
        val logPriority = when (level) {
            MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text
        logcat.logcat("mpv/$prefix", logPriority) { text }

        if (level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR || level == MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL ||
            text.contains("Cannot open", ignoreCase = true) || text.contains("failed to open", ignoreCase = true)
        ) {
            activity.runOnUiThread {
                activity.viewModel.handleMpvLogFailure(text)
            }
        }
    }

    companion object {
        const val TRACK_LOAD_FAILURE = "Can not open external file "
    }
}
// ANZ <--
