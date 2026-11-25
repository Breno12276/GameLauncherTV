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
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Tela inicial no estilo do PS5, desenhada inteira aqui:
 * - em cima, à esquerda, as abas "Jogos" e "Mídia"; à direita, buscar, configurações, o perfil (círculo) e a hora;
 * - logo abaixo, uma fileira de ícones quadrados de cantos arredondados; o selecionado fica maior, com o contorno
 *   branco, e o nome aparece ao lado dele;
 * - a arte do item selecionado ocupa a tela toda (escurecida à esquerda e embaixo);
 * - embaixo da fileira: o nome grande, o botão "Jogar" (branco no foco) com "…" e "ⓘ", e cartões do jogo.
 * Ícones e desenhos são próprios (nada da Sony). Medidas pensadas numa tela de 1280 × 720.
 */
class InicioPs5(
    ctx: Context, private val abas: List<Aba>, private val topo: List<Atalho>, private val ouvinte: Ouvinte,
) : View(ctx), TelaPs4 {

    /** "Jogos" ou "Mídia": a fileira de cada uma. */
    class Aba(val nome: String, val itens: List<InicioPs4.Item>)

    /** Ícone do canto de cima (buscar, configurações); [icone] null = o perfil. */
    class Atalho(val icone: Ps4Desenho.Icone?, val rotulo: String, val acao: () -> Unit)

    interface Ouvinte {
        fun selecionou(item: InicioPs4.Item)
        fun relogio(): String
        fun perfil(): String
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
        /** Arte de fundo grande do jogo (url), quando houver. */
        fun heroi(item: InicioPs4.Item, pronto: (String) -> Unit)
    }

    private val arte = ArteDeFundo(this, { i, f -> ouvinte.heroi(i, f) }, { a, b, c, d -> ouvinte.carregar(a, b, c, d) })

    private enum class Zona { TOPO, FILEIRA, BOTOES }

    private var aba = 0
    private var zona = Zona.FILEIRA
    private var sel = IntArray(abas.size) { i -> if (abas[i].itens.size > 1) 1 else 0 }
    /** No topo: 0..abas-1 = as abas; depois os atalhos. */
    private var selTopo = 0
    private var selBotao = 0

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val caminho = Path()

    // Animação da fileira (x de cada ícone) e da troca da arte de fundo.
    private var xs = FloatArray(0)
    private var xsDe = FloatArray(0)
    private var animIni = 0L
    private val ANIM = 180L
    private var fundoAnterior: Bitmap? = null
    private var fundoIni = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    private fun itens() = abas[aba].itens
    private fun atual() = itens().getOrNull(sel[aba])

    // ---------- Geometria (unidades de 1280 × 720) ----------

    private val PEQ = 92f
    private val GRANDE = 140f
    private val VAO = 14f
    private val Y_FILEIRA = 112f
    private val X_SEL = 186f

    /** x de cada ícone: o selecionado fica em [X_SEL] (o primeiro, no começo), com o nome do lado. */
    private fun destinos(): FloatArray {
        val l = itens()
        val s = sel[aba]
        val out = FloatArray(l.size)
        val xSel = if (s == 0) 72f else X_SEL
        t.typeface = Ps4Desenho.normal
        t.textSize = 22 * u
        val nome = min(260f, (l.getOrNull(s)?.nome?.let { t.measureText(it) / u } ?: 0f) + 30f)
        for (i in l.indices) out[i] = when {
            i == s -> xSel
            i < s -> xSel - (s - i) * (PEQ + VAO)
            else -> xSel + GRANDE + VAO + nome + (i - s - 1) * (PEQ + VAO)
        }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = w / 1280f
        xs = destinos()
        xsDe = xs.copyOf()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        atual()?.let { ouvinte.selecionou(it) }
    }

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        val k = ((agora - animIni) / ANIM.toFloat()).coerceIn(0f, 1f)
        val ef = 1f - (1f - k) * (1f - k)
        val alvo = destinos()
        if (xs.size != alvo.size) { xs = alvo.copyOf(); xsDe = alvo.copyOf() }
        for (i in xs.indices) xs[i] = xsDe[i] + (alvo[i] - xsDe[i]) * ef

        fundo(c, agora)
        barraDeCima(c)
        fileira(c)
        detalhes(c)
        if (k < 1f || agora - fundoIni < 400) postInvalidateOnAnimation()
    }

    /** Arte do item selecionado na tela toda, escurecida à esquerda e embaixo (como no console). */
    private fun fundo(c: Canvas, agora: Long) {
        c.drawColor(0xFF0B0C10.toInt())
        val atualImg = atual()?.let { it2 -> arte.de(it2, it2.imagem?.let { url -> imagem(url, (GRANDE * 1.3f).toInt()) }) }
        val kf = ((agora - fundoIni) / 400f).coerceIn(0f, 1f)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        fundoAnterior?.takeIf { kf < 1f }?.let { arte.desenhar(c, it, r) }
        atualImg?.let {
            val a = (kf * 255).toInt()
            c.saveLayerAlpha(r, a)
            arte.desenhar(c, it, r)
            if (arte.borrada(it)) { p.shader = null; p.color = Color.argb(130, 8, 8, 10); c.drawRect(r, p) }
            c.restore()
        }
        // Véu escuro: forte à esquerda e embaixo, para o texto ler bem.
        p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0xE6080A0F.toInt(), 0x99080A0F.toInt(), 0x33080A0F),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = LinearGradient(0f, height * 0.35f, 0f, height.toFloat(), intArrayOf(0x00080A0F, 0xF0080A0F.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = LinearGradient(0f, 0f, 0f, 200 * u, intArrayOf(0xB0080A0F.toInt(), 0x00080A0F), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), 200 * u, p)
        p.shader = null
    }

    private fun barraDeCima(c: Canvas) {
        // Abas.
        var x = 72f
        for ((i, a) in abas.withIndex()) {
            val escolhida = i == aba
            val focada = zona == Zona.TOPO && selTopo == i
            t.typeface = if (escolhida) Ps4Desenho.normal else Ps4Desenho.leve
            t.isFakeBoldText = escolhida
            t.textSize = 26 * u
            t.color = if (escolhida) Color.WHITE else Color.argb(150, 255, 255, 255)
            val larg = t.measureText(a.nome) / u
            if (focada) {
                p.color = Color.argb(45, 255, 255, 255)
                c.drawRoundRect((x - 14) * u, 36 * u, (x + larg + 14) * u, 82 * u, 23 * u, 23 * u, p)
                contorno(c, RectF((x - 14) * u, 36 * u, (x + larg + 14) * u, 82 * u), 23 * u, 0f)
            }
            c.drawText(a.nome, x * u, 68 * u, t)
            t.isFakeBoldText = false
            x += larg + 40f
        }
        // Hora, perfil e atalhos, da direita para a esquerda.
        t.typeface = Ps4Desenho.normal
        t.textSize = 22 * u
        t.color = Color.WHITE
        t.textAlign = Paint.Align.RIGHT
        val hora = ouvinte.relogio()
        c.drawText(hora, 1216 * u, 67 * u, t)
        t.textAlign = Paint.Align.LEFT
        var dx = 1216f - t.measureText(hora) / u - 30f
        for (i in topo.indices.reversed()) {
            val a = topo[i]
            val cx = dx - 22f
            val focado = zona == Zona.TOPO && selTopo == abas.size + i
            if (focado) {
                p.color = Color.argb(60, 255, 255, 255)
                c.drawCircle(cx * u, 60 * u, 26 * u, p)
                contorno(c, RectF((cx - 26) * u, 34 * u, (cx + 26) * u, 86 * u), 26 * u, 0f)
            }
            if (a.icone == null) {
                p.color = 0xFF2D6CDF.toInt()
                c.drawCircle(cx * u, 60 * u, 19 * u, p)
                t.textSize = 20 * u
                t.textAlign = Paint.Align.CENTER
                c.drawText(ouvinte.perfil().take(1).uppercase(), cx * u, 67 * u, t)
                t.textAlign = Paint.Align.LEFT
            } else {
                val l = 28 * u
                Ps4Desenho.icone(c, a.icone, cx * u - l / 2, 60 * u - l / 2, l, 0xFF1A1C22.toInt())
            }
            if (focado) {
                t.textSize = 15 * u
                t.textAlign = Paint.Align.CENTER
                c.drawText(a.rotulo, cx * u, 108 * u, t)
                t.textAlign = Paint.Align.LEFT
            }
            dx -= 58f
        }
    }

    private fun fileira(c: Canvas) {
        val l = itens()
        val s = sel[aba]
        for (i in l.indices) {
            val x = xs.getOrNull(i) ?: continue
            if (x > 1300 || x < -GRANDE) continue
            val grande = i == s
            val lado = if (grande) GRANDE else PEQ
            r.set(x * u, Y_FILEIRA * u, (x + lado) * u, (Y_FILEIRA + lado) * u)
            val rr = (if (grande) 16f else 12f) * u
            icone(c, l[i], r, rr)
            if (grande && zona == Zona.FILEIRA) contorno(c, r, rr, 4f)
        }
        // Nome ao lado do selecionado.
        val it = l.getOrNull(s) ?: return
        val x = (xs.getOrNull(s) ?: return) + GRANDE + 16f
        t.typeface = Ps4Desenho.normal
        t.textSize = 22 * u
        t.color = Color.WHITE
        c.drawText(TextUtils.ellipsize(it.nome, t, 250 * u, TextUtils.TruncateAt.END).toString(), x * u, (Y_FILEIRA + 30) * u, t)
    }

    private fun icone(c: Canvas, it: InicioPs4.Item, r: RectF, rr: Float) {
        c.save()
        caminho.reset()
        caminho.addRoundRect(r, rr, rr, Path.Direction.CW)
        c.clipPath(caminho)
        val img = it.imagem?.let { url -> imagem(url, (GRANDE * 1.3f).toInt()) }
        when {
            img != null -> Ps4Desenho.cortado(c, img, r)
            it.simbolo != null -> {
                p.color = 0xFF2A2D35.toInt()
                c.drawRect(r, p)
                val l = r.width() * 0.42f
                Ps4Desenho.icone(c, it.simbolo, r.centerX() - l / 2, r.centerY() - l / 2, l, 0xFF2A2D35.toInt())
            }
            else -> {
                p.color = Paleta.tom(it.cor, 0.6f)
                c.drawRect(r, p)
                it.icone?.let { ic ->
                    val l = r.width() * 0.52f
                    c.drawBitmap(ic, null, RectF(r.centerX() - l / 2, r.centerY() - l / 2, r.centerX() + l / 2, r.centerY() + l / 2), pImg)
                }
            }
        }
        c.restore()
    }

    /** Embaixo da fileira: nome grande, os botões e os cartões do item. */
    private fun detalhes(c: Canvas) {
        val it = atual() ?: return
        t.typeface = Ps4Desenho.leve
        t.textSize = 50 * u
        t.color = Color.WHITE
        c.drawText(TextUtils.ellipsize(it.nome, t, 760 * u, TextUtils.TruncateAt.END).toString(), 72 * u, 420 * u, t)
        // Botões: Jogar/Abrir (pílula larga), "…" (opções) e "ⓘ" (informações).
        val botoes = botoes(it)
        var x = 72f
        for ((i, b) in botoes.withIndex()) {
            val larg = if (i == 0) 210f else 58f
            r.set(x * u, 452 * u, (x + larg) * u, 510 * u)
            val focado = zona == Zona.BOTOES && selBotao == i
            p.color = if (focado) Color.WHITE else Color.argb(if (i == 0) 70 else 45, 255, 255, 255)
            val rr = 29 * u
            c.drawRoundRect(r, rr, rr, p)
            t.typeface = Ps4Desenho.normal
            t.textSize = (if (i == 0) 21 else 24) * u
            t.color = if (focado) 0xFF101114.toInt() else Color.WHITE
            t.textAlign = Paint.Align.CENTER
            c.drawText(b, r.centerX(), r.centerY() + 8 * u, t)
            t.textAlign = Paint.Align.LEFT
            x += larg + 12f
        }
        // Cartões (informação): detalhe e plataforma.
        val cartoes = listOfNotNull(
            it.detalhe.takeIf { d -> d.isNotBlank() }?.let { d -> (if (it.ehJogo) "Atividade" else "Categoria") to d },
            it.plataforma.takeIf { pl -> pl.isNotBlank() && pl != it.detalhe }?.let { pl -> "Plataforma" to pl },
            (if (it.ehJogo) "Jogo" else "App") to (if (it.ehJogo) "Pressione ⓘ para ver detalhes" else "Abrir com ⓧ"),
        )
        var cx = 72f
        for ((titulo, texto) in cartoes) {
            r.set(cx * u, 548 * u, (cx + 300) * u, 652 * u)
            p.color = Color.argb(150, 28, 30, 36)
            c.drawRoundRect(r, 12 * u, 12 * u, p)
            t.typeface = Ps4Desenho.normal
            t.textSize = 15 * u
            t.color = Color.argb(170, 255, 255, 255)
            c.drawText(titulo, r.left + 18 * u, r.top + 32 * u, t)
            t.textSize = 19 * u
            t.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(texto, t, r.width() - 36 * u, TextUtils.TruncateAt.END).toString(), r.left + 18 * u, r.top + 66 * u, t)
            cx += 316f
        }
    }

    private fun botoes(it: InicioPs4.Item) = listOfNotNull(
        if (it.ehJogo) "Jogar" else if (it.simbolo != null) "Abrir" else "Abrir",
        "⋯".takeIf { _ -> it.opcoes != null },
        "ⓘ".takeIf { _ -> it.info != null },
    )

    /** Contorno branco do foco (com um vão escuro de [vao] entre ele e o desenho). */
    private fun contorno(c: Canvas, r: RectF, rr: Float, vao: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.5f * u
        p.color = Color.WHITE
        val d = (vao + 1.75f) * u
        c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, rr + d, rr + d, p)
        p.style = Paint.Style.FILL
    }

    private fun imagem(url: String, lado: Int): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) {
            val l = (lado * u).toInt().coerceAtLeast(64)
            ouvinte.carregar(url, l, (l * 0.75f).toInt().coerceAtLeast(64)) { b -> imagens[url] = b; invalidate() }
        }
        return null
    }

    // ---------- Controle ----------

    private fun mudarSelecao(novo: Int) {
        val l = itens()
        if (novo !in l.indices || novo == sel[aba]) return
        xsDe = xs.copyOf()
        fundoAnterior = atual()?.let { arte.de(it, it.imagem?.let { url -> imagens[url] }) }
        fundoIni = SystemClock.uptimeMillis()
        sel[aba] = novo
        animIni = SystemClock.uptimeMillis()
        Sons.tocar(Sons.Evento.MOVER)
        atual()?.let { ouvinte.selecionou(it) }
        invalidate()
    }

    fun trocarAba(i: Int) {
        if (i !in abas.indices || i == aba) return
        aba = i
        xs = destinos()
        xsDe = xs.copyOf()
        fundoAnterior = null
        fundoIni = SystemClock.uptimeMillis()
        Sons.tocar(Sons.Evento.MOVER)
        atual()?.let { ouvinte.selecionou(it) }
        invalidate()
    }

    private fun mover(f: () -> Unit) {
        f()
        Sons.tocar(Sons.Evento.MOVER)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        mexeu = true
        val nTopo = abas.size + topo.size
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zona) {
                Zona.FILEIRA -> mudarSelecao(sel[aba] - 1)
                Zona.TOPO -> mover { selTopo = max(0, selTopo - 1) }
                Zona.BOTOES -> mover { selBotao = max(0, selBotao - 1) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zona) {
                Zona.FILEIRA -> mudarSelecao(sel[aba] + 1)
                Zona.TOPO -> mover { selTopo = min(nTopo - 1, selTopo + 1) }
                Zona.BOTOES -> mover { selBotao = min(atual()?.let { botoes(it).lastIndex } ?: 0, selBotao + 1) }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (zona) {
                Zona.FILEIRA -> mover { zona = Zona.TOPO; selTopo = aba }
                Zona.BOTOES -> mover { zona = Zona.FILEIRA }
                Zona.TOPO -> {}
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zona) {
                Zona.TOPO -> mover { zona = Zona.FILEIRA }
                Zona.FILEIRA -> if (atual() != null) mover { zona = Zona.BOTOES; selBotao = 0 }
                Zona.BOTOES -> {}
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) ativar()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    fun ativar() {
        when (zona) {
            Zona.FILEIRA -> atual()?.abrir?.invoke()
            Zona.TOPO -> if (selTopo < abas.size) { trocarAba(selTopo); zona = Zona.FILEIRA } else topo.getOrNull(selTopo - abas.size)?.acao?.invoke()
            Zona.BOTOES -> {
                val it = atual() ?: return
                when (botoes(it).getOrNull(selBotao)) {
                    "⋯" -> it.opcoes?.invoke()
                    "ⓘ" -> it.info?.invoke()
                    else -> it.abrir()
                }
            }
        }
    }

    override fun informacoes() { atual()?.info?.invoke() }

    override fun voltar(): Boolean {
        if (zona != Zona.FILEIRA) { mover { zona = Zona.FILEIRA }; return true }
        val inicio = if (itens().size > 1) 1 else 0
        if (sel[aba] <= inicio) return false
        mudarSelecao(inicio)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        ativar()
        return true
    }

    override fun performLongClick(): Boolean {
        atual()?.opcoes?.invoke()
        return true
    }

    fun atualizar() = invalidate()

    /** Onde o foco está (a tela remontada continua no mesmo lugar). */
    class Estado(val aba: Int, val nome: String?, val zona: Int)

    /** Só depois de a pessoa mexer: a tela montada antes de os jogos carregarem não vale como posição. */
    private var mexeu = false

    fun estado(): Estado? = if (!mexeu) null else Estado(aba, atual()?.nome, zona.ordinal)

    fun restaurar(e: Estado) {
        mexeu = true
        if (e.aba in abas.indices) aba = e.aba
        itens().indexOfFirst { it.nome == e.nome }.takeIf { it >= 0 }?.let { sel[aba] = it }
        zona = Zona.values()[e.zona]
        if (zona == Zona.BOTOES) zona = Zona.FILEIRA
        selTopo = aba
        invalidate()
    }

    // ---------- Toque ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        mexeu = true
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u
        if (y < 100) {
            var ax = 72f
            t.textSize = 26 * u
            for (i in abas.indices) {
                val larg = t.measureText(abas[i].nome) / u
                if (x in ax..(ax + larg)) { trocarAba(i); zona = Zona.FILEIRA; return true }
                ax += larg + 40f
            }
            return true
        }
        if (y in Y_FILEIRA..(Y_FILEIRA + GRANDE)) {
            for (i in xs.indices) {
                val lado = if (i == sel[aba]) GRANDE else PEQ
                if (x in xs[i]..(xs[i] + lado)) {
                    zona = Zona.FILEIRA
                    if (i == sel[aba]) ativar() else mudarSelecao(i)
                    return true
                }
            }
        }
        if (y in 452f..510f) {
            var bx = 72f
            val l = atual()?.let { botoes(it) } ?: return true
            for (i in l.indices) {
                val larg = if (i == 0) 210f else 58f
                if (x in bx..(bx + larg)) { zona = Zona.BOTOES; selBotao = i; ativar(); return true }
                bx += larg + 12f
            }
        }
        return true
    }
}

/**
 * Arte de fundo das telas modernas (PS5, Xbox Series): a arte grande do jogo (herói) quando houver; enquanto
 * não chega (ou não existe), a capa reduzida e esticada com filtro, que fica suave em vez de pixelada.
 */
class ArteDeFundo(private val view: View, private val heroi: (InicioPs4.Item, (String) -> Unit) -> Unit,
                  private val carregar: (String, Int, Int, (Bitmap) -> Unit) -> Unit) {
    private val urls = HashMap<String, String>()
    private val pedidas = HashSet<String>()
    private val grandes = HashMap<String, Bitmap>()
    private val suaves = HashMap<Bitmap, Bitmap>()
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** A melhor imagem disponível agora para [it] (pede o que falta). [capa]: a capa já carregada, se houver. */
    fun de(it: InicioPs4.Item, capa: Bitmap?): Bitmap? {
        val chave = it.id.ifEmpty { it.nome }
        urls[chave]?.let { url -> grandes[url]?.let { b -> return b } }
        if (it.ehJogo && it.id.isNotEmpty() && pedidas.add(chave)) heroi(it) { url ->
            urls[chave] = url
            carregar(url, view.width.coerceAtLeast(640), view.height.coerceAtLeast(360)) { b -> grandes[url] = b; view.invalidate() }
        }
        return capa?.let { c -> suaves.getOrPut(c) { borrar(c) } }
    }

    /** Capa borrada: reduz, passa um desfoque de caixa (3 vezes ≈ gaussiano) e amplia com filtro. */
    private fun borrar(original: Bitmap): Bitmap {
        val c = if (original.config == Bitmap.Config.HARDWARE) original.copy(Bitmap.Config.ARGB_8888, false) else original
        val w = 96
        val h = (w * c.height / c.width.coerceAtLeast(1)).coerceIn(16, 160)
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(c, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            caixa(px, tmp, w, h, 3, true)
            caixa(tmp, px, w, h, 3, false)
        }
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), w * 5, h * 5, true)
    }

    private fun caixa(de: IntArray, para: IntArray, w: Int, h: Int, raio: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val linhas = if (horizontal) h else w
        for (l in 0 until linhas) {
            for (i in 0 until n) {
                var r = 0; var g = 0; var b = 0; var cont = 0
                for (k in -raio..raio) {
                    val j = (i + k).coerceIn(0, n - 1)
                    val v = if (horizontal) de[l * w + j] else de[j * w + l]
                    r += (v shr 16) and 255; g += (v shr 8) and 255; b += v and 255; cont++
                }
                val idx = if (horizontal) l * w + i else i * w + l
                para[idx] = (0xFF shl 24) or ((r / cont) shl 16) or ((g / cont) shl 8) or (b / cont)
            }
        }
    }

    /** true quando [b] é só a capa borrada (o jogo ainda não tem, ou não tem, arte grande). */
    fun borrada(b: Bitmap) = suaves.containsValue(b)

    fun desenhar(c: Canvas, b: Bitmap, r: RectF) {
        val prop = r.width() / r.height()
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val src = if (bw / bh > prop) {
            val w = bh * prop
            android.graphics.Rect(((bw - w) / 2).toInt(), 0, ((bw + w) / 2).toInt(), bh.toInt())
        } else {
            val h = bw / prop
            android.graphics.Rect(0, ((bh - h) / 3).toInt(), bw.toInt(), ((bh - h) / 3 + h).toInt())
        }
        c.drawBitmap(b, src, r, pImg)
    }
}

/** Fundo das telas do estilo PS5 (configurações, grades): quase preto com um brilho azulado suave. */
class FundoPs5(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h, intArrayOf(0xFF151A26.toInt(), 0xFF0B0D13.toInt(), 0xFF07080B.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.RadialGradient(w * 0.85f, h * 0.1f, w * 0.6f, intArrayOf(0x302F5FD0, 0x002F5FD0), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }
}

/** Seleção arredondada dos estilos modernos (PS5, Xbox Series): fundo claro translúcido e contorno branco. */
class SelecaoModerna(private val u: Float, private val raio: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        val m = 3 * u
        val rr = raio * u
        if (focado) {
            p.color = Color.argb(38, 255, 255, 255)
            c.drawRoundRect(b.left + m, b.top + m, b.right - m, b.bottom - m, rr, rr, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * u
            p.color = Color.WHITE
            c.drawRoundRect(b.left + m, b.top + m, b.right - m, b.bottom - m, rr, rr, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = Color.argb(30, 255, 255, 255)
            c.drawRect(b.left + 16 * u, b.bottom - 1.2f * u, b.right - 16 * u, b.bottom.toFloat(), p)
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
