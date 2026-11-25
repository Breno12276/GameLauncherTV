package com.gamelaunchertv.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Configurações num painel lateral à direita, no jeito do Android TV: altura inteira, ~40% da largura,
 * entra deslizando; o launcher continua à vista, escurecido, à esquerda (tocar ali fecha).
 * Organizado em pastas: com [paginas], cada [secao] vira uma linha na lista principal (com um resumo do que tem
 * dentro) e as opções seguintes ficam numa página só dela. Cada opção é uma linha retangular (nome e valor atual);
 * abrir mostra as escolhas (○ / ●). Voltar sobe um nível por vez: escolhas → seção → lista principal → fecha.
 * Sem [paginas] (painéis pequenos, como o do controle), as seções são só títulos na mesma lista.
 */
class PainelConfig(
    private val act: Activity, private val s: Float, private val titulo: String = "Configurações",
    private val paginas: Boolean = true,
) {

    private val dialogo = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    /** Estilo PS4: tela cheia com o fundo azul, título fixo em cima, lista no meio e as dicas embaixo. */
    /** Visual do estilo de console escolhido (PS4, Xbox 360, Switch, Wii); null = o painel do Android TV. */
    private val vis = Visuais.de(act)?.takeIf { paginas }
    /** Tela cheia de console (qualquer estilo); [vis] dá as cores e desenhos. */
    private val ps4 = vis != null
    /** Título de cada página (no PS4 ele fica fixo em cima, fora da lista). */
    private val titulos = HashMap<View, String>()
    private var cabecalho: TextView? = null
    private val lista = pagina()
    private val painel = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        setBackgroundColor(Paleta.painel())
        elevation = 24f * s
    }
    private var primeiro: View? = null
    /** Página onde entram as próximas opções: a da última [secao] (ou a lista principal). */
    private var atual: LinearLayout = lista
    /** Páginas da lista principal: nome da seção → (página, linha que a abre, títulos das opções para o resumo). */
    private val secoes = LinkedHashMap<String, Triple<LinearLayout, View, MutableList<String>>>()
    /** Caminho aberto (sem a lista principal): cada página e a linha que a abriu, para o foco voltar nela. */
    private val pilha = ArrayList<Pair<View, View>>()
    /** Android 13+ (e sempre no 16): o Voltar não chega como tecla; este retorno o pega enquanto há página aberta. */
    private val voltarUmNivel = android.window.OnBackInvokedCallback { voltar() }
    private var retornoRegistrado = false

    init {
        if (ps4) titulos[lista] = titulo
        else lista.addView(texto(36f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(12f)
        })
    }

    private fun pagina() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        if (ps4) {
            // Lista no meio da tela, como as configurações do console.
            val w = act.resources.displayMetrics.widthPixels
            setPadding((w * 0.2f).toInt(), px(20f), (w * 0.2f).toInt(), px(40f))
        } else setPadding(px(44f), px(48f), px(44f), px(80f))
    }

    fun secao(titulo: String) {
        if (!paginas) {
            lista.addView(texto(22f, Paleta.AZUL_CLARO, 500).apply { text = titulo.uppercase() }, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = px(36f)
                bottomMargin = px(8f)
                leftMargin = px(20f)
            })
            return
        }
        val pag = pagina()
        if (ps4) titulos[pag] = titulo
        else {
            pag.addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = this@PainelConfig.titulo })
            pag.addView(texto(34f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = px(6f)
                bottomMargin = px(12f)
            })
        }
        val itens = ArrayList<String>()
        lateinit var linha: View
        linha = linhaBase(titulo, "", seta = true, subSuave = true, icone = if (vis?.icones == true) Estilo.iconeSecao(titulo) else null)
            .apply { setOnClickListener { abrir(pag, linha) } }
        lista.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        if (primeiro == null) primeiro = linha
        secoes[titulo] = Triple(pag, linha, itens)
        atual = pag
    }

    /** Guarda o nome da opção para o resumo da seção ("Tamanho dos apps, Cor de fundo, …") na lista principal. */
    private fun registrar(titulo: String) {
        val (_, linha, itens) = secoes.values.lastOrNull()?.takeIf { atual !== lista } ?: return
        itens += titulo
        val resumo = itens.take(3).joinToString(", ") + if (itens.size > 3) "…" else ""
        (linha.tag as? TextView)?.apply {
            text = resumo
            visibility = View.VISIBLE
        }
    }

    /**
     * Opção com escolhas: a linha mostra o nome e a escolha atual; abrir mostra as escolhas com ○ / ●.
     * [aoEscolher] recebe o índice escolhido.
     */
    fun opcoes(titulo: String, descricao: String?, rotulos: List<String>, atual: Int, aoEscolher: (Int) -> Unit) {
        var escolhido = atual.coerceIn(0, rotulos.lastIndex)
        lateinit var linha: View
        lateinit var valor: TextView
        linha = linhaBase(titulo, rotulos.getOrNull(escolhido).orEmpty(), seta = true, valor = { v -> valor = v }).apply {
            setOnClickListener {
                abrirEscolhas(titulo, descricao, rotulos, escolhido, linha) { i ->
                    escolhido = i
                    valor.text = rotulos[i]
                    aoEscolher(i)
                }
            }
        }
        adicionar(titulo, linha)
    }

    /** Linha que faz uma ação (abre outra tela, lista etc.); [rotulo] aparece embaixo do nome. */
    fun acao(titulo: String, descricao: String?, rotulo: String = "Abrir", fecharAntes: Boolean = false, acao: () -> Unit) {
        // Nos estilos de console a linha é como a do console: só o nome e a explicação (sem o rótulo do botão).
        val sub = listOfNotNull(descricao, rotulo.takeIf { it != "Abrir" && !ps4 }).joinToString("\n")
        val linha = linhaBase(titulo, sub, seta = false, subSuave = true).apply {
            setOnClickListener {
                if (fecharAntes) fechar()
                acao()
            }
        }
        adicionar(titulo, linha)
    }

    private fun adicionar(titulo: String, linha: View) {
        atual.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(if (ps4) 0f else 4f) })
        if (primeiro == null) primeiro = linha
        registrar(titulo)
    }

    fun nota(t: String) {
        atual.addView(texto(19f, vis?.corSub ?: Paleta.TEXTO_SUAVE, 400).apply {
            text = t
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(10f)
            leftMargin = px(20f)
            rightMargin = px(20f)
        })
    }

    /** [abrirSecao]: já abre dentro dessa pasta (ex.: depois de salvar uma chave, volta para "Contas e chaves"). */
    fun mostrar(abrirSecao: String? = null) {
        // Pastas em ordem alfabética (sem diferenciar acentos: "Aparência" antes de "Apps").
        if (paginas && secoes.size > 1) {
            val ordem = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR")).apply { strength = java.text.Collator.PRIMARY }
            val linhas = secoes.entries.sortedWith(compareBy(ordem) { it.key }).map { it.value.second }
            val inicio = linhas.minOf { lista.indexOfChild(it) }
            linhas.forEach { lista.removeView(it) }
            linhas.forEachIndexed { i, l -> lista.addView(l, inicio + i) }
            if (primeiro in linhas) primeiro = linhas.first()
        }
        val telaW = act.window.decorView.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels
        val largura = maxOf(px(760f), (telaW * 0.4f).toInt()).coerceAtMost(telaW)
        painel.addView(lista)
        if (ps4) return mostrarPs4(abrirSecao)
        val raiz = android.widget.FrameLayout(act).apply {
            // Toque no lado esquerdo (fora do painel) fecha, como nos painéis da TV.
            setOnClickListener { fechar() }
        }
        raiz.addView(painel, android.widget.FrameLayout.LayoutParams(largura, -1, Gravity.END))
        painel.isClickable = true // toques no painel não fecham
        dialogo.setContentView(raiz)
        dialogo.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.argb(110, 0, 0, 0))) // launcher escurecido atrás
            setLayout(-1, -1)
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setDecorFitsSystemWindows(false)
            insetsController?.hide(WindowInsets.Type.systemBars())
        }
        // B do controle: sobe um nível em vez de fechar. O Voltar do sistema vai só pelo retorno registrado em abrir()
        // (tratar os dois aqui subiria dois níveis de uma vez).
        dialogo.setOnKeyListener { _, codigo, e -> teclaDoPainel(codigo, e) }
        dialogo.show()
        // Entra deslizando da direita.
        painel.translationX = largura.toFloat()
        painel.animate().translationX(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        val inicial = abrirSecao?.let { secoes[it] }
        if (inicial != null) {
            abrir(inicial.first, inicial.second)
        } else {
            // Com controle, o foco começa na primeira linha.
            primeiro?.let { v -> v.post { if (!v.isInTouchMode) v.requestFocus() } }
        }
    }

    /** Estilo PS4: tela cheia, fundo azul com ondas, título fixo em cima, lista no meio e as dicas embaixo. */
    private fun mostrarPs4(abrirSecao: String?) {
        val w = act.resources.displayMetrics.widthPixels
        val u = w / 1280f
        painel.setBackgroundColor(Color.TRANSPARENT)
        painel.elevation = 0f
        val raiz = android.widget.FrameLayout(act)
        val v = vis!!
        raiz.addView(v.fundo(act), android.widget.FrameLayout.LayoutParams(-1, -1))
        val titulo = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, v.tituloTamanho * u)
            setTextColor(v.corTitulo)
            typeface = Ps4Desenho.leve
            includeFontPadding = false
            text = if (v.minusculo) this@PainelConfig.titulo.lowercase() else this@PainelConfig.titulo
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        cabecalho = titulo
        raiz.addView(titulo, android.widget.FrameLayout.LayoutParams(-1, -2).apply { leftMargin = (72 * u).toInt(); topMargin = (32 * u).toInt(); rightMargin = (72 * u).toInt() })
        raiz.addView(View(act).apply { setBackgroundColor(v.corLinha) },
            android.widget.FrameLayout.LayoutParams(-1, maxOf(1, (1.5f * u).toInt())).apply { topMargin = (90 * u).toInt() })
        raiz.addView(painel, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = (100 * u).toInt(); bottomMargin = (90 * u).toInt() })
        raiz.addView(v.dicas?.invoke(act) ?: View(act),
            android.widget.FrameLayout.LayoutParams(-1, (88 * u).toInt(), Gravity.BOTTOM))
        dialogo.setContentView(raiz)
        dialogo.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(-1, -1)
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setDecorFitsSystemWindows(false)
            insetsController?.hide(WindowInsets.Type.systemBars())
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dialogo.setOnKeyListener { _, codigo, e -> teclaDoPainel(codigo, e) }
        dialogo.show()
        raiz.alpha = 0f
        raiz.animate().alpha(1f).setDuration(180).start()
        val inicial = abrirSecao?.let { secoes[it] }
        if (inicial != null) abrir(inicial.first, inicial.second)
        else primeiro?.let { v -> v.post { if (!v.isInTouchMode) v.requestFocus() } }
    }

    /** A cor de fundo mudou: o painel acompanha na hora. */
    fun atualizarCor() { if (!ps4) painel.setBackgroundColor(Paleta.painel()) }

    fun fechar() = dialogo.dismiss()

    // ---------- Navegação entre páginas ----------

    /** Mostra [pagina] no painel; [origem] é a linha que a abriu (o foco volta nela no Voltar). */
    private fun abrir(pagina: View, origem: View, focoInicial: View? = null) {
        pilha += pagina to origem
        if (!retornoRegistrado) {
            dialogo.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, voltarUmNivel)
            retornoRegistrado = true
        }
        exibir(pagina)
        val alvo = focoInicial ?: primeiroFocavel(pagina)
        alvo?.post { if (!alvo.isInTouchMode) alvo.requestFocus() }
    }

    private fun voltar() {
        val (_, origem) = pilha.removeLastOrNull() ?: return
        if (pilha.isEmpty() && retornoRegistrado) {
            dialogo.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(voltarUmNivel)
            retornoRegistrado = false
        }
        exibir(pilha.lastOrNull()?.first ?: lista)
        origem.post {
            if (!origem.isInTouchMode) origem.requestFocus()
            origem.requestRectangleOnScreen(android.graphics.Rect(0, 0, origem.width, origem.height), true)
        }
    }

    private fun exibir(v: View) {
        cabecalho?.text = (titulos[v] ?: titulo).let { if (vis?.minusculo == true) it.lowercase() else it }
        painel.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        painel.addView(v)
        painel.scrollTo(0, 0)
    }

    /**
     * Teclas do controle no painel. B sobe um nível. Sem nada selecionado (depois de um toque, ou quando o painel
     * reabre após trocar o tema), a primeira seta ou o A selecionam a primeira opção da página em vez de se perder.
     */
    private fun teclaDoPainel(codigo: Int, e: KeyEvent): Boolean {
        if (codigo == KeyEvent.KEYCODE_BUTTON_B && pilha.isNotEmpty()) {
            if (e.action == KeyEvent.ACTION_UP) voltar()
            return true
        }
        val navega = codigo == KeyEvent.KEYCODE_DPAD_UP || codigo == KeyEvent.KEYCODE_DPAD_DOWN ||
            codigo == KeyEvent.KEYCODE_DPAD_LEFT || codigo == KeyEvent.KEYCODE_DPAD_RIGHT ||
            codigo == KeyEvent.KEYCODE_BUTTON_A || codigo == KeyEvent.KEYCODE_DPAD_CENTER || codigo == KeyEvent.KEYCODE_ENTER
        val focado = dialogo.currentFocus
        if (navega && (focado == null || !focado.isShown)) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val pag = pilha.lastOrNull()?.first ?: lista
                primeiroFocavel(pag)?.requestFocus()
            }
            return true
        }
        // A do controle confirma a opção em foco (o Android nem sempre trata o A como OK numa janela).
        if (codigo == KeyEvent.KEYCODE_BUTTON_A && focado != null) {
            if (e.action == KeyEvent.ACTION_UP) focado.performClick()
            return true
        }
        return false
    }

    private fun primeiroFocavel(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) primeiroFocavel(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun abrirEscolhas(titulo: String, descricao: String?, rotulos: List<String>, atual: Int, origem: View, escolher: (Int) -> Unit) {
        val sub = pagina()
        if (ps4) titulos[sub] = titulo
        else {
            sub.addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = this@PainelConfig.titulo })
            sub.addView(texto(34f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
        if (descricao != null) {
            sub.addView(texto(20f, vis?.corSub ?: Paleta.TEXTO_SUAVE, 400).apply {
                text = descricao
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f); bottomMargin = px(14f) })
        }
        var focoInicial: View? = null
        rotulos.forEachIndexed { i, r ->
            val marcado = i == atual
            val linha = linhaBase((if (vis?.caixas == true) (if (marcado) "☑  " else "☐  ") else if (marcado) "●  " else "○  ") + r, null, seta = false).apply {
                setOnClickListener {
                    escolher(i)
                    voltar()
                }
            }
            sub.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            if (marcado) focoInicial = linha
        }
        abrir(sub, origem, focoInicial)
    }

    // ---------- Visual ----------

    /**
     * Linha retangular de opção: nome e, embaixo, o valor/descrição. No foco, fundo branco e texto escuro
     * (como as listas do Android TV); [seta] mostra "›" de que abre uma lista. A tag da linha é o texto de baixo.
     */
    private fun linhaBase(
        titulo: String, sub: String?, seta: Boolean, subSuave: Boolean = false, valor: ((TextView) -> Unit)? = null,
        icone: Ps4Desenho.Icone? = null,
    ): LinearLayout {
        if (ps4) return linhaPs4(titulo, sub, seta, valor, icone)
        val nome = texto(26f, Color.WHITE, 500).apply {
            text = titulo
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val detalhe = texto(if (subSuave) 19f else 21f, if (subSuave) Paleta.TEXTO_SUAVE else Paleta.AZUL_CLARO, 400).apply {
            text = sub.orEmpty()
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            setLineSpacing(0f, 1.2f)
        }
        valor?.invoke(detalhe)
        val textos = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(nome)
            addView(detalhe, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
        val marca = texto(32f, Paleta.TEXTO_SUAVE, 400).apply {
            text = if (seta) "›" else ""
            gravity = Gravity.CENTER
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            tag = detalhe
            setPadding(px(20f), px(16f), px(16f), px(16f))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(marca, LinearLayout.LayoutParams(px(30f), -2))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), retangulo(Color.WHITE))
                addState(intArrayOf(android.R.attr.state_pressed), retangulo(Color.argb(60, 255, 255, 255)))
                addState(intArrayOf(), retangulo(Color.TRANSPARENT))
            }
            // No foco o fundo fica branco: textos escuros.
            setOnFocusChangeListener { _, f ->
                nome.setTextColor(if (f) Paleta.TEXTO_ESCURO else Color.WHITE)
                detalhe.setTextColor(when {
                    f -> Color.argb(200, 32, 33, 36)
                    subSuave -> Paleta.TEXTO_SUAVE
                    else -> Paleta.AZUL_CLARO
                })
                marca.setTextColor(if (f) Paleta.TEXTO_ESCURO else Paleta.TEXTO_SUAVE)
            }
        }
    }

    /** Linha do estilo PS4: ícone (nas pastas), nome em letra fina, valor em cinza; seleção com a moldura azul. */
    private fun linhaPs4(titulo: String, sub: String?, seta: Boolean, valor: ((TextView) -> Unit)?, icone: Ps4Desenho.Icone?): LinearLayout {
        val u = act.resources.displayMetrics.widthPixels / 1280f
        val v = vis!!
        val corNome = v.corTexto
        val corSub = v.corSub
        val nome = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * u)
            setTextColor(corNome)
            typeface = Ps4Desenho.normal
            includeFontPadding = false
            text = titulo
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val detalhe = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15 * u)
            setTextColor(corSub)
            typeface = Ps4Desenho.normal
            includeFontPadding = false
            text = sub.orEmpty()
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            setLineSpacing(0f, 1.15f)
        }
        valor?.invoke(detalhe)
        // Opção com escolha (como no console): a escolha atual fica à direita, na mesma linha do nome.
        val direita = valor != null
        if (direita) {
            detalhe.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * u)
            detalhe.gravity = Gravity.END
            detalhe.maxLines = 1
            detalhe.ellipsize = TextUtils.TruncateAt.END
        }
        val textos = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(nome)
            if (!direita) addView(detalhe, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (5 * u).toInt() })
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            tag = detalhe
            minimumHeight = (58 * u).toInt()
            setPadding((18 * u).toInt(), (12 * u).toInt(), (18 * u).toInt(), (12 * u).toInt())
            if (icone != null) addView(IconePs4(act, icone), LinearLayout.LayoutParams((30 * u).toInt(), (30 * u).toInt()).apply { rightMargin = (20 * u).toInt() })
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            if (direita) addView(detalhe, LinearLayout.LayoutParams((300 * u).toInt(), -2).apply { leftMargin = (16 * u).toInt(); rightMargin = (10 * u).toInt() })
            val marca = TextView(act).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * u)
                setTextColor(corSub)
                text = "›"
                typeface = Ps4Desenho.leve
            }
            if (seta) addView(marca, LinearLayout.LayoutParams(-2, -2))
            background = v.selecao(u)
            setOnFocusChangeListener { _, f ->
                if (f) Sons.tocar(Sons.Evento.MOVER)
                // Xbox: no fundo verde o texto fica branco.
                v.corTextoFocado?.let { foco ->
                    nome.setTextColor(if (f) foco else corNome)
                    detalhe.setTextColor(if (f) foco else corSub)
                    marca.setTextColor(if (f) foco else corSub)
                }
            }
        }
    }

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Paleta.fonte(peso)
        includeFontPadding = false
    }

    /** Cantos quase retos, como as listas do Android TV. */
    private fun retangulo(cor: Int) = GradientDrawable().apply {
        cornerRadius = 6f * s
        setColor(cor)
    }

    private fun px(ref: Float) = (ref * s).toInt()
}

/** Linha de pílulas que quebra para a linha de baixo quando não cabe. */
class Fluxo(ctx: android.content.Context, private val vao: Int) : android.view.ViewGroup(ctx) {

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val max = MeasureSpec.getSize(wSpec)
        var x = 0
        var y = 0
        var alturaLinha = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > max) {
                x = 0
                y += alturaLinha + vao
                alturaLinha = 0
            }
            x += c.measuredWidth + vao
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
        setMeasuredDimension(max, y + alturaLinha)
    }

    override fun onLayout(mudou: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val max = r - l
        var x = 0
        var y = 0
        var alturaLinha = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > max) {
                x = 0
                y += alturaLinha + vao
                alturaLinha = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + vao
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
    }
}
