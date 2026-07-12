package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * شاشة عرض DXF ثنائية الأبعاد حقيقية — بترسم بكانفاس 2D مباشرة بألوان الطبقات الحقيقية
 * (مش بتحوّل الخطوط لمثلثات وتعرضها في محرك 3D زي ما كان بيحصل قبل كده).
 * بتدعم: تكبير بإصبعين (pinch zoom)، تحريك بإصبع واحد (pan)، ضبط تلقائي للعرض (fit to view)،
 * وأداة قياس مسافة حقيقية بين نقطتين (وضع القياس).
 */
class DXF2DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var model: DxfModel? = null
    private var snapPoints: List<FloatArray> = emptyList() // كل نقاط النهايات/المراكز القابلة للالتقاط [x, y]
    private val snapRadiusPx = 45f // نصف قطر الالتقاط بالبكسل — لو التاتش قريب من نقطة حقيقية بيلتصق بيها

    // مصفوفة تحويل من إحداثيات DXF (وحدات الرسم) لإحداثيات الشاشة (بكسل)
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ══ وضع القياس ══
    var measureModeOn = false
        set(value) {
            field = value
            if (!value) { measureP1 = null; measureP2 = null }
            invalidate()
        }
    var onDistanceMeasured: ((Float) -> Unit)? = null
    private var measureP1: FloatArray? = null // [worldX, worldY]
    private var measureP2: FloatArray? = null

    private val defaultPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0F12") }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A1F26")
        strokeWidth = 1.5f
        isAntiAlias = false
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#3A4048")
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val measurePointPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val measureLinePaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    private val measureTextPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        textSize = 34f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusX = detector.focusX
                val focusY = detector.focusY
                val worldX = (focusX - offsetX) / scale
                val worldY = (focusY - offsetY) / scale
                scale = (scale * detector.scaleFactor).coerceIn(0.001f, 5000f)
                offsetX = focusX - worldX * scale
                offsetY = focusY - worldY * scale
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (measureModeOn) return false // في وضع القياس التحريك بإصبعين بس (pinch) مش سحب بإصبع واحد
                offsetX -= dx
                offsetY -= dy
                invalidate()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (measureModeOn) {
                    handleMeasureTap(e.x, e.y)
                    return true
                }
                return false
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleMeasureTap(screenX: Float, screenY: Float) {
        // نحاول نلتقط أقرب نقطة حقيقية في الرسمة (نهاية خط / مركز دايرة أو قوس) بدل الاعتماد
        // على دقة إصبع المستخدم فقط — بالظبط زي أدوات الـ Snap في برامج الـ CAD
        val snapped = findSnapPoint(screenX, screenY)
        val worldX: Float
        val worldY: Float
        if (snapped != null) {
            worldX = snapped[0]
            worldY = snapped[1]
        } else {
            worldX = (screenX - offsetX) / scale
            worldY = -(screenY - offsetY) / scale
        }

        if (measureP1 == null || (measureP1 != null && measureP2 != null)) {
            // بداية قياس جديد
            measureP1 = floatArrayOf(worldX, worldY)
            measureP2 = null
        } else {
            measureP2 = floatArrayOf(worldX, worldY)
            val p1 = measureP1!!
            val p2 = measureP2!!
            val dist = hypot((p2[0] - p1[0]).toDouble(), (p2[1] - p1[1]).toDouble()).toFloat()
            onDistanceMeasured?.invoke(dist)
        }
        invalidate()
    }

    fun clearMeasurement() {
        measureP1 = null; measureP2 = null
        invalidate()
    }

    /** تحميل موديل DXF جديد — بيعمل ضبط تلقائي (fit to view) أول ما يتحمّل */
    fun setModel(m: DxfModel) {
        model = m
        measureP1 = null; measureP2 = null
        snapPoints = buildSnapPoints(m)
        post { resetView() }
    }

    /** بيجمّع كل نقاط النهايات والمراكز من عناصر الرسمة عشان أداة القياس تقدر تلتقط عليها */
    private fun buildSnapPoints(m: DxfModel): List<FloatArray> {
        val pts = mutableListOf<FloatArray>()
        for (line in m.lines) {
            pts.add(floatArrayOf(line.x1, line.y1))
            pts.add(floatArrayOf(line.x2, line.y2))
        }
        for (circle in m.circles) {
            pts.add(floatArrayOf(circle.cx, circle.cy)) // مركز الدايرة
        }
        for (arc in m.arcs) {
            pts.add(floatArrayOf(arc.cx, arc.cy)) // مركز القوس
            val startRad = Math.toRadians(arc.startDeg.toDouble())
            val endRad = Math.toRadians(arc.endDeg.toDouble())
            pts.add(floatArrayOf(arc.cx + arc.r * cos(startRad).toFloat(), arc.cy + arc.r * sin(startRad).toFloat()))
            pts.add(floatArrayOf(arc.cx + arc.r * cos(endRad).toFloat(), arc.cy + arc.r * sin(endRad).toFloat()))
        }
        return pts
    }

    /** بيدوّر على أقرب نقطة التقاط لمكان اللمس (بمسافة بالبكسل على الشاشة، مش بوحدات الرسمة) */
    private fun findSnapPoint(screenX: Float, screenY: Float): FloatArray? {
        var closest: FloatArray? = null
        var closestDist = snapRadiusPx
        for (p in snapPoints) {
            val sx = toScreenX(p[0])
            val sy = toScreenY(p[1])
            val d = hypot((sx - screenX).toDouble(), (sy - screenY).toDouble()).toFloat()
            if (d < closestDist) {
                closestDist = d
                closest = p
            }
        }
        return closest
    }

    fun clear() {
        model = null
        measureP1 = null; measureP2 = null
        invalidate()
    }

    /** إعادة ضبط العرض عشان الرسمة كلها تظهر بالكامل في نص الشاشة */
    fun resetView() {
        val m = model ?: return
        if (width == 0 || height == 0) return

        val w = (m.maxX - m.minX).let { if (it <= 0f) 1f else it }
        val h = (m.maxY - m.minY).let { if (it <= 0f) 1f else it }

        val padding = 0.9f
        val scaleX = (width * padding) / w
        val scaleY = (height * padding) / h
        scale = minOf(scaleX, scaleY)

        val centerX = (m.minX + m.maxX) / 2f
        val centerY = (m.minY + m.maxY) / 2f

        offsetX = width / 2f - centerX * scale
        offsetY = height / 2f + centerY * scale

        invalidate()
    }

    private fun toScreenX(x: Float) = offsetX + x * scale
    private fun toScreenY(y: Float) = offsetY - y * scale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        drawGrid(canvas)

        val m = model ?: return

        for (line in m.lines) {
            defaultPaint.color = line.color
            canvas.drawLine(
                toScreenX(line.x1), toScreenY(line.y1),
                toScreenX(line.x2), toScreenY(line.y2),
                defaultPaint
            )
        }

        for (circle in m.circles) {
            defaultPaint.color = circle.color
            canvas.drawCircle(
                toScreenX(circle.cx), toScreenY(circle.cy),
                circle.r * scale, defaultPaint
            )
        }

        for (arc in m.arcs) {
            defaultPaint.color = arc.color
            drawArc(canvas, arc)
        }

        drawMeasurement(canvas)
    }

    private val snapDotPaint = Paint().apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun drawMeasurement(canvas: Canvas) {
        // إظهار كل نقاط الالتقاط المتاحة كنقط خفيفة عشان توضح للمستخدم فين يقدر يلزّق
        if (measureModeOn) {
            for (p in snapPoints) {
                canvas.drawCircle(toScreenX(p[0]), toScreenY(p[1]), 5f, snapDotPaint)
            }
        }

        val p1 = measureP1 ?: return
        val sx1 = toScreenX(p1[0]); val sy1 = -p1[1] * scale + offsetY
        canvas.drawCircle(sx1, sy1, 10f, measurePointPaint)

        val p2 = measureP2
        if (p2 != null) {
            val sx2 = toScreenX(p2[0]); val sy2 = -p2[1] * scale + offsetY
            canvas.drawCircle(sx2, sy2, 10f, measurePointPaint)
            canvas.drawLine(sx1, sy1, sx2, sy2, measureLinePaint)

            val dist = hypot((p2[0] - p1[0]).toDouble(), (p2[1] - p1[1]).toDouble()).toFloat()
            val midX = (sx1 + sx2) / 2f
            val midY = (sy1 + sy2) / 2f
            val label = "%.2f".format(dist)
            canvas.drawText(label, midX + 12f, midY - 12f, measureTextPaint)
        }
    }

    private fun drawArc(canvas: Canvas, arc: DxfArc) {
        val segments = 48
        var end = arc.endDeg
        if (end <= arc.startDeg) end += 360f
        val totalAngle = end - arc.startDeg
        var prevX = 0f; var prevY = 0f
        for (s in 0..segments) {
            val angle = Math.toRadians((arc.startDeg + s * totalAngle / segments).toDouble())
            val x = arc.cx + arc.r * cos(angle).toFloat()
            val y = arc.cy + arc.r * sin(angle).toFloat()
            if (s > 0) {
                canvas.drawLine(toScreenX(prevX), toScreenY(prevY), toScreenX(x), toScreenY(y), defaultPaint)
            }
            prevX = x; prevY = y
        }
    }

    /** شبكة خفيفة + محاور X/Y زي شاشة الرسم بالأوتوكاد */
    private fun drawGrid(canvas: Canvas) {
        if (scale <= 0f) return
        var step = 10f
        val minPixelStep = 40f
        while (step * scale < minPixelStep) step *= 10f
        while (step * scale > minPixelStep * 10f) step /= 10f

        val worldLeft = (0 - offsetX) / scale
        val worldRight = (width - offsetX) / scale
        val worldTop = (offsetY - 0) / scale
        val worldBottom = (offsetY - height) / scale

        var gx = (Math.floor((worldLeft / step).toDouble()) * step).toFloat()
        while (gx <= worldRight) {
            canvas.drawLine(toScreenX(gx), 0f, toScreenX(gx), height.toFloat(), gridPaint)
            gx += step
        }
        var gy = (Math.floor((worldBottom / step).toDouble()) * step).toFloat()
        while (gy <= worldTop) {
            canvas.drawLine(0f, toScreenY(gy), width.toFloat(), toScreenY(gy), gridPaint)
            gy += step
        }

        canvas.drawLine(toScreenX(0f), 0f, toScreenX(0f), height.toFloat(), axisPaint)
        canvas.drawLine(0f, toScreenY(0f), width.toFloat(), toScreenY(0f), axisPaint)
    }
}
