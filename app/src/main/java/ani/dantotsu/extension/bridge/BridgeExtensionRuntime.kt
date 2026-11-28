package ani.dantotsu.extension.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.extension.ExtensionInstallProgress
import ani.dantotsu.extension.ExtensionRuntime
import ani.dantotsu.extension.ExtensionRuntimeException
import ani.dantotsu.extension.ExtensionRuntimeState
import ani.dantotsu.extension.ExtensionRuntimeStatus
import ani.dantotsu.extension.ExtensionRuntimeType
import ani.dantotsu.extension.ExtensionSource
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.KirokuExtensionRepository
import io.chibi.bridge.models.BackendStatus
import io.chibi.bridge.models.BridgeError
import io.chibi.bridge.models.BridgeProtocol
import io.chibi.bridge.models.ExecutionMechanism
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.models.Repo
import io.chibi.bridge.models.RuntimeState
import io.chibi.bridge.models.Source
import io.chibi.bridge.sdk.ChibiBridgeException
import io.chibi.bridge.sdk.ChibiRuntime
import io.chibi.bridge.sdk.ChibiRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ExtensionRuntime] backed by ChibiExtensionRuntimeBridge.
 *
 * This is the only place that knows about the bridge's own models: everything above it (screens,
 * parsers, source registration) works with Kiroku's [KirokuExtension] model.
 *
 * The bridge executes extension code in a separate host APK (`io.chibi.runtimehost`), so a broken
 * extension can never take Kiroku down with it.
 *
 * The bridge reports *sources*, not packages: an Aniyomi extension APK ships many sources that all
 * share the extension name, so Kiroku groups them back into one extension keyed by
 * `backend|category|name`. Everything the runtime already knows (version, icon, update flag) is
 * mapped straight through; nothing is invented.
 */
class BridgeExtensionRuntime(context: Context) : ExtensionRuntime, AutoCloseable {

    private val appContext = context.applicationContext

    private val runtime = ChibiRuntime(
        appContext,
        ChibiRuntimeConfig(
            hostPackage = BridgeProtocol.HOST_PACKAGE,
            connectTimeoutMs = 8_000,
            requestTimeoutMs = 90_000,
        ),
    )

    /** Bridge-backed sources execute extension code through this client. */
    internal val chibi: ChibiRuntime get() = runtime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectMutex = Mutex()

    private val _status = MutableStateFlow(ExtensionRuntimeStatus())
    override val status: StateFlow<ExtensionRuntimeStatus> = _status.asStateFlow()

    // The bridge installs synchronously and reports no byte progress, so nothing is emitted here.
    private val _progress = MutableSharedFlow<ExtensionInstallProgress>(extraBufferCapacity = 8)
    override val progress: SharedFlow<ExtensionInstallProgress> = _progress.asSharedFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override val runtimePackage: String = BridgeProtocol.HOST_PACKAGE

    /** Repo entries by extension key; install/update/uninstall need the runtime's own [Source]. */
    private val availableSources = mutableMapOf<String, Source>()

    /** Installed entries by extension key, kept for operations on extensions no repo lists. */
    private val installedSources = mutableMapOf<String, Source>()

    private var availableCache: List<KirokuExtension>? = null

    private var availableCacheAt = 0L

    /** Backends the runtime reported; null until it answers for the first time. */
    private var backendCache: List<BridgeBackend>? = null

    init {
        // Mirror the transport state so a killed/unbound host is never shown as connected.
        scope.launch {
            runtime.state.collect { state ->
                val mapped = state.toKirokuState() ?: return@collect
                if (_status.value.state != mapped) _status.value = _status.value.copy(state = mapped)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override suspend fun connect(force: Boolean): ExtensionRuntimeStatus = connectMutex.withLock {
        if (!force && _status.value.isConnected) return@withLock _status.value
        _status.value = _status.value.copy(state = ExtensionRuntimeState.CONNECTING, message = null)
        val status = try {
            val connection = runtime.connect()
            // initialize() loads the backends' installed extensions; without it every call fails.
            runtime.initialize()
            val host = runCatching { runtime.hostInfo() }.getOrNull()
            val reported = connection.backends.ifEmpty {
                runCatching { runtime.backends() }.getOrDefault(emptyList())
            }
            val backends = reported.mapNotNull { it.toBridgeBackend() }
                .ifEmpty { BridgeBackend.DEFAULTS }
            backendCache = backends
            ExtensionRuntimeStatus(
                state = ExtensionRuntimeState.CONNECTED,
                runtimeVersion = host?.runtimeVersion ?: connection.host?.runtimeVersion,
                runtimes = backends.map { it.runtime },
                supportsInstall = true,
                supportsUpdates = true,
                supportsRepositories = backends.any { it.runtime.supportsRepositories },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChibiBridgeException) {
            e.toKirokuStatus()
        } catch (t: Throwable) {
            ExtensionRuntimeStatus(
                state = ExtensionRuntimeState.FAILED,
                message = t.message ?: t.javaClass.simpleName,
            )
        }
        _status.value = status
        status
    }

    override fun isRuntimeInstalled(): Boolean = try {
        runtime.installedHosts().isNotEmpty()
    } catch (_: Exception) {
        false
    }

    override fun launchRuntime(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(runtimePackage) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun openRuntimeDownload(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RUNTIME_RELEASES_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun close() {
        scope.launch { runCatching { runtime.disconnect() } }
    }

    // --------------------------------------------------------------- extensions

    override suspend fun installedExtensions(): List<KirokuExtension> = guard {
        ensureConnected()
        val rows = installedRows()
        val groups = rows.groupBy { it.extensionKey() }
        installedSources.clear()
        groups.forEach { (key, group) -> installedSources[key] = group.first().source }
        groups.map { (key, group) -> group.toKirokuExtension(key, installed = true) }
            .distinctBy { it.id }
    }

    override suspend fun availableExtensions(forceRefresh: Boolean): List<KirokuExtension> = guard {
        val cached = availableCache
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - availableCacheAt < AVAILABLE_CACHE_TTL_MS
        ) {
            return@guard cached
        }
        ensureConnected()
        val rows = availableRows()
        val groups = rows.groupBy { it.extensionKey() }
        availableSources.clear()
        groups.forEach { (key, group) -> availableSources[key] = group.first().source }
        groups.map { (key, group) -> group.toKirokuExtension(key, installed = false) }
            .distinctBy { it.id }
            .also {
                availableCache = it
                availableCacheAt = System.currentTimeMillis()
            }
    }

    override suspend fun install(extensionId: String): KirokuExtension = guard {
        ensureConnected()
        val backend = backendOf(extensionId)
        runtime.install(backend.id, operationSource(extensionId))
        invalidateCaches()
        _changes.tryEmit(Unit)
        installedExtensions().firstOrNull { it.id == extensionId }
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_NOT_VISIBLE",
                message = "The runtime installed this extension but does not list it as installed.",
            )
    }

    override suspend fun update(extensionId: String): KirokuExtension = guard {
        ensureConnected()
        val backend = backendOf(extensionId)
        runtime.update(backend.id, operationSource(extensionId))
        invalidateCaches()
        _changes.tryEmit(Unit)
        installedExtensions().firstOrNull { it.id == extensionId }
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_NOT_VISIBLE",
                message = "The runtime updated this extension but does not list it as installed.",
            )
    }

    override suspend fun uninstall(extensionId: String): Boolean = guard {
        ensureConnected()
        val backend = backendOf(extensionId)
        runtime.uninstall(backend.id, operationSource(extensionId))
        invalidateCaches()
        _changes.tryEmit(Unit)
        // The bridge does not confirm removals, so verify instead of trusting the call.
        if (installedRows().any { it.extensionKey() == extensionId }) {
            throw ExtensionRuntimeException(
                code = "UNINSTALL_FAILED",
                message = "The runtime could not remove this extension.",
            )
        }
        true
    }

    override suspend fun reload(): List<KirokuExtension> = guard {
        ensureConnected()
        backends().forEach { backend -> runCatching { runtime.refresh(backend.id) } }
        invalidateCaches()
        _changes.tryEmit(Unit)
        installedExtensions()
    }

    // ------------------------------------------------------------- repositories

    override suspend fun repositories(): List<KirokuExtensionRepository> = guard {
        ensureConnected()
        backends()
            .filter { it.runtime.supportsRepositories }
            .flatMap { backend ->
                runtime.repositories(backend.id, backend.types.first())
                    .mapNotNull { repo -> repo.toKirokuRepository(backend) }
            }.distinctBy { it.id }
    }

    override suspend fun addRepository(
        url: String,
        runtimeType: ExtensionRuntimeType,
        name: String?,
    ): KirokuExtensionRepository = guard {
        ensureConnected()
        val backend = backends().firstOrNull {
            it.id == runtimeType.id && it.runtime.supportsRepositories
        } ?: throw ExtensionRuntimeException(
            code = "EXTENSION_UNSUPPORTED",
            message = "${runtimeType.displayName} repositories are not supported by the runtime.",
        )
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            throw ExtensionRuntimeException(code = "REPOSITORY_INVALID", message = "Enter a repository URL.")
        }
        // Each backend keeps a single URL list, so the item type of the call does not matter here.
        runtime.addRepository(backend.id, trimmed, backend.types.first())
        invalidateCaches()
        val added = repositories().firstOrNull { it.url == trimmed }
        added ?: throw ExtensionRuntimeException(
            code = "REPOSITORY_INVALID",
            message = "The runtime did not accept this repository URL.",
        )
    }

    override suspend fun removeRepository(repositoryId: String): Boolean = guard {
        ensureConnected()
        val (backend, url) = splitRepositoryId(repositoryId) ?: throw ExtensionRuntimeException(
            code = "REPOSITORY_INVALID",
            message = "This repository is not managed by the runtime.",
        )
        runtime.removeRepository(backend.id, url, backend.types.first())
        invalidateCaches()
        repositories().none { it.url == url }
    }

    // ----------------------------------------------------------------- internals

    private data class SourceRow(
        val backend: BridgeBackend,
        val type: ItemType,
        val source: Source,
    ) {
        /** `backend|category|name`: the runtime's extension identity, rebuilt from its sources. */
        fun extensionKey(): String = bridgeExtensionKey(backend.id, type.category(), source.name)
    }

    private suspend fun installedRows(): List<SourceRow> = backends().flatMap { backend ->
        backend.types.flatMap { type ->
            runtime.installedSources(backend.id, type).map { SourceRow(backend, type, it) }
        }
    }

    private suspend fun availableRows(): List<SourceRow> = backends().flatMap { backend ->
        backend.types.flatMap { type ->
            runtime.availableSources(backend.id, type).map { SourceRow(backend, type, it) }
        }
    }

    /**
     * Backends the runtime reports, mapped to what Kiroku can address.
     *
     * Before the runtime answers, the defaults keep the screen usable; every mutation still has to
     * resolve a backend the runtime reported, so a default can never invent one.
     */
    private suspend fun backends(): List<BridgeBackend> {
        backendCache?.let { return it }
        val reported = runCatching { runtime.backends() }.getOrDefault(emptyList())
        return reported.mapNotNull { it.toBridgeBackend() }
            .ifEmpty { BridgeBackend.DEFAULTS }
            .also { backendCache = it }
    }

    /**
     * Only anime/manga backends are kept: Kiroku executes those through the bridge, while novels
     * still run through its own extension manager, so a novel-only backend would only produce rows
     * nothing can read yet.
     */
    private fun BackendStatus.toBridgeBackend(): BridgeBackend? {
        val types = buildList {
            if (capabilities.anime) add(ItemType.ANIME)
            if (capabilities.manga) add(ItemType.MANGA)
        }
        if (types.isEmpty()) return null
        return BridgeBackend(
            id = id,
            runtime = ExtensionRuntimeType(
                id = id,
                displayName = displayName,
                categories = types.map { it.category() },
                supportsRepositories = capabilities.repositories,
                execution = execution.label(),
            ),
            types = types,
        )
    }

    private fun ExecutionMechanism.label(): String =
        name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    private fun List<SourceRow>.toKirokuExtension(key: String, installed: Boolean): KirokuExtension {
        val first = first()
        return KirokuExtension(
            id = key,
            name = first.source.name,
            versionName = first.source.version,
            versionCode = first.source.version.toVersionCode(),
            language = map { it.source.lang }.firstOrNull { it.isNotBlank() },
            isNsfw = any { it.source.isNsfw },
            iconUrl = map { it.source.iconUrl }.firstOrNull { it.isNotBlank() },
            runtime = first.backend.runtime,
            category = first.type.category(),
            isInstalled = installed,
            hasUpdate = any { it.source.hasUpdate },
            repositoryId = first.source.repo,
            sources = map { it.toExtensionSource() }.distinctBy { it.id },
        )
    }

    private fun SourceRow.toExtensionSource(): ExtensionSource = ExtensionSource(
        id = callSourceId(),
        name = source.name,
        language = source.lang.ifBlank { null },
        baseUrl = source.baseUrl.ifBlank { null },
        supportsLatest = source.supportsLatest,
    )

    /**
     * Runtime id Kiroku stores in `SAnime.url` style fields.
     *
     * Aniyomi resolves sources by `anime:<id>`/`manga:<id>`; CloudStream matches the plugin id
     * directly, so it must not be prefixed.
     */
    private fun SourceRow.callSourceId(): String =
        if (backend.id == ANIYOMI_BACKEND_ID) "${type.category().name.lowercase()}:${source.id}"
        else source.id

    private suspend fun backendOf(extensionId: String): BridgeBackend {
        val id = extensionId.substringBefore('|')
        return backends().firstOrNull { it.id == id }
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_UNSUPPORTED",
                message = "The runtime does not manage this extension.",
            )
    }

    /** The Source a mutation needs: a repo entry carries the APK/JAR URL the runtime installs. */
    private suspend fun operationSource(extensionId: String): Source {
        availableSources[extensionId]?.let { return it }
        installedSources[extensionId]?.let { return it }
        // Catalogues are cached; load them once before reporting an extension as unknown.
        runCatching { availableExtensions() }
        return availableSources[extensionId] ?: installedSources[extensionId]
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_UNKNOWN",
                message = "The runtime no longer offers this extension.",
            )
    }

    private fun Repo.toKirokuRepository(backend: BridgeBackend): KirokuExtensionRepository? {
        val repoUrl = url.trim()
        if (repoUrl.isEmpty()) return null
        return KirokuExtensionRepository(
            id = "${backend.id}|$repoUrl",
            name = name?.takeIf { it.isNotBlank() }
                ?: repoUrl.substringAfter("://").substringBefore('/'),
            url = repoUrl,
            runtime = backend.runtime,
            enabled = true,
            isBuiltIn = false,
        )
    }

    private suspend fun splitRepositoryId(repositoryId: String): Pair<BridgeBackend, String>? {
        val id = repositoryId.substringBefore('|')
        val backend = backends().firstOrNull { it.id == id && it.runtime.supportsRepositories }
            ?: return null
        val url = repositoryId.substringAfter('|', "").trim()
        return if (url.isEmpty()) null else backend to url
    }

    private fun invalidateCaches() {
        availableCache = null
        availableCacheAt = 0L
        availableSources.clear()
    }

    private suspend fun ensureConnected() {
        if (_status.value.isConnected) return
        val status = connect()
        if (!status.isConnected) {
            throw ExtensionRuntimeException(
                code = status.state.name,
                message = status.message ?: "The extension runtime is not connected.",
                retryable = status.state == ExtensionRuntimeState.DISCONNECTED ||
                    status.state == ExtensionRuntimeState.FAILED,
            )
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChibiBridgeException) {
        throw ExtensionRuntimeException(e.error.code, e.error.message, e.error.recoverable, e)
    } catch (e: ExtensionRuntimeException) {
        throw e
    } catch (t: Throwable) {
        throw ExtensionRuntimeException(null, t.message ?: t.javaClass.simpleName, false, t)
    }

    private fun ChibiBridgeException.toKirokuStatus(): ExtensionRuntimeStatus {
        val hostMissing = error.code == BridgeError.CODE_HOST_NOT_INSTALLED
        return ExtensionRuntimeStatus(
            state = if (hostMissing) ExtensionRuntimeState.NOT_INSTALLED else ExtensionRuntimeState.FAILED,
            message = error.message,
        )
    }

    private fun RuntimeState.toKirokuState(): ExtensionRuntimeState? = when (this) {
        RuntimeState.UNKNOWN -> null
        RuntimeState.NOT_INSTALLED -> ExtensionRuntimeState.NOT_INSTALLED
        RuntimeState.INSTALLING -> ExtensionRuntimeState.CONNECTING
        RuntimeState.INSTALLED -> ExtensionRuntimeState.CONNECTED
        RuntimeState.LOADING -> ExtensionRuntimeState.CONNECTING
        RuntimeState.READY -> ExtensionRuntimeState.CONNECTED
        RuntimeState.ERROR -> ExtensionRuntimeState.FAILED
    }

    private fun String.toVersionCode(): Long {
        val parts = split('.')
        fun part(index: Int): Long =
            parts.getOrNull(index)?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
        return part(0) * 10_000 + part(1) * 100 + part(2)
    }

    private data class BridgeBackend(
        val id: String,
        val runtime: ExtensionRuntimeType,
        /** Item types this backend advertises; every bridge call takes one of them. */
        val types: List<ItemType>,
    ) {
        companion object {
            /** Used before the runtime reports its catalogue; never for a mutation it did not report. */
            val DEFAULTS: List<BridgeBackend> = listOf(
                BridgeBackend(
                    id = "aniyomi",
                    runtime = ExtensionRuntimeType.ANIYOMI,
                    types = listOf(ItemType.ANIME, ItemType.MANGA),
                ),
                BridgeBackend(
                    id = "cloudstream",
                    runtime = ExtensionRuntimeType.CLOUDSTREAM,
                    types = listOf(ItemType.ANIME),
                ),
            )
        }
    }

    private companion object {
        const val AVAILABLE_CACHE_TTL_MS = 5 * 60 * 1000L

        /** Aniyomi resolves sources by `anime:<id>`/`manga:<id>`; the others use the raw source id. */
        const val ANIYOMI_BACKEND_ID = "aniyomi"

        /** Bridge release page; the runtime host is distributed separately from Kiroku. */
        const val RUNTIME_RELEASES_URL =
            "https://github.com/saizenasato/ChibiExtensionRuntimeBridge/releases/latest"
    }
}

/** `backend|category|name`: stable Kiroku-side identity of a bridge extension. */
internal fun bridgeExtensionKey(
    backendId: String,
    category: ExtensionCategory,
    name: String,
): String = "$backendId|${category.name}|${name.trim()}"

internal fun ItemType.category(): ExtensionCategory = when (this) {
    ItemType.ANIME -> ExtensionCategory.ANIME
    ItemType.MANGA -> ExtensionCategory.MANGA
    ItemType.NOVEL -> ExtensionCategory.NOVEL
}
