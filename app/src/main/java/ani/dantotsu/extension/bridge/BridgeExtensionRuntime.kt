package ani.dantotsu.extension.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.dantotsu.extension.ExtensionInstallProgress
import ani.dantotsu.extension.ExtensionRuntime
import ani.dantotsu.extension.ExtensionRuntimeException
import ani.dantotsu.extension.ExtensionRuntimeState
import ani.dantotsu.extension.ExtensionRuntimeStatus
import ani.dantotsu.extension.ExtensionRuntimeType
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.KirokuExtensionRepository
import io.kiroku.runtime.bridge.BridgeConfig
import io.kiroku.runtime.bridge.api.BridgeEvent
import io.kiroku.runtime.bridge.client.KirokuRuntimeBridge
import io.kiroku.runtime.bridge.errors.ExtensionBridgeException
import io.kiroku.runtime.ipc.BackendType
import io.kiroku.runtime.ipc.Extension
import io.kiroku.runtime.ipc.ExtensionRepository
import io.kiroku.runtime.ipc.RepositoryExtension
import io.kiroku.runtime.ipc.RuntimeInfo
import io.kiroku.runtime.ipc.RuntimeProtocol
import io.kiroku.runtime.ipc.RuntimeState
import io.kiroku.runtime.ipc.RuntimeStatus
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
 * [ExtensionRuntime] backed by the Kiroku Extension Runtime Bridge.
 *
 * This is the only place that knows about the bridge's IPC models: everything above it (screens,
 * parsers, source registration) works with Kiroku's own [KirokuExtension] model.
 *
 * The runtime lives in its own APK (`io.kiroku.runtime.host`) and executes extension code out of
 * process, so a broken extension can never take Kiroku down with it.
 */
class BridgeExtensionRuntime(context: Context) : ExtensionRuntime, AutoCloseable {

    private val appContext = context.applicationContext

    private val bridge = KirokuRuntimeBridge(
        appContext,
        BridgeConfig(
            bindTimeoutMs = 5_000,
            controlTimeoutMs = 20_000,
            operationTimeoutMs = 90_000,
            autoReconnect = true,
        ),
    )

    /** Bridge-backed sources execute extension code through this client. */
    internal val bridgeClient: KirokuRuntimeBridge get() = bridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectMutex = Mutex()

    private val _status = MutableStateFlow(ExtensionRuntimeStatus())
    override val status: StateFlow<ExtensionRuntimeStatus> = _status.asStateFlow()

    private val _progress = MutableSharedFlow<ExtensionInstallProgress>(extraBufferCapacity = 32)
    override val progress: SharedFlow<ExtensionInstallProgress> = _progress.asSharedFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Volatile
    private var runtimeInfo: RuntimeInfo? = null

    @Volatile
    private var availableCache: List<KirokuExtension>? = null

    @Volatile
    private var availableCacheAt: Long = 0L

    override val runtimePackage: String = RuntimeProtocol.RUNTIME_PACKAGE

    init {
        // The SDK reports reconnects and fatal states; mirror them so the UI stays truthful.
        scope.launch {
            bridge.status.collect { _status.value = it.toKirokuStatus() }
        }
        scope.launch {
            bridge.events.collect { event ->
                when (event) {
                    is BridgeEvent.InstallProgress -> _progress.tryEmit(
                        ExtensionInstallProgress(
                            extensionId = event.extensionId,
                            fraction = if (event.totalBytes <= 0L) 0f
                            else (event.bytesDownloaded.toFloat() / event.totalBytes).coerceIn(0f, 1f),
                            phase = event.phase,
                        ),
                    )

                    is BridgeEvent.Installed,
                    is BridgeEvent.Uninstalled,
                    is BridgeEvent.ExtensionLoaded,
                    is BridgeEvent.ExtensionFailed,
                    -> {
                        invalidateCaches()
                        _changes.tryEmit(Unit)
                    }

                    else -> Unit
                }
            }
        }
    }

    override suspend fun connect(force: Boolean): ExtensionRuntimeStatus = connectMutex.withLock {
        if (!force && _status.value.isConnected) return@withLock _status.value
        val result = try {
            bridge.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RuntimeStatus(
                state = RuntimeState.FAILED,
                message = t.message ?: t.javaClass.simpleName,
            )
        }
        if (result.info != null) runtimeInfo = result.info
        result.toKirokuStatus().also { _status.value = it }
    }

    override suspend fun installedExtensions(): List<KirokuExtension> = guard {
        ensureConnected()
        bridge.getExtensions()
            .mapNotNull { it.toKirokuExtension(installed = true, repositoryId = it.repositoryId) }
            .distinctBy { it.id }
    }

    override suspend fun availableExtensions(forceRefresh: Boolean): List<KirokuExtension> = guard {
        val cached = availableCache
        val fresh = !forceRefresh && cached != null &&
            System.currentTimeMillis() - availableCacheAt < AVAILABLE_CACHE_TTL_MS
        if (fresh) return@guard cached.orEmpty()

        ensureConnected()
        val installed = bridge.getExtensions().associateBy { it.id }
        val list = bridge.getAvailableExtensions()
            .mapNotNull { remote ->
                remote.toKirokuExtension(installed[remote.id])
            }
            .distinctBy { it.id }
        availableCache = list
        availableCacheAt = System.currentTimeMillis()
        list
    }

    override suspend fun repositories(): List<KirokuExtensionRepository> = guard {
        ensureConnected()
        bridge.getRepositories().mapNotNull { it.toKirokuRepository() }
    }

    override suspend fun addRepository(
        url: String,
        runtime: ExtensionRuntimeType,
        name: String?,
    ): KirokuExtensionRepository = guard {
        ensureConnected()
        val backend = runtime.toBackend() ?: throw ExtensionRuntimeException(
            code = "EXTENSION_UNSUPPORTED",
            message = "${runtime.displayName} repositories are not supported by the runtime.",
        )
        val repository = bridge.addRepository(url, backend, name)
        invalidateCaches()
        repository.toKirokuRepository()
            ?: throw ExtensionRuntimeException(
                code = "REPOSITORY_INVALID",
                message = "The runtime returned an unreadable repository.",
            )
    }

    override suspend fun removeRepository(repositoryId: String): Boolean = guard {
        ensureConnected()
        val removed = bridge.removeRepository(repositoryId)
        if (removed) invalidateCaches()
        removed
    }

    override suspend fun install(extensionId: String): KirokuExtension = guard {
        ensureConnected()
        val extension = bridge.installExtension(extensionId)
        invalidateCaches()
        _changes.tryEmit(Unit)
        extension.toKirokuExtension(installed = true, repositoryId = extension.repositoryId)
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_UNSUPPORTED",
                message = "The runtime installed an extension Kiroku cannot display.",
            )
    }

    override suspend fun update(extensionId: String): KirokuExtension = guard {
        ensureConnected()
        val extension = bridge.updateExtension(extensionId)
        invalidateCaches()
        _changes.tryEmit(Unit)
        extension.toKirokuExtension(installed = true, repositoryId = extension.repositoryId)
            ?: throw ExtensionRuntimeException(
                code = "EXTENSION_UNSUPPORTED",
                message = "The runtime updated an extension Kiroku cannot display.",
            )
    }

    override suspend fun uninstall(extensionId: String): Boolean = guard {
        ensureConnected()
        val removed = bridge.uninstallExtension(extensionId)
        invalidateCaches()
        _changes.tryEmit(Unit)
        removed
    }

    override suspend fun reload(): List<KirokuExtension> = guard {
        ensureConnected()
        invalidateCaches()
        _changes.tryEmit(Unit)
        bridge.reloadExtensions()
            .mapNotNull { it.toKirokuExtension(installed = true, repositoryId = it.repositoryId) }
            .distinctBy { it.id }
    }

    override fun isRuntimeInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(runtimePackage, 0)
        true
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
        scope.launch { runCatching { bridge.close() } }
        scope.launch { runCatching { bridge.disconnect() } }
    }

    /** The SDK talks to a bound runtime; connecting lazily keeps callers free of that detail. */
    private suspend fun ensureConnected() {
        if (_status.value.isConnected) return
        val status = connect()
        if (!status.isConnected) {
            throw ExtensionRuntimeException(
                code = status.state.name,
                message = status.message ?: "The extension runtime is not connected.",
                retryable = status.state == ExtensionRuntimeState.DISCONNECTED,
            )
        }
    }

    private fun invalidateCaches() {
        availableCache = null
        availableCacheAt = 0L
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ExtensionBridgeException) {
        throw ExtensionRuntimeException(e.code.name, e.message, e.retryable, e)
    } catch (e: ExtensionRuntimeException) {
        throw e
    } catch (t: Throwable) {
        throw ExtensionRuntimeException(null, t.message ?: t.javaClass.simpleName, false, t)
    }

    private fun RuntimeStatus.toKirokuStatus(): ExtensionRuntimeStatus {
        val info = info ?: runtimeInfo
        val capabilities = info?.capabilities
        return ExtensionRuntimeStatus(
            state = when (state) {
                RuntimeState.IDLE -> ExtensionRuntimeState.IDLE
                RuntimeState.NOT_INSTALLED -> ExtensionRuntimeState.NOT_INSTALLED
                RuntimeState.INCOMPATIBLE -> ExtensionRuntimeState.INCOMPATIBLE
                RuntimeState.CONNECTING -> ExtensionRuntimeState.CONNECTING
                RuntimeState.CONNECTED -> ExtensionRuntimeState.CONNECTED
                RuntimeState.DISCONNECTED -> ExtensionRuntimeState.DISCONNECTED
                RuntimeState.FAILED -> ExtensionRuntimeState.FAILED
            },
            runtimeVersion = info?.runtimeVersion,
            message = message ?: error?.message,
            runtimes = capabilities?.backends.orEmpty().mapNotNull { ExtensionRuntimeType.fromWire(it.name) },
            supportsInstall = capabilities?.extensionInstallation == true,
            supportsUpdates = capabilities?.extensionUpdates == true,
            supportsRepositories = capabilities?.repositoryManagement == true,
        )
    }

    private fun ExtensionRepository.toKirokuRepository(): KirokuExtensionRepository? {
        val runtime = ExtensionRuntimeType.fromWire(backend.name) ?: return null
        return KirokuExtensionRepository(
            id = id,
            name = name,
            url = url,
            runtime = runtime,
            enabled = enabled,
            isBuiltIn = isBuiltIn,
            lastRefreshedAt = lastRefreshedAt,
            lastError = lastError?.message,
        )
    }

    private fun RepositoryExtension.toKirokuExtension(installed: Extension?): KirokuExtension? {
        val runtime = ExtensionRuntimeType.fromWire(backend.name) ?: return null
        return KirokuExtension(
            id = id,
            name = name,
            versionName = versionName,
            versionCode = versionCode,
            language = language ?: installed?.metadata?.language,
            isNsfw = isNsfw,
            iconUrl = iconUrl ?: installed?.metadata?.iconUrl,
            runtime = runtime,
            category = installed?.categoryFor()
                ?: ani.dantotsu.extension.ExtensionCategory.fromPackageId(id, runtime),
            isInstalled = installed != null,
            hasUpdate = installed != null && installed.metadata.versionCode < versionCode,
            repositoryId = repositoryId,
            sources = installed?.sourcesFor().orEmpty(),
            description = installed?.metadata?.description,
            libVersion = libVersion ?: installed?.metadata?.libVersion,
            loadError = installed?.loadError?.message,
        )
    }

    private fun Extension.toKirokuExtension(installed: Boolean, repositoryId: String?): KirokuExtension? {
        val runtime = ExtensionRuntimeType.fromWire(backend.name) ?: return null
        return KirokuExtension(
            id = id,
            name = metadata.name,
            versionName = metadata.versionName,
            versionCode = metadata.versionCode,
            language = metadata.language,
            isNsfw = metadata.isNsfw,
            iconUrl = metadata.iconUrl,
            runtime = runtime,
            category = categoryFor(),
            isInstalled = installed,
            hasUpdate = isUpdateAvailable,
            repositoryId = this.repositoryId ?: repositoryId,
            sources = sourcesFor(),
            description = metadata.description,
            libVersion = metadata.libVersion,
            loadError = loadError?.message,
        )
    }

    private fun Extension.categoryFor() =
        if (capabilities.manga) ani.dantotsu.extension.ExtensionCategory.MANGA
        else ani.dantotsu.extension.ExtensionCategory.fromPackageId(id, ExtensionRuntimeType.fromWire(backend.name) ?: ExtensionRuntimeType.ANIYOMI)

    private fun Extension.sourcesFor() = sources.map { source ->
        ani.dantotsu.extension.ExtensionSource(
            id = source.id,
            name = source.name,
            language = source.language,
            baseUrl = source.baseUrl,
            supportsLatest = source.supportsLatest,
        )
    }

    private fun ExtensionRuntimeType.toBackend(): BackendType? = when (this) {
        ExtensionRuntimeType.ANIYOMI -> BackendType.ANIYOMI
        ExtensionRuntimeType.CLOUDSTREAM -> BackendType.CLOUDSTREAM
        // Novels are executed in process by Kiroku, so the runtime has no backend for them.
        ExtensionRuntimeType.NOVEL -> null
    }

    private companion object {
        const val AVAILABLE_CACHE_TTL_MS = 5 * 60 * 1000L

        /** Bridge release page; the runtime is distributed separately from Kiroku. */
        const val RUNTIME_RELEASES_URL =
            "https://github.com/rho0908A/KirokuExtensionRuntimeBridge/releases/latest"
    }
}
