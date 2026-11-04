package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.simoneluconi.gccamera.R

/**
 * The Fn menu: shooting options grouped under section titles, each a row of choices with the
 * active one filled. Scrolls when it does not fit (e.g. turned sideways). Built only when
 * opened, so it costs nothing while hidden.
 */
class QuickMenuView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {

    /** One option row; rows with the same [section] are grouped under its title. */
    class Row(
        val key: String,
        val label: String,
        val options: List<Pair<String, String>>,
        val selected: String,
        val section: String = "",
    )

    var onChange: ((key: String, value: String) -> Unit)? = null

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val p = dp(16f).toInt()
        content.setPadding(p, dp(8f).toInt(), p, p)
        addView(content)
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(color(R.color.surface))
            cornerRadius = dp(18f)
        }
        elevation = dp(6f)
    }

    /** Drops the rows (the menu is rebuilt on every open). */
    fun clear() = content.removeAllViews()

    fun setRows(rows: List<Row>) {
        content.removeAllViews()
        var section: String? = null
        for (row in rows) {
            if (row.section != section) {
                section = row.section
                if (section.isNotEmpty()) content.addView(sectionTitle(section, first = content.childCount == 0))
            }
            content.addView(rowView(row))
        }
        scrollTo(0, 0)
    }

    private fun sectionTitle(text: String, first: Boolean) = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.08f
        typeface = MEDIUM
        setTextColor(color(R.color.accent))
        setPadding(0, dp(if (first) 6f else 14f).toInt(), 0, dp(4f).toInt())
    }

    private fun rowView(row: Row): LinearLayout {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3f).toInt(), 0, dp(3f).toInt())
        }
        line.addView(TextView(context).apply {
            text = row.label
            textSize = 14f
            setTextColor(color(R.color.on_surface))
            width = dp(132f).toInt()
        })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        var selected = row.selected
        val views = ArrayList<TextView>()
        fun refresh() {
            for ((i, v) in views.withIndex()) style(v, row.options[i].first == selected)
        }
        for ((value, label) in row.options) {
            val chip = TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(56f).toInt()
                minHeight = dp(40f).toInt()
                val ph = dp(16f).toInt()
                setPadding(ph, 0, ph, 0)
                setOnClickListener {
                    if (selected != value) {
                        selected = value
                        refresh()
                        onChange?.invoke(row.key, value)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8f).toInt() }
            chips.addView(chip, lp)
            views.add(chip)
        }
        refresh()
        line.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        })
        return line
    }

    private fun style(v: TextView, on: Boolean) {
        v.background = GradientDrawable().apply {
            setColor(color(if (on) R.color.accent else R.color.background))
            if (!on) setStroke(dp(1f).toInt(), color(R.color.outline))
            cornerRadius = dp(20f)
        }
        v.setTextColor(color(if (on) R.color.on_accent else R.color.on_surface))
        v.typeface = if (on) MEDIUM else Typeface.DEFAULT
    }
}
