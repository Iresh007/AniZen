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
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import animiru.feature.mpvfiles.MpvConfig
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.player.controls.components.panels.toColorHexString
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.DecoderPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.applyAnime4K
import eu.kanade.tachiyomi.ui.player.utils.Anime4KManager
import eu.kanade.tachiyomi.util.system.DeviceTierManager
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPV
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.injectLazy

// ANZ -->
class AniyomiMPVView(context: Context, attributes: AttributeSet?) : BaseMPVView(context, attributes) {

    private val playerPreferences: PlayerPreferences by injectLazy()
    private val decoderPreferences: DecoderPreferences by injectLazy()
    private val subtitlePreferences: SubtitlePreferences by injectLazy()
    private val audioPreferences: AudioPreferences by injectLazy()
    private val advancedPreferences: AdvancedPlayerPreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()
    private val anime4kManager: Anime4KManager by injectLazy()

    var isExiting = false
    private var lastAdaptiveCheckTime = 0L

    private val optionNameRegex = Regex("""^(?:--)?([\w-]+)(?:=|$)""", RegexOption.MULTILINE)
    private val mpvOptionNames = optionNameRegex.findAll(advancedPreferences.mpvConf().get()).map {
        it.groupValues[1].removePrefix("no-")
    }.toSet()

    // Set mpv option unless it's present in mpv.conf
    private fun setSafeOptionString(name: String, value: String) {
        if (name in mpvOptionNames) return
        mpv?.setOptionString(name, value)
    }

    val duration: Int?
        get() = mpv?.getPropertyInt("duration")

    var timePos: Int?
        get() = mpv?.getPropertyInt("time-pos")
        set(position) {
            if (position != null) mpv?.setPropertyInt("time-pos", position)
        }

    var paused: Boolean?
        get() = mpv?.getPropertyBoolean("pause")
        set(paused) {
            if (paused != null) mpv?.setPropertyBoolean("pause", paused)
        }

    val hwdecActive: String
        get() = mpv?.getPropertyString("hwdec-current") ?: "no"

    /**
     * Returns the video aspect ratio. Rotation is taken into account.
     */
    fun getVideoOutAspect(): Double? {
        return mpv?.getPropertyDouble("video-params/aspect")?.let {
            if (it < 0.001) return 0.0
            if ((mpv?.getPropertyInt("video-params/rotate") ?: 0) % 180 == 90) 1.0 / it else it
        }
    }

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
            DeviceTierManager.Tier.HIGH -> Triple(192, 128, 180)
        }

        currentMaxBytes = maxMb * 1024 * 1024L
        currentMaxBackBytes = maxBackMb * 1024 * 1024L

        setSafeOptionString("demuxer-readahead-secs", "$readahead")
        setSafeOptionString("demuxer-max-bytes", "$currentMaxBytes")
        setSafeOptionString("demuxer-max-back-bytes", "$currentMaxBackBytes")
    }

    fun restoreCache() {
        runCatching {
            mpv?.setPropertyString("demuxer-max-bytes", "$currentMaxBytes")
            mpv?.setPropertyString("demuxer-max-back-bytes", "$currentMaxBackBytes")
        }
    }

    fun shrinkCache() {
        runCatching {
            val shrinkBytes = 64 * 1024 * 1024L
            mpv?.setPropertyString("demuxer-max-bytes", "$shrinkBytes")
            mpv?.setPropertyString("demuxer-max-back-bytes", "$shrinkBytes")
        }
    }

    fun init(mpvInst: MPV) {
        this.mpv = mpvInst
        val targetVo = if (decoderPreferences.gpuNext().get()) "gpu-next" else "gpu"
        setVo(targetVo)
        mpv?.setPropertyBoolean("pause", true)
        setSafeOptionString("profile", "fast")

        // ANZ -->
        mpv?.setOptionString("hwdec", if (decoderPreferences.tryHWDecoding().get()) "auto" else "no")
        val isSmoothMotion = decoderPreferences.smoothMotion().get()
        // ANZ <--

        // Scaling quality
        val isHighQuality = decoderPreferences.highQualityScaling().get()
        val scaler = if (isHighQuality) "spline36" else "bilinear"
        setSafeOptionString("scale", scaler)
        setSafeOptionString("cscale", scaler)
        setSafeOptionString("dscale", scaler)
        setSafeOptionString("dither", if (isHighQuality) "fruit" else "no")

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

        mpv?.setOptionString("idle", "yes")
        mpv?.setOptionString("ytdl", "no")
        setSafeOptionString("tls-verify", "yes")
        setSafeOptionString("tls-ca-file", "${context.filesDir.path}/${MpvConfig.MPV_DIR}/cacert.pem")

        // ANZ -->
        // Point libass at our provisioned internal fonts dir for user custom fonts.
        // sub-font-provider is left at its default (fontconfig) so embedded ASS fonts
        // and plain-text subs continue to resolve.  The old fonts.conf alias to non-existent
        // Roboto/NotoSans was already deleted by provisionSync(), so fontconfig now resolves
        // "Sans Serif" correctly via system fonts on all devices.
        val internalFontsDir = "${context.filesDir.path}/${MpvConfig.MPV_DIR}/${MpvConfig.MPV_FONTS_DIR}"
        setSafeOptionString("sub-fonts-dir", internalFontsDir)
        setSafeOptionString("osd-fonts-dir", internalFontsDir)
        // ANZ <--

        // Track selection handled reactively by ViewModel
        mpv?.setOptionString("sid", "no")
        mpv?.setOptionString("aid", "no")

        applyPlaybackStrategy()

        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        screenshotDir.mkdirs()
        mpv?.setOptionString("screenshot-directory", screenshotDir.path)

        VideoFilters.entries.forEach {
            val value = it.preference(decoderPreferences).get()
            if (value != 0 && !it.mpvProperty.startsWith("vf_")) {
                mpv?.setOptionString(it.mpvProperty, value.toString())
            }
        }

        mpv?.setOptionString("speed", playerPreferences.playerSpeed().get().toString())
        setSafeOptionString("vd-lavc-film-grain", "cpu")

        postInitOptions()
        setupSubtitlesOptions()
        setupAudioOptions()
        observeProperties()
    }

    fun observeProperties() {
        for ((name, format) in observedProps) {
            mpv?.observeProperty(name, format)
        }
    }

    fun postInitOptions() {
        when (decoderPreferences.videoDebanding().get()) {
            Debanding.None -> {}
            Debanding.CPU -> mpv?.setOptionString("vf", "gradfun=radius=12")
            Debanding.GPU -> mpv?.setOptionString("deband", "yes")
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
        "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        "video-params/aspect" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,

        "user-data/aniyomi/show_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
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
        "user-data/aniyomi/show_seek_text" to MPV.mpvFormat.MPV_FORMAT_STRING,
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
        mpv?.setOptionString(
            "secondary-sub-delay",
            (subtitlePreferences.subtitlesSecondaryDelay().get() / 1000.0).toString(),
        )
        mpv?.setOptionString("sub-font", subtitlePreferences.subtitleFont().get())
        // ANZ -->
        // sub-ass-override is intentionally NOT forced here. mpv defaults to "scale" which
        // matches what the UI's reset button sets (line 151 in SubtitleSettingsMiscellaneousCard).
        // Previously setting "no" here created a mismatch with the UI's "scale" off-state, causing
        // ASS subtitle spacing and font size to be rendered differently from Anikku's behavior.
        if (subtitlePreferences.overrideSubsASS().get()) {
            mpv?.setOptionString("sub-ass-override", "force")
            mpv?.setOptionString("sub-ass-justify", "yes")
        }
        // ANZ <--
        mpv?.setOptionString("sub-font-size", subtitlePreferences.subtitleFontSize().get().toString())
        mpv?.setOptionString("sub-bold", if (subtitlePreferences.boldSubtitles().get()) "yes" else "no")
        mpv?.setOptionString("sub-italic", if (subtitlePreferences.italicSubtitles().get()) "yes" else "no")
        mpv?.setOptionString("sub-justify", subtitlePreferences.subtitleJustification().get().value)
        mpv?.setOptionString("sub-color", subtitlePreferences.textColorSubtitles().get().toColorHexString())
        mpv?.setOptionString("sub-back-color", subtitlePreferences.backgroundColorSubtitles().get().toColorHexString())
        // ANZ -->
        mpv?.setOptionString("sub-outline-color", subtitlePreferences.borderColorSubtitles().get().toColorHexString())
        mpv?.setOptionString("sub-outline-size", subtitlePreferences.subtitleBorderSize().get().toString())
        // ANZ <--
        mpv?.setOptionString("sub-border-style", subtitlePreferences.borderStyleSubtitles().get().value)
        mpv?.setOptionString("sub-shadow-offset", subtitlePreferences.shadowOffsetSubtitles().get().toString())
        mpv?.setOptionString("sub-pos", subtitlePreferences.subtitlePos().get().toString())
        mpv?.setOptionString("sub-scale", subtitlePreferences.subtitleFontScale().get().toString())
    }
}
// ANZ <--
