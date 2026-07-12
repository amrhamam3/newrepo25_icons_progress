package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * نص بيتحرق حرف حرف بشعاع ليزر متحرك (مع شرر بيتطاير من كل حرف) — نسخة أندرويد
 * من نفس تأثير الـ HTML preview اللي اشتغل عليه المستخدم برّه التطبيق.
 * بتتغذى بـ update() من حلقة الرندر الخارجية في SplashActivity زي باقي عناصر
 * الـ Splash (WireframeSplashView / RingView) عشان تفضل كل الأنيميشن متزامنة.
 */
class LaserTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var text: String = "AMR3D PREVIEW"
        set(value) {
            field = value
            charsBuilt = false
            invalidate()
        }

    /** لون الثيم الحالي — بيتلوّن بيه الحرف بعد ما يتحرق، وشعاع الليزر، والشرر */
    var accentColor: Int = 0xFFFF8A1E.toInt()
        set(value) {
            field = value
            burnedPaint.color = value
            laserPaint.color = value
            sparkPaint.color = value
        }

    /** true لما كل الحروف خلصت تتحرق */
    var isBurnComplete: Boolean = false
        private set

    private class Ch(val c: Char, val x: Float, var burned: Boolean = false, var glow: Float = 0f)
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var alpha: Float = 1f)

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textSize = context.resources.displayMetrics.scaledDensity * 26f
        textAlign = Paint.Align.LEFT
    }
    private val unburnedPaint = Paint(basePaint).apply { color = 0xFF0A0F1A.toInt() }
    private val burnedPaint = Paint(basePaint).apply {
        color = accentColor
        setShadowLayer(28f, 0f, 0f, accentColor)
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        strokeWidth = 5f
        setShadowLayer(20f, 0f, 0f, accentColor)
    }
    private val laserDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    private var chars = listOf<Ch>()
    private var charsBuilt = false
    private var currentIndex = 0
    private var laserX = 0f
    private var laserY = 0f
    private var textBaselineY = 0f
    private val sparks = mutableListOf<Spark>()

    private fun buildChars() {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val total = basePaint.measureText(text)
        val startX = cx - total / 2f
        val list = ArrayList<Ch>(text.length)
        for (i in text.indices) {
            val xBefore = basePaint.measureText(text, 0, i)
            list.add(Ch(text[i], startX + xBefore))
        }
        chars = list
        charsBuilt = true
        currentIndex = 0
        laserX = 0f
        laserY = 0f
        textBaselineY = height * 0.62f
        isBurnComplete = false
        sparks.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        charsBuilt = false
    }

    /** بيتنادى مرة كل فريم من حلقة الرندر الخارجية (زي wireframeView.updatePhysics()) */
    fun update() {
        if (!charsBuilt) {
            buildChars()
            if (!charsBuilt) return
        }
        if (currentIndex < chars.size) {
            val target = chars[currentIndex]
            val targetX = target.x
            val targetY = textBaselineY
            if (currentIndex == 0 && laserX == 0f) laserX = target.x
            laserX += (targetX - laserX) * 0.22f
            laserY += (targetY - laserY) * 0.22f
            if (abs(laserX - targetX) < 8f) {
                target.burned = true
                target.glow = 1f
                repeat(6) { spawnSpark(laserX, textBaselineY) }
                currentIndex++
            }
        } else if (!isBurnComplete) {
            laserY += (-150f - laserY) * 0.12f
            if (laserY < -140f) isBurnComplete = true
        }

        for (c in chars) if (c.glow > 0f) c.glow = (c.glow - 0.025f).coerceAtLeast(0f)

        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx
            s.y += s.vy
            s.vy += 0.25f
            s.alpha -= 0.035f
            if (s.alpha <= 0f) it.remove()
        }
    }

    private fun spawnSpark(x: Float, y: Float) {
        val angle = Random.nextDouble(0.0, Math.PI * 2).toFloat()
        val speed = 2f + Random.nextFloat() * 5f
        sparks.add(Spark(x, y, cos(angle) * speed, sin(angle) * speed - 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!charsBuilt) {
            buildChars()
            if (!charsBuilt) return
        }

        for (c in chars) {
            if (c.burned) {
                burnedPaint.setShadowLayer(28f + c.glow * 20f, 0f, 0f, accentColor)
                canvas.drawText(c.c.toString(), c.x, textBaselineY, burnedPaint)
            } else {
                canvas.drawText(c.c.toString(), c.x, textBaselineY, unburnedPaint)
            }
        }

        for (s in sparks) {
            sparkPaint.alpha = (s.alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(s.x, s.y, s.x + 5f, s.y + 5f, sparkPaint)
        }

        if (!isBurnComplete) {
            canvas.drawLine(laserX, 0f, laserX, laserY, laserPaint)
            canvas.drawCircle(laserX, laserY, 7f, laserDotPaint.apply {
                setShadowLayer(20f, 0f, 0f, accentColor)
            })
        }
    }
}
