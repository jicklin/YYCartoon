package com.yoyo.yycartoon

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val showName: String = DEFAULT_SHOW,
    val season: String = DEFAULT_SEASON,
    val relativePath: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(Uri::class.java.classLoader) ?: Uri.EMPTY,
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty().ifEmpty { DEFAULT_SHOW },
        parcel.readString().orEmpty().ifEmpty { DEFAULT_SEASON },
        parcel.readString().orEmpty()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(uri, flags)
        parcel.writeString(displayName)
        parcel.writeString(showName)
        parcel.writeString(season)
        parcel.writeString(relativePath)
    }

    companion object {
        const val DEFAULT_SHOW = "未分组动画"
        const val DEFAULT_SEASON = "第1季"

        @JvmField
        val CREATOR = object : Parcelable.Creator<VideoItem> {
            override fun createFromParcel(parcel: Parcel): VideoItem = VideoItem(parcel)

            override fun newArray(size: Int): Array<VideoItem?> = arrayOfNulls(size)
        }
    }
}
