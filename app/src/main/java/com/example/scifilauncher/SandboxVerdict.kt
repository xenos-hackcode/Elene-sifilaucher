package com.example.scifilauncher

import android.os.Parcel
import android.os.Parcelable

data class SandboxVerdict(val passed: Boolean, val reason: String) : Parcelable {
    constructor(parcel: Parcel) : this(
        passed = parcel.readByte() != 0.toByte(),
        reason = parcel.readString().orEmpty()
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByte(if (passed) 1 else 0)
        dest.writeString(reason)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SandboxVerdict> {
        override fun createFromParcel(parcel: Parcel): SandboxVerdict = SandboxVerdict(parcel)
        override fun newArray(size: Int): Array<SandboxVerdict?> = arrayOfNulls(size)
    }
}
