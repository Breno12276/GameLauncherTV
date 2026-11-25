package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View

/**
 * Visual das telas comuns (configurações, menu rápido, opções, informações do jogo) em cada estilo de console.
 * [Estilo.visual] devolve o do estilo escolhido (null = o padrão do Android TV).
 */
class VisualConsole(
    val estilo: Int,
    val fundo: (Context) -> View,
    val corTitulo: Int,
    val corTexto: Int,
    val corSub: Int,
    /** Cor do texto da linha em foco (null = a mesma). */
    val corTextoFocado: Int?,
    val corLinha: Int,
    val selecao: (Float) -> Drawable,
    val dicas: ((Context) -> View)?,
    val minusculo: Boolean = false,
    val icones: Boolean = false,
    val caixas: Boolean = false,
    val tituloTamanho: Float = 42f,
    /** Fundo do painel lateral (menu rápido / opções): cores de cima e de baixo. */
    val painel: IntArray,
    /** Cor do texto no painel lateral. */
    val textoPainel: Int,
    val subPainel: Int,
    /** Seleção no painel lateral. */
    val selecaoPainel: (Float) -> Drawable,
    val textoPainelFocado: Int?,
)

object Visuais {
    fun de(ctx: Context): VisualConsole? = when (ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE).getInt("layout_inicio", 0)) {
        1 -> VisualConsole(1, { FundoPs4(it) }, Color.WHITE, Color.WHITE, Color.argb(185, 255, 255, 255), null, Color.argb(70, 255, 255, 255),
            { SelecaoPs4(it, linhaEmbaixo = true) },
            { c -> DicasPs4(c, listOf('X' to "Entrar", 'O' to "Voltar"), Perfis.atual(c).nome) },
            icones = true, caixas = true,
            painel = intArrayOf(0xF2163A80.toInt(), 0xF20A1B42.toInt()), textoPainel = Color.WHITE, subPainel = Color.argb(185, 255, 255, 255),
            selecaoPainel = { SelecaoPs4(it, linhaEmbaixo = true) }, textoPainelFocado = null)
        2 -> VisualConsole(2, { FundoXbox(it) }, InicioXbox.CINZA_TEXTO, InicioXbox.CINZA_TEXTO, InicioXbox.CINZA_SUAVE, Color.WHITE,
            Color.argb(50, 0, 0, 0), { SelecaoXbox(it) }, { c -> DicasXbox(c, listOf('A' to "Selecionar", 'B' to "Voltar")) },
            minusculo = true, tituloTamanho = 46f,
            painel = intArrayOf(0xF7F4F5F6.toInt(), 0xF7DCDFE2.toInt()), textoPainel = InicioXbox.CINZA_TEXTO, subPainel = InicioXbox.CINZA_SUAVE,
            selecaoPainel = { SelecaoXbox(it) }, textoPainelFocado = Color.WHITE)
        3 -> VisualConsole(3, { FundoSwitch(it) }, InicioSwitch.TEXTO, InicioSwitch.TEXTO, InicioSwitch.SUAVE, null,
            Color.argb(45, 0, 0, 0), { SelecaoSwitch(it) }, { c -> DicasSwitch(c) }, tituloTamanho = 34f,
            painel = intArrayOf(0xF5F2F2F2.toInt(), 0xF5E4E4E4.toInt()), textoPainel = InicioSwitch.TEXTO, subPainel = InicioSwitch.SUAVE,
            selecaoPainel = { SelecaoSwitch(it) }, textoPainelFocado = null)
        4 -> VisualConsole(4, { FundoWii(it) }, InicioWii.CINZA, InicioWii.TEXTO, InicioWii.CINZA, null,
            Color.argb(40, 0, 0, 0), { SelecaoWii(it) }, null, tituloTamanho = 40f,
            painel = intArrayOf(0xF7FBFCFD.toInt(), 0xF7E3E8EC.toInt()), textoPainel = InicioWii.TEXTO, subPainel = InicioWii.CINZA,
            selecaoPainel = { SelecaoWii(it) }, textoPainelFocado = null)
        5 -> VisualConsole(5, { FundoPs5(it) }, Color.WHITE, Color.WHITE, Color.argb(170, 255, 255, 255), null, Color.argb(40, 255, 255, 255),
            { SelecaoModerna(it, 12f) }, { c -> DicasPs4(c, listOf('X' to "Entrar", 'O' to "Voltar"), Perfis.atual(c).nome) },
            icones = true, tituloTamanho = 38f,
            painel = intArrayOf(0xF51C1E24.toInt(), 0xF50D0E12.toInt()), textoPainel = Color.WHITE, subPainel = Color.argb(170, 255, 255, 255),
            selecaoPainel = { SelecaoModerna(it, 12f) }, textoPainelFocado = null)
        6 -> VisualConsole(6, { FundoSeries(it) }, Color.WHITE, Color.WHITE, Color.argb(165, 255, 255, 255), null, Color.argb(40, 255, 255, 255),
            { SelecaoModerna(it, 4f) }, { c -> DicasXbox(c, listOf('A' to "Selecionar", 'B' to "Voltar"), Color.WHITE) },
            tituloTamanho = 36f,
            painel = intArrayOf(0xF726282B.toInt(), 0xF7161718.toInt()), textoPainel = Color.WHITE, subPainel = Color.argb(165, 255, 255, 255),
            selecaoPainel = { SelecaoModerna(it, 4f) }, textoPainelFocado = null)
        else -> null
    }

    /** Painel lateral escuro (menu rápido, botões da tela de informações) dos estilos 3 a 6: cores de cima/baixo e a da borda do foco. */
    fun painelEscuro(estilo: Int): Triple<Int, Int, Int>? = when (estilo) {
        3 -> Triple(0xFF3A3A3A.toInt(), 0xFF1E1E1E.toInt(), InicioSwitch.CIANO)
        4 -> Triple(0xFF2A6FA8.toInt(), 0xFF173E66.toInt(), InicioWii.AZUL)
        5 -> Triple(0xFF22252C.toInt(), 0xFF0E1014.toInt(), Color.WHITE)
        6 -> Triple(0xFF2A2C2F.toInt(), 0xFF151617.toInt(), Color.WHITE)
        else -> null
    }
}

/** Fundo cinza-claro liso do estilo Switch. */
class FundoSwitch(ctx: Context) : View(ctx) {
    override fun onDraw(c: Canvas) = c.drawColor(InicioSwitch.FUNDO)
}

/** Fundo claro com listras do estilo Wii. */
class FundoWii(ctx: Context) : View(ctx) {
    private val p = Paint()
    override fun onDraw(c: Canvas) = InicioWii.fundo(c, width.toFloat(), height.toFloat(), p)
}

/** Seleção do estilo Switch: fundo branco e a borda azul-clara (com o vão branco), linha fina fora do foco. */
class SelecaoSwitch(private val u: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        if (focado) {
            p.color = Color.WHITE
            c.drawRect(b, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4 * u
            p.color = InicioSwitch.CIANO
            c.drawRect(b.left + 2 * u, b.top + 2 * u, b.right - 2 * u, b.bottom - 2 * u, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = Color.argb(40, 0, 0, 0)
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

/** Seleção do estilo Wii: botão arredondado branco com borda cinza; azul-clara e mais grossa no foco. */
class SelecaoWii(private val u: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        val raio = minOf(b.height() / 2f, 26 * u)
        val m = 3 * u
        p.color = if (focado) Color.WHITE else Color.argb(220, 255, 255, 255)
        c.drawRoundRect(b.left + m, b.top + m, b.right - m, b.bottom - m, raio, raio, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (focado) 4.5f else 2f) * u
        p.color = if (focado) InicioWii.AZUL else 0xFFC3CBD2.toInt()
        c.drawRoundRect(b.left + m, b.top + m, b.right - m, b.bottom - m, raio, raio, p)
        p.style = Paint.Style.FILL
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

/** Rodapé do estilo Switch: linha fina e "Ⓑ Voltar  Ⓐ OK" à direita, com os botões em círculos pretos. */
class DicasSwitch(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val u = width / 1280f
        p.color = 0xFFC8C8C8.toInt()
        c.drawRect(60 * u, 0f, 1220 * u, 1.5f * u, p)
        val cy = height / 2f
        var x = 1210f * u
        t.typeface = InicioSwitch.normal
        for ((letra, texto) in listOf('B' to "Voltar", 'A' to "OK").reversed()) {
            t.textSize = 18 * u
            t.color = InicioSwitch.TEXTO
            t.textAlign = Paint.Align.RIGHT
            c.drawText(texto, x, cy + 6 * u, t)
            x -= t.measureText(texto) + 8 * u
            p.color = InicioSwitch.TEXTO
            c.drawCircle(x - 11 * u, cy, 11 * u, p)
            t.color = Color.WHITE
            t.textSize = 13 * u
            t.textAlign = Paint.Align.CENTER
            c.drawText(letra.toString(), x - 11 * u, cy + 4.5f * u, t)
            x -= 50 * u
        }
        t.textAlign = Paint.Align.LEFT
    }
}

/** Seleção com só uma borda colorida no foco (fundo um pouco mais claro), linha fina fora dele (painéis escuros). */
class SelecaoBorda(private val u: Float, private val cor: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var focado = false
    override fun draw(c: Canvas) {
        val b = bounds
        if (focado) {
            p.color = Color.argb(40, 255, 255, 255)
            c.drawRect(b, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4 * u
            p.color = cor
            c.drawRect(b.left + 2 * u, b.top + 2 * u, b.right - 2 * u, b.bottom - 2 * u, p)
            p.style = Paint.Style.FILL
        } else {
            p.color = Color.argb(40, 255, 255, 255)
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
