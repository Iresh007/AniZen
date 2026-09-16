/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.os.Build
import android.os.Environment
import android.view.Surface
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.player.controls.components.panels.toColorHexString
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.applyAnime4K
import eu.kanade.tachiyomi.ui.player.buildVFChain
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import eu.kanade.tachiyomi.util.system.DeviceTierManager
import eu.kanade.tachiyomi.util.system.findActivity
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPV
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.reflect.KProperty

// ANZ -->
class AniyomiMPVView(context: Context, attributes: AttributeSet?) : BaseMPVView(context, attributes) {
// ANZ <--

    private val playerPreferences: PlayerPreferences by injectLazy()
    private val decoderPreferences: DecoderPreferences by injectLazy()
    private val subtitlePreferences: SubtitlePreferences by injectLazy()
    private val audioPreferences: AudioPreferences by injectLazy()
    private val advancedPreferences: AdvancedPlayerPreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()
    private val anime4kManager: Anime4KManager by injectLazy()

    var isExiting = false
    var initialized = false
    private var lastAdaptiveCheckTime = 0L

    // ANZ -->
    private fun getPropertyInt(property: String): Int? {
        if (!initialized) return null
        return mpv?.getPropertyInt(property)
    }

    private fun getPropertyBoolean(property: String): Boolean? {
        if (!initialized) return null
        return mpv?.getPropertyBoolean(property)
    }

    private fun getPropertyDouble(property: String): Double? {
        if (!initialized) return null
        return mpv?.getPropertyDouble(property)
    }

    private fun getPropertyString(property: String): String? {
        if (!initialized) return null
        return mpv?.getPropertyString(property)
    }

    val duration: Int?
        get() = getPropertyInt("duration")

    var timePos: Int?
        get() = getPropertyInt("time-pos")
        set(position) {
            if (initialized && position != null) mpv?.setPropertyInt("time-pos", position)
        }

    var paused: Boolean?
        get() = getPropertyBoolean("pause")
        set(paused) {
            if (initialized && paused != null) mpv?.setPropertyBoolean("pause", paused)
        }

    val hwdecActive: String
        get() = getPropertyString("hwdec-current") ?: "no"

    val coreIdle: Boolean?
        get() = getPropertyBoolean("core-idle")

    val pausedForCache: Boolean?
        get() = getPropertyBoolean("paused-for-cache")

    val videoH: Int?
        get() = getPropertyInt("video-params/h")

    fun getVideoOutAspect(): Double? {
        return getPropertyDouble("video-params/aspect")?.let {
            if (it < 0.001) return 0.0
            if ((getPropertyInt("video-params/rotate") ?: 0) % 180 == 90) 1.0 / it else it
        }
    }

    inner class TrackDelegate(private val name: String) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val v = getPropertyString(name)
            if (v == "no" || v == null) return -1
            return v.toIntOrNull() ?: -1
        }
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            if (value == -1) {
                mpv?.setPropertyString(name, "no")
            } else {
                mpv?.setPropertyInt(name, value)
            }
        }
    }

    var sid: Int by TrackDelegate("sid")
    var secondarySid: Int by TrackDelegate("secondary-sid")
    var aid: Int by TrackDelegate("aid")

    private var currentMaxBytes = 192 * 1024 * 1024L
    private var currentMaxBackBytes = 64 * 1024 * 1024L

    fun applyPlaybackStrategy() {
        val performanceProfile = decoderPreferences.performanceProfile().get()
        val tier = when (performanceProfile) {
            PlayerEfficiency.MaxPerformance -> DeviceTierManager.Tier.HIGH
            PlayerEfficiency.Balanced -> DeviceTierManager.Tier.MID
            PlayerEfficiency.PowerSaver -> DeviceTierManager.Tier.LOW
            else -> DeviceTierManager.getTier(context)
        }

        val (maxMb, maxBackMb, readahead) = when (tier) {
            DeviceTierManager.Tier.LOW -> Triple(64, 32, 60)
            DeviceTierManager.Tier.MID -> Triple(128, 64, 120)
            DeviceTierManager.Tier.HIGH -> {
                mpv?.setOptionString("hwdec-extra-frames", "24")
                Triple(192, 128, 180)
            }
        }

        currentMaxBytes = maxMb * 1024 * 1024L
        currentMaxBackBytes = maxBackMb * 1024 * 1024L

        mpv?.setOptionString("demuxer-readahead-secs", "$readahead")
        mpv?.setOptionString("demuxer-max-bytes", "$currentMaxBytes")
        mpv?.setOptionString("demuxer-max-back-bytes", "$currentMaxBackBytes")
    }

    fun restoreCache() {
        mpv?.setPropertyString("demuxer-max-bytes", "$currentMaxBytes")
        mpv?.setPropertyString("demuxer-max-back-bytes", "$currentMaxBackBytes")
    }

    fun shrinkCache() {
        val shrinkBytes = 64 * 1024 * 1024L
        mpv?.setPropertyString("demuxer-max-bytes", "$shrinkBytes")
        mpv?.setPropertyString("demuxer-max-back-bytes", "$shrinkBytes")
    }
    // ANZ <--

    private var pendingVideoToPlay: Pair<eu.kanade.tachiyomi.animesource.model.Video, Long?>? = null

    fun queueOrPlayVideo(video: eu.kanade.tachiyomi.animesource.model.Video, position: Long?, playBlock: (eu.kanade.tachiyomi.animesource.model.Video, Long?) -> Unit) {
        if (!initialized) {
            pendingVideoToPlay = video to position
        } else {
            playBlock(video, position)
        }
    }

    // ANZ -->
    fun release() {
        initialized = false
        holder.removeCallback(this)
        mpv = null
    }

    fun init(mpvInst: MPV) {
        this.mpv = mpvInst
        initialized = true
        setVo(if (decoderPreferences.gpuNext().get()) "gpu-next" else "gpu")

        mpv?.setPropertyBoolean("pause", true)
        mpv?.setOptionString("profile", "fast")
        val isSmoothMotion = decoderPreferences.smoothMotion().get()
        val defaultHwdec = if (decoderPreferences.tryHWDecoding().get()) {
            if (isSmoothMotion) "mediacodec-copy" else "mediacodec,mediacodec-copy"
        } else {
            "no"
        }
        mpv?.setOptionString("hwdec", defaultHwdec)

        // Gated Defaults with HQ toggle
        val isHighQuality = decoderPreferences.highQualityScaling().get()
        val scaler = if (isHighQuality) "spline36" else "bilinear"
        mpv?.setOptionString("scale", scaler)
        mpv?.setOptionString("cscale", scaler)
        mpv?.setOptionString("dscale", scaler)
        mpv?.setOptionString("dither", if (isHighQuality) "fruit" else "no")

        when (decoderPreferences.videoDebanding().get()) {
            Debanding.None -> {}
            Debanding.CPU -> mpv?.setOptionString("vf", "gradfun=radius=12")
            Debanding.GPU -> mpv?.setOptionString("deband", "yes")
        }

        if (isSmoothMotion) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay
            }
            val detectedRefreshRate = display?.refreshRate?.takeIf { it > 0 } ?: 60f
            val fpsLimit = decoderPreferences.interpolationFPSLimit().get()
            val targetFps = if (fpsLimit > 0) fpsLimit.toDouble() else detectedRefreshRate.toDouble()

            mpv?.setOptionString("video-sync", "display-resample")
            mpv?.setOptionString("interpolation", "yes")
            mpv?.setOptionString("correct-pts", "yes")
            mpv?.setOptionString("tscale", decoderPreferences.interpolationMode().get().value)
            mpv?.setOptionString("display-fps", targetFps.toString())
            mpv?.setOptionString("override-display-fps", targetFps.toString())
        } else {
            mpv?.setOptionString("video-sync", "audio")
            mpv?.setOptionString("interpolation", "no")
        }

        if (decoderPreferences.useYUV420P().get()) {
            mpv?.setOptionString("vf", "format=yuv420p")
        }

        if (decoderPreferences.enableAnime4K().get()) {
            anime4kManager.initialize()
            applyAnime4K(mpv, decoderPreferences, anime4kManager, isInit = true)
        }

        mpv?.setOptionString("msg-level", "all=" + if (networkPreferences.verboseLogging().get()) "v" else "warn")
        mpv?.setPropertyBoolean("input-default-bindings", true)
        mpv?.setOptionString("keep-open", "yes")
        mpv?.setOptionString("ytdl", "no")
        mpv?.setOptionString("cookies", "yes")
        mpv?.setOptionString("cache", "yes")
        mpv?.setOptionString("demuxer-thread", "yes")

        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        mpv?.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        mpv?.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")

        applyPlaybackStrategy()

        mpv?.setOptionString("hr-seek", "default")
        mpv?.setOptionString("sub-auto", "fuzzy")

        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        screenshotDir.mkdirs()
        mpv?.setOptionString("screenshot-directory", screenshotDir.path)

        // Only apply non-zero filters
        VideoFilters.entries.forEach {
            val value = it.preference(decoderPreferences).get()
            if (value != 0 && !it.mpvProperty.startsWith("vf_")) {
                mpv?.setOptionString(it.mpvProperty, value.toString())
            }
        }

        mpv?.setOptionString("speed", playerPreferences.playerSpeed().get().toString())
        mpv?.setOptionString("vd-lavc-film-grain", "cpu")

        setupSubtitlesOptions()
        setupAudioOptions()
        postInitOptions()
        observeProperties()
    }

    fun observeProperties() {
        for ((name, format) in observedProps) mpv?.observeProperty(name, format)
    }

    var onPlayerReady: (() -> Unit)? = null

    fun postInitOptions() {
        onPlayerReady?.invoke()
        pendingVideoToPlay?.let { (vid, pos) ->
            pendingVideoToPlay = null
        }
        if (decoderPreferences.smoothMotion().get()) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay
            }
            val detectedRefreshRate = display?.refreshRate?.takeIf { it > 0 } ?: 60f
            val fpsLimit = decoderPreferences.interpolationFPSLimit().get()
            val targetFps = if (fpsLimit > 0) fpsLimit.toDouble() else detectedRefreshRate.toDouble()

            mpv?.setPropertyDouble("display-fps", targetFps)
            mpv?.setPropertyDouble("override-display-fps", targetFps)
        }
        advancedPreferences.playerStatisticsPage().get().let {
            if (it in 1..5) {
                mpv?.command("script-binding", "stats/display-stats-toggle")
                mpv?.command("script-binding", "stats/display-page-$it")
            } else if (it == 6 || it == 0) {
                mpv?.setPropertyString("user-data/stats/display-page", "0")
            }
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) return false
        var mapped = KeyMapping[event.keyCode]
        if (mapped == null) {
            if (!event.isPrintingKey) return false
            val ch = event.unicodeChar
            if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) return false
            mapped = ch.toChar().toString()
        }
        if (event.repeatCount > 0) return true
        val mod: MutableList<String> = mutableListOf()
        event.isShiftPressed && mod.add("shift")
        event.isCtrlPressed && mod.add("ctrl")
        event.isAltPressed && mod.add("alt")
        event.isMetaPressed && mod.add("meta")
        val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        mod.add(mapped)
        mpv?.command(action, mod.joinToString("+"))
        return true
    }

    private val observedProps = mapOf(
        "chapter-list" to MPV.mpvFormat.MPV_FORMAT_NONE,
        "track-list" to MPV.mpvFormat.MPV_FORMAT_NONE,
        "time-pos" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "duration" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "volume" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "volume-max" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "sid" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "secondary-sid" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "aid" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-zoom" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-pan-x" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-pan-y" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "video-params/aspect" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "hwdec-current" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "hwdec" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "interpolation" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "video-sync" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "tscale" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "display-fps" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "override-display-fps" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "estimated-display-fps" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "user-data/current-anime/intro-length" to MPV.mpvFormat.MPV_FORMAT_INT64,
        "user-data/aniyomi/show_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/show_seek_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/toggle_ui" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/show_panel" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/software_keyboard" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/set_button_title" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/reset_button_title" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/toggle_button" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/switch_episode" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/pause" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_by" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_to" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_by_with_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/seek_to_with_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
        "user-data/aniyomi/launch_int_picker" to MPV.mpvFormat.MPV_FORMAT_STRING,
    )

    private fun setupAudioOptions() {
        mpv?.setOptionString("alang", audioPreferences.preferredAudioLanguages().get())
        mpv?.setOptionString("audio-delay", (audioPreferences.audioDelay().get() / 1000.0).toString())
        mpv?.setOptionString("audio-pitch-correction", audioPreferences.enablePitchCorrection().get().toString())
        mpv?.setOptionString("volume-max", (audioPreferences.volumeBoostCap().get() + 100).toString())
    }

    private fun setupSubtitlesOptions() {
        mpv?.setOptionString("slang", subtitlePreferences.preferredSubLanguages().get())
        mpv?.setOptionString("sub-delay", (subtitlePreferences.subtitlesDelay().get() / 1000.0).toString())
        mpv?.setOptionString("sub-speed", subtitlePreferences.subtitlesSpeed().get().toString())
        mpv?.setOptionString("secondary-sub-delay", (subtitlePreferences.subtitlesSecondaryDelay().get() / 1000.0).toString())
        mpv?.setOptionString("sub-font", subtitlePreferences.subtitleFont().get())
        if (subtitlePreferences.overrideSubsASS().get()) {
            mpv?.setOptionString("sub-ass-override", "force")
            mpv?.setOptionString("sub-ass-justify", "yes")
        }
        mpv?.setOptionString("sub-font-size", subtitlePreferences.subtitleFontSize().get().toString())
        mpv?.setOptionString("sub-bold", if (subtitlePreferences.boldSubtitles().get()) "yes" else "no")
        mpv?.setOptionString("sub-italic", if (subtitlePreferences.italicSubtitles().get()) "yes" else "no")
        mpv?.setOptionString("sub-justify", subtitlePreferences.subtitleJustification().get().value)
        mpv?.setOptionString("sub-color", subtitlePreferences.textColorSubtitles().get().toColorHexString())
        mpv?.setOptionString("sub-back-color", subtitlePreferences.backgroundColorSubtitles().get().toColorHexString())
        mpv?.setOptionString("sub-border-color", subtitlePreferences.borderColorSubtitles().get().toColorHexString())
        mpv?.setOptionString("sub-border-size", subtitlePreferences.subtitleBorderSize().get().toString())
        mpv?.setOptionString("sub-border-style", subtitlePreferences.borderStyleSubtitles().get().value)
        mpv?.setOptionString("sub-shadow-offset", subtitlePreferences.shadowOffsetSubtitles().get().toString())
        mpv?.setOptionString("sub-pos", subtitlePreferences.subtitlePos().get().toString())
        mpv?.setOptionString("sub-scale", subtitlePreferences.subtitleFontScale().get().toString())
    }

    fun checkAdaptiveScaling(delayedFrames: Long) {
        if (!decoderPreferences.adaptiveShaderScaling().get() || !decoderPreferences.enableAnime4K().get() || PlayerStats.isAdaptiveDowngraded.value) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAdaptiveCheckTime < 5000) return
        lastAdaptiveCheckTime = currentTime
        if (delayedFrames > 10 && decoderPreferences.anime4kQuality().get() == "HIGH") {
            decoderPreferences.anime4kQuality().set("BALANCED")
            applyAnime4K(mpv, decoderPreferences, anime4kManager)
            PlayerStats.isAdaptiveDowngraded.value = true
            (context as? PlayerActivity)?.runOnUiThread {
                (context as? PlayerActivity)?.showToast("Performance: Anime4K downgraded to Balanced")
            }
        }
    }
    // ANZ <--
}
