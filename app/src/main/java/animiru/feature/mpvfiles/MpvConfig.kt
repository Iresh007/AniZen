package animiru.feature.mpvfiles

import android.content.Context
import android.content.res.AssetManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ANZ -->
class MpvConfig(
    private val context: Context,
    private val storageManager: StorageManager,
    private val advancedPlayerPreferences: AdvancedPlayerPreferences,
    private val getCustomButtons: GetCustomButtons,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                logcat(LogPriority.ERROR, throwable) { "Uncaught failure while copying mpv files" }
            },
    )

    /** Number of live [eu.kanade.tachiyomi.ui.player.PlayerActivity] instances. */
    private val playerSessions = AtomicInteger(0)
    private val copyPending = AtomicBoolean(false)

    private var copyJob: Job? = null

    fun copyFiles() {
        if (playerSessions.get() > 0 || copyJob?.isActive == true) {
            copyPending.set(true)
            return
        }

        copyJob = scope.launchIO {
            do {
                copyPending.set(false)
                try {
                    val mpvDir = getMpvDir()
                    copyUserFiles(mpvDir)
                    copyFontsDirectory(mpvDir)
                    copyAssets(mpvDir)
                    writeFontsConf(context, mpvDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to copy mpv files" }
                }
            } while (copyPending.get() && playerSessions.get() == 0)
        }
    }

    /**
     * Suspends until any in-flight copy has finished, so mpv never initializes against a
     * directory tree that is still being deleted and rewritten.
     */
    suspend fun awaitCopy() {
        copyJob?.join()
    }

    fun onPlayerCreated() {
        playerSessions.incrementAndGet()
    }

    fun onPlayerDestroyed() {
        val remaining = playerSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0 && copyPending.get()) {
            copyFiles()
        }
    }

    private fun getMpvDir(): UniFile {
        return UniFile.fromFile(context.filesDir)!!.createDirectory(MPV_DIR)!!
    }

    private suspend fun copyUserFiles(mpvDir: UniFile) {
        // First, delete all present scripts
        val scriptsDir = deleteAndGet(mpvDir, MPV_SCRIPTS_DIR)
        val scriptOptsDir = deleteAndGet(mpvDir, MPV_SCRIPTS_OPTS_DIR)
        val shadersDir = deleteAndGet(mpvDir, MPV_SHADERS_DIR)

        // Then, copy the user files from the Aniyomi directory
        if (advancedPlayerPreferences.mpvUserFiles().get()) {
            copyDirectoryContents(storageManager.getScriptsDirectory(), scriptsDir)
            copyDirectoryContents(storageManager.getScriptOptsDirectory(), scriptOptsDir)
            copyDirectoryContents(storageManager.getShadersDirectory(), shadersDir)
        }

        val buttons = getCustomButtons.getAll()
        setupCustomButtons(buttons)

        // Copy over the bridge file
        val luaFile = scriptsDir.createFile("aniyomi.lua") ?: return
        context.assets.open("aniyomi.lua").use { inputStream ->
            luaFile.openOutputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    fun setupCustomButtons(buttons: List<CustomButton>) {
        val scriptsDir = getMpvDir().createDirectory(MPV_SCRIPTS_DIR)!!
        val primaryButtonId = buttons.firstOrNull { it.isFavorite }?.id ?: 0L

        val customButtonsContent = buildString {
            appendLine(
                """
                    local lua_modules = mp.find_config_file('scripts')
                    if lua_modules then
                        package.path = package.path .. ';' .. lua_modules .. '/?.lua;' .. lua_modules .. '/?/init.lua;' .. '${scriptsDir.filePath}' .. '/?.lua'
                    end
                    local aniyomi = require 'aniyomi'
                """.trimIndent(),
            )

            buttons.forEach { button ->
                appendLine(
                    """
                        ${button.getButtonOnStartup(primaryButtonId)}
                        function button${button.id}()
                            ${button.getButtonContent(primaryButtonId)}
                        end
                        mp.register_script_message('call_button_${button.id}', button${button.id})
                        function button${button.id}long()
                            ${button.getButtonLongPressContent(primaryButtonId)}
                        end
                        mp.register_script_message('call_button_${button.id}_long', button${button.id}long)
                    """.trimIndent(),
                )
            }
        }

        val file = scriptsDir.createFile("custombuttons.lua")
        file?.openOutputStream()?.bufferedWriter()?.use {
            it.write(customButtonsContent)
        }
    }

    private suspend fun copyFontsDirectory(mpvDir: UniFile) {
        val fontsDirectory = deleteAndGet(mpvDir, MPV_FONTS_DIR)
        copyDirectoryContents(storageManager.getFontsDirectory(), fontsDirectory)
    }

    private fun copyAssets(mpvDir: UniFile) {
        val assetManager = context.assets
        val files = arrayOf("cacert.pem")
        for (filename in files) {
            var ins: InputStream? = null
            var out: OutputStream? = null
            try {
                ins = assetManager.open(filename, AssetManager.ACCESS_STREAMING)
                val outFile = mpvDir.createFile(filename)!!
                if (outFile.length() == ins.available().toLong()) {
                    logcat(LogPriority.VERBOSE) { "Skipping copy of asset file (exists same size): $filename" }
                    continue
                }
                out = outFile.openOutputStream()
                ins.copyTo(out)
                logcat(LogPriority.WARN) { "Copied asset file: $filename" }
            } catch (e: IOException) {
                logcat(LogPriority.ERROR, e) { "Failed to copy asset file: $filename" }
            } finally {
                ins?.close()
                out?.close()
            }
        }
    }

    private fun writeFontsConf(context: Context, mpvDir: UniFile) {
        val parts = listOfNotNull(
            "<fontconfig>",
            "<dir>/system/fonts/</dir>",
            "<dir>/product/fonts/</dir>",
            mpvDir.createDirectory(MPV_FONTS_DIR)?.filePath?.let { filePath -> "<dir>$filePath</dir>" },
            "<cachedir>${context.cacheDir.path}</cachedir>",
            "<alias><family>serif</family>",
            "<prefer><family>Noto Serif</family></prefer>",
            "</alias>",
            "<alias><family>Sans Serif</family>",
            "<prefer>",
            "<family>Roboto</family>",
            "<family>Noto Sans</family>",
            "</prefer>",
            "</alias>",
            "<alias><family>monospace</family>",
            "<prefer><family>Droid Sans Mono</family></prefer>",
            "</alias>",
            "</fontconfig>",
        ).toMutableList()
        try {
            val file = mpvDir.createFile("fonts.conf")
            file?.openOutputStream()?.bufferedWriter()?.use {
                it.write(parts.joinToString("\n"))
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to write fonts.conf" }
        }
    }

    private fun deleteAndGet(parent: UniFile, name: String): UniFile {
        parent.createDirectory(name)?.delete()
        return parent.createDirectory(name)!!
    }

    private suspend fun copyDirectoryContents(sourceDir: UniFile?, destDir: UniFile) {
        sourceDir?.listFiles()?.forEach { file ->
            if (!currentCoroutineContext().isActive) {
                throw CancellationException()
            }

            val outFile = destDir.createFile(file.name) ?: return@forEach
            file.openInputStream().use { input ->
                outFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    companion object {
        const val MPV_DIR = "mpv"
        const val MPV_FONTS_DIR = "fonts"
        const val MPV_SCRIPTS_DIR = "scripts"
        const val MPV_SCRIPTS_OPTS_DIR = "script-opts"
        const val MPV_SHADERS_DIR = "shaders"
    }
}
// ANZ <--
