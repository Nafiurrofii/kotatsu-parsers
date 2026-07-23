package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toStringSet
@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class KiryuuParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KIRYUU, "kiryuu.online", pageSize = 50, searchPageSize = 10) {

	override val listUrl = "/manga/"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/manga/").removeSuffix("/")
		val apiUrl = "https://$domain/api/manga/$slug"
		val json = webClient.httpGet(apiUrl).parseJson().getJSONObject("data")
		val info = json.getJSONObject("info")

		val chapters = info.getJSONArray("chapters").mapChapters(reversed = true) { index, element ->
			val chapterObject = element as JSONObject
			val chapterSlug = chapterObject.getString("slug")
			// Store as /read/[manga-slug]/[chapter-slug] to easily hit API in getPages
			val url = "/read/$slug/$chapterSlug"
			MangaChapter(
				id = generateUid(url),
				title = chapterObject.optString("title"),
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		val state = when (info.optString("status")) {
			"Ongoing" -> MangaState.ONGOING
			"Completed" -> MangaState.FINISHED
			else -> null
		}

		return manga.copy(
			title = info.optString("title").ifBlank { manga.title },
			description = info.optString("synopsis"),
			coverUrl = info.optString("coverImage"),
			state = state,
			authors = setOfNotNull(info.optString("author").nullIfEmpty()),
			tags = info.getJSONArray("genres").toStringSet().mapNotNullToSet { tag ->
				getOrCreateTagMap()[tag] ?: MangaTag(tag, tag.lowercase().replace(" ", "-"), source)
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val apiUrl = chapter.url.toAbsoluteUrl(domain).replace("/read/", "/api/read/")
		val json = webClient.httpGet(apiUrl).parseJson().getJSONObject("data")
		val readerImages = json.getJSONObject("chapter").getJSONArray("images")

		val pages = ArrayList<MangaPage>(readerImages.length())
		for (i in 0 until readerImages.length()) {
			val url = readerImages.getString(i)
			pages.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}
}
