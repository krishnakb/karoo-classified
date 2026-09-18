package com.krishnakb.karooclassified.extension

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import com.krishnakb.karooclassified.R
import io.hammerhead.karooext.models.ViewConfig

/**
 * Renders a data field as a self-contained tile.
 *
 * Both fields draw themselves rather than relying on Karoo's standard numeric
 * treatment. That is deliberate: Karoo does not always call `startStream` for a
 * field - sometimes only `startView` - and a data type that depends on
 * `startStream` silently freezes at its last value when that happens. Rendering in
 * `startView` works either way.
 *
 * Drawing the whole tile also lets the background be coloured, which Karoo's own
 * header would otherwise leave unfilled.
 */
object FieldTile {

    /**
     * @param label the field name, drawn in place of Karoo's suppressed header
     * @param value the text to display
     * @param backgroundColor fill for the whole tile
     * @param textColor label, icon and value colour
     * @param italicLabel renders the label in italic, echoing the Classified
     *   wordmark. Applied as a span rather than in the layout, because both fields
     *   share this tile and only one of them wants it.
     */
    fun render(
        context: Context,
        config: ViewConfig,
        label: String,
        iconRes: Int,
        value: String,
        backgroundColor: Int,
        textColor: Int,
        italicLabel: Boolean = false,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.data_field_tile).apply {
        setInt(R.id.tile_root, "setBackgroundColor", backgroundColor)
        setImageViewResource(R.id.tile_icon, iconRes)
        setInt(R.id.tile_icon, "setColorFilter", textColor)
        setTextViewText(R.id.tile_label, if (italicLabel) italic(label) else label)
        setTextColor(R.id.tile_label, textColor)
        setTextViewText(R.id.tile_value, value)
        setTextColor(R.id.tile_value, textColor)
        setTextViewTextSize(
            R.id.tile_value,
            TypedValue.COMPLEX_UNIT_SP,
            valueTextSize(context, config, value),
        )
        // Only the value follows the global alignment. Karoo pins the icon and
        // label to the left on its own tiles regardless of that setting.
        setInt(R.id.tile_value, "setGravity", config.alignment.toGravity())
    }

    /**
     * Largest text size that still fits, never exceeding the native size.
     *
     * [ViewConfig.textSize] is what Karoo uses for a built-in numeric field, where
     * the header sits *outside* the value area. These tiles draw their own label
     * inside, so the same size overflows. Cap by the height left after the label
     * and by the width the text actually needs.
     */
    private fun valueTextSize(context: Context, config: ViewConfig, value: String): Float {
        // scaledDensity is deprecated; it was always density x fontScale.
        val resources = context.resources
        val density = resources.displayMetrics.density * resources.configuration.fontScale
        if (density <= 0f) return config.textSize.toFloat()
        // Divide by the line-height factor: a glyph's line box is taller than its
        // nominal size, so sizing text to the available height clips descenders.
        val heightSp = (config.viewSize.second / density - LABEL_ROW_SP) / LINE_HEIGHT_EM
        val widthSp = config.viewSize.first / density / (value.length.coerceAtLeast(1) * CHAR_WIDTH_EM)
        return minOf(config.textSize.toFloat(), heightSp, widthSp).coerceAtLeast(MIN_VALUE_SP)
    }

    private fun italic(text: String): CharSequence =
        SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

    private fun ViewConfig.Alignment.toGravity(): Int = when (this) {
        ViewConfig.Alignment.LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
        ViewConfig.Alignment.CENTER -> Gravity.CENTER
        ViewConfig.Alignment.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
    }

    /**
     * Height consumed above the value, in sp: the 15sp label's line box (~18sp)
     * plus 3dp of root padding top and bottom.
     *
     * Underestimating this only shows up on small tiles, where the height cap is
     * what binds - a full-size tile has enough slack to hide the error.
     */
    private const val LABEL_ROW_SP = 24f

    /**
     * A TextView's line box relative to its nominal text size, with font padding
     * left on. Roboto's ascent plus descent is about 1.17em and the font padding
     * adds roughly another 0.2em.
     */
    private const val LINE_HEIGHT_EM = 1.45f

    /** Rough advance width of a bold digit or letter as a fraction of text size. */
    private const val CHAR_WIDTH_EM = 0.62f

    private const val MIN_VALUE_SP = 12f
}
