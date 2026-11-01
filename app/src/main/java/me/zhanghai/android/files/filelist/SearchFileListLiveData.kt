/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import com.narad.search.lib.device.DeviceTierDetector
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.app.Analytics
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.index.SearchOutput
import me.zhanghai.android.files.index.SemanticSearch
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SearchFileListLiveData(
    private val path: Path,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    /** Meaning results of this search, set before the first postValue of each load (#19). */
    @Volatile
    var meaning: MeaningResults? = null
        private set

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        meaning = null
        value = Loading(emptyList())
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            try {
                // Index0: strong meaning matches first, then name matches. A file in both shows
                // once. Weak matches live only in [meaning] (Less related).
                val meaning = SemanticSearch.search(path, query)?.let { loadMeaning(it) }
                this.meaning = meaning
                meaning?.let { fileList += (it.docs + it.photos).map { hit -> hit.file } }
                if (Thread.interrupted()) {
                    return@submit
                }
                if (fileList.isNotEmpty() || meaning != null) {
                    postValue(Loading(fileList.toList()))
                }
                val semanticPathSet = fileList.mapTo(HashSet()) { it.path }
                path.search(query, INTERVAL_MILLIS) { paths: List<Path> ->
                    for (path in paths) {
                        if (path in semanticPathSet) {
                            continue
                        }
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        fileList.add(fileItem)
                    }
                    postValue(Loading(fileList.toList()))
                }
                // Sent once when the search ends. A cancelled search (interrupted) sends nothing.
                if (meaning != null && !Thread.currentThread().isInterrupted) {
                    Analytics.capture("meaning_search_used", meaningSearchProps(meaning.sections(fileList)))
                }
                postValue(Success(fileList))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    private fun loadMeaning(output: SearchOutput): MeaningResults {
        fun hit(file: String, snippet: String = "", terms: Set<String> = emptySet()) = try {
            SearchHit(Paths.get(file).loadFileItem(), snippet, terms)
        } catch (e: IOException) {
            // Stale index row (file moved or deleted).
            null
        }
        fun docs(strong: Boolean) = output.docs.filter { it.strong == strong }
            .mapNotNull { hit(it.filePath, it.snippet, it.matchedTerms) }
        fun photos(strong: Boolean) = output.photos.filter { it.strong == strong }
            .mapNotNull { hit(it.filePath) }
        return MeaningResults(
            path, query, docs(true), photos(true), docs(false), photos(false), output.docsCut,
            output.photosCut, DeviceTierDetector.detect(application).imageEmbeddingEnabled
        )
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
    }
}
