package dev.tabdesk.seethrough

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Things for the agent to tap and type into, for the acting tools' tests (tools/README.md): a "Roll d20" button, a
 * long-press button, a name field (Enter submits it), a password field, and a long list. Every event goes to the
 * log as "Pad: …" and into the status line at the top, so a test can see what landed.
 * Open it with: adb shell am start -n dev.tabdesk.seethrough/.Pad
 */
class Pad : Activity() {
    private lateinit var status: TextView
    private var rolls = 0

    private fun said(s: String) { Log.i("Pad", s); status.text = s }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f; text = "Ready"; contentDescription = null }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        box.addView(status)
        box.addView(Button(this).apply {
            text = "Roll d20"
            setOnClickListener { rolls++; said("Rolled ${(rolls * 7) % 20 + 1} (roll $rolls)") }
        })
        box.addView(Button(this).apply {
            text = "Hold me"
            setOnClickListener { said("Hold me: tapped") }
            setOnLongClickListener { said("Hold me: long-pressed"); true }
        })
        box.addView(EditText(this).apply {
            hint = "Name"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Log.i("Pad", "Name is now \"$s\"") }
            })
            setOnEditorActionListener { v, _, _ -> said("Submitted \"${v.text}\""); true }
        })
        box.addView(EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        for (i in 1..40) box.addView(TextView(this).apply { text = "Row $i"; textSize = 22f; setPadding(0, 24, 0, 24) })
        setContentView(ScrollView(this).apply {
            // Below the status bar: Android 15 and later draw apps under it.
            fitsSystemWindows = true
            addView(box)
            setOnScrollChangeListener { _, _, y, _, _ -> Log.i("Pad", "scrolled to $y") }
        })
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) said("Enter")
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("for the test's log")
    override fun onBackPressed() { said("Back"); Log.i("Pad", "Back"); @Suppress("DEPRECATION") super.onBackPressed() }
}
