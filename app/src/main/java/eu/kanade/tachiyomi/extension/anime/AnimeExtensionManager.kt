package eu.kanade.tachiyomi.extension.anime

import android.content.Context
import android.graphics.drawable.Drawable
import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.extension.ExtensionRuntime
import ani.dantotsu.extension.ExtensionRuntimeException
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.bridge.BridgeAnimeSource
import ani.dantotsu.extension.bridge.bridgeSourceId
import ani.dantotsu.util.Logger
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.source.anime.model.AnimeSourceData
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Kiroku's anime extension manager.
 *
 * Extensions are no longer loaded or installed from inside Kiroku: the [ExtensionRuntime] (the
 * ChibiExtensionRuntimeBridge) owns discovery, installation and execution. This manager keeps
 * Kiroku's own extension model in sync with the runtime so the rest of the app (source
 * registration, parsers, watch screen) keeps working unchanged.
 */
class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val runtime: ExtensionRuntime = Injekt.get(),
) {

    var isInitialized = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installedExtensionsFlow = MutableStateFlow(emptyList<AnimeExtension.Installed>())
    val installedExtensionsFlow = _installedExtensionsFlow.asStateFlow()

    private val _availableExtensionsFlow = MutableStateFlow(emptyList<AnimeExtension.Available>())
    val availableExtensionsFlow = _availableExtensionsFlow.asStateFlow()

    /** The runtime validates packages before loading them, so nothing is ever left untrusted. */
    private val _untrustedExtensionsFlow = MutableStateFlow(emptyList<AnimeExtension.Untrusted>())
    val untrustedExtensionsFlow = _untrustedExtensionsFlow.asStateFlow()

    private var availableSourcesData: Map<Long, AnimeSourceData> = emptyMap()

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

    /**
     * Connects to the runtime and refreshes the installed/available extension lists.
     * Kept as a suspend entry point because the application and screens call it directly.
     */
    suspend fun findAvailableExtensions() {
        refreshInstalled()
        refreshAvailable()
    }

    suspend fun refreshInstalled() {
        try {
            runtime.connect()
            val installed = runtime.installedExtensions()
                .filter { it.category == ExtensionCategory.ANIME }
                .mapNotNull { it.toInstalled() }
            _installedExtensionsFlow.value = installed
            isInitialized = true
            updatePendingUpdatesCount()
        } catch (e: ExtensionRuntimeException) {
            Logger.log("Anime extensions unavailable: ${e.message}")
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    private suspend fun refreshAvailable() {
        try {
            val available = runtime.availableExtensions()
                .filter { it.category == ExtensionCategory.ANIME }
                .map { it.toAvailable() }
            _availableExtensionsFlow.value = available
            availableSourcesData = available
                .flatMap { extension -> extension.sources.map { it.toAnimeSourceData() } }
                .associateBy { it.id }
        } catch (e: ExtensionRuntimeException) {
            Logger.log("Anime extension catalogue unavailable: ${e.message}")
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    suspend fun installExtension(extension: AnimeExtension.Available): Boolean = changeInstalled(extension.pkgName) {
        runtime.install(extension.pkgName)
    }

    suspend fun updateExtension(extension: AnimeExtension.Installed): Boolean = changeInstalled(extension.pkgName) {
        runtime.update(extension.pkgName)
    }

    suspend fun uninstallExtension(extension: AnimeExtension): Boolean = changeInstalled(extension.pkgName) {
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
        preferences.animeExtensionUpdatesCount()
            .set(_installedExtensionsFlow.value.count { it.hasUpdate })
    }

    private fun KirokuExtension.toInstalled(): AnimeExtension.Installed? {
        val sources = sources.map { source ->
            BridgeAnimeSource(
                extensionId = id,
                extensionName = name,
                sourceId = source.id,
                name = source.name,
                lang = source.language ?: language ?: "all",
                id = bridgeSourceId(id, source.id),
                supportsLatest = source.supportsLatest,
            )
        }
        // An extension the runtime could not load has no source to execute.
        if (sources.isEmpty() && loadError == null) return null
        return AnimeExtension.Installed(
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

    private fun KirokuExtension.toAvailable(): AnimeExtension.Available = AnimeExtension.Available(
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
            AvailableAnimeSources(
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
