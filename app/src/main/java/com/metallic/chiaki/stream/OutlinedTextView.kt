package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Crisp GTA 6 style subtitle TextView: draws a solid black stroke outline behind the fill text
 * with ZERO fuzzy drop shadow.
 */
class OutlinedTextView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

	var outlineColor: Int = Color.BLACK
	var outlineWidthPx: Float = 5.5f * resources.displayMetrics.density / 2.5f

	override fun onDraw(canvas: Canvas) {
		val textColor = currentTextColor
		val p = paint

		// Pass 1: crisp black stroke outline (no blur/shadow)
		p.style = Paint.Style.STROKE
		p.strokeJoin = Paint.Join.ROUND
		p.strokeCap = Paint.Cap.ROUND
		p.strokeWidth = (textSize * 0.14f).coerceIn(3.5f, 11f)
		setTextColor(outlineColor)
		super.onDraw(canvas)

		// Pass 2: solid crisp fill on top
		p.style = Paint.Style.FILL
		setTextColor(textColor)
		super.onDraw(canvas)
	}
}
