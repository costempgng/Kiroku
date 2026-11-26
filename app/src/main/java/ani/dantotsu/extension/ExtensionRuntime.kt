package ani.dantotsu.extension

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single contract between Kiroku and whatever executes extensions.
 *
 * Kiroku never loads extension code in its own process: an implementation of this interface talks
 * to a runtime (today the Kiroku Extension Runtime Bridge) and converts its answers into Kiroku's
 * own [KirokuExtension] model. Adding another runtime later means adding an implementation, not
 * touching the screens or the parsers.
 */
interface ExtensionRuntime {

    /** Connection state of the runtime, observable by any screen. */
    val status: StateFlow<ExtensionRuntimeStatus>

    /** Installation progress, keyed by extension id. */
    val progress: SharedFlow<ExtensionInstallProgress>

    /** Emits whenever installed extensions change (install, update, uninstall, reload). */
    val changes: SharedFlow<Unit>

    /** Connects (idempotently) or re-connects when [force] is set. Never throws. */
    suspend fun connect(force: Boolean = false): ExtensionRuntimeStatus

    suspend fun installedExtensions(): List<KirokuExtension>

    /** Catalogue from every enabled repository. Results are cached until [forceRefresh]. */
    suspend fun availableExtensions(forceRefresh: Boolean = false): List<KirokuExtension>

    suspend fun repositories(): List<KirokuExtensionRepository>

    suspend fun addRepository(
        url: String,
        runtime: ExtensionRuntimeType,
        name: String? = null,
    ): KirokuExtensionRepository

    suspend fun removeRepository(repositoryId: String): Boolean

    suspend fun install(extensionId: String): KirokuExtension

    suspend fun update(extensionId: String): KirokuExtension

    suspend fun uninstall(extensionId: String): Boolean

    /** Forces the runtime to rescan installed packages. */
    suspend fun reload(): List<KirokuExtension>

    /** Package name of the runtime host, used for install/launch affordances. */
    val runtimePackage: String

    fun isRuntimeInstalled(): Boolean

    /** Opens the runtime manager UI; false when the runtime is not installed. */
    fun launchRuntime(context: Context): Boolean

    /** Opens the runtime download page; false when no browser could be launched. */
    fun openRuntimeDownload(context: Context): Boolean
}

enum class ExtensionRuntimeState {
    IDLE,
    NOT_INSTALLED,
    CONNECTING,
    CONNECTED,
    INCOMPATIBLE,
    DISCONNECTED,
    FAILED,
}

data class ExtensionRuntimeStatus(
    val state: ExtensionRuntimeState = ExtensionRuntimeState.IDLE,
    val runtimeVersion: String? = null,
    val message: String? = null,
    val runtimes: List<ExtensionRuntimeType> = emptyList(),
    val supportsInstall: Boolean = false,
    val supportsUpdates: Boolean = false,
    val supportsRepositories: Boolean = false,
) {
    val isConnected: Boolean get() = state == ExtensionRuntimeState.CONNECTED

    val needsRuntimeInstall: Boolean get() = state == ExtensionRuntimeState.NOT_INSTALLED
}

data class ExtensionInstallProgress(
    val extensionId: String,
    val fraction: Float,
    val phase: String?,
)

/** Failure reported by a runtime, already translated for display. */
class ExtensionRuntimeException(
    val code: String?,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
