package com.gamelaunchertv.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desenhos das telas no estilo PS4 (início, menu de funções, biblioteca): fundo azul com ondas, ícones em branco,
 * brilho da seleção e imagem cortada no formato. Tudo desenhado aqui, sem nenhuma imagem da Sony.
 * Só na thread da tela (os pincéis são compartilhados).
 */
object Ps4Desenho {

    enum class Icone {
        ATIVIDADE, AMIGOS, MENSAGENS, FESTA, TROFEUS, CONFIGURACOES, ENERGIA,
        LOJA, BIBLIOTECA, APPS, NAVEGADOR, CAPTURAS, BUSCAR, TODOS, JOGOS, PLATAFORMA,
    }

    const val AZUL_FUNDO = 0xFF2160C4.toInt()
    const val AZUL_ESCURO = 0xF00B1A3C.toInt()
    val leve: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val normal: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val caminho = Path()
    private val r = RectF()

    /** Fundo azul em degradê com faixas claras curvas; [fase] em radianos (animação lenta). */
    fun fundo(c: Canvas, w: Float, h: Float, fase: Float) {
        p.color = Color.BLACK // opaco: a cor da última onda (translúcida) apagaria o degradê
        p.shader = LinearGradient(0f, 0f, w * 0.3f, h, intArrayOf(0xFF1B4AA6.toInt(), 0xFF2766CC.toInt(), 0xFF1D52B4.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val ondas = listOf(Triple(0.18f, 26, 0f), Triple(0.42f, 20, 1.7f), Triple(0.63f, 16, 3.1f), Triple(0.30f, 12, 4.4f))
        for ((base, alfa, desl) in ondas) {
            caminho.reset()
            val y0 = h * base
            caminho.moveTo(-w * 0.1f, y0 + sin(fase + desl) * h * 0.05f)
            caminho.cubicTo(w * 0.3f, y0 - h * 0.22f + sin(fase * 1.3f + desl) * h * 0.04f,
                w * 0.6f, y0 + h * 0.25f + sin(fase * 0.8f + desl) * h * 0.05f,
                w * 1.1f, y0 - h * 0.05f + sin(fase + desl * 2) * h * 0.04f)
            caminho.lineTo(w * 1.1f, y0 + h * 0.03f)
            caminho.cubicTo(w * 0.6f, y0 + h * 0.3f, w * 0.3f, y0 - h * 0.16f, -w * 0.1f, y0 + h * 0.06f)
            caminho.close()
            p.color = Color.argb(alfa, 255, 255, 255)
            c.drawPath(caminho, p)
        }
    }

    /** Borda azul-clara com brilho em volta (a seleção). */
    fun brilho(c: Canvas, l: Float, t: Float, rt: Float, b: Float, u: Float) {
        p.style = Paint.Style.STROKE
        for (k in 4 downTo 1) {
            p.strokeWidth = k * 2.2f * u
            p.color = Color.argb(28 + (4 - k) * 18, 120, 205, 255)
            c.drawRect(l - k * u, t - k * u, rt + k * u, b + k * u, p)
        }
        p.strokeWidth = 2.2f * u
        p.color = 0xFF8FDAFF.toInt()
        c.drawRect(l, t, rt, b, p)
        p.style = Paint.Style.FILL
    }

    /** Imagem cortada no formato do destino (como "centerCrop"). */
    fun cortado(c: Canvas, b: Bitmap, destino: RectF) {
        val prop = destino.width() / destino.height()
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val src = if (bw / bh > prop) {
            val w = bh * prop
            Rect(((bw - w) / 2).toInt(), 0, ((bw + w) / 2).toInt(), bh.toInt())
        } else {
            // Imagem mais alta que o destino (capa em pé num bloco quadrado): corta mais embaixo, onde o título
            // da caixa raramente fica.
            val h = bw / prop
            val topo = (bh - h) * 0.3f
            Rect(0, topo.toInt(), bw.toInt(), (topo + h).toInt())
        }
        c.drawBitmap(b, src, destino, pImg)
    }

    fun bitmap(c: Canvas, b: Bitmap, destino: RectF) = c.drawBitmap(b, null, destino, pImg)

    private val coresDaBorda = java.util.WeakHashMap<Bitmap, Int>()

    /** Cor da borda da imagem (o fundo de um logo), para preencher em volta dele. */
    private fun corDaBorda(b: Bitmap): Int = coresDaBorda.getOrPut(b) {
        try {
            val m = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val x = 1.coerceAtMost(m.width - 1)
            m.getPixel(x, m.height / 2) or 0xFF000000.toInt()
        } catch (_: Exception) { 0xFF2B2E33.toInt() }
    }

    /**
     * Banner de app (largo) num bloco: o logo inteiro, centralizado, sobre a cor do fundo dele, em vez de cortado.
     * Imagens quase quadradas são cortadas normalmente.
     */
    fun banner(c: Canvas, b: Bitmap, destino: RectF) {
        val prop = b.width.toFloat() / b.height.coerceAtLeast(1)
        val alvo = destino.width() / destino.height()
        if (prop < alvo * 1.25f) return cortado(c, b, destino)
        p.color = corDaBorda(b)
        c.drawRect(destino, p)
        val w = destino.width()
        val h = w / prop
        val y = destino.centerY() - h / 2
        c.drawBitmap(b, null, RectF(destino.left, y, destino.right, y + h), pImg)
    }

    /** Quadrado com a inicial do perfil (avatar). */
    fun avatar(c: Canvas, nome: String, x: Float, y: Float, l: Float, t: android.text.TextPaint) {
        p.color = 0xFF0A6FD6.toInt() // azul fixo (a cor automática do Android muda com o papel de parede)
        c.drawRect(x, y, x + l, y + l, p)
        t.typeface = normal
        t.textSize = l * 0.57f
        t.color = Color.WHITE
        val alinhar = t.textAlign
        t.textAlign = Paint.Align.CENTER
        c.drawText(nome.take(1).uppercase(), x + l / 2, y + l * 0.7f, t)
        t.textAlign = alinhar
    }

    /** Ícone branco [ic] num quadrado de lado [l] com canto em ([x], [y]). [fundo]: cor atrás (para os "furos"). */
    fun icone(c: Canvas, ic: Icone, x: Float, y: Float, l: Float, fundo: Int = AZUL_FUNDO) {
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        p.strokeWidth = l * 0.075f
        when (ic) {
            Icone.ATIVIDADE -> {
                c.drawCircle(x + l / 2, y + l / 2, l * 0.46f, p)
                caminho.reset()
                caminho.moveTo(x + l * 0.12f, y + l * 0.7f)
                caminho.lineTo(x + l * 0.02f, y + l * 1.02f)
                caminho.lineTo(x + l * 0.4f, y + l * 0.88f)
                caminho.close()
                c.drawPath(caminho, p)
                p.color = fundo
                c.drawCircle(x + l / 2, y + l * 0.27f, l * 0.07f, p)
                c.drawRect(x + l * 0.44f, y + l * 0.42f, x + l * 0.56f, y + l * 0.76f, p)
            }
            Icone.AMIGOS -> {
                r.set(x + l * 0.3f, y, x + l, y + l * 0.62f)
                c.drawRoundRect(r, l * 0.12f, l * 0.12f, p)
                p.color = fundo
                c.drawRoundRect(RectF(x - l * 0.05f, y + l * 0.31f, x + l * 0.71f, y + l * 1.05f), l * 0.14f, l * 0.14f, p)
                p.color = Color.WHITE
                r.set(x, y + l * 0.36f, x + l * 0.66f, y + l)
                c.drawRoundRect(r, l * 0.12f, l * 0.12f, p)
                p.color = fundo
                c.drawCircle(x + l * 0.22f, y + l * 0.6f, l * 0.05f, p)
                c.drawCircle(x + l * 0.44f, y + l * 0.6f, l * 0.05f, p)
                c.drawCircle(x + l * 0.56f, y + l * 0.22f, l * 0.05f, p)
                c.drawCircle(x + l * 0.78f, y + l * 0.22f, l * 0.05f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = l * 0.05f
                c.drawArc(RectF(x + l * 0.2f, y + l * 0.62f, x + l * 0.46f, y + l * 0.84f), 20f, 140f, false, p)
                p.style = Paint.Style.FILL
            }
            Icone.MENSAGENS -> {
                // Dois balões de conversa.
                r.set(x, y + l * 0.12f, x + l * 0.62f, y + l * 0.58f)
                c.drawRoundRect(r, l * 0.1f, l * 0.1f, p)
                caminho.reset()
                caminho.moveTo(x + l * 0.12f, y + l * 0.55f)
                caminho.lineTo(x + l * 0.08f, y + l * 0.8f)
                caminho.lineTo(x + l * 0.32f, y + l * 0.56f)
                caminho.close()
                c.drawPath(caminho, p)
                p.color = fundo
                r.set(x + l * 0.33f, y + l * 0.34f, x + l * 1.04f, y + l * 0.84f)
                c.drawRoundRect(r, l * 0.12f, l * 0.12f, p)
                p.color = Color.WHITE
                r.set(x + l * 0.38f, y + l * 0.38f, x + l, y + l * 0.8f)
                c.drawRoundRect(r, l * 0.1f, l * 0.1f, p)
                caminho.reset()
                caminho.moveTo(x + l * 0.86f, y + l * 0.78f)
                caminho.lineTo(x + l * 0.92f, y + l)
                caminho.lineTo(x + l * 0.68f, y + l * 0.79f)
                caminho.close()
                c.drawPath(caminho, p)
            }
            Icone.FESTA -> {
                // Fone de ouvido.
                p.style = Paint.Style.STROKE
                p.strokeWidth = l * 0.09f
                c.drawArc(RectF(x + l * 0.12f, y + l * 0.08f, x + l * 0.88f, y + l * 0.84f), 180f, 180f, false, p)
                p.style = Paint.Style.FILL
                r.set(x + l * 0.06f, y + l * 0.46f, x + l * 0.3f, y + l * 0.82f)
                c.drawRoundRect(r, l * 0.08f, l * 0.08f, p)
                r.set(x + l * 0.7f, y + l * 0.46f, x + l * 0.94f, y + l * 0.82f)
                c.drawRoundRect(r, l * 0.08f, l * 0.08f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = l * 0.06f
                c.drawArc(RectF(x + l * 0.45f, y + l * 0.62f, x + l * 0.85f, y + l * 1.0f), 0f, 90f, false, p)
                p.style = Paint.Style.FILL
            }
            Icone.TROFEUS -> {
                caminho.reset()
                caminho.moveTo(x + l * 0.18f, y + l * 0.05f)
                caminho.lineTo(x + l * 0.82f, y + l * 0.05f)
                caminho.cubicTo(x + l * 0.82f, y + l * 0.5f, x + l * 0.62f, y + l * 0.6f, x + l * 0.56f, y + l * 0.62f)
                caminho.lineTo(x + l * 0.56f, y + l * 0.78f)
                caminho.lineTo(x + l * 0.72f, y + l * 0.92f)
                caminho.lineTo(x + l * 0.28f, y + l * 0.92f)
                caminho.lineTo(x + l * 0.44f, y + l * 0.78f)
                caminho.lineTo(x + l * 0.44f, y + l * 0.62f)
                caminho.cubicTo(x + l * 0.38f, y + l * 0.6f, x + l * 0.18f, y + l * 0.5f, x + l * 0.18f, y + l * 0.05f)
                caminho.close()
                c.drawPath(caminho, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = l * 0.07f
                c.drawArc(RectF(x, y + l * 0.1f, x + l * 0.3f, y + l * 0.42f), 90f, 180f, false, p)
                c.drawArc(RectF(x + l * 0.7f, y + l * 0.1f, x + l, y + l * 0.42f), 270f, 180f, false, p)
                p.style = Paint.Style.FILL
            }
            Icone.CONFIGURACOES -> {
                // Maleta de ferramentas (cheia, com a alça e a faixa).
                r.set(x + l * 0.02f, y + l * 0.3f, x + l * 0.98f, y + l * 0.9f)
                c.drawRoundRect(r, l * 0.06f, l * 0.06f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = l * 0.08f
                r.set(x + l * 0.33f, y + l * 0.1f, x + l * 0.67f, y + l * 0.34f)
                c.drawRoundRect(r, l * 0.05f, l * 0.05f, p)
                p.style = Paint.Style.FILL
                p.color = fundo
                c.drawRect(x + l * 0.02f, y + l * 0.52f, x + l * 0.98f, y + l * 0.57f, p)
                c.drawRect(x + l * 0.42f, y + l * 0.46f, x + l * 0.58f, y + l * 0.63f, p)
            }
            Icone.ENERGIA -> linhas {
                c.drawArc(RectF(x + l * 0.1f, y + l * 0.12f, x + l * 0.9f, y + l * 0.92f), 300f, 300f, false, p)
                c.drawLine(x + l * 0.5f, y + l * 0.02f, x + l * 0.5f, y + l * 0.48f, p)
            }
            Icone.LOJA -> linhas {
                r.set(x + l * 0.12f, y + l * 0.3f, x + l * 0.88f, y + l * 0.95f)
                c.drawRoundRect(r, l * 0.06f, l * 0.06f, p)
                c.drawArc(RectF(x + l * 0.3f, y + l * 0.05f, x + l * 0.7f, y + l * 0.5f), 180f, 180f, false, p)
            }
            Icone.BIBLIOTECA -> linhas {
                for (i in 0..2) for (j in 0..2) {
                    val q = l * 0.26f
                    r.set(x + i * (q + l * 0.1f), y + j * (q + l * 0.1f), x + i * (q + l * 0.1f) + q, y + j * (q + l * 0.1f) + q)
                    c.drawRect(r, p)
                }
            }
            Icone.TODOS -> for (i in 0..1) for (j in 0..1) {
                val q = l * 0.44f
                r.set(x + i * (q + l * 0.12f), y + j * (q + l * 0.12f), x + i * (q + l * 0.12f) + q, y + j * (q + l * 0.12f) + q)
                c.drawRect(r, p)
            }
            Icone.APPS -> linhas {
                for (i in 0..1) for (j in 0..1) {
                    val q = l * 0.4f
                    r.set(x + i * (q + l * 0.16f), y + j * (q + l * 0.16f), x + i * (q + l * 0.16f) + q, y + j * (q + l * 0.16f) + q)
                    if (i == 1 && j == 0) c.drawRoundRect(r, q / 2, q / 2, p) else c.drawRect(r, p)
                }
            }
            Icone.JOGOS -> {
                // Controle visto de cima.
                caminho.reset()
                caminho.moveTo(x + l * 0.2f, y + l * 0.28f)
                caminho.lineTo(x + l * 0.8f, y + l * 0.28f)
                caminho.cubicTo(x + l * 0.98f, y + l * 0.3f, x + l * 1.04f, y + l * 0.8f, x + l * 0.9f, y + l * 0.82f)
                caminho.cubicTo(x + l * 0.78f, y + l * 0.84f, x + l * 0.7f, y + l * 0.62f, x + l * 0.62f, y + l * 0.6f)
                caminho.lineTo(x + l * 0.38f, y + l * 0.6f)
                caminho.cubicTo(x + l * 0.3f, y + l * 0.62f, x + l * 0.22f, y + l * 0.84f, x + l * 0.1f, y + l * 0.82f)
                caminho.cubicTo(x - l * 0.04f, y + l * 0.8f, x + l * 0.02f, y + l * 0.3f, x + l * 0.2f, y + l * 0.28f)
                caminho.close()
                c.drawPath(caminho, p)
                p.color = fundo
                c.drawRect(x + l * 0.2f, y + l * 0.4f, x + l * 0.34f, y + l * 0.44f, p)
                c.drawRect(x + l * 0.25f, y + l * 0.35f, x + l * 0.29f, y + l * 0.49f, p)
                c.drawCircle(x + l * 0.72f, y + l * 0.38f, l * 0.035f, p)
                c.drawCircle(x + l * 0.72f, y + l * 0.5f, l * 0.035f, p)
                c.drawCircle(x + l * 0.66f, y + l * 0.44f, l * 0.035f, p)
                c.drawCircle(x + l * 0.78f, y + l * 0.44f, l * 0.035f, p)
            }
            Icone.PLATAFORMA -> linhas {
                // Disco.
                c.drawCircle(x + l / 2, y + l / 2, l * 0.44f, p)
                c.drawCircle(x + l / 2, y + l / 2, l * 0.12f, p)
            }
            Icone.NAVEGADOR -> {
                for (k in 0 until 10) {
                    val a = k * Math.PI * 2 / 10
                    c.drawCircle(x + l / 2 + (l * 0.48f * cos(a)).toFloat(), y + l / 2 + (l * 0.48f * sin(a)).toFloat(), l * 0.035f, p)
                }
                val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    textSize = l * 0.3f
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                }
                c.drawText("www", x + l / 2, y + l * 0.6f, t)
            }
            Icone.CAPTURAS -> linhas {
                r.set(x, y + l * 0.25f, x + l * 0.62f, y + l * 0.85f)
                c.drawRoundRect(r, l * 0.06f, l * 0.06f, p)
                c.drawCircle(x + l * 0.31f, y + l * 0.55f, l * 0.15f, p)
                r.set(x + l * 0.45f, y + l * 0.05f, x + l, y + l * 0.45f)
                c.drawRect(r, p)
                c.drawLine(x + l * 0.45f, y + l * 0.18f, x + l, y + l * 0.18f, p)
                c.drawLine(x + l * 0.45f, y + l * 0.32f, x + l, y + l * 0.32f, p)
            }
            Icone.BUSCAR -> linhas {
                c.drawCircle(x + l * 0.42f, y + l * 0.42f, l * 0.3f, p)
                c.drawLine(x + l * 0.64f, y + l * 0.64f, x + l * 0.95f, y + l * 0.95f, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    private inline fun linhas(f: () -> Unit) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        f()
        p.style = Paint.Style.FILL
    }

    /** Botão do controle desenhado num círculo (Ⓧ, ◯) para as dicas de baixo. */
    fun botao(c: Canvas, tipo: Char, cx: Float, cy: Float, raio: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = raio * 0.14f
        p.color = Color.WHITE
        c.drawCircle(cx, cy, raio, p)
        when (tipo) {
            'X' -> {
                p.color = 0xFF9FB8FF.toInt()
                c.drawLine(cx - raio * 0.42f, cy - raio * 0.42f, cx + raio * 0.42f, cy + raio * 0.42f, p)
                c.drawLine(cx + raio * 0.42f, cy - raio * 0.42f, cx - raio * 0.42f, cy + raio * 0.42f, p)
            }
            'O' -> {
                p.color = 0xFFFF8A80.toInt()
                c.drawCircle(cx, cy, raio * 0.48f, p)
            }
            'T' -> {
                p.color = 0xFF7FE0C4.toInt()
                caminho.reset()
                caminho.moveTo(cx, cy - raio * 0.48f)
                caminho.lineTo(cx + raio * 0.48f, cy + raio * 0.36f)
                caminho.lineTo(cx - raio * 0.48f, cy + raio * 0.36f)
                caminho.close()
                c.drawPath(caminho, p)
            }
        }
        p.style = Paint.Style.FILL
    }

    fun seta(c: Canvas, cx: Float, cy: Float, l: Float) {
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        caminho.reset()
        caminho.moveTo(cx - l * 0.35f, cy - l)
        caminho.lineTo(cx + l * 0.35f, cy - l)
        caminho.lineTo(cx + l * 0.35f, cy)
        caminho.lineTo(cx + l * 0.8f, cy)
        caminho.lineTo(cx, cy + l)
        caminho.lineTo(cx - l * 0.8f, cy)
        caminho.lineTo(cx - l * 0.35f, cy)
        caminho.close()
        c.drawPath(caminho, p)
    }

    /** "⌄" do seletor de ordem. */
    fun chevron(c: Canvas, cx: Float, cy: Float, l: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = l * 0.18f
        p.color = Color.WHITE
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - l, cy - l * 0.5f, cx, cy + l * 0.5f, p)
        c.drawLine(cx, cy + l * 0.5f, cx + l, cy - l * 0.5f, p)
        p.style = Paint.Style.FILL
    }
}

/** Fundo azul com ondas do estilo PS4 como uma View (para telas montadas com Views comuns). */
class FundoPs4(ctx: android.content.Context, private val animado: Boolean = true) : android.view.View(ctx) {
    override fun onDraw(c: Canvas) {
        val agora = android.os.SystemClock.uptimeMillis()
        val fase = if (animado) (agora % 60_000L) / 60_000f * (2 * Math.PI).toFloat() else 0f
        Ps4Desenho.fundo(c, width.toFloat(), height.toFloat(), fase)
        if (animado && isShown) postInvalidateDelayed(40)
    }
}

/** Um ícone do estilo PS4 como View. */
class IconePs4(ctx: android.content.Context, private val icone: Ps4Desenho.Icone, private val fundo: Int = Ps4Desenho.AZUL_FUNDO) : android.view.View(ctx) {
    override fun onDraw(c: Canvas) = Ps4Desenho.icone(c, icone, 0f, 0f, minOf(width, height).toFloat(), fundo)
}

/** Seleção do estilo PS4 como Drawable: fundo um pouco mais claro e a moldura azul brilhante. */
class SelecaoPs4(private val u: Float, private val linhaEmbaixo: Boolean = false) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        if (focado) {
            p.color = Color.argb(45, 255, 255, 255)
            c.drawRect(b, p)
            Ps4Desenho.brilho(c, b.left + 3 * u, b.top + 3 * u, b.right - 3 * u, b.bottom - 3 * u, u)
        } else if (linhaEmbaixo) {
            p.color = Color.argb(45, 255, 255, 255)
            c.drawRect(b.left.toFloat(), b.bottom - 1.2f * u, b.right.toFloat(), b.bottom.toFloat(), p)
        }
    }
    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focado) return false
        focado = f
        invalidateSelf()
        return true
    }
    override fun isStateful() = true
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Barra de dicas de baixo ("ⓧ Entrar  ◯ Voltar"), com a linha em cima. */
class DicasPs4(ctx: android.content.Context, private val dicas: List<Pair<Char, String>>, private val direita: String? = null) : android.view.View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val u = width / 1280f
        p.color = Color.argb(70, 255, 255, 255)
        c.drawRect(0f, 0f, width.toFloat(), 1.5f * u, p)
        val cy = height / 2f + 2 * u
        var x = 84f * u
        t.typeface = Ps4Desenho.normal
        t.textSize = 19 * u
        t.color = Color.WHITE
        for ((b, texto) in dicas) {
            Ps4Desenho.botao(c, b, x, cy, 10 * u)
            c.drawText(texto, x + 18 * u, cy + 7 * u, t)
            x += 18 * u + t.measureText(texto) + 40 * u
        }
        direita?.let {
            val lx = 1186 * u - t.measureText(it)
            Ps4Desenho.avatar(c, it, lx - 34 * u, cy - 13 * u, 26 * u, t)
            t.textSize = 19 * u
            t.color = Color.WHITE
            c.drawText(it, lx, cy + 7 * u, t)
        }
    }
}

/** O estilo de tela escolhido (Temas e sons › Tela inicial): o PS4 muda as telas e menus do app inteiro. */
object Estilo {
    fun ps4(ctx: android.content.Context) = ctx.getSharedPreferences("nova_home", android.content.Context.MODE_PRIVATE).getInt("layout_inicio", 0) == 1
    fun xbox(ctx: android.content.Context) = ctx.getSharedPreferences("nova_home", android.content.Context.MODE_PRIVATE).getInt("layout_inicio", 0) == 2

    /** Ícone de cada pasta das configurações no estilo PS4. */
    fun iconeSecao(nome: String): Ps4Desenho.Icone = when (nome) {
        "Aparência" -> Ps4Desenho.Icone.TODOS
        "Apps" -> Ps4Desenho.Icone.APPS
        "Backup" -> Ps4Desenho.Icone.PLATAFORMA
        "Contas e chaves", "Perfis e controle parental" -> Ps4Desenho.Icone.AMIGOS
        "Descanso de tela" -> Ps4Desenho.Icone.CAPTURAS
        "Emuladores", "Jogos", "Luz", "Vibração", "Atalhos" -> Ps4Desenho.Icone.JOGOS
        "Menu rápido" -> Ps4Desenho.Icone.MENSAGENS
        "Tela do aparelho" -> Ps4Desenho.Icone.NAVEGADOR
        "Tela inicial" -> Ps4Desenho.Icone.BIBLIOTECA
        "Temas e sons" -> Ps4Desenho.Icone.FESTA
        "Trailers" -> Ps4Desenho.Icone.CAPTURAS
        "Sistema" -> Ps4Desenho.Icone.ENERGIA
        else -> Ps4Desenho.Icone.CONFIGURACOES
    }
}
