package eu.kanade.tachiyomi.ui.player

import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import logcat.LogPriority
import logcat.logcat

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
        activity.event(eventId, data)
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level == MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            if (text.startsWith(TRACK_LOAD_FAILURE)) {
                val url = text.removePrefix(TRACK_LOAD_FAILURE).substringBeforeLast(".")
                activity.onTrackLoadedFailure(url)
            }
        }

        if (prefix == "ffmpeg" && text.startsWith("Failed to open an HTTP connection:")) {
            httpError = text.removePrefix("Failed to open an HTTP connection: ")
        }

        val logPriority = when (level) {
            MPV.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        logcat("mpv/$prefix", logPriority) { text }
    }

    var httpError: String? = null

    companion object {
        const val TRACK_LOAD_FAILURE = "Can not open external file "
    }
}
// ANZ <--
