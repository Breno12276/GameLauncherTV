package com.exemplo.novahome

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
        lista.addView(texto(36f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(12f)
        })
    }

    private fun pagina() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(44f), px(48f), px(44f), px(80f))
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
        pag.addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = this@PainelConfig.titulo })
        pag.addView(texto(34f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(6f)
            bottomMargin = px(12f)
        })
        val itens = ArrayList<String>()
        lateinit var linha: View
        linha = linhaBase(titulo, "", seta = true, subSuave = true).apply { setOnClickListener { abrir(pag, linha) } }
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
        linha = linhaBase(titulo, rotulos.getOrNull(escolhido).orEmpty(), seta = true) { v -> valor = v }.apply {
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
        val sub = listOfNotNull(descricao, rotulo.takeIf { it != "Abrir" }).joinToString("\n")
        val linha = linhaBase(titulo, sub, seta = false, subSuave = true).apply {
            setOnClickListener {
                if (fecharAntes) fechar()
                acao()
            }
        }
        adicionar(titulo, linha)
    }

    private fun adicionar(titulo: String, linha: View) {
        atual.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        if (primeiro == null) primeiro = linha
        registrar(titulo)
    }

    fun nota(t: String) {
        atual.addView(texto(19f, Paleta.TEXTO_SUAVE, 400).apply {
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
        val telaW = act.window.decorView.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels
        val largura = maxOf(px(760f), (telaW * 0.4f).toInt()).coerceAtMost(telaW)
        painel.addView(lista)
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
        dialogo.setOnKeyListener { _, codigo, e ->
            if (codigo == KeyEvent.KEYCODE_BUTTON_B && pilha.isNotEmpty()) {
                if (e.action == KeyEvent.ACTION_UP) voltar()
                true
            } else false
        }
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

    /** A cor de fundo mudou: o painel acompanha na hora. */
    fun atualizarCor() = painel.setBackgroundColor(Paleta.painel())

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
        painel.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        painel.addView(v)
        painel.scrollTo(0, 0)
    }

    private fun primeiroFocavel(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) primeiroFocavel(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun abrirEscolhas(titulo: String, descricao: String?, rotulos: List<String>, atual: Int, origem: View, escolher: (Int) -> Unit) {
        val sub = pagina()
        sub.addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = this@PainelConfig.titulo })
        sub.addView(texto(34f, Color.WHITE, 400).apply { text = titulo }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        if (descricao != null) {
            sub.addView(texto(20f, Paleta.TEXTO_SUAVE, 400).apply {
                text = descricao
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f); bottomMargin = px(14f) })
        }
        var focoInicial: View? = null
        rotulos.forEachIndexed { i, r ->
            val marcado = i == atual
            val linha = linhaBase((if (marcado) "●  " else "○  ") + r, null, seta = false).apply {
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
    ): LinearLayout {
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

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Typeface.create(Typeface.SANS_SERIF, peso, false)
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
