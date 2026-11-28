package ani.dantotsu.extension

import android.graphics.drawable.Drawable

/**
 * Kiroku's own extension model.
 *
 * Extensions are executed by an out-of-process runtime (the ChibiExtensionRuntimeBridge); this
 * file only describes what Kiroku itself needs to render and manage them. Keeping the model flat
 * mirrors the rest of Kiroku's extension code (`AnimeExtension`, `MangaExtension`) instead of
 * leaking the bridge's IPC shape into the screens.
 */

/** Runtime ecosystems Kiroku can execute extensions through. */
enum class ExtensionRuntimeType(val displayName: String) {
    ANIYOMI("Aniyomi"),
    CLOUDSTREAM("CloudStream"),

    /** Kiroku's in-app novel extensions; novels have no bridge runtime. */
    NOVEL("Novel");

    /** Media categories this runtime can serve; the screen hides the rest. */
    val categories: List<ExtensionCategory>
        get() = when (this) {
            ANIYOMI -> listOf(ExtensionCategory.ANIME, ExtensionCategory.MANGA)
            CLOUDSTREAM -> listOf(ExtensionCategory.ANIME)
            NOVEL -> listOf(ExtensionCategory.NOVEL)
        }

    companion object {
        /** Maps the bridge's backend name; unknown backends are ignored instead of guessed. */
        fun fromWire(value: String?): ExtensionRuntimeType? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}

/** Content type an extension serves, matching Kiroku's own media categories. */
enum class ExtensionCategory(val displayName: String) {
    ANIME("Anime"),
    MANGA("Manga"),
    NOVEL("Novel");

    companion object {
        /**
         * Category of an extension Kiroku has not executed yet (a repository entry).
         *
         * The bridge reports the backend, not the media type, so the category follows the package
         * naming convention the ecosystems themselves use:
         * `eu.kanade.tachiyomi.animeextension.*` is an anime extension and
         * `eu.kanade.tachiyomi.extension.*` a Mihon manga extension. CloudStream plugins are video
         * providers.
         */
        fun fromPackageId(packageName: String, runtime: ExtensionRuntimeType): ExtensionCategory =
            when {
                runtime == ExtensionRuntimeType.CLOUDSTREAM -> ANIME
                packageName.contains(".animeextension.") -> ANIME
                packageName.contains(".extension.") -> MANGA
                else -> ANIME
            }
    }
}

/** A single source/provider inside an extension. */
data class ExtensionSource(
    val id: String,
    val name: String,
    val language: String?,
    val baseUrl: String? = null,
    val supportsLatest: Boolean = false,
)

/**
 * One extension as Kiroku sees it, installed or merely available from a repository.
 */
data class KirokuExtension(
    val id: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val language: String?,
    val isNsfw: Boolean,
    val iconUrl: String?,
    val runtime: ExtensionRuntimeType,
    val category: ExtensionCategory,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val repositoryId: String?,
    val sources: List<ExtensionSource> = emptyList(),
    val description: String? = null,
    val libVersion: String? = null,
    val loadError: String? = null,
    /** In-app extensions (novels) ship their icon as a drawable; bridge extensions use [iconUrl]. */
    val icon: Drawable? = null,
) {
    /** Language badge text; extensions without a language serve every locale. */
    val languageLabel: String
        get() = language?.takeIf { it.isNotBlank() && !it.equals("all", true) }?.uppercase() ?: "ALL"

    val canOpenSettings: Boolean
        get() = isInstalled && loadError == null && sources.isNotEmpty()
}

/** A repository index the runtime can install extensions from. */
data class KirokuExtensionRepository(
    val id: String,
    val name: String,
    val url: String,
    val runtime: ExtensionRuntimeType,
    val enabled: Boolean,
    val isBuiltIn: Boolean,
    val lastRefreshedAt: Long? = null,
    val lastError: String? = null,
)
