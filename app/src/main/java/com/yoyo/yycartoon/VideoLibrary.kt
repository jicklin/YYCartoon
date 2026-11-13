package com.yoyo.yycartoon

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.text.Collator
import java.util.Locale

enum class WatchMode {
    NORMAL,
    MINI,
    RANDOM
}

object VideoLibrary {
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts")

    fun load(context: Context, treeUri: Uri): List<VideoItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val videos = mutableListOf<VideoItem>()
        collectVideos(root, videos, 0)
        val order = naturalOrder()
        return videos.sortedWith { left, right ->
            order.compare(left.displayName, right.displayName)
        }
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
        depth: Int
    ) {
        if (depth > 6) return
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectVideos(file, output, depth + 1)
                file.isFile && file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in videoExtensions -> {
                    file.name?.let { name ->
                        output.add(VideoItem(file.uri, name.removeExtension()))
                    }
                }
            }
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
