package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ani.dantotsu.initActivity
import ani.dantotsu.settings.extensions.ExtensionsScreen
import ani.dantotsu.settings.extensions.ExtensionsTheme
import ani.dantotsu.settings.extensions.ExtensionsViewModel
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager

/**
 * The Extensions screen.
 *
 * Extensions are provided by the Kiroku Extension Runtime Bridge; this screen only renders and
 * manages that real state through [ExtensionsViewModel].
 */
class ExtensionsActivity : AppCompatActivity() {

    private val viewModel: ExtensionsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        val openRepositories = intent.getBooleanExtra(EXTRA_OPEN_REPOSITORIES, false)

        setContent {
            ExtensionsTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(openRepositories) {
                    if (openRepositories) viewModel.showRepositories(true)
                }

                ExtensionsScreen(
                    state = state,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpenTools = {
                        startActivity(Intent(this, SettingsExtensionsActivity::class.java))
                    },
                    onSelectCategory = viewModel::selectCategory,
                    onSelectTab = viewModel::selectTab,
                    onQueryChange = viewModel::updateQuery,
                    onLanguageFilter = viewModel::setLanguageFilter,
                    onRuntimeFilter = viewModel::setRuntimeFilter,
                    onInstall = viewModel::install,
                    onUpdate = viewModel::update,
                    onUninstall = viewModel::uninstall,
                    onMove = viewModel::moveInstalled,
                    onRefresh = { viewModel.refresh(forceAvailable = true) },
                    onOpenRepositories = { viewModel.showRepositories(true) },
                    onCloseRepositories = { viewModel.showRepositories(false) },
                    onAddRepository = viewModel::addRepository,
                    onRemoveRepository = viewModel::removeRepository,
                    onLaunchRuntime = {
                        if (!viewModel.launchRuntime()) {
                            snackString("The extension runtime is not installed")
                        }
                    },
                    onDownloadRuntime = {
                        if (!viewModel.downloadRuntime()) {
                            snackString("No browser available to download the runtime")
                        }
                    },
                    onDismissError = viewModel::dismissError,
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_REPOSITORIES = "open_repositories"
    }
}
