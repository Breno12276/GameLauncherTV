package com.gamelaunchertv.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Controle de videogame do indicador de bateria, desenhado num quadro de 24×24. */
class IconeControle : Drawable() {

    var cor = Color.WHITE

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corpo = Path().apply {
        moveTo(7f, 6.5f)
        lineTo(17f, 6.5f)
        cubicTo(20.2f, 6.5f, 22f, 9f, 22.3f, 13f)
        cubicTo(22.6f, 17.2f, 21.4f, 19.3f, 19.6f, 19.3f)
        cubicTo(18f, 19.3f, 17.2f, 17.6f, 15.8f, 16f)
        lineTo(8.2f, 16f)
        cubicTo(6.8f, 17.6f, 6f, 19.3f, 4.4f, 19.3f)
        cubicTo(2.6f, 19.3f, 1.4f, 17.2f, 1.7f, 13f)
        cubicTo(2f, 9f, 3.8f, 6.5f, 7f, 6.5f)
        close()
        // Direcional (cruz) à esquerda.
        moveTo(7f, 9.7f); lineTo(7f, 13.3f)
        moveTo(5.2f, 11.5f); lineTo(8.8f, 11.5f)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        traco.color = cor
        cheio.color = cor
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        c.drawPath(corpo, traco)
        c.drawCircle(16.3f, 10.4f, 1.1f, cheio)
        c.drawCircle(18.2f, 12.4f, 1.1f, cheio)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { traco.colorFilter = colorFilter; cheio.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Engrenagem do topo, desenhada num quadro de 24×24. */
class Engrenagem : Drawable() {

    var cor = Color.WHITE

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeJoin = Paint.Join.ROUND
    }
    private val caminho = Path().apply {
        val n = 32
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n - 5.625)
            val raio = if (i % 4 < 2) 9.5f else 7.4f
            val x = 12f + raio * cos(a).toFloat()
            val y = 12f + raio * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
        addCircle(12f, 12f, 3f, Path.Direction.CW)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        traco.color = cor
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        c.drawPath(caminho, traco)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { traco.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
