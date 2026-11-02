package me.zhanghai.android.files.filelist

import com.narad.search.lib.index.worker.IndexStage
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.index.IndexingStatus
import me.zhanghai.android.files.index.LESS_RELATED_MAX

// Search results screen (#19). The functions are pure and JVM tested in SearchSectionsTest.

internal enum class SearchChip { ALL, DOCUMENTS, PHOTOS, NAMES }

/** Rows under All before "See all". */
internal const val ALL_DOCS_MAX = 3
internal const val ALL_PHOTOS_MAX = 4
internal const val ALL_NAMES_MAX = 5
internal const val PHOTO_COLUMNS_ALL = 4
internal const val PHOTO_COLUMNS_CHIP = 3

/** Each file sits in one section only. Less related = [lessDocs] + [lessPhotos]. */
internal data class SearchSections<T>(
    val docs: List<T>,
    val photos: List<T>,
    val names: List<T>,
    val lessDocs: List<T>,
    val lessPhotos: List<T>,
    val docsCut: Boolean = false,
    val photosCut: Boolean = false,
) {
    val lessRelated: List<T> get() = lessDocs + lessPhotos
    val isEmpty: Boolean
        get() = docs.isEmpty() && photos.isEmpty() && names.isEmpty() && lessDocs.isEmpty() &&
            lessPhotos.isEmpty()
}

/**
 * Pure: put each hit in one section. Precedence: strong meaning, then name, then weak meaning,
 * so a file that matches by name never hides under the folded Less related. Less related keeps
 * [LESS_RELATED_MAX] rows at most, documents first.
 */
internal fun <T, K> searchSections(
    strongDocs: List<T>,
    strongPhotos: List<T>,
    names: List<T>,
    weakDocs: List<T>,
    weakPhotos: List<T>,
    key: (T) -> K,
    docsCut: Boolean = false,
    photosCut: Boolean = false,
): SearchSections<T> {
    val claimed = HashSet<K>()
    fun List<T>.claim() = filter { claimed.add(key(it)) }
    val docs = strongDocs.claim()
    val photos = strongPhotos.claim()
    val nameRows = names.claim()
    val lessDocs = weakDocs.claim().take(LESS_RELATED_MAX)
    val lessPhotos = weakPhotos.claim().take(LESS_RELATED_MAX - lessDocs.size)
    return SearchSections(docs, photos, nameRows, lessDocs, lessPhotos, docsCut, photosCut)
}

/**
 * Pure: chip count text. "+" only when the engine cut its raw list for that kind at the cap, and
 * never on 0: a cut list of weak hits only would read "0+".
 */
internal fun countText(rows: Int, cut: Boolean): String =
    if (cut && rows > 0) "$rows+" else rows.toString()

/** Pure: the chips in order with their count text (none on All). No Photos chip without photo search. */
internal fun searchChips(sections: SearchSections<*>, photosAvailable: Boolean): List<Pair<SearchChip, String?>> =
    buildList {
        add(SearchChip.ALL to null)
        add(SearchChip.DOCUMENTS to countText(sections.docs.size, sections.docsCut))
        if (photosAvailable) {
            add(SearchChip.PHOTOS to countText(sections.photos.size, sections.photosCut))
        }
        // Names stream in from the disk walk: a live count with no cap.
        add(SearchChip.NAMES to sections.names.size.toString())
    }

internal sealed class SearchRow<out T> {
    data class Progress(val done: Int, val total: Int) : SearchRow<Nothing>()
    data class Chips(val chips: List<Pair<SearchChip, String?>>, val selected: SearchChip) :
        SearchRow<Nothing>()
    /** [count] shows under a chip; null under All, which shows "See all" instead. */
    data class Title(val section: SearchChip, val count: String?) : SearchRow<Nothing>()
    data class Doc<T>(val item: T) : SearchRow<T>()
    data class LessHeader(val count: Int, val open: Boolean) : SearchRow<Nothing>()
    data class Weak<T>(val item: T) : SearchRow<T>()
}

/**
 * The screen in the ConcatAdapter order: [head] rows, the [photos] grid, [mid] rows, the [names]
 * (upstream FileListAdapter), then [tail] rows.
 */
internal data class SearchPage<T>(
    val head: List<SearchRow<T>>,
    val photos: List<T>,
    val photoColumns: Int,
    val mid: List<SearchRow<T>>,
    val names: List<T>,
    val tail: List<SearchRow<T>>,
)

/**
 * Pure: the rows for [selected]. Fixed section order: Documents, Photos, Names, Less related.
 * Under All: 3 documents, 4 photo tiles, 5 names, Less related folded unless [lessOpen].
 * Chips hide when nothing matched. [progress] is (done, total) while indexing runs.
 */
internal fun <T> searchPage(
    sections: SearchSections<T>,
    chips: List<Pair<SearchChip, String?>>,
    selected: SearchChip,
    lessOpen: Boolean,
    progress: Pair<Int, Int>?,
): SearchPage<T> {
    val head = mutableListOf<SearchRow<T>>()
    progress?.let { head += SearchRow.Progress(it.first, it.second) }
    if (sections.isEmpty) {
        return SearchPage(head, emptyList(), PHOTO_COLUMNS_ALL, emptyList(), emptyList(), emptyList())
    }
    val chip = selected.takeIf { s -> chips.any { it.first == s } } ?: SearchChip.ALL
    head += SearchRow.Chips(chips, chip)
    val all = chip == SearchChip.ALL
    fun shows(section: SearchChip, rows: List<T>) = (all || chip == section) && rows.isNotEmpty()
    fun title(section: SearchChip) =
        SearchRow.Title(section, if (all) null else chips.first { it.first == section }.second)

    if (shows(SearchChip.DOCUMENTS, sections.docs)) {
        head += title(SearchChip.DOCUMENTS)
        head += (if (all) sections.docs.take(ALL_DOCS_MAX) else sections.docs).map { SearchRow.Doc(it) }
    }
    val photos = if (shows(SearchChip.PHOTOS, sections.photos)) {
        head += title(SearchChip.PHOTOS)
        if (all) sections.photos.take(ALL_PHOTOS_MAX) else sections.photos
    } else {
        emptyList()
    }
    val mid = mutableListOf<SearchRow<T>>()
    val names = if (shows(SearchChip.NAMES, sections.names)) {
        mid += title(SearchChip.NAMES)
        if (all) sections.names.take(ALL_NAMES_MAX) else sections.names
    } else {
        emptyList()
    }
    val less = when (chip) {
        SearchChip.ALL -> sections.lessRelated
        SearchChip.DOCUMENTS -> sections.lessDocs
        SearchChip.PHOTOS -> sections.lessPhotos
        SearchChip.NAMES -> emptyList()
    }
    val tail = mutableListOf<SearchRow<T>>()
    if (less.isNotEmpty()) {
        tail += SearchRow.LessHeader(less.size, lessOpen)
        if (lessOpen) tail += less.map { SearchRow.Weak(it) }
    }
    val columns = if (all) PHOTO_COLUMNS_ALL else PHOTO_COLUMNS_CHIP
    return SearchPage(head, photos, columns, mid, names, tail)
}

/**
 * Pure: whether a file picker ([pickOptions]) may return this file. The same rule as the upstream
 * FileListAdapter: files of a requested type only, or folders when picking a folder.
 */
internal fun pickAccepts(pickOptions: PickOptions?, isDirectory: Boolean, mimeType: MimeType): Boolean {
    pickOptions ?: return true
    return when (pickOptions.mode) {
        PickOptions.Mode.OPEN_FILE, PickOptions.Mode.CREATE_FILE ->
            !isDirectory && pickOptions.mimeTypes.any { it.match(mimeType) }
        PickOptions.Mode.OPEN_DIRECTORY -> isDirectory
    }
}

/** Pure: the only properties of `meaning_search_used`. Counts only: no words, paths or names. */
internal fun meaningSearchProps(sections: SearchSections<*>): Map<String, Any> = mapOf(
    "doc_count" to sections.docs.size,
    "photo_count" to sections.photos.size,
    "name_count" to sections.names.size,
    "less_related_count" to sections.lessDocs.size + sections.lessPhotos.size,
)

/** Pure: (done, total) for "Reading your files · N of M", or null when no reading runs. */
internal fun readingProgress(status: IndexingStatus): Pair<Int, Int>? {
    val stage = status.stage as? IndexStage.Reading ?: return null
    return if (status.running && stage.total > 0) stage.done to stage.total else null
}

// ---- Meaning results of one search (held by SearchFileListLiveData) ----

/** One row on the search screen: the file, plus its snippet and matched words (documents only). */
class SearchHit(
    val file: FileItem,
    val snippet: String = "",
    val matchedTerms: Set<String> = emptySet(),
)

/**
 * Meaning results of one search, keyed by [path] and [query]. The fragment ignores them when the
 * key does not match the current search.
 */
class MeaningResults(
    val path: Path,
    val query: String,
    val docs: List<SearchHit>,
    val photos: List<SearchHit>,
    val weakDocs: List<SearchHit>,
    val weakPhotos: List<SearchHit>,
    val docsCut: Boolean,
    val photosCut: Boolean,
    val photosAvailable: Boolean,
) {
    internal fun sections(names: List<FileItem>, keep: (FileItem) -> Boolean = { true }) =
        searchSections(
            docs.filter { keep(it.file) }, photos.filter { keep(it.file) },
            names.filter(keep).map { SearchHit(it) },
            weakDocs.filter { keep(it.file) }, weakPhotos.filter { keep(it.file) },
            { it.file.path }, docsCut, photosCut,
        )
}
