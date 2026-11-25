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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tela inicial no estilo do Xbox Series (o painel de 2023), desenhada inteira aqui:
 * - a arte do jogo selecionado ocupa a tela, escurecida embaixo;
 * - em cima: perfil (círculo, nome e pontos) à esquerda; hora à direita;
 * - logo abaixo, a fileira de acesso rápido: botões quadrados pequenos com ícone (biblioteca, loja, buscar…);
 * - mais embaixo, "jogados recentemente": o primeiro bloco maior, os outros menores, com o contorno branco no foco;
 * - descendo, outras fileiras (apps fixados, todos os jogos) sobem e o fundo escurece.
 * Ícones e desenhos próprios (nada da Microsoft). Medidas numa tela de 1280 × 720.
 */
class InicioSeries(
    ctx: Context, private val rapidos: List<InicioPs4.Item>, private val fileiras: List<Fileira>, private val ouvinte: Ouvinte,
) : View(ctx), TelaPs4 {

    class Fileira(val titulo: String, val itens: List<InicioPs4.Item>)

    interface Ouvinte {
        fun selecionou(item: InicioPs4.Item)
        fun relogio(): String
        fun perfil(): String
        fun pontos(): String?
        fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit)
        /** Arte de fundo grande do jogo (url), quando houver. */
        fun heroi(item: InicioPs4.Item, pronto: (String) -> Unit)
    }

    private val arte = ArteDeFundo(this, { i, f -> ouvinte.heroi(i, f) }, { a, b, c, d -> ouvinte.carregar(a, b, c, d) })

    /** -1 = acesso rápido; 0.. = as fileiras. */
    private var linha = 0
    private var selRapido = 0
    private val sel = IntArray(fileiras.size)
    private val desloc = FloatArray(fileiras.size)
    private val deslocAlvo = FloatArray(fileiras.size)
    private var rolagem = 0f
    private var rolagemAlvo = 0f

    private val imagens = HashMap<String, Bitmap>()
    private val pedidas = HashSet<String>()
    private var u = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val caminho = Path()
    private val inverter = Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    }
    private var fundoAnterior: Bitmap? = null
    private var fundoIni = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        contentDescription = "Tela inicial"
    }

    // ---------- Geometria ----------

    private val X0 = 64f
    private val Y_RAPIDO = 118f
    private val RAP = 74f
    private val Y_REC = 360f
    private val GRANDE = 214f
    private val PEQ = 150f
    private val VAO = 12f
    private val PASSO_LINHA = 250f

    /** Topo de cada fileira (antes da rolagem). A primeira (recentes) fica embaixo da arte. */
    private fun yLinha(i: Int) = if (i == 0) Y_REC else Y_REC + GRANDE + 110f + (i - 1) * PASSO_LINHA

    private fun lado(i: Int, j: Int) = if (i == 0 && j == 0) GRANDE else PEQ

    private fun xItem(i: Int, j: Int): Float {
        if (i == 0) return if (j == 0) X0 else X0 + GRANDE + VAO + (j - 1) * (PEQ + VAO)
        return X0 + j * (PEQ + VAO)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { u = w / 1280f }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        atual()?.let { ouvinte.selecionou(it) }
    }

    private fun atual(): InicioPs4.Item? =
        if (linha < 0) rapidos.getOrNull(selRapido) else fileiras.getOrNull(linha)?.itens?.getOrNull(sel[linha])

    /** A arte de fundo é a do jogo em foco nos recentes (como no console). */
    private fun itemDoFundo() = fileiras.getOrNull(0)?.itens?.getOrNull(sel.getOrElse(0) { 0 })

    // ---------- Desenho ----------

    override fun onDraw(c: Canvas) {
        val agora = SystemClock.uptimeMillis()
        rolagem += (rolagemAlvo - rolagem) * 0.25f
        if (abs(rolagemAlvo - rolagem) < 0.5f) rolagem = rolagemAlvo
        var animando = rolagem != rolagemAlvo
        for (i in desloc.indices) {
            desloc[i] += (deslocAlvo[i] - desloc[i]) * 0.3f
            if (abs(deslocAlvo[i] - desloc[i]) < 0.5f) desloc[i] = deslocAlvo[i] else animando = true
        }
        fundo(c, agora)
        c.save()
        c.translate(0f, -rolagem * u)
        topo(c)
        acessoRapido(c)
        for (i in fileiras.indices) fileira(c, i)
        c.restore()
        if (animando || agora - fundoIni < 450) postInvalidateOnAnimation()
    }

    private fun fundo(c: Canvas, agora: Long) {
        c.drawColor(0xFF0E0F10.toInt())
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val kf = ((agora - fundoIni) / 450f).coerceIn(0f, 1f)
        fundoAnterior?.takeIf { kf < 1f }?.let { arte.desenhar(c, it, r) }
        itemDoFundo()?.let { it2 -> arte.de(it2, it2.imagem?.let { url -> imagem(url, (GRANDE * 1.2f).toInt()) }) }?.let {
            c.saveLayerAlpha(r, (kf * 255).toInt())
            arte.desenhar(c, it, r)
            if (arte.borrada(it)) { p.shader = null; p.color = Color.argb(150, 10, 10, 11); c.drawRect(r, p) }
            c.restore()
        }
        // Escurece embaixo (onde ficam as fileiras) e mais ainda quando se desce.
        val desce = (rolagem / 300f).coerceIn(0f, 1f)
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, height * 0.25f, 0f, height.toFloat(),
            intArrayOf(Color.argb((40 + 120 * desce).toInt(), 10, 10, 11), Color.argb(240, 10, 10, 11)), null, Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = LinearGradient(0f, 0f, 0f, 170 * u, intArrayOf(0xA00A0A0B.toInt(), 0x000A0A0B), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), 170 * u, p)
        p.shader = null
        if (desce > 0f) {
            p.color = Color.argb((150 * desce).toInt(), 10, 10, 11)
            c.drawRect(r, p)
        }
    }

    private fun topo(c: Canvas) {
        // Perfil: círculo com a inicial, nome e pontos.
        p.color = InicioXbox.VERDE
        c.drawCircle(88 * u, 60 * u, 22 * u, p)
        t.typeface = Ps4Desenho.normal
        t.textSize = 20 * u
        t.color = Color.WHITE
        t.textAlign = Paint.Align.CENTER
        c.drawText(ouvinte.perfil().take(1).uppercase(), 88 * u, 67 * u, t)
        t.textAlign = Paint.Align.LEFT
        t.textSize = 18 * u
        c.drawText(ouvinte.perfil(), 122 * u, 56 * u, t)
        ouvinte.pontos()?.let {
            t.textSize = 14 * u
            t.color = Color.argb(190, 255, 255, 255)
            c.drawText("Ⓖ $it", 122 * u, 76 * u, t)
        }
        t.textSize = 20 * u
        t.color = Color.WHITE
        t.textAlign = Paint.Align.RIGHT
        c.drawText(ouvinte.relogio(), 1216 * u, 66 * u, t)
        t.textAlign = Paint.Align.LEFT
    }

    private fun acessoRapido(c: Canvas) {
        for ((j, it) in rapidos.withIndex()) {
            val x = X0 + j * (RAP + 10f)
            r.set(x * u, Y_RAPIDO * u, (x + RAP) * u, (Y_RAPIDO + RAP) * u)
            val focado = linha == -1 && selRapido == j
            p.color = if (focado) Color.argb(235, 255, 255, 255) else Color.argb(110, 40, 42, 46)
            c.drawRoundRect(r, 6 * u, 6 * u, p)
            val l = RAP * 0.44f * u
            val fundo = if (focado) Color.WHITE else 0xFF2A2C30.toInt()
            if (focado) {
                // Ícone preto no botão branco: desenha o ícone (branco, furos pretos) numa camada com as cores invertidas.
                val camada = c.saveLayer(r, inverter)
                Ps4Desenho.icone(c, it.simbolo ?: Ps4Desenho.Icone.APPS, r.centerX() - l / 2, r.centerY() - l / 2, l, Color.BLACK)
                c.restoreToCount(camada)
            } else Ps4Desenho.icone(c, it.simbolo ?: Ps4Desenho.Icone.APPS, r.centerX() - l / 2, r.centerY() - l / 2, l, fundo)
            if (focado) {
                t.typeface = Ps4Desenho.normal
                t.textSize = 16 * u
                t.color = Color.WHITE
                c.drawText(it.nome, X0 * u, (Y_RAPIDO + RAP + 30) * u, t)
            }
        }
    }

    private fun fileira(c: Canvas, i: Int) {
        val f = fileiras[i]
        val y0 = yLinha(i)
        if (y0 - rolagem > 760 || y0 + GRANDE - rolagem < -40) return
        if (i > 0 || linha > 0) {
            t.typeface = Ps4Desenho.normal
            t.textSize = 19 * u
            t.color = Color.argb(if (linha == i) 255 else 170, 255, 255, 255)
            c.drawText(f.titulo, X0 * u, (y0 - 14) * u, t)
        }
        val selI = sel[i]
        for ((j, it) in f.itens.withIndex()) {
            val l = lado(i, j)
            val x = xItem(i, j) - desloc[i]
            if (x > 1300 || x + l < -20) continue
            // Os menores ficam alinhados por baixo com o grande (na fileira dos recentes).
            val y = if (i == 0) y0 + GRANDE - l else y0
            val focado = linha == i && selI == j
            if (!focado) bloco(c, it, x, y, l, false)
        }
        if (linha == i) f.itens.getOrNull(selI)?.let {
            val l = lado(i, selI)
            val x = xItem(i, selI) - desloc[i]
            val y = if (i == 0) y0 + GRANDE - l else y0
            bloco(c, it, x, y, l, true)
            t.typeface = Ps4Desenho.normal
            t.textSize = 17 * u
            t.color = Color.WHITE
            c.drawText(TextUtils.ellipsize(it.nome, t, max(l, 260f) * u, TextUtils.TruncateAt.END).toString(), x * u, (y + l + 30) * u, t)
        }
    }

    private fun bloco(c: Canvas, it: InicioPs4.Item, x: Float, y: Float, l: Float, focado: Boolean) {
        val esc = if (focado) 1.05f else 1f
        val cx = (x + l / 2) * u
        val cy = (y + l / 2) * u
        val m = l * esc / 2 * u
        r.set(cx - m, cy - m, cx + m, cy + m)
        val rr = 5 * u
        if (focado) {
            p.color = Color.argb(110, 0, 0, 0)
            c.drawRoundRect(r.left + 2 * u, r.top + 8 * u, r.right + 2 * u, r.bottom + 12 * u, rr, rr, p)
        }
        c.save()
        caminho.reset()
        caminho.addRoundRect(r, rr, rr, Path.Direction.CW)
        c.clipPath(caminho)
        val img = it.imagem?.let { url -> imagem(url, (l * 1.2f).toInt(), !it.ehJogo) }
        when {
            img != null -> if (it.ehJogo) Ps4Desenho.cortado(c, img, r) else Ps4Desenho.banner(c, img, r)
            it.simbolo != null -> {
                p.color = 0xFF2B2E33.toInt()
                c.drawRect(r, p)
                val li = r.width() * 0.36f
                Ps4Desenho.icone(c, it.simbolo, r.centerX() - li / 2, r.centerY() - li / 2, li, 0xFF2B2E33.toInt())
            }
            else -> {
                p.color = Paleta.tom(it.cor, 0.6f)
                c.drawRect(r, p)
                it.icone?.let { ic ->
                    val li = r.width() * 0.5f
                    c.drawBitmap(ic, null, RectF(r.centerX() - li / 2, r.centerY() - li / 2, r.centerX() + li / 2, r.centerY() + li / 2), pImg)
                }
            }
        }
        c.restore()
        if (focado) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3.5f * u
            p.color = Color.WHITE
            val d = 4f * u
            c.drawRoundRect(r.left - d, r.top - d, r.right + d, r.bottom + d, rr + d, rr + d, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun imagem(url: String, lado: Int, largo: Boolean = false): Bitmap? {
        imagens[url]?.let { return it }
        if (pedidas.add(url)) {
            val l = (lado * u).toInt().coerceAtLeast(64)
            ouvinte.carregar(url, l, (l * (if (largo) 0.5625f else 0.75f)).toInt().coerceAtLeast(36)) { b -> imagens[url] = b; invalidate() }
        }
        return null
    }

    // ---------- Controle ----------

    private fun ajustarRolagem() {
        rolagemAlvo = if (linha <= 0) 0f else yLinha(linha) - 190f
        if (linha >= 0) {
            val j = sel[linha]
            val x = xItem(linha, j)
            val l = lado(linha, j)
            if (x + l - deslocAlvo[linha] > 1216f) deslocAlvo[linha] = x + l - 1216f
            if (x - deslocAlvo[linha] < X0) deslocAlvo[linha] = max(0f, x - X0)
        }
    }

    private fun focar(novaLinha: Int, novoSel: Int) {
        val antesFundo = itemDoFundo()
        linha = novaLinha
        if (linha == -1) selRapido = novoSel else sel[linha] = novoSel
        if (itemDoFundo() !== antesFundo) {
            fundoAnterior = antesFundo?.let { arte.de(it, it.imagem?.let { url -> imagens[url] }) }
            fundoIni = SystemClock.uptimeMillis()
        }
        ajustarRolagem()
        Sons.tocar(Sons.Evento.MOVER)
        atual()?.let { ouvinte.selecionou(it) }
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        mexeu = true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (linha == -1) { if (selRapido > 0) focar(-1, selRapido - 1) }
                else if (sel[linha] > 0) focar(linha, sel[linha] - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (linha == -1) { if (selRapido < rapidos.lastIndex) focar(-1, selRapido + 1) }
                else if (sel[linha] < fileiras[linha].itens.lastIndex) focar(linha, sel[linha] + 1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                var n = linha - 1
                while (n >= 0 && fileiras[n].itens.isEmpty()) n--
                if (n >= 0) focar(n, sel[n]) else if (linha >= 0 && rapidos.isNotEmpty()) focar(-1, selRapido)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                var n = linha + 1
                while (n < fileiras.size && fileiras[n].itens.isEmpty()) n++
                if (n < fileiras.size) focar(n, min(sel[n], fileiras[n].itens.lastIndex))
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) atual()?.abrir?.invoke()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    override fun informacoes() { atual()?.info?.invoke() }

    override fun voltar(): Boolean {
        if (linha != 0 || sel.getOrElse(0) { 0 } != 0) {
            if (fileiras.isNotEmpty()) {
                deslocAlvo.fill(0f)
                focar(0, 0)
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        atual()?.abrir?.invoke()
        return true
    }

    override fun performLongClick(): Boolean {
        atual()?.opcoes?.invoke()
        return true
    }

    fun atualizar() = invalidate()

    class Estado(val linha: Int, val nome: String?, val selRapido: Int)

    /** Só depois de a pessoa mexer: a tela montada antes de os jogos carregarem não vale como posição. */
    private var mexeu = false

    fun estado(): Estado? = if (!mexeu) null else Estado(linha, atual()?.nome, selRapido)

    fun restaurar(e: Estado) {
        mexeu = true
        if (e.linha == -1) { linha = -1; selRapido = e.selRapido.coerceIn(0, max(0, rapidos.lastIndex)) }
        else if (e.linha in fileiras.indices) {
            linha = e.linha
            fileiras[linha].itens.indexOfFirst { it.nome == e.nome }.takeIf { it >= 0 }?.let { sel[linha] = it }
        }
        ajustarRolagem()
        rolagem = rolagemAlvo
        for (i in desloc.indices) desloc[i] = deslocAlvo[i]
        invalidate()
    }

    // ---------- Toque ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        mexeu = true
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x / u
        val y = e.y / u + rolagem
        for (j in rapidos.indices) {
            val rx = X0 + j * (RAP + 10f)
            if (x in rx..(rx + RAP) && y in Y_RAPIDO..(Y_RAPIDO + RAP)) {
                if (linha == -1 && selRapido == j) rapidos[j].abrir() else focar(-1, j)
                return true
            }
        }
        for (i in fileiras.indices) for (j in fileiras[i].itens.indices) {
            val l = lado(i, j)
            val bx = xItem(i, j) - desloc[i]
            val by = if (i == 0) yLinha(i) + GRANDE - l else yLinha(i)
            if (x in bx..(bx + l) && y in by..(by + l)) {
                if (linha == i && sel[i] == j) fileiras[i].itens[j].abrir() else focar(i, j)
                return true
            }
        }
        return true
    }
}

/** Fundo das telas do estilo Xbox Series (configurações, grades): cinza quase preto com um brilho verde discreto. */
class FundoSeries(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF1C1D1F.toInt(), 0xFF111213.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.RadialGradient(w * 0.1f, h * 1.05f, w * 0.55f, intArrayOf(0x22107C10, 0x00107C10), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }
}
