package com.andrew.proxyapp.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import com.andrew.proxyapp.R
import com.google.android.material.bottomsheet.BottomSheetDialog

internal fun AppCompatActivity.showPulseChoiceSheet(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val dialog = BottomSheetDialog(this)
    val horizontalInset = resources.getDimensionPixelSize(R.dimen.space_2xl)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            horizontalInset,
            resources.getDimensionPixelSize(R.dimen.space_sm),
            horizontalInset,
            resources.getDimensionPixelSize(R.dimen.space_2xl)
        )
        background = getColor(R.color.surface).toDrawable()
    }
    container.addView(View(this).apply {
        background = AppCompatResources.getDrawable(this@showPulseChoiceSheet, R.drawable.bottom_sheet_handle)
        layoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.touch_target),
            resources.getDimensionPixelSize(R.dimen.space_xs)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = resources.getDimensionPixelSize(R.dimen.space_lg)
        }
    })
    container.addView(TextView(this).apply {
        text = title
        setTextAppearance(R.style.TextAppearance_Pulse_Body)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.space_xs),
            0,
            resources.getDimensionPixelSize(R.dimen.space_xs),
            resources.getDimensionPixelSize(R.dimen.space_sm)
        )
    })
    items.forEachIndexed { index, item ->
        container.addView(TextView(this).apply {
            text = item
            setTextAppearance(R.style.TextAppearance_Pulse_Body)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = resources.getDimensionPixelSize(R.dimen.settings_row_height)
            setPadding(resources.getDimensionPixelSize(R.dimen.space_xs), 0, resources.getDimensionPixelSize(R.dimen.space_xs), 0)
            background = AppCompatResources.getDrawable(this@showPulseChoiceSheet, android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            if (index == selectedIndex) setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                onSelect(index)
                dialog.dismiss()
            }
        })
        if (index < items.lastIndex) {
            container.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.stroke_thin)
                )
            })
        }
    }
    dialog.setContentView(container)
    dialog.show()
}
