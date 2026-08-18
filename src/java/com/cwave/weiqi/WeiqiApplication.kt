package com.cwave.weiqi

import android.app.Application
import android.os.Build
import android.util.Log
import com.google.android.material.color.DynamicColors
import java.io.File

class WeiqiApplication : Application() {

  companion object {
    private const val TAG = "WeiqiApplication"

    /**
     * Checks if the device is part of the Google Pixel 11 family powered by Tensor G6 (Malibu / SantaFe TPU).
     */
    fun isPixel11Family(): Boolean {
      val isGoogle = Build.MANUFACTURER.equals("Google", ignoreCase = true)
      if (!isGoogle) return false

      val hardware = Build.HARDWARE.lowercase()
      val device = Build.DEVICE.lowercase()
      val product = Build.PRODUCT.lowercase()
      val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.lowercase()
      } else {
        ""
      }

      // Pixel 11 series (Tensor G6 / Malibu codenames: malibu, mbu, santafe, cubs, grizzly, kodiak, yogi, pixel 11)
      val pixel11Codenames = listOf("malibu", "mbu", "santafe", "cubs", "grizzly", "kodiak", "yogi", "pixel 11", "tensor g6")
      return pixel11Codenames.any {
        hardware.contains(it) || device.contains(it) || product.contains(it) || socModel.contains(it)
      }
    }

    /**
     * Checks if the device is part of the Google Pixel 9 family powered by Tensor G4.
     */
    fun isPixel9Family(): Boolean {
      val isGoogle = Build.MANUFACTURER.equals("Google", ignoreCase = true)
      if (!isGoogle) return false

      val hardware = Build.HARDWARE.lowercase()
      val device = Build.DEVICE.lowercase()
      val product = Build.PRODUCT.lowercase()
      val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.lowercase()
      } else {
        ""
      }

      // Pixel 9 series (Tensor G4 codenames: zuma, zuma_pro, caiman, komodo, tokay, akita)
      val pixel9Codenames = listOf("zuma", "zuma_pro", "caiman", "komodo", "tokay", "akita", "pixel 9")
      return pixel9Codenames.any {
        hardware.contains(it) || device.contains(it) || product.contains(it) || socModel.contains(it)
      }
    }

    /**
     * Checks if the device is any Google Pixel with Tensor TPU support (Pixel 9, 10, 11).
     */
    fun isPixelTpuSupported(): Boolean {
      return isPixel11Family() || isPixel9Family() || Build.MANUFACTURER.equals("Google", ignoreCase = true)
    }
  }

  override fun onCreate() {
    super.onCreate()
    DynamicColors.applyToActivitiesIfAvailable(this)
    copySystemOpenCL()
    copyPixelTpuLibraries()
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

  private fun copyPixelTpuLibraries() {
    if (!isPixel9Family()) {
      return
    }

    Log.i(TAG, "Pixel 9 (Tensor G4) detected. Initializing Edge TPU dispatcher libraries...")
    val tpuLibs = listOf(
      "libLiteRtDispatch_google_tensor.so",
      "libdarwinn_tflite_delegate.so"
    )

    for (libName in tpuLibs) {
      val destFile = File(filesDir, libName)
      if (destFile.exists()) continue

      val candidatePaths = listOf(
        "/vendor/lib64/$libName",
        "/system/vendor/lib64/$libName",
        "/system/lib64/$libName"
      )

      for (path in candidatePaths) {
        val srcFile = File(path)
        if (srcFile.exists() && srcFile.canRead()) {
          try {
            srcFile.inputStream().use { input ->
              destFile.outputStream().use { output ->
                input.copyTo(output)
              }
            }
            Log.i(TAG, "Successfully copied TPU dispatch lib $path to ${destFile.absolutePath}")
            break
          } catch (e: Exception) {
            Log.w(TAG, "Could not copy $path: ${e.message}")
          }
        }
      }
    }
  }
}
