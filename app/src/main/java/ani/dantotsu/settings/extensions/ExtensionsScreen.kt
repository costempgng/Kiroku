package ani.dantotsu.settings.extensions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ani.dantotsu.R
import ani.dantotsu.extension.ExtensionCategory
import ani.dantotsu.extension.ExtensionRuntimeType
import ani.dantotsu.extension.KirokuExtension
import ani.dantotsu.extension.KirokuExtensionRepository
import coil.compose.AsyncImage
import kotlin.math.roundToInt

private val HeaderShape = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(24.dp)
private val CardHeight = 88.dp

/**
 * Extensions screen: category + install-state segmented selectors, search, language and runtime
 * filters and the extension list. Every action is wired to [ExtensionsViewModel].
 */
@Composable
fun ExtensionsScreen(
    state: ExtensionsUiState,
    onBack: () -> Unit,
    onOpenTools: () -> Unit,
    onSelectCategory: (ExtensionCategory) -> Unit,
    onSelectTab: (ExtensionsTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onLanguageFilter: (String?) -> Unit,
    onRuntimeFilter: (ExtensionRuntimeType?) -> Unit,
    onInstall: (KirokuExtension) -> Unit,
    onUpdate: (KirokuExtension) -> Unit,
    onUninstall: (KirokuExtension) -> Unit,
    onMove: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onOpenRepositories: () -> Unit,
    onCloseRepositories: () -> Unit,
    onAddRepository: (String, ExtensionRuntimeType) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onLaunchRuntime: () -> Unit,
    onDownloadRuntime: () -> Unit,
    onDismissError: () -> Unit,
) {
    var detailsOf by remember { mutableStateOf<KirokuExtension?>(null) }
    var pendingUninstall by remember { mutableStateOf<KirokuExtension?>(null) }
    var languageDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to ExtensionPalette.background,
                    0.35f to ExtensionPalette.background,
                    1f to ExtensionPalette.backgroundGlow,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            ExtensionsHeader(
                onBack = onBack,
                onOpenRepositories = onOpenRepositories,
                onRefresh = onRefresh,
                onOpenTools = onOpenTools,
            )

            CategorySelector(
                selected = state.category,
                onSelect = onSelectCategory,
            )

            InstallStateSelector(
                selected = state.tab,
                onSelect = onSelectTab,
            )

            SearchRow(
                query = state.query,
                onQueryChange = onQueryChange,
                languageFilter = state.languageFilter,
                onLanguageClick = { languageDialog = true },
            )

            if (state.runtimeFilters.isNotEmpty()) {
                RuntimeFilterRow(
                    runtimes = state.runtimeFilters,
                    selected = state.runtimeFilter,
                    onSelect = onRuntimeFilter,
                )
            }

            if (state.error != null) {
                ErrorBanner(message = state.error, onDismiss = onDismissError)
            }

            when {
                state.loading -> LoadingState()
                !state.runtimeStatus.isConnected && state.category != ExtensionCategory.NOVEL ->
                    RuntimeUnavailable(
                        state = state,
                        onRetry = onRefresh,
                        onLaunchRuntime = onLaunchRuntime,
                        onDownloadRuntime = onDownloadRuntime,
                    )

                else -> ExtensionList(
                    state = state,
                    onInstall = onInstall,
                    onUpdate = onUpdate,
                    onUninstall = { pendingUninstall = it },
                    onDetails = { detailsOf = it },
                    onMove = onMove,
                )
            }
        }
    }

    if (languageDialog) {
        LanguageFilterDialog(
            selected = state.languageFilter,
            languages = state.languages,
            onSelect = {
                onLanguageFilter(it)
                languageDialog = false
            },
            onDismiss = { languageDialog = false },
        )
    }

    state.runtimeFilter?.let { }

    detailsOf?.let { extension ->
        ExtensionDetailsDialog(
            extension = extension,
            onDismiss = { detailsOf = null },
            onUpdate = {
                onUpdate(extension)
                detailsOf = null
            },
        )
    }

    pendingUninstall?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Uninstall ${extension.name}?") },
            text = {
                Text(
                    "The extension will be removed from the runtime. Media you already added to your " +
                        "library stays untouched, but its source will be unavailable until you reinstall it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUninstall(extension)
                    pendingUninstall = null
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("Cancel") }
            },
        )
    }

    if (state.showRepositories) {
        RepositoriesDialog(
            repositories = state.repositories,
            onDismiss = onCloseRepositories,
            onAdd = onAddRepository,
            onRemove = onRemoveRepository,
        )
    }
}

@Composable
private fun ExtensionsHeader(
    onBack: () -> Unit,
    onOpenRepositories: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTools: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(HeaderShape)
                .background(ExtensionPalette.pill)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_round_arrow_back_ios_new_24),
                    contentDescription = "Back",
                    tint = ExtensionPalette.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "Extensions",
                style = MaterialTheme.typography.titleLarge,
                color = ExtensionPalette.text,
                modifier = Modifier.padding(start = 8.dp, end = 18.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clip(HeaderShape)
                .background(ExtensionPalette.pill)
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderAction(
                iconRes = R.drawable.ic_round_source_24,
                description = "Manage repositories",
                onClick = onOpenRepositories,
            )
            HeaderAction(
                iconRes = R.drawable.ic_round_refresh_24,
                description = "Refresh extensions",
                onClick = onRefresh,
            )
            HeaderAction(
                iconRes = R.drawable.ic_round_settings_24,
                description = "Extension tools",
                onClick = onOpenTools,
            )
        }
    }
}

@Composable
private fun HeaderAction(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = ExtensionPalette.pink,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun CategorySelector(
    selected: ExtensionCategory,
    onSelect: (ExtensionCategory) -> Unit,
) {
    SegmentedRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        options = ExtensionCategory.entries,
        selected = selected,
        label = { it.displayName },
        iconRes = {
            when (it) {
                ExtensionCategory.ANIME -> R.drawable.ic_round_movie_filter_24
                ExtensionCategory.MANGA -> R.drawable.ic_round_import_contacts_24
                ExtensionCategory.NOVEL -> R.drawable.ic_round_menu_book_24
            }
        },
        onSelect = onSelect,
    )
}

@Composable
private fun InstallStateSelector(
    selected: ExtensionsTab,
    onSelect: (ExtensionsTab) -> Unit,
) {
    SegmentedRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        options = ExtensionsTab.entries,
        selected = selected,
        label = { if (it == ExtensionsTab.INSTALLED) "Installed" else "Available" },
        iconRes = { null },
        onSelect = onSelect,
    )
}

@Composable
private fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    iconRes: (T) -> Int?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(HeaderShape)
            .background(ExtensionPalette.pill)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(HeaderShape)
                    .background(if (isSelected) ExtensionPalette.pink else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iconRes(option)?.let { res ->
                    Icon(
                        painter = painterResource(res),
                        contentDescription = null,
                        tint = if (isSelected) ExtensionPalette.onPink else ExtensionPalette.muted,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) ExtensionPalette.onPink else ExtensionPalette.muted,
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    languageFilter: String?,
    onLanguageClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(HeaderShape)
                .background(ExtensionPalette.pill)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_round_search_24),
                contentDescription = null,
                tint = ExtensionPalette.muted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ExtensionPalette.text),
                cursorBrush = Brush.verticalGradient(
                    listOf(ExtensionPalette.pink, ExtensionPalette.pink),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search extensions...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ExtensionPalette.muted,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(HeaderShape)
                .background(
                    if (languageFilter != null) ExtensionPalette.pinkDeep else ExtensionPalette.pill,
                )
                .clickable(onClick = onLanguageClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_round_translate_24),
                contentDescription = "Filter by language",
                tint = if (languageFilter != null) ExtensionPalette.onPink else ExtensionPalette.muted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RuntimeFilterRow(
    runtimes: List<ExtensionRuntimeType>,
    selected: ExtensionRuntimeType?,
    onSelect: (ExtensionRuntimeType?) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                text = "All",
                selected = selected == null,
                iconRes = R.drawable.ic_round_source_24,
                onClick = { onSelect(null) },
            )
        }
        items(runtimes) { runtime ->
            FilterChip(
                text = runtime.displayName,
                selected = selected == runtime,
                iconRes = null,
                onClick = { onSelect(if (selected == runtime) null else runtime) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    iconRes: Int?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(HeaderShape)
            .background(if (selected) ExtensionPalette.pink else ExtensionPalette.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconRes?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                tint = if (selected) ExtensionPalette.onPink else ExtensionPalette.muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) ExtensionPalette.onPink else ExtensionPalette.muted,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF35222A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = ExtensionPalette.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        CircularProgressIndicator(color = ExtensionPalette.pink, strokeWidth = 2.dp)
    }
}

@Composable
private fun RuntimeUnavailable(
    state: ExtensionsUiState,
    onRetry: () -> Unit,
    onLaunchRuntime: () -> Unit,
    onDownloadRuntime: () -> Unit,
) {
    val status = state.runtimeStatus
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(CardShape)
            .background(ExtensionPalette.card)
            .padding(20.dp),
    ) {
        Text(
            text = when {
                status.needsRuntimeInstall -> "Extension runtime not installed"
                status.state == ani.dantotsu.extension.ExtensionRuntimeState.INCOMPATIBLE ->
                    "Extension runtime is out of date"

                else -> "Extension runtime unavailable"
            },
            style = MaterialTheme.typography.titleMedium,
            color = ExtensionPalette.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = status.message
                ?: "Extensions are executed by the Kiroku Extension Runtime. Install or update it, " +
                "then allow Kiroku in its Client access screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = ExtensionPalette.muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.needsRuntimeInstall) {
                ActionPill(text = "Get runtime", onClick = onDownloadRuntime)
            } else {
                ActionPill(text = "Open runtime", onClick = onLaunchRuntime)
            }
            ActionPill(text = "Retry", onClick = onRetry, muted = true)
        }
    }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit, muted: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(HeaderShape)
            .background(if (muted) ExtensionPalette.chip else ExtensionPalette.pink)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (muted) ExtensionPalette.text else ExtensionPalette.onPink,
        )
    }
}

@Composable
private fun ExtensionList(
    state: ExtensionsUiState,
    onInstall: (KirokuExtension) -> Unit,
    onUpdate: (KirokuExtension) -> Unit,
    onUninstall: (KirokuExtension) -> Unit,
    onDetails: (KirokuExtension) -> Unit,
    onMove: (String, String) -> Unit,
) {
    val items = state.filtered
    val density = LocalDensity.current
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    if (items.isEmpty()) {
        EmptyState(state)
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.canReorder) {
            Text(
                text = "Hold and drag any item to reorder",
                style = MaterialTheme.typography.bodySmall,
                color = ExtensionPalette.muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, extension ->
                val isDragging = draggedId == extension.id
                val cardHeightPx = with(density) { (CardHeight + 12.dp).toPx() }
                ExtensionCard(
                    extension = extension,
                    installedTab = state.tab == ExtensionsTab.INSTALLED,
                    busy = extension.id in state.busy,
                    progress = state.progress[extension.id],
                    onInstall = { onInstall(extension) },
                    onUpdate = { onUpdate(extension) },
                    onUninstall = { onUninstall(extension) },
                    onDetails = { onDetails(extension) },
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .pointerInput(extension.id, state.canReorder, items) {
                            if (!state.canReorder) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedId = extension.id
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                                onDragEnd = {
                                    val steps = (dragOffset / cardHeightPx).roundToInt()
                                    val target = (index + steps).coerceIn(0, items.lastIndex)
                                    if (target != index) onMove(extension.id, items[target].id)
                                    draggedId = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggedId = null
                                    dragOffset = 0f
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(state: ExtensionsUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_round_source_24),
            contentDescription = null,
            tint = ExtensionPalette.muted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = when {
                state.category == ExtensionCategory.NOVEL && state.tab == ExtensionsTab.INSTALLED ->
                    "No novel extensions installed"

                state.category == ExtensionCategory.NOVEL ->
                    "No novel extensions available in your repositories"

                state.tab == ExtensionsTab.INSTALLED ->
                    "No ${state.category.displayName.lowercase()} extensions installed yet"

                else -> "No ${state.category.displayName.lowercase()} extensions available"
            },
            style = MaterialTheme.typography.titleSmall,
            color = ExtensionPalette.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                state.category == ExtensionCategory.NOVEL ->
                    "Novel extensions are installed through their own repositories."

                state.tab == ExtensionsTab.INSTALLED ->
                    "Open Available to install one from your repositories."

                else -> "Add a repository, then refresh to list its extensions."
            },
            style = MaterialTheme.typography.bodySmall,
            color = ExtensionPalette.muted,
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: KirokuExtension,
    installedTab: Boolean,
    busy: Boolean,
    progress: Float?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CardHeight)
            .clip(CardShape)
            .background(ExtensionPalette.card)
            .clickable(onClick = onDetails),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIcon(extension)

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ExtensionPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        text = extension.languageLabel,
                        container = ExtensionPalette.pink,
                        content = ExtensionPalette.onPink,
                    )
                    Spacer(Modifier.width(6.dp))
                    Badge(
                        text = "v${extension.versionName}",
                        container = ExtensionPalette.green,
                        content = Color(0xFF10240F),
                    )
                    if (extension.isNsfw) {
                        Spacer(Modifier.width(6.dp))
                        Badge(
                            text = "18+",
                            container = ExtensionPalette.orange,
                            content = Color(0xFF33170A),
                        )
                    }
                    if (extension.loadError != null) {
                        Spacer(Modifier.width(6.dp))
                        Badge(
                            text = "load error",
                            container = Color(0xFF4A2530),
                            content = ExtensionPalette.error,
                        )
                    }
                }
            }

            when {
                busy -> Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = ExtensionPalette.pink,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }

                installedTab -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (extension.hasUpdate) {
                        RoundAction(
                            iconRes = R.drawable.ic_round_sync_24,
                            description = "Update ${extension.name}",
                            background = ExtensionPalette.pinkDeep,
                            tint = ExtensionPalette.onPink,
                            onClick = onUpdate,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    RoundAction(
                        iconRes = R.drawable.ic_round_delete_24,
                        description = "Uninstall ${extension.name}",
                        background = ExtensionPalette.deleteButton,
                        tint = Color(0xFF3A1A0F),
                        onClick = onUninstall,
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundAction(
                        iconRes = R.drawable.ic_round_settings_24,
                        description = "Details for ${extension.name}",
                        background = ExtensionPalette.settingsButton,
                        tint = Color(0xFF4A1029),
                        onClick = onDetails,
                    )
                }

                else -> ActionPill(text = "Install", onClick = onInstall)
            }
        }

        progress?.takeIf { busy }?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                color = ExtensionPalette.pink,
                trackColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ExtensionIcon(extension: KirokuExtension) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ExtensionPalette.chip),
        contentAlignment = Alignment.Center,
    ) {
        val drawable: Drawable? = extension.icon
        when {
            extension.iconUrl != null -> AsyncImage(
                model = extension.iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )

            drawable != null -> Image(
                bitmap = drawable.toImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            else -> Text(
                text = extension.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = ExtensionPalette.pink,
            )
        }
    }

    Spacer(Modifier.width(2.dp))

    // Runtime indicator, mirroring the small source badge in the reference design.
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(ExtensionPalette.pinkDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = extension.runtime.displayName.take(1),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = ExtensionPalette.onPink,
        )
    }
}

@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(HeaderShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundAction(
    iconRes: Int,
    description: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun LanguageFilterDialog(
    selected: String?,
    languages: List<String>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by language") },
        text = {
            Column {
                LanguageRow(
                    label = "All languages",
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
                languages.forEach { language ->
                    LanguageRow(
                        label = language.uppercase(),
                        selected = selected.equals(language, ignoreCase = true),
                        onClick = { onSelect(language) },
                    )
                }
                if (languages.isEmpty()) {
                    Text(
                        text = "No language metadata available for these extensions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ExtensionPalette.muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) ExtensionPalette.pink else ExtensionPalette.text,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_round_add_circle_24),
                contentDescription = null,
                tint = ExtensionPalette.pink,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ExtensionDetailsDialog(
    extension: KirokuExtension,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(extension.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("Package", extension.id)
                DetailLine("Runtime", extension.runtime.displayName)
                DetailLine("Category", extension.category.displayName)
                DetailLine("Version", "${extension.versionName} (${extension.versionCode})")
                DetailLine("Language", extension.languageLabel)
                if (extension.isNsfw) DetailLine("Age restriction", "18+")
                extension.libVersion?.let { DetailLine("Extension library", it) }
                extension.repositoryId?.let { DetailLine("Repository", it) }
                if (extension.sources.isNotEmpty()) {
                    DetailLine("Sources", extension.sources.joinToString { it.name })
                }
                extension.description?.let { DetailLine("Description", it) }
                extension.loadError?.let { DetailLine("Load error", it) }
            }
        },
        confirmButton = {
            if (extension.hasUpdate) {
                TextButton(onClick = onUpdate) { Text("Update") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = ExtensionPalette.muted,
    )
}

@Composable
private fun RepositoriesDialog(
    repositories: List<KirokuExtensionRepository>,
    onDismiss: () -> Unit,
    onAdd: (String, ExtensionRuntimeType) -> Unit,
    onRemove: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var runtime by remember { mutableStateOf(ExtensionRuntimeType.ANIYOMI) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repositories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (repositories.isEmpty()) {
                    Text(
                        text = "No repositories yet. Add an index URL to discover extensions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ExtensionPalette.muted,
                    )
                }
                repositories.forEach { repository ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ExtensionPalette.chip)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = repository.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = ExtensionPalette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${repository.runtime.displayName} · ${repository.url}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ExtensionPalette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            repository.lastError?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ExtensionPalette.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { onRemove(repository.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_round_delete_24),
                                contentDescription = "Remove ${repository.name}",
                                tint = ExtensionPalette.deleteButton,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtensionRuntimeType.entries
                        .filter { it != ExtensionRuntimeType.NOVEL }
                        .forEach { option ->
                            FilterChip(
                                text = option.displayName,
                                selected = runtime == option,
                                iconRes = null,
                                onClick = { runtime = option },
                            )
                        }
                }

                BasicTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ExtensionPalette.text),
                    cursorBrush = Brush.verticalGradient(
                        listOf(ExtensionPalette.pink, ExtensionPalette.pink),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ExtensionPalette.chip)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        if (url.isEmpty()) {
                            Text(
                                text = "Repository index URL",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ExtensionPalette.muted,
                            )
                        }
                        inner()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = url.trim()
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        onAdd(value, runtime)
                        url = ""
                    }
                },
                enabled = url.trim().startsWith("http"),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun Drawable.toImageBitmap() = Bitmap.createBitmap(
    intrinsicWidth.coerceAtLeast(1),
    intrinsicHeight.coerceAtLeast(1),
    Bitmap.Config.ARGB_8888,
).let { bitmap ->
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bitmap.asImageBitmap()
}
