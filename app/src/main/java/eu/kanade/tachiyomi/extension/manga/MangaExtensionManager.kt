package eu.kanade.tachiyomi.extension.manga

import android.content.Context
import android.graphics.drawable.Drawable
import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.extension.ExtensionRuntime
import ani.dantotsu.extension.ExtensionRuntimeException
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.bridge.BridgeMangaSource
import ani.dantotsu.extension.bridge.bridgeSourceId
import ani.dantotsu.util.Logger
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.source.manga.model.MangaSourceData
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Kiroku's manga extension manager.
 *
 * Like its anime counterpart it only adapts the [ExtensionRuntime]'s extension list into Kiroku's
 * own model; the runtime performs discovery, installation and execution out of process.
 */
class MangaExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val runtime: ExtensionRuntime = Injekt.get(),
) {

    var isInitialized = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installedExtensionsFlow = MutableStateFlow(emptyList<MangaExtension.Installed>())
    val installedExtensionsFlow = _installedExtensionsFlow.asStateFlow()

    private val _availableExtensionsFlow = MutableStateFlow(emptyList<MangaExtension.Available>())
    val availableExtensionsFlow = _availableExtensionsFlow.asStateFlow()

    private val _untrustedExtensionsFlow = MutableStateFlow(emptyList<MangaExtension.Untrusted>())
    val untrustedExtensionsFlow = _untrustedExtensionsFlow.asStateFlow()

    private var availableSourcesData: Map<Long, MangaSourceData> = emptyMap()

    init {
        scope.launch {
            runtime.changes.collect { refreshInstalled() }
        }
        scope.launch {
            refreshInstalled()
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? =
        _installedExtensionsFlow.value.firstOrNull { extension -> extension.sources.any { it.id == sourceId } }?.icon

    fun getSourceData(id: Long) = availableSourcesData[id]

    suspend fun findAvailableExtensions() {
        refreshInstalled()
        refreshAvailable()
    }

    suspend fun refreshInstalled() {
        try {
            runtime.connect()
            val installed = runtime.installedExtensions()
                .filter { it.category == ExtensionCategory.MANGA }
                .mapNotNull { it.toInstalled() }
            _installedExtensionsFlow.value = installed
            isInitialized = true
            updatePendingUpdatesCount()
        } catch (e: ExtensionRuntimeException) {
            Logger.log("Manga extensions unavailable: ${e.message}")
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    private suspend fun refreshAvailable() {
        try {
            val available = runtime.availableExtensions()
                .filter { it.category == ExtensionCategory.MANGA }
                .map { it.toAvailable() }
            _availableExtensionsFlow.value = available
            availableSourcesData = available
                .flatMap { extension -> extension.sources.map { it.toSourceData() } }
                .associateBy { it.id }
        } catch (e: ExtensionRuntimeException) {
            Logger.log("Manga extension catalogue unavailable: ${e.message}")
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    suspend fun installExtension(extension: MangaExtension.Available): Boolean = changeInstalled(extension.pkgName) {
        runtime.install(extension.pkgName)
    }

    suspend fun updateExtension(extension: MangaExtension.Installed): Boolean = changeInstalled(extension.pkgName) {
        runtime.update(extension.pkgName)
    }

    suspend fun uninstallExtension(extension: MangaExtension): Boolean = changeInstalled(extension.pkgName) {
        runtime.uninstall(extension.pkgName)
    }

    private suspend fun changeInstalled(
        extensionId: String,
        operation: suspend () -> Any,
    ): Boolean = try {
        runtime.connect()
        operation()
        refreshInstalled()
        refreshAvailable()
        true
    } catch (e: ExtensionRuntimeException) {
        Logger.log("Extension operation failed for $extensionId: ${e.message}")
        false
    } catch (e: Exception) {
        Logger.log(e)
        false
    }

    private fun updatePendingUpdatesCount() {
        preferences.mangaExtensionUpdatesCount()
            .set(_installedExtensionsFlow.value.count { it.hasUpdate })
    }

    private fun KirokuExtension.toInstalled(): MangaExtension.Installed? {
        val sources = sources.map { source ->
            BridgeMangaSource(
                extensionId = id,
                extensionName = name,
                sourceId = source.id,
                name = source.name,
                lang = source.language ?: language ?: "all",
                id = bridgeSourceId(id, source.id),
                supportsLatest = source.supportsLatest,
                baseUrl = source.baseUrl,
            )
        }
        if (sources.isEmpty() && loadError == null) return null
        return MangaExtension.Installed(
            name = name,
            pkgName = id,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion.toLibVersion(),
            lang = language ?: "all",
            isNsfw = isNsfw,
            hasReadme = false,
            hasChangelog = false,
            pkgFactory = null,
            sources = sources,
            icon = null,
            hasUpdate = hasUpdate,
            isObsolete = loadError != null,
            isUnofficial = false,
        )
    }

    private fun KirokuExtension.toAvailable(): MangaExtension.Available = MangaExtension.Available(
        name = name,
        pkgName = id,
        versionName = versionName,
        versionCode = versionCode,
        libVersion = libVersion.toLibVersion(),
        lang = language ?: "all",
        isNsfw = isNsfw,
        hasReadme = false,
        hasChangelog = false,
        sources = sources.map { source ->
            AvailableMangaSources(
                id = bridgeSourceId(id, source.id),
                lang = source.language ?: language ?: "all",
                name = source.name,
                baseUrl = source.baseUrl.orEmpty(),
            )
        },
        apkName = "",
        iconUrl = iconUrl.orEmpty(),
        repository = repositoryId.orEmpty(),
    )

    private fun String?.toLibVersion(): Double =
        this?.let { raw -> Regex("""\d+(\.\d+)?""").find(raw)?.value?.toDoubleOrNull() } ?: 0.0
}
