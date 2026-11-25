package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Grade de tela cheia no visual do console escolhido: "Todos os apps", "Biblioteca" e os resultados da busca
 * nos estilos que não têm uma tela própria para isso. Fica por cima da tela inicial; Voltar fecha.
 * [busca] != null mostra o campo de busca em cima (A nele pede outro texto).
 */
class GradeConsole(
    ctx: Context, private val vis: VisualConsole, private val titulo: String, private val itens: List<InicioPs4.Item>,
    private val busca: String?, private val ouvinte: Ouvinte,
) : FrameLayout(ctx) {

    interface Ouvinte {
        fun fechar()
        fun buscar(atual: String)
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    val grade = Grade(ctx)

    init {
        addView(vis.fundo(ctx), LayoutParams(-1, -1))
        addView(grade, LayoutParams(-1, -1))
        val u = ctx.resources.displayMetrics.widthPixels / 1280f
        vis.dicas?.invoke(ctx)?.let { addView(it, LayoutParams(-1, (88 * u).toInt(), android.view.Gravity.BOTTOM)) }
        isClickable = true
    }

    inner class Grade(ctx: Context) : View(ctx), TelaPs4 {
        private val e = vis.estilo
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val caminho = Path()
        private val imagens = HashMap<String, Bitmap>()
        private val pedidas = HashSet<String>()
        private var u = 1f
        /** -1 = o campo de busca. */
        private var sel = if (itens.isEmpty() && busca != null) -1 else 0
        private var rolagem = 0f
        private var rolagemAlvo = 0f

        private val COLS = 6
        private val LADO = 166f
        private val VAO = 22f
        private val PASSO_Y = LADO + 52f
        private val X0 = (1280f - (COLS * LADO + (COLS - 1) * VAO)) / 2f
        private val Y0 get() = if (busca != null) 196f else 134f
        private val raio get() = when (e) { 4 -> 18f; 5 -> 14f; 6 -> 6f; else -> 0f }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            contentDescription = titulo
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

        override fun onDraw(c: Canvas) {
            rolagem += (rolagemAlvo - rolagem) * 0.3f
            if (kotlin.math.abs(rolagemAlvo - rolagem) < 0.5f) rolagem = rolagemAlvo
            // Título e linha (como as outras telas do estilo).
            t.typeface = Ps4Desenho.leve
            t.textSize = vis.tituloTamanho * u
            t.color = vis.corTitulo
            c.drawText(if (vis.minusculo) titulo.lowercase() else titulo, 72 * u, 72 * u, t)
            p.color = vis.corLinha
            c.drawRect(0f, 92 * u, width.toFloat(), 93.5f * u, p)
            if (busca != null) campo(c)
            c.save()
            c.clipRect(0f, (Y0 - 24f) * u, width.toFloat(), 632 * u)
            c.translate(0f, -rolagem * u)
            for (i in itens.indices) if (i != sel) bloco(c, i, false)
            if (sel in itens.indices) bloco(c, sel, true)
            c.restore()
            if (itens.isEmpty()) {
                t.typeface = Ps4Desenho.normal
                t.textSize = 22 * u
                t.color = vis.corSub
                c.drawText(if (busca != null) "Nada encontrado para “$busca”." else "Nada aqui ainda.", 72 * u, (Y0 + 60) * u, t)
            }
            if (rolagem != rolagemAlvo) postInvalidateOnAnimation()
        }

        /** Campo de busca: a busca atual numa barra; no foco, com a seleção do estilo. */
        private fun campo(c: Canvas) {
            r.set(X0 * u, 112 * u, (1280f - X0) * u, 164 * u)
            val focado = sel == -1
            p.color = if (vis.corTexto == Color.WHITE) Color.argb(if (focado) 60 else 30, 255, 255, 255) else Color.argb(if (focado) 255 else 170, 255, 255, 255)
            val rr = if (e == 5 || e == 4) r.height() / 2 else raio * u
            c.drawRoundRect(r, rr, rr, p)
            if (focado) foco(c, r, rr)
            // Lupa branca num círculo (no fundo claro, o círculo é cinza para a lupa aparecer).
            val claro = vis.corTexto != Color.WHITE
            val l = 26 * u
            val cx = r.left + 34 * u
            p.color = if (claro) 0xFF7A8088.toInt() else Color.argb(50, 255, 255, 255)
            c.drawCircle(cx, r.centerY(), 19 * u, p)
            Ps4Desenho.icone(c, Ps4Desenho.Icone.BUSCAR, cx - l / 2, r.centerY() - l / 2, l, p.color)
            t.typeface = Ps4Desenho.normal
            t.textSize = 22 * u
            t.color = vis.corTexto
            c.drawText(busca.orEmpty(), r.left + 64 * u, r.centerY() + 8 * u, t)
            t.textSize = 16 * u
            t.color = vis.corSub
            t.textAlign = Paint.Align.RIGHT
            c.drawText("${itens.size} resultado" + (if (itens.size == 1) "" else "s"), r.right - 20 * u, r.centerY() + 6 * u, t)
            t.textAlign = Paint.Align.LEFT
        }

        private fun posicao(i: Int, out: RectF) {
            val col = i % COLS
            val lin = i / COLS
            val x = X0 + col * (LADO + VAO)
            val y = Y0 + lin * PASSO_Y
            out.set(x * u, y * u, (x + LADO) * u, (y + LADO) * u)
        }

        private fun bloco(c: Canvas, i: Int, focado: Boolean) {
            val it = itens[i]
            posicao(i, r)
            if ((r.bottom / u - rolagem) < Y0 - 200 || (r.top / u - rolagem) > 720) return
            val esc = when { !focado -> 1f; e == 5 -> 1.08f; e == 6 || e == 2 -> 1.05f; else -> 1f }
            if (esc != 1f) {
                val cx = r.centerX(); val cy = r.centerY()
                val w = r.width() * esc / 2; val h = r.height() * esc / 2
                r.set(cx - w, cy - h, cx + w, cy + h)
            }
            val rr = raio * u
            if (focado && (e == 2 || e == 5 || e == 6)) {
                p.color = Color.argb(70, 0, 0, 0)
                c.drawRoundRect(r.left + 3 * u, r.top + 7 * u, r.right + 3 * u, r.bottom + 9 * u, rr, rr, p)
            }
            c.save()
            if (rr > 0) {
                caminho.reset()
                caminho.addRoundRect(r, rr, rr, Path.Direction.CW)
                c.clipPath(caminho)
            }
            val img = it.imagem?.let { url -> imagem(url, !it.ehJogo) }
            when {
                img != null -> if (it.ehJogo) Ps4Desenho.cortado(c, img, r) else Ps4Desenho.banner(c, img, r)
                it.simbolo != null -> {
                    val cor = when (e) { 2 -> InicioXbox.VERDE; 6 -> 0xFF2B2E33.toInt(); 5 -> 0xFF26282E.toInt(); 3 -> 0xFFD8D8D8.toInt(); 4 -> Color.WHITE; else -> Ps4Desenho.AZUL_FUNDO }
                    p.color = cor
                    c.drawRect(r, p)
                    val l = r.width() * 0.4f
                    Ps4Desenho.icone(c, it.simbolo, r.centerX() - l / 2, r.centerY() - l / 2, l, cor)
                }
                else -> {
                    p.color = Paleta.tom(it.cor, 0.62f)
                    c.drawRect(r, p)
                    val ic = it.icone
                    if (ic != null) {
                        val l = r.width() * 0.5f
                        c.drawBitmap(ic, null, RectF(r.centerX() - l / 2, r.centerY() - l / 2, r.centerX() + l / 2, r.centerY() + l / 2), p)
                    } else {
                        // Sem capa nem ícone (ainda): o nome no meio do quadrado.
                        t.typeface = Ps4Desenho.normal
                        t.textSize = 20 * u
                        t.color = Color.WHITE
                        t.textAlign = Paint.Align.CENTER
                        c.drawText(TextUtils.ellipsize(it.nome, t, r.width() - 16 * u, TextUtils.TruncateAt.END).toString(), r.centerX(), r.centerY() + 7 * u, t)
                        t.textAlign = Paint.Align.LEFT
                    }
                }
            }
            c.restore()
            if (focado) foco(c, r, rr)
            // Nome embaixo (no foco, mais forte; no Switch, na cor de destaque).
            t.typeface = Ps4Desenho.normal
            t.textSize = 16 * u
            t.color = when {
                focado && e == 3 -> InicioSwitch.CIANO
                focado -> if (vis.corTexto == Color.WHITE) Color.WHITE else vis.corTexto
                else -> vis.corSub
            }
            c.drawText(TextUtils.ellipsize(it.nome, t, r.width(), TextUtils.TruncateAt.END).toString(), r.left, r.bottom + 24 * u, t)
        }

        /** A seleção de cada console em volta de [r]. */
        private fun foco(c: Canvas, r: RectF, rr: Float) {
            when (e) {
                1 -> Ps4Desenho.brilho(c, r.left, r.top, r.right, r.bottom, u)
                2 -> {
                    p.color = InicioXbox.VERDE_CLARO
                    c.drawRect(r.left, r.bottom - 6 * u, r.right, r.bottom, p)
                }
                3 -> contorno(c, r, 0f, 5f, 4f, InicioSwitch.CIANO)
                4 -> contorno(c, r, rr, 3f, 4.5f, InicioWii.AZUL)
                else -> contorno(c, r, rr, 3f, 3.5f, Color.WHITE)
            }
        }

        /** Borda de cor [cor] a [vao] do bloco (o vão fica na cor do fundo). */
        private fun contorno(c: Canvas, r: RectF, rr: Float, vao: Float, larg: Float, cor: Int) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = larg * u
            p.color = cor
            val d = (vao + larg / 2) * u
            c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, rr + d, rr + d, p)
            p.style = Paint.Style.FILL
        }

        /** [largo]: banner de app, carregado inteiro (16:9) para o logo não ser cortado. */
        private fun imagem(url: String, largo: Boolean = false): Bitmap? {
            imagens[url]?.let { return it }
            if (pedidas.add(url)) {
                val l = (LADO * u * 1.3f).toInt().coerceAtLeast(64)
                ouvinte.carregar(url, l, if (largo) l * 9 / 16 else l) { b -> imagens[url] = b; invalidate() }
            }
            return null
        }

        private fun ir(novo: Int) {
            if (novo == sel) return
            sel = novo
            val lin = if (sel < 0) 0 else sel / COLS
            // Mantém a linha em foco à vista (a primeira linha fica no topo).
            val topo = lin * PASSO_Y
            val vis = 632f - Y0 - LADO - 30f
            if (topo - rolagemAlvo > vis) rolagemAlvo = topo - vis
            if (topo < rolagemAlvo) rolagemAlvo = topo
            Sons.tocar(Sons.Evento.MOVER)
            invalidate()
        }

        override fun onKeyDown(keyCode: Int, ev: KeyEvent): Boolean {
            val n = itens.size
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (sel > 0 && sel % COLS != 0) ir(sel - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (sel >= 0 && sel + 1 < n && (sel + 1) % COLS != 0) ir(sel + 1)
                KeyEvent.KEYCODE_DPAD_UP -> when {
                    sel >= COLS -> ir(sel - COLS)
                    sel >= 0 && busca != null -> ir(-1)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    sel == -1 && n > 0 -> ir(0)
                    sel >= 0 && sel + COLS < n -> ir(sel + COLS)
                    sel >= 0 && sel / COLS < (n - 1) / COLS -> ir(n - 1)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (ev.repeatCount == 0) ativar()
                else -> return super.onKeyDown(keyCode, ev)
            }
            return true
        }

        private fun ativar() {
            if (sel == -1) ouvinte.buscar(busca.orEmpty()) else itens.getOrNull(sel)?.abrir?.invoke()
        }

        override fun performClick(): Boolean {
            super.performClick()
            ativar()
            return true
        }

        override fun performLongClick(): Boolean {
            itens.getOrNull(sel)?.opcoes?.invoke()
            return true
        }

        override fun informacoes() { itens.getOrNull(sel)?.info?.invoke() }

        override fun voltar(): Boolean {
            ouvinte.fechar()
            return true
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action != MotionEvent.ACTION_UP) return true
            val x = ev.x / u
            val y = ev.y / u
            if (busca != null && y in 112f..164f) { sel = -1; ativar(); return true }
            for (i in itens.indices) {
                posicao(i, r)
                if (x * u in r.left..r.right && (y + rolagem) * u in r.top..r.bottom) {
                    if (sel == i) ativar() else ir(i)
                    return true
                }
            }
            return true
        }

        init {
            // Rolagem com o dedo: arrastar sobe e desce a grade.
            setOnGenericMotionListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_SCROLL) {
                    val linhas = (itens.size + COLS - 1) / COLS
                    rolagemAlvo = (rolagemAlvo - ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f).coerceIn(0f, max(0f, linhas * PASSO_Y - 400f))
                    invalidate()
                    true
                } else false
            }
        }
    }
}
