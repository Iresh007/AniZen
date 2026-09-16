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
    }

    override fun eventProperty(property: String, value: Long) {
        if (property == "vo-delayed-frame-count") {
            activity.runOnUiThread { activity.onObserverEvent(property, value) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause" || property == "eof-reached") {
            activity.runOnUiThread { activity.onObserverEvent(property, value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property.startsWith("user-data/aniyomi")) {
            activity.runOnUiThread { activity.onObserverEvent(property, value) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (property == "video-params/aspect") {
            activity.runOnUiThread { activity.onObserverEvent(property, value) }
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
    }

    override fun event(eventId: Int, data: MPVNode) {
        activity.runOnUiThread { activity.event(eventId, data) }
    }

    var httpError: String? = null

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            if (text.startsWith(TRACK_LOAD_FAILURE)) {
                val url = text.removePrefix(TRACK_LOAD_FAILURE).substringBeforeLast(".")
                activity.runOnUiThread {
                    activity.onTrackLoadedFailure(url)
                }
            }
        }

        val logPriority = when (level) {
            MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text.removePrefix("http: ")
        logcat.logcat("mpv/$prefix", logPriority) { text }
    }

    companion object {
        const val TRACK_LOAD_FAILURE = "Can not open external file "
    }
}
// ANZ <--
