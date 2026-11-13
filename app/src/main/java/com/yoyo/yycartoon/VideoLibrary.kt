package com.yoyo.yycartoon

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

enum class WatchMode {
    NORMAL,
    MINI,
    RANDOM
}

object VideoLibrary {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts")
    private const val INDEX_FILE_NAME = ".yycartoon-index.db"
    private const val INDEX_SCHEMA_VERSION = 1

    data class IndexResult(
        val videos: List<VideoItem>,
        val seasons: List<String>,
        val fromCache: Boolean,
        val savedToUsb: Boolean
    )

    fun load(context: Context, treeUri: Uri, forceRebuild: Boolean = false): IndexResult {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyResult(savedToUsb = false)
        if (!root.isDirectory) return emptyResult(savedToUsb = false)

        if (!forceRebuild) {
            val localFile = localIndexFile(context, treeUri)
            readIndex(localFile, treeUri)?.let { return it }
            if (importUsbIndex(context, root, localFile)) {
                readIndex(localFile, treeUri)?.let { return it }
            }
        }

        return rebuildIndex(context, treeUri, root)
    }

    private fun emptyResult(savedToUsb: Boolean): IndexResult {
        return IndexResult(
            videos = emptyList(),
            seasons = emptyList(),
            fromCache = false,
            savedToUsb = savedToUsb
        )
    }

    private fun rebuildIndex(context: Context, treeUri: Uri, root: DocumentFile): IndexResult {
        val videos = mutableListOf<VideoItem>()
        collectVideos(root, videos, 0)
        val localFile = writeIndex(context, treeUri, videos)
        val savedToUsb = exportIndexToUsb(context, root, localFile)
        return IndexResult(
            videos = sortVideos(videos),
            seasons = videos.map { it.season }.distinct().sortedWith(naturalOrder()),
            fromCache = false,
            savedToUsb = savedToUsb
        )
    }

    private fun importUsbIndex(context: Context, root: DocumentFile, target: File): Boolean {
        val indexFile = root.listFiles().firstOrNull { it.isFile && it.name == INDEX_FILE_NAME }
            ?: return false
        val temporaryFile = File(target.parentFile, target.name + ".importing")
        return runCatching {
            temporaryFile.outputStream().use { output ->
                context.contentResolver.openInputStream(indexFile.uri)?.use { input ->
                    input.copyTo(output)
                } ?: error("Cannot open USB index")
            }
            if (temporaryFile.length() == 0L) error("USB index is empty")
            target.delete()
            if (!temporaryFile.renameTo(target)) error("Cannot replace local index")
            true
        }.getOrElse {
            temporaryFile.delete()
            false
        }
    }

    private fun writeIndex(context: Context, treeUri: Uri, videos: List<VideoItem>): File {
        val databaseFile = localIndexFile(context, treeUri)
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        database.use {
            it.execSQL(
                """
                CREATE TABLE videos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    season TEXT NOT NULL,
                    relative_path TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    uri TEXT NOT NULL
                )
                """.trimIndent()
            )
            it.execSQL("CREATE INDEX index_videos_season ON videos(season)")
            it.beginTransaction()
            val statement = it.compileStatement(
                "INSERT OR REPLACE INTO videos(season, relative_path, display_name, uri) VALUES(?, ?, ?, ?)"
            )
            videos.forEach { video ->
                statement.bindString(1, video.season)
                statement.bindString(2, video.relativePath)
                statement.bindString(3, video.displayName)
                statement.bindString(4, video.uri.toString())
                statement.executeInsert()
            }
            it.setTransactionSuccessful()
            it.endTransaction()
            it.execSQL(
                """
                CREATE TABLE metadata (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL,
                    tree_uri TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            it.execSQL(
                """
                INSERT INTO metadata(id, schema_version, tree_uri, updated_at)
                VALUES(1, $INDEX_SCHEMA_VERSION, '${treeUri.toString().replace("'", "''")}', ${System.currentTimeMillis()})
                """.trimIndent()
            )
            it.execSQL("PRAGMA user_version = $INDEX_SCHEMA_VERSION")
        }
        return databaseFile
    }

    private fun exportIndexToUsb(context: Context, root: DocumentFile, databaseFile: File): Boolean {
        return runCatching {
            val existing = root.listFiles().firstOrNull { it.isFile && it.name == INDEX_FILE_NAME }
            val target = existing ?: root.createFile("application/x-sqlite3", INDEX_FILE_NAME)
                ?: error("Cannot create USB index")
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                databaseFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Cannot write USB index")
            true
        }.getOrDefault(false)
    }

    private fun readIndex(databaseFile: File, treeUri: Uri): IndexResult? {
        if (!databaseFile.isFile) return null
        return runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { database ->
                if (database.getVersion() != INDEX_SCHEMA_VERSION) return@use null
                val metadata = database.rawQuery(
                    "SELECT tree_uri FROM metadata WHERE id = 1",
                    null
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0)
                }
                if (metadata != treeUri.toString()) return@use null
                val videos = database.rawQuery(
                    "SELECT uri, display_name, season, relative_path FROM videos",
                    null
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.toVideoItem())
                        }
                    }
                }
                IndexResult(
                    videos = sortVideos(videos),
                    seasons = videos.map { it.season }.distinct().sortedWith(naturalOrder()),
                    fromCache = true,
                    savedToUsb = true
                )
            }
        }.getOrNull()
    }

    private fun Cursor.toVideoItem(): VideoItem {
        return VideoItem(
            uri = Uri.parse(getString(0) ?: Uri.EMPTY.toString()),
            displayName = getString(1).orEmpty(),
            season = getString(2).orEmpty().ifEmpty { VideoItem.DEFAULT_SEASON },
            relativePath = getString(3).orEmpty()
        )
    }

    fun loadFromMediaStore(context: Context): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME
        )
        val videos = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn) ?: continue
                videos.add(
                    VideoItem(
                        ContentUris.withAppendedId(collection, id),
                        displayName.removeExtension()
                    )
                )
            }
        }
        val order = naturalOrder()
        return videos.sortedWith { left, right ->
            order.compare(left.displayName, right.displayName)
        }
    }

    private fun collectVideos(
        directory: DocumentFile,
        output: MutableList<VideoItem>,
        depth: Int,
        season: String = VideoItem.DEFAULT_SEASON,
        parentPath: String = ""
    ) {
        if (depth > 6) return
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectVideos(
                    directory = file,
                    output = output,
                    depth = depth + 1,
                    season = if (depth == 0) file.name.orEmpty().ifEmpty { season } else season,
                    parentPath = parentPath + (file.name ?: "") + "/"
                )
                file.isFile && file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in videoExtensions -> {
                    file.name?.let { name ->
                        output.add(
                            VideoItem(
                                uri = file.uri,
                                displayName = name.removeExtension(),
                                season = season,
                                relativePath = parentPath + name
                            )
                        )
                    }
                }
            }
        }
    }

    private fun localIndexFile(context: Context, treeUri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(treeUri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(20)
        return File(context.cacheDir, "video-index-$digest.db")
    }

    private fun sortVideos(videos: List<VideoItem>): List<VideoItem> {
        val order = naturalOrder()
        return videos.sortedWith { left, right ->
            val seasonResult = order.compare(left.season, right.season)
            if (seasonResult != 0) seasonResult else order.compare(left.displayName, right.displayName)
        }
    }

    fun filterByMode(videos: List<VideoItem>, mode: WatchMode): List<VideoItem> {
        return when (mode) {
            WatchMode.NORMAL -> videos.filterNot { it.isMiniEpisode() }
            WatchMode.MINI -> videos.filter { it.isMiniEpisode() }
            WatchMode.RANDOM -> videos
        }
    }

    fun buildPlaylist(
        videos: List<VideoItem>,
        mode: WatchMode,
        selectedCount: Int,
        isWatched: (VideoItem) -> Boolean
    ): List<VideoItem> {
        val candidates = filterByMode(videos, mode).filterNot(isWatched)
        return if (mode == WatchMode.RANDOM) {
            candidates.shuffled().take(selectedCount)
        } else {
            candidates.take(selectedCount)
        }
    }

    private fun VideoItem.isMiniEpisode(): Boolean {
        val lower = displayName.lowercase(Locale.ROOT)
        return listOf("迷你", "mini", "短片", "小小的").any { lower.contains(it) }
    }

    private fun String.removeExtension(): String {
        val dot = lastIndexOf('.')
        return if (dot > 0) substring(0, dot) else this
    }

    private fun naturalOrder(): Comparator<String> {
        val collator = Collator.getInstance(Locale.CHINA)
        return Comparator { left, right ->
            var leftIndex = 0
            var rightIndex = 0
            while (leftIndex < left.length && rightIndex < right.length) {
                val leftChar = left[leftIndex]
                val rightChar = right[rightIndex]
                if (leftChar.isDigit() && rightChar.isDigit()) {
                    var leftEnd = leftIndex
                    while (leftEnd < left.length && left[leftEnd].isDigit()) leftEnd++
                    var rightEnd = rightIndex
                    while (rightEnd < right.length && right[rightEnd].isDigit()) rightEnd++
                    val leftNumber = left.substring(leftIndex, leftEnd).toLongOrNull() ?: 0L
                    val rightNumber = right.substring(rightIndex, rightEnd).toLongOrNull() ?: 0L
                    if (leftNumber != rightNumber) return@Comparator leftNumber.compareTo(rightNumber)
                    leftIndex = leftEnd
                    rightIndex = rightEnd
                } else {
                    val result = collator.compare(leftChar.toString(), rightChar.toString())
                    if (result != 0) return@Comparator result
                    leftIndex++
                    rightIndex++
                }
            }
            (left.length - leftIndex).compareTo(right.length - rightIndex)
        }
    }
}
