package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Tela inicial no estilo do Wii, desenhada inteira aqui:
 * - fundo claro com listras finas;
 * - grade 4 × 3 de "canais" (molduras arredondadas cinza-claro) com páginas e setas nas laterais;
 * - faixa curva embaixo com o relógio grande, a data e dois botões redondos de cada lado.
 * O canal selecionado cresce um pouco, com a borda azul-clara. Medidas pensadas numa tela de 1280 × 720.
 */
class InicioWii(
    ctx: Context, private val canais: List<InicioPs4.Item>, private val botoes: List<InicioSwitch.Botao>,
    private val ouvinte: Ouvinte,
) : View(ctx), TelaPs4 {

    interface Ouvinte {
        fun selecionou(item: InicioPs4.Item)
        fun relogio(): String
        fun data(): String
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    companion object {
        const val AZUL = 0xFF34BEED.toInt()
        const val CINZA = 0xFF8A9198.toInt()
        const val TEXTO = 0xFF55595C.toInt()

        /** Fundo claro com listras finas (usado também nas configurações no estilo Wii). */
        fun fundo(c: Canvas, w: Float, h: Float, p: Paint) {
            c.drawColor(0xFFE9EDF0.toInt())
            p.color = Color.argb(12, 0, 0, 0)
            var y = 0f
            while (y < h) {
                c.drawRect(0f, y, w, y + 2f, p)
                y += 7f
            }
        }
    }

    private enum class Zona { CANAIS, BOTOES }

    private val POR_PAGINA = 12
    private val paginas = max(1, (canais.size + POR_PAGINA - 1) / POR_PAGINA)
    private var pagina = 0
    private var sel = 0
    private var selBotao = 0
    private var zona = Zona.CANAIS
    private var trocaIni = 0L
    private var trocaDir = 0

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val caminho = Path()
    private val leve = Typeface.create("sans-serif-light", Typeface.NORMAL)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

    // ---------- Geometria ----------

    private val CX0 = 92f
    private val CY0 = 48f
    private val CW = 258f
    private val CH = 144f
    private val CV = 22f

    private fun ret(i: Int, desl: Float = 0f): RectF {
        val col = i % 4
        val lin = i / 4
        val x = CX0 + col * (CW + CV) + desl
        val y = CY0 + lin * (CH + CV)
        return RectF(x * u, y * u, (x + CW) * u, (y + CH) * u)
    }

    private fun xBotao(i: Int): Float = when {
        botoes.size <= 1 -> 110f
        i < botoes.size / 2 -> 100f + i * 120f
        else -> 1180f - (botoes.size - 1 - i) * 120f
    }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        fundo(c, width.toFloat(), height.toFloat(), p)
        val pt = ((SystemClock.uptimeMillis() - trocaIni) / 280f).coerceIn(0f, 1f)
        val desl = (1f - pt) * (1f - pt) * 1200f * trocaDir
        for (i in 0 until POR_PAGINA) {
            val idx = pagina * POR_PAGINA + i
            if (zona == Zona.CANAIS && i == sel) continue
            canal(c, ret(i, desl), canais.getOrNull(idx), false)
        }
        if (zona == Zona.CANAIS) {
            val rr = ret(sel, desl)
            val g = 1.06f
            val cx = rr.centerX()
            val cy = rr.centerY()
            rr.set(cx - rr.width() / 2 * g, cy - rr.height() / 2 * g, cx + rr.width() / 2 * g, cy + rr.height() / 2 * g)
            canal(c, rr, canais.getOrNull(pagina * POR_PAGINA + sel), true)
        }
        setas(c)
        faixa(c)
        if (pt < 1f) postInvalidateOnAnimation() else postInvalidateDelayed(1000)
    }

    private fun canal(c: Canvas, r0: RectF, it: InicioPs4.Item?, focado: Boolean) {
        val raio = 26f * u
        // Moldura: branca com borda cinza (azul na seleção).
        p.color = Color.WHITE
        c.drawRoundRect(r0, raio, raio, p)
        val dentro = RectF(r0.left + 8 * u, r0.top + 8 * u, r0.right - 8 * u, r0.bottom - 8 * u)
        if (it == null) {
            p.color = 0xFFDDE2E6.toInt()
            c.drawRoundRect(dentro, raio * 0.7f, raio * 0.7f, p)
        } else {
            c.save()
            caminho.reset()
            caminho.addRoundRect(dentro, raio * 0.7f, raio * 0.7f, Path.Direction.CW)
            c.clipPath(caminho)
            val img = it.imagem?.let { url -> imagem(url) }
            when {
                img != null -> Ps4Desenho.cortado(c, img, dentro)
                it.simbolo != null -> {
                    p.shader = LinearGradient(0f, dentro.top, 0f, dentro.bottom, 0xFF58C8F0.toInt(), 0xFF1E90C8.toInt(), Shader.TileMode.CLAMP)
                    c.drawRect(dentro, p)
                    p.shader = null
                    val l = dentro.height() * 0.5f
                    Ps4Desenho.icone(c, it.simbolo, dentro.centerX() - l / 2, dentro.centerY() - l / 2 - 8 * u, l, 0xFF3AA8DC.toInt())
                }
                else -> {
                    p.shader = LinearGradient(0f, dentro.top, 0f, dentro.bottom, Color.WHITE, 0xFFE3E8EC.toInt(), Shader.TileMode.CLAMP)
                    c.drawRect(dentro, p)
                    p.shader = null
                    it.icone?.let { ic ->
                        val l = dentro.height() * 0.56f
                        c.drawBitmap(ic, null, RectF(dentro.centerX() - l / 2, dentro.centerY() - l / 2 - 6 * u, dentro.centerX() + l / 2, dentro.centerY() + l / 2 - 6 * u), pImg)
                    }
                }
            }
            if (focado || it.imagem == null) {
                // Nome no pé do canal (sempre nos sem imagem; no selecionado, por cima da arte).
                p.color = Color.argb(if (it.imagem == null) 0 else 150, 255, 255, 255)
                c.drawRect(dentro.left, dentro.bottom - 30 * u, dentro.right, dentro.bottom, p)
                t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = 15 * u
                t.color = TEXTO
                t.textAlign = Paint.Align.CENTER
                c.drawText(TextUtils.ellipsize(it.nome, t, dentro.width() - 16 * u, TextUtils.TruncateAt.END).toString(), dentro.centerX(), dentro.bottom - 10 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
            c.restore()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (focado) 5f else 3f) * u
        p.color = if (focado) AZUL else 0xFFB8C1C9.toInt()
        c.drawRoundRect(r0, raio, raio, p)
        p.style = Paint.Style.FILL
    }

    private fun setas(c: Canvas) {
        if (paginas <= 1) return
        p.color = if (pagina > 0) AZUL else 0x3334BEED
        seta(c, 46f * u, 270f * u, -1)
        p.color = if (pagina < paginas - 1) AZUL else 0x3334BEED
        seta(c, 1234f * u, 270f * u, 1)
    }

    private fun seta(c: Canvas, cx: Float, cy: Float, dir: Int) {
        caminho.reset()
        caminho.moveTo(cx + dir * 16 * u, cy)
        caminho.lineTo(cx - dir * 12 * u, cy - 24 * u)
        caminho.lineTo(cx - dir * 12 * u, cy + 24 * u)
        caminho.close()
        c.drawPath(caminho, p)
    }

    /** Faixa curva de baixo: relógio grande, data e os botões redondos. */
    private fun faixa(c: Canvas) {
        caminho.reset()
        val topo = 548f * u
        caminho.moveTo(0f, topo + 40 * u)
        caminho.cubicTo(width * 0.22f, topo + 40 * u, width * 0.3f, topo - 10 * u, width * 0.5f, topo - 10 * u)
        caminho.cubicTo(width * 0.7f, topo - 10 * u, width * 0.78f, topo + 40 * u, width.toFloat(), topo + 40 * u)
        caminho.lineTo(width.toFloat(), height.toFloat())
        caminho.lineTo(0f, height.toFloat())
        caminho.close()
        p.shader = LinearGradient(0f, topo, 0f, height.toFloat(), 0xFFF7F9FA.toInt(), 0xFFE0E5E9.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(caminho, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * u
        p.color = 0xFFB8C1C9.toInt()
        c.drawPath(caminho, p)
        p.style = Paint.Style.FILL
        t.typeface = leve
        t.textSize = 64 * u
        t.color = CINZA
        t.textAlign = Paint.Align.CENTER
        c.drawText(ouvinte.relogio(), width / 2f, 630 * u, t)
        t.textSize = 22 * u
        c.drawText(ouvinte.data(), width / 2f, 668 * u, t)
        t.textAlign = Paint.Align.LEFT
        for ((i, b) in botoes.withIndex()) {
            val cx = xBotao(i) * u
            val cy = 636f * u
            val raio = 46f * u
            p.color = Color.WHITE
            c.drawCircle(cx, cy, raio, p)
            p.style = Paint.Style.STROKE
            val focado = zona == Zona.BOTOES && i == selBotao
            p.strokeWidth = (if (focado) 5f else 3f) * u
            p.color = if (focado) AZUL else 0xFFB8C1C9.toInt()
            c.drawCircle(cx, cy, raio, p)
            p.style = Paint.Style.FILL
            // Ícone num círculo colorido por dentro.
            p.color = b.cor
            c.drawCircle(cx, cy, raio * 0.66f, p)
            val l = raio * 0.72f
            Ps4Desenho.icone(c, b.icone, cx - l / 2, cy - l / 2, l, b.cor)
            if (focado) {
                t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = 15 * u
                t.color = TEXTO
                t.textAlign = Paint.Align.CENTER
                c.drawText(b.nome, cx, cy - raio - 10 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
        }
    }

    private fun imagem(url: String): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) ouvinte.carregar(url, (CW * u).toInt().coerceAtLeast(64), (CH * u).toInt().coerceAtLeast(48)) { b -> imagens[url] = b; invalidate() }
        return null
    }

    // ---------- Controle ----------

    private fun naPagina(i: Int) = pagina * POR_PAGINA + i < canais.size

    private fun mover(f: () -> Unit) {
        f()
        Sons.tocar(Sons.Evento.MOVER)
        canais.getOrNull(pagina * POR_PAGINA + sel)?.let { if (zona == Zona.CANAIS) ouvinte.selecionou(it) }
        invalidate()
    }

    private fun trocarPagina(passo: Int, colunaNova: Int) {
        val nova = pagina + passo
        if (nova !in 0 until paginas) return
        pagina = nova
        trocaIni = SystemClock.uptimeMillis()
        trocaDir = passo
        val lin = sel / 4
        sel = lin * 4 + colunaNova
        while (sel > 0 && !naPagina(sel)) sel--
        mover {}
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (zona == Zona.BOTOES) mover { selBotao = max(0, selBotao - 1) }
                else if (sel % 4 > 0) mover { sel-- } else trocarPagina(-1, 3)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (zona == Zona.BOTOES) mover { selBotao = min(botoes.lastIndex, selBotao + 1) }
                else if (sel % 4 < 3 && naPagina(sel + 1)) mover { sel++ } else trocarPagina(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (zona == Zona.BOTOES) mover { zona = Zona.CANAIS } else if (sel >= 4) mover { sel -= 4 }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (zona == Zona.CANAIS) {
                if (sel + 4 < POR_PAGINA && naPagina(sel + 4)) mover { sel += 4 }
                else if (botoes.isNotEmpty()) mover { zona = Zona.BOTOES; selBotao = if (sel % 4 < 2) 0 else botoes.lastIndex }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            KeyEvent.KEYCODE_BUTTON_L1 -> trocarPagina(-1, sel % 4)
            KeyEvent.KEYCODE_BUTTON_R1 -> trocarPagina(1, sel % 4)
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun ativar() {
        if (zona == Zona.BOTOES) botoes.getOrNull(selBotao)?.acao?.invoke()
        else canais.getOrNull(pagina * POR_PAGINA + sel)?.abrir?.invoke()
    }

    /** Página seguinte/anterior (L1/R1 no launcher). */
    fun pagina(passo: Int) = trocarPagina(passo, sel % 4)

    override fun informacoes() { if (zona == Zona.CANAIS) canais.getOrNull(pagina * POR_PAGINA + sel)?.info?.invoke() }

    override fun voltar(): Boolean {
        when {
            zona == Zona.BOTOES -> mover { zona = Zona.CANAIS }
            pagina != 0 -> trocarPagina(-pagina, 0)
            sel != 0 -> mover { sel = 0 }
            else -> return false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        if (zona == Zona.CANAIS) canais.getOrNull(pagina * POR_PAGINA + sel)?.opcoes?.invoke()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x
        val y = e.y
        for (i in 0 until POR_PAGINA) if (ret(i).contains(x, y) && naPagina(i)) {
            if (zona == Zona.CANAIS && sel == i) ativar() else mover { zona = Zona.CANAIS; sel = i }
            return true
        }
        for (i in botoes.indices) {
            val cx = xBotao(i) * u
            if (kotlin.math.abs(x - cx) < 50 * u && kotlin.math.abs(y - 636 * u) < 50 * u) {
                if (zona == Zona.BOTOES && selBotao == i) ativar() else mover { zona = Zona.BOTOES; selBotao = i }
                return true
            }
        }
        if (y in 240 * u..300 * u) {
            if (x < 70 * u) trocarPagina(-1, 0) else if (x > 1210 * u) trocarPagina(1, 0)
        }
        return true
    }

    fun atualizar() = invalidate()
}
