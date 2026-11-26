package ani.dantotsu.extension.bridge

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kiroku.runtime.bridge.client.KirokuRuntimeBridge
import io.kiroku.runtime.ipc.Anime
import io.kiroku.runtime.ipc.Chapter
import io.kiroku.runtime.ipc.Episode
import io.kiroku.runtime.ipc.Manga
import io.kiroku.runtime.ipc.MangaPage
import io.kiroku.runtime.ipc.StreamSource
import java.security.MessageDigest
import okhttp3.Headers
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Bridge-backed sources.
 *
 * Kiroku's media pipeline (watch/read adapters, library, downloads, source settings) speaks
 * `AnimeSource`/`MangaSource`. Instead of teaching all of it about the bridge, each source the
 * runtime reports is exposed as an ordinary Kiroku source that forwards calls through
 * [KirokuRuntimeBridge]. Nothing outside this file needs to know where the data comes from.
 */

/** Stable source id derived from the extension and source identity (never invented per run). */
internal fun bridgeSourceId(extensionId: String, sourceId: String): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("$extensionId/$sourceId".toByteArray())
    var value = 0L
    for (i in 0..7) {
        value = (value shl 8) or (bytes[i].toLong() and 0xff)
    }
    return value and Long.MAX_VALUE
}

/**
 * An anime source executed by the runtime.
 *
 * Parameters are the extension's own ids: Kiroku stores them in `SAnime.url`/`SEpisode.url` and
 * hands them back unchanged, which is exactly what the bridge expects.
 */
class BridgeAnimeSource(
    val extensionId: String,
    private val extensionName: String,
    private val sourceId: String?,
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val supportsLatest: Boolean,
) : AnimeCatalogueSource {

    private val runtime: KirokuRuntimeBridge get() = Injekt.get<BridgeExtensionRuntime>().bridgeClient

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = runtime.getDetails(extensionId, anime.animeKey(), sourceId)
        return details.anime.toSAnime()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        runtime.getEpisodes(extensionId, anime.animeKey(), sourceId).map { it.toSEpisode() }

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        runtime.extractStreams(extensionId, episode.url, sourceId).map { it.toVideo() }

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        runtime.popular(extensionId, sourceId, page).toAnimesPage()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        runtime.search(extensionId, query, sourceId, page).toAnimesPage()

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        runtime.latest(extensionId, sourceId, page).toAnimesPage()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // The runtime paginates on its own; these deprecated variants only exist for the API contract.
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.error<AnimesPage>(UnsupportedOperationException("Use getPopularAnime"))

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
        Observable.error<AnimesPage>(UnsupportedOperationException("Use getSearchAnime"))

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.error<AnimesPage>(UnsupportedOperationException("Use getLatestUpdates"))

    override fun toString(): String = "$name (${lang.uppercase()})"
}

/**
 * A manga source executed by the runtime.
 *
 * Extends [HttpSource] because Kiroku's manga pipeline expects it, but every network read is
 * delegated to the runtime; the base URL is only used for display and cookies.
 */
class BridgeMangaSource(
    val extensionId: String,
    private val extensionName: String,
    private val sourceId: String?,
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val supportsLatest: Boolean,
    baseUrl: String?,
) : HttpSource() {

    override val baseUrl: String = baseUrl?.takeIf { it.isNotBlank() } ?: "https://extension.kiroku"

    private val runtime: KirokuRuntimeBridge get() = Injekt.get<BridgeExtensionRuntime>().bridgeClient

    override suspend fun getMangaDetails(manga: SManga): SManga =
        runtime.getMangaDetails(extensionId, manga.mangaKey(), sourceId).manga.toSManga()

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        runtime.getChapters(extensionId, manga.mangaKey(), sourceId).map { it.toSChapter() }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        runtime.getPages(extensionId, chapter.url, sourceId).map { it.toPage() }

    override suspend fun getPopularManga(page: Int): MangasPage =
        runtime.popularManga(extensionId, sourceId, page).toMangasPage()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        runtime.searchManga(extensionId, query, sourceId, page).toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        runtime.latestManga(extensionId, sourceId, page).toMangasPage()

    override fun getFilterList(): FilterList = FilterList()

    override fun toString(): String = "$name (${lang.uppercase()})"
}

// -------------------------------------------------------------------------------------------------
// Model conversion: runtime models -> the models Kiroku's pipeline consumes
// -------------------------------------------------------------------------------------------------

/** The runtime accepts either the extension's url or its id; Kiroku keeps the url. */
internal fun SAnime.animeKey(): String = url.ifBlank { title }

internal fun SManga.mangaKey(): String = url.ifBlank { title }

internal fun Anime.toSAnime(): SAnime = SAnimeImpl().also { sAnime ->
    sAnime.url = url ?: id
    sAnime.title = title
    sAnime.thumbnail_url = coverUrl
    sAnime.description = description
    sAnime.author = author
    sAnime.artist = artist
    sAnime.genre = genres.joinToString(", ").ifBlank { null }
    sAnime.initialized = true
}

internal fun Episode.toSEpisode(): SEpisode = SEpisodeImpl().also { sEpisode ->
    sEpisode.url = url ?: id
    sEpisode.name = name
    sEpisode.date_upload = dateUpload ?: 0L
    sEpisode.episode_number = episodeNumber?.toFloat() ?: -1f
    sEpisode.scanlator = scanlator
}

// The runtime answers one page per call and does not expose the source's page cursor.
internal fun List<Anime>.toAnimesPage(): AnimesPage =
    AnimesPage(animes = map { it.toSAnime() }, hasNextPage = false)

internal fun StreamSource.toVideo(): Video {
    val mergedHeaders = Headers.Builder().apply {
        headers.forEach { (key, value) -> add(key, value) }
        referer?.takeIf { it.isNotBlank() }?.let { add("Referer", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { add("User-Agent", it) }
    }.build()

    return Video(
        url = url,
        quality = quality ?: server ?: title ?: "Default",
        videoUrl = url,
        headers = mergedHeaders,
        subtitleTracks = subtitles.map { Track(it.url, it.label ?: it.language ?: "Subtitle") },
        audioTracks = audioTracks.mapNotNull { track ->
            track.url?.let { Track(it, track.label ?: track.language ?: "Audio") }
        },
    )
}

internal fun Manga.toSManga(): SManga = SMangaImpl().also { sManga ->
    sManga.url = url ?: id
    sManga.title = title
    sManga.thumbnail_url = coverUrl
    sManga.description = description
    sManga.author = author
    sManga.artist = artist
    sManga.genre = genres.joinToString(", ").ifBlank { null }
    sManga.initialized = true
}

internal fun Chapter.toSChapter(): SChapter = SChapterImpl().also { sChapter ->
    sChapter.url = url ?: id
    sChapter.name = name
    sChapter.date_upload = dateUpload ?: 0L
    sChapter.chapter_number = chapterNumber?.toFloat() ?: -1f
    sChapter.scanlator = scanlator
}

internal fun MangaPage.toPage(): Page = Page(index = index, url = url, imageUrl = imageUrl ?: url)

internal fun List<Manga>.toMangasPage(): MangasPage =
    MangasPage(mangas = map { it.toSManga() }, hasNextPage = false)
