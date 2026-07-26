package com.example.scifilauncher

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.view.inputmethod.InputMethodManager

private const val PKG_DUCKDUCKGO = "com.duckduckgo.mobile.android"
private const val PKG_OPERA_MINI = "com.opera.mini.native"

/**
 * Opens a web page preferring DuckDuckGo, then Opera Mini, then whatever the
 * system resolves - these are the two browsers actually installed/used on this device.
 */
fun openUrlInPreferredBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    val triedPackages = listOf(PKG_DUCKDUCKGO, PKG_OPERA_MINI)
    for (pkg in triedPackages) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (opened) return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun showInputMethodPicker(context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showInputMethodPicker()
}

fun setFlashlight(context: Context, on: Boolean) {
    runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        cameraManager.setTorchMode(cameraId, on)
    }
}
