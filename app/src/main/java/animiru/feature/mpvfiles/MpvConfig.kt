package animiru.feature.mpvfiles

import android.content.Context
import android.content.res.AssetManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.player.settings.AdvancedPlayerPreferences
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
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

    fun provisionSync(mpvDir: UniFile = getMpvDir()) {
        try {
            // Drop any stale fonts.conf to prevent broken font fallbacks and startup stutters
            mpvDir.findFile("fonts.conf")?.delete()
            copyUserFiles(mpvDir)
            copyAssets(mpvDir)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to provision mpv files" }
        }
    }

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
                    mpvDir.findFile("fonts.conf")?.delete()
                    copyUserFiles(mpvDir)
                    copyFontsDirectory()
                    copyAssets(mpvDir)
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

    private fun copyUserFiles(mpvDir: UniFile) {
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

        val buttons = runBlocking(Dispatchers.IO) {
            try {
                getCustomButtons.getAll()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load custom buttons" }
                emptyList()
            }
        }
        setupCustomButtons(buttons)

        // Copy over the bridge file
        val luaFile = scriptsDir.createFile("aniyomi.lua") ?: return
        context.assets.open("aniyomi.lua").use { inputStream ->
            luaFile.openOutputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    fun setupCustomButtons(buttons: List<CustomButton>): UniFile? {
        val scriptsDir = getMpvDir().createDirectory(MPV_SCRIPTS_DIR) ?: return null
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
        return file
    }

    suspend fun copyFontsDirectory(mpv: MPV? = null) {
        val mpvDir = getMpvDir()
        val fontsDirectory = deleteAndGet(mpvDir, MPV_FONTS_DIR)
        copyDirectoryContents(storageManager.getFontsDirectory(), fontsDirectory)
        fontsDirectory.filePath?.let {
            mpv?.setPropertyString("sub-fonts-dir", it)
            mpv?.setPropertyString("osd-fonts-dir", it)
        }
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

    private fun deleteAndGet(parent: UniFile, name: String): UniFile {
        parent.createDirectory(name)?.delete()
        return parent.createDirectory(name)!!
    }

    private fun copyDirectoryContents(sourceDir: UniFile?, destDir: UniFile) {
        sourceDir?.listFiles()?.forEach { file ->
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
