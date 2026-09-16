package eu.kanade.tachiyomi.ui.player.domain

import androidx.core.os.LocaleListCompat
import eu.kanade.tachiyomi.ui.player.VideoTrack
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.MissingResourceException

// ANZ -->
private fun String.parseCommaSeparatedList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

class TrackSelect(
    private val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val audioPreferences: AudioPreferences = Injekt.get(),
) {
    fun getPreferredTrackIndex(tracks: List<VideoTrack>, subtitle: Boolean = true): VideoTrack? {
        val prefLangs = if (subtitle) {
            subtitlePreferences.preferredSubLanguages().get()
        } else {
            audioPreferences.preferredAudioLanguages().get()
        }.parseCommaSeparatedList()

        val whitelist = if (subtitle) {
            subtitlePreferences.subtitleWhitelist().get()
        } else {
            ""
        }.parseCommaSeparatedList()

        val blacklist = if (subtitle) {
            subtitlePreferences.subtitleBlacklist().get()
        } else {
            ""
        }.parseCommaSeparatedList()

        val locales = prefLangs.map { Locale(it) }.ifEmpty {
            listOf(LocaleListCompat.getDefault()[0]!!)
        }

        val chosenLocale = locales.firstOrNull { locale ->
            tracks.any { t -> containsLang(t, locale) }
        }

        val filtered = tracks.asSequence()
            .filterNot { track ->
                blacklist.any { track.title.contains(it, true) }
            }
            .filter { track ->
                chosenLocale?.let { containsLang(track, it) } ?: true
            }

        whitelist.forEach { w ->
            filtered.firstOrNull { track ->
                track.title.contains(w, true)
            }?.let { return it }
        }

        return filtered.firstOrNull()
    }

    private fun containsLang(track: VideoTrack, locale: Locale): Boolean {
        try {
            val localName = locale.getDisplayName(locale)
            val englishName = locale.getDisplayName(Locale.ENGLISH).substringBefore(" (")
            val langRegex = Regex("""\b${locale.isO3Language}|\b${locale.language}\b""", RegexOption.IGNORE_CASE)
            val trackTitle = track.title

            return trackTitle.contains(localName, true) ||
                trackTitle.contains(englishName, true) ||
                (track.lang?.let { langRegex.find(it) != null } ?: false)
        } catch (_: MissingResourceException) {
            return false
        }
    }
}
// ANZ <--
