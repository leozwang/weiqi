package com.cwave.weiqi

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import java.io.File

class WeiqiApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    DynamicColors.applyToActivitiesIfAvailable(this)
    copySystemOpenCL()
  }

  private fun copySystemOpenCL() {
    val destFile = File(filesDir, "libOpenCL.so")
    if (destFile.exists()) {
      Log.i("WeiqiApplication", "libOpenCL.so already exists in app files directory.")
      return
    }

    val sources = listOf(
      "/vendor/lib64/libOpenCL.so",
      "/system/vendor/lib64/libOpenCL.so",
      "/system/lib64/libOpenCL.so",
      "/vendor/lib/libOpenCL.so"
    )

    for (sourcePath in sources) {
      val srcFile = File(sourcePath)
      if (srcFile.exists() && srcFile.canRead()) {
        try {
          srcFile.inputStream().use { input ->
            destFile.outputStream().use { output ->
              input.copyTo(output)
            }
          }
          Log.i("WeiqiApplication", "Successfully copied $sourcePath to ${destFile.absolutePath}")
          break
        } catch (e: Exception) {
          Log.e("WeiqiApplication", "Failed to copy $sourcePath", e)
        }
      }
    }
  }
}
