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
import io.chibi.bridge.models.Episode as BridgeEpisode
import io.chibi.bridge.models.Media as BridgeMedia
import io.chibi.bridge.models.Pages as BridgePages
import io.chibi.bridge.models.Video as BridgeVideo
import io.chibi.bridge.sdk.ChibiRuntime
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
 * [ChibiRuntime]. Nothing outside this file needs to know where the data comes from.
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

/** Backend id prefix of an extension key, e.g. `aniyomi` in `aniyomi|ANIME|AnimeAV1`. */
private fun backendIdOf(extensionId: String): String = extensionId.substringBefore('|')

/**
 * An anime source executed by the runtime.
 *
 * [sourceId] is the runtime's own source id (`anime:<id>`/`manga:<id>` for Aniyomi, the plugin id
 * for CloudStream). Kiroku keeps the ids the runtime hands out, so they round-trip unchanged.
 */
class BridgeAnimeSource(
    val extensionId: String,
    @Suppress("UNUSED_PARAMETER") private val extensionName: String,
    private val sourceId: String,
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val supportsLatest: Boolean,
) : AnimeCatalogueSource {

    private val runtime: ChibiRuntime get() = Injekt.get<BridgeExtensionRuntime>().chibi

    private val backend: String get() = backendIdOf(extensionId)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        runtime.detail(backend, sourceId, anime.toBridgeMedia()).toSAnime()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        runtime.detail(backend, sourceId, anime.toBridgeMedia()).episodes.map { it.toSEpisode() }

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        runtime.videoList(backend, sourceId, episode.toBridgeEpisode()).map { it.toKirokuVideo() }

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        runtime.popular(backend, sourceId, page).toAnimesPage()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        runtime.search(backend, sourceId, query, page).toAnimesPage()

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        runtime.latest(backend, sourceId, page).toAnimesPage()

    // The bridge does not expose source filters through Kiroku's filter model yet.
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
    @Suppress("UNUSED_PARAMETER") private val extensionName: String,
    private val sourceId: String,
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val supportsLatest: Boolean,
    baseUrl: String?,
) : HttpSource() {

    override val baseUrl: String = baseUrl?.takeIf { it.isNotBlank() } ?: "https://extension.kiroku"

    private val runtime: ChibiRuntime get() = Injekt.get<BridgeExtensionRuntime>().chibi

    private val backend: String get() = backendIdOf(extensionId)

    override suspend fun getMangaDetails(manga: SManga): SManga =
        runtime.detail(backend, sourceId, manga.toBridgeMedia()).toSManga()

    override suspend fun getChapterList(manga: SManga): List<SChapter> =
        runtime.detail(backend, sourceId, manga.toBridgeMedia()).episodes.map { it.toSChapter() }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        runtime.pageList(backend, sourceId, chapter.toBridgeEpisode()).mapIndexed { index, page ->
            Page(index = index, url = page.url, imageUrl = page.url)
        }

    override suspend fun getPopularManga(page: Int): MangasPage =
        runtime.popular(backend, sourceId, page).toMangasPage()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        runtime.search(backend, sourceId, query, page).toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        runtime.latest(backend, sourceId, page).toMangasPage()

    override fun getFilterList(): FilterList = FilterList()

    override fun toString(): String = "$name (${lang.uppercase()})"
}

// -------------------------------------------------------------------------------------------------
// Model conversion: the runtime's models <-> the models Kiroku's pipeline consumes
// -------------------------------------------------------------------------------------------------

/** The runtime addresses media by its url; Kiroku keeps that url in `url`/`title`. */
internal fun SAnime.toBridgeMedia(): BridgeMedia =
    BridgeMedia(url = url.ifBlank { title }, title = title)

internal fun SManga.toBridgeMedia(): BridgeMedia =
    BridgeMedia(url = url.ifBlank { title }, title = title)

internal fun SEpisode.toBridgeEpisode(): BridgeEpisode = BridgeEpisode(
    url = url,
    name = name,
    dateUpload = date_upload.takeIf { it > 0L }?.toString().orEmpty(),
    scanlator = scanlator,
    episodeNumber = episode_number.takeIf { it >= 0f }?.toString().orEmpty(),
)

internal fun SChapter.toBridgeEpisode(): BridgeEpisode = BridgeEpisode(
    url = url,
    name = name,
    dateUpload = date_upload.takeIf { it > 0L }?.toString().orEmpty(),
    scanlator = scanlator,
    episodeNumber = chapter_number.takeIf { it >= 0f }?.toString().orEmpty(),
)

internal fun BridgeMedia.toSAnime(): SAnime = SAnimeImpl().also { sAnime ->
    sAnime.url = url ?: title.orEmpty()
    sAnime.title = title.orEmpty()
    sAnime.thumbnail_url = cover
    sAnime.description = description
    sAnime.author = author
    sAnime.artist = artist
    sAnime.genre = genre.joinToString(", ").ifBlank { null }
    sAnime.initialized = true
}

internal fun BridgeEpisode.toSEpisode(): SEpisode = SEpisodeImpl().also { sEpisode ->
    sEpisode.url = url.orEmpty()
    sEpisode.name = name.orEmpty()
    sEpisode.date_upload = dateUpload.toLongOrNull() ?: 0L
    sEpisode.episode_number = episodeNumber.toFloatOrNull() ?: -1f
    sEpisode.scanlator = scanlator
}

internal fun BridgePages.toAnimesPage(): AnimesPage =
    AnimesPage(animes = list.map { it.toSAnime() }, hasNextPage = hasNextPage)

internal fun BridgeVideo.toKirokuVideo(): Video = Video(
    url = url,
    quality = quality.ifBlank { title ?: "Default" },
    videoUrl = url,
    headers = headers?.takeIf { it.isNotEmpty() }?.let { map ->
        Headers.Builder().apply { map.forEach { (key, value) -> add(key, value) } }.build()
    },
    subtitleTracks = subtitles.mapNotNull { track ->
        track.file?.let { Track(it, track.label ?: "Subtitle") }
    },
    audioTracks = audios.mapNotNull { track ->
        track.file?.let { Track(it, track.label ?: "Audio") }
    },
)

internal fun BridgeMedia.toSManga(): SManga = SMangaImpl().also { sManga ->
    sManga.url = url ?: title.orEmpty()
    sManga.title = title.orEmpty()
    sManga.thumbnail_url = cover
    sManga.description = description
    sManga.author = author
    sManga.artist = artist
    sManga.genre = genre.joinToString(", ").ifBlank { null }
    sManga.initialized = true
}

internal fun BridgeEpisode.toSChapter(): SChapter = SChapterImpl().also { sChapter ->
    sChapter.url = url.orEmpty()
    sChapter.name = name.orEmpty()
    sChapter.date_upload = dateUpload.toLongOrNull() ?: 0L
    sChapter.chapter_number = episodeNumber.toFloatOrNull() ?: -1f
    sChapter.scanlator = scanlator
}

internal fun BridgePages.toMangasPage(): MangasPage =
    MangasPage(mangas = list.map { it.toSManga() }, hasNextPage = hasNextPage)
