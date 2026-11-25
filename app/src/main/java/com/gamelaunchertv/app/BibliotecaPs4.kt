package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Biblioteca no estilo do PS4, desenhada inteira aqui: título "Biblioteca", menu à esquerda (Buscar, categorias
 * com a contagem), seletor de ordem em cima da grade, grade de blocos (imagem + nome) separada em seções e a
 * barra de baixo com as dicas dos botões e o perfil. Medidas pensadas numa tela de 1280 × 720.
 */
class BibliotecaPs4(ctx: Context, private val categorias: List<Categoria>, private val ouvinte: Ouvinte, inicial: String? = null) : View(ctx), TelaPs4 {

    /** Uma entrada do menu da esquerda; [secoes]: título → itens (título vazio = sem cabeçalho). [grupo]: cabeçalho acima dela. */
    class Categoria(val nome: String, val icone: Ps4Desenho.Icone, val secoes: List<Pair<String, List<InicioPs4.Item>>>, val grupo: String? = null) {
        val total get() = secoes.sumOf { it.second.size }
    }

    interface Ouvinte {
        fun buscar()
        fun fechar()
        fun perfil(): String
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    private enum class Zona { MENU, ORDEM, GRADE }

    private val ORDENS = listOf("Nome: A-Z", "Nome: Z-A", "Jogado recentemente")
    private var ordem = 0
    private var zona = Zona.MENU
    /** 0 = Buscar; 1.. = categorias. */
    private var selMenu = (categorias.indexOfFirst { it.nome == inicial } + 1).coerceAtLeast(1)
    private var selItem = 0
    private var rolagem = 0f
    private var rolagemAlvo = 0f

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var animarFundo = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Biblioteca"
    }

    // ---------- Geometria ----------

    private val GX = 394f        // grade: esquerda
    private val GW = 792f        // grade: largura
    private val GTOPO = 158f     // grade: começo da área visível
    private val GBASE = 622f     // grade: fim da área visível
    private val BL = 260f        // bloco: largura
    private val BA = 128f        // bloco: altura
    private val BV = 6f          // vão entre blocos
    private val CAB = 34f        // altura do cabeçalho de seção

    private fun categoria() = categorias.getOrNull(selMenu - 1) ?: categorias.first()

    /** Itens da categoria atual em ordem, por seção. */
    private fun secoes(): List<Pair<String, List<InicioPs4.Item>>> = categoria().secoes.map { (titulo, l) ->
        titulo to when (ordem) {
            1 -> l.sortedByDescending { it.nome.lowercase() }
            2 -> l.sortedByDescending { it.quando }
            else -> l.sortedBy { it.nome.lowercase() }
        }
    }

    /** Posição (x, y relativos ao topo da grade, sem rolagem) de cada item, na ordem de [secoes]. */
    private fun posicoes(): List<Triple<InicioPs4.Item, Float, Float>> {
        val lista = ArrayList<Triple<InicioPs4.Item, Float, Float>>()
        var y = 0f
        for ((titulo, itens) in secoes()) {
            if (itens.isEmpty()) continue
            if (titulo.isNotEmpty()) y += CAB
            itens.forEachIndexed { i, it ->
                lista += Triple(it, GX + (i % 3) * (BL + BV), y + (i / 3) * (BA + BV))
            }
            y += ((itens.size + 2) / 3) * (BA + BV) + 8f
        }
        return lista
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        val fase = if (animarFundo) (agora % 60_000L) / 60_000f * (2 * Math.PI).toFloat() else 0f
        Ps4Desenho.fundo(c, width.toFloat(), height.toFloat(), fase)
        rolagem += (rolagemAlvo - rolagem) * 0.35f
        if (kotlin.math.abs(rolagemAlvo - rolagem) < 0.5f) rolagem = rolagemAlvo

        // Título e linha de cima.
        t.typeface = Ps4Desenho.leve
        t.textSize = 42 * u
        t.color = Color.WHITE
        c.drawText("Biblioteca", 72 * u, 76 * u, t)
        p.color = Color.argb(70, 255, 255, 255)
        c.drawRect(0f, 90 * u, width.toFloat(), 91.5f * u, p)

        menu(c)
        grade(c)
        seletorOrdem(c)
        rodape(c)
        if (rolagem != rolagemAlvo) postInvalidateOnAnimation() else if (animarFundo) postInvalidateDelayed(40)
    }

    /** Topo (em unidades) de cada entrada do menu: 0 = Buscar, depois as categorias. */
    private fun ysMenu(): List<Float> {
        val ys = ArrayList<Float>()
        var y = 116f
        ys += y
        y += 56f
        for (cat in categorias) {
            if (cat.grupo != null) y += 44f
            ys += y
            y += 56f
        }
        return ys
    }

    private fun menu(c: Canvas) {
        // Com muitas plataformas o menu passa do fim da tela: desliza para a entrada escolhida aparecer.
        val ySel = ysMenu().getOrElse(selMenu) { 116f }
        val desloc = max(0f, ySel + 60f - 620f)
        c.save()
        c.clipRect(0f, 100f * u, 340f * u, 626f * u)
        c.translate(0f, -desloc * u)
        menuDesenho(c)
        c.restore()
    }

    private fun menuDesenho(c: Canvas) {
        var y = 116f
        // Buscar.
        itemMenu(c, 0, Ps4Desenho.Icone.BUSCAR, "Buscar", null, y)
        y += 56f
        for ((i, cat) in categorias.withIndex()) {
            cat.grupo?.let { g ->
                t.typeface = Ps4Desenho.normal
                t.textSize = 16 * u
                t.color = Color.argb(170, 255, 255, 255)
                c.drawText(g, 105 * u, (y + 22f) * u, t)
                p.color = Color.argb(60, 255, 255, 255)
                c.drawRect(100 * u, (y + 32f) * u, 308 * u, (y + 33.5f) * u, p)
                y += 44f
            }
            itemMenu(c, i + 1, cat.icone, cat.nome, cat.total.toString(), y)
            y += 56f
        }
    }

    private fun itemMenu(c: Canvas, indice: Int, ic: Ps4Desenho.Icone, nome: String, numero: String?, y: Float) {
        val focado = indice == selMenu && zona == Zona.MENU
        val escolhido = indice == selMenu
        if (focado) {
            p.color = Color.argb(40, 255, 255, 255)
            c.drawRect(90 * u, y * u, 322 * u, (y + 52f) * u, p)
            Ps4Desenho.brilho(c, 90 * u, y * u, 322 * u, (y + 52f) * u, u)
        }
        Ps4Desenho.icone(c, ic, 104 * u, (y + 13f) * u, 26 * u)
        t.typeface = Ps4Desenho.normal
        t.textSize = (if (focado) 21 else 18) * u
        t.color = if (escolhido) Color.WHITE else Color.argb(205, 255, 255, 255)
        c.drawText(TextUtils.ellipsize(nome, t, 150 * u, TextUtils.TruncateAt.END).toString(), 146 * u, (y + 33f) * u, t)
        numero?.let {
            t.textSize = 15 * u
            t.textAlign = Paint.Align.RIGHT
            c.drawText(it, 312 * u, (y + 32f) * u, t)
            t.textAlign = Paint.Align.LEFT
        }
    }

    private fun seletorOrdem(c: Canvas) {
        // Fundo atrás do seletor (a grade rola por baixo dele).
        val l = 694f * u
        val tp = 99f * u
        val rt = 1186f * u
        val b = 139f * u
        p.color = 0x55102A6B
        c.drawRect(l, tp, rt, b, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * u
        p.color = Color.argb(210, 255, 255, 255)
        c.drawRect(l, tp, rt, b, p)
        p.style = Paint.Style.FILL
        t.typeface = Ps4Desenho.normal
        t.textSize = 20 * u
        t.color = Color.WHITE
        c.drawText(ORDENS[ordem], l + 12 * u, tp + 28 * u, t)
        Ps4Desenho.chevron(c, rt - 18 * u, (tp + b) / 2, 7 * u)
        if (zona == Zona.ORDEM) Ps4Desenho.brilho(c, l, tp, rt, b, u)
    }

    private fun grade(c: Canvas) {
        c.save()
        c.clipRect(0f, (GTOPO - 6f) * u, width.toFloat(), GBASE * u)
        var y = 0f
        var indice = 0
        val lista = secoes()
        val vazio = lista.all { it.second.isEmpty() }
        if (vazio) {
            t.typeface = Ps4Desenho.leve
            t.textSize = 24 * u
            t.color = Color.argb(200, 255, 255, 255)
            c.drawText("Nada aqui ainda.", GX * u, (GTOPO + 60f) * u, t)
        }
        for ((titulo, itens) in lista) {
            if (itens.isEmpty()) continue
            val base = GTOPO - rolagem
            if (titulo.isNotEmpty()) {
                t.typeface = Ps4Desenho.normal
                t.textSize = 16 * u
                t.color = Color.argb(225, 255, 255, 255)
                c.drawText(titulo, GX * u, (base + y + 20f) * u, t)
                p.color = Color.argb(70, 255, 255, 255)
                c.drawRect(GX * u, (base + y + 28f) * u, (GX + GW) * u, (base + y + 29.5f) * u, p)
                y += CAB
            }
            for ((i, it) in itens.withIndex()) {
                val x = GX + (i % 3) * (BL + BV)
                val by = base + y + (i / 3) * (BA + BV)
                if (by + BA >= GTOPO - 10f && by <= GBASE) bloco(c, it, x, by, indice == selItem && zona == Zona.GRADE)
                indice++
            }
            y += ((itens.size + 2) / 3) * (BA + BV) + 8f
        }
        c.restore()
    }

    private fun bloco(c: Canvas, it: InicioPs4.Item, x: Float, y: Float, focado: Boolean) {
        val l = x * u
        val tp = y * u
        val rt = (x + BL) * u
        val b = (y + BA) * u
        p.color = if (focado) 0xFF3A7BE0.toInt() else 0xFF2A63C8.toInt()
        c.drawRect(l, tp, rt, b, p)
        // Imagem quadrada à esquerda.
        r.set(l, tp, l + BA * u, b)
        val img = it.imagem?.let { url -> imagem(url) }
        when {
            img != null -> Ps4Desenho.cortado(c, img, r)
            it.icone != null -> {
                p.color = Paleta.tom(it.cor, 0.55f)
                c.drawRect(r, p)
                val q = BA * 0.5f * u
                Ps4Desenho.bitmap(c, it.icone, RectF(r.centerX() - q / 2, r.centerY() - q / 2, r.centerX() + q / 2, r.centerY() + q / 2))
            }
            else -> {
                p.color = 0xFF1A4CA8.toInt()
                c.drawRect(r, p)
                Ps4Desenho.icone(c, Ps4Desenho.Icone.JOGOS, r.centerX() - 26 * u, r.centerY() - 26 * u, 52 * u, 0xFF1A4CA8.toInt())
            }
        }
        // Nome (até 3 linhas) e plataforma embaixo.
        t.typeface = Ps4Desenho.normal
        t.textSize = 15 * u
        t.color = Color.WHITE
        val largTexto = ((BL - BA) * u - 18 * u).toInt().coerceAtLeast(10)
        val layout = StaticLayout.Builder.obtain(it.nome, 0, it.nome.length, t, largTexto)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save()
        c.translate(l + BA * u + 9 * u, tp + 8 * u)
        layout.draw(c)
        c.restore()
        if (it.plataforma.isNotEmpty()) {
            t.textSize = 12 * u
            t.color = Color.argb(190, 255, 255, 255)
            c.drawText(TextUtils.ellipsize(it.plataforma, t, largTexto.toFloat(), TextUtils.TruncateAt.END).toString(), l + BA * u + 9 * u, b - 10 * u, t)
        }
        if (focado) Ps4Desenho.brilho(c, l, tp, rt, b, u)
    }

    private fun rodape(c: Canvas) {
        p.color = Color.argb(70, 255, 255, 255)
        c.drawRect(0f, 631 * u, width.toFloat(), 632.5f * u, p)
        val cy = 650f * u
        Ps4Desenho.botao(c, 'X', 84 * u, cy, 10 * u)
        t.typeface = Ps4Desenho.normal
        t.textSize = 19 * u
        t.color = Color.WHITE
        c.drawText("Entrar", 102 * u, cy + 7 * u, t)
        Ps4Desenho.botao(c, 'O', 194 * u, cy, 10 * u)
        c.drawText("Voltar", 212 * u, cy + 7 * u, t)
        Ps4Desenho.botao(c, 'T', 312 * u, cy, 10 * u)
        c.drawText("Opções", 330 * u, cy + 7 * u, t)
        val nome = ouvinte.perfil()
        val lx = 1186 * u - t.measureText(nome)
        Ps4Desenho.avatar(c, nome, lx - 34 * u, cy - 13 * u, 26 * u, t)
        t.textSize = 19 * u
        t.color = Color.WHITE
        c.drawText(nome, lx, cy + 7 * u, t)
    }

    private fun imagem(url: String): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) {
            val lado = (BA * u).toInt().coerceAtLeast(48)
            ouvinte.carregar(url, lado, lado) { b -> imagens[url] = b; invalidate() }
        }
        return null
    }

    // ---------- Controle ----------

    private fun totalMenu() = categorias.size + 1

    private fun itemAtual(): InicioPs4.Item? = posicoes().getOrNull(selItem)?.first

    /** Rola a grade para o item selecionado ficar visível. */
    private fun mostrarSelecionado() {
        val pos = posicoes().getOrNull(selItem) ?: return
        val y = pos.third
        val visivel = GBASE - GTOPO
        if (y - rolagemAlvo < 0f) rolagemAlvo = max(0f, y - CAB)
        if (y + BA - rolagemAlvo > visivel) rolagemAlvo = y + BA - visivel + 8f
    }

    private fun moverNaGrade(dx: Int, dy: Int): Boolean {
        val lista = posicoes()
        if (lista.isEmpty()) return false
        val atual = lista.getOrNull(selItem) ?: return false
        val alvo: Int? = if (dy == 0) {
            val i = selItem + dx
            // Esquerda na primeira coluna volta ao menu.
            if (dx < 0 && atual.second == GX) null else i.takeIf { it in lista.indices && lista[it].third == atual.third }
        } else {
            // O bloco da linha de cima/baixo mais perto na mesma coluna.
            val linhas = lista.map { it.third }.distinct().sorted()
            val li = linhas.indexOf(atual.third) + dy
            if (li !in linhas.indices) return dy < 0 && run { irPara(Zona.ORDEM); true }
            val linha = lista.withIndex().filter { it.value.third == linhas[li] }
            linha.minByOrNull { kotlin.math.abs(it.value.second - atual.second) }?.index
        }
        if (alvo == null) {
            if (dx < 0) irPara(Zona.MENU)
            return true
        }
        selItem = alvo
        mostrarSelecionado()
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
        return true
    }

    private fun irPara(z: Zona) {
        zona = z
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when (zona) {
                Zona.MENU -> if (selMenu > 0) trocarMenu(selMenu - 1)
                Zona.GRADE -> moverNaGrade(0, -1)
                Zona.ORDEM -> {}
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zona) {
                Zona.MENU -> if (selMenu < totalMenu() - 1) trocarMenu(selMenu + 1)
                Zona.GRADE -> moverNaGrade(0, 1)
                Zona.ORDEM -> if (posicoes().isNotEmpty()) { selItem = 0; rolagemAlvo = 0f; irPara(Zona.GRADE) }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zona) {
                Zona.GRADE -> moverNaGrade(-1, 0)
                Zona.ORDEM -> irPara(Zona.MENU)
                Zona.MENU -> {}
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zona) {
                Zona.MENU -> if (posicoes().isNotEmpty()) { selItem = min(selItem, posicoes().lastIndex); mostrarSelecionado(); irPara(Zona.GRADE) } else irPara(Zona.ORDEM)
                Zona.GRADE -> moverNaGrade(1, 0)
                Zona.ORDEM -> {}
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun trocarMenu(novo: Int) {
        selMenu = novo
        // Como no console: a grade muda junto com a categoria em foco.
        if (novo > 0) { selItem = 0; rolagem = 0f; rolagemAlvo = 0f }
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    private fun ativar() {
        when (zona) {
            Zona.MENU -> if (selMenu == 0) ouvinte.buscar() else if (posicoes().isNotEmpty()) { selItem = 0; irPara(Zona.GRADE) }
            Zona.ORDEM -> { ordem = (ordem + 1) % ORDENS.size; selItem = 0; rolagemAlvo = 0f; Sons.tocar(Sons.Evento.ABRIR); invalidate() }
            Zona.GRADE -> itemAtual()?.abrir?.invoke()
        }
    }

    override fun informacoes() { if (zona == Zona.GRADE) itemAtual()?.info?.invoke() }

    override fun voltar(): Boolean {
        if (zona == Zona.GRADE || zona == Zona.ORDEM) { irPara(Zona.MENU); return true }
        ouvinte.fechar()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        if (zona == Zona.GRADE) itemAtual()?.opcoes?.invoke()
        return true
    }

    // ---------- Toque ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u
        if (x < 330f) {
            var my = 116f
            if (y in my..(my + 52f)) { selMenu = 0; zona = Zona.MENU; ativar(); return true }
            my += 56f
            for (i in categorias.indices) {
                if (categorias[i].grupo != null) my += 44f
                if (y in my..(my + 52f)) { zona = Zona.MENU; trocarMenu(i + 1); return true }
                my += 56f
            }
            return true
        }
        if (y in 99f..139f && x in 694f..1186f) { zona = Zona.ORDEM; ativar(); return true }
        if (y > 640f && x < 280f) { ouvinte.fechar(); return true }
        if (y in GTOPO..GBASE) {
            for ((i, pos) in posicoes().withIndex()) {
                val by = GTOPO + pos.third - rolagem
                if (x in pos.second..(pos.second + BL) && y in by..(by + BA)) {
                    if (zona == Zona.GRADE && selItem == i) ativar() else { zona = Zona.GRADE; selItem = i; invalidate() }
                    return true
                }
            }
        }
        return true
    }

    override fun onVisibilityAggregated(visivel: Boolean) {
        super.onVisibilityAggregated(visivel)
        if (visivel) invalidate()
    }
}
