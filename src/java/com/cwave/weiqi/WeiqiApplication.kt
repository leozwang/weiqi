package com.cwave.weiqi

import android.app.Application
import com.google.android.material.color.DynamicColors

class WeiqiApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    DynamicColors.applyToActivitiesIfAvailable(this)
  }
}
