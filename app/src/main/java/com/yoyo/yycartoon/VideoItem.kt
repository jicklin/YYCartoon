package com.yoyo.yycartoon

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class VideoItem(
    val uri: Uri,
    val displayName: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(Uri::class.java.classLoader) ?: Uri.EMPTY,
        parcel.readString().orEmpty()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(uri, flags)
        parcel.writeString(displayName)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<VideoItem> {
            override fun createFromParcel(parcel: Parcel): VideoItem = VideoItem(parcel)

            override fun newArray(size: Int): Array<VideoItem?> = arrayOfNulls(size)
        }
    }
}
