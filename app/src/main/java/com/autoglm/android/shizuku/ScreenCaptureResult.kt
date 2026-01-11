/*
 * Copyright (C) 2024 AutoGLM
 *
 * Screen capture result parcelable.
 * Inspired by scrcpy project (Apache License 2.0)
 * https://github.com/Genymobile/scrcpy
 */

package com.autoglm.android.shizuku

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

data class ScreenCaptureResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val imageData: ByteArray?,
    val errorMessage: String?
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        success = parcel.readInt() != 0,
        width = parcel.readInt(),
        height = parcel.readInt(),
        rotation = parcel.readInt(),
        imageData = parcel.createByteArray(),
        errorMessage = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (success) 1 else 0)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(rotation)
        parcel.writeByteArray(imageData)
        parcel.writeString(errorMessage)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ScreenCaptureResult
        if (success != other.success) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (rotation != other.rotation) return false
        if (imageData != null) {
            if (other.imageData == null) return false
            if (!imageData.contentEquals(other.imageData)) return false
        } else if (other.imageData != null) return false
        if (errorMessage != other.errorMessage) return false
        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotation
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }

    companion object CREATOR : Parcelable.Creator<ScreenCaptureResult> {
        override fun createFromParcel(parcel: Parcel): ScreenCaptureResult {
            return ScreenCaptureResult(parcel)
        }

        override fun newArray(size: Int): Array<ScreenCaptureResult?> {
            return arrayOfNulls(size)
        }
        
        fun success(width: Int, height: Int, rotation: Int, imageData: ByteArray): ScreenCaptureResult {
            return ScreenCaptureResult(
                success = true,
                width = width,
                height = height,
                rotation = rotation,
                imageData = imageData,
                errorMessage = null
            )
        }
        
        fun error(message: String): ScreenCaptureResult {
            return ScreenCaptureResult(
                success = false,
                width = 0,
                height = 0,
                rotation = 0,
                imageData = null,
                errorMessage = message
            )
        }
    }
}
