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

/**
 * One extension ecosystem, as the runtime reports it.
 *
 * Identity is the runtime's own backend id (`aniyomi`, `cloudstream`, `kotatsu`, `mangayomi`, …),
 * so a backend Kiroku has never heard of still shows up with the runtime's own name and the media
 * categories its capabilities advertise. The constants below are only assumptions used before the
 * runtime has answered.
 */
data class ExtensionRuntimeType(
    val id: String,
    val displayName: String,
    /** Media categories the backend advertises support for. */
    val categories: List<ExtensionCategory>,
    val supportsRepositories: Boolean = true,
    /** How the backend executes extension code, as the runtime describes it. */
    val execution: String? = null,
) {

    /** Backends are the same backend when their ids match, whatever else the runtime reported. */
    override fun equals(other: Any?): Boolean = other is ExtensionRuntimeType && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Kiroku's in-app novel extensions; novels are not executed by the bridge. */
        val NOVEL = ExtensionRuntimeType(
            id = "novel",
            displayName = "Novel",
            categories = listOf(ExtensionCategory.NOVEL),
        )

        /** Assumed for the screen only until the runtime reports its real backends. */
        val ANIYOMI = ExtensionRuntimeType(
            id = "aniyomi",
            displayName = "Aniyomi",
            categories = listOf(ExtensionCategory.ANIME, ExtensionCategory.MANGA),
        )

        val CLOUDSTREAM = ExtensionRuntimeType(
            id = "cloudstream",
            displayName = "CloudStream",
            categories = listOf(ExtensionCategory.ANIME),
        )
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
