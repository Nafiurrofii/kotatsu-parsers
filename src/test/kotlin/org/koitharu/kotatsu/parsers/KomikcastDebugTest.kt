package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import kotlin.time.Duration.Companion.minutes

internal class KomikcastDebugTest {

    private val context = MangaLoaderContextMock
    private val timeout = 2.minutes

    /**
     * Debug test: Komikcast - getList()
     * Fokus pada validasi dasar list manga tanpa ParameterizedTest.
     */
    @Test
    fun `debug - komikcast getList returns non-empty manga list`() =
        runTest(timeout = timeout) {

        val source = MangaParserSource.KOMIKCAST
        val parser = context.newParserInstance(source)

        println("═══════════════════════════════════════")
        println("🔍 [DEBUG] Source   : ${source.name}")
        println("🔍 [DEBUG] Domain   : ${parser.domain}")
        println("═══════════════════════════════════════")

        // ── 1. Fetch list ──────────────────────────────────
        val list = try {
            parser.getList(MangaSearchQuery.EMPTY).also {
                println("✅ [DEBUG] getList() sukses, size = ${it.size}")
            }
        } catch (e: Exception) {
            println("❌ [DEBUG] getList() EXCEPTION:")
            println("   Type    : ${e::class.simpleName}")
            println("   Message : ${e.message}")
            e.printStackTrace()
            // Diagnosis otomatis
            diagnoseParseFail(e)
            throw e
        }

        // ── 2. Validasi tidak kosong ────────────────────────
        assert(list.isNotEmpty()) {
            """
            ❌ List KOSONG – kemungkinan penyebab:
               [a] Endpoint URL salah atau berubah
               [b] CSS selector tidak cocok dengan HTML terbaru
               [c] Response bukan HTML (misal: JSON, redirect, Cloudflare wall)
            Domain: ${parser.domain}
            """.trimIndent()
        }

        // ── 3. Tampilkan sample manga ───────────────────────
        val sampleSize = minOf(5, list.size)
        println("\n📋 [DEBUG] Sample $sampleSize dari ${list.size} manga:")
        println("─────────────────────────────────────────────────────")
        list.take(sampleSize).forEachIndexed { index, manga ->
            println("  [${index + 1}] Title : ${manga.title}")
            println("       URL   : ${manga.url}")
            println("       Cover : ${manga.coverUrl ?: "(null)"}")
            println("       PubURL: ${manga.publicUrl}")
            println()
        }

        // ── 4. Validasi per item ────────────────────────────
        var failCount = 0
        list.forEachIndexed { i, manga ->

            // Title tidak boleh kosong
            if (manga.title.isBlank()) {
                println("⚠️  [DEBUG] Item #$i title KOSONG (url=${manga.url})")
                failCount++
            }

            // URL harus relative (tidak absolute)
            if (manga.url.isEmpty()) {
                println("⚠️  [DEBUG] Item #$i url KOSONG")
                failCount++
            }
            if (manga.url.startsWith("http")) {
                println("⚠️  [DEBUG] Item #$i url bukan relative: ${manga.url}")
                failCount++
            }

            // publicUrl harus absolute
            if (!manga.publicUrl.startsWith("http")) {
                println("⚠️  [DEBUG] Item #$i publicUrl tidak absolute: ${manga.publicUrl}")
                failCount++
            }

            // Source harus cocok
            if (manga.source != source) {
                println("⚠️  [DEBUG] Item #$i source mismatch: ${manga.source}")
                failCount++
            }
        }

        println("─────────────────────────────────────────────────────")
        println("📊 [DEBUG] Total item : ${list.size}")
        println("📊 [DEBUG] Fail count : $failCount")
        println("═══════════════════════════════════════")

        assert(failCount == 0) {
            "❌ Terdapat $failCount item yang gagal validasi – lihat log di atas"
        }
    }

    // ─── Helper: diagnosis error ───────────────────────────
    private fun diagnoseParseFail(e: Exception) {
        println("\n🩺 [DIAGNOSIS]")
        when {
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                println("   → Domain tidak dapat diresolve. Cek koneksi / domain parser.")

            e.message?.contains("404") == true ||
            e.message?.contains("not found", ignoreCase = true) == true ->
                println("   → 404 Not Found. Endpoint URL berubah, cek getListPage().")

            e.message?.contains("403") == true ||
            e.message?.contains("cloudflare", ignoreCase = true) == true ->
                println("   → 403 / Cloudflare block. Parser memerlukan header / cookie khusus.")

            e is NullPointerException || e is NoSuchElementException ->
                println("   → NPE/NoSuchElement. CSS selector di parseMangaList() tidak menemukan elemen.")

            e.message?.contains("JSON", ignoreCase = true) == true ->
                println("   → Response JSON bukan HTML. Mungkin API endpoint atau redirect.")

            else ->
                println("   → Error tidak dikenali. Periksa stack trace di atas.")
        }
    }
}