package com.cwave.weiqi

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {
  private lateinit var container: FrameLayout
  private lateinit var fragmentContainer: FragmentContainerView

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    // Edge to edge on every Android version (Android 15+ always draws apps that target SDK 35+ this
    // way): the window extends behind the status and navigation bars, and GameFragment keeps its
    // content clear of the system bars and display cutout. The status bar lies on the purple app
    // bar (light icons); the navigation bar on the light background (dark icons).
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    )

    window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)

    setContentView(createView())

    if (savedInstanceState == null) {
      supportFragmentManager
        .beginTransaction()
        .replace(fragmentContainer.id, GameFragment())
        .commitNow()
    }
  }

  private fun createView(): View {
    container = FrameLayout(this).apply {
      id = View.generateViewId()
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    fragmentContainer = FragmentContainerView(this).apply {
      // A fixed id, not View.generateViewId(): when the activity is re-created, the restored
      // GameFragment goes back into the container with the id it was added to. With a new id it
      // stayed detached and the screen was blank.
      id = R.id.fragment_container
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    container.addView(fragmentContainer)
    return container
  }
}
