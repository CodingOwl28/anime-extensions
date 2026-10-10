package eu.kanade.tachiyomi.animeextension.en.ytsubs

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

class YtSubs : AnimeHttpSource() {

    override val name = "YT Subs"

    override val baseUrl = "https://www.youtube.com"

    override val lang = "en"

    override val supportsLatest = false

    override val disableRelatedAnimesBySearch = true

    private val youtube = ServiceList.YouTube

    private val npInit by lazy {
        NewPipe.init(OkDownloader(client), Localization("en", "US"))
    }

    private fun initNewPipe() {
        npInit
    }

    // ============================== Browse ================================

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val results = withContext(Dispatchers.IO) { searchChannels(query) }
        return AnimesPage(results, false)
    }

    private fun searchChannels(query: String): List<SAnime> {
        initNewPipe()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        if (q.startsWith("https://")) {
            val host = q.toHttpUrlOrNull()?.host ?: return emptyList()
            if (host == "www.youtube.com" || host == "youtube.com" || host == "m.youtube.com") {
                return listOf(ChannelInfo.getInfo(youtube, q).toSAnime())
            }
            return emptyList()
        }

        val extractor = youtube.getSearchExtractor(q, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<ChannelInfoItem>()
            .map { item ->
                SAnime.create().apply {
                    title = item.name
                    url = item.url
                    thumbnail_url = item.thumbnails.firstOrNull()?.url
                    description = item.description
                }
            }
    }

    private fun ChannelInfo.toSAnime(): SAnime = SAnime.create().apply {
        title = this@toSAnime.name
        url = this@toSAnime.url
        thumbnail_url = this@toSAnime.avatars.firstOrNull()?.url
        description = this@toSAnime.description
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        initNewPipe()
        ChannelInfo.getInfo(youtube, anime.url).toSAnime().apply { initialized = true }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        initNewPipe()
        val info = ChannelInfo.getInfo(youtube, anime.url)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return@withContext emptyList()

        val first = ChannelTabInfo.getInfo(youtube, tab)
        val items = first.relatedItems.filterIsInstance<StreamInfoItem>().toMutableList()
        var page = first.nextPage
        var pages = 1
        while (page != null && pages < MAX_PAGES) {
            val more = ChannelTabInfo.getMoreItems(youtube, tab, page)
            items += more.items.filterIsInstance<StreamInfoItem>()
            page = more.nextPage
            pages++
        }

        items.mapIndexed { index, item ->
            SEpisode.create().apply {
                name = item.name
                url = item.url
                episode_number = (items.size - index).toFloat()
                date_upload = item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: -1L
            }
        }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterUrl = episode.url, hosterName = "YouTube"),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> = withContext(Dispatchers.IO) {
        initNewPipe()
        val info = StreamInfo.getInfo(youtube, hoster.hosterUrl)

        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedByDescending { it.averageBitrate }
            .take(2)
            .map { Track(it.content, "${it.format?.suffix ?: "audio"} ${it.averageBitrate}kbps") }

        val withSound = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { stream ->
                Video(
                    videoUrl = stream.content,
                    videoTitle = "${stream.resolution} (with sound)",
                )
            }

        val separate = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedByDescending { it.height }
            .map { stream ->
                Video(
                    videoUrl = stream.content,
                    videoTitle = "${stream.resolution} ${stream.format?.suffix ?: ""}".trim(),
                    audioTracks = audio,
                )
            }

        // Best quality first
        if (audio.isEmpty()) withSound else separate + withSound
    }

    // ===================== Unused (this source works without them) =====================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    companion object {
        private const val MAX_PAGES = 3
    }
}

/** Lets NewPipe's extractor use the app's own network client. Secure (HTTPS) connections only. */
private class OkDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val url = request.url()
        require(url.startsWith("https://")) { "Blocked non-HTTPS request" }

        val builder = Request.Builder().url(url)
        request.headers().forEach { (headerName, values) ->
            builder.removeHeader(headerName)
            values.forEach { builder.addHeader(headerName, it) }
        }

        val data = request.dataToSend()
        when (val method = request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method, (data ?: ByteArray(0)).toRequestBody())
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCAPTCHA challenge requested", url)
            }
            return NpResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }
}
