package me.zhanghai.android.files.index

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.narad.search.lib.index.enumeration.ExcludedFolders
import com.narad.search.lib.index.provision.ProvisionState
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.linux.isLinuxPath

/** Meaning-based search for the file list. Results are local file paths under the current folder. */
object SemanticSearch {
    private const val TAG = "SemanticSearch"
    private const val TOP_K = 20

    /**
     * Strong and weak hits under [folder], best first, with snippets and matched words. Paths are
     * resolved file paths. Null when meaning search did not run: [folder] is not on local
     * storage, the model is not ready, or anything fails. Name search must never break.
     */
    fun search(folder: Path, query: String): SearchOutput? {
        val scope = if (folder.isLinuxPath) folder.toString() else null
        if (semanticScope(scope) == null || query.isBlank()) return null
        // No model yet: do not touch the engine (it would create an empty index file).
        if (IndexEngine.provisioner.stateFlow.value != ProvisionState.Ready) return null
        return try {
            val output = runBlocking { IndexEngine.searchController.search(query, TOP_K) }
            val uris = output.docs.map { it.filePath } + output.photos.map { it.filePath }
            val paths = resolvePaths(uris)
            val kept = scopeHits(uris.map { paths[it] }, scope, ExcludedFolders.userFolders(application))
                .toSet()
            fun scoped(uri: String) = paths[uri]?.takeIf { it in kept }
            output.copy(
                docs = output.docs.mapNotNull { row -> scoped(row.filePath)?.let { row.copy(filePath = it) } }
                    .distinctBy { it.filePath },
                photos = output.photos.mapNotNull { row -> scoped(row.filePath)?.let { row.copy(filePath = it) } }
                    .distinctBy { it.filePath }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Semantic search failed, name search only", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "Semantic search failed, name search only", e)
            null
        }
    }

    /** `content://media/.../<id>` (or a raw path) to its file path, one MediaStore query. */
    private fun resolvePaths(urisOrPaths: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val idToUri = HashMap<String, String>()
        for (item in urisOrPaths) {
            if (item.startsWith("content://")) {
                idToUri[ContentUris.parseId(Uri.parse(item)).toString()] = item
            } else {
                out[item] = item
            }
        }
        if (idToUri.isEmpty()) return out
        val ids = idToUri.keys.toList()
        application.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA),
            "${MediaStore.Files.FileColumns._ID} IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(1) ?: continue
                idToUri[cursor.getLong(0).toString()]?.let { out[it] = path }
            }
        }
        return out
    }
}

/**
 * Pure: the folder semantic search runs in, or null. Only shared storage volumes are indexed,
 * so the device root, app folders and remote or archive paths (null) get name search only.
 */
internal fun semanticScope(folder: String?): String? =
    folder?.takeIf { it.startsWith("/storage/") }?.trimEnd('/')

/**
 * Pure: keep resolved hits (null = did not resolve) that sit under [folder] on a `/`
 * boundary and are not excluded. Order kept, duplicates dropped.
 */
internal fun scopeHits(resolved: List<String?>, folder: String?, userExcluded: Collection<String>): List<String> {
    val scope = semanticScope(folder) ?: return emptyList()
    return resolved.asSequence()
        .filterNotNull()
        .filter { it.startsWith("$scope/") }
        .filterNot { ExcludedFolders.isExcluded(it, userExcluded) }
        .distinct()
        .toList()
}
