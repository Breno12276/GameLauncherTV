package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.min

/**
 * Imagem de fundo trocada com esmaecimento, sob um véu azul-marinho para os textos continuarem legíveis.
 * - [Veu.JOGO]: arte do jogo selecionado na aba Jogos (véu forte à esquerda e embaixo).
 * - [Veu.APP]: paisagem ou foto atrás de todas as abas (véu por igual, mais forte no alto e embaixo).
 * - [Veu.DESCANSO]: descanso de tela; só escurece embaixo (relógio e crédito) e a foto se aproxima devagar.
 */
class FundoImagem(ctx: Context, private val veu: Veu) : android.widget.FrameLayout(ctx) {

    enum class Veu { JOGO, APP, DESCANSO }

    private val camadas = Array(2) {
        android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
    }
    private var frente = 0
    private var urlAtual: String? = null

    init {
        camadas.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        fun degrade(o: android.graphics.drawable.GradientDrawable.Orientation, vararg alfas: Int) =
            android.graphics.drawable.GradientDrawable(o, alfas.map { Paleta.alfa(Paleta.FUNDO, it) }.toIntArray())
        val E = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
        val B = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
        val camadasVeu = when (veu) {
            Veu.JOGO -> arrayOf(degrade(E, 235, 150, 80), degrade(B, 90, 20, 235))
            Veu.APP -> arrayOf(degrade(B, 200, 120, 150, 220))
            Veu.DESCANSO -> arrayOf(degrade(B, 0, 0, 0, 170))
        }
        addView(View(ctx).apply { background = android.graphics.drawable.LayerDrawable(camadasVeu) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun mostrar(imagem: Bitmap, url: String, duracao: Long = 450) {
        if (url == urlAtual) return
        urlAtual = url
        val nova = camadas[1 - frente]
        val velha = camadas[frente]
        nova.setImageBitmap(imagem)
        nova.animate().cancel()
        aproximacao?.cancel()
        nova.scaleX = 1f
        nova.scaleY = 1f
        nova.animate().alpha(1f).setDuration(duracao).start()
        velha.animate().alpha(0f).setDuration(duracao).start()
        // Descanso: aproximação lenta (efeito "Ken Burns"), como no protetor de tela do Android TV.
        if (veu == Veu.DESCANSO) {
            aproximacao = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                nova,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            ).apply {
                duration = INTERVALO_DESCANSO + duracao
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        }
        frente = 1 - frente
        if (animado && veu == Veu.APP) mover()
    }

    private var aproximacao: android.animation.Animator? = null
    private var movimento: android.animation.Animator? = null
    private var animado = false

    /** Fundo animado: a imagem passeia devagar de um lado para o outro, com um leve zoom (ida e volta, 40 s). */
    fun animar(sim: Boolean) {
        animado = sim
        if (sim) mover() else {
            movimento?.cancel()
            camadas.forEach { it.translationX = 0f; it.scaleX = 1f; it.scaleY = 1f }
        }
    }

    private fun mover() {
        movimento?.cancel()
        val v = camadas[frente]
        val d = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 0.035f
        movimento = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            v,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.1f, 1.16f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.1f, 1.16f),
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -d, d),
        ).apply {
            duration = 40_000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Fora da tela a animação para; na volta, continua. */
    fun pausarMovimento(pausar: Boolean) {
        val m = movimento ?: return
        if (pausar) m.pause() else m.resume()
    }

    /** Volta ao fundo liso (sem imagem). */
    fun limpar() {
        urlAtual = null
        aproximacao?.cancel()
        camadas.forEach { it.animate().cancel(); it.alpha = 0f; it.setImageDrawable(null) }
    }

    fun pararAnimacoes() {
        aproximacao?.cancel()
        camadas.forEach { it.animate().cancel() }
    }

    companion object {
        const val INTERVALO_DESCANSO = 15_000L
    }
}

/**
 * Trailer de fundo (tela inteira, em loop), atrás das fileiras e sob um véu escuro. Com som (se ligado),
 * o volume começa em 50% e sobe aos poucos até 100%.
 * Usa TextureView (e não VideoView/SurfaceView) para poder esmaecer e cortar como uma imagem comum.
 */
class FundoVideo(ctx: Context) : android.widget.FrameLayout(ctx), android.view.TextureView.SurfaceTextureListener {

    private val tela = android.view.TextureView(ctx)
    private var mp: android.media.MediaPlayer? = null
    private var superficie: android.view.Surface? = null
    /** Arquivo (caminho) ou endereço do vídeo esperando a superfície ficar pronta. */
    private var pendente: String? = null
    private var geracao = 0
    private val veu = View(ctx)

    init {
        alpha = 0f
        addView(tela, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        veu.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(Paleta.alfa(Paleta.FUNDO, 95)),
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Paleta.alfa(Paleta.FUNDO, 170), Paleta.alfa(Paleta.FUNDO, 30), Paleta.alfa(Paleta.FUNDO, 220))),
        ))
        addView(veu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tela.surfaceTextureListener = this
    }

    val tocando get() = mp != null || youtubeAtivo

    /** Modo imersivo: o véu some e o trailer aparece limpo; fora dele, volta a escurecer atrás da interface. */
    fun imersivo(ligado: Boolean) {
        veu.animate().alpha(if (ligado) 0f else 1f).setDuration(if (ligado) 800 else 400).start()
    }

    /** Vídeo da Steam [f]; [audio] (opcional) é o som em arquivo separado, tocado junto. */
    fun tocar(f: java.io.File, audio: java.io.File? = null) {
        geracao++
        pararYoutube()
        tela.visibility = VISIBLE
        pendente = f.path
        somProprio = false
        pendenteAudio = audio
        superficie?.let { iniciar(f.path, it) }
    }

    /** Vídeo direto da internet (MP4 com som, como os trailers da Apple), tocado sem baixar antes. */
    fun tocarUrl(url: String) {
        geracao++
        pararYoutube()
        tela.visibility = VISIBLE
        pendente = url
        somProprio = true
        pendenteAudio = null
        superficie?.let { iniciar(url, it) }
    }

    /** O próprio vídeo traz o som (MP4 da Apple); os da Steam vêm mudos, com o som em [pendenteAudio]. */
    private var somProprio = false

    private var pendenteAudio: java.io.File? = null
    private var audioMp: android.media.MediaPlayer? = null
    private var subida: android.animation.Animator? = null

    fun parar() {
        pendente = null
        val minha = ++geracao
        animate().alpha(0f).setDuration(300).withEndAction {
            if (minha == geracao) {
                soltar()
                pararYoutube()
            }
        }.start()
    }

    // ---------- Trailer do YouTube (exceção autorizada: WebView só para o player oficial de incorporação) ----------

    private var web: android.webkit.WebView? = null
    private var youtubeAtivo = false
    private var aoFalharYoutube: (() -> Unit)? = null

    /** Ponte JS → app: o player avisa quando começou a tocar ou deu erro (vídeo que não permite incorporar). */
    private inner class Ponte {
        @android.webkit.JavascriptInterface
        fun tocando(g: Int) = post {
            if (g == geracao && youtubeAtivo) {
                // Espera o título do YouTube sumir antes de mostrar (o véu escurece o resto).
                postDelayed({ if (g == geracao) animate().alpha(1f).setDuration(700).start() }, 1200)
            }
        }

        @android.webkit.JavascriptInterface
        fun erro(g: Int) = post {
            if (g == geracao) {
                pararYoutube()
                aoFalharYoutube?.invoke()
            }
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun criarWeb(): android.webkit.WebView {
        val w = android.webkit.WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            addJavascriptInterface(Ponte(), "Nova")
            visibility = GONE
        }
        // Fica entre o vídeo da Steam e o véu.
        addView(w, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        web = w
        return w
    }

    /**
     * Toca o trailer do YouTube [id] em loop (som conforme a configuração), cobrindo a tela; [aoFalhar] se o vídeo não puder tocar.
     * [zoom] > 1 corta as tarjas pretas dos trailers de cinema (2.39:1 dentro de um vídeo 16:9).
     */
    fun tocarYoutube(id: String, zoom: Float = 1f, aoFalhar: () -> Unit) {
        val comSom = SomTrailer.ligado(context)
        geracao++
        soltar()
        pendente = null
        tela.visibility = INVISIBLE
        youtubeAtivo = true
        aoFalharYoutube = aoFalhar
        val w = web ?: criarWeb()
        w.visibility = VISIBLE
        val seguro = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            #p{position:absolute;top:50%;left:50%;width:100vw;height:56.25vw;min-height:100vh;min-width:177.78vh;
               transform:translate(-50%,-50%) scale($zoom);pointer-events:none;border:0}</style></head>
            <body><div id="p"></div>
            <script>
              var g=$geracao;
              function onYouTubeIframeAPIReady(){
                new YT.Player('p',{videoId:'$seguro',
                  playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,
                    playsinline:1,rel:0,loop:1,playlist:'$seguro',origin:'https://com.gamelaunchertv.app'},
                  events:{onReady:function(e){
                      var p=e.target; p.playVideo();
                      // Som: começa em 50% e sobe 5% a cada 0,6 s até 100% (~6 s).
                      if($comSom){ p.unMute(); var v=50; p.setVolume(v);
                        var t=setInterval(function(){v+=5; if(v>=100){v=100;clearInterval(t);} p.setVolume(v);},600); }
                      else { p.mute(); } },
                    onStateChange:function(e){if(e.data==1)Nova.tocando(g);},
                    onError:function(e){Nova.erro(g);}}});
              }
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
            </body></html>
        """.trimIndent()
        w.loadDataWithBaseURL("https://com.gamelaunchertv.app/", html, "text/html", "utf-8", null)
    }

    private fun pararYoutube() {
        if (!youtubeAtivo) return
        youtubeAtivo = false
        web?.loadUrl("about:blank")
        web?.visibility = GONE
    }

    override fun onDetachedFromWindow() {
        web?.destroy()
        web = null
        super.onDetachedFromWindow()
    }

    private fun iniciar(origem: String, s: android.view.Surface) {
        soltar()
        val minha = geracao
        prepararAudio(minha)
        mp = try {
            android.media.MediaPlayer().apply {
                setDataSource(origem)
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { p ->
                    if (minha != geracao) return@setOnPreparedListener
                    cobrir(p.videoWidth, p.videoHeight)
                    videoPronto = true
                    comecarJuntos(minha)
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Corta o vídeo para cobrir a tela toda sem deformar (como CENTER_CROP). */
    private fun cobrir(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || width == 0 || height == 0) return
        val escala = maxOf(width.toFloat() / vw, height.toFloat() / vh)
        val m = android.graphics.Matrix()
        m.setScale(vw * escala / width, vh * escala / height, width / 2f, height / 2f)
        tela.setTransform(m)
    }

    private var videoPronto = false
    private var audioPronto = false

    /** Áudio da Steam: prepara junto com o vídeo; os dois começam ao mesmo tempo. */
    private fun prepararAudio(minha: Int) {
        val a = pendenteAudio?.takeIf { SomTrailer.ligado(context) } ?: return
        audioMp = SomTrailer.tocadorDeAudio(a) {
            if (minha != geracao) return@tocadorDeAudio
            audioPronto = true
            comecarJuntos(minha)
        }
    }

    private fun comecarJuntos(minha: Int) {
        if (minha != geracao || !videoPronto) return
        val a = audioMp
        if (a != null && !audioPronto) return // espera o áudio (sem áudio, começa já)
        mp?.start()
        this@FundoVideo.animate().alpha(1f).setDuration(700).start()
        val v = mp
        if (somProprio && v != null && SomTrailer.ligado(context)) {
            subida?.cancel()
            subida = SomTrailer.subir { x -> try { v.setVolume(x, x) } catch (_: Exception) {} }
        }
        if (a != null) {
            a.start()
            subida?.cancel()
            subida = SomTrailer.subir { v -> try { a.setVolume(v, v) } catch (_: Exception) {} }
        }
    }

    private fun soltar() {
        subida?.cancel()
        subida = null
        try { mp?.release() } catch (_: Exception) {}
        try { audioMp?.release() } catch (_: Exception) {}
        mp = null
        audioMp = null
        videoPronto = false
        audioPronto = false
    }

    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
        superficie = android.view.Surface(st)
        pendente?.let { iniciar(it, superficie!!) }
    }

    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        soltar()
        superficie?.release()
        superficie = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
}

/** Fundo azul-marinho liso do Android TV Home. */
class FundoTV(ctx: Context) : View(ctx) {
    override fun isOpaque() = true
    override fun onDraw(c: Canvas) = c.drawColor(Paleta.FUNDO)
}

/**
 * Arte do destaque no alto da tela inicial. Sem catálogo de filmes, usa a cor e o ícone
 * do app em destaque; some para o azul-marinho à esquerda e embaixo, como a imagem da TV.
 */
class HeroArte(ctx: Context) : View(ctx) {

    private var icone: Bitmap? = null
    private var imagem: Bitmap? = null
    private var cor = Paleta.AZUL
    private val pImagem = Paint(Paint.FILTER_BITMAP_FLAG)
    private val areaImagem = RectF()
    private val arte = Paint()
    private val brilho = Paint()
    private val someEsquerda = Paint()
    private val someBaixo = Paint()
    private val pIcone = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val areaIcone = RectF()
    private var x0 = 0f

    init {
        // A troca de destaque é um esmaecimento: a GPU só muda a transparência da imagem pronta.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun definir(icone: Bitmap?, cor: Int, imagem: Bitmap? = null) {
        this.icone = icone
        this.cor = cor
        this.imagem = imagem
        preparar()
        invalidate()
    }

    /** Tamanho certo da imagem do destaque (o pedaço da direita, onde ela aparece). */
    fun tamanhoImagem(): Pair<Int, Int> = (width - (width * 0.34f).toInt()) to height

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = preparar()

    private fun preparar() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        x0 = w * 0.34f
        val larg = w - x0
        arte.shader = LinearGradient(x0, 0f, w, h, Paleta.tom(cor, 0.50f), Paleta.tom(cor, 0.16f), Shader.TileMode.CLAMP)
        val cx = x0 + larg * 0.56f
        val cy = h * 0.40f
        brilho.shader = RadialGradient(cx, cy, larg * 0.55f,
            Paleta.alfa(Paleta.tom(cor, 0.95f), 120), Paleta.alfa(cor, 0), Shader.TileMode.CLAMP)
        // Máscaras (DST_IN): a arte some até ficar transparente à esquerda e embaixo, em vez de pintar
        // azul-marinho por cima — assim ela se mistura com qualquer fundo (paisagem, foto ou azul).
        someEsquerda.shader = LinearGradient(x0, 0f, x0 + larg * 0.45f, 0f,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        someEsquerda.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        someBaixo.shader = LinearGradient(0f, h * 0.5f, 0f, h,
            Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        someBaixo.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        val lado = h * 0.36f
        areaIcone.set(cx - lado / 2f, cy - lado / 2f, cx + lado / 2f, cy + lado / 2f)
        areaImagem.set(x0, 0f, w, h)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val img = imagem
        if (img != null) {
            c.drawBitmap(img, null, areaImagem, pImagem)
        } else {
            c.drawRect(x0, 0f, w, h, arte)
            c.drawRect(x0, 0f, w, h, brilho)
            icone?.let { c.drawBitmap(it, null, areaIcone, pIcone) }
        }
        c.drawRect(x0, 0f, w, h, someEsquerda)
        c.drawRect(x0, 0f, w, h, someBaixo)
    }
}

/** Marcador de páginas do destaque: um traço para o atual, pontos para os outros. */
class Indicador(ctx: Context) : View(ctx) {

    var total = 0
        set(v) { field = v; invalidate() }
    var atual = 0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (total <= 1) return
        val h = height.toFloat()
        val ponto = h * 0.5f
        val traco = h * 3.2f
        val vao = h * 0.7f
        var x = width - (traco + (total - 1) * (ponto + vao))
        val y = h / 2f
        for (i in 0 until total) {
            p.color = if (i == atual) Color.WHITE else Color.argb(140, 255, 255, 255)
            val l = if (i == atual) traco else ponto
            r.set(x, y - ponto / 2f, x + l, y + ponto / 2f)
            c.drawRoundRect(r, ponto / 2f, ponto / 2f, p)
            x += l + vao
        }
    }
}

/** Aba do topo (Início, Descobrir, Apps): sublinhada quando ativa, pílula branca no foco do controle. */
class AbaView(ctx: Context, rotulo: String, tamanho: Float, private val s: Float) : TextView(ctx) {

    var ativa = false
        set(v) { field = v; atualizarCor(); invalidate() }

    private val d = resources.displayMetrics.density
    private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        text = rotulo
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho)
        typeface = Paleta.fonte(400)
        includeFontPadding = false
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        atualizarCor()
    }

    private fun atualizarCor() {
        setTextColor(when {
            isFocused -> Paleta.TEXTO_ESCURO
            ativa -> Color.WHITE
            else -> Paleta.ABA_INATIVA
        })
    }

    override fun onDraw(c: Canvas) {
        if (isFocused || isPressed) {
            tinta.color = if (isFocused) Color.WHITE else Color.argb(50, 255, 255, 255)
            val alt = textSize * 1.7f
            r.set(0f, height / 2f - alt / 2f, width.toFloat(), height / 2f + alt / 2f)
            c.drawRoundRect(r, alt / 2f, alt / 2f, tinta)
        } else if (ativa) {
            tinta.color = Color.WHITE
            val largTexto = paint.measureText(text.toString())
            val esp = 4f * s
            r.set(width / 2f - largTexto / 2f, height - esp, width / 2f + largTexto / 2f, height.toFloat())
            c.drawRoundRect(r, esp / 2f, esp / 2f, tinta)
        }
        super.onDraw(c)
    }

    override fun onFocusChanged(foco: Boolean, direcao: Int, anterior: Rect?) {
        super.onFocusChanged(foco, direcao, anterior)
        atualizarCor()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Os quatro pontos coloridos do Google Assistente, ao lado da busca. */
class AssistenteView(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        // Desenho num quadro de 50×46.
        val e = min(width / 50f, height / 46f)
        val ox = (width - 50f * e) / 2f
        val oy = (height - 46f * e) / 2f
        fun ponto(x: Float, y: Float, raio: Float, cor: Int) {
            p.color = cor
            c.drawCircle(ox + x * e, oy + y * e, raio * e, p)
        }
        ponto(14f, 15f, 12.5f, 0xFF4285F4.toInt())
        ponto(39f, 18f, 7f, 0xFFEA4335.toInt())
        ponto(35.5f, 37f, 7.5f, 0xFFFBBC04.toInt())
        ponto(44.5f, 29f, 4.5f, 0xFF34A853.toInt())
    }
}

/** Botão redondo da engrenagem no topo. */
class BotaoIcone(ctx: Context, private val tamanhoIcone: Float) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val desenho = Engrenagem()
    private val circulo = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (isPressed || isFocused) {
            circulo.color = if (isFocused) Color.WHITE else Color.argb(50, 255, 255, 255)
            c.drawCircle(cx, cy, min(width, height) / 2f - d, circulo)
        }
        desenho.cor = if (isFocused) Paleta.TEXTO_ESCURO else Paleta.TEXTO
        val m = tamanhoIcone / 2f
        desenho.setBounds((cx - m).toInt(), (cy - m).toInt(), (cx + m).toInt(), (cy + m).toInt())
        desenho.draw(c)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
