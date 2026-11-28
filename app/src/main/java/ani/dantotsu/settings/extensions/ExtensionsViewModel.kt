package ani.dantotsu.settings.extensions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.extension.ExtensionRuntime
import ani.dantotsu.extension.ExtensionRuntimeException
import ani.dantotsu.extension.ExtensionRuntimeState
import ani.dantotsu.extension.ExtensionRuntimeStatus
import ani.dantotsu.extension.ExtensionRuntimeType
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.KirokuExtensionRepository
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelExtensionManager
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.Observable
import rx.Subscriber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class ExtensionsTab { INSTALLED, AVAILABLE }

data class ExtensionsUiState(
    val category: ExtensionCategory = ExtensionCategory.ANIME,
    val tab: ExtensionsTab = ExtensionsTab.INSTALLED,
    val query: String = "",
    val languageFilter: String? = null,
    val runtimeFilter: ExtensionRuntimeType? = null,
    val installed: List<KirokuExtension> = emptyList(),
    val available: List<KirokuExtension> = emptyList(),
    val novelInstalled: List<KirokuExtension> = emptyList(),
    val novelAvailable: List<KirokuExtension> = emptyList(),
    val repositories: List<KirokuExtensionRepository> = emptyList(),
    val runtimeStatus: ExtensionRuntimeStatus = ExtensionRuntimeStatus(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val progress: Map<String, Float> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val showRepositories: Boolean = false,
) {

    /** Everything the current category can show, before search/language/runtime filters. */
    val categoryInstalled: List<KirokuExtension>
        get() = if (category == ExtensionCategory.NOVEL) novelInstalled else installed.filter { it.category == category }

    val categoryAvailable: List<KirokuExtension>
        get() = if (category == ExtensionCategory.NOVEL) novelAvailable else available.filter { it.category == category }

    val source: List<KirokuExtension>
        get() = if (tab == ExtensionsTab.INSTALLED) categoryInstalled else categoryAvailable

    val filtered: List<KirokuExtension>
        get() {
            val needle = query.trim().lowercase()
            return source
                .filter { extension ->
                    runtimeFilter == null || extension.runtime == runtimeFilter
                }
                .filter { extension ->
                    languageFilter == null ||
                        extension.language.equals(languageFilter, ignoreCase = true) ||
                        extension.sources.any { it.language.equals(languageFilter, ignoreCase = true) }
                }
                .filter { extension ->
                    needle.isEmpty() ||
                        extension.name.lowercase().contains(needle) ||
                        extension.id.lowercase().contains(needle) ||
                        extension.description?.lowercase()?.contains(needle) == true
                }
                .let { list -> if (tab == ExtensionsTab.INSTALLED) sortByOrder(list) else list }
        }

    val languages: List<String>
        get() = (categoryInstalled + categoryAvailable)
            .flatMap { extension -> listOfNotNull(extension.language).plus(extension.sources.mapNotNull { it.language }) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
            .distinct()
            .sorted()

    /**
     * Backends this runtime reports it can execute.
     *
     * Before the first connection there is nothing to go on, so the catalogue is used until the
     * runtime answers; the last resort is both bridge backends so the screen is never empty.
     */
    val supportedRuntimes: List<ExtensionRuntimeType>
        get() {
            val reported = runtimeStatus.runtimes.filter { it != ExtensionRuntimeType.NOVEL }
            if (reported.isNotEmpty()) return reported
            val listed = (installed + available)
                .map { it.runtime }
                .filter { it != ExtensionRuntimeType.NOVEL }
                .distinct()
            return listed.ifEmpty {
                listOf(ExtensionRuntimeType.ANIYOMI, ExtensionRuntimeType.CLOUDSTREAM)
            }
        }

    /** Categories the connected backends cover, plus novels, which always run in-app. */
    val supportedCategories: List<ExtensionCategory>
        get() {
            val listed = (installed + available).map { it.category }
            return ExtensionCategory.entries.filter { category ->
                category == ExtensionCategory.NOVEL ||
                    category in listed ||
                    supportedRuntimes.any { category in it.categories }
            }
        }

    val runtimeFilters: List<ExtensionRuntimeType>
        get() = supportedRuntimes

    val canInstall: Boolean get() = runtimeStatus.supportsInstall

    val canUpdate: Boolean get() = runtimeStatus.supportsUpdates

    val canManageRepositories: Boolean get() = runtimeStatus.supportsRepositories

    /** Only installed extensions can be reordered, and only when nothing reshapes the list. */
    val canReorder: Boolean
        get() = tab == ExtensionsTab.INSTALLED && query.isBlank() &&
            languageFilter == null && runtimeFilter == null

    private fun sortByOrder(extensions: List<KirokuExtension>): List<KirokuExtension> {
        if (order.isEmpty()) return extensions
        val positions = order.withIndex().associate { (index, id) -> id to index }
        return extensions.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
    }
}

/**
 * State holder for the Extensions screen.
 *
 * It is the only screen that talks to [ExtensionRuntime] directly; every mutation goes through the
 * bridge adapter, so what the screen shows is always the runtime's real state.
 */
class ExtensionsViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime: ExtensionRuntime = Injekt.get()
    private val novelExtensions: NovelExtensionManager = Injekt.get()
    private val orderStore = ExtensionOrderStore()

    private val _state = MutableStateFlow(
        ExtensionsUiState(order = orderStore.order(ExtensionCategory.ANIME)),
    )
    val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

    init {
        observeRuntime()
        observeNovelExtensions()
        refresh()
    }

    private fun observeRuntime() {
        viewModelScope.launch {
            runtime.status.collect { status ->
                _state.update { it.copy(runtimeStatus = status) }
            }
        }
        viewModelScope.launch {
            runtime.progress.collect { progress ->
                _state.update { current ->
                    current.copy(progress = current.progress + (progress.extensionId to progress.fraction))
                }
            }
        }
        viewModelScope.launch {
            runtime.changes.collect {
                _state.update { it.copy(progress = emptyMap()) }
                loadRuntimeExtensions(forceAvailable = false)
            }
        }
    }

    private fun observeNovelExtensions() {
        viewModelScope.launch {
            combine(
                novelExtensions.installedExtensionsFlow,
                novelExtensions.availableExtensionsFlow,
            ) { installed, available -> installed to available }
                .collect { (installed, available) ->
                    _state.update {
                        it.copy(
                            novelInstalled = installed.map(NovelExtension.Installed::toKiroku),
                            novelAvailable = available.map(NovelExtension.Available::toKiroku),
                        )
                    }
                }
        }
    }

    fun refresh(forceAvailable: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, refreshing = true) }
            loadRuntimeExtensions(forceAvailable)
            // A backend can disappear (runtime updated, host removed): never stay on a tab or
            // filter the runtime no longer serves.
            _state.update {
                val categories = it.supportedCategories
                it.copy(
                    loading = false,
                    refreshing = false,
                    category = it.category.takeIf { category -> category in categories }
                        ?: categories.first(),
                    runtimeFilter = it.runtimeFilter
                        ?.takeIf { filter -> filter in it.supportedRuntimes },
                )
            }
        }
    }

    private suspend fun loadRuntimeExtensions(forceAvailable: Boolean) {
        val status = try {
            runtime.connect()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
            return
        }
        if (!status.isConnected) {
            _state.update {
                it.copy(
                    installed = emptyList(),
                    available = emptyList(),
                    repositories = emptyList(),
                    loading = false,
                    refreshing = false,
                )
            }
            return
        }
        try {
            val installed = runtime.installedExtensions()
            val available = runtime.availableExtensions(forceAvailable)
            val repositories = runtime.repositories()
            _state.update {
                it.copy(
                    installed = installed,
                    available = available.filterNot { extension -> installed.any { ext -> ext.id == extension.id } },
                    repositories = repositories,
                    error = null,
                )
            }
        } catch (e: ExtensionRuntimeException) {
            _state.update { it.copy(error = e.message, loading = false, refreshing = false) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message, loading = false, refreshing = false) }
        }
    }

    fun selectCategory(category: ExtensionCategory) {
        _state.update { it.copy(category = category, order = orderStore.order(category)) }
        if (category == ExtensionCategory.NOVEL && _state.value.novelAvailable.isEmpty()) {
            viewModelScope.launch {
                runCatching { novelExtensions.findAvailableExtensions() }
            }
        }
    }

    fun selectTab(tab: ExtensionsTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == ExtensionsTab.AVAILABLE && _state.value.category != ExtensionCategory.NOVEL &&
            _state.value.available.isEmpty()
        ) {
            refresh(forceAvailable = true)
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setLanguageFilter(language: String?) {
        _state.update { it.copy(languageFilter = language) }
    }

    fun setRuntimeFilter(runtime: ExtensionRuntimeType?) {
        if (runtime != null && runtime !in _state.value.supportedRuntimes) return
        _state.update { it.copy(runtimeFilter = runtime) }
    }

    fun showRepositories(show: Boolean = true) {
        _state.update { it.copy(showRepositories = show) }
        if (show) {
            viewModelScope.launch { refreshRepositories() }
        }
    }

    private suspend fun refreshRepositories() {
        if (!_state.value.runtimeStatus.isConnected) return
        runCatching { runtime.repositories() }
            .onSuccess { repositories -> _state.update { it.copy(repositories = repositories) } }
            .onFailure { e -> _state.update { it.copy(error = e.message) } }
    }

    fun install(extension: KirokuExtension) {
        if (extension.runtime == ExtensionRuntimeType.NOVEL) {
            val available = _state.value.novelAvailable.firstOrNull { it.id == extension.id } ?: return
            val source = novelExtensions.availableExtensionsFlow.value.firstOrNull { it.pkgName == extension.id }
            if (source != null) runNovelOperation(extension.id, novelExtensions.installExtension(source))
            return
        }
        runRuntimeOperation(extension.id) { runtime.install(extension.id) }
    }

    fun update(extension: KirokuExtension) {
        if (extension.runtime == ExtensionRuntimeType.NOVEL) {
            val installed = novelExtensions.installedExtensionsFlow.value.firstOrNull { it.pkgName == extension.id }
            if (installed != null) runNovelOperation(extension.id, novelExtensions.updateExtension(installed))
            return
        }
        runRuntimeOperation(extension.id) { runtime.update(extension.id) }
    }

    fun uninstall(extension: KirokuExtension) {
        if (extension.runtime == ExtensionRuntimeType.NOVEL) {
            viewModelScope.launch {
                _state.update { it.copy(busy = it.busy + extension.id) }
                withContext(Dispatchers.IO) {
                    runCatching { novelExtensions.uninstallExtension(extension.pkgName()) }
                }
                _state.update { it.copy(busy = it.busy - extension.id) }
                runCatching { novelExtensions.findAvailableExtensions() }
            }
            return
        }
        runRuntimeOperation(extension.id) { runtime.uninstall(extension.id) }
    }

    private fun KirokuExtension.pkgName(): String = id

    private fun runRuntimeOperation(extensionId: String, operation: suspend () -> Any) {
        if (extensionId in _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + extensionId, error = null) }
            try {
                runtime.connect()
                operation()
                val installed = runtime.installedExtensions()
                val available = runtime.availableExtensions()
                _state.update {
                    it.copy(
                        installed = installed,
                        available = available.filterNot { row -> installed.any { ext -> ext.id == row.id } },
                    )
                }
            } catch (e: ExtensionRuntimeException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = it.busy - extensionId, progress = it.progress - extensionId) }
            }
        }
    }

    private fun runNovelOperation(extensionId: String, operation: Observable<InstallStep>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + extensionId, error = null) }
            withContext(Dispatchers.IO) {
                operation.subscribe(
                    object : Subscriber<InstallStep>() {
                        override fun onNext(step: InstallStep) = Unit

                        override fun onError(e: Throwable) {
                            _state.update { it.copy(error = e.message ?: "Extension operation failed") }
                        }

                        override fun onCompleted() = Unit
                    },
                )
            }
            _state.update { it.copy(busy = it.busy - extensionId) }
            runCatching { novelExtensions.findAvailableExtensions() }
        }
    }

    fun addRepository(url: String, runtimeType: ExtensionRuntimeType, name: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                runtime.addRepository(url, runtimeType, name)
                _state.update { it.copy(repositories = runtime.repositories()) }
                loadRuntimeExtensions(forceAvailable = true)
            } catch (e: ExtensionRuntimeException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun removeRepository(repositoryId: String) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                runtime.removeRepository(repositoryId)
                _state.update { it.copy(repositories = runtime.repositories()) }
                loadRuntimeExtensions(forceAvailable = true)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun moveInstalled(fromId: String, toId: String) {
        val current = _state.value.categoryInstalled.map { it.id }.toMutableList()
        val from = current.indexOf(fromId)
        val to = current.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        current.removeAt(from)
        current.add(to.coerceIn(0, current.size), fromId)
        orderStore.save(_state.value.category, current)
        _state.update { it.copy(order = current) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun retryConnection() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runtime.connect(force = true)
            loadRuntimeExtensions(forceAvailable = true)
            _state.update { it.copy(loading = false) }
        }
    }

    val runtimePackage: String get() = runtime.runtimePackage

    fun launchRuntime(): Boolean = runtime.launchRuntime(getApplication())

    fun downloadRuntime(): Boolean = runtime.openRuntimeDownload(getApplication())

    val isRuntimeStateConnected: Boolean
        get() = _state.value.runtimeStatus.state == ExtensionRuntimeState.CONNECTED
}

// -------------------------------------------------------------------------------------------------
// Novel extensions -> the same row model the screen renders
// -------------------------------------------------------------------------------------------------

private fun NovelExtension.Installed.toKiroku() = KirokuExtension(
    id = pkgName,
    name = name,
    versionName = versionName,
    versionCode = versionCode,
    language = null,
    isNsfw = false,
    iconUrl = null,
    runtime = ExtensionRuntimeType.NOVEL,
    category = ExtensionCategory.NOVEL,
    isInstalled = true,
    hasUpdate = hasUpdate,
    repositoryId = null,
    description = if (isObsolete) "This extension is no longer published by its repository." else null,
    icon = icon,
)

private fun NovelExtension.Available.toKiroku() = KirokuExtension(
    id = pkgName,
    name = name,
    versionName = versionName,
    versionCode = versionCode,
    language = sources.firstOrNull()?.lang,
    isNsfw = false,
    iconUrl = iconUrl.ifBlank { null },
    runtime = ExtensionRuntimeType.NOVEL,
    category = ExtensionCategory.NOVEL,
    isInstalled = false,
    hasUpdate = false,
    repositoryId = repository,
    sources = sources.map {
        ani.dantotsu.extension.ExtensionSource(id = it.id.toString(), name = it.name, language = it.lang)
    },
)
