package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tela inicial no estilo do Switch, desenhada inteira aqui (tema claro):
 * - perfis em círculos no canto de cima, relógio, Wi-Fi e bateria à direita;
 * - fileira de quadrados grandes; o selecionado tem a borda azul-clara pulsando e o nome em cima;
 * - fileira de botões redondos coloridos embaixo (nome embaixo do selecionado);
 * - linha fina e as dicas "Ⓐ OK" no rodapé;
 * - o último quadrado ("Todos os programas") abre a grade com todos os jogos.
 * Ícones e cores próprios (nada da Nintendo). Medidas pensadas numa tela de 1280 × 720.
 */
class InicioSwitch(
    ctx: Context, private val jogos: List<InicioPs4.Item>, private val todos: List<InicioPs4.Item>,
    private val botoes: List<Botao>, private val perfis: List<Pair<String, () -> Unit>>, private val perfilAtual: Int,
    private val ouvinte: Ouvinte,
) : View(ctx), TelaPs4 {

    class Botao(val icone: Ps4Desenho.Icone, val nome: String, val cor: Int, val acao: () -> Unit)

    interface Ouvinte {
        fun selecionou(item: InicioPs4.Item)
        fun relogio(): String
        fun bateria(): Int
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    companion object {
        const val CIANO = 0xFF00BBDD.toInt()
        const val FUNDO = 0xFFEBEBEB.toInt()
        const val TEXTO = 0xFF2D2D2D.toInt()
        const val SUAVE = 0xFF707070.toInt()
        val normal: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val medio: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        /** A borda azul-clara pulsando do item selecionado (com o vão branco por dentro). */
        fun borda(c: Canvas, r: RectF, u: Float, p: Paint, raio: Float = 0f) {
            val pulso = 0.55f + 0.45f * (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 260.0).toFloat())
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * u
            p.color = Color.WHITE
            c.drawRoundRect(r.left - 2.5f * u, r.top - 2.5f * u, r.right + 2.5f * u, r.bottom + 2.5f * u, raio, raio, p)
            p.strokeWidth = 5 * u
            p.color = Color.argb((255 * pulso).toInt(), 0, 0xBB, 0xDD)
            c.drawRoundRect(r.left - 6.5f * u, r.top - 6.5f * u, r.right + 6.5f * u, r.bottom + 6.5f * u, raio + 4 * u, raio + 4 * u, p)
            p.style = Paint.Style.FILL
        }
    }

    private enum class Zona { PERFIS, JOGOS, BOTOES, TODOS }

    private var zona = Zona.JOGOS
    private var selJogo = 0
    private var selBotao = 0
    private var selPerfil = perfilAtual
    private var selTodos = 0
    private var rolagem = 0f
    private var rolagemAlvo = 0f
    private var rolagemTodos = 0f

    // A fileira: os jogos e, no fim, "Todos os programas".
    private val fileira: List<InicioPs4.Item?> = jogos + listOf(null)

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fileira.getOrNull(selJogo)?.let { ouvinte.selecionou(it) }
    }

    // ---------- Geometria ----------

    private val LADO = 256f
    private val VAO = 16f
    private val X0 = 92f
    private val Y_FILEIRA = 172f

    private fun xJogo(i: Int) = X0 + i * (LADO + VAO)

    private fun xBotao(i: Int): Float {
        val total = botoes.size * 72f + (botoes.size - 1) * 34f
        return (1280f - total) / 2f + i * 106f
    }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(FUNDO)
        rolagem += (rolagemAlvo - rolagem) * 0.3f
        if (abs(rolagemAlvo - rolagem) < 0.5f) rolagem = rolagemAlvo
        if (zona == Zona.TODOS) todosOsProgramas(c) else inicio(c)
        // A borda pulsa sempre: redesenha ~30 vezes por segundo enquanto a tela está à vista.
        postInvalidateDelayed(33)
    }

    private fun inicio(c: Canvas) {
        perfis(c)
        topoDireita(c)
        // Nome do selecionado em cima da fileira.
        if (zona == Zona.JOGOS) {
            val nome = fileira.getOrNull(selJogo)?.nome ?: "Todos os programas"
            t.typeface = medio
            t.textSize = 22 * u
            t.color = CIANO
            val x = max(X0, xJogo(selJogo) - rolagem)
            c.drawText(TextUtils.ellipsize(nome, t, 900 * u, TextUtils.TruncateAt.END).toString(), x * u, (Y_FILEIRA - 18f) * u, t)
        }
        c.save()
        c.translate(-rolagem * u, 0f)
        for (i in fileira.indices) {
            val x = xJogo(i)
            if ((x - rolagem) * u > width || (x + LADO - rolagem) * u < 0) continue
            r.set(x * u, Y_FILEIRA * u, (x + LADO) * u, (Y_FILEIRA + LADO) * u)
            val it = fileira[i]
            if (it == null) {
                // "Todos os programas": quadrado claro com a grade de quadradinhos.
                p.color = Color.WHITE
                c.drawRect(r, p)
                p.color = 0xFF8C8C8C.toInt()
                val q = 22f * u
                for (a in 0..2) for (b in 0..2) c.drawRect(r.centerX() - 40 * u + a * 28 * u, r.centerY() - 52 * u + b * 28 * u,
                    r.centerX() - 40 * u + a * 28 * u + q, r.centerY() - 52 * u + b * 28 * u + q, p)
                t.typeface = normal
                t.textSize = 17 * u
                t.color = TEXTO
                t.textAlign = Paint.Align.CENTER
                c.drawText("Todos os programas", r.centerX(), r.bottom - 34 * u, t)
                t.textAlign = Paint.Align.LEFT
            } else quadradoJogo(c, it, r)
            if (zona == Zona.JOGOS && i == selJogo) borda(c, r, u, p)
        }
        c.restore()
        botoes(c)
        rodape(c)
    }

    private fun quadradoJogo(c: Canvas, it: InicioPs4.Item, r: RectF) {
        val img = it.imagem?.let { url -> imagem(url) }
        when {
            img != null -> Ps4Desenho.cortado(c, img, r)
            else -> {
                p.color = Paleta.tom(it.cor, 0.7f)
                c.drawRect(r, p)
                it.icone?.let { ic ->
                    val l = r.width() * 0.4f
                    c.drawBitmap(ic, null, RectF(r.centerX() - l / 2, r.centerY() - l / 2, r.centerX() + l / 2, r.centerY() + l / 2), pImg)
                }
                if (it.icone == null) {
                    t.typeface = medio
                    t.textSize = 18 * u
                    t.color = Color.WHITE
                    t.textAlign = Paint.Align.CENTER
                    c.drawText(TextUtils.ellipsize(it.nome, t, r.width() - 20 * u, TextUtils.TruncateAt.END).toString(), r.centerX(), r.centerY(), t)
                    t.textAlign = Paint.Align.LEFT
                }
            }
        }
    }

    private fun perfis(c: Canvas) {
        for ((i, pf) in perfis.withIndex()) {
            val cx = (92f + 27f + i * 68f) * u
            val cy = 70f * u
            p.color = if (i == perfilAtual) Paleta.DESTAQUE else 0xFFBDBDBD.toInt()
            c.drawCircle(cx, cy, 27 * u, p)
            t.typeface = medio
            t.textSize = 24 * u
            t.color = Color.WHITE
            t.textAlign = Paint.Align.CENTER
            c.drawText(pf.first.take(1).uppercase(), cx, cy + 8.5f * u, t)
            t.textAlign = Paint.Align.LEFT
            if (zona == Zona.PERFIS && i == selPerfil) {
                r.set(cx - 27 * u, cy - 27 * u, cx + 27 * u, cy + 27 * u)
                borda(c, r, u, p, 27 * u)
                t.typeface = normal
                t.textSize = 15 * u
                t.color = TEXTO
                t.textAlign = Paint.Align.CENTER
                c.drawText(pf.first, cx, cy + 50 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
        }
    }

    /** Relógio, Wi-Fi e bateria. */
    private fun topoDireita(c: Canvas) {
        val y = 78f * u
        // Bateria.
        val bat = ouvinte.bateria().coerceIn(0, 100)
        val bx = 1176f * u
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * u
        p.color = TEXTO
        r.set(bx, y - 15 * u, bx + 34 * u, y + 1 * u)
        c.drawRoundRect(r, 3 * u, 3 * u, p)
        p.style = Paint.Style.FILL
        c.drawRect(bx + 34 * u, y - 10 * u, bx + 37 * u, y - 4 * u, p)
        c.drawRect(bx + 3 * u, y - 12 * u, bx + 3 * u + 28 * u * bat / 100f, y - 2 * u, p)
        t.typeface = normal
        t.textSize = 17 * u
        t.color = TEXTO
        t.textAlign = Paint.Align.RIGHT
        c.drawText("$bat%", bx - 8 * u, y, t)
        // Wi-Fi (três arcos e o ponto).
        val wx = bx - 70 * u
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.6f * u
        for (k in 1..3) c.drawArc(wx - k * 6 * u, y - k * 6 * u, wx + k * 6 * u, y + k * 6 * u, 225f, 90f, false, p)
        p.style = Paint.Style.FILL
        c.drawCircle(wx, y - 1 * u, 2.4f * u, p)
        t.textSize = 24 * u
        c.drawText(ouvinte.relogio(), wx - 28 * u, y + 1 * u, t)
        t.textAlign = Paint.Align.LEFT
    }

    private fun botoes(c: Canvas) {
        val cy = 548f * u
        for ((i, b) in botoes.withIndex()) {
            val cx = (xBotao(i) + 36f) * u
            p.color = b.cor
            c.drawCircle(cx, cy, 36 * u, p)
            val l = 34 * u
            Ps4Desenho.icone(c, b.icone, cx - l / 2, cy - l / 2, l, b.cor)
            if (zona == Zona.BOTOES && i == selBotao) {
                r.set(cx - 36 * u, cy - 36 * u, cx + 36 * u, cy + 36 * u)
                borda(c, r, u, p, 36 * u)
                t.typeface = normal
                t.textSize = 17 * u
                t.color = CIANO
                t.textAlign = Paint.Align.CENTER
                c.drawText(b.nome, cx, cy + 66 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
        }
    }

    private fun rodape(c: Canvas) {
        p.color = 0xFFC8C8C8.toInt()
        c.drawRect(60 * u, 652 * u, 1220 * u, 653.5f * u, p)
        val cy = 684f * u
        var x = 1210f * u
        t.typeface = normal
        t.textSize = 18 * u
        t.textAlign = Paint.Align.RIGHT
        for ((letra, texto) in listOf('A' to "OK", 'Y' to "Opções").reversed()) {
            t.color = TEXTO
            c.drawText(texto, x, cy + 6 * u, t)
            x -= t.measureText(texto) + 8 * u
            p.color = TEXTO
            c.drawCircle(x - 11 * u, cy, 11 * u, p)
            t.color = Color.WHITE
            t.textSize = 13 * u
            t.textAlign = Paint.Align.CENTER
            c.drawText(letra.toString(), x - 11 * u, cy + 4.5f * u, t)
            t.textSize = 18 * u
            t.textAlign = Paint.Align.RIGHT
            x -= 50 * u
        }
        t.textAlign = Paint.Align.LEFT
    }

    // ---------- Todos os programas ----------

    private val COLS = 6
    private val TL = 168f
    private val TV = 18f

    private fun todosOsProgramas(c: Canvas) {
        t.typeface = normal
        t.textSize = 28 * u
        t.color = TEXTO
        c.drawText("Todos os programas", 92 * u, 80 * u, t)
        p.color = 0xFFC8C8C8.toInt()
        c.drawRect(60 * u, 104 * u, 1220 * u, 105.5f * u, p)
        val linhaSel = selTodos / COLS
        val alvo = max(0f, (linhaSel - 1) * (TL + TV + 30f))
        rolagemTodos += (alvo - rolagemTodos) * 0.3f
        c.save()
        c.clipRect(0f, 110 * u, width.toFloat(), 650 * u)
        for ((i, it) in todos.withIndex()) {
            val x = 92f + (i % COLS) * (TL + TV)
            val y = 130f + (i / COLS) * (TL + TV + 30f) - rolagemTodos
            if (y > 660f || y + TL < 100f) continue
            r.set(x * u, y * u, (x + TL) * u, (y + TL) * u)
            quadradoJogo(c, it, r)
            if (i == selTodos) {
                borda(c, r, u, p)
                t.typeface = normal
                t.textSize = 15 * u
                t.color = CIANO
                c.drawText(TextUtils.ellipsize(it.nome, t, TL * u, TextUtils.TruncateAt.END).toString(), r.left, r.bottom + 22 * u, t)
            }
        }
        c.restore()
        rodape(c)
    }

    private fun imagem(url: String): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) {
            val l = (LADO * u).toInt().coerceAtLeast(64)
            ouvinte.carregar(url, (l * 1.3f).toInt(), l) { b -> imagens[url] = b; invalidate() }
        }
        return null
    }

    // ---------- Controle ----------

    private fun mover(f: () -> Unit) {
        f()
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    private fun selecionarJogo(i: Int) {
        if (i !in fileira.indices) return
        selJogo = i
        val x = xJogo(i)
        if (x + LADO - rolagemAlvo > 1188f) rolagemAlvo = x + LADO - 1188f
        if (x - rolagemAlvo < X0) rolagemAlvo = max(0f, x - X0)
        fileira[i]?.let { ouvinte.selecionou(it) }
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        if (zona == Zona.TODOS) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (selTodos % COLS > 0) mover { selTodos-- }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (selTodos % COLS < COLS - 1 && selTodos + 1 < todos.size) mover { selTodos++ }
                KeyEvent.KEYCODE_DPAD_UP -> if (selTodos >= COLS) mover { selTodos -= COLS }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (selTodos + COLS < todos.size) mover { selTodos += COLS } else if (selTodos / COLS < (todos.size - 1) / COLS) mover { selTodos = todos.lastIndex }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
                else -> return super.onKeyDown(keyCode, e)
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zona) {
                Zona.JOGOS -> selecionarJogo(selJogo - 1)
                Zona.BOTOES -> mover { selBotao = max(0, selBotao - 1) }
                Zona.PERFIS -> mover { selPerfil = max(0, selPerfil - 1) }
                else -> {}
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zona) {
                Zona.JOGOS -> selecionarJogo(selJogo + 1)
                Zona.BOTOES -> mover { selBotao = min(botoes.lastIndex, selBotao + 1) }
                Zona.PERFIS -> mover { selPerfil = min(perfis.lastIndex, selPerfil + 1) }
                else -> {}
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (zona) {
                Zona.BOTOES -> mover { zona = Zona.JOGOS }
                Zona.JOGOS -> if (perfis.isNotEmpty()) mover { zona = Zona.PERFIS }
                else -> {}
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zona) {
                Zona.PERFIS -> mover { zona = Zona.JOGOS }
                Zona.JOGOS -> if (botoes.isNotEmpty()) mover { zona = Zona.BOTOES }
                else -> {}
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun ativar() {
        when (zona) {
            Zona.JOGOS -> {
                val it = fileira.getOrNull(selJogo)
                if (it == null) mover { zona = Zona.TODOS; selTodos = 0; rolagemTodos = 0f } else it.abrir()
            }
            Zona.BOTOES -> botoes.getOrNull(selBotao)?.acao?.invoke()
            Zona.PERFIS -> perfis.getOrNull(selPerfil)?.second?.invoke()
            Zona.TODOS -> todos.getOrNull(selTodos)?.abrir?.invoke()
        }
    }

    private fun itemAtual(): InicioPs4.Item? = when (zona) {
        Zona.JOGOS -> fileira.getOrNull(selJogo)
        Zona.TODOS -> todos.getOrNull(selTodos)
        else -> null
    }

    override fun informacoes() { itemAtual()?.info?.invoke() }

    override fun voltar(): Boolean {
        when {
            zona == Zona.TODOS -> mover { zona = Zona.JOGOS }
            zona != Zona.JOGOS -> mover { zona = Zona.JOGOS }
            selJogo != 0 -> selecionarJogo(0)
            else -> return false
        }
        return true
    }

    /** Abre a grade "Todos os programas" (aba Jogos). */
    fun abrirTodos() { zona = Zona.TODOS; selTodos = 0; rolagemTodos = 0f; invalidate() }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        itemAtual()?.opcoes?.invoke()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u
        if (zona == Zona.TODOS) {
            for (i in todos.indices) {
                val tx = 92f + (i % COLS) * (TL + TV)
                val ty = 130f + (i / COLS) * (TL + TV + 30f) - rolagemTodos
                if (x in tx..(tx + TL) && y in ty..(ty + TL)) {
                    if (i == selTodos) ativar() else mover { selTodos = i }
                    return true
                }
            }
            return true
        }
        if (y in Y_FILEIRA..(Y_FILEIRA + LADO)) {
            for (i in fileira.indices) {
                val jx = xJogo(i) - rolagem
                if (x in jx..(jx + LADO)) {
                    zona = Zona.JOGOS
                    if (i == selJogo) ativar() else selecionarJogo(i)
                    return true
                }
            }
        }
        if (y in 500f..600f) {
            for (i in botoes.indices) if (x in xBotao(i)..(xBotao(i) + 72f)) {
                zona = Zona.BOTOES
                if (selBotao == i) ativar() else mover { selBotao = i }
                return true
            }
        }
        if (y < 110f) {
            for (i in perfis.indices) {
                val cx = 92f + 27f + i * 68f
                if (abs(x - cx) < 30f) { zona = Zona.PERFIS; selPerfil = i; ativar(); return true }
            }
        }
        return true
    }

    @Suppress("unused")
    private fun sinal() = sin(0.0)

    fun atualizar() = invalidate()
}
