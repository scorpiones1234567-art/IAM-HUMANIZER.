
package com.iam.humanizer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class AudioControl(
    context: Context,
    title: String,
    description: String,
    accentColor: Int,
    minValue: Float = 0f,
    maxValue: Float = 100f,
    initialValue: Float = 0f,
    suffix: String = "%"
) : LinearLayout(context) {

    val knob = RotaryKnob(context)

    private val valueLabel = TextView(context)

    var onValueChanged: ((Float) -> Unit)? = null

    var value: Float
        get() = knob.value
        set(newValue) {
            knob.value = newValue
            updateValue(knob.value)
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        setPadding(dp(5), dp(8), dp(5), dp(8))

        val titleLabel = TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        addView(
            titleLabel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )

        knob.minValue = minValue
        knob.maxValue = maxValue
        knob.suffix = suffix
        knob.accentColor = accentColor
        knob.value = initialValue

        addView(
            knob,
            LayoutParams(dp(104), dp(104))
        )

        valueLabel.apply {
            textSize = 15f
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        addView(
            valueLabel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )

        val descriptionLabel = TextView(context).apply {
            text = description
            textSize = 10f
            setTextColor(0xFFD8C8F5.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }

        addView(
            descriptionLabel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )

        updateValue(knob.value)

        knob.onValueChanged = { newValue ->
            updateValue(newValue)
            onValueChanged?.invoke(newValue)
        }
    }

    private fun updateValue(number: Float) {
        valueLabel.text = "${number.toInt()}${knob.suffix}"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
