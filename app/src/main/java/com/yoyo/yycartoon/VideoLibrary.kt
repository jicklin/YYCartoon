package com.yoyo.yycartoon

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

object VideoLibrary {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts")
    private const val INDEX_FILE_NAME = ".yycartoon-index.db"
    private const val INDEX_SCHEMA_VERSION = 2

    data class IndexResult(
        val videos: List<VideoItem>,
        val shows: List<String>,
        val fromCache: Boolean,
        val savedToUsb: Boolean
    )

    fun load(context: Context, treeUri: Uri, forceRebuild: Boolean = false): IndexResult {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyResult(false)
        if (!root.isDirectory) return emptyResult(false)

        if (!forceRebuild) {
            val localFile = localIndexFile(context, treeUri)
            readIndex(localFile, treeUri)?.let { return it }
            if (importUsbIndex(context, root, localFile)) {
                readIndex(localFile, treeUri)?.let { return it }
            }
        }
        return rebuildIndex(context, treeUri, root)
    }

    fun loadFromMediaStore(context: Context): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA
            )
        }
        val videos = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val displayName = cursor.getString(1)?.removeExtension() ?: continue
                val rawFolder = cursor.getString(2).orEmpty()
                val folderPath = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    rawFolder.substringBeforeLast("/")
                } else {
                    rawFolder
                }
                val folders = folderPath
                    .trimEnd('/', File.separatorChar)
                    .split('/', File.separatorChar)
                    .filter { it.isNotBlank() }
                val (showName, season) = when {
                    folders.size >= 2 -> folders.takeLast(2).let { it[0] to it[1] }
                    folders.size == 1 -> folders[0] to VideoItem.DEFAULT_SEASON
                    else -> VideoItem.DEFAULT_SHOW to VideoItem.DEFAULT_SEASON
                }
                videos.add(
                    VideoItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = displayName,
                        showName = showName,
                        season = season,
                        relativePath = folderPath + displayName
                    )
                )
            }
        }
        return sortVideos(videos)
    }

    fun seasonsFor(videos: List<VideoItem>, showName: String): List<String> {
        return videos.asSequence()
            .filter { it.showName == showName }
            .map { it.season }
            .distinct()
            .sortedWith(naturalOrder())
            .toList()
    }

    fun episodesFor(
        videos: List<VideoItem>,
        showName: String,
        season: String? = null
    ): List<VideoItem> {
        return videos.filter { video ->
            video.showName == showName && (season == null || video.season == season)
        }
    }

    fun playlistFrom(videos: List<VideoItem>, startVideo: VideoItem): List<VideoItem> {
        val orderedShow = episodesFor(videos, startVideo.showName)
        val startIndex = orderedShow.indexOfFirst { it.uri == startVideo.uri }
        if (startIndex < 0) return listOf(startVideo)
        return orderedShow.drop(startIndex)
    }

    private fun emptyResult(savedToUsb: Boolean): IndexResult {
        return IndexResult(emptyList(), emptyList(), false, savedToUsb)
    }

    private fun rebuildIndex(context: Context, treeUri: Uri, root: DocumentFile): IndexResult {
        val videos = mutableListOf<VideoItem>()
        collectVideos(root, videos, 0)
        val sorted = sortVideos(videos)
        val localFile = writeIndex(context, treeUri, sorted)
        val savedToUsb = exportIndexToUsb(context, root, localFile)
        return IndexResult(sorted, showsFor(sorted), false, savedToUsb)
    }

    private fun collectVideos(
        directory: DocumentFile,
        output: MutableList<VideoItem>,
        depth: Int,
        showName: String = VideoItem.DEFAULT_SHOW,
        season: String = VideoItem.DEFAULT_SEASON,
        parentPath: String = ""
    ) {
        if (depth > 6) return
        directory.listFiles().forEach { file ->
            val name = file.name.orEmpty()
            when {
                file.isDirectory -> {
                    val nextShow = if (depth == 0) name.ifEmpty { showName } else showName
                    val nextSeason = if (depth == 1) name.ifEmpty { season } else season
                    collectVideos(
                        directory = file,
                        output = output,
                        depth = depth + 1,
                        showName = nextShow,
                        season = nextSeason,
                        parentPath = parentPath + name + "/"
                    )
                }
                file.isFile && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions -> {
                    output.add(
                        VideoItem(
                            uri = file.uri,
                            displayName = name.removeExtension(),
                            showName = showName,
                            season = season,
                            relativePath = parentPath + name
                        )
                    )
                }
            }
        }
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
            check(temporaryFile.renameTo(target)) { "Cannot replace local index" }
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
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL(
                """
                CREATE TABLE videos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    show_name TEXT NOT NULL,
                    season TEXT NOT NULL,
                    relative_path TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    uri TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX index_videos_show_season ON videos(show_name, season)")
            database.beginTransaction()
            val statement = database.compileStatement(
                "INSERT INTO videos(show_name, season, relative_path, display_name, uri) VALUES(?, ?, ?, ?, ?)"
            )
            videos.forEach { video ->
                statement.bindString(1, video.showName)
                statement.bindString(2, video.season)
                statement.bindString(3, video.relativePath)
                statement.bindString(4, video.displayName)
                statement.bindString(5, video.uri.toString())
                statement.executeInsert()
            }
            database.setTransactionSuccessful()
            database.endTransaction()
            database.execSQL(
                """
                CREATE TABLE metadata (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL,
                    tree_uri TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                "INSERT INTO metadata(id, schema_version, tree_uri, updated_at) VALUES(1, ?, ?, ?)",
                arrayOf(INDEX_SCHEMA_VERSION, treeUri.toString(), System.currentTimeMillis().toString())
            )
            database.version = INDEX_SCHEMA_VERSION
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
                if (database.version != INDEX_SCHEMA_VERSION) return@use null
                val metadata = database.rawQuery(
                    "SELECT tree_uri FROM metadata WHERE id = 1",
                    null
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0)
                }
                if (metadata != treeUri.toString()) return@use null
                val videos = database.rawQuery(
                    "SELECT uri, display_name, show_name, season, relative_path FROM videos",
                    null
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toVideoItem())
                    }
                }
                IndexResult(sortVideos(videos), showsFor(videos), true, true)
            }
        }.getOrNull()
    }

    private fun showsFor(videos: List<VideoItem>): List<String> {
        return videos.map { it.showName }.distinct().sortedWith(naturalOrder())
    }

    private fun Cursor.toVideoItem(): VideoItem {
        return VideoItem(
            uri = Uri.parse(getString(0) ?: Uri.EMPTY.toString()),
            displayName = getString(1).orEmpty(),
            showName = getString(2).orEmpty().ifEmpty { VideoItem.DEFAULT_SHOW },
            season = getString(3).orEmpty().ifEmpty { VideoItem.DEFAULT_SEASON },
            relativePath = getString(4).orEmpty()
        )
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
            val showResult = order.compare(left.showName, right.showName)
            if (showResult != 0) {
                showResult
            } else {
                val seasonResult = order.compare(left.season, right.season)
                if (seasonResult != 0) seasonResult else order.compare(left.displayName, right.displayName)
            }
        }
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
