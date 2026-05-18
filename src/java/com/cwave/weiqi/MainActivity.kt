package com.cwave.weiqi

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
  private lateinit var container: FrameLayout
  private lateinit var fragmentContainer: FragmentContainerView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    DynamicColors.applyToActivityIfAvailable(this)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)

    setContentView(createView())
    setLayoutEdgeToEdge()

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
      id = View.generateViewId()
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    container.addView(fragmentContainer)
    return container
  }

  private fun setLayoutEdgeToEdge() {
    ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
      val insets = windowInsets.getInsets(
        WindowInsetsCompat.Type.navigationBars() or
        WindowInsetsCompat.Type.statusBars() or
        WindowInsetsCompat.Type.displayCutout()
      )
      view.updateLayoutParams<MarginLayoutParams> {
        leftMargin = insets.left
        rightMargin = insets.right
        topMargin = insets.top
        bottomMargin = insets.bottom
      }
      WindowInsetsCompat.CONSUMED
    }
  }
}
