package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Telas desenhadas no estilo PS4 (início e biblioteca): o launcher fala com elas pelo controle. */
interface TelaPs4 {
    /** Círculo/Voltar; true = a tela tratou (fechou um menu, voltou à fileira…). */
    fun voltar(): Boolean
    /** Quadrado: informações do item selecionado. */
    fun informacoes()
}

/**
 * Tela inicial no estilo do PS4, desenhada inteira aqui (ver [Ps4Desenho]):
 * - barra de cima (atividade, aviso, amigos, perfil, troféus, relógio);
 * - fileira de quadrados com o selecionado grande (borda brilhante e a faixa "Iniciar") e o nome ao lado;
 * - três painéis embaixo (abrir, informações, opções);
 * - ▲ abre o menu de funções: a fileira desce e aparece a linha de ícones grandes, com o nome embaixo do
 *   selecionado (como a tela de funções do console).
 * Medidas pensadas numa tela de 1280 × 720.
 */
class InicioPs4(
    ctx: Context, private val itens: List<Item>, private val topo: List<Topo>, private val funcoes: List<Funcao>,
    private val ouvinte: Ouvinte,
) : View(ctx), TelaPs4 {

    /** Um quadrado da fileira. [simbolo] != null = item do sistema (azul com o ícone). */
    class Item(
        val nome: String, val imagem: String?, val icone: Bitmap?, val cor: Int, val simbolo: Ps4Desenho.Icone?,
        val detalhe: String, val ehJogo: Boolean, val abrir: () -> Unit, val opcoes: (() -> Unit)?, val info: (() -> Unit)?,
        val plataforma: String = "", val quando: Long = 0L,
        /** Id do card (jogo); "" nos itens do sistema. Usado para achar a arte de fundo. */
        val id: String = "",
    )

    /** Ícone da barra de cima; [icone] null = o perfil (quadrado com a inicial e o nome). */
    class Topo(val icone: Ps4Desenho.Icone?, val texto: String, val numero: String?)

    /** Item do menu de funções; [icone] null = o perfil. */
    class Funcao(val icone: Ps4Desenho.Icone?, val rotulo: String, val numero: String?, val acao: () -> Unit)

    interface Ouvinte {
        fun selecionou(item: Item)
        fun relogio(): String
        fun aviso(): String
        fun perfil(): String
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    private enum class Zona { FILEIRA, PAINEIS, FUNCOES }

    private var zona = Zona.FILEIRA
    private var sel = if (itens.size > 1) 1 else 0
    private var selPainel = 0
    private var selFuncao = funcoes.indexOfFirst { it.icone == Ps4Desenho.Icone.CONFIGURACOES }.coerceAtLeast(0)

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()

    // Animação da fileira (posições antigas → novas) e da troca para o menu de funções (k de 0 a 1).
    private val atual = Array(itens.size) { FloatArray(3) }
    private val de = Array(itens.size) { FloatArray(3) }
    private var animIni = 0L
    private val ANIM = 170L
    private var funcK = 0f
    private var funcDe = 0f
    private var funcAlvo = 0f
    private var funcIni = 0L
    private val ANIM_FUNC = 260L

    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var animarFundo = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    // ---------- Geometria (unidades de uma tela de 1280 × 720) ----------

    private val PEQ = 146f
    private val PASSO = 149f
    private val GRANDE = 238f
    private val FAIXA = 47f
    private val Y_PEQ = 151f
    private val Y_GRANDE = 123f
    // Menu de funções: fileira embaixo, quadrados iguais.
    private val F_LADO = 121f
    private val F_PASSO = 127.5f
    private val F_Y = 606f

    private fun xSel() = if (sel == 0) 64f else 222f

    private fun destino(i: Int, out: FloatArray) {
        // Normal.
        val xs = xSel()
        val nx: Float
        val ny: Float
        val nl: Float
        when {
            i == sel -> { nx = xs; ny = Y_GRANDE; nl = GRANDE }
            i < sel -> { nx = xs - 3f - PEQ - (sel - 1 - i) * PASSO; ny = Y_PEQ; nl = PEQ }
            else -> { nx = xs + GRANDE + 10f + (i - sel - 1) * PASSO; ny = Y_PEQ; nl = PEQ }
        }
        // Menu de funções.
        val fx = 145f + (i - sel + (if (sel == 0) 0 else 1)) * F_PASSO
        val k = funcK
        out[0] = nx + (fx - nx) * k
        out[1] = ny + (F_Y - ny) * k
        out[2] = nl + (F_LADO - nl) * k
    }

    private fun mudarSelecao(nova: Int) {
        if (nova == sel || nova !in itens.indices) return
        for (i in itens.indices) System.arraycopy(atual[i], 0, de[i], 0, 3)
        sel = nova
        animIni = SystemClock.uptimeMillis()
        Sons.tocar(Sons.Evento.MOVER)
        ouvinte.selecionou(itens[sel])
        invalidate()
    }

    /** Onde o foco está (para a tela remontada continuar no mesmo lugar). */
    class Estado(val zona: Int, val nome: String?, val selFuncao: Int, val selPainel: Int)

    /** Só depois de a pessoa mexer: a tela montada antes de os jogos carregarem não vale como posição. */
    private var mexeu = false

    fun estado(): Estado? = if (!mexeu) null else Estado(zona.ordinal, itens.getOrNull(sel)?.nome, selFuncao, selPainel)

    /** Volta ao [e] sem animação (chamar antes de a tela aparecer). */
    fun restaurar(e: Estado) {
        mexeu = true
        itens.indexOfFirst { it.nome == e.nome }.takeIf { it >= 0 }?.let { sel = it }
        zona = Zona.values()[e.zona]
        if (zona == Zona.PAINEIS && itens.getOrNull(sel)?.let { paineisDe(it).isEmpty() } != false) zona = Zona.FILEIRA
        selFuncao = e.selFuncao.coerceIn(0, maxOf(0, funcoes.lastIndex))
        selPainel = e.selPainel
        funcK = if (zona == Zona.FUNCOES) 1f else 0f
        funcDe = funcK
        funcAlvo = funcK
        if (width > 0) for (i in itens.indices) { destino(i, atual[i]); System.arraycopy(atual[i], 0, de[i], 0, 3) }
        invalidate()
    }

    private fun irPara(z: Zona) {
        if (z == zona) return
        val ia = z == Zona.FUNCOES || zona == Zona.FUNCOES
        zona = z
        if (ia) {
            funcDe = funcK
            funcAlvo = if (z == Zona.FUNCOES) 1f else 0f
            funcIni = SystemClock.uptimeMillis()
        }
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = w / 1280f
        for (i in itens.indices) { destino(i, atual[i]); System.arraycopy(atual[i], 0, de[i], 0, 3) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        itens.getOrNull(sel)?.let { ouvinte.selecionou(it) }
    }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        // Troca normal ↔ funções.
        val pf = ((agora - funcIni).toFloat() / ANIM_FUNC).coerceIn(0f, 1f)
        val ef = 1f - (1f - pf) * (1f - pf)
        funcK = funcDe + (funcAlvo - funcDe) * ef
        val fase = if (animarFundo) (agora % 60_000L) / 60_000f * (2 * Math.PI).toFloat() else 0f
        Ps4Desenho.fundo(c, width.toFloat(), height.toFloat(), fase)

        val normal = 1f - funcK
        if (normal > 0.01f) barraTopo(c, normal)
        relogio(c)

        val prog = ((agora - animIni).toFloat() / ANIM).coerceIn(0f, 1f)
        val k = 1f - (1f - prog) * (1f - prog)
        val alvo = FloatArray(3)
        for (i in itens.indices) {
            destino(i, alvo)
            for (j in 0..2) atual[i][j] = de[i][j] + (alvo[j] - de[i][j]) * k
            // Durante a troca para o menu de funções a posição segue direto o destino (já animado por funcK).
            if (pf < 1f) System.arraycopy(alvo, 0, atual[i], 0, 3)
        }
        for (i in itens.indices) if (i != sel) quadrado(c, i, false)
        itens.getOrNull(sel)?.let { quadrado(c, sel, true) }
        if (normal > 0.01f) {
            rotuloSelecionado(c, k * normal)
            paineis(c, normal)
        }
        if (funcK > 0.01f) menuFuncoes(c, funcK)
        if (prog < 1f || pf < 1f) postInvalidateOnAnimation() else if (animarFundo) postInvalidateDelayed(40)
    }

    private fun relogio(c: Canvas) {
        t.typeface = Ps4Desenho.leve
        t.textSize = 25 * u
        t.color = Color.WHITE
        t.textAlign = Paint.Align.RIGHT
        c.drawText(ouvinte.relogio(), 1212 * u, 76 * u, t)
        t.textAlign = Paint.Align.LEFT
    }

    private fun barraTopo(c: Canvas, alfa: Float) {
        val cy = 67f * u
        var x = 70f * u
        val a = (255 * alfa).toInt()
        for (item in topo) {
            val ic = item.icone
            if (ic == null) {
                Ps4Desenho.avatar(c, ouvinte.perfil(), x, cy - 21 * u, 42 * u, t)
                t.typeface = Ps4Desenho.normal
                t.textSize = 19 * u
                t.color = Color.argb((235 * alfa).toInt(), 255, 255, 255)
                c.drawText(ouvinte.perfil(), x + 52 * u, cy + 7 * u, t)
                x += 52 * u + t.measureText(ouvinte.perfil()) + 46 * u
                continue
            }
            Ps4Desenho.icone(c, ic, x, cy - 18 * u, 36 * u)
            item.numero?.let { n ->
                t.typeface = Ps4Desenho.normal
                t.textSize = 13 * u
                t.color = Color.argb(a, 255, 255, 255)
                c.drawText(n, x + (if (ic == Ps4Desenho.Icone.TROFEUS) 44 else 30) * u, cy + (if (ic == Ps4Desenho.Icone.TROFEUS) 7 else -20) * u, t)
            }
            x += 36 * u + (if (item.numero != null) 26 * u else 0f) + 46 * u
            if (ic == Ps4Desenho.Icone.ATIVIDADE) {
                val aviso = ouvinte.aviso()
                if (aviso.isNotEmpty()) {
                    t.typeface = Ps4Desenho.normal
                    t.textSize = 17 * u
                    t.color = Color.argb((215 * alfa).toInt(), 255, 255, 255)
                    val txt = TextUtils.ellipsize(aviso, t, 200 * u, TextUtils.TruncateAt.END).toString()
                    c.drawText(txt, x + 140 * u, cy + 6 * u, t)
                }
                x += 360 * u
            }
        }
    }

    private fun quadrado(c: Canvas, i: Int, selecionado: Boolean) {
        val it = itens[i]
        val x = atual[i][0] * u
        val y = atual[i][1] * u
        val lado = atual[i][2] * u
        if (x > width || x + lado < 0) return
        val grande = selecionado && funcK < 0.5f
        r.set(x, y, x + lado, y + lado)
        val img = it.imagem?.let { url -> imagem(url) }
        when {
            img != null -> Ps4Desenho.cortado(c, img, r)
            it.simbolo != null -> {
                p.shader = LinearGradient(0f, y, 0f, y + lado, 0xFF2B6BD3.toInt(), 0xFF194FAF.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(r, p)
                p.shader = null
                p.color = Color.argb(70, 255, 255, 255)
                c.drawRect(x, y, x + lado, y + 1.5f * u, p)
                Ps4Desenho.icone(c, it.simbolo, x + lado * 0.27f, y + lado * 0.2f, lado * 0.46f, 0xFF2463C9.toInt())
                if (!grande) {
                    t.typeface = Ps4Desenho.normal
                    t.textSize = 11 * u
                    t.color = Color.WHITE
                    t.textAlign = Paint.Align.CENTER
                    c.drawText(it.nome, x + lado / 2, y + lado - 14 * u * lado / (PEQ * u), t)
                    t.textAlign = Paint.Align.LEFT
                }
            }
            else -> {
                p.color = Paleta.tom(it.cor, 0.55f)
                c.drawRect(r, p)
                it.icone?.let { ic ->
                    val l = lado * 0.42f
                    val rr = RectF(x + (lado - l) / 2, y + (lado - l) / 2 - lado * 0.05f, x + (lado + l) / 2, y + (lado + l) / 2 - lado * 0.05f)
                    Ps4Desenho.bitmap(c, ic, rr)
                }
                if (!grande) {
                    t.typeface = Ps4Desenho.normal
                    t.textSize = 11 * u
                    t.color = Color.WHITE
                    t.textAlign = Paint.Align.CENTER
                    c.drawText(TextUtils.ellipsize(it.nome, t, lado - 12 * u, TextUtils.TruncateAt.END).toString(), x + lado / 2, y + lado - 12 * u, t)
                    t.textAlign = Paint.Align.LEFT
                }
            }
        }
        if (!selecionado) return
        if (!grande) {
            // No menu de funções o quadrado selecionado só fica um pouco mais claro.
            p.color = Color.argb(60, 255, 255, 255)
            c.drawRect(r, p)
            return
        }
        val fy = y + lado
        val fh = FAIXA * u * (lado / (GRANDE * u))
        p.color = 0xF0101C38.toInt()
        c.drawRect(x, fy, x + lado, fy + fh, p)
        t.typeface = Ps4Desenho.normal
        t.color = Color.WHITE
        t.textAlign = Paint.Align.CENTER
        if (it.simbolo == null) {
            t.textSize = 17 * u
            c.drawText(if (it.ehJogo) "Iniciar" else "Abrir", x + lado / 2, fy + fh / 2 + 6 * u, t)
        } else {
            Ps4Desenho.seta(c, x + lado / 2, fy + fh / 2, 9 * u)
        }
        t.textAlign = Paint.Align.LEFT
        if (zona == Zona.FILEIRA) Ps4Desenho.brilho(c, x, y, x + lado, fy + fh, u)
    }

    private fun rotuloSelecionado(c: Canvas, alfa: Float) {
        val it = itens.getOrNull(sel) ?: return
        val x = (xSel() + GRANDE + 20f) * u
        t.typeface = Ps4Desenho.leve
        t.textSize = 37 * u
        t.color = Color.argb((255 * alfa).toInt(), 255, 255, 255)
        c.drawText(TextUtils.ellipsize(it.nome, t, width - x - 40 * u, TextUtils.TruncateAt.END).toString(), x, 396f * u, t)
    }

    private fun paineisDe(it: Item): List<Triple<String, String, () -> Unit>> = buildList {
        if (it.simbolo != null) return@buildList
        add(Triple(it.nome, it.detalhe, it.abrir))
        it.info?.let { f -> add(Triple("Informações do jogo", it.plataforma.ifEmpty { "Capa, tempo jogado, conquistas" }, f)) }
        it.opcoes?.let { f -> add(Triple("Opções", if (it.ehJogo) "Coleções, capa, nome, esconder" else "Favoritos, capa, nome, esconder", f)) }
    }

    private fun xPaineis(n: Int) = (1280f - (n * 313f + (n - 1) * 20f)) / 2f - 7f

    private fun paineis(c: Canvas, alfa: Float) {
        val it = itens.getOrNull(sel) ?: return
        val lista = paineisDe(it)
        val desce = (1f - alfa) * 200f
        var x = xPaineis(lista.size)
        val y = 487f + desce
        c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * alfa).toInt())
        for ((i, pn) in lista.withIndex()) {
            val l = x * u
            val tp = y * u
            val rt = (x + 313f) * u
            val bt = (y + 228f) * u
            p.color = 0xD0102452.toInt()
            c.drawRect(l, tp, rt, bt, p)
            val faixa = 58f * u
            r.set(l, tp, rt, bt - faixa)
            val img = it.imagem?.let { url -> imagem(url) }
            when {
                i == 0 && img != null -> Ps4Desenho.cortado(c, img, r)
                i == 1 && img != null -> {
                    p.color = 0xFF0C1B3D.toInt()
                    c.drawRect(r, p)
                    val lado = r.height() * 0.8f
                    val rr = RectF(r.left + 16 * u, r.top + (r.height() - lado) / 2, r.left + 16 * u + lado, r.top + (r.height() + lado) / 2)
                    Ps4Desenho.cortado(c, img, rr)
                    t.typeface = Ps4Desenho.normal
                    t.textSize = 15 * u
                    t.color = Color.argb(220, 255, 255, 255)
                    c.drawText(TextUtils.ellipsize(it.plataforma, t, r.right - rr.right - 30 * u, TextUtils.TruncateAt.END).toString(), rr.right + 14 * u, rr.top + 22 * u, t)
                }
                else -> {
                    p.color = 0xFF14306A.toInt()
                    c.drawRect(r, p)
                    p.color = Color.WHITE
                    for (d in -1..1) c.drawCircle(r.centerX() + d * 22 * u, r.centerY(), 6 * u, p)
                }
            }
            p.color = Ps4Desenho.AZUL_ESCURO
            c.drawRect(l, bt - faixa, rt, bt, p)
            t.typeface = Ps4Desenho.normal
            t.textSize = 19 * u
            t.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(pn.first, t, rt - l - 28 * u, TextUtils.TruncateAt.END).toString(), l + 14 * u, bt - faixa + 25 * u, t)
            t.textSize = 14 * u
            t.color = Color.argb(185, 255, 255, 255)
            c.drawText(TextUtils.ellipsize(pn.second, t, rt - l - 28 * u, TextUtils.TruncateAt.END).toString(), l + 14 * u, bt - faixa + 46 * u, t)
            if (zona == Zona.PAINEIS && i == selPainel) Ps4Desenho.brilho(c, l, tp, rt, bt, u)
            x += 333f
        }
        c.restore()
    }

    /** A linha de ícones do menu de funções (aparece subindo um pouco, junto com a descida da fileira). */
    private fun menuFuncoes(c: Canvas, k: Float) {
        c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * k).toInt())
        val cy = (485f + (1f - k) * 40f) * u
        for ((i, f) in funcoes.withIndex()) {
            val cx = (135f + i * 118f) * u
            val focado = zona == Zona.FUNCOES && i == selFuncao
            val l = (if (focado) 68f else 42f) * u
            val ic = f.icone
            if (ic == null) Ps4Desenho.avatar(c, ouvinte.perfil(), cx - l / 2, cy - l / 2, l, t)
            else Ps4Desenho.icone(c, ic, cx - l / 2, cy - l / 2, l)
            f.numero?.let { n ->
                t.typeface = Ps4Desenho.normal
                t.textSize = 15 * u
                t.color = Color.argb(225, 255, 255, 255)
                t.textAlign = Paint.Align.CENTER
                c.drawText(n, cx, cy - l / 2 - 14 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
            if (focado) {
                t.typeface = Ps4Desenho.normal
                t.textSize = 19 * u
                t.color = Color.WHITE
                t.textAlign = Paint.Align.CENTER
                c.drawText(f.rotulo, cx, cy + l / 2 + 34 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
        }
        c.restore()
    }

    private fun imagem(url: String): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) {
            val lado = (GRANDE * u).toInt().coerceAtLeast(64)
            ouvinte.carregar(url, (lado * 1.4f).toInt(), lado) { b -> imagens[url] = b; invalidate() }
        }
        return null
    }

    // ---------- Controle ----------

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        mexeu = true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zona) {
                Zona.FILEIRA -> mudarSelecao(sel - 1)
                Zona.PAINEIS -> mover { selPainel = max(0, selPainel - 1) }
                Zona.FUNCOES -> mover { selFuncao = max(0, selFuncao - 1) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zona) {
                Zona.FILEIRA -> mudarSelecao(sel + 1)
                Zona.PAINEIS -> mover { selPainel = min(paineisDe(itens[sel]).lastIndex, selPainel + 1) }
                Zona.FUNCOES -> mover { selFuncao = min(funcoes.lastIndex, selFuncao + 1) }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (zona) {
                Zona.FILEIRA -> if (funcoes.isNotEmpty()) irPara(Zona.FUNCOES)
                Zona.PAINEIS -> irPara(Zona.FILEIRA)
                Zona.FUNCOES -> {}
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zona) {
                Zona.FUNCOES -> irPara(Zona.FILEIRA)
                Zona.FILEIRA -> if (itens.isNotEmpty() && paineisDe(itens[sel]).isNotEmpty()) { selPainel = 0; irPara(Zona.PAINEIS) }
                Zona.PAINEIS -> {}
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun mover(f: () -> Unit) {
        f()
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    fun ativar() {
        when (zona) {
            Zona.FILEIRA -> itens.getOrNull(sel)?.abrir?.invoke()
            Zona.PAINEIS -> itens.getOrNull(sel)?.let { paineisDe(it).getOrNull(selPainel)?.third?.invoke() }
            Zona.FUNCOES -> funcoes.getOrNull(selFuncao)?.acao?.invoke()
        }
    }

    override fun informacoes() { itens.getOrNull(sel)?.info?.invoke() }

    override fun voltar(): Boolean {
        if (zona != Zona.FILEIRA) {
            irPara(Zona.FILEIRA)
            return true
        }
        if (sel <= 1) return false
        mudarSelecao(if (itens.size > 1) 1 else 0)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        if (zona != Zona.FUNCOES) itens.getOrNull(sel)?.opcoes?.invoke()
        return true
    }

    // ---------- Toque ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        mexeu = true
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u
        if (zona == Zona.FUNCOES) {
            for (i in funcoes.indices) {
                val cx = 135f + i * 118f
                if (x in (cx - 50f)..(cx + 50f) && y in 430f..560f) {
                    if (selFuncao == i) ativar() else mover { selFuncao = i }
                    return true
                }
            }
            if (y > 560f) irPara(Zona.FILEIRA)
            return true
        }
        for (i in itens.indices) {
            val q = atual[i]
            val alt = if (i == sel) q[2] + FAIXA else q[2]
            if (x in q[0]..(q[0] + q[2]) && y in q[1]..(q[1] + alt)) {
                zona = Zona.FILEIRA
                if (i == sel) ativar() else mudarSelecao(i)
                return true
            }
        }
        val lista = paineisDe(itens.getOrNull(sel) ?: return true)
        var px = xPaineis(lista.size)
        for (i in lista.indices) {
            if (x in px..(px + 313f) && y in 487f..715f) { zona = Zona.PAINEIS; selPainel = i; ativar(); return true }
            px += 333f
        }
        // Barra de cima: abre o menu de funções.
        if (y < 110f && funcoes.isNotEmpty()) irPara(Zona.FUNCOES)
        return true
    }

    override fun onVisibilityAggregated(visivel: Boolean) {
        super.onVisibilityAggregated(visivel)
        if (visivel) invalidate()
    }

    fun atualizar() = invalidate()
}
