package ani.dantotsu.extension.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.chibi.bridge.ipc.IChibiRuntime
import io.chibi.bridge.loader.HostDiscovery
import io.chibi.bridge.loader.InstalledHost
import io.chibi.bridge.models.BackendStatus
import io.chibi.bridge.models.BridgeError
import io.chibi.bridge.models.BridgeJson
import io.chibi.bridge.models.BridgeProtocol
import io.chibi.bridge.models.BridgeRequest
import io.chibi.bridge.models.BridgeResponse
import io.chibi.bridge.models.Episode
import io.chibi.bridge.models.Filter
import io.chibi.bridge.models.HostInfo
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.models.Media
import io.chibi.bridge.models.Methods
import io.chibi.bridge.models.PageUrl
import io.chibi.bridge.models.Pages
import io.chibi.bridge.models.Repo
import io.chibi.bridge.models.RuntimeState
import io.chibi.bridge.models.RuntimeStatus
import io.chibi.bridge.models.Source
import io.chibi.bridge.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure reported by the runtime host (or by the transport to it). */
class BridgeClientException(val error: BridgeError) :
    Exception("${error.code}: ${error.message}") {

    constructor(code: String, message: String) : this(BridgeError(code, message))
}

/**
 * Kiroku's client for the runtime host's AIDL service.
 *
 * The host runs in its own process, so it can be killed at any time: every binding here is
 * written to survive that. A [ServiceConnection] may have `onServiceConnected` delivered more than
 * once (the system re-delivers it after the host process restarts), so the bind continuation is
 * resumed at most once and later deliveries just drop the stale connection instead of throwing.
 */
internal class ChibiClient(
    context: Context,
    private val hostPackage: String = BridgeProtocol.HOST_PACKAGE,
    private val requestTimeoutMs: Long = 90_000,
) {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow(RuntimeState.UNKNOWN)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val connectMutex = Mutex()

    @Volatile
    private var service: IChibiRuntime? = null

    @Volatile
    private var connection: ServiceConnection? = null

    // ------------------------------------------------------------------ discovery

    /** Runtime hosts installed on the device. */
    fun installedHosts(): List<InstalledHost> = HostDiscovery.installedHosts(appContext, listOf(hostPackage))

    // ----------------------------------------------------------------- connection

    suspend fun connect(): RuntimeStatus = connectMutex.withLock {
        service?.let { return@withLock status() }
        val host = installedHosts().firstOrNull() ?: run {
            _state.value = RuntimeState.NOT_INSTALLED
            throw BridgeClientException(
                BridgeError.CODE_HOST_NOT_INSTALLED,
                "Runtime host '$hostPackage' is not installed.",
            )
        }
        val bound = bind(host)
        val protocol = try {
            bound.protocolVersion()
        } catch (e: RemoteException) {
            dropService()
            throw hostGone(e)
        }
        if (protocol != BridgeProtocol.VERSION) {
            dropService()
            throw BridgeClientException(
                "PROTOCOL_MISMATCH",
                "Host speaks protocol $protocol, Kiroku expects ${BridgeProtocol.VERSION}. Update the runtime host.",
            )
        }
        service = bound
        _state.value = RuntimeState.INSTALLED
        status(bound)
    }

    /**
     * Binds one connection to the host.
     *
     * The continuation is resumed at most once: a repeated or late `onServiceConnected` (which the
     * system sends after the host process is recreated) is discarded, and the connection that
     * produced it is unbound instead of resuming an already-completed continuation.
     */
    private suspend fun bind(host: InstalledHost): IChibiRuntime = suspendCancellableCoroutine { continuation ->
        val intent = Intent("${host.packageName}.RUNTIME")
            .setAction(BridgeProtocol.ACTION_HOST_SERVICE)
            .setPackage(host.packageName)
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val runtime = IChibiRuntime.Stub.asInterface(binder)
                if (!continuation.isActive || runtime == null) {
                    // Stale delivery (the host restarted) or a binder-less bind: never resume twice.
                    if (!continuation.isActive) {
                        if (connection === this) connection = null
                        runCatching { appContext.unbindService(this) }
                    } else {
                        continuation.resumeWithException(
                            BridgeClientException("BIND_FAILED", "Runtime host returned no binder"),
                        )
                    }
                    return
                }
                continuation.resume(runtime)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this) return
                // The host process died; the next call rebinds through connect().
                connection = null
                service = null
                _state.value = RuntimeState.ERROR
            }
        }
        connection = newConnection
        val started = runCatching {
            appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!started) {
            connection = null
            continuation.resumeWithException(
                BridgeClientException("BIND_FAILED", "Unable to bind ${host.packageName}"),
            )
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            if (connection === newConnection) connection = null
            runCatching { appContext.unbindService(newConnection) }
        }
    }

    fun disconnect() {
        val bound = connection
        connection = null
        service = null
        if (bound != null) runCatching { appContext.unbindService(bound) }
        _state.value = RuntimeState.UNKNOWN
    }

    private suspend fun requireService(): IChibiRuntime {
        service?.let { return it }
        connect()
        return service ?: throw BridgeClientException(
            BridgeError.CODE_HOST_NOT_READY,
            "Runtime host is not connected.",
        )
    }

    private fun dropService() {
        val bound = connection
        connection = null
        service = null
        if (bound != null) runCatching { appContext.unbindService(bound) }
        _state.value = RuntimeState.ERROR
    }

    private fun hostGone(cause: Throwable?): BridgeClientException = BridgeClientException(
        BridgeError(
            code = BridgeError.CODE_HOST_NOT_READY,
            message = "The runtime host stopped responding. Reconnect to continue.",
            cause = cause?.javaClass?.simpleName,
            recoverable = true,
        ),
    )

    // ---------------------------------------------------------------- raw calls

    suspend fun call(
        method: String,
        backendId: String? = null,
        sourceId: String? = null,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = withContext(Dispatchers.IO) {
        val payload = BridgeJson.encodeToString(
            BridgeRequest.serializer(),
            BridgeRequest(method, backendId, sourceId, args),
        )
        val responseJson = try {
            withTimeout(requestTimeoutMs) { requireService().execute(payload) }
        } catch (e: TimeoutCancellationException) {
            throw BridgeClientException(BridgeError.CODE_TIMEOUT, "Request timed out: $method")
        } catch (e: RemoteException) {
            dropService()
            throw hostGone(e)
        }
        val response = BridgeJson.decodeFromString(BridgeResponse.serializer(), responseJson)
        response.error?.let { throw BridgeClientException(it) }
        response.result ?: JsonPrimitive(true)
    }

    private suspend fun <T> callTyped(
        method: String,
        backendId: String? = null,
        sourceId: String? = null,
        args: JsonObject = JsonObject(emptyMap()),
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = BridgeJson.decodeFromJsonElement(serializer, call(method, backendId, sourceId, args))

    // ------------------------------------------------------------------ lifecycle

    suspend fun hostInfo(): HostInfo =
        callTyped(Methods.HOST_INFO, serializer = HostInfo.serializer())

    suspend fun status(): RuntimeStatus = requireService().let { status(it) }

    private suspend fun status(runtime: IChibiRuntime): RuntimeStatus = withContext(Dispatchers.IO) {
        val json = try {
            runtime.status()
        } catch (e: RemoteException) {
            dropService()
            throw hostGone(e)
        }
        BridgeJson.decodeFromString(RuntimeStatus.serializer(), json).also { status ->
            _state.value = status.state
        }
    }

    suspend fun initialize(): Boolean {
        val result = call(Methods.INITIALIZE).toString().toBoolean()
        if (result) _state.value = RuntimeState.READY
        return result
    }

    // ------------------------------------------------------------------- backends

    suspend fun backends(): List<BackendStatus> =
        callTyped(Methods.BACKENDS, serializer = ListSerializer(BackendStatus.serializer()))

    suspend fun refresh(backendId: String): BackendStatus =
        callTyped(Methods.REFRESH, backendId = backendId, serializer = BackendStatus.serializer())

    suspend fun installedSources(backendId: String, type: ItemType): List<Source> = callTyped(
        Methods.INSTALLED_SOURCES,
        backendId = backendId,
        args = typeArgs(type),
        serializer = ListSerializer(Source.serializer()),
    )

    suspend fun availableSources(backendId: String, type: ItemType): List<Source> = callTyped(
        Methods.SOURCES,
        backendId = backendId,
        args = typeArgs(type),
        serializer = ListSerializer(Source.serializer()),
    )

    suspend fun install(backendId: String, source: Source) {
        call(Methods.INSTALL, backendId = backendId, args = sourceArgs(source))
    }

    suspend fun uninstall(backendId: String, source: Source) {
        call(Methods.UNINSTALL, backendId = backendId, args = sourceArgs(source))
    }

    suspend fun update(backendId: String, source: Source) {
        call(Methods.UPDATE, backendId = backendId, args = sourceArgs(source))
    }

    // ---------------------------------------------------------------- repositories

    suspend fun repositories(backendId: String, type: ItemType): List<Repo> = callTyped(
        Methods.REPOSITORIES,
        backendId = backendId,
        args = typeArgs(type),
        serializer = ListSerializer(Repo.serializer()),
    )

    suspend fun addRepository(backendId: String, url: String, type: ItemType) {
        call(
            Methods.ADD_REPOSITORY,
            backendId = backendId,
            args = buildJsonObject {
                put("url", url)
                put("type", type.name.lowercase())
            },
        )
    }

    suspend fun removeRepository(backendId: String, url: String, type: ItemType) {
        call(
            Methods.REMOVE_REPOSITORY,
            backendId = backendId,
            args = buildJsonObject {
                put("url", url)
                put("type", type.name.lowercase())
            },
        )
    }

    // -------------------------------------------------------------------- sources

    suspend fun popular(backendId: String, sourceId: String, page: Int = 1): Pages = callTyped(
        Methods.POPULAR,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject { put("page", page) },
        serializer = Pages.serializer(),
    )

    suspend fun latest(backendId: String, sourceId: String, page: Int = 1): Pages = callTyped(
        Methods.LATEST,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject { put("page", page) },
        serializer = Pages.serializer(),
    )

    suspend fun search(backendId: String, sourceId: String, query: String, page: Int = 1): Pages = callTyped(
        Methods.SEARCH,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject {
            put("query", query)
            put("page", page)
            put(
                "filters",
                BridgeJson.encodeToString(ListSerializer(Filter.serializer()), emptyList()),
            )
        },
        serializer = Pages.serializer(),
    )

    suspend fun detail(backendId: String, sourceId: String, media: Media): Media = callTyped(
        Methods.DETAIL,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject { put("media", BridgeJson.encodeToString(Media.serializer(), media)) },
        serializer = Media.serializer(),
    )

    suspend fun pageList(backendId: String, sourceId: String, episode: Episode): List<PageUrl> = callTyped(
        Methods.PAGES,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject { put("episode", BridgeJson.encodeToString(Episode.serializer(), episode)) },
        serializer = ListSerializer(PageUrl.serializer()),
    )

    suspend fun videoList(backendId: String, sourceId: String, episode: Episode): List<Video> = callTyped(
        Methods.VIDEOS,
        backendId = backendId,
        sourceId = sourceId,
        args = buildJsonObject { put("episode", BridgeJson.encodeToString(Episode.serializer(), episode)) },
        serializer = ListSerializer(Video.serializer()),
    )

    private fun typeArgs(type: ItemType): JsonObject =
        buildJsonObject { put("type", type.name.lowercase()) }

    private fun sourceArgs(source: Source): JsonObject =
        buildJsonObject { put("source", BridgeJson.encodeToString(Source.serializer(), source)) }
}
