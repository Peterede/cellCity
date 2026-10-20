package com.cellcity

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.cellcity.game.GameView

class MainActivity : Activity() {

    private lateinit var gameView: GameView

    @Suppress("DEPRECATION")
    private val immersiveFlags =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        applyImmersiveFlags()
        gameView = GameView(this)
        setContentView(gameView)
        window.decorView.setOnSystemUiVisibilityChangeListener { applyImmersiveFlags() }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFlags() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val decor = window.decorView
        if (decor.systemUiVisibility and immersiveFlags != immersiveFlags) {
            decor.systemUiVisibility = immersiveFlags
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFlags()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFlags()
        gameView.onActivated()
    }

    override fun onPause() {
        super.onPause()
        gameView.onDeactivated()
    }
}
