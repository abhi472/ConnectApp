package com.abhi.connectapp.model

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Device(
    val name: String?,
    val address: String,
    val type: String // e.g., Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC, Constants.CONNECTION_TYPE_BLE, Constants.CONNECTION_TYPE_WIFI_DIRECT
) : Parcelable // Added Parcelable for easier Intent passing
