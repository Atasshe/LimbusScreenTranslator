package com.kimght.limbusscreentranslator.data.repository

import android.content.Context
import com.kimght.limbusscreentranslator.data.datastore.SettingsRepository
import com.kimght.limbusscreentranslator.data.install.ZipUtils
import com.kimght.limbusscreentranslator.data.install.workDirName
import com.kimght.limbusscreentranslator.data.network.Downloader
import com.kimght.limbusscreentranslator.domain.model.InstalledPack
import com.kimght.limbusscreentranslator.domain.model.PackFormat
import com.kimght.limbusscreentranslator.domain.screen.ScreenTextIndex
import com.kimght.limbusscreentranslator.domain.screen.ScreenTextPacks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject

internal class ScreenPackUnavailable : IOException()
internal class ScreenPackNeedsUpdate : IOException()

internal class ScreenTranslationRepository @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    private val localizations: LocalizationRepository,
    private val sources: SourceRepository,
    private val downloader: Downloader,
) {
    private val cache = File(context.cacheDir, "screen-localizations")

    val activePack = combine(
        settings.settings.map { it.activeLocalizationId }.distinctUntilChanged(),
        localizations.installedPacks,
    ) { key, packs -> packs.firstOrNull { it.key == key } }
        .distinctUntilChanged()

    suspend fun load(pack: InstalledPack): ScreenTextIndex = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        cache.mkdirs()

        // Story mode stores dialogue in Room. Screen mode also needs the archive
        // because it contains the menu, item and skill dictionaries.
        val target = File(cache, "pack-" + workDirName(pack.key, pack.version) + ".zip")
        if (!target.exists()) {
            val source = sources.sources.first().firstOrNull { it.name == pack.sourceName }
                ?: throw ScreenPackUnavailable()
            val localization = localizations.fetchCatalog(source.url).localizations
                .firstOrNull { it.id == pack.id } ?: throw ScreenPackUnavailable()
            if (localization.version != pack.version) throw ScreenPackNeedsUpdate()
            downloadArchive(localization.downloadUrl, target, localization.sizeBytes)
        }

        val original = File(cache, "english.zip")
        if (!original.exists() || System.currentTimeMillis() - original.lastModified() > ENGLISH_MAX_AGE) {
            try {
                downloadArchive(ENGLISH_ARCHIVE, original)
            } catch (e: IOException) {
                context.ensureActive()
                if (!original.exists()) throw e
            }
        }

        val pairs = ZipFile(original).use { english ->
            ZipFile(target).use { translated ->
                val englishRoot = ZipUtils.locateLanguageDir(english, PackFormat.AUTO)
                    ?: throw ScreenPackUnavailable()
                val translatedRoot = ZipUtils.locateLanguageDir(translated, PackFormat.AUTO)
                    ?: throw ScreenPackUnavailable()
                ScreenTextPacks.read(english, englishRoot, translated, translatedRoot) {
                    context.ensureActive()
                }
            }
        }
        val index = ScreenTextIndex(pairs)
        if (index.isEmpty) throw ScreenPackUnavailable()
        cache.listFiles()?.filter { it != target && it.name.startsWith("pack-") }?.forEach(File::delete)
        index
    }

    private suspend fun downloadArchive(url: String, destination: File, expectedSize: Long = 0) {
        val pending = File.createTempFile("download-", ".partial", cache)
        try {
            val size = downloader.download(url, pending) { bytes ->
                if (bytes > MAX_ARCHIVE_BYTES) throw IOException("Localization archive is too large")
            }
            if (expectedSize > 0 && size != expectedSize) throw IOException("Incomplete localization archive")
            ZipFile(pending).use {
                if (ZipUtils.locateLanguageDir(it, PackFormat.AUTO) == null) throw ScreenPackUnavailable()
            }
            if (!pending.renameTo(destination)) throw IOException("Cannot cache localization archive")
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val ENGLISH_ARCHIVE = "https://codeload.github.com/x1bViolet/Limbus-Localization-Files/zip/refs/heads/English"
        const val ENGLISH_MAX_AGE = 7 * 24 * 60 * 60 * 1000L
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024L
    }
}
