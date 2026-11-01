package me.zhanghai.android.files.filelist

import com.narad.search.lib.index.worker.IndexStage
import me.zhanghai.android.files.app.filterAnalyticsEvent
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.index.IndexingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSectionsTest {

    private fun sections(
        docs: List<String> = emptyList(),
        photos: List<String> = emptyList(),
        names: List<String> = emptyList(),
        weakDocs: List<String> = emptyList(),
        weakPhotos: List<String> = emptyList(),
        docsCut: Boolean = false,
        photosCut: Boolean = false,
    ) = searchSections(docs, photos, names, weakDocs, weakPhotos, { it }, docsCut, photosCut)

    // ---- B3 sections ----

    @Test
    fun `a name match never hides under Less related`() {
        val s = sections(names = listOf("elephant.txt"), weakDocs = listOf("elephant.txt", "zoo.txt"))
        assertEquals(listOf("elephant.txt"), s.names)
        assertEquals(listOf("zoo.txt"), s.lessRelated)
    }

    @Test
    fun `a strong meaning match wins over a name match`() {
        val s = sections(docs = listOf("a.pdf"), photos = listOf("b.jpg"), names = listOf("a.pdf", "b.jpg", "c.txt"))
        assertEquals(listOf("a.pdf"), s.docs)
        assertEquals(listOf("b.jpg"), s.photos)
        assertEquals(listOf("c.txt"), s.names)
    }

    @Test
    fun `each file shows in one section only`() {
        val s = sections(
            docs = listOf("a", "a"), photos = listOf("a", "p"), names = listOf("p", "n"),
            weakDocs = listOf("n", "w"), weakPhotos = listOf("w", "q"),
        )
        val all = s.docs + s.photos + s.names + s.lessRelated
        assertEquals(all.distinct(), all)
        assertEquals(listOf("a", "p", "n", "w", "q"), all)
    }

    @Test
    fun `less related keeps 10 rows at most, documents first`() {
        val s = sections(weakDocs = (1..8).map { "d$it" }, weakPhotos = (1..5).map { "p$it" })
        assertEquals(10, s.lessRelated.size)
        assertEquals(8, s.lessDocs.size)
        assertEquals(listOf("p1", "p2"), s.lessPhotos)
    }

    @Test
    fun `under All the caps are 3 documents, 4 photos, 5 names and less related is folded`() {
        val s = sections(
            docs = (1..6).map { "d$it" }, photos = (1..6).map { "p$it" },
            names = (1..9).map { "n$it" }, weakDocs = listOf("w"),
        )
        val page = searchPage(s, searchChips(s, true), SearchChip.ALL, lessOpen = false, progress = null)
        assertEquals(3, page.head.count { it is SearchRow.Doc })
        assertEquals(4, page.photos.size)
        assertEquals(PHOTO_COLUMNS_ALL, page.photoColumns)
        assertEquals(5, page.names.size)
        assertEquals(listOf(SearchRow.LessHeader(1, false)), page.tail)
        val titles = (page.head + page.mid).filterIsInstance<SearchRow.Title>()
        assertEquals(listOf(SearchChip.DOCUMENTS, SearchChip.PHOTOS, SearchChip.NAMES), titles.map { it.section })
        assertTrue(titles.all { it.count == null })
    }

    @Test
    fun `section order does not change while names stream in`() {
        fun kinds(s: SearchSections<String>): List<String> {
            val page = searchPage(s, searchChips(s, true), SearchChip.ALL, lessOpen = false, progress = null)
            return (page.head + page.mid + page.tail).mapNotNull {
                when (it) {
                    is SearchRow.Title -> it.section.name
                    is SearchRow.LessHeader -> "LESS"
                    else -> null
                }
            }
        }
        val weak = listOf("w1", "n2")
        assertEquals(listOf("DOCUMENTS", "LESS"), kinds(sections(docs = listOf("d1"), weakDocs = weak)))
        val after = sections(docs = listOf("d1"), names = listOf("n1", "n2"), weakDocs = weak)
        assertEquals(listOf("DOCUMENTS", "NAMES", "LESS"), kinds(after))
        assertEquals(listOf("w1"), after.lessRelated)
    }

    @Test
    fun `a chip shows its section in full with its less related rows`() {
        val s = sections(docs = (1..6).map { "d$it" }, photos = listOf("p"), weakDocs = listOf("wd"), weakPhotos = listOf("wp"))
        val docs = searchPage(s, searchChips(s, true), SearchChip.DOCUMENTS, lessOpen = true, progress = null)
        assertEquals(6, docs.head.count { it is SearchRow.Doc })
        assertEquals(SearchRow.Title(SearchChip.DOCUMENTS, "6"), docs.head[1])
        assertTrue(docs.photos.isEmpty())
        assertEquals(listOf(SearchRow.LessHeader(1, true), SearchRow.Weak("wd")), docs.tail)
        val photos = searchPage(s, searchChips(s, true), SearchChip.PHOTOS, lessOpen = false, progress = null)
        assertEquals(PHOTO_COLUMNS_CHIP, photos.photoColumns)
        assertEquals(listOf(SearchRow.LessHeader(1, false)), photos.tail)
    }

    @Test
    fun `no match hides the chips and keeps the progress line`() {
        val page = searchPage(sections(), searchChips(sections(), true), SearchChip.ALL, false, 231 to 405)
        assertEquals(listOf<SearchRow<String>>(SearchRow.Progress(231, 405)), page.head)
        assertTrue(page.mid.isEmpty() && page.tail.isEmpty() && page.names.isEmpty())
    }

    // ---- B4 / B5 counts and chips ----

    @Test
    fun `count adds a plus only when the engine list was cut`() {
        assertEquals("14", countText(14, false))
        assertEquals("14+", countText(14, true))
    }

    @Test
    fun `chips count the rows they show and names have no cap`() {
        val s = sections(
            docs = (1..14).map { "d$it" }, photos = listOf("p"), names = (1..40).map { "n$it" },
            weakDocs = listOf("w"), docsCut = true,
        )
        assertEquals(
            listOf(SearchChip.ALL to null, SearchChip.DOCUMENTS to "14+", SearchChip.PHOTOS to "1", SearchChip.NAMES to "40"),
            searchChips(s, photosAvailable = true),
        )
    }

    @Test
    fun `no photos chip without photo search, and a hidden chip falls back to All`() {
        val s = sections(docs = listOf("d"), photos = listOf("p"))
        val chips = searchChips(s, photosAvailable = false)
        assertFalse(chips.any { it.first == SearchChip.PHOTOS })
        val page = searchPage(s, chips, SearchChip.PHOTOS, false, null)
        assertEquals(SearchRow.Chips(chips, SearchChip.ALL), page.head[0])
    }

    // ---- B8 analytics ----

    @Test
    fun `meaning_search_used carries the four counts only`() {
        val s = sections(docs = listOf("/storage/a.pdf"), names = listOf("b", "c"), weakPhotos = listOf("d"))
        val props = meaningSearchProps(s)
        assertEquals(
            mapOf("doc_count" to 1, "photo_count" to 0, "name_count" to 2, "less_related_count" to 1),
            props,
        )
        val sent = filterAnalyticsEvent("meaning_search_used", props + ("query" to "elephant"))!!
        assertEquals(setOf("doc_count", "photo_count", "name_count", "less_related_count", "\$geoip_disable"), sent.keys)
    }

    // ---- Picker rule ----

    @Test
    fun `a picker gets only the file types it asked for`() {
        fun pick(mode: PickOptions.Mode, vararg types: String) =
            PickOptions(mode, null, true, types.map { MimeType(it) }, false, false)
        val png = pick(PickOptions.Mode.OPEN_FILE, "image/png")
        assertFalse(pickAccepts(png, false, MimeType("application/pdf")))
        assertTrue(pickAccepts(png, false, MimeType("image/png")))
        assertTrue(pickAccepts(pick(PickOptions.Mode.OPEN_FILE, "*/*"), false, MimeType("application/pdf")))
        assertFalse(pickAccepts(pick(PickOptions.Mode.OPEN_DIRECTORY), false, MimeType("application/pdf")))
        assertTrue(pickAccepts(null, false, MimeType("application/pdf")))
    }

    // ---- B7 progress ----

    @Test
    fun `progress shows only while reading runs`() {
        val reading = IndexStage.Reading("docs", 231, 405)
        assertEquals(231 to 405, readingProgress(IndexingStatus(stage = reading, running = true)))
        assertNull(readingProgress(IndexingStatus(stage = reading, running = false)))
        assertNull(readingProgress(IndexingStatus(stage = IndexStage.Reading("docs", 0, 0), running = true)))
        assertNull(readingProgress(IndexingStatus(stage = IndexStage.Done(4), running = true)))
    }
}
