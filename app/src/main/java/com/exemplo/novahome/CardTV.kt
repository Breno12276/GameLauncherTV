package com.exemplo.novahome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.max

/**
 * Card no estilo do Android TV.
 * - Banner (Apps favoritos): fundo na cor do app, ícone e nome.
 * - Conteúdo (fileiras grandes): imagem do TMDB, ou degradê na cor do app com ícone grande.
 *   No foco cresce bem mais (1,25×) e mostra título maior e sinopse por cima da imagem.
 */
class CardTV(
    ctx: Context, private val conteudo: Boolean, private val raio: Float, private val ajuste: Ajuste = Ajuste.CORTAR,
) : View(ctx) {

    /** Como a imagem entra no card: cortando as sobras, inteira (sobra fica na cor do app) ou esticada. */
    enum class Ajuste { CORTAR, INTEIRA, ESTICAR }

    var aoMudarFoco: ((Boolean) -> Unit)? = null
    /** Tamanho próprio (capa em pé de cada plataforma); null = o tamanho padrão da fileira. */
    var tamanho: android.util.Size? = null
    private val destinoImagem = RectF()

    private val d = resources.displayMetrics.density
    private val r = RectF()
    private val recorte = Path()
    private var nome = ""
    private var detalhe = ""
    private var icone: Bitmap? = null
    private var imagem: Bitmap? = null
    private var cor = Paleta.AZUL

    private val pFundo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVeu = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pVeuFoco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImagem = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pIcone = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pDetalhe = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255) }
    private val areaIcone = RectF()
    private val origemImagem = Rect()
    private var texto: CharSequence = ""
    private var textoX = 0f
    private var textoY = 0f
    private var textoYFoco = 0f
    private var sinopse: StaticLayout? = null
    private var sinopseY = 0f

    /** 0 = normal, 1 = em foco (anima título e sinopse). */
    private var progresso = 0f
    private var anim: ValueAnimator? = null
    private val escalaFoco = if (conteudo) 1.25f else 1.12f

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        defaultFocusHighlightEnabled = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, raio)
        }
        // Sem camada fixa: com ~200 cards, uma camada de GPU por card estourava a memória gráfica
        // (190 MB, 100% dos quadros atrasados). A camada só existe durante a animação de foco (withLayer).
    }

    fun definir(nome: String, icone: Bitmap?, cor: Int, detalhe: String = "") {
        this.nome = nome
        this.icone = icone
        this.cor = cor
        this.detalhe = detalhe
        contentDescription = nome
        if (width > 0) preparar()
        invalidate()
    }

    fun definirImagem(b: Bitmap) {
        imagem = b
        if (width > 0) preparar()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        r.set(0f, 0f, w.toFloat(), h.toFloat())
        recorte.reset()
        recorte.addRoundRect(r, raio, raio, Path.Direction.CW)
        preparar()
    }

    private fun preparar() {
        val w = r.width()
        val h = r.height()
        if (w <= 0f || h <= 0f) return
        if (conteudo) {
            pFundo.shader = LinearGradient(0f, 0f, w, h, Paleta.tom(cor, 0.52f), Paleta.tom(cor, 0.14f), Shader.TileMode.CLAMP)
            pVeu.shader = LinearGradient(0f, h * 0.45f, 0f, h, Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP)
            pVeuFoco.shader = LinearGradient(0f, h * 0.15f, 0f, h, Color.TRANSPARENT, Color.argb(225, 0, 0, 0), Shader.TileMode.CLAMP)
            // Texto e ícone pela altura de um card deitado desta largura: em cards em pé (capas) não ficam enormes.
            val base = if (h > w) w * 0.8f else minOf(h, w * 0.5625f)
            val lado = minOf(h * 0.42f, w * 0.6f)
            areaIcone.set(w / 2f - lado / 2f, h * 0.40f - lado / 2f, w / 2f + lado / 2f, h * 0.40f + lado / 2f)
            pTexto.textSize = base * 0.11f
            pTexto.typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
            pTexto.textAlign = Paint.Align.LEFT
            texto = TextUtils.ellipsize(nome, pTexto, w * 0.9f, TextUtils.TruncateAt.END)
            textoX = w * 0.05f
            textoY = h - h * 0.08f
            pDetalhe.textSize = base * 0.075f
            sinopse = if (detalhe.isBlank()) null else StaticLayout.Builder
                .obtain(detalhe, 0, detalhe.length, pDetalhe, (w * 0.9f).toInt())
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val altSinopse = sinopse?.height ?: 0
            sinopseY = h - h * 0.07f - altSinopse
            textoYFoco = sinopseY - h * 0.05f
        } else {
            pFundo.shader = LinearGradient(0f, 0f, 0f, h, Paleta.tom(cor, 0.80f), Paleta.tom(cor, 0.60f), Shader.TileMode.CLAMP)
            val lado = h * 0.50f
            val vao = h * 0.08f
            pTexto.textSize = h * 0.175f
            pTexto.typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
            pTexto.textAlign = Paint.Align.LEFT
            texto = TextUtils.ellipsize(nome, pTexto, max(0f, w * 0.92f - lado - vao), TextUtils.TruncateAt.END)
            val inicio = w / 2f - (lado + vao + pTexto.measureText(texto, 0, texto.length)) / 2f
            areaIcone.set(inicio, h / 2f - lado / 2f, inicio + lado, h / 2f + lado / 2f)
            textoX = inicio + lado + vao
            textoY = h / 2f - (pTexto.ascent() + pTexto.descent()) / 2f
        }
        imagem?.let { b ->
            when (ajuste) {
                Ajuste.CORTAR -> {
                    // Recorte central na proporção do card.
                    val escala = max(w / b.width, h / b.height)
                    val cw = (w / escala).toInt()
                    val ch = (h / escala).toInt()
                    origemImagem.set((b.width - cw) / 2, (b.height - ch) / 2, (b.width + cw) / 2, (b.height + ch) / 2)
                    destinoImagem.set(r)
                }
                Ajuste.INTEIRA -> {
                    // A imagem toda, centralizada; o que sobra fica no degradê da cor do app.
                    val escala = minOf(w / b.width, h / b.height)
                    val iw = b.width * escala
                    val ih = b.height * escala
                    origemImagem.set(0, 0, b.width, b.height)
                    destinoImagem.set((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f)
                }
                Ajuste.ESTICAR -> {
                    origemImagem.set(0, 0, b.width, b.height)
                    destinoImagem.set(r)
                }
            }
        }
        pTexto.setShadowLayer(3f * d, 0f, d, Color.argb(140, 0, 0, 0))
    }

    override fun onDraw(c: Canvas) {
        if (!conteudo) {
            val img = imagem
            if (img != null) {
                // Banner com arte: só a imagem, como os banners da TV (o nome aparece embaixo no foco).
                c.save()
                c.clipPath(recorte)
                if (ajuste == Ajuste.INTEIRA) c.drawRect(r, pFundo)
                c.drawBitmap(img, origemImagem, destinoImagem, pImagem)
                c.restore()
                return
            }
            c.drawRoundRect(r, raio, raio, pFundo)
            icone?.let { c.drawBitmap(it, null, areaIcone, pIcone) }
            c.drawText(texto, 0, texto.length, textoX, textoY, pTexto)
            return
        }
        val img = imagem
        if (img != null) {
            c.save()
            c.clipPath(recorte)
            if (ajuste == Ajuste.INTEIRA) c.drawRect(r, pFundo)
            c.drawBitmap(img, origemImagem, destinoImagem, pImagem)
            c.restore()
        } else {
            c.drawRoundRect(r, raio, raio, pFundo)
            icone?.let {
                pIcone.alpha = (255 * (1f - 0.6f * progresso)).toInt()
                c.drawBitmap(it, null, areaIcone, pIcone)
            }
        }
        c.drawRoundRect(r, raio, raio, pVeu)
        if (progresso > 0f) {
            pVeuFoco.alpha = (255 * progresso).toInt()
            c.drawRoundRect(r, raio, raio, pVeuFoco)
        }
        // Título sobe e a sinopse aparece conforme o foco entra.
        val y = textoY + (textoYFoco - textoY) * if (sinopse != null) progresso else 0f
        c.drawText(texto, 0, texto.length, textoX, y, pTexto)
        val s = sinopse
        if (s != null && progresso > 0f) {
            pDetalhe.alpha = (225 * progresso).toInt()
            c.save()
            c.translate(textoX, sinopseY)
            s.draw(c)
            c.restore()
        }
    }

    override fun onFocusChanged(foco: Boolean, direcao: Int, anterior: Rect?) {
        super.onFocusChanged(foco, direcao, anterior)
        realcar(foco, 200L)
        if (conteudo) animarProgresso(if (foco) 1f else 0f)
        aoMudarFoco?.invoke(foco)
    }

    override fun setPressed(pressionado: Boolean) {
        val antes = isPressed
        super.setPressed(pressionado)
        // Banners crescem por um instante no toque; os cards grandes crescem no foco (1º toque).
        if (!conteudo && pressionado != antes && !isFocused) realcar(pressionado, if (pressionado) 90L else 260L)
    }

    private fun realcar(sim: Boolean, duracao: Long) {
        val e = if (sim) escalaFoco else 1f
        // Só escala, sem sombra animada: a sombra recalculada a cada quadro atrasava 20% dos quadros.
        animate().scaleX(e).scaleY(e).setDuration(duracao).withLayer().start()
        // O item sobe por cima dos vizinhos enquanto cresce.
        (parent as? View)?.translationZ = if (sim) 1f else 0f
    }

    private fun animarProgresso(alvo: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progresso, alvo).apply {
            duration = 200
            addUpdateListener {
                progresso = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }
}
