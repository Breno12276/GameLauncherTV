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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Tela inicial no estilo do Xbox 360 de 2011 (a dos blocos), desenhada inteira aqui:
 * - fundo claro em degradê com faixas brancas curvas;
 * - categorias em texto minúsculo em cima ("buscar  início  jogos  apps  configurações"), trocadas com LB/RB
 *   ou pelas setas na linha das categorias; a categoria escolhida fica escura e as outras cinza;
 * - cartão do perfil no canto (quadrado com a inicial, nome e pontos);
 * - "início": um bloco grande com o último jogo, quatro pequenos embaixo (jogados antes), um bloco largo e dois
 *   médios no meio, e os apps favoritos à direita; "jogos" e "apps": grade de duas linhas que corre para o lado;
 * - o bloco selecionado cresce um pouco com a borda branca e a sombra.
 * Nada da Microsoft é usado (só a ideia de organizar). Medidas pensadas numa tela de 1280 × 720.
 */
class InicioXbox(
    ctx: Context, private val hubs: List<Hub>, private val ouvinte: Ouvinte, inicial: Int = 1,
) : View(ctx), TelaPs4 {

    /** Uma categoria. [blocos] com a posição de cada um (em unidades); [acao] != null = abrir direto (ex.: buscar). */
    class Hub(val nome: String, val blocos: List<Bloco>, val acao: (() -> Unit)? = null)

    /** Um bloco: item, retângulo (x, y, largura, altura em unidades) e se o nome aparece embaixo, dentro. */
    class Bloco(val item: InicioPs4.Item, val x: Float, val y: Float, val w: Float, val h: Float, val comNome: Boolean = true)

    interface Ouvinte {
        fun selecionou(item: InicioPs4.Item)
        fun relogio(): String
        fun perfil(): String
        fun pontos(): String?
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
    }

    companion object {
        const val VERDE = 0xFF107C10.toInt()
        const val VERDE_CLARO = 0xFF5DC21E.toInt()
        const val CINZA_TEXTO = 0xFF3A3D40.toInt()
        const val CINZA_SUAVE = 0xFF8C9298.toInt()
        val fina: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val normal: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        /** Fundo claro com faixas brancas curvas (usado também nas configurações no estilo Xbox). */
        fun fundo(c: Canvas, w: Float, h: Float, fase: Float, p: Paint, caminho: Path, escuro: Boolean = false) {
            p.color = Color.BLACK // opaco: a cor da última onda (translúcida) apagaria o degradê
            p.shader = if (escuro)
                // Tela inicial: cinza-escuro em cima clareando até quase branco embaixo.
                LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF474849.toInt(), 0xFF7E7F81.toInt(), 0xFFCFD0D1.toInt(), 0xFFE9E9EA.toInt()),
                    floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFFF1F2F3.toInt(), 0xFFDDE0E3.toInt(), 0xFFC7CBCF.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            val ondas = if (escuro) listOf(Triple(0.55f, 34, 0f), Triple(0.72f, 26, 2.1f), Triple(0.38f, 18, 4f))
                else listOf(Triple(0.55f, 90, 0f), Triple(0.72f, 70, 2.1f), Triple(0.38f, 50, 4f))
            for ((base, alfa, desl) in ondas) {
                caminho.reset()
                val y0 = h * base
                caminho.moveTo(-w * 0.1f, y0 + sin(fase + desl) * h * 0.03f)
                caminho.cubicTo(w * 0.35f, y0 - h * 0.18f, w * 0.65f, y0 + h * 0.2f + sin(fase * 0.7f + desl) * h * 0.04f, w * 1.1f, y0 - h * 0.08f)
                caminho.lineTo(w * 1.1f, y0 - h * 0.05f)
                caminho.cubicTo(w * 0.65f, y0 + h * 0.24f, w * 0.35f, y0 - h * 0.13f, -w * 0.1f, y0 + h * 0.025f)
                caminho.close()
                p.color = Color.argb(alfa, 255, 255, 255)
                c.drawPath(caminho, p)
            }
        }
    }

    private enum class Zona { HUBS, BLOCOS }

    private var zona = Zona.BLOCOS
    private var hub = inicial.coerceIn(0, hubs.lastIndex)
    private var sel = 0
    private var rolagem = 0f
    private var rolagemAlvo = 0f
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
    var animarFundo = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    private fun blocos() = hubs[hub].blocos

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blocos().getOrNull(sel)?.let { ouvinte.selecionou(it.item) }
    }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        val fase = if (animarFundo) (agora % 80_000L) / 80_000f * (2 * Math.PI).toFloat() else 0f
        fundo(c, width.toFloat(), height.toFloat(), fase, p, caminho, escuro = true)
        rolagem += (rolagemAlvo - rolagem) * 0.3f
        if (abs(rolagemAlvo - rolagem) < 0.5f) rolagem = rolagemAlvo

        categorias(c)
        cartao(c)
        // Troca de categoria: o conteúdo entra deslizando do lado para onde se foi.
        val pt = ((agora - trocaIni) / 240f).coerceIn(0f, 1f)
        val desl = (1f - pt) * (1f - pt) * 120f * trocaDir
        c.save()
        c.translate((desl - rolagem) * u, 0f)
        val lista = blocos()
        for ((i, b) in lista.withIndex()) if (i != sel || zona != Zona.BLOCOS) bloco(c, b, false)
        if (zona == Zona.BLOCOS) lista.getOrNull(sel)?.let { bloco(c, it, true) }
        c.restore()
        // Como no console, a ponta da categoria vizinha aparece nas bordas (só quando a atual cabe na tela).
        if (pt >= 1f && rolagemAlvo == 0f) {
            hubs.getOrNull(hub + 1)?.takeIf { it.acao == null && maxDireita(lista) <= 1220f }?.let { prox ->
                c.save()
                c.clipRect(1236f * u, 0f, width.toFloat(), height.toFloat())
                c.translate((1236f - 64f) * u, 0f)
                prox.blocos.filter { it.x < 120f }.forEach { bloco(c, it, false, apagado = true) }
                c.restore()
            }
            hubs.getOrNull(hub - 1)?.takeIf { it.acao == null && it.blocos.isNotEmpty() && maxDireita(lista) <= 1220f }?.let { ant ->
                val fim = maxDireita(ant.blocos)
                c.save()
                c.clipRect(0f, 0f, 44f * u, height.toFloat())
                c.translate((44f - fim) * u, 0f)
                ant.blocos.filter { it.x + it.w > fim - 120f }.forEach { bloco(c, it, false, apagado = true) }
                c.restore()
            }
        }
        dica(c)
        if (lista.isEmpty()) {
            t.typeface = fina
            t.textSize = 26 * u
            t.color = CINZA_SUAVE
            c.drawText("Nada aqui ainda.", 64 * u, 220 * u, t)
        }
        if (pt < 1f || rolagem != rolagemAlvo) postInvalidateOnAnimation() else if (animarFundo) postInvalidateDelayed(60)
    }

    private fun maxDireita(l: List<Bloco>) = l.maxOfOrNull { it.x + it.w } ?: 0f

    /** "Ⓐ Selecionar" pequeno no canto de baixo. */
    private fun dica(c: Canvas) {
        p.color = VERDE_CLARO
        c.drawCircle(78f * u, 684f * u, 11f * u, p)
        t.typeface = normal
        t.textSize = 14f * u
        t.color = Color.WHITE
        t.textAlign = Paint.Align.CENTER
        c.drawText("A", 78f * u, 689f * u, t)
        t.textAlign = Paint.Align.LEFT
        t.textSize = 17f * u
        t.color = CINZA_TEXTO
        c.drawText("Selecionar", 96f * u, 690f * u, t)
    }

    private fun categorias(c: Canvas) {
        var x = 64f
        for ((i, h) in hubs.withIndex()) {
            val escolhida = i == hub
            t.typeface = if (escolhida) normal else fina
            t.textSize = 40 * u
            t.color = if (escolhida) Color.WHITE else Color.argb(150, 255, 255, 255)
            t.setShadowLayer(4 * u, 0f, 1.5f * u, Color.argb(if (escolhida) 110 else 50, 0, 0, 0))
            val larg = t.measureText(h.nome) / u
            c.drawText(h.nome, x * u, 90 * u, t)
            t.clearShadowLayer()
            if (escolhida && zona == Zona.HUBS) {
                p.color = VERDE_CLARO
                c.drawRect(x * u, 102 * u, (x + larg) * u, 106 * u, p)
            }
            x += larg + 48f
        }
    }

    /** Cartão do perfil: quadrado com a inicial, nome e pontos, e o relógio. */
    private fun cartao(c: Canvas) {
        val nome = ouvinte.perfil()
        t.typeface = normal
        t.textSize = 20 * u
        val largNome = t.measureText(nome)
        val x = 1216 * u - largNome
        p.color = VERDE
        r.set(x - 62 * u, 36 * u, x - 12 * u, 86 * u)
        c.drawRect(r, p)
        t.color = Color.WHITE
        t.textSize = 28 * u
        t.textAlign = Paint.Align.CENTER
        c.drawText(nome.take(1).uppercase(), r.centerX(), r.centerY() + 10 * u, t)
        t.textAlign = Paint.Align.LEFT
        t.textSize = 20 * u
        t.color = Color.WHITE
        c.drawText(nome, x, 56 * u, t)
        t.textSize = 16 * u
        t.color = Color.argb(190, 255, 255, 255)
        val pontos = ouvinte.pontos()
        c.drawText(listOfNotNull(pontos?.let { "Ⓖ $it" }, ouvinte.relogio()).joinToString("   "), x, 80 * u, t)
    }

    private fun bloco(c: Canvas, b: Bloco, focado: Boolean, apagado: Boolean = false) {
        val esc = if (focado) 1.06f else 1f
        val cx = (b.x + b.w / 2) * u
        val cy = (b.y + b.h / 2) * u
        val w = b.w * u * esc
        val h = b.h * u * esc
        r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        if (r.right - rolagem * u < -50 || r.left - rolagem * u > width + 50) return
        if (focado) {
            // Sombra embaixo e à direita (o bloco "sai" da tela).
            p.color = Color.argb(45, 0, 0, 0)
            c.drawRect(r.left + 4 * u, r.top + 8 * u, r.right + 9 * u, r.bottom + 13 * u, p)
            p.color = Color.argb(60, 0, 0, 0)
            c.drawRect(r.left + 2 * u, r.top + 4 * u, r.right + 5 * u, r.bottom + 7 * u, p)
        }
        val it = b.item
        val img = it.imagem?.let { url -> imagem(url, b.w, b.h) }
        when {
            img != null -> Ps4Desenho.cortado(c, img, r)
            it.simbolo != null -> {
                // Bloco verde liso; no foco, mais claro em degradê. (Cor opaca: senão o degradê herda a transparência.)
                p.color = Color.BLACK
                p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                    if (focado) intArrayOf(0xFF52C43C.toInt(), 0xFF1EA21E.toInt()) else intArrayOf(0xFF14A014.toInt(), 0xFF0F930F.toInt()),
                    null, Shader.TileMode.CLAMP)
                c.drawRect(r, p)
                p.shader = null
                val l = minOf(r.width(), r.height()) * 0.3f
                val fundoIc = if (focado) 0xFF33B12D.toInt() else 0xFF129A12.toInt()
                Ps4Desenho.icone(c, it.simbolo, r.centerX() - l / 2, r.centerY() - l / 2 - (if (b.comNome) 14 * u else 0f), l, fundoIc)
            }
            else -> {
                p.color = Paleta.tom(it.cor, 0.6f)
                c.drawRect(r, p)
                it.icone?.let { ic ->
                    val l = minOf(r.width(), r.height()) * 0.42f
                    c.drawBitmap(ic, null, RectF(r.centerX() - l / 2, r.centerY() - l / 2 - 8 * u, r.centerX() + l / 2, r.centerY() + l / 2 - 8 * u), pImg)
                }
            }
        }
        if (b.comNome && it.simbolo != null && img == null) {
            // Bloco verde: o nome direto no verde, embaixo à esquerda.
            t.typeface = normal
            t.textSize = minOf(21f * u, r.height() * 0.12f)
            t.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(it.nome, t, r.width() - 24 * u, TextUtils.TruncateAt.END).toString(), r.left + 13 * u, r.bottom - 14 * u, t)
        } else if (b.comNome) {
            // Faixa translúcida com o nome, embaixo, dentro do bloco (verde no foco).
            val fh = minOf(34f * u, r.height() * 0.3f)
            p.color = if (focado) Paleta.alfa(VERDE, 235) else Color.argb(150, 0, 0, 0)
            c.drawRect(r.left, r.bottom - fh, r.right, r.bottom, p)
            t.typeface = normal
            t.textSize = minOf(17f * u, fh * 0.55f)
            t.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(it.nome, t, r.width() - 16 * u, TextUtils.TruncateAt.END).toString(), r.left + 8 * u, r.bottom - fh * 0.32f, t)
        } else if (focado) {
            // Bloco pequeno sem nome: filete verde embaixo marca o foco.
            p.color = VERDE_CLARO
            c.drawRect(r.left, r.bottom - 5 * u, r.right, r.bottom, p)
        }
        if (apagado) {
            p.color = Color.argb(70, 60, 60, 62)
            c.drawRect(r, p)
        }
    }

    private fun imagem(url: String, w: Float, h: Float): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) ouvinte.carregar(url, (w * u * 1.1f).toInt().coerceAtLeast(64), (h * u * 1.1f).toInt().coerceAtLeast(64)) { b -> imagens[url] = b; invalidate() }
        return null
    }

    // ---------- Controle ----------

    /** Próximo bloco na direção (dx, dy): o mais perto cujo centro fica daquele lado. */
    private fun vizinho(dx: Int, dy: Int): Int? {
        val lista = blocos()
        val a = lista.getOrNull(sel) ?: return null
        val ax = a.x + a.w / 2
        val ay = a.y + a.h / 2
        var melhor: Int? = null
        var dist = Float.MAX_VALUE
        for ((i, b) in lista.withIndex()) {
            if (i == sel) continue
            val bx = b.x + b.w / 2
            val by = b.y + b.h / 2
            val ok = when {
                dx > 0 -> b.x >= a.x + a.w - 4
                dx < 0 -> b.x + b.w <= a.x + 4
                dy > 0 -> b.y >= a.y + a.h - 4
                else -> b.y + b.h <= a.y + 4
            }
            if (!ok) continue
            // Peso maior para o desvio na direção que não é a do movimento (fica na mesma linha/coluna).
            val d = if (dx != 0) abs(bx - ax) + abs(by - ay) * 2.2f else abs(by - ay) + abs(bx - ax) * 2.2f
            if (d < dist) { dist = d; melhor = i }
        }
        return melhor
    }

    private fun selecionar(i: Int) {
        sel = i
        val b = blocos()[i]
        // Rola para o bloco ficar inteiro na tela.
        if (b.x + b.w - rolagemAlvo > 1216f) rolagemAlvo = b.x + b.w - 1216f
        if (b.x - rolagemAlvo < 64f) rolagemAlvo = max(0f, b.x - 64f)
        Sons.tocar(Sons.Evento.MOVER)
        ouvinte.selecionou(b.item)
        invalidate()
    }

    /** Onde o foco está (para a tela remontada continuar na mesma categoria e no mesmo bloco). */
    class Estado(val hub: String, val sel: Int, val nosHubs: Boolean, val rolagem: Float)

    /** Só depois de a pessoa mexer: a tela montada antes de os jogos carregarem não vale como posição. */
    private var mexeu = false

    fun estado(): Estado? = if (!mexeu) null else Estado(hubs[hub].nome, sel, zona == Zona.HUBS, rolagemAlvo)

    /** Volta ao [e] sem animação (chamar antes de a tela aparecer). */
    fun restaurar(e: Estado) {
        mexeu = true
        val i = hubs.indexOfFirst { it.nome == e.hub }
        if (i < 0) return
        hub = i
        sel = e.sel.coerceIn(0, max(0, blocos().lastIndex))
        zona = if (e.nosHubs) Zona.HUBS else Zona.BLOCOS
        rolagem = e.rolagem
        rolagemAlvo = e.rolagem
        invalidate()
    }

    /** Vai para a categoria vizinha (LB/RB ou as setas na linha das categorias). */
    fun trocarHub(passo: Int) {
        var novo = hub + passo
        // Categorias que só fazem uma ação (buscar) não entram no passeio pelo LB/RB.
        while (novo in hubs.indices && hubs[novo].acao != null && zona == Zona.BLOCOS) novo += passo
        if (novo !in hubs.indices) return
        hub = novo
        sel = 0
        rolagem = 0f
        rolagemAlvo = 0f
        trocaIni = SystemClock.uptimeMillis()
        trocaDir = passo
        Sons.tocar(Sons.Evento.MOVER)
        blocos().firstOrNull()?.let { ouvinte.selecionou(it.item) }
        invalidate()
    }

    /** Vai direto para a categoria [nome] ("jogos", "apps"). */
    fun irPara(nome: String) {
        val i = hubs.indexOfFirst { it.nome == nome }
        if (i < 0 || i == hub) return
        zona = Zona.BLOCOS
        trocarHub(i - hub)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        mexeu = true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (zona == Zona.HUBS) trocarHub(-1) else (vizinho(-1, 0)?.let { selecionar(it) } ?: trocarHub(-1))
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (zona == Zona.HUBS) trocarHub(1) else (vizinho(1, 0)?.let { selecionar(it) } ?: trocarHub(1))
            KeyEvent.KEYCODE_DPAD_UP -> if (zona == Zona.BLOCOS) (vizinho(0, -1)?.let { selecionar(it) } ?: run { zona = Zona.HUBS; Sons.tocar(Sons.Evento.MOVER); invalidate() })
            KeyEvent.KEYCODE_DPAD_DOWN -> if (zona == Zona.HUBS) { if (blocos().isNotEmpty()) { zona = Zona.BLOCOS; Sons.tocar(Sons.Evento.MOVER); invalidate() } } else vizinho(0, 1)?.let { selecionar(it) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun ativar() {
        val h = hubs[hub]
        if (zona == Zona.HUBS || h.blocos.isEmpty()) {
            h.acao?.invoke() ?: run { if (h.blocos.isNotEmpty()) { zona = Zona.BLOCOS; invalidate() } }
            return
        }
        blocos().getOrNull(sel)?.item?.abrir?.invoke()
    }

    override fun informacoes() { if (zona == Zona.BLOCOS) blocos().getOrNull(sel)?.item?.info?.invoke() }

    override fun voltar(): Boolean {
        if (zona == Zona.HUBS) { zona = Zona.BLOCOS; invalidate(); return true }
        val inicio = hubs.indexOfFirst { it.nome == "início" }.coerceAtLeast(0)
        if (hub != inicio) { trocarHub(inicio - hub); return true }
        if (sel != 0) { selecionar(0); return true }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        if (zona == Zona.BLOCOS) blocos().getOrNull(sel)?.item?.opcoes?.invoke()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        mexeu = true
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u
        if (y < 110f) {
            var hx = 64f
            for ((i, h) in hubs.withIndex()) {
                t.typeface = fina
                t.textSize = (if (i == hub) 46 else 34) * u
                val larg = t.measureText(h.nome) / u
                if (x in hx..(hx + larg)) {
                    if (h.acao != null) h.acao.invoke() else if (i != hub) trocarHub(i - hub)
                    return true
                }
                hx += larg + 30f
            }
            return true
        }
        for ((i, b) in blocos().withIndex()) {
            val bx = b.x - rolagem
            if (x in bx..(bx + b.w) && y in b.y..(b.y + b.h)) {
                zona = Zona.BLOCOS
                if (i == sel) ativar() else selecionar(i)
                return true
            }
        }
        return true
    }

    override fun onVisibilityAggregated(visivel: Boolean) {
        super.onVisibilityAggregated(visivel)
        if (visivel) invalidate()
    }

    fun atualizar() = invalidate()
}

/** Fundo claro do estilo Xbox 360 como View. */
class FundoXbox(ctx: Context, private val animado: Boolean = true) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caminho = Path()
    override fun onDraw(c: Canvas) {
        val fase = if (animado) (SystemClock.uptimeMillis() % 80_000L) / 80_000f * (2 * Math.PI).toFloat() else 0f
        InicioXbox.fundo(c, width.toFloat(), height.toFloat(), fase, p, caminho)
        if (animado && isShown) postInvalidateDelayed(60)
    }
}

/** Seleção do estilo Xbox 360: fundo verde no foco (o texto fica branco), linha fina embaixo fora dele. */
class SelecaoXbox(private val u: Float, private val escuro: Boolean = false) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        if (focado) {
            p.color = InicioXbox.VERDE
            c.drawRect(b, p)
        } else {
            p.color = if (escuro) Color.argb(40, 255, 255, 255) else Color.argb(40, 0, 0, 0)
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

/** Dicas de baixo do estilo Xbox 360 ("Ⓐ Selecionar  Ⓑ Voltar"), com os botões coloridos. */
class DicasXbox(ctx: Context, private val dicas: List<Pair<Char, String>>, private val corTexto: Int = InicioXbox.CINZA_TEXTO) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val u = width / 1280f
        val cy = height / 2f
        var x = 84f * u
        for ((b, texto) in dicas) {
            p.color = when (b) { 'A' -> 0xFF5DC21E.toInt(); 'B' -> 0xFFE3342F.toInt(); 'X' -> 0xFF2A7DE1.toInt(); else -> 0xFFF2C21B.toInt() }
            c.drawCircle(x, cy, 12 * u, p)
            t.typeface = InicioXbox.normal
            t.textSize = 15 * u
            t.color = Color.WHITE
            t.textAlign = Paint.Align.CENTER
            c.drawText(b.toString(), x, cy + 5.5f * u, t)
            t.textAlign = Paint.Align.LEFT
            t.textSize = 19 * u
            t.color = corTexto
            c.drawText(texto, x + 20 * u, cy + 7 * u, t)
            x += 20 * u + t.measureText(texto) + 40 * u
        }
    }
}
