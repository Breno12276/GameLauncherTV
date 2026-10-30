package com.exemplo.novahome

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Tela "Trocar capa" (ou "Trocar arte de fundo"): a pessoa escolhe de onde buscar — libretro (consoles), GameTDB
 * (Wii/GC, Wii U, 3DS, DS, Switch, PS3), Steam, App Store, Internet Archive (capas escaneadas) ou SteamGridDB
 * (com chave) —, edita o nome, escolhe o jogo entre os encontrados e a arte
 * entre as miniaturas. [aoEscolher] recebe a URL da arte, ou "" para "Sem capa". [anos] = época do
 * console (ROMs): o libretro vem primeiro e já na plataforma; nos outros jogos, escolhe-se a plataforma.
 */
class SeletorCapa(
    private val act: Activity,
    private val s: Float,
    private val nome: String,
    private val aoGaleria: (() -> Unit)? = null,
    private val anos: IntRange? = null,
    private val titulo: String = "Trocar capa",
    private val rotuloSem: String = "Sem capa",
    private val aoEscolher: (String) -> Unit,
) {
    private enum class Fonte(val rotulo: String) {
        LIBRETRO("libretro (consoles)"), GAMETDB("GameTDB"), STEAM("Steam"), APPSTORE("App Store"),
        ARCHIVE("Internet Archive"), STEAMGRIDDB("SteamGridDB"),
    }

    /** Um jogo encontrado numa fonte: o texto do botão e o que a fonte precisa para listar as artes. */
    private class Opcao(val rotulo: String, val chave: Any)

    private val trabalho = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val dialogo = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    private lateinit var campo: EditText
    private lateinit var fontesLinha: LinearLayout
    private lateinit var jogos: LinearLayout
    private lateinit var grade: LinearLayout
    private lateinit var aviso: TextView
    private var pedido = 0 // ignora respostas de buscas antigas
    // Sem chave primeiro (libretro, Steam, App Store); o SteamGridDB aparece sempre, mas só busca com chave.
    // ROM: o libretro vem primeiro e já na plataforma dela; outros jogos começam pela Steam.
    private val ehRom = FontesLivres.libretroServe(anos)
    private val sistemaDaRom = Sistema.entries.firstOrNull { it.anos == anos }
    private val gametdbDaRom = sistemaDaRom != null && sistemaDaRom in FontesLivres.sistemasGametdb()
    private val fontes = when {
        ehRom && gametdbDaRom -> listOf(Fonte.LIBRETRO, Fonte.GAMETDB, Fonte.STEAM, Fonte.APPSTORE, Fonte.ARCHIVE, Fonte.STEAMGRIDDB)
        ehRom -> listOf(Fonte.LIBRETRO, Fonte.STEAM, Fonte.APPSTORE, Fonte.ARCHIVE, Fonte.GAMETDB, Fonte.STEAMGRIDDB)
        // Switch/PS3/Wii U sem libretro: o GameTDB é o banco de capas deles.
        gametdbDaRom -> listOf(Fonte.GAMETDB, Fonte.STEAM, Fonte.APPSTORE, Fonte.ARCHIVE, Fonte.LIBRETRO, Fonte.STEAMGRIDDB)
        else -> listOf(Fonte.STEAM, Fonte.APPSTORE, Fonte.ARCHIVE, Fonte.LIBRETRO, Fonte.GAMETDB, Fonte.STEAMGRIDDB)
    }
    private var fonte = fontes.first()
    /** Plataforma para buscar no libretro: a da ROM, ou a escolhida na linha de plataformas (jogos que não são ROM). */
    private var sistema: Sistema? = sistemaDaRom
    private lateinit var sistemasLinha: LinearLayout

    fun mostrar() {
        val raiz = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paleta.FUNDO)
            setPadding(px(107f), px(50f), px(107f), 0)
        }
        raiz.addView(texto(40f, Color.WHITE, 400).apply { text = titulo })
        raiz.addView(texto(24f, Paleta.TEXTO_SUAVE, 400).apply { text = nome }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6f) })

        // De onde buscar.
        fontesLinha = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fontesLinha.addView(texto(24f, Paleta.TEXTO_SUAVE, 400).apply { text = "Buscar em:" })
        fontes.forEach { f ->
            fontesLinha.addView(botao(f.rotulo) { trocarFonte(f) }.apply {
                tag = f
                isSelected = f == fonte
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16f) })
        }
        raiz.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(fontesLinha)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26f) })

        // libretro e GameTDB são separados por plataforma: escolher em qual procurar (a ROM já sabe a sua).
        sistemasLinha = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        raiz.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(sistemasLinha)
            visibility = View.GONE
            tag = "rolagemSistemas"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })

        val linhaBusca = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        campo = EditText(act).apply {
            setText(nome)
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Paleta.TEXTO_SUAVE)
            hint = "Nome do jogo"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * s)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = forma(Color.argb(40, 255, 255, 255))
            setPadding(px(28f), px(16f), px(28f), px(16f))
            setOnEditorActionListener { _, _, _ -> buscar(); true }
        }
        linhaBusca.addView(campo, LinearLayout.LayoutParams(0, -2, 1f))
        linhaBusca.addView(botao("Buscar") { buscar() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(20f) })
        aoGaleria?.let { galeria ->
            linhaBusca.addView(botao("Da galeria") {
                dialogo.dismiss()
                galeria()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16f) })
        }
        linhaBusca.addView(botao(rotuloSem) { escolher("") }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16f) })
        linhaBusca.addView(botao("Fechar") { dialogo.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16f) })
        raiz.addView(linhaBusca, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })

        jogos = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        raiz.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(jogos)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24f) })

        aviso = texto(24f, Paleta.TEXTO_SUAVE, 400)
        raiz.addView(aviso, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })

        grade = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(60f))
        }
        raiz.addView(ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(grade)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(12f) })

        dialogo.setContentView(raiz)
        dialogo.window?.setBackgroundDrawable(ColorDrawable(Paleta.FUNDO))
        dialogo.window?.insetsController?.hide(WindowInsets.Type.systemBars())
        dialogo.setOnDismissListener { trabalho.shutdownNow() }
        dialogo.show()
        // Com controle, o foco começa na fonte escolhida.
        fontesLinha.findViewWithTag<View>(fonte)?.let { v -> v.post { if (!v.isInTouchMode) v.requestFocus() } }
        trocarFonte(fonte)
    }

    private fun trocarFonte(f: Fonte) {
        fonte = f
        for (i in 0 until fontesLinha.childCount) fontesLinha.getChildAt(i).let { it.isSelected = it.tag == f }
        // libretro e GameTDB são por plataforma: a da ROM, se o banco tiver; senão a pessoa escolhe na linha de plataformas.
        val plataformas = when (f) {
            Fonte.LIBRETRO -> FontesLivres.sistemasLibretro()
            Fonte.GAMETDB -> FontesLivres.sistemasGametdb()
            else -> null
        }
        sistema = when {
            plataformas == null -> sistema
            sistemaDaRom != null && sistemaDaRom in plataformas -> sistemaDaRom
            sistema in plataformas -> sistema
            else -> null
        }
        sistemasLinha.removeAllViews()
        val mostrarLinha = plataformas != null && (sistemaDaRom == null || sistemaDaRom !in plataformas)
        if (mostrarLinha) {
            sistemasLinha.addView(texto(24f, Paleta.TEXTO_SUAVE, 400).apply { text = "Plataforma:" })
            plataformas!!.forEach { sis ->
                sistemasLinha.addView(botao(sis.nome) { trocarSistema(sis) }.apply {
                    tag = sis
                    isSelected = sis == sistema
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
            }
        }
        (sistemasLinha.parent as? View)?.visibility = if (mostrarLinha) View.VISIBLE else View.GONE
        buscar()
    }

    private fun trocarSistema(s: Sistema) {
        sistema = s
        for (i in 0 until sistemasLinha.childCount) sistemasLinha.getChildAt(i).let { it.isSelected = it.tag == s }
        buscar()
    }

    /** Jogos com esse nome na fonte escolhida; null = erro de rede. */
    private fun pesquisar(termo: String): List<Opcao>? = try {
        when (fonte) {
            Fonte.LIBRETRO -> FontesLivres.libretroPesquisar(act, termo, sistema?.anos)?.map { Opcao(it.arquivo, it) }
            Fonte.STEAM -> FontesLivres.steamPesquisar(termo).map { Opcao(it.nome, it.id) }
            Fonte.APPSTORE -> FontesLivres.appStorePesquisar(termo).map { Opcao(it.nome, it.id) }
            Fonte.GAMETDB -> sistema?.let { s -> FontesLivres.gametdbPesquisar(act, termo, s)?.map { Opcao(it.titulo, it) } }
            // "título|id": o rótulo é o título; a chave, o id do item no Internet Archive.
            Fonte.ARCHIVE -> FontesLivres.archivePesquisar(termo).map { Opcao(it.nome.substringBeforeLast('|'), it.nome.substringAfterLast('|')) }
            Fonte.STEAMGRIDDB -> Capas.pesquisar(termo).map { Opcao(if (it.ano != null) "${it.nome} (${it.ano})" else it.nome, it.id) }
        }
    } catch (_: Exception) {
        null
    }

    private fun artesDe(o: Opcao): List<Capas.Arte>? = try {
        when (fonte) {
            Fonte.LIBRETRO -> FontesLivres.libretroArtes(o.chave as FontesLivres.JogoLibretro)
            Fonte.STEAM -> FontesLivres.steamArtes(o.chave as Int)
            Fonte.APPSTORE -> FontesLivres.appStoreArtes(o.chave as Int)
            Fonte.GAMETDB -> FontesLivres.gametdbArtes(o.chave as FontesLivres.JogoTdb)
            Fonte.ARCHIVE -> FontesLivres.archiveArtes(o.chave as String)
            Fonte.STEAMGRIDDB -> Capas.artes(o.chave as Int)
        }
    } catch (_: Exception) {
        null
    }

    private fun buscar() {
        val termo = campo.text.toString().trim()
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(campo.windowToken, 0)
        if (termo.isEmpty()) return
        val meu = ++pedido
        jogos.removeAllViews()
        grade.removeAllViews()
        if ((fonte == Fonte.LIBRETRO || fonte == Fonte.GAMETDB) && sistema == null) {
            aviso.text = "Escolha a plataforma do jogo na linha acima (o banco ${fonte.rotulo} é separado por console)."
            return
        }
        if (fonte == Fonte.STEAMGRIDDB && !Capas.ativo()) {
            aviso.text = "O SteamGridDB precisa de uma chave (grátis). Coloque a sua em Configurações → Contas e chaves, ou escolha outra fonte."
            return
        }
        aviso.text = "Buscando em ${fonte.rotulo}…"
        trabalho.execute {
            val r = pesquisar(termo)
            ui.post {
                if (meu != pedido) return@post
                when {
                    r == null -> aviso.text = "Não foi possível buscar agora. Confira a internet e tente de novo."
                    r.isEmpty() -> aviso.text = "Nenhum jogo encontrado em ${fonte.rotulo}. Tente outro nome (em inglês costuma achar mais) ou outra fonte."
                    else -> {
                        r.take(12).forEachIndexed { i, jogo ->
                            jogos.addView(botao(jogo.rotulo) { mostrarArtes(jogo) }.apply { tag = jogo },
                                LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = px(14f) })
                        }
                        mostrarArtes(r.first())
                    }
                }
            }
        }
    }

    private fun mostrarArtes(jogo: Opcao) {
        val meu = ++pedido
        grade.removeAllViews()
        aviso.text = "Artes de ${jogo.rotulo}…"
        for (i in 0 until jogos.childCount) jogos.getChildAt(i).isSelected = jogos.getChildAt(i).tag === jogo
        trabalho.execute {
            val artes = artesDe(jogo)
            ui.post {
                if (meu != pedido) return@post
                if (artes.isNullOrEmpty()) {
                    aviso.text = if (artes == null) "Não foi possível carregar as artes." else "Esse jogo não tem artes aqui. Escolha outro resultado ou outra fonte."
                    return@post
                }
                aviso.text = "${artes.size} artes de ${jogo.rotulo} (${fonte.rotulo}). Toque em uma para usar."
                montarGrade(artes)
            }
        }
    }

    private fun montarGrade(artes: List<Capas.Arte>) {
        val colunas = 3
        val vao = px(24f)
        val largura = (act.window.decorView.width - 2 * px(107f) - vao * (colunas - 1)) / colunas
        val altura = largura * 9 / 16
        artes.chunked(colunas).forEach { grupo ->
            val linha = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            grupo.forEachIndexed { i, arte ->
                val img = ImageView(act).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.argb(30, 255, 255, 255))
                    isFocusable = true
                    isClickable = true
                    defaultFocusHighlightEnabled = false
                    contentDescription = "Arte"
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 8f * s)
                    }
                    clipToOutline = true
                    foreground = StateListDrawable().apply {
                        val borda = GradientDrawable().apply {
                            cornerRadius = 8f * s
                            setStroke((4f * s).toInt().coerceAtLeast(3), Color.WHITE)
                        }
                        addState(intArrayOf(android.R.attr.state_focused), borda)
                        addState(intArrayOf(android.R.attr.state_pressed), borda)
                    }
                    setOnClickListener { escolher(arte.url) }
                }
                Imagens.carregar(act, arte.miniatura, largura, altura) { img.setImageBitmap(it) }
                linha.addView(img, LinearLayout.LayoutParams(largura, altura).apply { if (i > 0) leftMargin = vao })
            }
            grade.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = vao })
        }
    }

    private fun escolher(url: String) {
        aoEscolher(url)
        dialogo.dismiss()
    }

    // ---------- Ajudantes de visual ----------

    private fun px(ref: Float) = (ref * s).toInt()

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Typeface.create(Typeface.SANS_SERIF, peso, false)
        includeFontPadding = false
    }

    private fun forma(cor: Int, borda: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = 40f * s
        setColor(cor)
        setStroke((3f * s).toInt().coerceAtLeast(2), borda)
    }

    /** Pílula de texto: branca no foco do controle, azul quando selecionada. */
    private fun botao(rotulo: String, acao: () -> Unit) = texto(24f, Color.WHITE, 500).apply {
        text = rotulo
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        setPadding(px(28f), px(16f), px(28f), px(16f))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), forma(Color.argb(90, 255, 255, 255), Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), forma(Paleta.DESTAQUE))
            addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(70, 255, 255, 255)))
            addState(intArrayOf(), forma(Color.argb(35, 255, 255, 255)))
        }
        setOnClickListener { acao() }
    }
}
