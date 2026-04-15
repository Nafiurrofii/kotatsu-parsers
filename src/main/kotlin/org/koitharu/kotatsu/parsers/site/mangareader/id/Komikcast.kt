package org.koitharu.kotatsu.parsers.site.mangareader.id

import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KOMIKCAST, "v1.komikcast.fit", pageSize = 20, searchPageSize = 20) {

    private val apiDomain = "be.komikcast.cc"
    private val webDomain = "v1.komikcast.fit"

    override val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // TODO: [BLOCKED] Endpoint `/chapters/{id}` saat ini mereturn 401 Unauthorized.
        // Di sini nantinya Anda perlu menginjeksi header "Authorization: Bearer <...>"
        // setelah Anda mereverse-engineering autentikasi tamu/guest API SPA mereka.

        val newRequest = request.newBuilder()
            .header("Referer", "https://$webDomain/")
            .header("Origin", "https://$webDomain")
            .header("Accept", "application/json, text/plain, */*")
            .build()
            
        return chain.proceed(newRequest)
    }

    override val sourceLocale: Locale = Locale.ENGLISH
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        val preset = when (order) {
            SortOrder.POPULARITY -> "populer"
            else -> "rilisan_terbaru"
        }

        // Endpoint JSON untuk list komik: GET https://be.komikcast.cc/series
        val url = "https://$apiDomain/series?preset=$preset&take=${pageSize}&page=$page"
        val json = webClient.httpGet(url).parseJson()
        
        return parseMangaListFromJson(json)
    }

    private fun parseMangaListFromJson(json: JSONObject): List<Manga> {
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val mangas = mutableListOf<Manga>()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            val dataObj = item.getJSONObject("data")
            
            val slug = dataObj.getString("slug")
            val title = dataObj.getString("title")
            val coverUrl = dataObj.optString("coverImage", "")
            val rating = dataObj.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat()

            mangas.add(
                Manga(
                    id = generateUid(slug),
                    url = slug, // Disimpan sebagai slug agar endpoint detail mudah dicari
                    publicUrl = "https://$webDomain/komik/$slug",
                    title = title,
                    altTitles = emptySet(),
                    rating = rating,
                    contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                    coverUrl = coverUrl.takeIf { it.isNotEmpty() },
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
        }
        return mangas
    }

    override suspend fun getDetails(manga: Manga): Manga {
        // Slug sudah disimpan pada manga.url di getListPage.
        val slug = manga.url 
        val url = "https://$apiDomain/series/$slug"

        repeat(3) { attempt ->
            if (attempt > 0) delay(1000)

            val response = webClient.httpGet(url)
            if (!response.isSuccessful) return@repeat
            
            val rootData = response.parseJson().getJSONObject("data")
            val mangaId = rootData.getInt("id") // Manga ID dibutuhkan untuk list chapter
            val dataObj = rootData.getJSONObject("data")
            
            val title = dataObj.getString("title")
            val synopsis = dataObj.optString("synopsis", "")
            val author = dataObj.optString("author", "")
            val statusStr = dataObj.optString("status", "ongoing")
            val state = if (statusStr.equals("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
            
            // Generate tags dari JSON array 'genres'
            val genresArray = dataObj.optJSONArray("genres")
            val tagsMap = getOrCreateTagMap()
            val parsedTags = mutableSetOf<MangaTag>()
            
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    val genreObj = genresArray.getJSONObject(i).getJSONObject("data")
                    val genreName = genreObj.getString("name")
                    tagsMap[genreName]?.let { parsedTags.add(it) }
                }
            }

            // Memanggil Chapter List secara berantai via ID
            val chapters = getChapters(mangaId)

            return manga.copy(
                title = title,
                description = synopsis,
                state = state,
                authors = setOfNotNull(author.takeIf { it.isNotEmpty() }),
                tags = parsedTags,
                chapters = chapters
            )
        }
        throw Exception("Gagal mengekstrak JSON Detail Manga setelah 3 percobaan: $slug")
    }

    private suspend fun getChapters(mangaId: Int): List<MangaChapter> {
        val url = "https://$apiDomain/series/$mangaId/chapters"
        val response = webClient.httpGet(url)
        if (!response.isSuccessful) return emptyList()

        val chaptersArray = response.parseJson().optJSONArray("data") ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        
        for (i in 0 until chaptersArray.length()) {
            val chapterItem = chaptersArray.getJSONObject(i)
            val chapterId = chapterItem.getInt("id")
            val chapterData = chapterItem.getJSONObject("data")
            
            val index = chapterData.optDouble("index", (chaptersArray.length() - i).toDouble()).toFloat()
            val dateStr = chapterItem.optString("createdAt", "")

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterId.toString()),
                    title = "Chapter $index",
                    url = chapterId.toString(), // Disimpan sebagai ID Chapter agar bisa dpanggil di `getPages()`
                    number = index,
                    volume = 0,
                    scanlator = null,
                    uploadDate = parseIsoDate(dateStr),
                    branch = null,
                    source = source,
                )
            )
        }
        return chapters
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url
        val url = "https://$apiDomain/chapters/$chapterId"

        repeat(3) { attempt ->
            if (attempt > 0) delay(1000)

            val response = webClient.httpGet(url)
            
            if (response.code == 401 || response.code == 403) {
                // TODO: 401 UNAUTHORIZED CHECKPOINT
                // Harus ditangani dengan login bearer / network interceptor dari SPA.
                throw Exception("API membutuhkan Token/Header (Http ${response.code})")
            }

            if (!response.isSuccessful) return@repeat
            
            val json = response.parseJson().optJSONObject("data") ?: return@repeat
            
            // ASUMSI: Array JSON akan berada di properti "images" dengan array of strings
            val imagesArray = json.optJSONArray("images") ?: org.json.JSONArray()
            
            val pages = mutableListOf<MangaPage>()
            for (i in 0 until imagesArray.length()) {
                val imageUrl = imagesArray.getString(i)
                pages.add(
                    MangaPage(
                        id = generateUid(imageUrl),
                        url = imageUrl,
                        preview = null,
                        source = source
                    )
                )
            }
            if (pages.isNotEmpty()) return pages
        }
        return emptyList()
    }

    /** Helper custom untuk mengubah format tanggal ISO dari JSON */
    private fun parseIsoDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0
        return try {
            // ISO Date: "2026-04-15T09:24:16.098+07:00" -> Kita pangkas formatnya menjadi T00:00:00 untuk parser standar
            val cleanDate = dateStr.substringBefore("+").substringBeforeLast(".")
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
            format.parse(cleanDate)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
