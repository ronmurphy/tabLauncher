package dev.tabdesk.seethrough

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A see-through window with a card in the middle, for the screen reader's picture tests (tools/README.md). Open it
 * on its own (am start), or in the test caller's task with `tools/agentcaller/run.sh over`, so the caller's
 * password screen is under it in the same task.
 */
class Card : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val card = TextView(this).apply {
            text = "See-through test card"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.rgb(255, 230, 120)); cornerRadius = 24f }
        }
        setContentView(FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(700, 300, Gravity.CENTER))
        })
    }
}
