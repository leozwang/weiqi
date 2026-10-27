package com.cwave.weiqi

import android.app.Application
import android.util.Log
import java.io.File

class WeiqiApplication : Application() {

  companion object {
    private const val TAG = "WeiqiApplication"

    /**
     * Checks if the device has OpenCL GPU libraries available.
     */
    fun hasSystemOpenCL(filesDir: File? = null): Boolean {
      if (filesDir != null && File(filesDir, "libOpenCL.so").exists()) return true
      val sources = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so"
      )
      return sources.any {
        val f = File(it)
        f.exists() && f.canRead()
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    copySystemOpenCL()
  }

  private fun copySystemOpenCL() {
    val destFile = File(filesDir, "libOpenCL.so")
    if (destFile.exists()) {
      Log.i(TAG, "libOpenCL.so already exists in app files directory.")
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
          Log.i(TAG, "Successfully copied $sourcePath to ${destFile.absolutePath}")
          break
        } catch (e: Exception) {
          Log.e(TAG, "Failed to copy $sourcePath", e)
        }
      }
    }
  }
}
