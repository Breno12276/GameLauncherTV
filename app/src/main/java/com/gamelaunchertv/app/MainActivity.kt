package com.gamelaunchertv.app

import android.animation.LayoutTransition
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private enum class Tela { INICIO, JOGOS, APPS, BUSCA }

    /** Um item do destaque do topo: título do TMDB (com imagem) ou, sem catálogo, o próprio app. */
    private class Destaque(
        val provedor: String, val titulo: String, val sub: String,
        val cor: Int, val icone: Bitmap?, val fundo: String?, val abrir: () -> Unit,
    )

    private val trabalho = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    // Medidas: tudo é proporcional à altura da tela de referência do Android TV Home (1127 px).
    private var s = 1f

    private lateinit var telas: FrameLayout
    private val cacheTelas = HashMap<Tela, View>()
    private var telaAtual = Tela.INICIO
    private val relogios = ArrayList<TextView>()

    // ---------- Bateria do controle (Android 12+: InputDevice.batteryState) ----------

    /** Um indicador por barra do topo: (caixa, porcentagem, ícone). */
    private val indicadoresControle = ArrayList<Triple<View, TextView, View>>()
    private val inputManager by lazy { getSystemService(android.hardware.input.InputManager::class.java) }
    private val ouvinteControles = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            atualizarControle()
            // Controle novo: já entra com a luz escolhida.
            Controle.controlesMudaram()
            Controle.aplicarLuz(this@MainActivity, corDoFoco)
        }
        override fun onInputDeviceRemoved(id: Int) {
            atualizarControle()
            Controle.controlesMudaram()
        }
        override fun onInputDeviceChanged(id: Int) = atualizarControle()
    }
    /** Cor do card de jogo/app em foco (para a luz do controle na opção "Cor do jogo em foco"). */
    private var corDoFoco: Int? = null

    // A bateria não avisa quando muda: confere a cada minuto, só com o launcher na tela.
    private val conferirControle = object : Runnable {
        override fun run() {
            atualizarControle()
            ui.postDelayed(this, 60_000L)
        }
    }

    /** Primeiro controle externo conectado (gamepad/joystick). */
    private fun controleConectado(): android.view.InputDevice? {
        val gamepad = android.view.InputDevice.SOURCE_GAMEPAD
        val joystick = android.view.InputDevice.SOURCE_JOYSTICK
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d: android.view.InputDevice = android.view.InputDevice.getDevice(id) ?: continue
            val fontes = d.sources
            if (d.isVirtual || !d.isExternal) continue
            if ((fontes and gamepad) == gamepad || (fontes and joystick) == joystick) return d
        }
        return null
    }

    private fun atualizarControle() {
        val dev = controleConectado()
        val bateria = dev?.batteryState
        val nivel = bateria?.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
        val carregando = bateria?.status == android.hardware.BatteryState.STATUS_CHARGING
        val texto = when {
            nivel == null -> ""
            carregando -> "⚡$nivel%"
            else -> "$nivel%"
        }
        // Bateria baixa (até 15%) em vermelho, como aviso.
        val cor = if (nivel != null && nivel <= 15 && !carregando) 0xFFFF6B6B.toInt() else Paleta.TEXTO
        for ((caixa, rotulo, icone) in indicadoresControle) {
            caixa.visibility = if (dev != null) View.VISIBLE else View.GONE
            caixa.contentDescription = if (dev != null) "Controle ${dev.name}: ${texto.ifEmpty { "bateria desconhecida" }}. Abrir opções do controle" else null
            val corAqui = if (caixa.isFocused) Paleta.TEXTO_ESCURO else cor
            rotulo.text = texto
            rotulo.setTextColor(corAqui)
            (icone.background as? IconeControle)?.let { it.cor = corAqui; it.invalidateSelf() }
        }
    }
    private var campoBusca: EditText? = null

    // Originais (como o sistema/arquivo dizem) e a versão com os nomes que a pessoa trocou.
    private var appsOriginais: List<AppInfo> = emptyList()
    private var romsOriginais: List<Rom> = emptyList()
    private var apps: List<AppInfo> = emptyList()
    private var catalogo: Map<Int, List<Titulo>> = emptyMap()
    private var roms: List<Rom> = emptyList()
    private var atalhos: List<Atalho> = emptyList()
    private lateinit var personalizado: SharedPreferences
    private var varrendoRoms = false
    private var capas: Map<String, String> = emptyMap()
    private var abertoComoHome = false
    private var naTela = false

    // Destaque do topo.
    private var destaques: List<Destaque> = emptyList()
    private var indiceDestaque = 0
    private var heroArte: HeroArte? = null
    private var heroTextos: View? = null
    private var heroProvedor: TextView? = null
    private var heroTitulo: TextView? = null
    private var heroSub: TextView? = null
    private var heroIndicador: Indicador? = null
    private var heroLogo: ImageView? = null
    /** true enquanto o destaque mostra o jogo do trailer (e não os filmes). */
    private var destaqueComJogo = false
    private val girarDestaque = object : Runnable {
        override fun run() {
            proximoDestaque()
            ui.postDelayed(this, intervaloDestaque())
        }
    }

    private val receptorHora = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = atualizarRelogio()
    }
    private val receptorPacotes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = carregarApps()
    }
    private val voltar = OnBackInvokedCallback { Sons.tocar(Sons.Evento.VOLTAR); tratarVoltar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nova_home", MODE_PRIVATE)
        personalizado = getSharedPreferences("personalizado", MODE_PRIVATE)
        Chaves.iniciar(this)
        Roms.corrigirReservas(this)
        // Cor de fundo antes de montar a tela (padrão: automática, pelo tema do Android).
        Paleta.definirFundo(this, prefs.getInt("cor_fundo", 0))
        Paleta.definirDestaque(this, prefs.getInt("cor_destaque", 0))
        Paleta.definirFonte(this)
        Paleta.definirZoom(this)
        abertoComoHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        window.setDecorFitsSystemWindows(false)
        construirUi()
        // Sons de navegação: um toque a cada mudança de foco feita pelo controle (no toque na tela, não).
        Sons.configurar(this)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { antes, depois ->
            if (antes != null && depois != null && !depois.isInTouchMode) Sons.tocar(Sons.Evento.MOVER)
        }
        window.setBackgroundDrawable(null) // o fundo próprio cobre tudo; evita pintar a tela duas vezes
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, voltar)
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(receptorPacotes, filtro, RECEIVER_NOT_EXPORTED)
        carregarApps()
        // Recursos root ligados: o serviço (carregamento e botões do controle) sobe junto com o launcher.
        ServicoRoot.atualizar(this)
        // Catálogos da nuvem (GeForce NOW, Xbox Cloud) e conquistas recentes, para a aba Jogos.
        carregarNuvem()
        // "Quem está jogando?" ao abrir (com mais de um perfil e a opção ligada).
        if (savedInstanceState == null && Perfis.prefs(this).getBoolean("perguntar", false) && Perfis.todos(this).size > 1 &&
            intent?.getBooleanExtra(EXTRA_ULTIMO_JOGO, false) != true) ui.postDelayed({ escolherPerfil() }, 600)
        // Aberto pelos dois toques no PS (o launcher não estava na memória).
        if (intent?.getBooleanExtra(EXTRA_ULTIMO_JOGO, false) == true) ui.postDelayed({ abrirUltimoJogo(intent.getStringExtra(EXTRA_PACOTE_FRENTE), 0) }, 800)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            abertoComoHome = true
            fecharBibliotecaPs4()
            fecharGradesConsole()
            mostrar(Tela.INICIO)
            (cacheTelas[Tela.INICIO] as? ScrollView)?.smoothScrollTo(0, 0)
        }
        // Vindo da confirmação de atalho: mostra a aba Jogos (o atalho novo entra no onResume).
        if (intent.getBooleanExtra(EXTRA_ABA_JOGOS, false)) mostrar(Tela.JOGOS)
        if (intent.getBooleanExtra(EXTRA_CONFIG, false)) ui.postDelayed({ abrirConfiguracoes("Root") }, 300)
        if (intent.getBooleanExtra(EXTRA_ULTIMO_JOGO, false)) abrirUltimoJogo(intent.getStringExtra(EXTRA_PACOTE_FRENTE), 0)
    }

    /** Dois toques no PS: abre o jogo jogado por último (pulando o que está na frente). Tenta de novo se os jogos ainda estão carregando. */
    private fun abrirUltimoJogo(frente: String?, tentativa: Int) {
        val r = Historico.recentes(this, 10).firstOrNull { frente == null || !it.id.contains(frente) }
        if (r == null) {
            Toast.makeText(this, "Nenhum jogo jogado ainda", Toast.LENGTH_SHORT).show()
            return
        }
        val card = cardPorId(r.id)
        if (card != null) {
            Toast.makeText(this, "Abrindo ${r.nome}", Toast.LENGTH_SHORT).show()
            card.performClick()
        } else if (tentativa < 5) {
            ui.postDelayed({ abrirUltimoJogo(frente, tentativa + 1) }, 1000)
        } else {
            Toast.makeText(this, "\"${r.nome}\" não está mais no launcher", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        naTela = true
        ui.removeCallbacks(liberarEmSegundoPlano)
        fundoApp?.pausarMovimento(false)
        Dormir.acordou(this)
        val filtro = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(receptorHora, filtro, RECEIVER_NOT_EXPORTED)
        atualizarRelogio()
        iniciarDestaque()
        inputManager.registerInputDeviceListener(ouvinteControles, ui)
        ui.post(conferirControle)
        // De volta ao launcher (ou o Android desfez a luz enquanto outro app estava na frente): reaplica.
        Controle.controlesMudaram()
        Controle.aplicarLuz(this, corDoFoco)
        // Paisagem nova no fundo a cada volta depois de pelo menos 1 hora.
        if (System.currentTimeMillis() - prefs.getLong("paisagem_quando", 0) > 60 * 60 * 1000) aplicarFundoApp(trocar = true)
    }

    override fun onStop() {
        // Fora da tela (ou com a tela apagada): relógio, destaque e descanso parados. Pôsteres ficam na memória.
        naTela = false
        pinOk = false
        ui.postDelayed(liberarEmSegundoPlano, 45_000)
        fundoApp?.pausarMovimento(true)
        pararDestaque()
        esconderDescanso(animar = false)
        pararTrailerFundo()
        inputManager.unregisterInputDeviceListener(ouvinteControles)
        ui.removeCallbacks(conferirControle)
        unregisterReceiver(receptorHora)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        esconderBarras()
        reiniciarOcio()
        recarregarAtalhos()
        // De volta do jogo: soma o tempo jogado; "Continuar jogando" é refeito com o jogo na frente.
        Historico.voltou(this)
        if (historicoMudou) {
            historicoMudou = false
            // A aba é refeita ("Continuar jogando" muda): o foco volta para o mesmo card, na mesma fileira.
            val foco = if (telaAtual == Tela.JOGOS) currentFocus else null
            val idFoco = foco?.tag as? String
            var p = foco?.parent as? View
            while (p != null && (p.tag as? String)?.startsWith("ordem:") != true) p = p.parent as? View
            val linhaFoco = p?.tag as? String
            descartar(Tela.JOGOS)
            if (layoutInicio() != 0) {
                descartar(Tela.INICIO)
                if (telaAtual == Tela.INICIO) mostrar(Tela.INICIO)
            }
            if (telaAtual == Tela.JOGOS) {
                mostrar(Tela.JOGOS)
                ui.post { focarDeNovo(cacheTelas[Tela.JOGOS] ?: return@post, linhaFoco, idFoco) }
            }
        }
        // De volta do jogo: desfaz o modo desempenho e faz o backup automático dos saves, se for a hora.
        if (Root.ligado(this)) Thread {
            RecursosRoot.fimDoJogo(this)
            Saves.automaticoSePreciso(this)
        }.start()
    }

    override fun onPause() {
        ui.removeCallbacks(abrirDescanso)
        ui.removeCallbacks(liberarTela)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderBarras()
    }

    override fun onDestroy() {
        pararDestaque()
        unregisterReceiver(receptorPacotes)
        trabalho.shutdownNow()
        buscaFundos.shutdownNow()
        super.onDestroy()
    }

    /**
     * Em segundo plano há um tempo (um jogo aberto): solta as telas que não estão à vista e as imagens da memória,
     * deixando mais RAM para o emulador. Na volta elas vêm do cache em disco (rápido).
     */
    private val liberarEmSegundoPlano = Runnable {
        if (naTela) return@Runnable
        descartarTodas(exceto = telaAtual)
        Imagens.soltarMemoria()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Só solta memória em aviso sério; os avisos RUNNING_* chegam com o launcher na tela.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            descartarTodas(exceto = telaAtual)
            Imagens.soltarMemoria()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        construirUi()
    }

    // ---------- Estrutura ----------

    private fun construirUi() {
        val m = windowManager.currentWindowMetrics.bounds
        s = Paleta.escala(this, min(m.width(), m.height()))
        cacheTelas.clear()
        relogios.clear()
        infosTopo.clear()
        indicadoresControle.clear()
        heroArte = null

        val raiz = FrameLayout(this)
        raiz.addView(FundoTV(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Paisagem ou foto escolhida atrás de todas as abas (fica liso no padrão azul-marinho).
        fundoApp = FundoImagem(this, FundoImagem.Veu.APP).also {
            raiz.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            it.animar(prefs.getBoolean("fundo_animado", false))
        }
        // Estilo PS4: o fundo azul com ondas atrás de todas as telas (busca, informações), no lugar da paisagem.
        if (layoutInicio() == 1) raiz.addView(FundoPs4(this, prefs.getBoolean("fundo_animado", true)),
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (layoutInicio() == 5) raiz.addView(FundoPs5(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (layoutInicio() == 6) raiz.addView(FundoSeries(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Trailer de fundo (acima da paisagem, atrás das abas).
        fundoVideo = FundoVideo(this).also {
            raiz.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        telas = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        raiz.addView(telas, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (prefs.getBoolean("efeito_crt", false)) raiz.addView(EfeitoCrt(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        descanso = construirDescanso().also {
            raiz.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        aplicarFundoApp(trocar = false)
        // Recorte da câmera e barras do sistema (escondidas no modo imersivo). Os textos já ficam
        // a ~107 px da borda; só empurra o layout se o recorte for maior que isso.
        raiz.setOnApplyWindowInsetsListener { _, wi ->
            val i = wi.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            telas.setPadding(max(0, i.left - px(90f)), i.top, max(0, i.right - px(90f)), i.bottom)
            wi
        }
        setContentView(raiz)
        mostrar(telaAtual)
        atualizarRelogio()
    }

    private fun esconderBarras() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun mostrar(t: Tela) {
        if (t != Tela.INICIO && layoutInicio() != 0 && abrirNoConsole(t)) return
        if (t != telaAtual) pararTrailerFundo()
        telaAtual = t
        // Telas já montadas continuam na árvore de desenho, só deslocadas para fora da tela:
        // trocar de tela não refaz o layout nem as camadas de GPU dos cards.
        val v = montada(t)
        for (i in 0 until telas.childCount) {
            val filho = telas.getChildAt(i)
            if (filho === v) exibir(filho) else esconder(filho)
        }
        val campo = campoBusca
        if (t == Tela.BUSCA && campo != null) {
            campo.requestFocus()
            ui.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(campo, 0) }, 120)
        } else if (campo != null) {
            esconderTeclado(campo)
        }
        if (t == Tela.INICIO) iniciarDestaque() else pararDestaque()
        // Tela do PS4: o controle fala com ela (não há cards focáveis).
        if (t == Tela.INICIO) telaDoConsole()?.let { ps -> ps.post { if (telaAtual == Tela.INICIO && currentFocus !is TelaPs4) ps.requestFocus() } }
    }

    private fun montada(t: Tela): View = cacheTelas.getOrPut(t) {
        val v = when (t) {
            Tela.INICIO -> telaInicio()
            Tela.JOGOS -> telaJogos()
            Tela.APPS -> telaApps()
            Tela.BUSCA -> telaBusca()
        }
        esconder(v)
        telas.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v
    }

    // Abas escondidas ficam INVISIBLE: continuam montadas (trocar de aba não refaz o layout),
    // mas não são desenhadas. (Deslocadas para fora da tela elas eram desenhadas a cada quadro.)
    private fun exibir(v: View) {
        v.visibility = View.VISIBLE
        (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun esconder(v: View) {
        if (v.hasFocus()) v.clearFocus()
        v.visibility = View.INVISIBLE
        (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun descartar(t: Tela) {
        cacheTelas.remove(t)?.let { telas.removeView(it) }
        if (t == Tela.BUSCA) campoBusca = null
        if (t == Tela.INICIO) heroArte = null
        if (t == Tela.JOGOS) fundoJogos = null
    }

    private fun descartarTodas(exceto: Tela? = null) {
        cacheTelas.keys.filter { it != exceto }.forEach { descartar(it) }
    }

    /** Remonta as telas com os dados novos, mantendo a tela atual na frente. */
    private fun remontar() {
        val busca = cacheTelas[Tela.BUSCA]
        descartarTodas(exceto = if (busca != null && telaAtual == Tela.BUSCA) Tela.BUSCA else null)
        mostrar(telaAtual)
        premontar()
    }

    /** Monta as outras abas quando o celular está ocioso, para a primeira visita não travar. */
    private fun premontar() {
        Looper.myQueue().addIdleHandler {
            if (!isDestroyed && apps.isNotEmpty()) {
                montada(Tela.APPS)
                montada(Tela.JOGOS)
            }
            false
        }
    }

    /**
     * Página rolável com um "ralo de foco" invisível no topo: os cards grandes aceitam foco no
     * toque (1º toque seleciona e expande, 2º abre), e sem o ralo o Android daria foco a um deles sozinho.
     */
    private fun pagina(): Pair<ScrollView, LinearLayout> {
        val sv = rolagemVertical()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        col.addView(View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            tag = RALO
        }, LinearLayout.LayoutParams(1, 1))
        return sv to col
    }

    // ---------- Barra do topo: busca, abas, engrenagem e relógio ----------

    private fun barraTopo(ativa: Tela): View {
        val barra = FrameLayout(this).apply { clipChildren = false }
        val busca = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8f), 0, px(24f), 0)
            defaultFocusHighlightEnabled = false
        }
        busca.addView(AssistenteView(this).apply { contentDescription = "Google Assistente" },
            LinearLayout.LayoutParams(px(46f), px(42f)))

        if (ativa == Tela.BUSCA) {
            val campo = EditText(this).apply {
                hint = "Pesquise apps e jogos"
                isSingleLine = true
                background = null
                setTextColor(Color.WHITE)
                setHintTextColor(Paleta.TEXTO_SUAVE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f * s)
                typeface = Paleta.fonte(400)
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                setPadding(0, 0, 0, 0)
                setOnEditorActionListener { v, _, _ -> esconderTeclado(v); true }
            }
            campoBusca = campo
            busca.background = forma(Color.argb(40, 255, 255, 255), 38f)
            busca.addView(campo, LinearLayout.LayoutParams(px(820f), -2).apply { leftMargin = px(20f) })
            // Busca por voz: o reconhecimento de fala do Android (microfone do celular).
            val mic = texto(30f, Paleta.TEXTO, 400).apply {
                text = "🎤"
                gravity = Gravity.CENTER
                contentDescription = "Buscar por voz"
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), forma(Color.argb(90, 255, 255, 255), 30f))
                }
                setOnClickListener { buscarPorVoz() }
            }
            busca.addView(mic, LinearLayout.LayoutParams(px(64f), px(64f)).apply { leftMargin = px(8f) })
        } else {
            busca.addView(texto(32f, Paleta.TEXTO_SUAVE, 400).apply { text = "Pesquisar" },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(20f) })
            busca.isFocusable = true
            busca.isClickable = true
            busca.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), forma(Color.argb(60, 255, 255, 255), 38f))
                addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(45, 255, 255, 255), 38f))
            }
            busca.setOnClickListener { mostrar(Tela.BUSCA) }
            // Toque longo: fala com o Assistente, como o botão do microfone no controle da TV.
            busca.setOnLongClickListener { abrirAssistente(); true }
        }
        // Busca e abas na mesma linha: "Pesquisar" é mais comprido que o "Search" da referência,
        // então as abas vêm logo depois dela, com o mesmo vão (59 px entre os textos).
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        linha.addView(busca, LinearLayout.LayoutParams(-2, px(76f)))
        barra.addView(linha, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply {
            leftMargin = px(99f)
            topMargin = px(42f)
        })

        if (ativa != Tela.BUSCA) {
            val abas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((tela, rotulo) in listOf(Tela.INICIO to "Início", Tela.JOGOS to "Jogos", Tela.APPS to "Apps")) {
                val aba = AbaView(this, rotulo, 32f * s, s).apply {
                    this.ativa = tela == ativa
                    tag = "aba_${tela.name}"
                    setPadding(px(18f), 0, px(18f), 0)
                    setOnClickListener { trocarAba(tela) }
                }
                abas.addView(aba, LinearLayout.LayoutParams(-2, px(76f)).apply { rightMargin = px(22f) })
            }
            linha.addView(abas, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(17f) })
        }

        val engrenagem = BotaoIcone(this, 44f * s).apply {
            contentDescription = "Configurações"
            setOnClickListener { abrirConfiguracoes() }
        }
        barra.addView(engrenagem, FrameLayout.LayoutParams(px(70f), px(70f), Gravity.END or Gravity.TOP).apply {
            rightMargin = px(238f)
            topMargin = px(50f)
        })
        // Bateria do controle, à esquerda da engrenagem (só aparece com controle conectado).
        // É um botão: abre o painel do controle (luz, brilho, vibração).
        val controle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            setPadding(px(14f), 0, px(18f), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), forma(Color.WHITE, 35f))
                addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(50, 255, 255, 255), 35f))
            }
            setOnClickListener { abrirControle() }
            // No foco o fundo fica branco: ícone e texto escurecem (atualizarControle escolhe a cor).
            setOnFocusChangeListener { _, _ -> atualizarControle() }
        }
        val iconeControle = View(this).apply { background = IconeControle().apply { cor = Paleta.TEXTO } }
        val textoControle = texto(28f, Paleta.TEXTO, 400)
        controle.addView(iconeControle, LinearLayout.LayoutParams(px(40f), px(40f)))
        controle.addView(textoControle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(6f) })
        barra.addView(controle, FrameLayout.LayoutParams(-2, px(70f), Gravity.END or Gravity.TOP).apply {
            rightMargin = px(238f + 70f + 4f)
            topMargin = px(50f)
        })
        indicadoresControle += Triple(controle, textoControle, iconeControle)
        atualizarControle()
        val relogio = texto(40f, Paleta.TEXTO, 400)
        relogios += relogio
        barra.addView(relogio, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(112f)
            topMargin = px(62f)
        })
        // Informações embaixo do relógio (clima, temperatura do celular).
        val info = texto(22f, Paleta.TEXTO_SUAVE, 400).apply { gravity = Gravity.END }
        infosTopo += info
        barra.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(112f)
            topMargin = px(if (prefs.getInt("relogio", 0) == 2) 140f else 112f)
        })
        atualizarRelogio()
        return barra
    }

    private val infosTopo = ArrayList<TextView>()

    private fun atualizarInfosTopo() {
        val t = Widgets.texto(this, prefs.getInt("widgets", 0)) { ui.post { atualizarInfosTopo() } }
        infosTopo.forEach {
            it.text = t
            it.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun trocarAba(t: Tela) {
        // Estilo PS4: não há abas; Jogos e Apps são a Biblioteca, por cima da tela inicial.
        if (layoutInicio() == 1 && (t == Tela.JOGOS || t == Tela.APPS)) {
            mostrar(Tela.INICIO)
            raizPs4?.let { abrirBibliotecaPs4(it, if (t == Tela.JOGOS) "Jogos" else "Aplicativos") }
            return
        }
        // Estilo Switch: Jogos é a grade "Todos os programas" da tela inicial.
        if (layoutInicio() == 3 && t == Tela.JOGOS) {
            mostrar(Tela.INICIO)
            inicioSwitch?.abrirTodos()
            return
        }
        // Estilo Xbox 360: Jogos e Apps são categorias da própria tela inicial.
        if (layoutInicio() == 2 && (t == Tela.JOGOS || t == Tela.APPS)) {
            mostrar(Tela.INICIO)
            inicioXbox?.irPara(if (t == Tela.JOGOS) "jogos" else "apps")
            return
        }
        mostrar(t)
        // Com o controle, o foco continua na aba escolhida (na nova tela).
        if (!telas.isInTouchMode) cacheTelas[t]?.findViewWithTag<View>("aba_${t.name}")?.requestFocus()
    }

    // ---------- Início ----------

    /** Serviços de vídeo instalados que o TMDB conhece (Netflix, Google TV…), na ordem dos favoritos. */
    private fun servicos(visiveis: List<AppInfo>) = Catalogo.favoritos(visiveis)
        .mapNotNull { app -> Catalogo.provedor(app.pacote)?.let { (nome, id) -> Triple(nome, id, app) } }
        .distinctBy { it.second }

    private fun telaInicio(): View {
        if (layoutInicio() != 0) return telaConsole()
        val (sv, col) = pagina()
        val pagina = FrameLayout(this).apply { clipChildren = false }
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val favoritos = favoritosDe(visiveis)
        val servicos = servicos(visiveis).filter { !catalogo[it.second].isNullOrEmpty() }

        // Destaque: os títulos mais populares de cada serviço (TMDB); sem ele, os do Stremio (sem chave);
        // sem nenhum dos dois, os próprios apps.
        val stremio = pacoteStremio()
        val doStremio = if (stremio == null) emptyList() else listOf("stremio_filmes" to "Filme", "stremio_series" to "Série").map { (k, tipo) ->
            recs[k].orEmpty().filter { it.imagem != null }.take(3).map { item ->
                val app = apps.firstOrNull { it.pacote == stremio }
                Destaque("Stremio · $tipo", item.nome, "Em alta | " + primeiraFrase(item.detalhe), app?.cor ?: Paleta.AZUL, app?.icone,
                    item.imagem) { abrirRec(item, stremio) }
            }
        }
        destaques = if (servicos.isNotEmpty()) {
            val porServico = servicos.map { (nome, id, app) ->
                catalogo[id].orEmpty().take(3).map { t ->
                    Destaque(nome, t.nome, "Em alta | " + primeiraFrase(t.sinopse), app.cor, app.icone, t.fundo) { abrirApp(app) }
                }
            }
            (0 until 3).flatMap { i -> porServico.mapNotNull { it.getOrNull(i) } }.take(6)
        } else if (doStremio.any { it.isNotEmpty() }) {
            (0 until 3).flatMap { i -> doStremio.mapNotNull { it.getOrNull(i) } }
        } else {
            favoritos.filter { it.categoria == Categoria.VIDEO || it.categoria == Categoria.MUSICA }.take(5)
                .ifEmpty { favoritos.take(5) }
                .map { app -> Destaque(rotuloCategoria(app), app.nome, "No seu celular | Toque para abrir", app.cor, app.icone, null) { abrirApp(app) } }
        }
        indiceDestaque = indiceDestaque.coerceAtMost(max(0, destaques.size - 1))

        if (!prefs.getBoolean("destaque", true)) {
            // Destaque desligado: as fileiras começam logo abaixo da barra do topo.
            destaques = emptyList()
            heroArte = null
            heroTextos = null
            heroProvedor = null
            heroTitulo = null
            heroSub = null
            heroIndicador = null
            col.addView(barraTopo(Tela.INICIO), LinearLayout.LayoutParams(-1, px(140f)))
        } else {
        // Arte do destaque: atrás da barra e dos textos, rola junto com a página.
        val arte = HeroArte(this).apply {
            setOnClickListener { destaques.getOrNull(indiceDestaque)?.abrir?.invoke() }
            // Só para toque: no controle o foco vai das abas direto para os favoritos, como na TV.
            isFocusable = false
        }
        heroArte = arte
        pagina.addView(arte, FrameLayout.LayoutParams(-1, px(670f)))

        col.addView(barraTopo(Tela.INICIO), LinearLayout.LayoutParams(-1, px(140f)))
        val hero = FrameLayout(this)
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val provedor = texto(24f, Color.argb(235, 255, 255, 255), 500)
        val titulo = texto(60f, Color.WHITE, 400).apply { umaLinha() }
        val sub = texto(30f, Paleta.TEXTO_SUAVE, 400).apply { umaLinha() }
        textos.addView(provedor)
        textos.addView(titulo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        textos.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        // Logo do jogo: aparece no lugar do título quando o trailer de um jogo toca no fundo.
        val logoJogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        textos.addView(logoJogo, 2, LinearLayout.LayoutParams(px(420f), px(88f)).apply { topMargin = px(10f) })
        heroLogo = logoJogo
        hero.addView(textos, FrameLayout.LayoutParams(px(900f), -2).apply {
            leftMargin = px(107f)
            topMargin = px(88f)
        })
        val indicador = Indicador(this)
        hero.addView(indicador, FrameLayout.LayoutParams(px(160f), px(10f), Gravity.END or Gravity.TOP).apply {
            rightMargin = px(128f)
            topMargin = px(220f)
        })
        col.addView(hero, LinearLayout.LayoutParams(-1, px(270f)))
        heroTextos = textos
        heroProvedor = provedor
        heroTitulo = titulo
        heroSub = sub
        heroIndicador = indicador
        aplicarDestaque()
        }

        if (apps.isNotEmpty()) {
            // Seções na ordem que a pessoa escolheu (painel ⋮ ao lado de cada fileira, ou Configurações).
            montarSecoes(col, ABA_INICIO, secoesDoInicio(visiveis, favoritos, servicos))
            if (servicos.isNotEmpty()) {
                col.addView(texto(20f, Paleta.TEXTO_SUAVE, 400).apply {
                    text = "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB."
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    leftMargin = px(107f)
                    topMargin = px(30f)
                })
            }
        }
        // Crédito da paisagem do fundo (a licença CC BY-SA pede autor e licença à vista).
        val credito = texto(20f, Paleta.TEXTO_SUAVE, 400)
        rodapeCredito = credito
        mostrarCreditoFundo()
        col.addView(credito, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = px(107f)
            topMargin = px(10f)
        })
        col.addView(Space(this), LinearLayout.LayoutParams(-1, px(60f)))
        pagina.addView(col, FrameLayout.LayoutParams(-1, -2))
        sv.addView(pagina, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return sv
    }

    // ---------- Telas iniciais no estilo de console (Temas e sons › Tela inicial) ----------
    // Só o jeito de organizar a tela lembra cada console (fileira única, blocos, canais); nenhum logo, ícone ou som
    // deles é usado. As abas Jogos e Apps continuam em fileiras, com as cores do tema.

    /** 0 = Android TV (fileiras), 1 = PS4, 2 = Xbox 360, 3 = Switch, 4 = Wii. */
    private fun layoutInicio() = prefs.getInt("layout_inicio", 0)

    private class ItemConsole(val id: String, val nome: String, val card: View, val capa: String?, val anos: IntRange?, val detalhe: String)

    /** Jogos da tela de console: os jogados por último e, depois, os outros (até [n]). */
    private fun jogosConsole(n: Int): List<ItemConsole> {
        val ids = LinkedHashSet<String>()
        Historico.recentes(this, n).forEach { ids += it.id }
        jogosAndroid(apps.filter { it.pacote !in ocultos() }).forEach { if (ids.size < n) ids += idApp(it) }
        roms.forEach { if (ids.size < n) ids += idRom(it) }
        return ids.mapNotNull { id ->
            if (Perfis.escondido(this, id)) return@mapNotNull null
            val card = cardPorId(id) ?: return@mapNotNull null
            val nome = card.contentDescription?.toString() ?: Historico.registro(this, id)?.nome ?: return@mapNotNull null
            val anos = if (id.startsWith("rom:")) roms.firstOrNull { idRom(it) == id }?.sistema?.anos else null
            val capa = if (id.startsWith("nuvem:")) todosNuvem().firstOrNull { it.idCard == id }?.capa ?: capaDe(id, nome) else capaDe(id, nome)
            val r = Historico.registro(this, id)
            ItemConsole(id, nome, card, capa, anos, r?.let { "Jogado " + Historico.quandoLegivel(it.ultimaVez) } ?: plataformaDoId(id))
        }
    }

    private fun arteApp(app: AppInfo): String? {
        val manual = capaManual(idApp(app))
        return if (manual != null) manual.ifEmpty { null } else capas[Capas.chaveApp(Catalogo.nomeParaArte(app))]
    }

    /** Apps favoritos como cards da tela de console. */
    private fun appsConsole(n: Int): List<ItemConsole> = favoritosDe(apps.filter { it.pacote !in ocultos() }).take(n).map { app ->
        val arte = arteApp(app)
        val card = cardGrande(app.nome, rotuloCategoria(app), app.icone, app.cor, arte, { abrirApp(app) }, { v -> opcoes(v, app) }, idApp(app))
        if (arte == null && usarBannerTv()) app.banner?.let { (card as CardTV).definirImagem(it) }
        ItemConsole(idApp(app), app.nome, card, arte, null, rotuloCategoria(app))
    }

    /** Ações fixas das telas de console (os botões redondos). */
    private fun acoesConsole(): List<Triple<String, String, () -> Unit>> = listOf(
        Triple("🎮", "Jogos") { trocarAba(Tela.JOGOS) },
        Triple("📱", "Apps") { trocarAba(Tela.APPS) },
        Triple("🔍", "Buscar") { mostrar(Tela.BUSCA) },
        Triple("📊", "Estatísticas") { mostrarEstatisticas() },
        Triple("👤", "Perfil") { escolherPerfil() },
        Triple("⚙", "Configurações") { abrirConfiguracoes() },
        Triple("🌙", "Dormir") { dormirAgora() },
    )

    /** Botão redondo com ícone e legenda (a legenda aparece no foco). [claro]: para fundos claros (Wii). */
    private fun botaoRedondo(icone: String, rotulo: String, tam: Float, claro: Boolean = false, acao: () -> Unit): View {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        val corTexto = if (claro) 0xFF4A535C.toInt() else Color.WHITE
        val b = texto(tam * 0.42f, corTexto, 400).apply {
            text = icone
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = false
            contentDescription = rotulo
            defaultFocusHighlightEnabled = false
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (claro) Color.WHITE else Paleta.DESTAQUE)
                    setStroke(px(5f), if (claro) Paleta.DESTAQUE else Color.WHITE)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (claro) 0xFFF7F9FB.toInt() else Color.argb(46, 255, 255, 255))
                    if (claro) setStroke(px(3f), 0xFFB9C3CC.toInt())
                })
            }
            setOnClickListener { acao() }
        }
        val legenda = texto(22f, corTexto, 500).apply {
            text = rotulo
            gravity = Gravity.CENTER
            alpha = 0f
            umaLinha()
        }
        b.setOnFocusChangeListener { _, f ->
            legenda.animate().alpha(if (f) 1f else 0f).setDuration(150).start()
            b.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(150).start()
        }
        caixa.addView(b, LinearLayout.LayoutParams(px(tam), px(tam)))
        caixa.addView(legenda, LinearLayout.LayoutParams(px(tam * 2.4f), -2).apply { topMargin = px(10f) })
        return caixa
    }

    /** Chama [f] quando o card ganha ou perde o foco, sem tirar o que o card já faz. */
    private fun aoFocar(item: ItemConsole, f: (Boolean) -> Unit) {
        val c = item.card as? CardTV ?: return
        val antes = c.aoMudarFoco
        c.aoMudarFoco = { foco -> antes?.invoke(foco); f(foco) }
    }

    /** Fundo da tela de console com a arte do item em foco (como o fundo da aba Jogos). */
    private fun fundoDoItem(fundo: FundoImagem, item: ItemConsole) {
        if (item.id.startsWith("app:") && apps.firstOrNull { idApp(it) == item.id }?.let { ehJogoAndroid(it) } != true) {
            val capa = item.capa ?: return fundo.limpar()
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            val pedido = ++pedidoFundo
            Imagens.carregar(this, capa, w, h) { b -> if (pedido == pedidoFundo) fundo.mostrar(b, capa) }
            return
        }
        selecionarFundo(fundo, item.capa, item.nome, item.anos, item.id)
    }

    /** Relógio (e linha de informações) no canto de cima à direita das telas de console. */
    private fun relogioConsole(raiz: FrameLayout, cor: Int = Paleta.TEXTO, tam: Float = 40f, embaixo: Boolean = false) {
        val relogio = texto(tam, cor, 400)
        relogios += relogio
        val info = texto(22f, if (cor == Paleta.TEXTO) Paleta.TEXTO_SUAVE else cor, 400).apply { gravity = Gravity.END }
        infosTopo += info
        if (embaixo) return
        raiz.addView(relogio, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = px(112f); topMargin = px(56f) })
        raiz.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = px(112f); topMargin = px(110f) })
    }

    /** Fileira horizontal (rolável) de itens, com tamanho fixo de card. */
    private fun fileiraConsole(itens: List<ItemConsole>, w: Int, h: Int, vao: Int): HorizontalScrollView {
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            tag = "console"
            setPadding(px(107f), px(30f), px(107f), px(30f))
        }
        for (it in itens) {
            (it.card as? CardTV)?.tamanho = android.util.Size(w, h)
            (it.card.parent as? ViewGroup)?.removeView(it.card)
            linha.addView(it.card, LinearLayout.LayoutParams(w, h).apply { rightMargin = vao })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(linha)
        }
    }

    /** Foca o primeiro item quando a tela aparece (só no controle). */
    private fun focarAoMostrar(v: View, primeiro: View?) {
        primeiro ?: return
        v.post { if (!v.isInTouchMode && telaAtual == Tela.INICIO && v.findFocus() == null) primeiro.requestFocus() }
    }

    private fun telaConsole(): View {
        // Sem destaque do topo nestas telas.
        destaques = emptyList()
        heroArte = null
        heroTextos = null
        heroProvedor = null
        heroTitulo = null
        heroSub = null
        heroIndicador = null
        estadoPs4 = inicioPs4?.estado() ?: estadoPs4
        inicioPs4 = null
        raizPs4 = null
        estadoXbox = inicioXbox?.estado() ?: estadoXbox
        inicioXbox = null
        estadoPs5 = inicioPs5?.estado() ?: estadoPs5
        inicioPs5 = null
        estadoSeries = inicioSeries?.estado() ?: estadoSeries
        inicioSeries = null
        inicioSwitch = null
        inicioWii = null
        return when (layoutInicio()) {
            1 -> telaPs4()
            2 -> telaXbox360()
            3 -> telaSwitch()
            4 -> telaWii()
            5 -> telaPs5()
            else -> telaSeries()
        }.also { raizConsole = it as? FrameLayout }
    }

    /** Onde o foco estava na tela PS4 anterior (a tela é remontada quando a lista de jogos muda). */
    private var estadoPs4: InicioPs4.Estado? = null

    /** A tela inicial do PS4 na tela agora (o relógio e o aviso de cima são redesenhados a cada minuto). */
    private var inicioPs4: InicioPs4? = null

    /** Cards escondidos atrás da tela PS4: o menu de opções de um jogo precisa de um card montado para se ancorar. */
    private var escondidosPs4: FrameLayout? = null

    private fun guardarEscondido(v: View): View {
        val caixa = escondidosPs4 ?: return v
        caixa.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        caixa.addView(v, FrameLayout.LayoutParams(px(10f), px(10f)))
        return v
    }

    private fun nomeExibido(id: String, padrao: String) = personalizado.getString("nome:$id", null) ?: padrao

    /** Um jogo (pelo id do card) como item das telas PS4. Abrir, opções e informações usam o card de verdade. */
    private fun itemJogoPs4(id: String, nome: String, capa: String?, detalhe: String? = null): InicioPs4.Item {
        val r = Historico.registro(this, id)
        val plataforma = plataformaDoId(id)
        return InicioPs4.Item(nome, if (temaQuadrado()) capaQuadrada(id, nome, capa) else capa, null, Paleta.AZUL, null,
            detalhe ?: r?.let { "Jogado " + Historico.quandoLegivel(it.ultimaVez) } ?: plataforma, true,
            { cardPorId(id)?.performClick() },
            { cardPorId(id)?.let { guardarEscondido(it).performLongClick() } },
            { cardPorId(id)?.let { c -> guardarEscondido(c); infoJogo[c]?.invoke() } },
            plataforma, r?.ultimaVez ?: 0L, id)
    }

    private fun itemAppPs4(app: AppInfo): InicioPs4.Item = InicioPs4.Item(nomeExibido(idApp(app), app.nome), arteApp(app), app.icone, app.cor, null,
        rotuloCategoria(app), false, { abrirApp(app) },
        {
            val card = cardGrande(app.nome, rotuloCategoria(app), app.icone, app.cor, null, { abrirApp(app) }, { v -> opcoes(v, app) }, idApp(app))
            guardarEscondido(card).performLongClick()
        }, null, rotuloCategoria(app), Historico.registro(this, idApp(app))?.ultimaVez ?: 0L)

    private fun sistemaPs4(nome: String, simbolo: Ps4Desenho.Icone, f: () -> Unit) =
        InicioPs4.Item(nome, null, null, Paleta.AZUL, simbolo, "", false, f, null, null)

    /** Todos os jogos da biblioteca: ROMs, jogos Android e atalhos (os escondidos ficam de fora). */
    private fun todosJogosPs4(): List<InicioPs4.Item> {
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val lista = ArrayList<InicioPs4.Item>()
        for (rom in roms) if (!Perfis.escondido(this, idRom(rom))) {
            val id = idRom(rom)
            val nome = nomeExibido(id, rom.nome)
            lista += itemJogoPs4(id, nome, capaDe(id, nome))
        }
        for (app in jogosAndroid(visiveis)) if (!Perfis.escondido(this, idApp(app))) {
            val id = idApp(app)
            val nome = nomeExibido(id, app.nome)
            lista += itemJogoPs4(id, nome, capaDe(id, nome) ?: arteApp(app))
        }
        for (a in atalhos) if (!Perfis.escondido(this, idAtalho(a))) {
            val id = idAtalho(a)
            val nome = nomeExibido(id, a.nome)
            lista += itemJogoPs4(id, nome, capaDe(id, nome))
        }
        return lista
    }

    /** A Biblioteca do estilo PS4 (por cima da tela inicial). */
    private var raizPs4: FrameLayout? = null

    private fun abrirBibliotecaPs4(raiz: FrameLayout, categoria: String? = null, busca: String? = null) {
        // Já aberta: não abre outra por cima.
        for (i in 0 until raiz.childCount) if (raiz.getChildAt(i) is BibliotecaPs4) return
        val jogos = todosJogosPs4()
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val aplicativos = visiveis.filter { !ehJogoAndroid(it) }.map { itemAppPs4(it) }
        val categorias = ArrayList<BibliotecaPs4.Categoria>()
        categorias += BibliotecaPs4.Categoria("Todos", Ps4Desenho.Icone.TODOS, listOf("Jogos" to jogos, "Aplicativos" to aplicativos), grupo = "Este aparelho")
        categorias += BibliotecaPs4.Categoria("Jogos", Ps4Desenho.Icone.JOGOS, listOf("" to jogos))
        categorias += BibliotecaPs4.Categoria("Aplicativos", Ps4Desenho.Icone.APPS, listOf("" to aplicativos))
        val porPlataforma = jogos.groupBy { it.plataforma }.toSortedMap(compareBy { it.lowercase() })
        porPlataforma.entries.forEachIndexed { i, (nome, l) ->
            categorias += BibliotecaPs4.Categoria(nome, Ps4Desenho.Icone.PLATAFORMA, listOf("" to l), grupo = if (i == 0) "Plataformas" else null)
        }
        val recentes = Historico.recentes(this, 30).mapNotNull { r -> jogos.firstOrNull { it.nome == nomeExibido(r.id, r.nome) } }
        if (recentes.isNotEmpty()) categorias.add(3, BibliotecaPs4.Categoria("Jogados", Ps4Desenho.Icone.TROFEUS, listOf("" to recentes)))
        // Busca (como a do console): os resultados viram a primeira categoria do menu, já aberta.
        var inicial = categoria
        if (busca != null) {
            val q = semAcento(busca)
            val achados = (jogos + aplicativos).filter { semAcento(it.nome).contains(q) }
            val nome = "“$busca”"
            categorias.add(0, BibliotecaPs4.Categoria(nome, Ps4Desenho.Icone.BUSCAR,
                listOf("Jogos" to achados.filter { it.ehJogo }, "Aplicativos" to achados.filter { !it.ehJogo }).filter { it.second.isNotEmpty() }.ifEmpty { listOf("" to emptyList()) },
                grupo = "Resultados"))
            categorias[1] = BibliotecaPs4.Categoria(categorias[1].nome, categorias[1].icone, categorias[1].secoes, grupo = "Este aparelho")
            inicial = nome
            if (achados.isEmpty()) Toast.makeText(this, "Nada encontrado para “$busca”", Toast.LENGTH_SHORT).show()
        }
        lateinit var tela: BibliotecaPs4
        tela = BibliotecaPs4(this, categorias, object : BibliotecaPs4.Ouvinte {
            override fun buscar() = pedirTexto("Buscar", busca.orEmpty()) { q ->
                raiz.removeView(tela)
                abrirBibliotecaPs4(raiz, null, q)
            }
            override fun fechar() {
                raiz.removeView(tela)
                Sons.tocar(Sons.Evento.VOLTAR)
                inicioPs4?.requestFocus()
            }
            override fun perfil() = Perfis.atual(this@MainActivity).nome
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        }, inicial)
        tela.animarFundo = prefs.getBoolean("fundo_animado", true)
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        Sons.tocar(Sons.Evento.ABRIR)
        tela.requestFocus()
    }

    /** O botão Home (ou PS) volta à tela inicial: fecha a Biblioteca aberta por cima. */
    private fun fecharBibliotecaPs4() {
        val raiz = raizPs4 ?: return
        for (i in raiz.childCount - 1 downTo 0) if (raiz.getChildAt(i) is BibliotecaPs4) raiz.removeViewAt(i)
        inicioPs4?.requestFocus()
    }

    /** Home também fecha a grade (apps, busca) aberta por cima da tela inicial de console. */
    private fun fecharGradesConsole() {
        val raiz = raizConsole ?: return
        for (i in raiz.childCount - 1 downTo 0) if (raiz.getChildAt(i) is GradeConsole) raiz.removeViewAt(i)
        telaDoConsole()?.requestFocus()
    }

    private fun semAcento(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** "Atividade" (balão do menu de funções): os últimos jogos jogados; escolher abre o jogo. */
    private fun mostrarAtividade() {
        val lista = Historico.recentes(this, 12)
        if (lista.isEmpty()) return Toast.makeText(this, "Nenhuma atividade ainda", Toast.LENGTH_SHORT).show()
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Atividade")
            .setItems(lista.map { "${it.nome}  ·  ${Historico.quandoLegivel(it.ultimaVez)}" + (if (it.totalMs >= 60_000) "  ·  ${Historico.tempoLegivel(it.totalMs)}" else "") }.toTypedArray()) { _, i ->
                cardPorId(lista[i].id)?.performClick()
            }
            .show()
    }

    /**
     * Estilo PS4: desenhada inteira pela [InicioPs4] (e a [BibliotecaPs4] por cima, quando aberta). Os cards de
     * verdade (abrir, opções e informações) só são montados quando usados, escondidos atrás da tela.
     */
    private fun telaPs4(): View {
        val raiz = FrameLayout(this)
        raizPs4 = raiz
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val itens = ArrayList<InicioPs4.Item>()
        packageManager.getLaunchIntentForPackage("com.android.vending")?.let { loja -> itens += sistemaPs4("Loja", Ps4Desenho.Icone.LOJA) { abrir(loja) } }
        for (j in jogosConsole(14)) itens += itemJogoPs4(j.id, j.nome, j.capa, j.detalhe)
        favoritosDe(apps.filter { it.pacote !in ocultos() }).take(10).filter { it.pacote != "com.android.vending" }.forEach { itens += itemAppPs4(it) }
        itens += sistemaPs4("Biblioteca", Ps4Desenho.Icone.BIBLIOTECA) { abrirBibliotecaPs4(raiz) }
        itens += sistemaPs4("Navegador", Ps4Desenho.Icone.NAVEGADOR) { abrir(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))) }
        itens += sistemaPs4("Capturas", Ps4Desenho.Icone.CAPTURAS) { abrir(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)) }
        itens += sistemaPs4("Buscar", Ps4Desenho.Icone.BUSCAR) { mostrar(Tela.BUSCA) }

        val trofeus = xboxPerfil?.gamerscore?.takeIf { it.isNotBlank() } ?: conquistasRecentes.size.takeIf { it > 0 }?.toString()
        val recentes = Historico.recentes(this, 50).count { System.currentTimeMillis() - it.ultimaVez < 7L * 24 * 60 * 60 * 1000 }
        val topo = listOf(
            InicioPs4.Topo(Ps4Desenho.Icone.ATIVIDADE, "Atividade", recentes.takeIf { it > 0 }?.toString()),
            InicioPs4.Topo(Ps4Desenho.Icone.AMIGOS, "Perfis", Perfis.todos(this).size.takeIf { it > 1 }?.toString()),
            InicioPs4.Topo(null, Perfis.atual(this).nome, null),
            InicioPs4.Topo(Ps4Desenho.Icone.TROFEUS, "Troféus", trofeus),
        )
        val funcoes = listOf(
            InicioPs4.Funcao(Ps4Desenho.Icone.ATIVIDADE, "Atividade", recentes.takeIf { it > 0 }?.toString()) { mostrarAtividade() },
            InicioPs4.Funcao(Ps4Desenho.Icone.AMIGOS, "Perfis", Perfis.todos(this).size.takeIf { it > 1 }?.toString()) { escolherPerfil() },
            InicioPs4.Funcao(Ps4Desenho.Icone.MENSAGENS, "Contas e chaves", null) { abrirConfiguracoes("Contas e chaves") },
            InicioPs4.Funcao(Ps4Desenho.Icone.FESTA, "Som", null) {
                getSystemService(android.media.AudioManager::class.java)
                    .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI)
            },
            InicioPs4.Funcao(null, Perfis.atual(this).nome, null) { escolherPerfil() },
            InicioPs4.Funcao(Ps4Desenho.Icone.TROFEUS, "Troféus e estatísticas", trofeus) { mostrarEstatisticas() },
            InicioPs4.Funcao(Ps4Desenho.Icone.CONFIGURACOES, "Configurações", null) { abrirConfiguracoes() },
            InicioPs4.Funcao(Ps4Desenho.Icone.ENERGIA, "Energia", null) { dormirAgora() },
        )
        val tela = InicioPs4(this, itens, topo, funcoes, object : InicioPs4.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, if (item.simbolo != null) Ps4Desenho.AZUL_FUNDO else item.cor)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return if (prefs.getInt("relogio", 0) == 1)
                    String.format(Locale.ROOT, "%d:%02d %s", (c.get(Calendar.HOUR) + 11) % 12 + 1, c.get(Calendar.MINUTE), if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
                else String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun aviso(): String {
                val w = Widgets.texto(this@MainActivity, prefs.getInt("widgets", 0)) { ui.post { inicioPs4?.atualizar() } }
                if (w.isNotEmpty()) return w
                return Historico.recentes(this@MainActivity, 1).firstOrNull()?.let { "▶ Jogado por último: ${it.nome}" }.orEmpty()
            }
            override fun perfil() = Perfis.atual(this@MainActivity).nome
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        })
        tela.animarFundo = prefs.getBoolean("fundo_animado", true)
        estadoPs4?.let { tela.restaurar(it) }
        inicioPs4 = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 222f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 300f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        return raiz
    }

    /** Onde o foco estava na tela Xbox anterior (a tela é remontada quando a lista de jogos muda). */
    private var estadoXbox: InicioXbox.Estado? = null

    /** A tela inicial do Xbox 360 na tela agora. */
    private var inicioXbox: InicioXbox? = null

    /**
     * Estilo Xbox 360 (2011): desenhada inteira pela [InicioXbox]. Categorias: buscar, início, jogos, apps,
     * configurações. Os cards de verdade (opções, informações) são montados escondidos atrás, só quando usados.
     */
    private fun telaXbox360(): View {
        val raiz = FrameLayout(this)
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val B = InicioXbox.Bloco::class
        val recentes = jogosConsole(10).map { itemJogoPs4(it.id, it.nome, it.capa, it.detalhe) }
        val favoritos = favoritosDe(apps.filter { it.pacote !in ocultos() }).map { itemAppPs4(it) }
        fun sistema(nome: String, ic: Ps4Desenho.Icone, f: () -> Unit) = sistemaPs4(nome, ic, f)

        // Início (como o painel de 2011): à esquerda o último jogo e dois blocos verdes (recentes e meus jogos);
        // no meio o destaque grande com dois embaixo; à direita uma coluna de três apps.
        val inicio = ArrayList<InicioXbox.Bloco>()
        recentes.getOrNull(0)?.let { inicio += InicioXbox.Bloco(it, 64f, 140f, 250f, 222f) }
        inicio += InicioXbox.Bloco(sistema("Recentes", Ps4Desenho.Icone.ATIVIDADE) { mostrarAtividade() }, 64f, 370f, 250f, 131f)
        inicio += InicioXbox.Bloco(sistema("Meus jogos", Ps4Desenho.Icone.JOGOS) { inicioXbox?.irPara("jogos") }, 64f, 509f, 250f, 131f)
        inicio += InicioXbox.Bloco(recentes.getOrNull(1) ?: sistema("Todos os jogos", Ps4Desenho.Icone.JOGOS) { inicioXbox?.irPara("jogos") },
            322f, 140f, 562f, 361f)
        recentes.getOrNull(2)?.let { inicio += InicioXbox.Bloco(it, 322f, 509f, 277f, 131f) }
        recentes.getOrNull(3)?.let { inicio += InicioXbox.Bloco(it, 607f, 509f, 277f, 131f) }
        favoritos.getOrNull(0)?.let { inicio += InicioXbox.Bloco(it, 892f, 140f, 250f, 162f) }
        favoritos.getOrNull(1)?.let { inicio += InicioXbox.Bloco(it, 892f, 310f, 250f, 162f) }
        inicio += InicioXbox.Bloco(favoritos.getOrNull(2) ?: sistema("Todos os apps", Ps4Desenho.Icone.APPS) { inicioXbox?.irPara("apps") },
            892f, 480f, 250f, 160f)

        fun grade(itens: List<InicioPs4.Item>): List<InicioXbox.Bloco> = itens.mapIndexed { i, it ->
            InicioXbox.Bloco(it, 64f + (i / 2) * 262f, 150f + (i % 2) * 222f, 250f, 210f)
        }
        val jogos = grade(todosJogosPs4().sortedBy { it.nome.lowercase() })
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val todosApps = grade(visiveis.filter { !ehJogoAndroid(it) }.sortedBy { it.nome.lowercase() }.map { itemAppPs4(it) })
        // Configurações: 4 × 2 blocos verdes iguais, como no console (o último é desligar).
        val config = listOf(
            sistema("Sistema", Ps4Desenho.Icone.CONFIGURACOES) { abrirConfiguracoes() },
            sistema("Perfil", Ps4Desenho.Icone.AMIGOS) { escolherPerfil() },
            sistema("Temas e sons", Ps4Desenho.Icone.FESTA) { abrirConfiguracoes("Temas e sons") },
            sistema("Aparência", Ps4Desenho.Icone.TODOS) { abrirConfiguracoes("Aparência") },
            sistema("Contas", Ps4Desenho.Icone.MENSAGENS) { abrirConfiguracoes("Contas e chaves") },
            sistema("Backup", Ps4Desenho.Icone.BIBLIOTECA) { abrirConfiguracoes("Backup") },
            sistema("Estatísticas", Ps4Desenho.Icone.TROFEUS) { mostrarEstatisticas() },
            sistema("Dormir", Ps4Desenho.Icone.ENERGIA) { dormirAgora() },
        ).mapIndexed { i, it -> InicioXbox.Bloco(it, 64f + (i % 4) * 264f, 140f + (i / 4) * 254f, 256f, 246f) }
        val hubs = listOf(
            InicioXbox.Hub("buscar", emptyList()) { mostrar(Tela.BUSCA) },
            InicioXbox.Hub("início", inicio),
            InicioXbox.Hub("jogos", jogos),
            InicioXbox.Hub("apps", todosApps),
            InicioXbox.Hub("configurações", config),
        )
        val tela = InicioXbox(this, hubs, object : InicioXbox.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, if (item.simbolo != null) InicioXbox.VERDE else item.cor)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun perfil() = xboxPerfil?.gamertag ?: Perfis.atual(this@MainActivity).nome
            override fun pontos() = xboxPerfil?.gamerscore?.takeIf { it.isNotBlank() }
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        })
        tela.animarFundo = prefs.getBoolean("fundo_animado", true)
        estadoXbox?.let { tela.restaurar(it) }
        inicioXbox = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 120f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 300f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        @Suppress("UNUSED_VARIABLE") val semUso = B
        return raiz
    }

    /** A tela inicial do Switch na tela agora. */
    private var inicioSwitch: InicioSwitch? = null

    /** Estilo Switch: desenhada inteira pela [InicioSwitch]; os cards de verdade só são montados quando usados. */
    private fun telaSwitch(): View {
        val raiz = FrameLayout(this)
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val recentes = jogosConsole(12).map { itemJogoPs4(it.id, it.nome, it.capa, it.detalhe) }
        val todos = todosJogosPs4().sortedBy { it.nome.lowercase() }
        val botoes = listOf(
            InicioSwitch.Botao(Ps4Desenho.Icone.BIBLIOTECA, "Todos os programas", 0xFFE4002B.toInt()) { inicioSwitch?.abrirTodos() },
            InicioSwitch.Botao(Ps4Desenho.Icone.APPS, "Apps", 0xFFF39800.toInt()) { mostrar(Tela.APPS) },
            InicioSwitch.Botao(Ps4Desenho.Icone.LOJA, "Loja", 0xFFF2652B.toInt()) {
                packageManager.getLaunchIntentForPackage("com.android.vending")?.let { abrir(it) }
            },
            InicioSwitch.Botao(Ps4Desenho.Icone.CAPTURAS, "Álbum", 0xFF2E8BE8.toInt()) {
                abrir(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            },
            InicioSwitch.Botao(Ps4Desenho.Icone.BUSCAR, "Buscar", 0xFF5AB4E6.toInt()) { mostrar(Tela.BUSCA) },
            InicioSwitch.Botao(Ps4Desenho.Icone.TROFEUS, "Estatísticas", 0xFF3CB44B.toInt()) { mostrarEstatisticas() },
            InicioSwitch.Botao(Ps4Desenho.Icone.CONFIGURACOES, "Configurações", 0xFF8C8C8C.toInt()) { abrirConfiguracoes() },
            InicioSwitch.Botao(Ps4Desenho.Icone.ENERGIA, "Dormir", 0xFF8C8C8C.toInt()) { dormirAgora() },
        )
        val lista = Perfis.todos(this)
        val atual = lista.indexOfFirst { it.id == Perfis.atualId(this) }.coerceAtLeast(0)
        val perfis = lista.map { pf -> pf.nome to { if (pf.id != Perfis.atualId(this)) escolherPerfil() } }
        val tela = InicioSwitch(this, recentes, todos, botoes, perfis, atual, object : InicioSwitch.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, InicioSwitch.CIANO)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun bateria() = getSystemService(android.os.BatteryManager::class.java)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        })
        inicioSwitch = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 120f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 300f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        return raiz
    }

    /** Raiz da tela inicial de console (as grades de apps e de busca abrem por cima dela). */
    private var raizConsole: FrameLayout? = null

    private fun telaDoConsole(): View? = inicioPs4 ?: inicioXbox ?: inicioSwitch ?: inicioWii ?: inicioPs5 ?: inicioSeries

    /**
     * Nos estilos de console não há as abas do Android TV: Jogos, Apps e Busca abrem a tela do próprio estilo
     * (a Biblioteca no PS4, as categorias no Xbox 360, "Todos os programas" no Switch, a grade nos outros).
     */
    private fun abrirNoConsole(t: Tela): Boolean {
        val l = layoutInicio()
        val visiveis = apps.filter { it.pacote !in ocultos() }
        when (t) {
            Tela.JOGOS -> {
                mostrar(Tela.INICIO)
                when (l) {
                    1 -> raizPs4?.let { abrirBibliotecaPs4(it, "Jogos") }
                    2 -> inicioXbox?.irPara("jogos")
                    3 -> inicioSwitch?.abrirTodos()
                    else -> abrirGradeConsole(if (l == 6) "Meus jogos e apps" else "Biblioteca de jogos",
                        todosJogosPs4().sortedBy { it.nome.lowercase() } +
                            (if (l == 6) visiveis.filter { !ehJogoAndroid(it) }.sortedBy { it.nome.lowercase() }.map { itemAppPs4(it) } else emptyList()), null)
                }
            }
            Tela.APPS -> {
                mostrar(Tela.INICIO)
                when (l) {
                    1 -> raizPs4?.let { abrirBibliotecaPs4(it, "Aplicativos") }
                    2 -> inicioXbox?.irPara("apps")
                    else -> abrirGradeConsole(if (l == 3) "Todos os apps" else "Apps",
                        visiveis.filter { !ehJogoAndroid(it) }.sortedBy { it.nome.lowercase() }.map { itemAppPs4(it) }, null)
                }
            }
            Tela.BUSCA -> {
                mostrar(Tela.INICIO)
                buscarNoConsole(null)
            }
            else -> return false
        }
        return true
    }

    /** Busca do estilo de console: pede o texto e mostra os jogos e apps que batem (no PS4, dentro da Biblioteca). */
    private fun buscarNoConsole(q: String?) {
        if (q == null) return pedirTexto("Buscar", "") { buscarNoConsole(it) }
        if (layoutInicio() == 1) {
            val raiz = raizPs4 ?: return
            fecharBibliotecaPs4()
            abrirBibliotecaPs4(raiz, null, q)
            return
        }
        val alvo = semAcento(q)
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val achados = (todosJogosPs4() + visiveis.filter { !ehJogoAndroid(it) }.map { itemAppPs4(it) })
            .filter { semAcento(it.nome).contains(alvo) }.distinctBy { it.nome }.sortedBy { it.nome.lowercase() }
        abrirGradeConsole("Buscar", achados, q)
    }

    private fun abrirGradeConsole(titulo: String, itens: List<InicioPs4.Item>, busca: String?) {
        val raiz = raizConsole ?: return
        val vis = Visuais.de(this) ?: return
        for (i in raiz.childCount - 1 downTo 0) if (raiz.getChildAt(i) is GradeConsole) raiz.removeViewAt(i)
        lateinit var g: GradeConsole
        g = GradeConsole(this, vis, titulo, itens, busca, object : GradeConsole.Ouvinte {
            override fun fechar() {
                raiz.removeView(g)
                Sons.tocar(Sons.Evento.VOLTAR)
                telaDoConsole()?.requestFocus()
            }
            override fun buscar(atual: String) = pedirTexto("Buscar", atual) { buscarNoConsole(it) }
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        })
        raiz.addView(g, FrameLayout.LayoutParams(-1, -1))
        Sons.tocar(Sons.Evento.ABRIR)
        g.grade.requestFocus()
    }

    /** Onde o foco estava nas telas PS5 / Xbox Series anteriores (a tela é remontada quando a lista de jogos muda). */
    private var estadoPs5: InicioPs5.Estado? = null
    private var estadoSeries: InicioSeries.Estado? = null
    private var inicioPs5: InicioPs5? = null
    private var inicioSeries: InicioSeries? = null

    /** Arte de fundo grande de um jogo: a escolhida à mão ou a "herói" achada pelo nome (em segundo plano). */
    private fun arteDoItem(item: InicioPs4.Item, pronto: (String) -> Unit) {
        val id = item.id
        if (id.isEmpty()) return
        fundoManual(id)?.let { pronto(it); return }
        if (capaManual(id) != null) return
        val anos = if (id.startsWith("rom:")) roms.firstOrNull { idRom(it) == id }?.sistema?.anos else null
        buscaFundos.execute {
            val heroi = Capas.heroi(this, item.nome, anos) ?: return@execute
            ui.post { pronto(heroi) }
        }
    }

    /** Estilo PS5: abas Jogos e Mídia, fileira de ícones e a arte do selecionado na tela toda. */
    private fun telaPs5(): View {
        val raiz = FrameLayout(this)
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val jogos = ArrayList<InicioPs4.Item>()
        packageManager.getLaunchIntentForPackage("com.android.vending")?.let { loja -> jogos += sistemaPs4("Loja", Ps4Desenho.Icone.LOJA) { abrir(loja) } }
        for (j in jogosConsole(16)) jogos += itemJogoPs4(j.id, j.nome, j.capa, j.detalhe)
        jogos += sistemaPs4("Biblioteca de jogos", Ps4Desenho.Icone.BIBLIOTECA) { mostrar(Tela.JOGOS) }
        val midia = favoritosDe(visiveis).filter { !ehJogoAndroid(it) && it.pacote != "com.android.vending" }.map { itemAppPs4(it) } +
            sistemaPs4("Todos os apps", Ps4Desenho.Icone.APPS) { mostrar(Tela.APPS) }
        val topo = listOf(
            InicioPs5.Atalho(Ps4Desenho.Icone.BUSCAR, "Buscar") { mostrar(Tela.BUSCA) },
            InicioPs5.Atalho(Ps4Desenho.Icone.CONFIGURACOES, "Configurações") { abrirConfiguracoes() },
            InicioPs5.Atalho(null, Perfis.atual(this).nome) { escolherPerfil() },
        )
        val tela = InicioPs5(this, listOf(InicioPs5.Aba("Jogos", jogos), InicioPs5.Aba("Mídia", midia)), topo, object : InicioPs5.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, if (item.simbolo != null) 0xFF2D6CDF.toInt() else item.cor)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun perfil() = Perfis.atual(this@MainActivity).nome
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
            override fun heroi(item: InicioPs4.Item, pronto: (String) -> Unit) = arteDoItem(item, pronto)
        })
        estadoPs5?.let { tela.restaurar(it) }
        inicioPs5 = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 186f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 180f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        return raiz
    }

    /** Estilo Xbox Series: acesso rápido em cima, jogados recentemente e mais fileiras embaixo, arte do jogo atrás. */
    private fun telaSeries(): View {
        val raiz = FrameLayout(this)
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val rapidos = ArrayList<InicioPs4.Item>()
        rapidos += sistemaPs4("Meus jogos e apps", Ps4Desenho.Icone.TODOS) { mostrar(Tela.JOGOS) }
        packageManager.getLaunchIntentForPackage("com.android.vending")?.let { loja -> rapidos += sistemaPs4("Loja", Ps4Desenho.Icone.LOJA) { abrir(loja) } }
        rapidos += sistemaPs4("Buscar", Ps4Desenho.Icone.BUSCAR) { mostrar(Tela.BUSCA) }
        rapidos += sistemaPs4("Perfis", Ps4Desenho.Icone.AMIGOS) { escolherPerfil() }
        rapidos += sistemaPs4("Configurações", Ps4Desenho.Icone.CONFIGURACOES) { abrirConfiguracoes() }
        rapidos += sistemaPs4("Dormir", Ps4Desenho.Icone.ENERGIA) { dormirAgora() }
        val recentes = jogosConsole(12).map { itemJogoPs4(it.id, it.nome, it.capa, it.detalhe) }
        val fixados = favoritosDe(visiveis).map { itemAppPs4(it) }
        val fileiras = listOf(
            InicioSeries.Fileira("Jogados recentemente", recentes),
            InicioSeries.Fileira("Apps fixados", fixados),
            InicioSeries.Fileira("Todos os jogos", todosJogosPs4().sortedBy { it.nome.lowercase() }),
        )
        val tela = InicioSeries(this, rapidos, fileiras, object : InicioSeries.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, if (item.simbolo != null) InicioXbox.VERDE else item.cor)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun perfil() = xboxPerfil?.gamertag ?: Perfis.atual(this@MainActivity).nome
            override fun pontos() = xboxPerfil?.gamerscore?.takeIf { it.isNotBlank() }
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
            override fun heroi(item: InicioPs4.Item, pronto: (String) -> Unit) = arteDoItem(item, pronto)
        })
        estadoSeries?.let { tela.restaurar(it) }
        inicioSeries = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 100f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 420f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        return raiz
    }

    /** A tela inicial do Wii na tela agora. */
    private var inicioWii: InicioWii? = null

    /** Estilo Wii: desenhada inteira pela [InicioWii]; os cards de verdade só são montados quando usados. */
    private fun telaWii(): View {
        val raiz = FrameLayout(this)
        val escondidos = FrameLayout(this).apply { alpha = 0f }
        escondidosPs4 = escondidos
        val recentes = jogosConsole(12).map { itemJogoPs4(it.id, it.nome, it.capa, it.detalhe) }
        val favoritos = favoritosDe(apps.filter { it.pacote !in ocultos() }).map { itemAppPs4(it) }
        val nomes = recentes.map { it.nome }.toHashSet()
        val resto = todosJogosPs4().filter { it.nome !in nomes }.sortedBy { it.nome.lowercase() }
        val canais = recentes + favoritos + resto
        val botoes = listOf(
            InicioSwitch.Botao(Ps4Desenho.Icone.CONFIGURACOES, "Configurações", 0xFF8C959D.toInt()) { abrirConfiguracoes() },
            InicioSwitch.Botao(Ps4Desenho.Icone.AMIGOS, "Perfis", 0xFF34BEED.toInt()) { escolherPerfil() },
            InicioSwitch.Botao(Ps4Desenho.Icone.BUSCAR, "Buscar", 0xFF34BEED.toInt()) { mostrar(Tela.BUSCA) },
            InicioSwitch.Botao(Ps4Desenho.Icone.ENERGIA, "Dormir", 0xFF8C959D.toInt()) { dormirAgora() },
        )
        val tela = InicioWii(this, canais, botoes, object : InicioWii.Ouvinte {
            override fun selecionou(item: InicioPs4.Item) {
                corDoFoco = item.cor
                Controle.aplicarLuz(this@MainActivity, InicioWii.AZUL)
            }
            override fun relogio(): String {
                val c = Calendar.getInstance()
                return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            override fun data(): String = java.text.SimpleDateFormat("EEE d/M", Locale.forLanguageTag("pt-BR")).format(java.util.Date())
                .replace(".", "").replaceFirstChar { it.uppercase() }
            override fun carregar(url: String, w: Int, h: Int, pronto: (Bitmap) -> Unit) = Imagens.carregar(this@MainActivity, url, w, h, inteira = true) { pronto(it) }
        })
        inicioWii = tela
        raiz.addView(escondidos, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.START).apply {
            leftMargin = (resources.displayMetrics.widthPixels * 120f / 1280f).toInt()
            topMargin = (resources.displayMetrics.widthPixels * 200f / 1280f).toInt()
        })
        raiz.addView(tela, FrameLayout.LayoutParams(-1, -1))
        raiz.post { if (telaAtual == Tela.INICIO) tela.requestFocus() }
        return raiz
    }

    /** Fundo claro com listras finas (lembra a tela de canais). */
    private class ListrasClaras(private val cor: Int) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint().apply { color = Color.argb(14, 0, 0, 0) }
        override fun draw(c: android.graphics.Canvas) {
            c.drawColor(cor)
            var y = 0f
            while (y < bounds.height()) {
                c.drawRect(0f, y, bounds.width().toFloat(), y + 2f, p)
                y += 7f
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** Efeito de TV antiga (tema Retrô): linhas de varredura e cantos escuros por cima de tudo. */
    private class EfeitoCrt(ctx: android.content.Context) : View(ctx) {
        private val linhas = android.graphics.Paint().apply { color = Color.argb(34, 0, 0, 0) }
        private var vinheta: android.graphics.Paint? = null
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            vinheta = android.graphics.Paint().apply {
                shader = android.graphics.RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.72f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 0, 0, 0)), floatArrayOf(0f, 0.6f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
            }
        }
        override fun onDraw(c: android.graphics.Canvas) {
            var y = 0f
            while (y < height) {
                c.drawRect(0f, y, width.toFloat(), y + 1.5f, linhas)
                y += 4f
            }
            vinheta?.let { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        }
    }

    // ---------- Seções (fileiras) do Início, Jogos e Apps: a pessoa move e esconde ----------

    /** Uma fileira: [montar] recebe o bloco da seção e a margem acima do título. */
    private class Secao(val id: String, val nome: String, val montar: (LinearLayout, Int) -> Unit)

    /** Por aba: (id, nome) de todas as seções, na ordem salva — inclusive as escondidas. */
    private val secoesAtuais = HashMap<String, List<Pair<String, String>>>()

    // O Início guarda as seções escondidas na chave antiga, para não perder o que já foi escolhido.
    private fun chaveOcultas(aba: String) = if (aba == ABA_INICIO) "secoes_ocultas" else "secoes_ocultas_$aba"
    private fun secoesOcultas(aba: String) = prefs.getStringSet(chaveOcultas(aba), emptySet()).orEmpty()
    private fun telaDaAba(aba: String) = when (aba) {
        ABA_JOGOS -> Tela.JOGOS
        ABA_APPS -> Tela.APPS
        else -> Tela.INICIO
    }
    private fun nomeDaAba(aba: String) = when (aba) {
        ABA_JOGOS -> "Jogos"
        ABA_APPS -> "Apps"
        else -> "Início"
    }

    /** Põe as seções na coluna, na ordem salva, cada uma num bloco próprio (sobe e desce inteira). */
    private fun montarSecoes(col: LinearLayout, aba: String, secoes: List<Secao>) {
        val ordenadas = ordenar("secoes_$aba", secoes) { it.id }
        secoesAtuais[aba] = ordenadas.map { it.id to it.nome }
        val ocultas = secoesOcultas(aba)
        ordenadas.filter { it.id !in ocultas }.forEach { secao ->
            val bloco = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                tag = "secao:$aba|${secao.id}"
            }
            secao.montar(bloco, 0)
            if (bloco.childCount > 0) col.addView(bloco, LinearLayout.LayoutParams(-1, -2))
        }
        ajustarMargensSecoes(col)
    }

    private fun secoesDoInicio(
        visiveis: List<AppInfo>, favoritos: List<AppInfo>, servicos: List<Triple<String, Int, AppInfo>>,
    ): List<Secao> {
        val lista = ArrayList<Secao>()
        lista += Secao(SECAO_FAVORITOS, "Apps favoritos") { col, margem ->
            // Banners maiores que os da TV (300×169 na referência, contra 200×112): no celular a tela é menor.
            val e = escalaApps()
            adicionarFileira(col, "Apps favoritos", favoritos.map { bannerApp(it, px(300f * e), px(169f * e)) }, conteudo = false,
                margemTitulo = margem, padrao = true, ordem = ORDEM_FAVORITOS, larguraBanner = px(300f * e))
        }
        if (servicos.isNotEmpty()) {
            for ((nome, id, app) in servicos) {
                val chave = "servico_$id"
                lista += Secao(chave, "Em alta na $nome") { col, margem ->
                    adicionarFileira(col, "Em alta na $nome", catalogo[id].orEmpty().map { t -> cardTitulo(t, app) },
                        conteudo = true, margemTitulo = margem)
                }
            }
        } else {
            val videos = Catalogo.daCategoria(visiveis, Categoria.VIDEO)
            if (videos.isNotEmpty()) lista += Secao(SECAO_VIDEOS, "Vídeos") { col, margem ->
                adicionarFileira(col, "Vídeos", videos.map { cardApp(it) }, conteudo = true, margemTitulo = margem)
            }
        }
        for ((id, titulo, pacote) in fileirasExtras()) {
            lista += Secao(id, titulo) { col, margem ->
                adicionarFileira(col, titulo, recs[id].orEmpty().map { cardRec(it, pacote) }, conteudo = true, margemTitulo = margem)
            }
        }
        // Todos os jogos numa fileira só: Android, atalhos (GameHub…) e ROMs dos emuladores.
        // Ordem própria do Início (arrastar aqui não mexe na ordem da aba Jogos).
        val android = jogosAndroid(visiveis)
        if (android.isNotEmpty() || atalhos.isNotEmpty() || roms.isNotEmpty()) lista += Secao(SECAO_JOGOS, "Jogos") { col, margem ->
            val itens = ArrayList<Pair<String, () -> View>>()
            android.forEach { app -> itens += idApp(app) to { cardJogo(app) } }
            atalhos.forEach { a ->
                val app = apps.firstOrNull { it.pacote == a.pacote }
                itens += idAtalho(a) to { cardAtalho(a, app?.nome ?: a.pacote, app?.cor ?: Paleta.AZUL, comFundo = false) }
            }
            for ((sistema, doSistema) in roms.groupBy { it.sistema }.toSortedMap()) {
                val (detalhe, iconeEmu) = infoEmulador(sistema)
                doSistema.forEach { rom -> itens += idRom(rom) to { cardRom(rom, detalhe, iconeEmu) } }
            }
            val cards = ordenar(ORDEM_TODOS_JOGOS, itens) { it.first }.map { it.second() }
            adicionarFileira(col, "Jogos", cards, conteudo = true, margemTitulo = margem, ordem = ORDEM_TODOS_JOGOS)
        }
        return lista
    }

    private fun salvarSecoes(aba: String, ordem: List<String>, ocultas: Set<String>) {
        prefs.edit().putString("ordem_secoes_$aba", ordem.joinToString("\n")).putStringSet(chaveOcultas(aba), ocultas).apply()
        descartar(telaDaAba(aba))
        mostrar(telaAtual)
    }

    // ---------- Painel ⋮ à esquerda da fileira: subir, descer, topo, fim, esconder ----------

    private fun blocoDe(v: View): ViewGroup? {
        var x: View? = v
        while (x != null) {
            if ((x.tag as? String)?.startsWith("secao:") == true) return x as ViewGroup
            x = x.parent as? View
        }
        return null
    }

    private fun ajustarMargensSecoes(col: ViewGroup) {
        var primeiro = true
        for (i in 0 until col.childCount) {
            val b = col.getChildAt(i)
            if ((b.tag as? String)?.startsWith("secao:") != true) continue
            (b.layoutParams as LinearLayout.LayoutParams).topMargin = if (primeiro) px(16f) else px(2f)
            primeiro = false
            b.requestLayout()
        }
    }

    /** Blocos de seção vizinhos (para subir e descer). */
    private fun blocoVizinho(b: ViewGroup, delta: Int): ViewGroup? {
        val col = b.parent as? ViewGroup ?: return null
        var i = col.indexOfChild(b) + delta
        while (i in 0 until col.childCount) {
            val c = col.getChildAt(i)
            if ((c.tag as? String)?.startsWith("secao:") == true) return c as ViewGroup
            i += delta
        }
        return null
    }

    /** Se o foco está no primeiro card de uma fileira (ou de uma linha da grade), o bloco da seção. */
    private fun blocoSePrimeiroCard(v: View): ViewGroup? {
        var x: View = v
        while (true) {
            val p = x.parent as? View ?: return null
            if (p is LinearLayout && (p.parent is HorizontalScrollView || p.tag == LINHA_GRADE)) {
                if (p.indexOfChild(x) != 0) return null
                return blocoDe(p)
            }
            x = p
        }
    }

    /** Controle: ◀ no primeiro card abre o painel da seção, como os cabeçalhos do Android TV. */
    private fun teclaSecao(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN || e.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false
        val bloco = currentFocus?.let { blocoSePrimeiroCard(it) } ?: return false
        abrirPainelSecao(bloco)
        return true
    }

    private fun salvarOrdemNaTela(aba: String, col: ViewGroup) {
        val visiveis = (0 until col.childCount).mapNotNull { i ->
            (col.getChildAt(i).tag as? String)?.takeIf { it.startsWith("secao:$aba|") }?.substringAfter('|')
        }
        val antes = secoesAtuais[aba].orEmpty()
        val ordem = visiveis + antes.map { it.first }.filter { it !in visiveis }
        prefs.edit().putString("ordem_secoes_$aba", ordem.joinToString("\n")).apply()
        secoesAtuais[aba] = ordem.mapNotNull { id -> antes.firstOrNull { it.first == id } }
    }

    private var painelSecao: android.widget.PopupWindow? = null

    /**
     * Painel lateral de altura inteira, no estilo dos painéis do Android TV: desliza da esquerda,
     * fundo azul-escuro do launcher, nome da seção no topo e opções em linhas (a em foco vira a
     * pílula branca, como as abas). A fileira desliza para a direita e continua à vista enquanto
     * sobe ou desce. ▶, Voltar ou toque fora fecham e devolvem o foco aos cards.
     */
    private fun abrirPainelSecao(bloco: ViewGroup) {
        painelSecao?.dismiss()
        val (aba, id) = (bloco.tag as String).removePrefix("secao:").split('|', limit = 2).let { it[0] to it[1] }
        val col = bloco.parent as? ViewGroup ?: return
        // Recorte da câmera à esquerda: o painel cresce para cobri-lo e o conteúdo começa depois dele.
        val recorte = window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())?.left ?: 0
        val largura = px(440f) + recorte
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(36f) + recorte, px(56f), px(28f), px(40f))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Paleta.alfa(Paleta.FUNDO, 0xFA), Paleta.painel()))
        }
        val nome = secoesAtuais[aba]?.firstOrNull { it.first == id }?.second ?: ""
        caixa.addView(texto(22f, Paleta.TEXTO_SUAVE, 500).apply { text = "Organizar seção · ${nomeDaAba(aba)}" })
        caixa.addView(texto(38f, Color.WHITE, 400).apply {
            text = nome
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f); bottomMargin = px(30f) })
        val janela = android.widget.PopupWindow(caixa, largura, ViewGroup.LayoutParams.MATCH_PARENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = 24f * s
            animationStyle = 0
            // Vai até a borda da tela, por cima do recorte da câmera (sem a faixa vazia à esquerda).
            isClippingEnabled = false
            setIsLaidOutInScreen(true)
        }
        painelSecao = janela

        val botoes = ArrayList<Pair<View, () -> Boolean>>()
        fun atualizar() {
            botoes.forEach { (b, pode) ->
                b.isEnabled = pode()
                b.alpha = if (b.isEnabled) 1f else 0.35f
            }
            // A opção em foco pode ter acabado de ficar indisponível (ex.: Subir no topo): passa o foco adiante.
            if (!telas.isInTouchMode && botoes.none { it.first.hasFocus() }) botoes.firstOrNull { it.first.isEnabled }?.first?.requestFocus()
        }
        fun depoisDeMover() {
            ajustarMargensSecoes(col)
            salvarOrdemNaTela(aba, col)
            atualizar()
            bloco.post { bloco.requestRectangleOnScreen(android.graphics.Rect(0, 0, bloco.width, bloco.height), true) }
        }
        fun moverPara(alvo: ViewGroup?, depois: Boolean) {
            if (alvo == null || alvo === bloco) return
            col.removeView(bloco)
            col.addView(bloco, col.indexOfChild(alvo) + if (depois) 1 else 0)
            depoisDeMover()
        }
        fun blocos() = (0 until col.childCount).map { col.getChildAt(it) }
            .filter { (it.tag as? String)?.startsWith("secao:") == true }.map { it as ViewGroup }

        /** Linha do painel: ícone + texto; no foco, pílula branca com texto escuro (como as abas). */
        fun botao(icone: String, rotulo: String, pode: () -> Boolean, acao: () -> Unit) {
            val simbolo = texto(28f, Color.WHITE, 500).apply {
                text = icone
                gravity = Gravity.CENTER
            }
            val legenda = texto(28f, Color.WHITE, 400).apply { text = rotulo }
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                defaultFocusHighlightEnabled = false
                setPadding(px(18f), px(18f), px(24f), px(18f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), forma(Color.WHITE, 40f))
                    addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(50, 255, 255, 255), 40f))
                }
                setOnFocusChangeListener { _, f ->
                    val cor = if (f) Paleta.TEXTO_ESCURO else Color.WHITE
                    simbolo.setTextColor(cor)
                    legenda.setTextColor(cor)
                }
                setOnClickListener { if (isEnabled) acao() }
                // ▶ volta para os cards (fecha o painel); ◀ não sai do painel.
                setOnKeyListener { _, codigo, e ->
                    when {
                        codigo == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN -> { janela.dismiss(); true }
                        codigo == KeyEvent.KEYCODE_DPAD_LEFT -> true
                        else -> false
                    }
                }
            }
            linha.addView(simbolo, LinearLayout.LayoutParams(px(46f), -2))
            linha.addView(legenda, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
            caixa.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            botoes += linha to pode
        }
        botao("▲", "Subir", { blocoVizinho(bloco, -1) != null }) { moverPara(blocoVizinho(bloco, -1), depois = false) }
        botao("▼", "Descer", { blocoVizinho(bloco, 1) != null }) { moverPara(blocoVizinho(bloco, 1), depois = true) }
        botao("⤒", "Para o topo", { blocoVizinho(bloco, -1) != null }) { moverPara(blocos().firstOrNull(), depois = false) }
        botao("⤓", "Para o fim", { blocoVizinho(bloco, 1) != null }) { moverPara(blocos().lastOrNull(), depois = true) }
        caixa.addView(View(this).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = px(18f); bottomMargin = px(12f) })
        // Aba Jogos: cada plataforma escolhe banner deitado ou capa original em pé.
        if (aba == ABA_JOGOS && temFormato(id)) {
            val emPe = emPeNaSecao(id)
            botao(if (emPe) "▭" else "▯", if (emPe) "Usar banner (deitado)" else "Usar capa original (em pé)", { true }) {
                janela.dismiss()
                definirFormatoSecao(id, !emPe)
            }
        }
        // Aba Jogos: ordenar a fileira (nome, recentes, mais jogados), filtrar o GeForce NOW, editar coleções.
        val ordenavel = aba == ABA_JOGOS && listOf("rom_", "atalhos_", "nuvem_", "colecao:").any { id.startsWith(it) } || id == "android"
        if (ordenavel) {
            val chave = chaveOrdemDaSecao(id)
            val modo = prefs.getInt("ordem_modo:$chave", 0)
            botao("⇅", "Ordenar: ${MODOS_ORDEM[modo].substringBefore(" (")}", { true }) {
                janela.dismiss()
                android.app.AlertDialog.Builder(this, temaDialogo())
                    .setTitle("Ordenar \"$nome\"")
                    .setSingleChoiceItems(MODOS_ORDEM.toTypedArray(), modo) { d, i ->
                        prefs.edit().putInt("ordem_modo:$chave", i).apply()
                        d.dismiss()
                        remontarJogos()
                    }
                    .show()
            }
        }
        if (id == SECAO_GFN) botao("⚲", "Filtrar por gênero", { true }) {
            janela.dismiss()
            escolherFiltroGfn()
        }
        if (id.startsWith("colecao:")) {
            val colecao = id.removePrefix("colecao:")
            botao("✎", "Renomear coleção", { true }) {
                janela.dismiss()
                pedirNomeColecao(colecao) { remontarJogos() }
            }
            botao("🗑", "Excluir coleção", { true }) {
                janela.dismiss()
                android.app.AlertDialog.Builder(this, temaDialogo())
                    .setTitle("Excluir a coleção \"$colecao\"?")
                    .setMessage("Só a coleção some; os jogos continuam nas outras fileiras.")
                    .setPositiveButton("Excluir") { _, _ -> Colecoes.excluir(this, colecao); remontarJogos() }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        botao("✕", "Esconder seção", { true }) {
            janela.dismiss()
            col.removeView(bloco)
            ajustarMargensSecoes(col)
            salvarOrdemNaTela(aba, col)
            prefs.edit().putStringSet(chaveOcultas(aba), secoesOcultas(aba) + id).apply()
            Toast.makeText(this, "\"$nome\" escondida. Para voltar: engrenagem → Ordem das seções", Toast.LENGTH_LONG).show()
        }
        // A fileira desliza para a direita enquanto o painel está aberto (os cards começam a 107 px).
        val inicioCards = IntArray(2).also { bloco.getLocationOnScreen(it) }[0] + px(107f)
        bloco.animate().translationX((largura + px(24f) - inicioCards).coerceAtLeast(0).toFloat()).setDuration(200).start()
        janela.setOnDismissListener {
            painelSecao = null
            bloco.animate().translationX(0f).setDuration(200).start()
            if (!telas.isInTouchMode) primeiroCard(bloco)?.requestFocus()
        }
        janela.showAtLocation(telas, Gravity.TOP or Gravity.START, 0, 0)
        // Entra deslizando da esquerda, como os painéis do Android TV.
        caixa.translationX = -largura.toFloat()
        caixa.animate().translationX(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        atualizar()
        // Com controle, o foco começa na primeira opção que dá para usar.
        botoes.firstOrNull { it.first.isEnabled }?.first?.requestFocus()
    }

    /** Lista com ▲ ▼ e caixinha "mostrar" para cada seção de uma aba (funciona bem no toque e no controle). */
    private fun organizarSecoes(aba: String) {
        montada(telaDaAba(aba))
        val atuais = secoesAtuais[aba].orEmpty()
        if (atuais.isEmpty()) return
        val itens = atuais.toMutableList()
        val ocultas = secoesOcultas(aba).toMutableSet()
        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(40f), px(10f), px(30f), px(10f))
        }
        fun redesenhar() {
            lista.removeAllViews()
            itens.forEachIndexed { i, (id, nome) ->
                val linha = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                linha.addView(android.widget.CheckBox(this).apply {
                    text = nome
                    isChecked = id !in ocultas
                    setOnCheckedChangeListener { _, marcado -> if (marcado) ocultas.remove(id) else ocultas.add(id) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun seta(simbolo: String, ativa: Boolean, destino: Int) = android.widget.Button(this).apply {
                    text = simbolo
                    isEnabled = ativa
                    setOnClickListener {
                        itens.add(destino, itens.removeAt(i))
                        redesenhar()
                        // Mantém o foco do controle na seta da seção que se moveu.
                        (lista.getChildAt(destino) as? ViewGroup)?.getChildAt(if (simbolo == "▲") 1 else 2)?.requestFocus()
                    }
                }
                linha.addView(seta("▲", i > 0, i - 1), LinearLayout.LayoutParams(px(110f), -2))
                linha.addView(seta("▼", i < itens.lastIndex, i + 1), LinearLayout.LayoutParams(px(110f), -2))
                lista.addView(linha, LinearLayout.LayoutParams(-1, -2))
            }
        }
        redesenhar()
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Seções da aba ${nomeDaAba(aba)}")
            .setView(ScrollView(this).apply { addView(lista) })
            .setPositiveButton("Salvar") { _, _ -> salvarSecoes(aba, itens.map { it.first }, ocultas) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicarDestaque() {
        val d = destaques.getOrNull(indiceDestaque)
        val arte = heroArte
        arte?.definir(d?.icone, d?.cor ?: Paleta.AZUL)
        heroProvedor?.text = d?.provedor.orEmpty()
        heroTitulo?.text = d?.titulo ?: "Carregando…"
        heroSub?.text = d?.sub.orEmpty()
        heroIndicador?.total = destaques.size
        heroIndicador?.atual = indiceDestaque
        val fundo = d?.fundo
        if (arte != null && d != null && fundo != null) {
            arte.post {
                val (w, h) = arte.tamanhoImagem()
                // TMDB manda só o caminho da imagem; o Stremio já manda o endereço inteiro.
                val url = if (fundo.startsWith("http")) fundo else Tmdb.urlImagem(fundo, "w1280")
                Imagens.carregar(this, url, w, h) { b ->
                    if (heroArte === arte && destaques.getOrNull(indiceDestaque) === d) arte.definir(d.icone, d.cor, b)
                }
            }
        }
    }

    private fun proximoDestaque() {
        val arte = heroArte ?: return
        if (destaques.size < 2) return
        val textos = heroTextos
        indiceDestaque = (indiceDestaque + 1) % destaques.size
        textos?.animate()?.alpha(0f)?.setDuration(250)?.start()
        arte.animate().alpha(0f).setDuration(250).withEndAction {
            aplicarDestaque()
            arte.animate().alpha(1f).setDuration(400).start()
            textos?.animate()?.alpha(1f)?.setDuration(400)?.start()
        }.start()
    }

    private fun iniciarDestaque() {
        ui.removeCallbacks(girarDestaque)
        if (naTela && telaAtual == Tela.INICIO && destaques.size > 1) ui.postDelayed(girarDestaque, intervaloDestaque())
    }

    private fun pararDestaque() {
        ui.removeCallbacks(girarDestaque)
        heroArte?.animate()?.cancel()
        heroArte?.alpha = 1f
        heroTextos?.animate()?.cancel()
        heroTextos?.alpha = 1f
    }

    // ---------- Fileiras e cards ----------

    /**
     * Fileira com título em cima e cards rolando para o lado, alinhados a 107 px da borda.
     * Com [ordem], a pessoa reordena os cards (segurar e arrastar, ou "Mover" no controle).
     */
    private fun adicionarFileira(
        destino: LinearLayout, nome: String, itens: List<View>, conteudo: Boolean,
        margemTitulo: Int, padrao: Boolean = false, ordem: String? = null, larguraBanner: Int = px(200f),
    ) {
        if (itens.isEmpty()) return
        val ec = escalaCards()
        val cardW = if (conteudo) px(426f * ec) else larguraBanner
        // Folga em cima para o card crescer no foco (1,25× nos grandes, 1,12× nos banners); capas em pé são mais altas.
        val altMax = itens.maxOfOrNull { (it as? CardTV)?.tamanho?.height ?: 0 } ?: 0
        val folga = if (conteudo) maxOf(px(34f * ec), (altMax * 0.14f).toInt()) else px(14f)
        adicionarTitulo(destino, nome, margemTitulo)
        val rolagem = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            // Sem isso a GPU recorta a fileira na altura dos cards e o card em foco perde as bordas.
            clipChildren = false
            setPadding(px(107f), folga, px(140f), if (conteudo) folga else 0)
        }
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        if (ordem != null) {
            linha.tag = "ordem:$ordem"
            linha.setOnDragListener(ouvinteArraste)
            // Os vizinhos deslizam para abrir espaço enquanto o card é arrastado.
            linha.layoutTransition = LayoutTransition().apply {
                setAnimator(LayoutTransition.APPEARING, null)
                setAnimator(LayoutTransition.DISAPPEARING, null)
                setDuration(160)
            }
        }
        // "Ordenar" da fileira (⋮): manual (arrastar), por nome, recentes ou mais jogados. Ações (não-cards) ficam no fim.
        val ordenados = ordenarCards(ordem, itens)
        ordenados.forEachIndexed { i, v ->
            if (i == 0 && padrao) ((v as? ViewGroup)?.getChildAt(0) ?: v).isFocusedByDefault = true
            val proprio = (v as? CardTV)?.tamanho
            val alt = if (v is CardTV) proprio?.height ?: px(240f * ec) else -2
            linha.addView(v, LinearLayout.LayoutParams(proprio?.width ?: cardW, alt).apply { rightMargin = px(26f) })
        }
        rolagem.addView(linha)
        destino.addView(rolagem, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(19f) - folga })
    }

    /**
     * Título de fileira. Dentro de uma seção (bloco "secao:…"), ganha o botão ⋮ à esquerda:
     * tocar nele (ou no título) abre o painel de subir/descer/esconder.
     */
    private fun adicionarTitulo(destino: LinearLayout, nome: String, margemTitulo: Int) {
        val titulo = texto(26f, Paleta.TEXTO, 500).apply { text = nome }
        val bloco = destino.takeIf { (it.tag as? String)?.startsWith("secao:") == true }
        if (bloco == null) {
            destino.addView(titulo, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = px(107f)
                topMargin = margemTitulo
            })
            return
        }
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val alca = texto(40f, Paleta.TEXTO, 900).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            contentDescription = "Mover ou esconder $nome"
            // No controle o caminho é ◀ no primeiro card; aqui é só para o toque.
            isFocusable = false
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(60, 255, 255, 255), 22f))
            }
            setOnClickListener { abrirPainelSecao(bloco) }
        }
        titulo.isFocusable = false
        titulo.setOnClickListener { abrirPainelSecao(bloco) }
        titulo.setOnLongClickListener { abrirPainelSecao(bloco); true }
        linha.addView(alca, LinearLayout.LayoutParams(px(44f), px(44f)))
        linha.addView(titulo, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(8f) })
        destino.addView(linha, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = px(107f) - px(52f)
            topMargin = margemTitulo - px(9f)
        })
    }

    /** Banner de app (Apps favoritos, grades): arte do SteamGridDB quando houver; nome embaixo no foco, como na TV. */
    private fun bannerApp(app: AppInfo, w: Int = px(200f), h: Int = px(112f)): View {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            tag = idApp(app)
        }
        val rotulo = texto(22f, Paleta.TEXTO, 400).apply {
            text = app.nome
            gravity = Gravity.CENTER
            umaLinha()
            alpha = 0f
        }
        val card = CardTV(this, false, raioCards(), ajusteImagens()).apply {
            definir(app.nome, app.icone, app.cor)
            setOnClickListener { abrirApp(app) }
            setOnLongClickListener { segurar(this) { opcoes(this, app) }; true }
            aoMudarFoco = { foco -> rotulo.animate().alpha(if (foco) 1f else 0f).setDuration(150).start() }
        }
        val manual = capaManual(idApp(app))
        val arte = if (manual != null) manual.ifEmpty { null } else capas[Capas.chaveApp(Catalogo.nomeParaArte(app))]
        when {
            arte != null -> Imagens.carregar(this, arte, w, h, ajusteImagens() != CardTV.Ajuste.CORTAR) { card.definirImagem(it) }
            // Sem arte no SteamGridDB (e sem "Sem capa" escolhido): o banner de TV do próprio app.
            manual == null && usarBannerTv() -> app.banner?.let { card.definirImagem(it) }
        }
        caixa.addView(card, LinearLayout.LayoutParams(w, h))
        caixa.addView(rotulo, LinearLayout.LayoutParams(w, px(28f)).apply { topMargin = px(6f) })
        return caixa
    }

    /**
     * Card grande genérico. No foco cresce 1,25× e empurra os vizinhos para os lados. [imagem]: URL completa.
     * [aoSelecionar]: chamado quando o card ganha o foco (1º toque ou controle).
     */
    private fun cardGrande(
        nome: String, detalhe: String, icone: Bitmap?, cor: Int, imagem: String?,
        aoAbrir: () -> Unit, aoSegurar: ((View) -> Unit)? = null, id: String? = null,
        aoSelecionar: (() -> Unit)? = null,
        /** Capa em pé: largura ÷ altura da caixa da plataforma; o card fica mais alto e com essa proporção. */
        proporcao: Float? = null,
        /** A imagem não é da proporção do card (banner num card em pé): mostra inteira, sem esticar nem cortar. */
        imagemInteira: Boolean = false,
    ): View {
        val ec = escalaCards()
        val h = if (proporcao != null) px(330f * ec) else px(240f * ec)
        val w = if (proporcao != null) (h * proporcao).toInt() else px(426f * ec)
        val ajuste = if (imagemInteira) CardTV.Ajuste.INTEIRA else ajusteImagens()
        val card = CardTV(this, true, raioCards(), ajuste)
        if (proporcao != null) card.tamanho = android.util.Size(w, h)
        card.tag = id
        card.definir(nome, icone, cor, detalhe)
        // No toque: 1º toque seleciona (cresce e mostra a sinopse), 2º abre. No controle: foco e OK.
        card.isFocusableInTouchMode = true
        card.setOnClickListener { aoAbrir() }
        if (aoSegurar != null) card.setOnLongClickListener { segurar(it) { aoSegurar(it) }; true }
        card.aoMudarFoco = { foco ->
            afastarVizinhos(card, foco, w * 0.25f)
            if (foco) {
                aoSelecionar?.invoke()
                // Luz do controle na cor do jogo/plataforma em foco.
                corDoFoco = cor
                Controle.aplicarLuz(this, cor)
            }
            focoTrailer(card, foco)
        }
        // Perfil infantil: jogos marcados "só para adultos" somem (o card existe, mas não aparece nem recebe foco).
        if (Perfis.escondido(this, id)) {
            card.visibility = View.GONE
            card.isFocusable = false
            return card
        }
        if (imagem != null) Imagens.carregar(this, imagem, w, h, ajuste != CardTV.Ajuste.CORTAR) { card.definirImagem(it) }
        return card
    }

    // ---------- Capas em pé (proporção original de cada plataforma) ----------

    /**
     * Formato das capas de uma seção da aba Jogos ("android", "rom_PS2", "atalhos_<pacote>"): o escolhido para ela
     * ou, sem escolha, o padrão das configurações. O Início fica sempre com banners deitados.
     */
    private fun emPeNaSecao(secao: String) =
        prefs.getInt("formato:$secao", -1).let { if (it == -1) prefs.getInt("formato_capas", 0) == 1 else it == 1 }

    private fun definirFormatoSecao(secao: String, emPe: Boolean) {
        prefs.edit().putInt("formato:$secao", if (emPe) 1 else 0).apply()
        descartar(Tela.JOGOS)
        if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
        carregarCapas()
    }

    /** Seções da aba Jogos que têm capas de jogo (as de ações, como "Escolher jogos", não). */
    private fun temFormato(secao: String) = secao == "android" || secao.startsWith("rom_") || secao.startsWith("atalhos_")

    /** Alguma seção usa capa em pé (para buscar essas capas). */
    private fun algumaEmPe() = prefs.getInt("formato_capas", 0) == 1 || prefs.all.any { (k, v) -> k.startsWith("formato:") && v == 1 }

    /** Plataformas da aba Jogos, para a opção de formato por plataforma nas configurações. */
    private fun secoesDeJogos(): List<Pair<String, String>> = buildList {
        if (jogosAndroid(apps).isNotEmpty()) add("android" to "Jogos Android")
        roms.map { it.sistema }.distinct().forEach { add("rom_${it.name}" to it.nome) }
        atalhos.map { it.pacote }.distinct().forEach { p -> add("atalhos_$p" to "Jogos do ${apps.firstOrNull { it.pacote == p }?.nome ?: p}") }
    }

    /** Largura ÷ altura da caixa de cada plataforma (frente da capa); jogos Android/PC usam a da Steam (600×900). */
    private fun proporcaoCapa(sistema: Sistema?): Float = when (sistema) {
        null -> 2f / 3f
        Sistema.PS2, Sistema.GC, Sistema.MD -> 0.70f
        Sistema.PS1, Sistema.GB, Sistema.GBA -> 1.0f
        Sistema.PSP -> 0.57f
        Sistema.SWITCH -> 0.62f
        Sistema.NDS, Sistema.N3DS -> 0.88f
        Sistema.NES -> 0.73f
        Sistema.SNES, Sistema.N64 -> 1.38f
        Sistema.PS3, Sistema.PS4 -> 0.86f
        Sistema.VITA -> 0.85f
        Sistema.X360 -> 0.71f
        Sistema.WIIU -> 0.72f
        Sistema.DC -> 1.0f
    }

    /**
     * Capa em pé do jogo e se ela é mesmo em pé (senão é o banner, mostrado inteiro): a em pé escolhida à mão,
     * a automática, e só então o banner (o escolhido à mão no modo banner, ou o automático).
     */
    private fun capaEmPe(id: String, nome: String): Pair<String?, Boolean> {
        personalizado.getString("capaEmPe:$id", null)?.let { return it.ifEmpty { null } to true }
        capas[Capas.chavePoster(nome)]?.let { return it to true }
        return capaDe(id, nome) to false
    }

    /** Onde "Trocar capa" guarda a escolha: cada formato (banner ou em pé) tem a sua. */
    private fun chaveCapa(id: String, emPe: Boolean) = if (emPe) "capaEmPe:$id" else "capa:$id"

    /** O card está em pé (capa original) — vale para "Trocar capa" e "Usar foto da galeria" desse card. */
    private fun cardEmPe(v: View) = (v as? CardTV)?.tamanho != null

    private fun cardApp(app: AppInfo) = cardGrande(app.nome, rotuloCategoria(app), app.icone, app.cor, null,
        { abrirApp(app) }, { v -> opcoes(v, app) }, idApp(app))

    /** Jogo Android: capa escolhida pela pessoa ou, se não houver, a automática do SteamGridDB. */
    private fun cardJogo(app: AppInfo, comFundo: Boolean = false, emPeSecao: Boolean = false): View {
        val capa = capaDe(idApp(app), app.nome)
        val emPe = if (emPeSecao) capaEmPe(idApp(app), app.nome) else null
        val card = cardGrande(app.nome, "Jogo Android", app.icone, app.cor, emPe?.first ?: capa,
            { abrirJogo(app.pacote, idApp(app) to app.nome) { abrirApp(app) } }, { v -> opcoes(v, app) },
            idApp(app), if (comFundo) ({ selecionarJogo(capa, app.nome, null, idApp(app)) }) else null,
            proporcao = emPe?.let { proporcaoCapa(null) }, imagemInteira = emPe?.second == false)
        // Sem capa no SteamGridDB: o banner de TV do próprio jogo, se ele tiver.
        if (capa == null && capaManual(idApp(app)) == null && usarBannerTv()) app.banner?.let { (card as CardTV).definirImagem(it) }
        infoJogo[card] = { abrirInfoJogo(app.nome, null, capa, listOf("No celular" to "Jogo Android · ${app.pacote}") + infoTempo(idApp(app)), acoesApp(card, app), fundoManual(idApp(app))) { abrirJogo(app.pacote, idApp(app) to app.nome) { abrirApp(app) } } }
        trailerDe[card] = app.nome to null
        return card
    }

    private fun cardRom(rom: Rom, detalhe: String, iconeEmu: Bitmap?, comFundo: Boolean = false, emPeSecao: Boolean = false): View {
        val capa = capaDe(idRom(rom), rom.nome)
        val emPe = if (emPeSecao) capaEmPe(idRom(rom), rom.nome) else null
        return cardGrande(rom.nome, detalhe, iconeEmu, rom.sistema.cor, emPe?.first ?: capa, { abrirRom(rom) }, { v -> opcoesRom(v, rom) },
            idRom(rom), if (comFundo) ({ selecionarJogo(capa, rom.nome, rom.sistema.anos, idRom(rom)) }) else null,
            proporcao = emPe?.let { proporcaoCapa(rom.sistema) }, imagemInteira = emPe?.second == false)
            .also {
                infoJogo[it] = { abrirInfoJogo(rom.nome, rom.sistema.anos, capa, listOf("Sua plataforma" to rom.sistema.nome) + infoArquivo(rom) + infoTempo(idRom(rom)), acoesRom(it, rom), fundoManual(idRom(rom))) { abrirRom(rom) } }
                trailerDe[it] = rom.nome to rom.sistema.anos
            }
    }

    // ---------- Trailer automático no fundo (1 s de foco num jogo) ----------

    private var fundoVideo: FundoVideo? = null
    /** Card de jogo → (nome, época do console) para achar o trailer. */
    private val trailerDe = java.util.WeakHashMap<View, Pair<String, IntRange?>>()
    private var cardTrailer: View? = null
    private var pedidoTrailer = 0
    private val buscaTrailer = Executors.newSingleThreadExecutor()
    private val tocarTrailerFundo = Runnable { iniciarTrailerFundo() }

    // Modo imersivo: interface some após alguns segundos parados no trailer (0 = desligado).
    private var modoImersivo = false
    private val IMERSIVOS_SEG = intArrayOf(5, 10, 15, 30, 60, 0)
    private val ativarImersivo = Runnable { entrarModoImersivo() }

    /** Agenda (ou reagenda) a entrada no modo imersivo conforme o tempo escolhido nas configurações. */
    private fun agendarImersivo() {
        ui.removeCallbacks(ativarImersivo)
        val seg = prefs.getInt("imersivo_seg", 10)
        if (seg > 0) ui.postDelayed(ativarImersivo, seg * 1000L)
    }
    private var barraAtual: View? = null
    private var conteudoAtual: View? = null

    private fun trailersPermitidos(): Boolean = when (prefs.getInt("trailers_auto", 0)) {
        2 -> false
        1 -> try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
        else -> true
    }

    /** Filme/série (ou vídeo) com trailer no YouTube: o id direto, ou a referência do TMDB para buscar. */
    private class FonteFilme(val nome: String, val sinopse: String, val provedor: String, val youtube: String?, val tmdb: String?)

    private val filmeDe = java.util.WeakHashMap<View, FonteFilme>()

    private fun temTrailer(v: View) = trailerDe[v] != null || filmeDe[v] != null

    private fun focoTrailer(card: View, foco: Boolean) {
        ui.removeCallbacks(tocarTrailerFundo)
        if (foco && temTrailer(card) && trailersPermitidos()) {
            if (card !== cardTrailer) pararTrailerFundo()
            cardTrailer = card
            ui.postDelayed(tocarTrailerFundo, 1000)
        } else if (!foco && card === cardTrailer) {
            // Só para se o foco não foi para outro card com trailer (aí o outro assume).
            ui.post { if (cardTrailer === card && currentFocus?.let { temTrailer(it) } != true) pararTrailerFundo() }
        }
    }

    /**
     * Jogo: trailer da Steam (vídeo baixado) ou, sem ele, o do YouTube (busca com a chave do YouTube).
     * Filme/série: trailer do YouTube (Stremio já traz o id; senão, a lista oficial do TMDB).
     */
    private fun iniciarTrailerFundo() {
        val card = cardTrailer ?: return
        val jogo = trailerDe[card]
        val filme = filmeDe[card]
        if (jogo == null && filme == null) return
        val pedido = ++pedidoTrailer
        buscaTrailer.execute {
            var dados: DadosJogo? = null
            var arquivo: java.io.File? = null
            var audio: java.io.File? = null
            var youtube: String? = null
            if (jogo != null) {
                dados = InfoJogos.buscar(this, jogo.first, jogo.second)
                arquivo = dados?.trailer?.let { InfoJogos.prepararTrailer(this, it) }
                // Som do trailer (arquivo separado na Steam), se o som estiver ligado.
                if (arquivo != null && SomTrailer.ligado(this)) audio = dados?.trailer?.let { InfoJogos.prepararAudio(this, it) }
                // Sem trailer da Steam: busca no YouTube (com a época do console, para não pegar o jogo novo de mesmo nome).
                if (arquivo == null) youtube = try { TrailersYoutube.deJogo(this, jogo.first, jogo.second) } catch (_: Exception) { null }
            } else if (filme != null) {
                // Idioma do aparelho primeiro; o trailer do Stremio (em inglês) só se não houver outro.
                youtube = TrailersYoutube.deFilme(this, filme.nome, filme.tmdb, filme.youtube)
            }
            ui.post {
                if (pedido != pedidoTrailer || cardTrailer !== card || !naTela || !card.hasFocus() && !card.isFocused) return@post
                when {
                    arquivo != null -> fundoVideo?.tocar(arquivo, audio)
                    // Filmes: amplia 1,33× para tirar as tarjas pretas de cinema; jogos ficam no tamanho certo.
                    // "mp4:" = vídeo direto (trailers da Apple); o resto é id do YouTube.
                    youtube?.startsWith("mp4:") == true -> fundoVideo?.tocarUrl(youtube.removePrefix("mp4:"))
                    youtube != null -> fundoVideo?.tocarYoutube(youtube, if (filme != null) 1.33f else 1f) { if (cardTrailer === card) pararTrailerFundo() }
                    else -> return@post
                }
                // Inicia o timer do modo imersivo após o trailer começar.
                agendarImersivo()
                // Na aba Jogos a arte do jogo fica por cima do fundo: some para o trailer aparecer.
                if (telaAtual == Tela.JOGOS) fundoJogos?.animate()?.alpha(0f)?.setDuration(500)?.start()
                // No Início a arte do destaque (outro título) some e o espaço passa a mostrar este título.
                if (telaAtual == Tela.INICIO) {
                    pararDestaque()
                    heroArte?.animate()?.alpha(0f)?.setDuration(500)?.start()
                    heroIndicador?.animate()?.alpha(0f)?.setDuration(500)?.start()
                    val d = dados
                    when {
                        jogo != null && d != null -> mostrarJogoNoDestaque(jogo.first, d)
                        jogo != null -> mostrarNoDestaque("Jogo", jogo.first, "", null)
                        filme != null -> mostrarNoDestaque(filme.provedor, filme.nome, primeiraFrase(filme.sinopse), null)
                    }
                }
            }
        }
    }

    private fun pararTrailerFundo() {
        ui.removeCallbacks(tocarTrailerFundo)
        ui.removeCallbacks(ativarImersivo)
        sairModoImersivo()
        pedidoTrailer++
        cardTrailer = null
        if (fundoVideo?.tocando == true) {
            fundoVideo?.parar()
            heroArte?.animate()?.alpha(1f)?.setDuration(300)?.start()
            heroIndicador?.animate()?.alpha(1f)?.setDuration(300)?.start()
            if (destaqueComJogo) voltarDestaqueDeFilmes()
            iniciarDestaque()
        }
        fundoJogos?.animate()?.alpha(1f)?.setDuration(300)?.start()
    }

    /** Entra no modo imersivo: interface some deixando só o trailer em tela cheia. */
    private fun entrarModoImersivo() {
        if (modoImersivo || fundoVideo?.tocando != true) return
        modoImersivo = true
        // Faz fade out de toda a interface e tira o véu do trailer.
        telas.animate().alpha(0f).setDuration(800).start()
        fundoVideo?.imersivo(true)
    }

    /** Sai do modo imersivo: interface volta. */
    private fun sairModoImersivo() {
        if (!modoImersivo) return
        modoImersivo = false
        ui.removeCallbacks(ativarImersivo)
        // Faz fade in de volta.
        telas.animate().alpha(1f).setDuration(400).start()
        fundoVideo?.imersivo(false)
    }

    /** Tecla que tirou do modo imersivo: ela e suas repetições não fazem mais nada até soltar. */
    private var teclaImersiva = -1

    /** Analógico inclinado além disso conta como mexer no controle (abaixo é folga do próprio controle). */
    private fun analogicoMexeu(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        return listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y).any { abs(e.getAxisValue(it)) > 0.5f }
    }

    /** Analógico que tirou do modo imersivo: não move o foco até voltar ao centro. */
    private var analogicoImersivo = false

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val mexeu = analogicoMexeu(e)
        if (analogicoImersivo) {
            if (!mexeu) analogicoImersivo = false
            return true
        }
        if (mexeu) {
            analogicoImersivo = modoImersivo
            movimentoControle()
            if (analogicoImersivo) return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    /** Detecta movimento do controle para sair do modo imersivo e reiniciar timer. */
    private fun movimentoControle() {
        if (modoImersivo) {
            sairModoImersivo()
        }
        // Reinicia o timer do modo imersivo se há trailer tocando.
        if (fundoVideo?.tocando == true && trailersPermitidos()) agendarImersivo()
    }

    /**
     * Troca os textos do destaque pelos do jogo do trailer: selos (Metacritic, idade…) no lugar do
     * serviço, o logo do jogo (ou o nome) e a ficha curta / começo da sinopse.
     */
    private fun mostrarJogoNoDestaque(nome: String, d: DadosJogo) = mostrarNoDestaque(
        (listOf("Jogo") + d.selos.take(3)).joinToString("  ·  "), nome, d.linha.ifBlank { primeiraFrase(d.sinopse) }, d.logo)

    /** Troca os textos do destaque (linha de cima, título ou logo, linha de baixo) pelos do trailer em foco. */
    private fun mostrarNoDestaque(linha1: String, nome: String, sub: String, logoUrl: String?) {
        val textos = heroTextos ?: return
        destaqueComJogo = true
        textos.animate().alpha(0f).setDuration(200).withEndAction {
            heroProvedor?.text = linha1
            heroTitulo?.text = nome
            heroSub?.text = sub
            heroLogo?.visibility = View.GONE
            heroTitulo?.visibility = View.VISIBLE
            logoUrl?.let { url ->
                val logo = heroLogo ?: return@let
                Imagens.carregar(this, url, px(420f), px(88f), inteira = true) { b ->
                    if (!destaqueComJogo) return@carregar
                    logo.setImageBitmap(b)
                    logo.visibility = View.VISIBLE
                    heroTitulo?.visibility = View.GONE
                }
            }
            textos.animate().alpha(1f).setDuration(350).start()
        }.start()
    }

    private fun voltarDestaqueDeFilmes() {
        destaqueComJogo = false
        val textos = heroTextos ?: return
        textos.animate().alpha(0f).setDuration(150).withEndAction {
            heroLogo?.visibility = View.GONE
            heroTitulo?.visibility = View.VISIBLE
            aplicarDestaque()
            textos.animate().alpha(1f).setDuration(300).start()
        }.start()
    }

    // ---------- Informações do jogo (Quadrado no controle, ou "Informações do jogo" no menu) ----------

    /** Card de jogo → abre a tela de informações dele. */
    private val infoJogo = java.util.WeakHashMap<View, () -> Unit>()

    /** [acoes]: as mesmas opções do menu do card, no botão "Editar jogo"; [fundo]: arte de fundo escolhida à mão. */
    private fun abrirInfoJogo(nome: String, anos: IntRange?, capa: String?, extras: List<Pair<String, String>>,
                              acoes: List<Acao> = emptyList(), fundo: String? = null, jogar: () -> Unit) {
        pararDestaque()
        pararTrailerFundo()
        TelaInfoJogo(this, s, nome, anos, capa, jogar, pacoteYoutube(), extras,
            acoes.filter { it.naInfo }.map { it.rotulo to it.fazer }, fundo).mostrar()
    }

    // ---------- Fundo do app (paisagens, foto ou azul) e menu da engrenagem ----------

    private var fundoApp: FundoImagem? = null
    private var paisagens: List<Paisagem> = emptyList()

    /** Chama [usar] com a lista de paisagens (do cache na hora, ou depois de buscar). */
    private fun comPaisagens(usar: (List<Paisagem>) -> Unit) {
        if (paisagens.isNotEmpty()) return usar(paisagens)
        trabalho.execute {
            val l = Paisagens.lista(this)
            ui.post {
                if (isDestroyed || l.isEmpty()) return@post
                paisagens = l
                usar(l)
            }
        }
    }

    private fun aplicarFundoApp(trocar: Boolean) {
        val fundo = fundoApp ?: return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val escolha = prefs.getString("fundo", "padrao").orEmpty()
        when {
            escolha == "paisagens" -> comPaisagens { lista ->
                var i = prefs.getInt("paisagem_i", 0)
                if (trocar) {
                    i = (i + 1) % lista.size
                    prefs.edit().putInt("paisagem_i", i).putLong("paisagem_quando", System.currentTimeMillis()).apply()
                }
                val p = lista[i % lista.size]
                creditoFundo = p.credito
                Imagens.carregar(this, p.url, w, h) { b -> if (fundoApp === fundo) fundo.mostrar(b, p.url, 800) }
            }
            escolha.startsWith("foto:") -> {
                val url = escolha.removePrefix("foto:")
                creditoFundo = null
                Imagens.carregar(this, url, w, h) { b -> if (fundoApp === fundo) fundo.mostrar(b, url, 800) }
            }
            else -> {
                creditoFundo = null
                fundo.limpar()
            }
        }
    }

    private var creditoFundo: String? = null
        set(v) {
            field = v
            mostrarCreditoFundo()
        }
    private var rodapeCredito: TextView? = null

    private fun mostrarCreditoFundo() {
        val t = rodapeCredito ?: return
        val c = creditoFundo
        t.text = if (c != null) "Fundo: $c" else ""
        t.visibility = if (c != null) View.VISIBLE else View.GONE
    }

    /** Aplica um tema pronto ([Paleta.TEMAS]) e remonta a interface (as configurações reabrem em "Temas e sons"). */
    private fun aplicarTema(i: Int) {
        val t = Paleta.TEMAS[i]
        prefs.edit().putInt("tema", i).putInt("cor_fundo", t.fundo).putInt("cor_destaque", t.destaque).putInt("sons_pacote", t.som)
            .putInt("raio_cards", t.raio).putInt("fonte", t.fonte).putInt("zoom_foco", t.zoom).putBoolean("fundo_animado", t.animado)
            .putInt("layout_inicio", t.layout).putBoolean("efeito_crt", t.crt)
            .apply()
        if (prefs.getInt("sons_volume", 0) == 0) prefs.edit().putInt("sons_volume", 2).apply()
        Paleta.definirFundo(this, t.fundo)
        Paleta.definirDestaque(this, t.destaque)
        Paleta.definirFonte(this)
        Paleta.definirZoom(this)
        Sons.amostra(this)
        painelAberto?.fechar()
        descartarTodas()
        construirUi()
        carregarCapas()
        ui.postDelayed({ abrirConfiguracoes("Temas e sons") }, 300)
    }

    private fun mudarFundo(escolha: String) {
        prefs.edit().putString("fundo", escolha).apply()
        aplicarFundoApp(trocar = escolha == "paisagens")
    }

    // ---------- Configurações do GameLauncherTV (engrenagem) ----------

    private val ESCALAS_APPS = floatArrayOf(0.8f, 1f, 1.2f, 1.4f)
    private val ESCALAS_CARDS = floatArrayOf(0.85f, 1f, 1.2f)
    private val INTERVALOS = longArrayOf(5_000L, 8_000L, 15_000L)
    private val OCIOS_MIN = intArrayOf(0, 1, 5, 10, 15)

    // Raio das bordas dos cards (na altura de referência de 1127 px): reto, pouco (padrão), médio, muito, máximo.
    private val RAIOS = floatArrayOf(0f, 6f, 14f, 24f, 38f)
    private fun usarBannerTv() = prefs.getBoolean("banner_tv", true)
    private fun ajusteImagens() = CardTV.Ajuste.entries[prefs.getInt("ajuste_img", 0).coerceIn(0, 2)]
    private fun raioCards() = RAIOS[prefs.getInt("raio_cards", 1).coerceIn(0, RAIOS.lastIndex)] * s

    private fun escalaApps() = ESCALAS_APPS[prefs.getInt("tam_apps", 1).coerceIn(0, ESCALAS_APPS.lastIndex)]
    private fun escalaCards() = ESCALAS_CARDS[prefs.getInt("tam_cards", 1).coerceIn(0, ESCALAS_CARDS.lastIndex)]
    private fun intervaloDestaque() = INTERVALOS[prefs.getInt("intervalo_destaque", 1).coerceIn(0, INTERVALOS.lastIndex)]
    private fun tempoOcio() = prefs.getInt("ocio_min", 5) * 60_000L

    /** Muda um ajuste de aparência e remonta as telas na hora (aparece atrás do painel). */
    private fun ajustar(chave: String, valor: Int) {
        prefs.edit().putInt(chave, valor).apply()
        descartarTodas()
        mostrar(telaAtual)
    }

    /** Painel do controle (ícone do controle na barra do topo): bateria, luz e vibração. */
    private fun abrirControle() {
        val p = PainelConfig(this, s, "Controle", paginas = false)
        val controles = Controle.conectados()
        p.nota(if (controles.isEmpty()) "Nenhum controle conectado. Conecte um (Bluetooth ou cabo) para ver as opções de luz e vibração."
            else "Conectado: " + Controle.nomes().joinToString(", ") + (controleConectado()?.batteryState?.takeIf { it.isPresent }
                ?.capacity?.takeIf { !it.isNaN() }?.let { " · bateria ${(it * 100).toInt()}%" }.orEmpty()))
        if (Controle.temLuz()) {
            p.secao("Luz")
            p.opcoes("Luz do controle", "A barra de luz (DualShock 4, DualSense…). \"Cor do jogo em foco\" muda conforme a plataforma ou o app selecionado.",
                Controle.LUZES, prefs.getInt("luz_controle", 0)) {
                prefs.edit().putInt("luz_controle", it).apply()
                Controle.controlesMudaram()
                Controle.aplicarLuz(this, corDoFoco)
            }
            p.opcoes("Brilho da luz", null, listOf("100%", "50%", "20%"), prefs.getInt("brilho_controle", 0)) {
                prefs.edit().putInt("brilho_controle", it).apply()
                Controle.controlesMudaram()
                Controle.aplicarLuz(this, corDoFoco)
            }
        } else if (controles.isNotEmpty()) {
            p.nota("Este controle não deixa o Android mudar a luz (o DualShock 4 e o DualSense deixam).")
        }
        if (Controle.temVibracao()) {
            p.secao("Vibração")
            p.opcoes("Vibrar ao abrir um jogo", "Uma vibração curta no controle quando um jogo começa a abrir.",
                listOf("Ligado", "Desligado"), if (prefs.getBoolean("vibrar_abrir", false)) 0 else 1) {
                prefs.edit().putBoolean("vibrar_abrir", it == 0).apply()
            }
            p.acao("Testar vibração", null, "Vibrar") { Controle.vibrar(forte = true) }
        }
        secaoMenuRapido(p)
        p.secao("Atalhos")
        p.nota("R1 / L1: próxima aba / aba anterior (Início, Jogos, Apps)\n" +
            "R2 / L2: pula $PULO_CARDS cards para a direita / esquerda (segure para ir rápido)\n" +
            "Start (Options): configurações   ·   Select (Share): pesquisar\n" +
            "Quadrado / X: informações do jogo   ·   Triângulo / Y: opções do card\n" +
            "◀ no primeiro card de uma fileira: subir, descer ou esconder a fileira")
        p.mostrar()
    }

    /** [secao]: já abre dentro dessa pasta (ao reabrir depois de mudar algo nela). */
    /** Tema das janelas de escolha: azul-escuro no estilo PS4, o do Android nos outros. */
    private fun temaDialogo() = when (layoutInicio()) {
        1 -> R.style.DialogoPs4
        2 -> R.style.DialogoXbox
        3 -> R.style.DialogoSwitch
        4 -> R.style.DialogoWii
        5 -> R.style.DialogoPs5
        6 -> R.style.DialogoSeries
        else -> android.R.style.Theme_DeviceDefault_Dialog_Alert
    }

    private var painelAberto: PainelConfig? = null
    /** PIN já digitado nesta visita (vale até o launcher sair da tela). */
    private var pinOk = false

    /** Pede o PIN do controle parental; [ok] roda se acertar. Sem PIN criado, roda direto. */
    private fun pedirPin(titulo: String, ok: () -> Unit) {
        val pin = Perfis.pin(this) ?: return ok()
        val campo = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setTextColor(Color.WHITE)
        }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle(titulo)
            .setMessage("Digite o PIN do controle parental.")
            .setView(campo)
            .setPositiveButton("OK") { _, _ ->
                if (campo.text.toString() == pin) ok() else Toast.makeText(this, "PIN errado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    /** Pede um texto (nome do perfil, PIN novo). */
    private fun pedirTexto(titulo: String, inicial: String, numerico: Boolean = false, ok: (String) -> Unit) {
        val campo = EditText(this).apply {
            setText(inicial)
            setSelection(inicial.length)
            setTextColor(if (layoutInicio() in 2..4) 0xFF2D2D2D.toInt() else Color.WHITE)
            if (numerico) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle(titulo)
            .setView(campo)
            .setPositiveButton("OK") { _, _ -> campo.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    /** "Quem está jogando?": escolhe o perfil (trocar reabre o launcher). */
    private fun escolherPerfil() {
        val lista = Perfis.todos(this)
        val atual = Perfis.atualId(this)
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Quem está jogando?")
            .setItems(lista.map { (if (it.id == atual) "✓ " else "   ") + it.nome + if (it.infantil) "  (infantil)" else "" }.toTypedArray()) { _, i ->
                val p = lista[i]
                if (p.id == atual) return@setItems
                val trocar = {
                    trabalho.execute {
                        Perfis.trocar(this, p.id)
                        ui.post { reiniciarLauncher("Perfil: ${p.nome}") }
                    }
                }
                // Sair de um perfil infantil pede o PIN.
                if (Perfis.protegido(this)) pedirPin("Trocar de perfil") { trocar() } else trocar()
            }
            .show()
    }

    private fun secaoPerfis(p: PainelConfig) {
        val atual = Perfis.atual(this)
        val todos = Perfis.todos(this)
        p.secao("Perfis e controle parental")
        p.acao("Perfil: ${atual.nome}", "Cada perfil tem os próprios favoritos, coleções, aparência, sons, histórico e contas " +
            "(Steam, Xbox, RetroAchievements). ${todos.size} perfil(s).", "Trocar", fecharAntes = true) { escolherPerfil() }
        p.acao("Novo perfil", "Começa com a aparência padrão e sem favoritos nem histórico.", "Criar", fecharAntes = true) {
            pedirTexto("Nome do perfil", "") { nome ->
                android.app.AlertDialog.Builder(this, temaDialogo())
                    .setTitle("\"$nome\" é um perfil infantil?")
                    .setMessage("Perfis infantis têm limite de tempo por dia, não veem os jogos marcados \"só para adultos\" e " +
                        "precisam do PIN para abrir as configurações.")
                    .setPositiveButton("Infantil") { _, _ -> Perfis.criar(this, nome, true); Toast.makeText(this, "Perfil criado", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Normal") { _, _ -> Perfis.criar(this, nome, false); Toast.makeText(this, "Perfil criado", Toast.LENGTH_SHORT).show() }
                    .show()
            }
        }
        p.acao("Renomear este perfil", null, "Renomear", fecharAntes = true) {
            pedirTexto("Nome do perfil", atual.nome) { Perfis.alterar(this, Perfis.Perfil(atual.id, it, atual.infantil, atual.limiteMin)) }
        }
        val outros = todos.filter { it.id != atual.id }
        if (outros.isNotEmpty()) p.acao("Excluir um perfil", "Apaga os favoritos, histórico e contas dele.", "Excluir", fecharAntes = true) {
            android.app.AlertDialog.Builder(this, temaDialogo())
                .setTitle("Excluir qual perfil?")
                .setItems(outros.map { it.nome }.toTypedArray()) { _, i -> Perfis.excluir(this, outros[i].id) }
                .show()
        }
        p.opcoes("Perguntar quem está jogando", "Ao abrir o launcher (com mais de um perfil).", listOf("Não", "Sim"),
            if (Perfis.prefs(this).getBoolean("perguntar", false)) 1 else 0) { Perfis.prefs(this).edit().putBoolean("perguntar", it == 1).apply() }
        p.acao(if (Perfis.pin(this) == null) "Criar PIN do controle parental" else "Trocar o PIN",
            "Protege as configurações e a troca de perfil nos perfis infantis, e libera tempo extra.", "PIN", fecharAntes = true) {
            pedirPin("PIN atual") {
                pedirTexto("PIN novo (4 dígitos ou mais)", "", numerico = true) { novo ->
                    if (novo.length < 4) Toast.makeText(this, "Use pelo menos 4 dígitos", Toast.LENGTH_SHORT).show()
                    else { Perfis.definirPin(this, novo); Toast.makeText(this, "PIN guardado", Toast.LENGTH_SHORT).show() }
                }
            }
        }
        for (perf in todos.filter { it.infantil }) {
            p.opcoes("Tempo de jogo: ${perf.nome}", "Depois do limite, os jogos só abrem com o PIN. Conta o que foi aberto pelo launcher.",
                Perfis.LIMITES.map { Perfis.rotuloLimite(it) }, Perfis.LIMITES.indexOf(perf.limiteMin).coerceAtLeast(0)) {
                Perfis.alterar(this, Perfis.Perfil(perf.id, perf.nome, true, Perfis.LIMITES[it]))
            }
        }
        if (Perfis.pin(this) != null) p.nota("Para esconder um jogo dos perfis infantis: segure o card (ou ⋮) › \"Só para adultos\".")
    }


    private fun dormirAgora() {
        val acess = ServicoAcessibilidade.instancia
        if (!Root.ligado(this) && acess == null) {
            Toast.makeText(this, "Para apagar a tela, ligue o root ou o serviço de acessibilidade (Menu rápido)", Toast.LENGTH_LONG).show()
            return
        }
        trabalho.execute {
            Dormir.dormir(this) {
                if (Root.ligado(this)) RecursosRoot.desligarTela()
                else acess?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }

    private fun buscarPorVoz() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Diga o nome do jogo ou app")
        try {
            startActivityForResult(i, PEDIDO_VOZ)
        } catch (_: Exception) {
            Toast.makeText(this, "Este aparelho não tem reconhecimento de voz", Toast.LENGTH_SHORT).show()
        }
    }

    /** Reabre o launcher do zero (depois de importar um backup ou trocar de perfil: tudo é lido de novo). */
    private fun reiniciarLauncher(aviso: String) {
        Toast.makeText(this, aviso, Toast.LENGTH_SHORT).show()
        val i = Intent.makeRestartActivityTask(android.content.ComponentName(this, MainActivity::class.java))
        ui.postDelayed({
            startActivity(i)
            Runtime.getRuntime().exit(0)
        }, 600)
    }

    /** Nome da plataforma de um jogo pelo id do card, para as estatísticas. */
    private fun plataformaDoId(id: String): String = when {
        id.startsWith("rom:") -> roms.firstOrNull { idRom(it) == id }?.sistema?.nome ?: "ROMs"
        id.startsWith("app:") -> "Jogos Android"
        id.startsWith("nuvem:gfn") -> "GeForce NOW"
        id.startsWith("nuvem:xbox") -> "Xbox Cloud Gaming"
        id.startsWith("atalho:") -> apps.firstOrNull { it.pacote == id.removePrefix("atalho:").substringBefore('/') }?.nome ?: "Atalhos"
        else -> "Outros"
    }

    private fun mostrarEstatisticas() {
        val d = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val v = Estatisticas.tela(this, s) { plataformaDoId(it) }
        v.setBackgroundColor(Paleta.FUNDO)
        d.setContentView(v)
        d.show()
        v.requestFocus()
    }

    private fun abrirConfiguracoes(secao: String? = null) {
        if (Perfis.protegido(this) && !pinOk) {
            pedirPin("Configurações") { pinOk = true; abrirConfiguracoes(secao) }
            return
        }
        val p = PainelConfig(this, s)
        painelAberto = p
        val fundo = prefs.getString("fundo", "padrao").orEmpty()

        p.secao("Aparência")
        p.opcoes("Tamanho dos apps favoritos", "Banners da fileira \"Apps favoritos\" no Início.",
            listOf("Pequeno", "Médio", "Grande", "Enorme"), prefs.getInt("tam_apps", 1)) { ajustar("tam_apps", it) }
        p.opcoes("Tamanho dos cards de jogos e filmes", "Cards grandes do Início e da aba Jogos.",
            listOf("Pequeno", "Médio", "Grande"), prefs.getInt("tam_cards", 1)) { ajustar("tam_cards", it) }
        p.opcoes("Arredondamento das bordas", "Cantos dos banners de apps, jogos e filmes.",
            listOf("Reto", "Pouco", "Médio", "Muito", "Máximo"), prefs.getInt("raio_cards", 1)) { ajustar("raio_cards", it) }
        p.opcoes("Ajuste das imagens", "Cortar preenche o card; Inteira mostra tudo sem deformar; Esticar mostra tudo ocupando o card todo.",
            listOf("Cortar", "Inteira", "Esticar"), prefs.getInt("ajuste_img", 0)) { ajustar("ajuste_img", it) }
        val comBanner = apps.count { it.banner != null }
        p.opcoes("Sem capa no SteamGridDB", "Usar o banner de Android TV que o próprio app traz ($comBanner app(s) têm) ou o ícone.",
            listOf("Banner de TV do app", "Ícone do app"), if (usarBannerTv()) 0 else 1) {
            prefs.edit().putBoolean("banner_tv", it == 0).apply()
            descartarTodas()
            mostrar(telaAtual)
        }
        val colunas = listOf(0, 3, 4, 5, 6)
        p.opcoes("Colunas na aba Apps", "Menos colunas = apps maiores.",
            listOf("Automático", "3", "4", "5", "6"), colunas.indexOf(prefs.getInt("colunas_apps", 0)).coerceAtLeast(0)) {
            ajustar("colunas_apps", colunas[it])
        }
        // Tamanho, fonte e zoom valem para tudo: a interface é montada de novo (as configurações reabrem aqui).
        p.opcoes("Tamanho da interface", "Textos, cards e painéis do launcher, sem mexer na resolução nem nos outros apps.",
            Paleta.TAMANHOS_INTERFACE.map { it.first }, prefs.getInt("tam_interface", 1)) {
            prefs.edit().putInt("tam_interface", it).apply()
            p.fechar()
            construirUi()
            ui.postDelayed({ abrirConfiguracoes("Aparência") }, 300)
        }
        p.opcoes("Fonte", "Letra de todos os textos do launcher.", Paleta.FONTES.map { it.first }, prefs.getInt("fonte", 0)) {
            prefs.edit().putInt("fonte", it).apply()
            Paleta.definirFonte(this)
            p.fechar()
            construirUi()
            ui.postDelayed({ abrirConfiguracoes("Aparência") }, 300)
        }
        p.opcoes("Zoom no card selecionado", "Quanto o card cresce quando o controle passa por ele.",
            Paleta.ZOOMS_FOCO.map { it.first }, prefs.getInt("zoom_foco", 2)) {
            prefs.edit().putInt("zoom_foco", it).apply()
            Paleta.definirZoom(this)
            descartarTodas()
            mostrar(telaAtual)
        }
        p.opcoes("Relógio", "Hora no canto de cima.", listOf("24 horas", "12 horas (AM/PM)", "Com a data", "Escondido"),
            prefs.getInt("relogio", 0)) {
            prefs.edit().putInt("relogio", it).apply()
            atualizarRelogio()
        }
        p.opcoes("Informações no topo", "Embaixo do relógio. O clima vem do wttr.in pela localização aproximada da sua internet; " +
            "a bateria do controle já aparece no ícone do controle.", Widgets.MODOS, prefs.getInt("widgets", 0)) {
            prefs.edit().putInt("widgets", it).apply()
            atualizarInfosTopo()
        }
        p.opcoes("Cor de fundo", "Cor lisa do fundo e dos painéis. Automático segue o tema do Android (muda com o papel de parede).",
            Paleta.CORES_FUNDO.map { it.first }, prefs.getInt("cor_fundo", 0)) {
            prefs.edit().putInt("cor_fundo", it).apply()
            Paleta.definirFundo(this, it)
            // Remonta a tela por trás do painel com a cor nova (véus e fundo guardam a cor ao serem criados).
            construirUi()
            p.atualizarCor()
        }
        p.opcoes("Cor de destaque", "Cor dos itens selecionados. Automático segue o tema do Android.",
            Paleta.CORES_DESTAQUE.map { it.first }, prefs.getInt("cor_destaque", 0)) {
            prefs.edit().putInt("cor_destaque", it).apply()
            Paleta.definirDestaque(this, it)
            // Reabre o painel já com a cor nova nas opções.
            p.fechar()
            abrirConfiguracoes("Aparência")
        }
        p.opcoes("Plano de fundo", "Paisagens do Wikimedia Commons, uma foto sua ou só a cor de fundo.",
            listOf("Cor de fundo", "Paisagens", "Foto da galeria"),
            when { fundo == "paisagens" -> 1; fundo.startsWith("foto:") -> 2; else -> 0 }) {
            when (it) {
                0 -> mudarFundo("padrao")
                1 -> mudarFundo("paisagens")
                2 -> escolherFoto { url -> mudarFundo("foto:$url") }
            }
        }
        p.acao("Trocar paisagem agora", "Só vale com o fundo de paisagens.", "Trocar") {
            if (prefs.getString("fundo", "padrao") == "paisagens") aplicarFundoApp(trocar = true)
            else Toast.makeText(this, "Escolha o fundo \"Paisagens\" primeiro", Toast.LENGTH_SHORT).show()
        }

        p.secao("Temas e sons")
        p.opcoes("Tema pronto", "Muda de uma vez cores, sons, bordas, fonte, zoom e fundo animado. Depois dá para ajustar cada um em Aparência.",
            Paleta.TEMAS.map { it.nome }, prefs.getInt("tema", -1)) { aplicarTema(it) }
        p.opcoes("Tela inicial", "O visual do app inteiro: tela inicial, biblioteca, configurações, menu rápido, opções e janelas no jeito de cada console.",
            listOf("Android TV (fileiras)", "Estilo PS4", "Estilo Xbox 360", "Estilo Switch", "Estilo Wii", "Estilo PS5", "Estilo Xbox Series"), layoutInicio()) {
            prefs.edit().putInt("layout_inicio", it).apply()
            painelAberto?.fechar()
            descartarTodas()
            construirUi()
            carregarCapas()
            ui.postDelayed({ abrirConfiguracoes("Temas e sons") }, 300)
        }
        p.opcoes("Efeito de TV antiga", "Linhas de varredura e cantos escuros por cima de tudo.", listOf("Desligado", "Ligado"),
            if (prefs.getBoolean("efeito_crt", false)) 1 else 0) {
            prefs.edit().putBoolean("efeito_crt", it == 1).apply()
            painelAberto?.fechar()
            construirUi()
            ui.postDelayed({ abrirConfiguracoes("Temas e sons") }, 300)
        }
        p.opcoes("Sons de navegação", "Som ao mover a seleção, abrir e voltar.", Sons.VOLUMES.map { it.first }, prefs.getInt("sons_volume", 0)) {
            prefs.edit().putInt("sons_volume", it).apply()
            Sons.amostra(this)
        }
        p.opcoes("Tipo de som", null, Sons.PACOTES.map { it.nome }, prefs.getInt("sons_pacote", 0)) {
            prefs.edit().putInt("sons_pacote", it).apply()
            if (prefs.getInt("sons_volume", 0) == 0) prefs.edit().putInt("sons_volume", 2).apply()
            Sons.amostra(this)
        }
        p.opcoes("Fundo animado", "A imagem de fundo se move devagar (paisagem ou foto). Gasta um pouco mais de bateria.",
            listOf("Desligado", "Ligado"), if (prefs.getBoolean("fundo_animado", false)) 1 else 0) {
            prefs.edit().putBoolean("fundo_animado", it == 1).apply()
            fundoApp?.animar(it == 1)
        }

        p.secao("Tela inicial")
        p.opcoes("Destaque do topo", "Filme ou série em destaque no alto do Início.",
            listOf("Ligado", "Desligado"), if (prefs.getBoolean("destaque", true)) 0 else 1) {
            prefs.edit().putBoolean("destaque", it == 0).apply()
            descartarTodas()
            mostrar(telaAtual)
        }
        p.opcoes("Troca do destaque", null, listOf("5 s", "8 s", "15 s"), prefs.getInt("intervalo_destaque", 1)) {
            prefs.edit().putInt("intervalo_destaque", it).apply()
            iniciarDestaque()
        }
        p.opcoes("Ordem das seções", "Subir, descer ou mostrar de novo seções escondidas. Também dá pelo ⋮ ao lado de cada título.",
            listOf("Início", "Jogos", "Apps"), -1) {
            organizarSecoes(listOf(ABA_INICIO, ABA_JOGOS, ABA_APPS)[it])
        }

        p.secao("Apps")
        p.acao("Apps favoritos", "Escolher quais apps aparecem nos favoritos.", "Escolher") { escolherFavoritos() }
        p.acao("Apps escondidos", "Mostrar de novo apps tirados da tela inicial.", "Escolher") { escolherOcultos() }

        p.secao("Jogos")
        // Formato das capas: o Início fica sempre com banners; na aba Jogos, cada plataforma escolhe.
        p.opcoes("Formato das capas na aba Jogos", "Padrão para as plataformas sem escolha própria. Banner deitado, ou a " +
            "capa original em pé na proporção da caixa (PS2, PSP, Switch, SNES…), vinda do libretro, da Steam e, com chave, " +
            "do SteamGridDB; sem capa em pé, o banner aparece inteiro. O Início continua com banners.",
            listOf("Banner (deitado)", "Capa original (em pé)"), prefs.getInt("formato_capas", 0)) {
            prefs.edit().putInt("formato_capas", it).apply()
            descartar(Tela.JOGOS)
            carregarCapas()
        }
        for ((secao, nome) in secoesDeJogos()) {
            p.opcoes(nome, null, listOf("Banner", "Capa em pé"), if (emPeNaSecao(secao)) 1 else 0) { definirFormatoSecao(secao, it == 1) }
        }
        p.nota("Também dá para trocar pelo ⋮ ao lado do nome de cada fileira na aba Jogos.")
        p.acao("Estatísticas de jogo", "Tempo jogado hoje e na semana, gráfico dos dias, jogos e plataformas mais jogados.", "Ver",
            fecharAntes = true) { mostrarEstatisticas() }
        p.acao("Jogos Android", "Escolher quais apps aparecem em Jogos.", "Escolher") { escolherJogos() }
        p.acao("Pastas de ROMs", "Ao adicionar, você escolhe para qual plataforma é a pasta.", "Adicionar pasta",
            fecharAntes = true) { escolherPasta() }
        for (uri in pastasRoms()) {
            p.opcoes("📁 ${nomeDaPasta(uri)}", "Plataforma: ${Roms.sistemaDaPasta(this, uri)?.nome ?: "detectar sozinho"}",
                listOf("Mudar plataforma", "Remover pasta"), -1) {
                p.fechar()
                if (it == 0) escolherPlataformaDaPasta(uri) else removerPasta(uri)
            }
        }

        p.acao("Procurar ROMs de novo", "Lê as pastas outra vez e baixa capas dos jogos novos.", "Procurar") { varrerRoms() }
        p.acao("Jogos escondidos", "Mostrar de novo ROMs escondidas.", "Escolher") { escolherRomsEscondidas() }

        p.opcoes("Capa das ROMs (sem chave)", "Imagem do banco libretro, o mesmo do RetroArch e do Daijishō. A caixa fica em pé: " +
            "com \"Ajuste das imagens\" em Inteira ela aparece toda. Capas escolhidas à mão não mudam.",
            listOf("Tela-título", "Caixa do jogo", "Print do jogo"), prefs.getInt("capa_roms", 0)) {
            prefs.edit().putInt("capa_roms", it).apply()
            trabalho.execute {
                Capas.esquecerLibretro(this)
                ui.post { carregarCapas() }
            }
        }

        p.opcoes("Testar a conexão antes de jogar na nuvem", "GeForce NOW e Xbox Cloud: mede o Wi-Fi e a resposta do serviço e avisa " +
            "se algo vai atrapalhar (2,4 GHz, sinal fraco, latência alta).", listOf("Ligado", "Desligado"),
            if (prefs.getBoolean("teste_nuvem", true)) 0 else 1) { prefs.edit().putBoolean("teste_nuvem", it == 0).apply() }

        // Fonte preferida das capas: tentada primeiro; sem a capa lá, as outras na ordem de sempre.
        val fontes = Capas.FonteCapa.entries
        val semChaveSgdb = if (Capas.ativo()) "" else " O SteamGridDB precisa de uma chave (Contas e chaves); sem ela, fica o automático."
        p.opcoes("Fonte preferida das capas", "Vale para todas as plataformas sem escolha própria. Capas escolhidas à mão não mudam." +
            semChaveSgdb, fontes.map { it.rotulo }, prefs.getInt("fonte_capa", 0)) {
            prefs.edit().putInt("fonte_capa", it).apply()
            refazerCapas(null)
        }
        val global = fontes.getOrElse(prefs.getInt("fonte_capa", 0)) { fontes[0] }
        for ((secao, nome) in secoesDeJogos().filter { it.first == "android" || it.first.startsWith("rom_") }) {
            val atual = prefs.getInt("fonte_capa:$secao", -1)
            p.opcoes("Capas: $nome", null, listOf("Usar a global (${global.rotulo.substringBefore(" (")})") + fontes.map { it.rotulo }, atual + 1) {
                if (it == 0) prefs.edit().remove("fonte_capa:$secao").apply() else prefs.edit().putInt("fonte_capa:$secao", it - 1).apply()
                refazerCapas(secao)
            }
        }

        p.secao("Emuladores")
        val semEmulador = ArrayList<String>()
        for (sistema in Sistema.entries) {
            val lista = Roms.candidatos(this, sistema)
            val qtd = roms.count { it.sistema == sistema }
            // Só aparecem as plataformas com jogos ou com emulador instalado (são muitas).
            if (lista.isEmpty() && qtd == 0) continue
            if (lista.isEmpty()) semEmulador += sistema.nome
            val nomes = lista.map { pk -> nomeDoPacote(pk) }
            val atual = Roms.escolhido(this, sistema)?.let { lista.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
            // A última escolha abre a lista de todos os apps: serve para emuladores que o launcher não conhece.
            p.opcoes(sistema.nome, if (qtd > 0) "$qtd jogo(s)" else null, listOf("Automático") + nomes + "Outro app instalado…", atual) { i ->
                when {
                    i == 0 -> Roms.escolher(this, sistema, null)
                    i <= lista.size -> Roms.escolher(this, sistema, lista[i - 1])
                    else -> escolherOutroEmulador(sistema)
                }
                descartar(Tela.JOGOS)
                if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
            }
        }
        p.nota("O launcher reconhece DuckStation, NetherSX2/AetherSX2, PPSSPP, Dolphin, Azahar/Lime3DS, melonDS, DraStic, Eden, " +
            "Vita3K, Cemu, Flycast, Redream, RetroArch e os que têm no nome rpcs3/aps3e, shadps4, x360/xenia/ax360e e afins. " +
            "Para qualquer outro, escolha \"Outro app instalado…\". Pastas com ps3, ps4, vita, xbox360, wiiu ou dreamcast no nome " +
            "levam os jogos para a plataforma certa." +
            if (semEmulador.isNotEmpty()) "\nSem emulador para: ${semEmulador.joinToString(", ")}." else "")

        p.secao("Trailers")
        p.opcoes("Trailers automáticos", "Com o foco 1 s parado num jogo, filme ou série, o trailer toca no fundo (Steam ou YouTube).",
            listOf("Ligado", "Só no Wi-Fi", "Desligado"), prefs.getInt("trailers_auto", 0)) {
            prefs.edit().putInt("trailers_auto", it).apply()
            if (it == 2) pararTrailerFundo()
        }
        p.opcoes("Som dos trailers", "Começa em 50% do volume de mídia e sobe aos poucos até 100%.",
            listOf("Ligado", "Desligado"), if (prefs.getBoolean("som_trailers", true)) 0 else 1) {
            prefs.edit().putBoolean("som_trailers", it == 0).apply()
            pararTrailerFundo()
        }
        val imersivoAtual = IMERSIVOS_SEG.indexOf(prefs.getInt("imersivo_seg", 10)).coerceAtLeast(0)
        p.opcoes("Tela cheia do trailer", "Sem mexer no controle por esse tempo, a interface some e o trailer fica em tela cheia. Qualquer botão traz a interface de volta.",
            listOf("5 s", "10 s", "15 s", "30 s", "1 min", "Desligado"), imersivoAtual) {
            prefs.edit().putInt("imersivo_seg", IMERSIVOS_SEG[it]).apply()
        }

        p.secao("Descanso de tela")
        val ocioAtual = OCIOS_MIN.indexOf(prefs.getInt("ocio_min", 5)).coerceAtLeast(0)
        p.opcoes("Começar depois de", "Paisagens na tela inteira com relógio. A tela fica acesa até lá e apaga 10 min depois.",
            listOf("Desligado", "1 min", "5 min", "10 min", "15 min"), ocioAtual) {
            prefs.edit().putInt("ocio_min", OCIOS_MIN[it]).apply()
            reiniciarOcio()
        }

        secaoPerfis(p)

        p.secao("Backup")
        p.acao("Exportar configurações", "Salva num arquivo: aparência, favoritos, coleções, capas e nomes trocados, histórico, perfis, " +
            "fotos usadas como capa e as chaves e contas (guarde o arquivo num lugar seguro).", "Exportar", fecharAntes = true) {
            val nome = "GameLauncherTV-backup-${Historico.dia(System.currentTimeMillis())}.json"
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, nome), PEDIDO_EXPORTAR)
            } catch (_: Exception) {
                Toast.makeText(this, "Este aparelho não tem o seletor de arquivos", Toast.LENGTH_SHORT).show()
            }
        }
        p.acao("Importar configurações", "Troca as configurações atuais pelas de um arquivo de backup e reabre o launcher.", "Importar",
            fecharAntes = true) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), PEDIDO_IMPORTAR)
            } catch (_: Exception) {
                Toast.makeText(this, "Este aparelho não tem o seletor de arquivos", Toast.LENGTH_SHORT).show()
            }
        }

        p.secao("Contas e chaves")
        // Resumo de verdade: quais estão ativas agora (antes era um texto fixo que parecia dizer "sem chave").
        val ativas = Chaves.Servico.entries.filter { sv ->
            Chaves.ativa(sv)
        }
        p.nota(if (ativas.isEmpty()) "Nenhuma chave ativa: o app usa só fontes livres (Steam, Wikipédia, libretro, GameTDB, " +
            "Stremio, Apple). As chaves são grátis e completam o resto."
            else "✅ Ativas: ${ativas.joinToString(", ") { it.nome }}." +
                (Chaves.Servico.entries - ativas.toSet()).takeIf { it.isNotEmpty() }?.let { r -> " Sem chave: ${r.joinToString(", ") { it.nome }}." }.orEmpty())
        p.acao("Colocar pelo celular", "Mostra um QR code: escaneie com o celular (na mesma rede Wi-Fi) e cole as chaves lá, " +
            "sem digitar com o controle.", "Mostrar QR code") {
            chavesPeloCelular {
                p.fechar()
                abrirConfiguracoes("Contas e chaves")
            }
        }
        for (sv in Chaves.Servico.entries) {
            // Serviços com usuário (RetroAchievements, Steam) são contas: "Entrar" e "conta …".
            val conta = sv.usuario != null
            val estado = when {
                conta && Chaves.ativa(sv) -> "✅ Ativa — conta ${Chaves.usuario(sv)}"
                conta -> "Sem conta"
                Chaves.propria(sv).isNotEmpty() -> "✅ Ativa — sua chave"
                Chaves.embutida(sv).isNotEmpty() -> "✅ Ativa — chave desta versão"
                else -> "Sem chave"
            }
            val botao = when {
                conta -> if (Chaves.ativa(sv)) "Trocar conta" else "Entrar"
                Chaves.propria(sv).isNotEmpty() -> "Trocar chave"
                else -> "Colocar chave"
            }
            // O estado vem primeiro (é o que importa ao bater o olho); a explicação de onde criar, depois.
            p.acao(sv.nome, "$estado\n${sv.onde}", botao) {
                pedirChave(sv) {
                    p.fechar()
                    abrirConfiguracoes("Contas e chaves")
                }
            }
        }

        // Só em aparelhos com root (Magisk, KernelSU, APatch); tudo desligado até a pessoa ligar.
        // O botão PS e o menu rápido também aqui (não só no painel do controle), para achar fácil.
        secaoMenuRapido(p)
        if (Root.existe(this) || Root.ligado(this)) secaoRoot(p)
        secaoTela(p)

        p.secao("Sistema")
        p.acao("Dormir", "Pausa o que toca, liga a economia de energia e apaga a tela. Com o celular no cabo HDMI, a TV fica sem " +
            "sinal e entra em espera. Também fica no menu rápido.", "Dormir", fecharAntes = true) { dormirAgora() }
        p.opcoes("Economia de energia ao dormir", "Desliga sozinha quando você volta ao launcher.", listOf("Desligado", "Ligado"),
            if (prefs.getBoolean("dormir_economia", true)) 1 else 0) { prefs.edit().putBoolean("dormir_economia", it == 1).apply() }
        p.acao("Tela inicial padrão do celular", "Para voltar ao One UI Home ou ao Projectivy.", "Abrir") {
            abrir(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        p.acao("Configurações do Android", null, "Abrir") { abrir(Intent(Settings.ACTION_SETTINGS)) }
        p.nota("GameLauncherTV ${BuildConfig.VERSION_NAME} · versão ${if (BuildConfig.FLAVOR == "pessoal") "pessoal" else "pública"}.\n" +
            "Filmes e séries: TMDB (este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB) e Stremio (Cinemeta).\n" +
            "Jogos: Steam, Wikipédia, libretro-thumbnails e SteamGridDB. Paisagens: Wikimedia Commons (CC BY-SA, crédito em cada foto).\n" +
            "As marcas pertencem aos seus titulares.")
        p.mostrar(secao)
    }

    /**
     * Fonte preferida mudou: as capas automáticas da plataforma [secao] (ou de todos os jogos, com null) são
     * buscadas de novo na ordem nova. As escolhidas à mão ficam (são guardadas à parte).
     */
    private fun refazerCapas(secao: String?) {
        val nomes = when {
            secao == null -> null
            secao == "android" -> jogosAndroid(apps).map { it.nome } + atalhos.map { nomeAtalho(it) }
            else -> roms.filter { "rom_${it.sistema.name}" == secao }.map { it.nome }
        }
        val chaves = nomes?.flatMap { listOf(Capas.chave(it), Capas.chavePoster(it)) }?.toSet()
        trabalho.execute {
            Capas.esquecerAutomaticas(this, chaves)
            ui.post {
                capas = capas.filterKeys { k -> chaves != null && k !in chaves }
                descartarTodas()
                mostrar(telaAtual)
                carregarCapas()
            }
        }
    }

    // ---------- Ordenar as fileiras ----------

    private val MODOS_ORDEM = listOf("Manual (arrastar)", "Por nome", "Jogados recentemente", "Mais jogados")

    /** Chave de ordem da fileira de uma seção da aba Jogos (a dos jogos Android tem nome próprio). */
    private fun chaveOrdemDaSecao(secao: String) = if (secao == "android") ORDEM_JOGOS else secao

    /** Ordena os cards da fileira [ordem] pelo modo escolhido ("ordem_modo:<ordem>"); manual = como já vieram. */
    private fun ordenarCards(ordem: String?, itens: List<View>): List<View> {
        val modo = ordem?.let { prefs.getInt("ordem_modo:$it", 0) } ?: 0
        if (modo == 0) return itens
        val (cards, outros) = itens.partition { it is CardTV && it.tag is String }
        val hist = Historico.registros(this).associateBy { it.id }
        val ordenados = when (modo) {
            1 -> cards.sortedBy { it.contentDescription?.toString()?.lowercase().orEmpty() }
            2 -> cards.sortedByDescending { hist[it.tag as String]?.ultimaVez ?: 0L }
            else -> cards.sortedByDescending { hist[it.tag as String]?.totalMs ?: 0L }
        }
        return ordenados + outros
    }

    // ---------- Nuvem, Continuar jogando, coleções, conquistas e jogo aleatório ----------

    private var jogosNuvem: Map<Nuvem.Servico, List<Nuvem.Jogo>> = emptyMap()
    private var conquistasRecentes: List<RetroConquistas.Recente> = emptyList()
    /** Seus jogos da Steam (appid → jogo), para "Seus jogos no GeForce NOW". */
    private var meusSteam: Map<Int, ContaSteam.JogoSteam> = emptyMap()
    /** Jogos gratuitos do Xbox Cloud Gaming (sem Game Pass). */
    private var xboxGratis: List<Nuvem.Jogo> = emptyList()
    private var xboxPerfil: ContaXbox.Perfil? = null
    private var xboxJogos: List<ContaXbox.Jogo> = emptyList()

    /** Jogos do GeForce NOW que você tem na Steam, do jogado por último ao mais antigo. */
    private fun meusNoGfn(): List<Nuvem.Jogo> = jogosNuvem[Nuvem.Servico.GFN].orEmpty()
        .filter { it.steamId != null && it.steamId in meusSteam }
        .sortedByDescending { meusSteam[it.steamId]?.ultimaVez ?: 0L }
        .take(60)

    /** Card de um jogo da sua conta Xbox: abre na nuvem se estiver no Xbox Cloud; senão, o app do Xbox. */
    private fun cardXbox(j: ContaXbox.Jogo): View {
        val conquistas = "🏆 ${j.ganhas} de ${j.total} · ${j.pontos}/${j.pontosTotal} G"
        val naNuvem = jogosNuvem[Nuvem.Servico.XBOX].orEmpty().firstOrNull { normalizar(it.nome) == normalizar(j.nome) }
        if (naNuvem != null) return cardNuvem(naNuvem).also { (it as? CardTV)?.definirDetalhe("☁ Xbox Cloud · $conquistas") }
        val id = "xbox:${j.titleId}"
        val capa = capaManual(id)?.ifEmpty { null } ?: j.imagem ?: capas[Capas.chave(j.nome)]
        val abrirApp = {
            val app = Nuvem.appInstalado(this, Nuvem.Servico.XBOX)?.let { packageManager.getLaunchIntentForPackage(it) }
            if (app != null) abrir(app) else Toast.makeText(this, "\"${j.nome}\" não está no Xbox Cloud Gaming.", Toast.LENGTH_SHORT).show()
        }
        return cardGrande(j.nome, conquistas, null, Nuvem.Servico.XBOX.cor, capa, abrirApp, null, id, { selecionarJogo(capa, j.nome, null, id) })
            .also {
                infoJogo[it] = {
                    abrirInfoJogo(j.nome, null, capa, listOf("Conquistas" to "${j.ganhas} de ${j.total} (${j.progresso}%)",
                        "Gamerscore" to "${j.pontos} de ${j.pontosTotal}"), acoesDoCard(it), fundoManual(id)) { abrirApp() }
                }
            }
    }

    /** Catálogos da nuvem e conquistas recentes (rede, fora da tela); a aba Jogos é refeita quando chegam. */
    private fun carregarNuvem() {
        Thread {
            val gfn = try { Nuvem.gfn(this) } catch (_: Exception) { emptyList() }
            val xbox = try { Nuvem.xbox(this) } catch (_: Exception) { emptyList() }
            val gratis = try { Nuvem.xboxGratis(this) } catch (_: Exception) { emptyList() }
            val ra = try { RetroConquistas.recentes(this).orEmpty() } catch (_: Exception) { emptyList() }
            val steam = try { ContaSteam.meusJogos(this).associateBy { it.appId } } catch (_: Exception) { emptyMap() }
            val perfilXbox = try { ContaXbox.perfil(this) } catch (_: Exception) { null }
            val jogosXbox = try { ContaXbox.jogos(this) } catch (_: Exception) { emptyList() }
            ui.post {
                if (isDestroyed) return@post
                jogosNuvem = mapOf(Nuvem.Servico.GFN to gfn, Nuvem.Servico.XBOX to xbox)
                conquistasRecentes = ra
                xboxGratis = gratis
                meusSteam = steam
                xboxPerfil = perfilXbox
                xboxJogos = jogosXbox
                descartar(Tela.JOGOS)
                if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
                carregarCapas()
            }
        }.start()
    }

    /**
     * Jogos do GeForce NOW na fileira: os "otimizados" (padrão), todos, ou de um gênero ("filtro:nuvem_gfn").
     * No máximo 60 (o catálogo tem mais de 1.500; o resto aparece na Pesquisar).
     */
    private fun gfnNaFileira(): List<Nuvem.Jogo> {
        val todos = jogosNuvem[Nuvem.Servico.GFN].orEmpty()
        val lista = when (val f = prefs.getString("filtro:$SECAO_GFN", null)) {
            null -> todos.filter { it.otimizado }
            "*" -> todos
            else -> todos.filter { f in it.generos }
        }
        return semRepetir(lista).take(60)
    }

    /**
     * O GeForce NOW tem uma entrada por loja do mesmo jogo (Steam, Ubisoft, Epic…): fica uma só por nome, a da Steam
     * quando existe (é a que cruza com a sua conta), na posição da primeira.
     */
    private fun semRepetir(lista: List<Nuvem.Jogo>): List<Nuvem.Jogo> {
        val grupos = lista.groupBy { "${it.servico}:${normalizar(it.nome)}" }
        return lista.map { "${it.servico}:${normalizar(it.nome)}" }.distinct().map { k ->
            grupos.getValue(k).let { g -> g.firstOrNull { it.steamId != null && it.steamId in meusSteam } ?: g.firstOrNull { it.steamId != null } ?: g.first() }
        }
    }

    private fun todosNuvem() = jogosNuvem.values.flatten() + xboxGratis

    private fun cardNuvem(j: Nuvem.Jogo, comFundo: Boolean = true): View {
        val id = j.idCard
        val capa = capaManual(id)?.ifEmpty { null } ?: j.capa ?: capas[Capas.chave(j.nome)]
        val app = Nuvem.appInstalado(this, j.servico)?.let { p -> apps.firstOrNull { it.pacote == p } }
        // "☁" marca o que roda na nuvem (não é ROM nem jogo instalado).
        val detalhe = "☁ ${j.servico.nome}" + (j.generos.firstOrNull()?.let { " · $it" } ?: "")
        return cardGrande(j.nome, detalhe, app?.icone, j.servico.cor, capa, { abrirNuvem(j) }, { v -> mostrarMenu(v, acoesNuvem(v, j)) }, id,
            if (comFundo) ({ selecionarJogo(capa, j.nome, null, id) }) else null)
            .also {
                val extras = listOf("Na nuvem" to j.servico.nome) +
                    listOfNotNull(j.generos.takeIf { g -> g.isNotEmpty() }?.let { g -> "Gêneros" to g.joinToString(", ") }) + infoTempo(id)
                infoJogo[it] = { abrirInfoJogo(j.nome, null, capa, extras, acoesNuvem(it, j), fundoManual(id)) { abrirNuvem(j) } }
                trailerDe[it] = j.nome to null
            }
    }

    private fun acoesNuvem(v: View, j: Nuvem.Jogo): List<Acao> = acoesDoCard(v) + listOf(
        Acao("Jogar sem testar a conexão") { abrirNuvem(j, testar = false) },
        Acao("Trocar capa") { trocarCapa(j.idCard, j.nome) },
        Acao("Abrir no navegador") { abrir(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Nuvem.link(j)))) },
    )

    /**
     * Abre o jogo na nuvem. Antes (opção "Testar a conexão antes de jogar na nuvem", ligada por padrão) mede o
     * Wi-Fi e a resposta do serviço; se algo atrapalha, avisa e deixa jogar assim mesmo.
     */
    private fun abrirNuvem(j: Nuvem.Jogo, testar: Boolean = prefs.getBoolean("teste_nuvem", true)) {
        fun jogar() = abrirJogo(Nuvem.appInstalado(this, j.servico), j.idCard to j.nome) {
            try { startActivity(Nuvem.intent(this, j)) } catch (_: Exception) {
                abrir(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Nuvem.link(j))))
            }
        }
        // Sem o app web do serviço, o jogo abre no navegador com a barra em cima: uma vez, explica como deixar em tela cheia.
        if (Nuvem.appInstalado(this, j.servico) == null && Nuvem.appWeb(this, Nuvem.link(j)) == null &&
            !prefs.getBoolean("dica_tela_cheia:${j.servico}", false)) {
            prefs.edit().putBoolean("dica_tela_cheia:${j.servico}", true).apply()
            android.app.AlertDialog.Builder(this, temaDialogo())
                .setTitle("Jogar em tela cheia")
                .setMessage("Instale o app web do ${j.servico.nome} uma vez: ele abre no Chrome, toque em ⋮ e depois em " +
                    "\"Adicionar à tela inicial\" → \"Instalar\". Daí em diante os jogos abrem nele, em tela cheia, sem a barra do navegador.")
                .setPositiveButton("Abrir para instalar") { _, _ ->
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Nuvem.paginaInicial(j.servico)))
                    Nuvem.navegadorXbox(this)?.let { i.setPackage(it) }
                    abrir(i)
                }
                .setNegativeButton("Jogar assim") { _, _ -> abrirNuvem(j, testar) }
                .show()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b -> b.isFocusableInTouchMode = true; b.post { b.requestFocus() } }
            return
        }
        if (!testar) return jogar()
        Toast.makeText(this, "Testando a conexão…", Toast.LENGTH_SHORT).show()
        Thread {
            val c = Nuvem.testar(this, j.servico)
            ui.post {
                if (isDestroyed) return@post
                if (c.problemas.isEmpty()) {
                    Toast.makeText(this, "Conexão boa para jogar: ${c.resumo}", Toast.LENGTH_SHORT).show()
                    jogar()
                    return@post
                }
                android.app.AlertDialog.Builder(this, temaDialogo())
                    .setTitle("A conexão pode atrapalhar")
                    .setMessage(c.resumo + "\n\n" + c.problemas.joinToString("\n") { "• $it" })
                    .setPositiveButton("Jogar assim mesmo") { _, _ -> jogar() }
                    .setNegativeButton("Cancelar", null)
                    .show()
                    .getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b -> b.isFocusableInTouchMode = true; b.post { b.requestFocus() } }
            }
        }.start()
    }

    /** "Tempo jogado" e "Jogou por último" para a tela de informações (vazio se nunca foi aberto pelo launcher). */
    private fun infoTempo(id: String): List<Pair<String, String>> {
        val r = Historico.registro(this, id) ?: return emptyList()
        return listOfNotNull(
            r.totalMs.takeIf { it >= 60_000 }?.let { "Tempo jogado" to Historico.tempoLegivel(it) },
            r.ultimaVez.takeIf { it > 0 }?.let { "Jogou por último" to Historico.quandoLegivel(it) },
        )
    }

    /** O card de um jogo pelo id ("rom:…", "app:…", "atalho:…", "nuvem:…"); null se o jogo não existe mais. */
    private fun cardPorId(id: String): View? = when {
        id.startsWith("rom:") -> roms.firstOrNull { idRom(it) == id }?.let { rom ->
            val (detalhe, iconeEmu) = infoEmulador(rom.sistema)
            cardRom(rom, detalhe, iconeEmu, comFundo = true)
        }
        id.startsWith("app:") -> apps.firstOrNull { idApp(it) == id }?.let { cardJogo(it, comFundo = true) }
        id.startsWith("atalho:") -> atalhos.firstOrNull { idAtalho(it) == id }?.let { a ->
            val app = apps.firstOrNull { it.pacote == a.pacote }
            cardAtalho(a, app?.nome ?: a.pacote, app?.cor ?: Paleta.AZUL)
        }
        id.startsWith("nuvem:") -> todosNuvem().firstOrNull { it.idCard == id }?.let { cardNuvem(it) }
        else -> null
    }

    /** Sorteia um jogo (ROMs, Android e atalhos) e mostra a tela dele; dá para sortear outro. */
    private fun jogoAleatorio() {
        val ids = roms.map { idRom(it) } + jogosAndroid(apps.filter { it.pacote !in ocultos() }).map { idApp(it) } + atalhos.map { idAtalho(it) }
        if (ids.isEmpty()) return Toast.makeText(this, "Nenhum jogo para sortear ainda.", Toast.LENGTH_SHORT).show()
        val card = cardPorId(ids.random()) ?: return
        val nome = card.contentDescription?.toString().orEmpty()
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("🎲 Que tal jogar…")
            .setMessage(nome)
            .setPositiveButton("Ver o jogo") { _, _ -> infoJogo[card]?.invoke() ?: card.performClick() }
            .setNeutralButton("Sortear outro") { _, _ -> jogoAleatorio() }
            .setNegativeButton("Cancelar", null)
            .show()
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b -> b.isFocusableInTouchMode = true; b.post { b.requestFocus() } }
    }

    /** Pôr ou tirar o jogo do card [v] das coleções (com "Nova coleção…"). */
    private fun escolherColecoes(v: View) {
        val id = v.tag as? String ?: return
        val todas = Colecoes.todas(this).keys.toList()
        val marcadas = BooleanArray(todas.size) { Colecoes.contem(this, todas[it], id) }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Coleções")
            .setMultiChoiceItems(todas.toTypedArray(), marcadas) { _, i, sim -> marcadas[i] = sim }
            .setPositiveButton("Salvar") { _, _ ->
                todas.forEachIndexed { i, nome -> if (Colecoes.contem(this, nome, id) != marcadas[i]) Colecoes.alternar(this, nome, id) }
                descartar(Tela.JOGOS)
                if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
            }
            .setNeutralButton("Nova coleção…") { _, _ -> pedirNomeColecao(null) { nome -> Colecoes.alternar(this, nome, id); remontarJogos() } }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Pede o nome de uma coleção ([antigo] = renomear). */
    private fun pedirNomeColecao(antigo: String?, pronto: (String) -> Unit) {
        val campo = EditText(this).apply {
            setText(antigo.orEmpty())
            hint = "Ex.: Favoritos, Multiplayer, Zerar"
            isSingleLine = true
        }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle(if (antigo == null) "Nova coleção" else "Renomear coleção")
            .setView(campo)
            .setPositiveButton("OK") { _, _ ->
                val nome = campo.text.toString().trim()
                if (nome.isEmpty()) return@setPositiveButton
                if (antigo == null) Colecoes.criar(this, nome) else Colecoes.renomear(this, antigo, nome)
                pronto(nome)
            }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    private fun remontarJogos() {
        descartar(Tela.JOGOS)
        if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
    }

    /** Filtro da fileira do GeForce NOW: otimizados, todos ou um gênero (os mais comuns primeiro). */
    private fun escolherFiltroGfn() {
        val generos = jogosNuvem[Nuvem.Servico.GFN].orEmpty().flatMap { it.generos }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        val rotulos = listOf("Otimizados para o GeForce NOW", "Todos (os 60 primeiros)") + generos
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Mostrar no GeForce NOW")
            .setItems(rotulos.toTypedArray()) { _, i ->
                val e = prefs.edit()
                when (i) { 0 -> e.remove("filtro:$SECAO_GFN"); 1 -> e.putString("filtro:$SECAO_GFN", "*"); else -> e.putString("filtro:$SECAO_GFN", generos[i - 2]) }
                e.apply()
                remontarJogos()
                carregarCapas()
            }
            .show()
    }

    // ---------- Tela do aparelho (resolução e densidade) ----------

    /**
     * Resolução e densidade, sem precisar do Second Screen: com root ("wm size"/"wm density") ou sem root, com a
     * permissão WRITE_SECURE_SETTINGS dada uma vez pelo PC (o mesmo jeito do Second Screen).
     */
    private fun secaoTela(p: PainelConfig) {
        p.secao("Tela do aparelho")
        val comRoot = Root.ligado(this)
        val suportado = TelaSemRoot.suportado()
        val semRoot = !comRoot && suportado && TelaSemRoot.permitido(this)
        val t = TelaSemRoot.tela(this)
        p.nota("Agora: ${t.atualL}×${t.atualA}, densidade ${t.densAtual}.  Original: ${t.fisicaL}×${t.fisicaA}, densidade ${t.densFisica}." +
            if (comRoot) "  (com root)" else if (semRoot) "  (sem root, com a permissão liberada)" else "")
        if (comRoot || semRoot) {
            val (tv, densTv) = RecursosRoot.MODO_TV
            val noModoTv = t.atualL == tv.curto && t.atualA == tv.longo && t.densAtual == densTv
            p.acao("Modo TV", "1280×720 com densidade 240, como o perfil de TV do Second Screen: preenche a TV e deixa os " +
                "emuladores mais leves." + if (noModoTv) " (ligado agora)" else "", if (noModoTv) "Ligado" else "Ligar", fecharAntes = true) {
                trocarTela(tv, densTv) {}
            }
            // A escolha marcada é a da tela agora (pode ter sido mudada pelo Second Screen ou pelo PC).
            val resAtual = if (t.atualL == t.fisicaL && t.atualA == t.fisicaA) 0
                else RecursosRoot.RESOLUCOES.indexOfFirst { it.curto == t.atualL && it.longo == t.atualA }.coerceAtLeast(0)
            val densAtual = if (t.densAtual == t.densFisica) 0 else RecursosRoot.DENSIDADES.indexOfFirst { it.first == t.densAtual }.coerceAtLeast(0)
            p.opcoes("Resolução da tela", "As de TV são 16:9 (preenchem a TV). Menor = mais leve para os emuladores. " +
                "Depois de trocar, você tem 15 s para confirmar; senão volta.",
                RecursosRoot.RESOLUCOES.map { it.rotulo }, resAtual) { i ->
                p.fechar()
                trocarTela(RecursosRoot.RESOLUCOES[i], null) {}
            }
            p.opcoes("Densidade (tamanho de tudo)", "Menor = tudo menor na tela. Com resolução de 720p, 240 deixa como o Second Screen. " +
                "Também com 15 s para confirmar.", RecursosRoot.DENSIDADES.map { it.second }, densAtual) { i ->
                p.fechar()
                trocarTela(null, RecursosRoot.DENSIDADES[i].first) {}
            }
            p.acao("Voltar tudo ao original", "Resolução e densidade de fábrica do aparelho.", "Restaurar", fecharAntes = true) {
                trocarTela(RecursosRoot.RESOLUCOES[0], 0) {}
            }
        } else {
            if (!suportado) p.nota("Este Android não deixa apps trocarem a resolução sem root.")
            else {
                p.nota("Sem root, o Android precisa de uma permissão dada uma vez (a mesma que o Second Screen usa): pelo Shizuku, " +
                    "sem PC, ou por um comando no PC.")
                secaoShizuku(p)
                p.nota("Pelo PC (cabo ou depuração sem fio), rode:\n${TelaSemRoot.comando(this)}\nDepois, abra estas configurações de novo.")
                p.acao("Copiar o comando", null, "Copiar") {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("comando", TelaSemRoot.comando(this)))
                    Toast.makeText(this, "Comando copiado", Toast.LENGTH_SHORT).show()
                }
            }
            p.acao("Zoom da tela do Android", "Sem a permissão, o Android ainda deixa mudar o tamanho de tudo (zoom) nas configurações de tela.",
                "Abrir") { abrir(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        }
    }

    /**
     * Liberar a resolução sem PC pelo Shizuku: um passo de cada vez, conforme o que falta (instalar, ligar pela
     * Depuração sem fio, permitir o launcher, liberar). Depois de liberado, o Shizuku não é mais necessário.
     */
    private fun secaoShizuku(p: PainelConfig) {
        val instalado = TelaSemRoot.shizukuInstalado(this)
        val rodando = TelaSemRoot.shizukuRodando()
        when {
            !instalado -> p.acao("Liberar sem PC: instalar o Shizuku", "App gratuito (GitHub ou Play Store) que usa a Depuração sem fio do " +
                "próprio celular. Depois de instalar, volte aqui.", "Baixar", fecharAntes = true) {
                abrir(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")))
            }
            !rodando -> p.acao("Liberar sem PC: ligar o Shizuku", "No Shizuku: \"Iniciar via Depuração sem fio\" (o próprio app explica o " +
                "pareamento, uma vez só). Com ele ligado, volte aqui.", "Abrir Shizuku", fecharAntes = true) {
                val i = listOf("moe.shizuku.privileged.api", "com.thedjchi.shizuku").firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                if (i != null) abrir(i) else abrir(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            else -> p.acao("Liberar sem PC pelo Shizuku", "O Shizuku está ligado. Toque para liberar a resolução (o Shizuku pergunta se " +
                "permite o GameLauncherTV).", "Liberar", fecharAntes = true) {
                TelaSemRoot.pedirShizuku { ok ->
                    if (!ok) {
                        ui.post { Toast.makeText(this, "O Shizuku não foi permitido", Toast.LENGTH_SHORT).show() }
                        return@pedirShizuku
                    }
                    Thread {
                        val liberou = TelaSemRoot.liberarPeloShizuku(this)
                        ui.post {
                            Toast.makeText(this, if (liberou) "Resolução liberada. O Shizuku não é mais necessário para isso."
                                else "Não foi possível liberar pelo Shizuku", Toast.LENGTH_LONG).show()
                            if (liberou) abrirConfiguracoes("Tela do aparelho")
                        }
                    }.start()
                }
            }
        }
    }

    // ---------- Menu rápido (botão PS) sem root ----------

    /**
     * Botão PS e menu rápido sem root: pelo serviço de acessibilidade, que a pessoa liga uma vez. Aqui ficam o
     * estado, o modo do botão e as permissões extras de cada opção do menu (brilho, Não perturbe, Bluetooth).
     */
    private fun secaoMenuRapido(p: PainelConfig) {
        p.secao("Menu rápido")
        if (Root.ligado(this)) {
            p.nota("Com os recursos root ligados, o botão PS e o menu rápido usam o root (Configurações › Root).")
            return
        }
        val ativo = ServicoAcessibilidade.ativo
        p.acao("Ativar acessibilidade", if (ativo) "Ativada. Segure PS em qualquer jogo para abrir o menu rápido."
            else "Desativada. Sem root, o botão PS e o menu rápido precisam dela: ligue \"Botão PS e menu rápido\" na tela que abre. " +
                "Se a opção estiver cinza: Configurações › Apps › GameLauncherTV › ⋮ › Permitir configurações restritas.",
            if (ativo) "Abrir" else "Ativar", fecharAntes = true) {
            // Já abre na tela do serviço, quando o aparelho deixa; senão, na lista de Acessibilidade.
            abrirTelaDoServico()
        }
        p.opcoes("Botão PS", "Como no PS4: apertar alterna entre o jogo e o launcher e segurar abre o menu rápido.",
            ServicoRoot.MODOS_PS, prefs.getInt("botao_ps", 0)) { prefs.edit().putInt("botao_ps", it).apply() }
        p.opcoes("PS + Share fecha o jogo", "Sem root, alguns emuladores continuam abertos em segundo plano.",
            listOf("Desligado", "Ligado"), if (prefs.getBoolean("ps_share_fecha", true)) 1 else 0) {
            prefs.edit().putBoolean("ps_share_fecha", it == 1).apply()
        }
        p.opcoes("Dois toques no PS abrem o último jogo", "Com isso ligado, o toque simples espera um instante (0,35 s) para ver se vem o segundo.",
            listOf("Desligado", "Ligado"), if (prefs.getBoolean("ps_duplo", true)) 1 else 0) {
            prefs.edit().putBoolean("ps_duplo", it == 1).apply()
        }
        p.opcoes("PS + L1 tira captura de tela", "Salva em Imagens › GameLauncherTV.",
            listOf("Desligado", "Ligado"), if (prefs.getBoolean("ps_l1_captura", true)) 1 else 0) {
            prefs.edit().putBoolean("ps_l1_captura", it == 1).apply()
        }
        if (!Settings.System.canWrite(this)) {
            p.acao("Permitir ajustar o brilho pelo menu", "Ligue \"Permitir alteração das configurações do sistema\".", "Permitir", fecharAntes = true) {
                abrir(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
        }
        if (!getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            p.acao("Permitir o Não perturbe pelo menu", "Libere o acesso ao Não perturbe para o GameLauncherTV.", "Permitir", fecharAntes = true) {
                abrir(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            p.acao("Mostrar os aparelhos Bluetooth no menu", "Permissão \"Dispositivos próximos\".", "Permitir", fecharAntes = true) {
                requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 7)
            }
        }
    }

    private var psSemServicoDesde = 0L

    /** Abre direto a tela do serviço "Botão PS e menu rápido" (Android 11+); se não der, a lista de Acessibilidade. */
    private fun abrirTelaDoServico() {
        val componente = android.content.ComponentName(this, ServicoAcessibilidade::class.java)
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, componente.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", componente.flattenToString())
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", componente.flattenToString()) }))
        }
    }

    /** O PS não tem quem o leia: explica e leva direto para ligar o serviço. */
    private fun pedirServicoAcessibilidade() {
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Ligar o botão PS e o menu rápido")
            .setMessage("Sem root, o botão PS funciona por um serviço de acessibilidade do GameLauncherTV (ele só reage ao PS e ao Share).\n\n" +
                "Na próxima tela, ligue \"Botão PS e menu rápido\" e confirme. Se a opção estiver cinza: Configurações › Apps › " +
                "GameLauncherTV › ⋮ › Permitir configurações restritas, e tente de novo.")
            .setPositiveButton("Ligar agora") { _, _ -> abrirTelaDoServico() }
            .setNegativeButton("Agora não", null)
            .show()
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b -> b.isFocusableInTouchMode = true; b.post { b.requestFocus() } }
    }

    // ---------- Recursos root ----------

    private fun secaoRoot(p: PainelConfig) {
        p.secao("Root")
        val ligado = Root.ligado(this)
        p.opcoes("Recursos root", "Carregamento, menu rápido do controle, modo desempenho, backup dos saves, emuladores do GitHub e tela. " +
            "No KernelSU, libere o GameLauncherTV em Superusuário antes de ligar; no Magisk, aceite o pedido que aparecer.",
            listOf("Desligado", "Ligado"), if (ligado) 1 else 0) { i ->
            if ((i == 1) != ligado) {
                p.fechar()
                ligarRoot(i == 1)
            }
        }
        if (!ligado) {
            p.nota("Com root, o launcher pode: segurar a carga da bateria ou alimentar o aparelho direto da tomada; abrir um menu " +
                "rápido por cima do jogo segurando PS (fechar o jogo, brilho, volume, captura); liberar memória ao abrir um jogo; " +
                "fazer backup dos saves; instalar emuladores do GitHub; e mudar resolução, densidade e barra de navegação.")
            return
        }
        fun ligadoOuNao(chave: String, padrao: Boolean = false) = if (prefs.getBoolean(chave, padrao)) 1 else 0
        fun salvar(chave: String, sim: Boolean) = prefs.edit().putBoolean(chave, sim).apply()

        val interruptor = prefs.getString("carga_interruptor", null)
        p.opcoes("Carregamento", "Limitar: para no limite e volta a carregar 10% abaixo. Direto da tomada: na tomada o aparelho não " +
            "carrega a bateria e roda pela fonte (com carregador USB PD de 25 W ou mais; com outros, só pausa a carga). Abaixo de 20% " +
            "ele carrega até 30% por segurança." + (interruptor?.let { "\nControle usado: $it." } ?: ""),
            RecursosRoot.MODOS_CARGA, prefs.getInt("carga_modo", 0)) {
            prefs.edit().putInt("carga_modo", it).apply()
            reaplicarCarga()
        }
        p.opcoes("Limite da carga", "Usado em \"Limitar a carga\".", RecursosRoot.LIMITES.map { "$it%" }, prefs.getInt("carga_limite", 2)) {
            prefs.edit().putInt("carga_limite", it).apply()
            reaplicarCarga()
        }

        p.opcoes("Botão PS do controle", "Funciona por cima de qualquer jogo ou app. Como no PS4: apertar vai para o início e segurar " +
            "abre o menu rápido (voltar ao jogo, fechar o jogo, brilho, volume, bateria, captura de tela).",
            ServicoRoot.MODOS_PS, prefs.getInt("botao_ps", 0)) { prefs.edit().putInt("botao_ps", it).apply() }
        p.opcoes("PS + Share fecha o jogo", "Fecha o jogo ou emulador na frente na hora (útil quando ele trava) e volta ao launcher.",
            listOf("Desligado", "Ligado"), ligadoOuNao("ps_share_fecha", true)) { salvar("ps_share_fecha", it == 1) }

        p.opcoes("Modo desempenho ao abrir um jogo", "Antes de abrir: fecha apps em segundo plano, libera memória e liga o modo de " +
            "desempenho do Android para o jogo. Tudo volta ao normal quando você retorna ao launcher.",
            listOf("Desligado", "Ligado"), ligadoOuNao("desempenho_jogo")) { salvar("desempenho_jogo", it == 1) }
        p.opcoes("Taxa da tela no jogo", "Com o modo desempenho: trava a tela em 60 Hz (economiza) ou 120 Hz (mais fluido).",
            RecursosRoot.TAXAS, prefs.getInt("taxa_jogo", 0)) { prefs.edit().putInt("taxa_jogo", it).apply() }
        p.opcoes("Silenciar notificações no jogo", "Com o modo desempenho: liga o Não perturbe durante o jogo.",
            listOf("Desligado", "Ligado"), ligadoOuNao("silenciar_jogo")) { salvar("silenciar_jogo", it == 1) }

        p.acao("Fazer backup dos saves agora", "Dados dos emuladores em ${Saves.PASTA} (os 3 últimos de cada um).", "Fazer backup") {
            val emus = Roms.emuladoresInstalados(this)
            if (emus.isEmpty()) return@acao Toast.makeText(this, "Nenhum emulador instalado.", Toast.LENGTH_SHORT).show()
            Toast.makeText(this, "Fazendo backup de ${emus.size} emulador(es)…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = Saves.fazer(emus)
                val ok = r.count { it.second }
                prefs.edit().putLong("saves_ultimo", System.currentTimeMillis()).apply()
                ui.post { Toast.makeText(this, "Backup pronto: $ok de ${r.size} emulador(es).", Toast.LENGTH_LONG).show() }
            }.start()
        }
        p.opcoes("Backup automático dos saves", "Feito quando você volta de um jogo, se já passou o prazo.",
            listOf("Desligado", "Diário", "Semanal"), prefs.getInt("saves_auto", 0)) { prefs.edit().putInt("saves_auto", it).apply() }
        p.acao("Restaurar saves…", "Escolher um backup e voltar os saves do emulador para ele.", "Escolher", fecharAntes = true) {
            restaurarSaves()
        }

        p.acao("Instalar e atualizar emuladores…", "Versões oficiais do GitHub: " +
            AtualizadorEmuladores.LISTA.joinToString(", ") { it.nome } + ".", "Ver", fecharAntes = true) { emuladoresGithub() }

        p.opcoes("Barra de navegação", "Escondida: navegação por gestos sem a barrinha embaixo (deslize da borda para voltar).",
            listOf("Normal", "Escondida"), ligadoOuNao("nav_escondida")) { i ->
            salvar("nav_escondida", i == 1)
            Thread { RecursosRoot.esconderNavegacao(this, i == 1) }.start()
        }
        p.acao("Fixar como tela inicial", "O botão Início sempre volta para o GameLauncherTV, sem perguntar.", "Fixar") {
            Thread {
                val ok = RecursosRoot.travarInicio(this)
                ui.post { Toast.makeText(this, if (ok) "GameLauncherTV fixado como tela inicial" else "Não foi possível fixar", Toast.LENGTH_SHORT).show() }
            }.start()
        }
    }

    /** Liga ou desliga os recursos root: pede o acesso, dá a permissão do menu rápido e sobe (ou para) o serviço. */
    private fun ligarRoot(sim: Boolean) {
        Thread {
            if (sim) {
                if (!Root.pedir()) {
                    ui.post {
                        Toast.makeText(this, "Sem acesso root. No KernelSU: abra o app KernelSU › Superusuário e libere o GameLauncherTV. " +
                            "No Magisk: aceite o pedido.", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                // Menu rápido por cima dos jogos e a lista de aparelhos Bluetooth dele.
                Root.rodar("appops set $packageName SYSTEM_ALERT_WINDOW allow; pm grant $packageName android.permission.BLUETOOTH_CONNECT")
                prefs.edit().putBoolean("root_ligado", true).putString("carga_interruptor", RecursosRoot.interruptorCarga()).apply()
            } else {
                // Desfaz o que estiver mudado antes de largar o root.
                RecursosRoot.soltarCarga(this)
                RecursosRoot.fimDoJogo(this)
                if (prefs.getBoolean("nav_escondida", false)) RecursosRoot.esconderNavegacao(this, false)
                prefs.edit().putBoolean("root_ligado", false).putBoolean("nav_escondida", false).apply()
                Root.fechar()
            }
            ui.post {
                ServicoRoot.atualizar(this)
                Toast.makeText(this, if (sim) "Recursos root ligados" else "Recursos root desligados", Toast.LENGTH_SHORT).show()
                abrirConfiguracoes("Root")
            }
        }.start()
    }

    /** Modo ou limite da carga mudou: libera e aplica a regra nova com o nível atual. */
    private fun reaplicarCarga() {
        Thread {
            RecursosRoot.soltarCarga(this)
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@Thread
            val nivel = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            if (nivel >= 0) RecursosRoot.aplicarCarga(this, nivel, b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }.start()
    }

    /**
     * Troca a resolução [r] e/ou a densidade e pergunta se fica; sem resposta em 15 s, volta ao que era (uma
     * resolução ruim pode deixar a tela difícil de usar). A pergunta aparece depois que a tela terminou de mudar
     * (antes ela sumia no meio da troca), com o foco em "Manter". [aoManter] grava a escolha.
     */
    private fun trocarTela(r: RecursosRoot.Resolucao?, densidade: Int?, aoManter: () -> Unit) {
        // Com root, "wm size"/"wm density"; sem root, o serviço de janelas com a permissão dada pelo PC.
        val comRoot = Root.ligado(this)
        Thread {
            val antes = if (comRoot) RecursosRoot.tela() else TelaSemRoot.tela(this)
            if (antes == null) {
                ui.post { Toast.makeText(this, "Não foi possível ler a tela", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val ok = if (comRoot) RecursosRoot.definirResolucao(r, densidade) else TelaSemRoot.definir(this, r, densidade)
            fun voltarTela() = Thread { if (comRoot) RecursosRoot.voltarTela(antes) else TelaSemRoot.voltar(this, antes) }.start()
            if (!ok) {
                ui.post { Toast.makeText(this, "Não foi possível trocar", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            // A tela leva um instante para trocar (a imagem da TV também): pergunta depois.
            ui.postDelayed({
                if (isDestroyed) return@postDelayed
                var resta = 15
                var decidido = false
                val d = android.app.AlertDialog.Builder(this, temaDialogo())
                    .setTitle("Manter esta configuração?")
                    .setMessage("Voltando em $resta s…")
                    .setPositiveButton("Manter") { _, _ ->
                        decidido = true
                        aoManter()
                        // As medidas do launcher seguem a tela nova.
                        recreate()
                    }
                    .setNegativeButton("Voltar") { _, _ -> decidido = true; voltarTela() }
                    .setCancelable(false)
                    .show()
                // Com controle, OK já confirma: o foco vai para "Manter" mesmo vindo de um toque na tela.
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b ->
                    b.isFocusableInTouchMode = true
                    b.post { b.requestFocus() }
                }
                // Só direcional, OK e B mexem aqui: Start, Select e afins não podem confirmar nem desfazer sem querer.
                d.setOnKeyListener { _, codigo, _ ->
                    codigo !in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)
                }
                val conta = object : Runnable {
                    override fun run() {
                        if (decidido || !d.isShowing) return
                        resta--
                        if (resta <= 0) {
                            decidido = true
                            d.dismiss()
                            voltarTela()
                            return
                        }
                        d.setMessage("Voltando em $resta s…")
                        ui.postDelayed(this, 1000)
                    }
                }
                ui.postDelayed(conta, 1000)
            }, 1500)
        }.start()
    }

    /** Lista dos emuladores do GitHub com a versão instalada e a última; escolher instala ou atualiza. */
    private fun emuladoresGithub() {
        Toast.makeText(this, "Consultando o GitHub…", Toast.LENGTH_SHORT).show()
        Thread {
            val lista = AtualizadorEmuladores.consultar(this)
            ui.post {
                val p = PainelConfig(this, s, "Emuladores do GitHub", paginas = false)
                p.nota("Versões oficiais das releases de cada projeto, instaladas com root sem pedir confirmação. " +
                    "Se a versão instalada veio de outro lugar (outra assinatura), não dá para atualizar por cima.")
                for (st in lista) {
                    val estado = when {
                        st.ultima == null -> "Não foi possível consultar agora"
                        st.apk == null -> "A última versão (${st.ultima}) não tem APK de Android"
                        st.instalada == null -> "Não instalado · última ${st.ultima}"
                        st.atualizado -> "Atualizado · ${st.instalada}"
                        else -> "Instalado ${st.instalada} · nova ${st.ultima}"
                    }
                    val rotulo = when {
                        st.apk == null -> "Abrir"
                        st.instalada == null -> "Instalar"
                        st.atualizado -> "Reinstalar"
                        else -> "Atualizar"
                    }
                    p.acao("${st.emu.nome} (${st.emu.plataformas})", estado, rotulo, fecharAntes = true) {
                        if (st.apk == null) return@acao
                        Toast.makeText(this, "Baixando ${st.emu.nome}…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val erro = AtualizadorEmuladores.instalar(this, st)
                            ui.post {
                                Toast.makeText(this, erro ?: "${st.emu.nome} ${st.ultima} instalado", Toast.LENGTH_LONG).show()
                                if (erro == null) carregarApps()
                            }
                        }.start()
                    }
                }
                p.mostrar()
            }
        }.start()
    }

    /** Backups guardados; escolher um restaura os saves daquele emulador (com confirmação). */
    private fun restaurarSaves() {
        Thread {
            val copias = Saves.listar()
            ui.post {
                if (copias.isEmpty()) return@post Toast.makeText(this, "Nenhum backup ainda. Use \"Fazer backup dos saves agora\".", Toast.LENGTH_LONG).show()
                val p = PainelConfig(this, s, "Restaurar saves", paginas = false)
                val formato = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                for (c in copias) {
                    val nome = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.pacote, 0)).toString() } catch (_: Exception) { c.pacote }
                    p.acao(nome, "${formato.format(java.util.Date(c.quando))} · ${Saves.tamanhoLegivel(c.tamanho)}", "Restaurar", fecharAntes = true) {
                        android.app.AlertDialog.Builder(this, temaDialogo())
                            .setTitle("Restaurar os saves de $nome?")
                            .setMessage("O emulador é fechado e os arquivos do backup substituem os atuais. Se quiser guardar os de agora, " +
                                "faça um backup antes.")
                            .setPositiveButton("Restaurar") { _, _ ->
                                Toast.makeText(this, "Restaurando…", Toast.LENGTH_SHORT).show()
                                Thread {
                                    val ok = Saves.restaurar(c)
                                    ui.post { Toast.makeText(this, if (ok) "Saves de $nome restaurados" else "Não foi possível restaurar", Toast.LENGTH_LONG).show() }
                                }.start()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
                p.mostrar()
            }
        }.start()
    }

    /** Lista com caixinhas: quais apps ficam em "Apps favoritos". */
    private fun escolherFavoritos() {
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val atuais = favoritosDe(visiveis).map { it.pacote }.toSet()
        val candidatos = visiveis.sortedWith(compareBy({ it.pacote !in atuais }, { it.nome.lowercase() }))
        val marcados = BooleanArray(candidatos.size) { candidatos[it].pacote in atuais }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Apps favoritos")
            .setMultiChoiceItems(candidatos.map { it.nome }.toTypedArray(), marcados) { _, i, sim -> marcados[i] = sim }
            .setPositiveButton("Salvar") { _, _ ->
                val fabrica = Catalogo.favoritos(visiveis).map { it.pacote }.toSet()
                val add = favoritosAdicionados().toMutableSet()
                val fora = favoritosTirados().toMutableSet()
                candidatos.forEachIndexed { i, app ->
                    if (marcados[i]) {
                        fora.remove(app.pacote)
                        if (app.pacote !in fabrica) add += app.pacote
                    } else {
                        add.remove(app.pacote)
                        if (app.pacote in fabrica) fora += app.pacote
                    }
                }
                prefs.edit().putStringSet("fav_add", add).putStringSet("fav_fora", fora).apply()
                remontar()
                carregarCapas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Lista com caixinhas: desmarcado = escondido da tela inicial. */
    private fun escolherOcultos() {
        val candidatos = apps.sortedWith(compareBy({ it.pacote !in ocultos() }, { it.nome.lowercase() }))
        val marcados = BooleanArray(candidatos.size) { candidatos[it].pacote !in ocultos() }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Apps mostrados na tela inicial")
            .setMultiChoiceItems(candidatos.map { it.nome }.toTypedArray(), marcados) { _, i, sim -> marcados[i] = sim }
            .setPositiveButton("Salvar") { _, _ ->
                val novo = candidatos.filterIndexed { i, _ -> !marcados[i] }.map { it.pacote }.toSet()
                prefs.edit().putStringSet("ocultos", novo).apply()
                remontar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Foto da galeria (seletor do Android, sem permissão de arquivos) ----------

    private var aoEscolherFoto: ((String) -> Unit)? = null

    @Suppress("DEPRECATION")
    private fun escolherFoto(aoEscolher: (String) -> Unit) {
        aoEscolherFoto = aoEscolher
        try {
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PEDIDO_FOTO)
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir a galeria", Toast.LENGTH_SHORT).show()
        }
    }

    private fun capaDaGaleria(id: String, emPe: Boolean = false) = escolherFoto { url ->
        personalizado.edit().putString(chaveCapa(id, emPe), url).apply()
        depoisDePersonalizar()
        Toast.makeText(this, "Capa trocada pela foto", Toast.LENGTH_SHORT).show()
    }

    // ---------- Descanso de tela: paisagens na tela inteira depois de 1 minuto sem mexer ----------

    private var descanso: FrameLayout? = null
    private var descansoFoto: FundoImagem? = null
    private var descansoData: TextView? = null
    private var descansoCredito: TextView? = null
    private var indiceDescanso = -1
    private var engolindoToque = false
    private val abrirDescanso = Runnable { mostrarDescanso() }
    private val girarDescanso = object : Runnable {
        override fun run() {
            proximaFotoDescanso()
            ui.postDelayed(this, FundoImagem.INTERVALO_DESCANSO)
        }
    }

    private fun construirDescanso(): FrameLayout {
        val d = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        val foto = FundoImagem(this, FundoImagem.Veu.DESCANSO)
        descansoFoto = foto
        d.addView(foto, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val sombra = Color.argb(160, 0, 0, 0)
        val hora = texto(120f, Color.WHITE, 300).apply { setShadowLayer(12f * s, 0f, 2f * s, sombra) }
        relogios += hora
        d.addView(hora, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = px(107f)
            bottomMargin = px(150f)
        })
        val data = texto(34f, Color.argb(235, 255, 255, 255), 400).apply { setShadowLayer(8f * s, 0f, 2f * s, sombra) }
        descansoData = data
        d.addView(data, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = px(110f)
            bottomMargin = px(100f)
        })
        val credito = texto(20f, Color.argb(200, 255, 255, 255), 400).apply {
            setShadowLayer(6f * s, 0f, s, sombra)
            umaLinha()
        }
        descansoCredito = credito
        d.addView(credito, FrameLayout.LayoutParams(px(900f), -2, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = px(107f)
            bottomMargin = px(60f)
        })
        return d
    }

    private fun descansoAtivo() = descanso?.visibility == View.VISIBLE

    /**
     * O celular apaga a tela em 30 s, antes dos 5 min do descanso. Por isso, com o launcher na
     * frente, a tela fica acesa; depois de 10 min de descanso ela é liberada e o celular apaga.
     */
    private val liberarTela = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun reiniciarOcio() {
        ui.removeCallbacks(abrirDescanso)
        ui.removeCallbacks(liberarTela)
        val ocio = tempoOcio()
        if (naTela && ocio > 0) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.postDelayed(abrirDescanso, ocio)
        } else {
            // Descanso desligado: a tela segue o tempo normal do celular.
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun mostrarDescanso() {
        val d = descanso ?: return
        // Não cobre a tela com menu, janela ou teclado abertos, nem no meio de um arrasto.
        if (!naTela || !hasWindowFocus() || movendo != null || arrastando != null || telaAtual == Tela.BUSCA) {
            reiniciarOcio()
            return
        }
        comPaisagens { _ ->
            if (!naTela || descansoAtivo()) return@comPaisagens
            pararDestaque()
            atualizarDataDescanso()
            d.alpha = 0f
            d.visibility = View.VISIBLE
            d.animate().alpha(1f).setDuration(900).start()
            proximaFotoDescanso()
            ui.postDelayed(girarDescanso, FundoImagem.INTERVALO_DESCANSO)
            ui.postDelayed(liberarTela, DESCANSO_MAXIMO)
        }
    }

    private fun proximaFotoDescanso() {
        val lista = paisagens
        val foto = descansoFoto ?: return
        if (lista.isEmpty()) return
        indiceDescanso = (indiceDescanso + 1) % lista.size
        val p = lista[indiceDescanso]
        Imagens.carregar(this, p.url, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) { b ->
            if (descansoAtivo()) {
                foto.mostrar(b, p.url, 1500)
                descansoCredito?.text = p.credito
            }
        }
    }

    private fun esconderDescanso(animar: Boolean = true) {
        ui.removeCallbacks(girarDescanso)
        val d = descanso ?: return
        if (d.visibility != View.VISIBLE) return
        descansoFoto?.pararAnimacoes()
        // Ao sair, solta as fotos em tela cheia do descanso (voltam do disco na próxima vez).
        val fim = Runnable {
            d.visibility = View.GONE
            descansoFoto?.limpar()
        }
        if (animar) d.animate().alpha(0f).setDuration(300).withEndAction(fim).start() else fim.run()
        iniciarDestaque()
        reiniciarOcio()
    }

    private fun atualizarDataDescanso() {
        val c = Calendar.getInstance()
        val dias = arrayOf("domingo", "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado")
        val meses = arrayOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
            "setembro", "outubro", "novembro", "dezembro")
        descansoData?.text = "${dias[c.get(Calendar.DAY_OF_WEEK) - 1]}, ${c.get(Calendar.DAY_OF_MONTH)} de ${meses[c.get(Calendar.MONTH)]}"
    }

    /** Qualquer toque tira o descanso (e não abre nada sem querer); fora dele, reinicia a contagem. */
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && descansoAtivo()) {
            engolindoToque = true
            esconderDescanso()
            return true
        }
        if (engolindoToque) {
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                engolindoToque = false
            }
            return true
        }
        reiniciarOcio()
        return super.dispatchTouchEvent(e)
    }

    // ---------- Plano de fundo da aba Jogos ----------

    private var fundoJogos: FundoImagem? = null
    private var pedidoFundo = 0
    private val buscaFundos = Executors.newSingleThreadExecutor()

    /**
     * Jogo selecionado: a capa do card vira o fundo na hora; depois, se o SteamGridDB tiver a arte
     * larga ("hero") do jogo, ela entra no lugar. Capa escolhida à mão continua sendo a escolhida.
     */
    private fun selecionarJogo(capa: String?, nome: String, anos: IntRange?, id: String) {
        selecionarFundo(fundoJogos ?: return, capa, nome, anos, id)
    }

    private fun selecionarFundo(fundo: FundoImagem, capa: String?, nome: String, anos: IntRange?, id: String) {
        val w = fundo.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = fundo.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val pedido = ++pedidoFundo
        fun usar(url: String) = Imagens.carregar(this, url, w, h) { b -> if (pedido == pedidoFundo) fundo.mostrar(b, url) }
        // Arte de fundo escolhida à mão vale primeiro; capa escolhida à mão também fica (sem trocar pela automática).
        fundoManual(id)?.let { usar(it); return }
        capa?.let { usar(it) }
        if (capaManual(id) != null) return
        buscaFundos.execute {
            val heroi = Capas.heroi(this, nome, anos) ?: return@execute
            ui.post { if (pedido == pedidoFundo) usar(heroi) }
        }
    }

    // ---------- Reordenar: segurar e arrastar (toque) ou "Mover" + setas (controle) ----------

    private var arrastando: View? = null
    private var menuSemArrastar: (() -> Unit)? = null
    private var inicioArrasteX = Float.NaN
    private var moveuArraste = false
    private var indiceInicial = -1
    private var movendo: View? = null

    /** O card (ou banner com nome) que é filho direto de uma fileira reordenável, se houver. */
    private fun itemNaFileira(v: View): View? {
        var x = v
        while (true) {
            val p = x.parent as? View ?: return null
            if ((p.tag as? String)?.startsWith("ordem:") == true) return x
            x = p
        }
    }

    /** Toque longo: no toque, começa a arrastar (soltar sem mover abre o menu); no controle, abre o menu. */
    private fun segurar(v: View, menu: () -> Unit) {
        val item = itemNaFileira(v)
        if (item == null || !telas.isInTouchMode) {
            menu()
            return
        }
        val linha = item.parent as ViewGroup
        arrastando = item
        menuSemArrastar = menu
        inicioArrasteX = Float.NaN
        moveuArraste = false
        indiceInicial = linha.indexOfChild(item)
        val sombra = (item as? ViewGroup)?.getChildAt(0) ?: item
        if (item.startDragAndDrop(null, View.DragShadowBuilder(sombra), item, 0)) item.alpha = 0.3f else menu()
    }

    private val ouvinteArraste = View.OnDragListener { v, e ->
        val linha = v as LinearLayout
        val item = arrastando
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> item != null && item.parent === linha
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (item != null && item.parent === linha) {
                    if (inicioArrasteX.isNaN()) inicioArrasteX = e.x
                    if (abs(e.x - inicioArrasteX) > px(40f)) moveuArraste = true
                    if (moveuArraste) {
                        // Posição = quantos outros cards têm o centro antes do dedo.
                        var alvo = 0
                        for (i in 0 until linha.childCount) {
                            val c = linha.getChildAt(i)
                            if (c !== item && (c.left + c.width / 2f) < e.x) alvo++
                        }
                        if (alvo != linha.indexOfChild(item)) {
                            linha.removeView(item)
                            linha.addView(item, alvo)
                        }
                        rolarNaBorda(linha, e.x)
                    }
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if (item != null && item.parent === linha) terminarArraste(linha, item)
                true
            }
            else -> true
        }
    }

    private fun rolarNaBorda(linha: View, x: Float) {
        val rolagem = linha.parent as? HorizontalScrollView ?: return
        val naTela = x - rolagem.scrollX
        when {
            naTela > rolagem.width - px(220f) -> rolagem.smoothScrollBy(px(70f), 0)
            naTela < px(260f) -> rolagem.smoothScrollBy(-px(70f), 0)
        }
    }

    private fun terminarArraste(linha: ViewGroup, item: View) {
        item.alpha = 1f
        arrastando = null
        val menu = menuSemArrastar
        menuSemArrastar = null
        when {
            moveuArraste && linha.indexOfChild(item) != indiceInicial -> salvarOrdem(linha)
            !moveuArraste && menu != null -> ui.post { menu() }
        }
    }

    /** Modo "Mover" do controle: ◀ ▶ trocam de lugar, OK ou Voltar terminam. */
    private fun moverComControle(item: View) {
        movendo = item
        item.alpha = 0.7f
        Toast.makeText(this, "Use ◀ ▶ para mover e OK para terminar", Toast.LENGTH_SHORT).show()
    }

    private fun passo(item: View, delta: Int) {
        val linha = item.parent as? ViewGroup ?: return
        val novo = (linha.indexOfChild(item) + delta).coerceIn(0, linha.childCount - 1)
        if (novo == linha.indexOfChild(item)) return
        linha.removeView(item)
        linha.addView(item, novo)
        val alvo = (item as? ViewGroup)?.getChildAt(0) ?: item
        alvo.post {
            alvo.requestFocus()
            alvo.requestRectangleOnScreen(android.graphics.Rect(0, 0, alvo.width, alvo.height))
        }
    }

    private fun terminarMover() {
        val item = movendo ?: return
        movendo = null
        item.alpha = 1f
        (item.parent as? ViewGroup)?.let { salvarOrdem(it) }
        Toast.makeText(this, "Posição salva", Toast.LENGTH_SHORT).show()
    }

    private fun opcaoMover(v: View) {
        val item = itemNaFileira(v) ?: return
        if (telas.isInTouchMode) {
            Toast.makeText(this, "Segure o card e arraste para o lado", Toast.LENGTH_LONG).show()
        } else {
            moverComControle(item)
        }
    }

    private fun salvarOrdem(linha: ViewGroup) {
        val chave = (linha.tag as? String)?.removePrefix("ordem:") ?: return
        val ids = (0 until linha.childCount).mapNotNull { linha.getChildAt(it).tag as? String }
        prefs.edit().putString("ordem_$chave", ids.joinToString("\n")).apply()
        // A mesma ordem aparece em outras telas (ex.: Jogos no Início e na aba Jogos): remonta as outras.
        cacheTelas.keys.filter { it != telaAtual }.forEach { descartar(it) }
    }

    /** Aplica a ordem salva; itens novos vão para o fim, na ordem em que vieram. */
    private fun <T> ordenar(chave: String, lista: List<T>, id: (T) -> String): List<T> {
        val salva = prefs.getString("ordem_$chave", null)?.split("\n") ?: return lista
        val pos = salva.withIndex().associate { it.value to it.index }
        return lista.sortedBy { pos[id(it)] ?: Int.MAX_VALUE }
    }

    // ---------- Favoritos (a pessoa adiciona e tira) ----------

    private fun favoritosAdicionados() = prefs.getStringSet("fav_add", emptySet()).orEmpty()
    private fun favoritosTirados() = prefs.getStringSet("fav_fora", emptySet()).orEmpty()

    private fun favoritosDe(visiveis: List<AppInfo>): List<AppInfo> {
        val fabrica = Catalogo.favoritos(visiveis)
        val pacotesFabrica = fabrica.map { it.pacote }.toSet()
        val extras = visiveis.filter { it.pacote in favoritosAdicionados() && it.pacote !in pacotesFabrica }
            .sortedBy { it.nome.lowercase() }
        val lista = (fabrica + extras).filter { it.pacote !in favoritosTirados() }
        return ordenar(ORDEM_FAVORITOS, lista, ::idApp)
    }

    private fun ehFavorito(app: AppInfo) = favoritosDe(apps).any { it.pacote == app.pacote }

    private fun definirFavorito(app: AppInfo, sim: Boolean) {
        val add = favoritosAdicionados().toMutableSet()
        val fora = favoritosTirados().toMutableSet()
        if (sim) {
            fora.remove(app.pacote)
            add += app.pacote
        } else {
            add.remove(app.pacote)
            fora += app.pacote
        }
        prefs.edit().putStringSet("fav_add", add).putStringSet("fav_fora", fora).apply()
        remontar()
        carregarCapas()
    }

    // ---------- Nome e capa trocados à mão ----------

    private fun idApp(app: AppInfo) = "app:${app.pacote}"
    private fun idRom(rom: Rom) = "rom:${rom.uri}"

    /** null = sem troca manual; "" = a pessoa escolheu "Sem capa". */
    private fun capaManual(id: String): String? = personalizado.getString("capa:$id", null)

    /** Temas com blocos quadrados de jogo (PS4, Switch, PS5, Xbox Series); os outros usam banners deitados. */
    private fun temaQuadrado() = layoutInicio() in intArrayOf(1, 3, 5, 6)

    /**
     * Capa para os blocos quadrados: a quadrada escolhida à mão ("Trocar capa" › Quadrada), a em pé à mão, a quadrada
     * automática (SteamGridDB), a em pé automática (cortada no alto) e só então o banner.
     */
    private fun capaQuadrada(id: String, nome: String, banner: String?): String? {
        personalizado.getString("capaQuadrada:$id", null)?.ifEmpty { null }?.let { return it }
        personalizado.getString("capaEmPe:$id", null)?.ifEmpty { null }?.let { return it }
        capas[Capas.chaveQuadrada(nome)]?.let { return it }
        // Sem quadrada: com banner escolhido à mão, ele (a em pé automática poderia ser de outro jogo).
        if (capaManual(id) != null) return banner
        return capas[Capas.chavePoster(nome)] ?: banner
    }

    private fun capaDe(id: String, nome: String): String? {
        val manual = capaManual(id)
        return if (manual != null) manual.ifEmpty { null } else capas[Capas.chave(nome)]
    }

    private fun foiPersonalizado(id: String) =
        personalizado.contains("capa:$id") || personalizado.contains("nome:$id") || personalizado.contains("fundo:$id") ||
            personalizado.contains("capaEmPe:$id") || personalizado.contains("capaQuadrada:$id")

    /** Aplica os nomes trocados sobre os originais (a busca automática de capa usa o nome novo). */
    private fun aplicarPersonalizacao() {
        apps = appsOriginais.map { a ->
            personalizado.getString("nome:${idApp(a)}", null)
                ?.let { AppInfo(a.pacote, a.componente, it, a.categoria, a.cor, a.icone, a.banner) } ?: a
        }
        // ROMs: nome e plataforma trocados à mão; as escondidas saem da lista.
        val escondidas = romsOcultas()
        roms = romsOriginais.filter { it.uri.toString() !in escondidas }.map { r ->
            val nome = personalizado.getString("nome:rom:${r.uri}", null) ?: r.nome
            val sistema = personalizado.getString("sis:rom:${r.uri}", null)
                ?.let { n -> Sistema.entries.firstOrNull { it.name == n } } ?: r.sistema
            if (nome == r.nome && sistema == r.sistema) r else Rom(nome, r.uri, sistema)
        }
    }

    private fun romsOcultas() = personalizado.getStringSet("roms_ocultas", emptySet()).orEmpty()

    private fun depoisDePersonalizar() {
        aplicarPersonalizacao()
        remontar()
        carregarCapas()
    }

    /** [anos] = época do console (ROMs): o seletor oferece o libretro como primeira fonte. */
    /**
     * [secao]: plataforma do jogo na aba Jogos ("rom_PS2", "android"…). Com ela, o seletor deixa escolher o formato
     * (deitada ou em pé); escolher o outro formato passa a plataforma inteira para ele (o formato é por plataforma).
     */
    private fun trocarCapa(
        id: String, nome: String, anos: IntRange? = null, emPe: Boolean = false, arquivo: String = "", secao: String? = null,
    ) {
        val comFormato = secao != null && temFormato(secao)
        val sistema = Sistema.entries.firstOrNull { it.anos == anos }
        // Nos temas de blocos quadrados o seletor já abre em "Quadrada".
        val inicial = when {
            !comFormato -> null
            temaQuadrado() -> SeletorCapa.QUADRADA
            emPe -> SeletorCapa.EM_PE
            else -> SeletorCapa.DEITADA
        }
        SeletorCapa(this, s, nome, aoGaleria = { capaDaGaleria(id, emPe) }, anos = anos,
            titulo = if (emPe && !comFormato) "Trocar capa (em pé)" else "Trocar capa", arquivo = arquivo,
            formato = inicial, proporcao = proporcaoCapa(sistema)) { url, formato ->
            if (comFormato && formato == SeletorCapa.QUADRADA) {
                // A quadrada fica à parte: vale nos temas PS4, PS5, Switch e Xbox Series, sem mexer no formato da aba Jogos.
                if (url.isEmpty()) personalizado.edit().remove("capaQuadrada:$id").apply()
                else personalizado.edit().putString("capaQuadrada:$id", url).apply()
                depoisDePersonalizar()
                Toast.makeText(this, if (url.isEmpty()) "Capa quadrada removida (volta a automática)"
                    else "Capa quadrada trocada (usada nos temas PS4, PS5, Switch e Xbox Series)", Toast.LENGTH_LONG).show()
                return@SeletorCapa
            }
            val emPeEscolhido = if (comFormato) formato == SeletorCapa.EM_PE else emPe
            personalizado.edit().putString(chaveCapa(id, emPeEscolhido), url).apply()
            // Só quando a pessoa trocou o formato na tela (o card do Início é sempre deitado e não mexe na plataforma).
            val mudouFormato = comFormato && emPeEscolhido != emPe && emPeNaSecao(secao!!) != emPeEscolhido
            if (mudouFormato) definirFormatoSecao(secao!!, emPeEscolhido)
            depoisDePersonalizar()
            val plataforma = secoesDeJogos().firstOrNull { it.first == secao }?.second
            Toast.makeText(this, when {
                url.isEmpty() -> "Capa removida"
                mudouFormato -> "Capa trocada. ${plataforma ?: "A plataforma"} agora usa capas " + if (emPeEscolhido) "em pé" else "deitadas"
                else -> "Capa trocada"
            }, Toast.LENGTH_LONG).show()
        }.mostrar()
    }

    /** Arte de fundo do jogo (aba Jogos e tela de informações), separada da capa. "" volta para a automática. */
    private fun trocarFundo(id: String, nome: String, anos: IntRange? = null, arquivo: String = "") {
        SeletorCapa(this, s, nome, aoGaleria = { fundoDaGaleria(id) }, anos = anos, titulo = "Trocar arte de fundo",
            rotuloSem = "Automática", arquivo = arquivo) { url, _ ->
            if (url.isEmpty()) personalizado.edit().remove("fundo:$id").apply() else personalizado.edit().putString("fundo:$id", url).apply()
            depoisDePersonalizar()
            Toast.makeText(this, if (url.isEmpty()) "Arte de fundo automática" else "Arte de fundo trocada", Toast.LENGTH_SHORT).show()
        }.mostrar()
    }

    private fun fundoDaGaleria(id: String) = escolherFoto { url ->
        personalizado.edit().putString("fundo:$id", url).apply()
        depoisDePersonalizar()
        Toast.makeText(this, "Arte de fundo trocada pela foto", Toast.LENGTH_SHORT).show()
    }

    private fun fundoManual(id: String): String? = personalizado.getString("fundo:$id", null)?.ifEmpty { null }

    private fun renomear(id: String, atual: String) {
        val campo = EditText(this).apply {
            setText(atual)
            setSelection(atual.length)
            isSingleLine = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val moldura = FrameLayout(this).apply {
            setPadding(px(50f), px(20f), px(50f), 0)
            addView(campo)
        }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Renomear")
            .setView(moldura)
            .setPositiveButton("Salvar") { _, _ ->
                val novo = campo.text.toString().trim()
                if (novo.isNotEmpty() && novo != atual) {
                    personalizado.edit().putString("nome:$id", novo).apply()
                    depoisDePersonalizar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    /**
     * Campo para a pessoa colar ou digitar a própria chave de [sv]. Testa antes de guardar; "Remover" volta
     * para a chave da versão (ou para só fontes livres). [pronto] roda depois de guardar ou remover.
     */
    private fun pedirChave(sv: Chaves.Servico, pronto: () -> Unit) {
        val campo = EditText(this).apply {
            setText(Chaves.propria(sv))
            hint = "Cole ou digite a chave"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        // RetroAchievements e Steam: o usuário vai junto (o progresso e os jogos são de uma pessoa).
        val conta = sv.usuario != null
        val campoUsuario = if (!conta) null else EditText(this).apply {
            setText(Chaves.usuario(sv))
            hint = sv.usuario
            isSingleLine = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val moldura = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(50f), px(20f), px(50f), 0)
            campoUsuario?.let { addView(it) }
            addView(campo)
        }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle(if (conta) "Conta do ${sv.nome}" else "Chave do ${sv.nome}")
            .setMessage(sv.onde)
            .setView(moldura)
            .setPositiveButton("Testar e salvar") { _, _ ->
                val chave = campo.text.toString().trim()
                val usuario = campoUsuario?.text?.toString()?.trim().orEmpty()
                if (chave.isEmpty() || (conta && usuario.isEmpty())) return@setPositiveButton
                Toast.makeText(this, "Testando…", Toast.LENGTH_SHORT).show()
                trabalho.execute {
                    // O teste devolve o usuário a guardar (na Steam, o número do ID achado pelo link do perfil).
                    val guardado = Chaves.testar(sv, chave, usuario)
                    ui.post {
                        if (guardado == null) {
                            Toast.makeText(this, if (conta) "Conta ou chave do ${sv.nome} não conferem. Confira e tente de novo."
                                else "A chave do ${sv.nome} não funcionou. Confira e tente de novo.", Toast.LENGTH_LONG).show()
                            return@post
                        }
                        if (conta) Chaves.guardarUsuario(sv, guardado)
                        Chaves.guardar(sv, chave)
                        depoisDeTrocarChave(sv)
                        Toast.makeText(this, "Chave do ${sv.nome} salva", Toast.LENGTH_SHORT).show()
                        pronto()
                    }
                }
            }
            .setNeutralButton("Remover") { _, _ ->
                Chaves.guardar(sv, "")
                Chaves.guardarUsuario(sv, "")
                depoisDeTrocarChave(sv)
                pronto()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        campo.requestFocus()
    }

    /**
     * QR code com o endereço de uma página na rede local onde a pessoa cola as chaves pelo celular
     * ([ChavesPeloCelular]). A página só existe enquanto esta janela estiver aberta. [pronto]: depois de salvar.
     */
    private fun chavesPeloCelular(pronto: () -> Unit) {
        var salvou = false
        lateinit var status: TextView
        val servidor = ChavesPeloCelular(this) { resumo ->
            salvou = true
            status.text = "Recebido: $resumo. Pode enviar mais ou fechar."
            Chaves.Servico.entries.forEach { depoisDeTrocarChave(it) }
            Toast.makeText(this, "Chaves pelo celular: $resumo", Toast.LENGTH_LONG).show()
        }
        val endereco = try { servidor.ligar() } catch (_: Exception) { null }
        if (endereco == null) {
            Toast.makeText(this, "Sem rede local (Wi-Fi ou cabo): conecte o aparelho para usar o QR code.", Toast.LENGTH_LONG).show()
            return
        }
        val lado = px(460f)
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(40f), px(20f), px(40f), px(10f))
        }
        caixa.addView(ImageView(this).apply { setImageBitmap(ChavesPeloCelular.qr(endereco, lado)) }, LinearLayout.LayoutParams(lado, lado))
        caixa.addView(texto(24f, Paleta.TEXTO, 500).apply {
            text = endereco
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16f) })
        status = texto(22f, Paleta.TEXTO_SUAVE, 400).apply {
            text = "Escaneie com a câmera do celular, que precisa estar na mesma rede Wi-Fi. Esperando…"
            gravity = Gravity.CENTER
        }
        caixa.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Colocar chaves pelo celular")
            .setView(caixa)
            .setPositiveButton("Fechar", null)
            .setOnDismissListener {
                servidor.desligar()
                if (salvou) pronto()
            }
            .show()
    }

    /** Chave nova (ou removida): refaz o que depende dela, sem esperar os 7 dias do cache. */
    private fun depoisDeTrocarChave(sv: Chaves.Servico) {
        when (sv) {
            Chaves.Servico.STEAMGRIDDB -> {
                trabalho.execute { Capas.esquecerFaltantes(this) }
                carregarCapas()
            }
            Chaves.Servico.TMDB, Chaves.Servico.YOUTUBE -> {
                trabalho.execute { TrailersYoutube.esquecer(this) }
                carregarCatalogo()
            }
            Chaves.Servico.RETROACHIEVEMENTS -> Unit // o progresso é buscado ao abrir as informações de cada jogo
            // Seus jogos no GeForce NOW e sua conta Xbox: busca de novo e refaz a aba Jogos.
            Chaves.Servico.STEAM, Chaves.Servico.XBOX -> {
                ContaSteam.esquecer(this)
                ContaXbox.esquecer(this)
                carregarNuvem()
            }
        }
    }

    private fun restaurar(id: String) {
        personalizado.edit().remove("capa:$id").remove("nome:$id").remove("fundo:$id").remove("capaEmPe:$id").apply()
        depoisDePersonalizar()
        Toast.makeText(this, "Nome, capa e arte de fundo originais de volta", Toast.LENGTH_SHORT).show()
    }

    // ---------- Atalhos fixados (GameHub e outros) ----------

    private fun idAtalho(a: Atalho) = "atalho:${a.pacote}/${a.id}"
    private fun nomeAtalho(a: Atalho) = personalizado.getString("nome:${idAtalho(a)}", null) ?: a.nome

    private fun cardAtalho(a: Atalho, nomeApp: String, cor: Int, comFundo: Boolean = true, emPeSecao: Boolean = false): View {
        val id = idAtalho(a)
        val nome = nomeAtalho(a)
        val capa = capaDe(id, nome)
        val emPe = if (emPeSecao) capaEmPe(id, nome) else null
        return cardGrande(nome, "Abre no $nomeApp", a.icone, cor, emPe?.first ?: capa, { abrirAtalho(a) }, { v -> opcoesAtalho(v, a) }, id,
            if (comFundo) ({ selecionarJogo(capa, nome, null, id) }) else null,
            proporcao = emPe?.let { proporcaoCapa(null) }, imagemInteira = emPe?.second == false)
            .also {
                infoJogo[it] = { abrirInfoJogo(nome, null, capa, listOf("Abre no" to nomeApp) + infoTempo(id), acoesAtalho(it, a), fundoManual(id)) { abrirAtalho(a) } }
                trailerDe[it] = nome to null
            }
    }

    /** Texto "PS2 · abre no NetherSX2" e ícone do emulador escolhido para o sistema. */
    private fun infoEmulador(sistema: Sistema): Pair<String, Bitmap?> {
        val pacote = Roms.emulador(this, sistema)
        val app = apps.firstOrNull { it.pacote == pacote }
        val detalhe = if (app != null) "${sistema.nome} · abre no ${app.nome}" else "${sistema.nome} · nenhum emulador compatível instalado"
        return detalhe to app?.icone
    }

    private fun abrirAtalho(a: Atalho) = abrirJogo(a.pacote, idAtalho(a) to nomeAtalho(a)) {
        if (!Atalhos.abrir(this, a)) Toast.makeText(this, "Não foi possível abrir \"${nomeAtalho(a)}\"", Toast.LENGTH_SHORT).show()
    }

    /**
     * Abre um jogo do app [pacote]: vibra o controle (se ligado) e, com o modo desempenho dos recursos root,
     * libera memória e ajusta o sistema antes de abrir (desfeito quando o launcher volta à frente).
     */
    /** Um jogo foi aberto: a aba Jogos refaz "Continuar jogando" na volta. */
    private var historicoMudou = false

    private fun abrirJogo(pacote: String?, jogo: Pair<String, String>? = null, abrirAgora: () -> Unit) {
        // Perfil infantil com limite diário: acabou o tempo de hoje, só com o PIN.
        val resta = Perfis.restanteMin(this)
        if (resta != null && resta <= 0) {
            android.app.AlertDialog.Builder(this, temaDialogo())
                .setTitle("O tempo de jogo de hoje acabou")
                .setMessage("O limite deste perfil é ${Perfis.rotuloLimite(Perfis.atual(this).limiteMin)}. Amanhã tem mais!")
                .setPositiveButton("OK", null)
                .setNegativeButton("Liberar com o PIN") { _, _ ->
                    pedirPin("Liberar o resto do dia") { Perfis.liberarHoje(this); abrirJogo(pacote, jogo, abrirAgora) }
                }
                .show()
            return
        }
        if (resta != null && resta <= 15) Toast.makeText(this, "Restam $resta min de jogo hoje", Toast.LENGTH_LONG).show()
        Sons.tocar(Sons.Evento.ABRIR)
        Controle.aoAbrirJogo(this)
        // Continuar jogando e tempo de jogo: (id do card, nome).
        jogo?.let { (id, nome) -> Historico.abriu(this, id, nome); historicoMudou = true }
        // O PS no launcher volta para este jogo (recursos root).
        if (pacote != null) prefs.edit().putString("ultimo_jogo", pacote).apply()
        if (pacote == null || !RecursosRoot.desempenhoLigado(this)) return abrirAgora()
        Thread {
            RecursosRoot.inicioDoJogo(this, pacote)
            ui.post { abrirAgora() }
        }.start()
    }

    /** Relê os atalhos fixados (um app pode ter acabado de criar um); remonta Jogos só se mudou. */
    private fun recarregarAtalhos() {
        trabalho.execute {
            val novos = Atalhos.listar(this)
            ui.post {
                if (isDestroyed) return@post
                if (novos.map { it.pacote + "/" + it.id } == atalhos.map { it.pacote + "/" + it.id }) return@post
                atalhos = novos
                // Os atalhos aparecem na aba Jogos e na fileira Jogos do Início.
                descartar(Tela.JOGOS)
                descartar(Tela.INICIO)
                mostrar(telaAtual)
                carregarCapas()
            }
        }
    }

    /** Uma opção de jogo/app: aparece no menu do card e, se [naInfo], no "Editar jogo" da tela de informações. */
    private class Acao(val rotulo: String, val naInfo: Boolean = true, val fazer: () -> Unit)

    private fun mostrarMenu(v: View, acoes: List<Acao>) {
        if (layoutInicio() != 0) return menuOpcoesPs4(acoes)
        PopupMenu(this, v).apply {
            acoes.forEachIndexed { i, a -> menu.add(0, i, i, a.rotulo) }
            setOnMenuItemClickListener { acoes[it.itemId].fazer(); true }
            show()
        }
    }

    /** Menu de opções no estilo PS4: painel azul-escuro que entra pela direita, com a lista e a seleção brilhante. */
    private fun menuOpcoesPs4(acoes: List<Acao>) {
        val vis = Visuais.de(this) ?: return
        val xbox = layoutInicio() == 2
        val w = resources.displayMetrics.widthPixels
        val u = w / 1280f
        val d = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val raiz = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setOnClickListener { d.dismiss() }
        }
        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((40 * u).toInt(), (60 * u).toInt(), (40 * u).toInt(), (40 * u).toInt())
        }
        lista.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * u)
            setTextColor(vis.textoPainel)
            typeface = Ps4Desenho.leve
            text = if (xbox) "opções" else "Opções"
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (20 * u).toInt() })
        var primeiro: View? = null
        for (a in acoes) {
            val l = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 20 * u)
                setTextColor(vis.textoPainel)
                typeface = Ps4Desenho.normal
                text = a.rotulo
                isFocusable = true
                isClickable = true
                defaultFocusHighlightEnabled = false
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (56 * u).toInt()
                setPadding((18 * u).toInt(), (10 * u).toInt(), (18 * u).toInt(), (10 * u).toInt())
                background = vis.selecaoPainel(u)
                setOnFocusChangeListener { v, f ->
                    if (f) Sons.tocar(Sons.Evento.MOVER)
                    vis.textoPainelFocado?.let { foco -> (v as TextView).setTextColor(if (f) foco else vis.textoPainel) }
                }
                setOnClickListener {
                    d.dismiss()
                    a.fazer()
                }
            }
            lista.addView(l, LinearLayout.LayoutParams(-1, -2))
            if (primeiro == null) primeiro = l
        }
        val painel = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, vis.painel)
            addView(lista)
        }
        val largura = (w * 0.34f).toInt()
        raiz.addView(painel, FrameLayout.LayoutParams(largura, -1, Gravity.END))
        d.setContentView(raiz)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(-1, -1)
            setDecorFitsSystemWindows(false)
            insetsController?.hide(WindowInsets.Type.systemBars())
        }
        d.setOnKeyListener { _, codigo, e ->
            if (codigo == KeyEvent.KEYCODE_BUTTON_B || codigo == KeyEvent.KEYCODE_BUTTON_Y || codigo == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) d.dismiss()
                true
            } else false
        }
        d.show()
        Sons.tocar(Sons.Evento.ABRIR)
        painel.translationX = largura.toFloat()
        painel.animate().translationX(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        primeiro?.let { p -> p.post { p.requestFocus() } }
    }

    /** "Informações do jogo" e "Mover" só fazem sentido no card (não dentro da própria tela de informações). */
    private fun acoesDoCard(v: View) = listOfNotNull(
        infoJogo[v]?.let { info -> Acao("Informações do jogo", naInfo = false) { info() } },
        itemNaFileira(v)?.let { Acao("Mover", naInfo = false) { opcaoMover(v) } },
        // Só cards de jogo (o id deles começa com rom:, app:, atalho: ou nuvem:).
        (v.tag as? String)?.takeIf { infoJogo[v] != null }?.let { Acao("Coleções…") { escolherColecoes(v) } },
        (v.tag as? String)?.takeIf { Historico.registro(this, it) != null }?.let { id ->
            Acao("Remover de Continuar jogando") { Historico.esquecer(this, id); remontarJogos() }
        },
        // Controle parental: só com PIN criado, e fora dos perfis infantis.
        (v.tag as? String)?.takeIf { infoJogo[v] != null && Perfis.pin(this) != null && !Perfis.atual(this).infantil }?.let { id ->
            val adulto = id in Perfis.adultos(this)
            Acao(if (adulto) "Liberar para os perfis infantis" else "Só para adultos (esconder dos perfis infantis)") {
                Perfis.marcarAdulto(this, id, !adulto)
                Toast.makeText(this, if (adulto) "Liberado para os perfis infantis" else "Escondido dos perfis infantis", Toast.LENGTH_SHORT).show()
            }
        },
    )

    private fun opcoesAtalho(v: View, a: Atalho) = mostrarMenu(v, acoesAtalho(v, a))

    private fun acoesAtalho(v: View, a: Atalho): List<Acao> {
        val id = idAtalho(a)
        val nome = nomeAtalho(a)
        return acoesDoCard(v) + listOfNotNull(
            Acao("Trocar capa") { trocarCapa(id, nome, emPe = cardEmPe(v), secao = "atalhos_${a.pacote}") },
            Acao("Trocar arte de fundo") { trocarFundo(id, nome) },
            Acao("Usar foto da galeria") { capaDaGaleria(id, cardEmPe(v)) },
            Acao("Renomear") { renomear(id, nome) },
            if (foiPersonalizado(id)) Acao("Restaurar nome, capa e fundo originais") { restaurar(id) } else null,
            Acao("Remover atalho") {
                try {
                    Atalhos.remover(this@MainActivity, a)
                } catch (_: Exception) {
                }
                recarregarAtalhos()
                Toast.makeText(this@MainActivity, "\"$nome\" removido", Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun opcoesRom(v: View, rom: Rom) = mostrarMenu(v, acoesRom(v, rom))

    private fun acoesRom(v: View, rom: Rom): List<Acao> {
        val id = idRom(rom)
        val anos = rom.sistema.anos
        val emuJogo = Roms.emuladorDoJogo(this, rom)
        val emuAtual = emuJogo ?: Roms.emulador(this, rom.sistema)
        return acoesDoCard(v) + listOfNotNull(
            Acao("Trocar capa") { trocarCapa(id, rom.nome, anos, cardEmPe(v), rom.arquivo, "rom_${rom.sistema.name}") },
            Acao("Trocar arte de fundo") { trocarFundo(id, rom.nome, anos, rom.arquivo) },
            Acao("Usar foto da galeria") { capaDaGaleria(id, cardEmPe(v)) },
            Acao("Renomear") { renomear(id, rom.nome) },
            Acao("Emulador deste jogo" + (emuAtual?.let { ": ${nomeDoPacote(it)}" } ?: "") + "…") { escolherEmuladorDoJogo(rom) },
            Acao("Plataforma: ${rom.sistema.nome}…") { escolherPlataformaDoJogo(rom) },
            Acao("Abrir com outro app…") { abrirRomComOutroApp(rom) },
            Acao("Detalhes do arquivo") { detalhesDaRom(rom) },
            Acao("Esconder este jogo") { esconderRom(rom) },
            if (foiPersonalizado(id) || emuJogo != null || personalizado.contains("sis:$id")) {
                Acao("Restaurar tudo do jogo") {
                    Roms.definirEmuladorDoJogo(this@MainActivity, rom, null)
                    personalizado.edit().remove("sis:$id").apply()
                    restaurar(id)
                }
            } else null,
        )
    }

    // ---------- Opções de uma ROM ----------

    private fun nomeDoPacote(p: String) = apps.firstOrNull { it.pacote == p }?.nome ?: p

    /** "Outro app instalado…": qualquer app vira o emulador da plataforma (os que parecem emuladores vêm primeiro). */
    private fun escolherOutroEmulador(sistema: Sistema) {
        val pacotes = Roms.todosOsApps(this)
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Emulador para ${sistema.nome}")
            .setItems(pacotes.map { "${nomeDoPacote(it)}  ·  $it" }.toTypedArray()) { _, i ->
                Roms.escolher(this, sistema, pacotes[i])
                descartar(Tela.JOGOS)
                if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
                Toast.makeText(this, "${nomeDoPacote(pacotes[i])} abre os jogos de ${sistema.nome}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Emulador só para este jogo (vale mais que o escolhido para a plataforma). */
    private fun escolherEmuladorDoJogo(rom: Rom) {
        val daPlataforma = Roms.candidatos(this, rom.sistema)
        val outros = Roms.todosInstalados(this).filter { it !in daPlataforma }
        val pacotes = daPlataforma + outros
        val padrao = Roms.emulador(this, rom.sistema)
        val rotulos = listOf("Padrão de ${rom.sistema.nome}" + (padrao?.let { " (${nomeDoPacote(it)})" } ?: "")) +
            daPlataforma.map { nomeDoPacote(it) } + outros.map { "${nomeDoPacote(it)}  ·  outra plataforma" }
        val atual = Roms.emuladorDoJogo(this, rom)?.let { pacotes.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Emulador para \"${rom.nome}\"")
            .setSingleChoiceItems(rotulos.toTypedArray(), atual) { d, i ->
                Roms.definirEmuladorDoJogo(this, rom, if (i == 0) null else pacotes[i - 1])
                d.dismiss()
                remontar()
                val agora = Roms.emuladorEfetivo(this, rom)?.let { nomeDoPacote(it) } ?: "nenhum"
                Toast.makeText(this, "\"${rom.nome}\" vai abrir no $agora", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Plataforma deste jogo, se foi detectada errada (ex.: .iso de PSP lido como PS2). */
    private fun escolherPlataformaDoJogo(rom: Rom) {
        val id = idRom(rom)
        val sistemas = Sistema.entries
        val original = romsOriginais.firstOrNull { it.uri == rom.uri }?.sistema
        val rotulos = sistemas.map { s -> s.nome + if (s == original) "  (detectada)" else "" }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Plataforma de \"${rom.nome}\"")
            .setSingleChoiceItems(rotulos.toTypedArray(), sistemas.indexOf(rom.sistema)) { d, i ->
                val s = sistemas[i]
                personalizado.edit().apply { if (s == original) remove("sis:$id") else putString("sis:$id", s.name) }.apply()
                d.dismiss()
                depoisDePersonalizar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** O Android pergunta com qual app abrir o arquivo (qualquer app que aceite o tipo). */
    private fun abrirRomComOutroApp(rom: Rom) {
        val i = Intent(Intent.ACTION_VIEW).setData(rom.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        i.clipData = android.content.ClipData.newRawUri(rom.nome, rom.uri)
        try {
            startActivity(Intent.createChooser(i, "Abrir \"${rom.nome}\" com").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Nenhum app aceita esse arquivo", Toast.LENGTH_SHORT).show()
        }
    }

    /** Nome do arquivo, tamanho e pasta, lidos pelo próprio Android (sem permissão extra). */
    private fun infoArquivo(rom: Rom): List<Pair<String, String>> {
        var arquivo = "?"
        var tamanho = "?"
        try {
            contentResolver.query(rom.uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    arquivo = c.getString(0) ?: "?"
                    val b = c.getLong(1)
                    tamanho = if (b >= 1L shl 30) String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0)
                    else String.format(Locale.ROOT, "%.0f MB", b / 1048576.0)
                }
            }
        } catch (_: Exception) {
        }
        val pasta = try { DocumentsContract.getDocumentId(rom.uri).substringAfter(':').substringBeforeLast('/') } catch (_: Exception) { "?" }
        val emu = Roms.emuladorEfetivo(this, rom)?.let { nomeDoPacote(it) } ?: "nenhum instalado"
        return listOf("Arquivo" to arquivo, "Tamanho" to tamanho, "Pasta" to pasta, "Emulador" to emu)
    }

    private fun detalhesDaRom(rom: Rom) {
        val texto = (listOf("Plataforma" to rom.sistema.nome) + infoArquivo(rom)).joinToString("\n\n") { "${it.first}\n${it.second}" }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle(rom.nome)
            .setMessage(texto)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun esconderRom(rom: Rom) {
        personalizado.edit().putStringSet("roms_ocultas", romsOcultas() + rom.uri.toString()).apply()
        depoisDePersonalizar()
        Toast.makeText(this, "\"${rom.nome}\" escondido. Para voltar: Configurações → Jogos escondidos", Toast.LENGTH_LONG).show()
    }

    /** Lista com caixinhas: desmarcado = ROM escondida. */
    private fun escolherRomsEscondidas() {
        val todas = romsOriginais.sortedBy { it.nome.lowercase() }
        if (todas.isEmpty()) {
            Toast.makeText(this, "Nenhuma ROM encontrada", Toast.LENGTH_SHORT).show()
            return
        }
        val ocultas = romsOcultas()
        val marcados = BooleanArray(todas.size) { todas[it].uri.toString() !in ocultas }
        val nomes = todas.map { r -> (personalizado.getString("nome:rom:${r.uri}", null) ?: r.nome) + "  ·  ${r.sistema.nome}" }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Jogos de emulador mostrados")
            .setMultiChoiceItems(nomes.toTypedArray(), marcados) { _, i, sim -> marcados[i] = sim }
            .setPositiveButton("Salvar") { _, _ ->
                personalizado.edit().putStringSet("roms_ocultas",
                    todas.filterIndexed { i, _ -> !marcados[i] }.map { it.uri.toString() }.toSet()).apply()
                depoisDePersonalizar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cardTitulo(t: Titulo, app: AppInfo) = cardGrande(t.nome, t.sinopse, app.icone, app.cor,
        Tmdb.urlImagem(t.fundo, "w780"), { abrirApp(app) }).also {
        if (t.id > 0) filmeDe[it] = FonteFilme(t.nome, t.sinopse, "${app.nome} · ${if (t.tipo == "tv") "Série" else "Filme"}", null, "${t.tipo}:${t.id}")
    }

    // ---------- Quais apps são "jogos Android" (a pessoa escolhe) ----------

    private fun jogosAdicionados() = prefs.getStringSet("jogos_add", emptySet()).orEmpty()
    private fun jogosTirados() = prefs.getStringSet("jogos_del", emptySet()).orEmpty()

    /**
     * Jogo pela classificação do próprio app, mais os que a pessoa marcou, menos os que ela tirou.
     * Emuladores ficam de fora por padrão, mas entram se a pessoa marcar.
     */
    private fun ehJogoAndroid(app: AppInfo) = when (app.pacote) {
        in jogosTirados() -> false
        in jogosAdicionados() -> true
        else -> app.categoria == Categoria.JOGO && !Catalogo.ehEmulador(app.pacote)
    }

    private fun jogosAndroid(lista: List<AppInfo>) =
        ordenar(ORDEM_JOGOS, lista.filter { ehJogoAndroid(it) }.sortedBy { it.nome.lowercase() }, ::idApp)

    private fun definirJogo(app: AppInfo, jogo: Boolean) {
        val padrao = app.categoria == Categoria.JOGO && !Catalogo.ehEmulador(app.pacote)
        val add = jogosAdicionados().toMutableSet()
        val del = jogosTirados().toMutableSet()
        add.remove(app.pacote)
        del.remove(app.pacote)
        if (jogo && !padrao) add += app.pacote
        if (!jogo && padrao) del += app.pacote
        prefs.edit().putStringSet("jogos_add", add).putStringSet("jogos_del", del).apply()
    }

    /** Lista com caixinhas: marca o que aparece em "Jogos Android". */
    private fun escolherJogos() {
        // Todos os apps, inclusive emuladores; os já marcados vêm primeiro.
        val candidatos = apps.sortedWith(compareBy({ !ehJogoAndroid(it) }, { it.nome.lowercase() }))
        val marcados = BooleanArray(candidatos.size) { ehJogoAndroid(candidatos[it]) }
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Quais apps aparecem em Jogos?")
            .setMultiChoiceItems(candidatos.map { it.nome }.toTypedArray(), marcados) { _, i, sim -> marcados[i] = sim }
            .setPositiveButton("Salvar") { _, _ ->
                candidatos.forEachIndexed { i, app -> definirJogo(app, marcados[i]) }
                remontar()
                carregarCapas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Vizinhos se afastam para o card em foco crescer sem cobrir ninguém. */
    private fun afastarVizinhos(card: View, foco: Boolean, extra: Float) {
        val linha = card.parent as? ViewGroup ?: return
        if (linha.tag == "console") return
        val i = linha.indexOfChild(card)
        for (j in 0 until linha.childCount) {
            val v = linha.getChildAt(j)
            val alvo = when {
                !foco || j == i -> 0f
                j < i -> -extra / 2f
                else -> extra / 2f
            }
            v.animate().translationX(alvo).setDuration(200).start()
        }
    }

    // ---------- Jogos ----------

    private fun telaJogos(): View {
        val (sv, col) = pagina()
        col.setPadding(0, 0, 0, px(60f))
        col.addView(barraTopo(Tela.JOGOS), LinearLayout.LayoutParams(-1, px(140f)))
        val visiveis = apps.filter { it.pacote !in ocultos() }
        val android = jogosAndroid(visiveis)
        val emuladores = visiveis.filter { Catalogo.ehEmulador(it.pacote) }
        val secoes = ArrayList<Secao>()

        // Continuar jogando: os últimos jogos abertos (de qualquer plataforma), com quando e quanto tempo.
        val recentes = Historico.recentes(this, 15)
        if (recentes.isNotEmpty()) secoes += Secao(SECAO_RECENTES, "Continuar jogando") { b, m ->
            val cards = recentes.mapNotNull { r ->
                cardPorId(r.id)?.also { c ->
                    (c as? CardTV)?.definirDetalhe("Jogado ${Historico.quandoLegivel(r.ultimaVez)}" +
                        if (r.totalMs >= 60_000) " · ${Historico.tempoLegivel(r.totalMs)}" else "")
                }
            }
            adicionarFileira(b, "Continuar jogando", cards + bannerAcao("🎲", "Jogo aleatório", 0xFFE8710A.toInt()) { jogoAleatorio() },
                conteudo = true, margemTitulo = m)
        }

        // Coleções da pessoa (de qualquer plataforma), na ordem em que foram adicionados os jogos.
        for ((nome, ids) in Colecoes.todas(this)) {
            val chave = Colecoes.secao(nome)
            secoes += Secao(chave, nome) { b, m ->
                adicionarFileira(b, nome, ids.mapNotNull { cardPorId(it) }, conteudo = true, margemTitulo = m, ordem = chave)
            }
        }

        // Conquistas do RetroAchievements: os jogos jogados há pouco que estão nas suas pastas de ROMs.
        if (conquistasRecentes.isNotEmpty()) secoes += Secao(SECAO_CONQUISTAS, "Conquistas (RetroAchievements)") { b, m ->
            val cards = conquistasRecentes.mapNotNull { r ->
                val sistema = RetroConquistas.sistemaDo(r.console) ?: return@mapNotNull null
                val rom = roms.firstOrNull { it.sistema == sistema && RetroConquistas.mesmoJogo(r.titulo, it.nome) } ?: return@mapNotNull null
                val (detalhe, iconeEmu) = infoEmulador(sistema)
                cardRom(rom, detalhe, iconeEmu, comFundo = true).also { c ->
                    (c as? CardTV)?.definirDetalhe("🏆 ${r.ganhas} de ${r.total} conquistas" + if (r.ganhas == r.total && r.total > 0) " · completo" else "")
                }
            }
            adicionarFileira(b, "Conquistas (RetroAchievements)", cards, conteudo = true, margemTitulo = m)
        }

        // Sua conta Xbox (OpenXBL): os jogos jogados por último, com as conquistas.
        val perfil = xboxPerfil
        if (perfil != null && xboxJogos.isNotEmpty()) secoes += Secao(SECAO_XBOX_CONTA, "Sua conta Xbox") { b, m ->
            adicionarFileira(b, "Xbox · ${perfil.gamertag} · ${perfil.gamerscore} G", xboxJogos.take(30).map { cardXbox(it) },
                conteudo = true, margemTitulo = m, ordem = SECAO_XBOX_CONTA)
        }

        // Seus jogos no GeForce NOW: os do catálogo que você tem na Steam (o GeForce NOW roda o que você já comprou).
        val meusGfn = meusNoGfn()
        if (meusGfn.isNotEmpty()) secoes += Secao(SECAO_GFN_MEUS, "Seus jogos no GeForce NOW") { b, m ->
            val cards = meusGfn.map { j ->
                cardNuvem(j).also { c ->
                    val horas = meusSteam[j.steamId]?.minutos?.takeIf { it >= 60 }?.let { " · ${it / 60} h na Steam" }.orEmpty()
                    (c as? CardTV)?.definirDetalhe("☁ GeForce NOW · você tem na Steam$horas")
                }
            }
            adicionarFileira(b, "Seus jogos no GeForce NOW", cards, conteudo = true, margemTitulo = m, ordem = SECAO_GFN_MEUS)
        }

        // Jogos na nuvem: GeForce NOW (filtrável por gênero) e Xbox Cloud Gaming, e os apps de streaming instalados.
        val gfn = gfnNaFileira()
        if (gfn.isNotEmpty()) secoes += Secao(SECAO_GFN, "GeForce NOW") { b, m ->
            val filtro = prefs.getString("filtro:$SECAO_GFN", null)
            val titulo = "GeForce NOW" + when (filtro) { null -> " · otimizados"; "*" -> ""; else -> " · $filtro" }
            adicionarFileira(b, titulo, gfn.map { cardNuvem(it) }, conteudo = true, margemTitulo = m, ordem = SECAO_GFN)
        }
        val xbox = jogosNuvem[Nuvem.Servico.XBOX].orEmpty()
        if (xbox.isNotEmpty()) secoes += Secao(SECAO_XBOX, "Xbox Cloud Gaming") { b, m ->
            adicionarFileira(b, "Xbox Cloud Gaming · mais jogados", xbox.map { cardNuvem(it) }, conteudo = true, margemTitulo = m, ordem = SECAO_XBOX)
        }
        if (xboxGratis.isNotEmpty()) secoes += Secao(SECAO_XBOX_GRATIS, "Grátis no Xbox Cloud") { b, m ->
            val cards = xboxGratis.map { j -> cardNuvem(j).also { (it as? CardTV)?.definirDetalhe("☁ Xbox Cloud · grátis, sem Game Pass") } }
            adicionarFileira(b, "Grátis no Xbox Cloud · sem Game Pass", cards, conteudo = true, margemTitulo = m, ordem = SECAO_XBOX_GRATIS)
        }
        val streaming = visiveis.filter { Nuvem.ehStreaming(it.pacote) }
        if (streaming.isNotEmpty()) secoes += Secao(SECAO_STREAMING, "Streaming e nuvem") { b, m ->
            adicionarFileira(b, "Streaming e nuvem", streaming.map { bannerApp(it) }, conteudo = false, margemTitulo = m)
        }

        if (android.isNotEmpty()) secoes += Secao("android", "Jogos Android") { b, m ->
            adicionarFileira(b, "Jogos Android", android.map { cardJogo(it, comFundo = true, emPeSecao = emPeNaSecao("android")) }, conteudo = true, margemTitulo = m,
                padrao = true, ordem = ORDEM_JOGOS)
        }

        // ROMs agrupadas por sistema, cada uma abre direto no emulador.
        for ((sistema, lista) in roms.groupBy { it.sistema }) {
            val pacote = Roms.emulador(this, sistema)
            val iconeEmu = apps.firstOrNull { it.pacote == pacote }?.icone
            val nomeEmu = apps.firstOrNull { it.pacote == pacote }?.nome
            val detalhe = if (nomeEmu != null) "${sistema.nome} · abre no $nomeEmu" else "${sistema.nome} · nenhum emulador compatível instalado"
            val chaveOrdem = "rom_${sistema.name}"
            secoes += Secao(chaveOrdem, sistema.nome) { b, m ->
                val cards = ordenar(chaveOrdem, lista, ::idRom).map { rom -> cardRom(rom, detalhe, iconeEmu, comFundo = true, emPeSecao = emPeNaSecao(chaveOrdem)) }
                adicionarFileira(b, sistema.nome, cards, conteudo = true, margemTitulo = m, ordem = chaveOrdem)
            }
        }

        // Atalhos fixados por outros apps (ex.: jogos do GameHub), uma seção por app.
        for ((pacote, lista) in atalhos.groupBy { it.pacote }) {
            val app = apps.firstOrNull { it.pacote == pacote }
            val nomeApp = app?.nome ?: pacote
            val chave = "atalhos_$pacote"
            secoes += Secao(chave, "Jogos do $nomeApp") { b, m ->
                val cards = ordenar(chave, lista, ::idAtalho).map { cardAtalho(it, nomeApp, app?.cor ?: Paleta.AZUL, emPeSecao = emPeNaSecao(chave)) }
                adicionarFileira(b, "Jogos do $nomeApp", cards, conteudo = true, margemTitulo = m, ordem = chave)
            }
        }

        // Emuladores e as pastas de ROMs.
        val acoes = ArrayList<View>()
        acoes += bannerAcao("🎲", "Jogo aleatório", 0xFFE8710A.toInt()) { jogoAleatorio() }
        acoes += bannerAcao("✓", "Escolher jogos", 0xFF8E24AA.toInt()) { escolherJogos() }
        acoes += bannerAcao("+", "Adicionar pasta", 0xFF1A73E8.toInt()) { escolherPasta() }
        if (pastasRoms().isNotEmpty()) {
            acoes += bannerAcao("↻", "Procurar de novo", 0xFF1E8E3E.toInt()) { varrerRoms() }
            pastasRoms().forEach { uri ->
                acoes += bannerAcao("📁", rotuloPasta(uri), 0xFF5F6368.toInt(), aoSegurar = { v -> opcoesPasta(v, uri) }) {
                    escolherPlataformaDaPasta(uri)
                }
            }
        }
        secoes += Secao("emuladores", "Emuladores e pastas") { b, m ->
            adicionarFileira(b, "Emuladores e pastas", emuladores.map { bannerApp(it) } + acoes, conteudo = false, margemTitulo = m)
        }
        // Seções novas ainda fora da ordem salva: "Continuar jogando" entra no topo; as outras no fim, como sempre.
        prefs.getString("ordem_secoes_$ABA_JOGOS", null)?.let { salva ->
            if (SECAO_RECENTES !in salva.split("\n")) prefs.edit().putString("ordem_secoes_$ABA_JOGOS", "$SECAO_RECENTES\n$salva").apply()
        }
        montarSecoes(col, ABA_JOGOS, secoes)
        if (roms.isEmpty()) {
            col.addView(texto(24f, Paleta.TEXTO_SUAVE, 400).apply {
                text = if (varrendoRoms) "Procurando jogos nas pastas…"
                else "Nenhuma ROM encontrada. Toque em \"Adicionar pasta\" (ou no cartão SD, se as pastas estiverem nele)."
            }, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = px(107f)
                rightMargin = px(107f)
                topMargin = px(30f)
            })
        }
        sv.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // O fundo fica parado atrás da página (não rola junto), como nos consoles.
        val tela = FrameLayout(this).apply { clipChildren = false }
        val fundo = FundoImagem(this, FundoImagem.Veu.JOGO)
        fundoJogos = fundo
        tela.addView(fundo, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        tela.addView(sv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return tela
    }

    private fun bannerAcao(simbolo: String, nome: String, cor: Int, aoSegurar: ((View) -> Unit)? = null, aoTocar: () -> Unit): View {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        val card = CardTV(this, false, raioCards()).apply {
            definir(nome, iconeSimbolo(simbolo), cor)
            setOnClickListener { aoTocar() }
            if (aoSegurar != null) setOnLongClickListener { aoSegurar(it); true }
        }
        caixa.addView(card, LinearLayout.LayoutParams(px(200f), px(112f)))
        caixa.addView(Space(this), LinearLayout.LayoutParams(1, px(34f)))
        return caixa
    }

    private fun iconeSimbolo(simbolo: String): Bitmap {
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
        c.drawCircle(60f, 60f, 58f, p)
        p.color = Color.WHITE
        p.textSize = 76f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(simbolo, 60f, 60f - (p.ascent() + p.descent()) / 2f, p)
        return b
    }

    private fun pastasRoms(): List<Uri> = prefs.getStringSet("pastas_roms", emptySet()).orEmpty().map { Uri.parse(it) }

    private fun nomeDaPasta(uri: Uri) = try {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Pasta" }
    } catch (_: Exception) {
        "Pasta"
    }

    @Suppress("DEPRECATION")
    private fun escolherPasta() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PEDIDO_PASTA)
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir o seletor de pastas", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PEDIDO_FOTO) {
            val foto = data?.data
            val aoEscolher = aoEscolherFoto
            aoEscolherFoto = null
            if (resultCode != RESULT_OK || foto == null || aoEscolher == null) return
            trabalho.execute {
                val url = Imagens.guardarFoto(this, foto)
                ui.post {
                    if (url == null) Toast.makeText(this, "Não foi possível usar essa foto", Toast.LENGTH_SHORT).show()
                    else aoEscolher(url)
                }
            }
            return
        }
        if (requestCode == PEDIDO_VOZ) {
            val falado = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (resultCode == RESULT_OK && !falado.isNullOrBlank()) {
                if (layoutInicio() != 0) {
                    buscarNoConsole(falado)
                    return
                }
                if (telaAtual != Tela.BUSCA) mostrar(Tela.BUSCA)
                campoBusca?.let { it.setText(falado); it.setSelection(falado.length) }
            }
            return
        }
        if (requestCode == PEDIDO_EXPORTAR || requestCode == PEDIDO_IMPORTAR) {
            val arq = data?.data
            if (resultCode != RESULT_OK || arq == null) return
            trabalho.execute {
                val ok = try {
                    if (requestCode == PEDIDO_EXPORTAR) contentResolver.openOutputStream(arq, "wt")?.use { Backup.exportar(this, it); true } ?: false
                    else contentResolver.openInputStream(arq)?.use { Backup.importar(this, it) } ?: false
                } catch (_: Exception) {
                    false
                }
                ui.post {
                    when {
                        requestCode == PEDIDO_EXPORTAR -> Toast.makeText(this, if (ok) "Configurações exportadas" else "Não foi possível salvar o arquivo",
                            Toast.LENGTH_SHORT).show()
                        ok -> reiniciarLauncher("Configurações importadas")
                        else -> Toast.makeText(this, "Esse arquivo não é um backup do GameLauncherTV", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        val uri = data?.data
        if (requestCode != PEDIDO_PASTA || resultCode != RESULT_OK || uri == null) return
        // Guarda a permissão de leitura só desta pasta, para continuar valendo depois de reiniciar.
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putStringSet("pastas_roms", pastasRoms().map { it.toString() }.toSet() + uri.toString()).apply()
        // Pergunta para qual plataforma é esta pasta; a leitura acontece depois da resposta.
        escolherPlataformaDaPasta(uri)
    }

    /** "PS2 · PlayStation 2" ou "Switch · detectar sozinho". */
    private fun rotuloPasta(uri: Uri) = nomeDaPasta(uri) + " · " + (Roms.sistemaDaPasta(this, uri)?.nome ?: "automático")

    /** Diálogo "Qual plataforma tem nesta pasta?": define e lê a pasta de novo. */
    private fun escolherPlataformaDaPasta(uri: Uri) {
        val sistemas = Sistema.entries
        val rotulos = arrayOf("Detectar sozinho (pelo nome das pastas)") + sistemas.map { s ->
            val emu = Roms.emulador(this, s)?.let { p -> apps.firstOrNull { it.pacote == p }?.nome ?: p }
            if (emu != null) "${s.nome}  ·  $emu" else "${s.nome}  ·  sem emulador"
        }
        val atual = Roms.sistemaDaPasta(this, uri)?.let { sistemas.indexOf(it) + 1 } ?: 0
        android.app.AlertDialog.Builder(this, temaDialogo())
            .setTitle("Qual plataforma tem em \"${nomeDaPasta(uri)}\"?")
            .setSingleChoiceItems(rotulos, atual) { d, i ->
                Roms.definirSistemaDaPasta(this, uri, if (i == 0) null else sistemas[i - 1])
                d.dismiss()
                varrerRoms()
            }
            .setOnCancelListener { varrerRoms() }
            .show()
    }

    private fun removerPasta(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        Roms.definirSistemaDaPasta(this, uri, null)
        prefs.edit().putStringSet("pastas_roms", pastasRoms().map { it.toString() }.toSet() - uri.toString()).apply()
        varrerRoms()
    }

    private fun opcoesPasta(v: View, uri: Uri) {
        PopupMenu(this, v).apply {
            menu.add(0, 2, 0, "Mudar plataforma")
            menu.add(0, 1, 1, "Remover esta pasta")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    50 -> infoJogo[v]?.invoke()
                    1 -> removerPasta(uri)
                    2 -> escolherPlataformaDaPasta(uri)
                }
                true
            }
            show()
        }
    }

    private fun varrerRoms() {
        val pastas = pastasRoms()
        varrendoRoms = true
        descartar(Tela.JOGOS)
        if (telaAtual == Tela.JOGOS) mostrar(Tela.JOGOS)
        val anteriores = romsOriginais.ifEmpty { Roms.lerCache(this) }
        trabalho.execute {
            val v = Roms.varrer(this, pastas, anteriores)
            val lista = v.roms
            Roms.salvarCache(this, lista)
            ui.post {
                if (isDestroyed) return@post
                romsOriginais = lista
                aplicarPersonalizacao()
                varrendoRoms = false
                descartar(Tela.JOGOS)
                descartar(Tela.INICIO) // a fileira Jogos do Início também mostra as ROMs
                mostrar(telaAtual)
                val aviso = if (v.pastasFora > 0)
                    "${lista.size} jogos. ${v.pastasFora} pasta(s) não abriram (cartão SD fora?) — mantive os jogos delas."
                else "${lista.size} jogos encontrados"
                Toast.makeText(this, aviso, Toast.LENGTH_LONG).show()
                carregarCapas()
            }
        }
    }

    private fun abrirRom(rom: Rom) {
        val a = Roms.abertura(this, rom)
        if (a == null) {
            Toast.makeText(this, "Nenhum emulador de ${rom.sistema.nome} instalado. Escolha um em Configurações → Emuladores.",
                Toast.LENGTH_LONG).show()
            return
        }
        a.aviso?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        abrirJogo(a.intent.component?.packageName ?: a.intent.`package`, idRom(rom) to rom.nome) { abrir(a.intent) }
    }

    // ---------- Apps e Pesquisa ----------

    private fun telaApps(): View {
        val (sv, col) = pagina()
        col.setPadding(0, 0, 0, px(60f))
        col.addView(barraTopo(Tela.APPS), LinearLayout.LayoutParams(-1, px(140f)))
        montarSecoes(col, ABA_APPS, listOf(
            Secao("seus", "Seus apps") { b, m -> grade(b, "Seus apps", apps.filter { it.categoria != Categoria.JOGO }, m) },
            Secao("jogos", "Jogos e emuladores") { b, m -> grade(b, "Jogos e emuladores", apps.filter { it.categoria == Categoria.JOGO }, m) },
        ))
        sv.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return sv
    }

    private fun telaBusca(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        col.addView(barraTopo(Tela.BUSCA), LinearLayout.LayoutParams(-1, px(140f)))
        val resultados = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        col.addView(resultados, LinearLayout.LayoutParams(-1, 0, 1f))
        fun filtrar(q: String) {
            val n = normalizar(q)
            val lista = if (n.isBlank()) apps else apps.filter { normalizar(it.nome).contains(n) }
            val jogos = if (n.isBlank()) emptyList() else roms.filter { normalizar(it.nome).contains(n) }
            // Jogos na nuvem (todo o catálogo do GeForce NOW e do Xbox Cloud, não só os da fileira).
            val nuvem = if (n.length < 2) emptyList() else semRepetir(todosNuvem().filter { normalizar(it.nome).contains(n) }).take(30)
            resultados.removeAllViews()
            if (lista.isEmpty() && jogos.isEmpty() && nuvem.isEmpty()) {
                resultados.addView(texto(28f, Paleta.TEXTO_SUAVE, 400).apply {
                    text = "Nada encontrado."
                    gravity = Gravity.CENTER
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return
            }
            val sv = rolagemVertical()
            val grupo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, 0, px(60f))
            }
            if (jogos.isNotEmpty()) {
                adicionarFileira(grupo, "Jogos de emulador", jogos.map { rom -> cardRom(rom, rom.sistema.nome, null) },
                    conteudo = true, margemTitulo = px(20f))
            }
            if (nuvem.isNotEmpty()) {
                adicionarFileira(grupo, "Na nuvem", nuvem.map { cardNuvem(it, comFundo = false) }, conteudo = true, margemTitulo = px(20f))
            }
            grade(grupo, if (n.isBlank()) "Todos os apps" else "Apps", lista, px(20f))
            sv.addView(grupo, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            resultados.addView(sv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        campoBusca?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) = filtrar(e?.toString().orEmpty())
        })
        filtrar("")
        return col
    }

    /** Grade de banners (mesmo tamanho dos "Apps favoritos"), quantos couberem por linha. */
    private fun grade(destino: LinearLayout, titulo: String, lista: List<AppInfo>, margemTitulo: Int) {
        if (lista.isEmpty()) return
        adicionarTitulo(destino, titulo, margemTitulo)
        val largura = telas.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        // Pelo menos o tamanho dos "Apps favoritos" do Início (300×169 na referência); as colunas
        // que cabem são esticadas para ocupar a largura toda, sem faixa vazia à direita.
        val vao = px(26f)
        val util = largura - 2 * px(107f)
        val fixas = prefs.getInt("colunas_apps", 0)
        val colunas = if (fixas > 0) fixas else max(1, (util + vao) / (px(300f) + vao))
        val cardW = (util - vao * (colunas - 1)) / colunas
        val cardH = cardW * 169 / 300
        lista.chunked(colunas).forEachIndexed { i, grupo ->
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(px(107f), 0, 0, 0)
                tag = LINHA_GRADE // ◀ no primeiro app da linha abre o painel da seção
            }
            grupo.forEach { app -> linha.addView(bannerApp(app, cardW, cardH), LinearLayout.LayoutParams(cardW, -2).apply { rightMargin = vao }) }
            destino.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) px(19f) else px(4f) })
        }
    }

    // ---------- Ações ----------

    private fun abrirApp(app: AppInfo) {
        abrir(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.componente)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    private fun abrirAssistente() {
        for (acao in arrayOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)) {
            try {
                startActivity(Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        mostrar(Tela.BUSCA)
    }

    private fun abrir(i: Intent) {
        Sons.tocar(Sons.Evento.ABRIR)
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun opcoes(v: View, app: AppInfo) = mostrarMenu(v, acoesApp(v, app))

    private fun acoesApp(v: View, app: AppInfo): List<Acao> {
        val oculto = app.pacote in ocultos()
        val jogo = ehJogoAndroid(app)
        val favorito = ehFavorito(app)
        val id = idApp(app)
        return acoesDoCard(v) + listOfNotNull(
            Acao("Trocar capa") { trocarCapa(id, app.nome, emPe = cardEmPe(v), secao = if (jogo) "android" else null) },
            if (jogo) Acao("Trocar arte de fundo") { trocarFundo(id, app.nome) } else null,
            Acao("Usar foto da galeria") { capaDaGaleria(id, cardEmPe(v)) },
            Acao("Renomear") { renomear(id, app.nome) },
            if (foiPersonalizado(id)) Acao("Restaurar nome, capa e fundo originais") { restaurar(id) } else null,
            if (!jogo) Acao(if (favorito) "Tirar dos favoritos" else "Adicionar aos favoritos") { definirFavorito(app, !favorito) } else null,
            Acao(if (jogo) "Tirar de Jogos" else "Mostrar em Jogos") {
                definirJogo(app, !jogo)
                remontar()
                carregarCapas()
            },
            Acao(if (oculto) "Mostrar na tela inicial" else "Remover da tela inicial") { alternarOculto(app.pacote, oculto) },
            Acao("Informações do app") {
                abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pacote, null)))
            },
        )
    }

    private fun ocultos(): Set<String> = prefs.getStringSet("ocultos", emptySet()) ?: emptySet()

    private fun alternarOculto(pacote: String, oculto: Boolean) {
        val novo = ocultos().toMutableSet()
        if (oculto) novo.remove(pacote) else novo.add(pacote)
        prefs.edit().putStringSet("ocultos", novo).apply()
        remontar()
        val aviso = if (oculto) "Voltou para a tela inicial" else "Removido da tela inicial. Para voltar, toque e segure o app na aba Apps."
        Toast.makeText(this, aviso, Toast.LENGTH_LONG).show()
    }

    private fun tratarVoltar() {
        when {
            movendo != null -> terminarMover()
            (currentFocus as? TelaPs4)?.voltar() == true -> {}
            telaAtual != Tela.INICIO -> mostrar(Tela.INICIO)
            !abertoComoHome -> finish()
            // Como tela inicial, Voltar no início não faz nada.
        }
    }

    /** Controle: A abre, B volta, Y (ou Menu) abre as opções do card. No modo "Mover", ◀ ▶ movem o card. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Qualquer botão do controle tira do modo imersivo (sem executar o botão) e reinicia o timer.
        // Volume fica de fora: ajusta o som do trailer sem trazer a interface.
        if (e.keyCode !in VOLUME) {
            if (modoImersivo && e.action == KeyEvent.ACTION_DOWN) {
                teclaImersiva = e.keyCode
                movimentoControle()
                return true
            }
            if (e.keyCode == teclaImersiva) {
                if (e.action == KeyEvent.ACTION_UP) teclaImersiva = -1
                return true
            }
            if (e.action == KeyEvent.ACTION_DOWN) movimentoControle()
        }
        // Qualquer tecla tira o descanso de tela (sem executar a tecla).
        if (descansoAtivo()) {
            if (e.action == KeyEvent.ACTION_UP) esconderDescanso()
            return true
        }
        reiniciarOcio()
        // PS segurado no launcher sem quem cuide dele (nem root nem o serviço de acessibilidade): explica como ligar.
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && !Root.ligado(this) && !ServicoAcessibilidade.ativo) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) psSemServicoDesde = System.currentTimeMillis()
            if (e.action == KeyEvent.ACTION_UP && System.currentTimeMillis() - psSemServicoDesde > 500) pedirServicoAcessibilidade()
            return true
        }
        val m = movendo
        if (m != null) {
            if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> passo(m, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> passo(m, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> terminarMover()
            }
            return true
        }
        if (teclaSecao(e)) return true
        // Primeira seta do controle com o foco no ralo invisível: vai para o primeiro card da tela.
        if ((currentFocus == null || currentFocus?.tag == RALO) && e.keyCode in SETAS) {
            if (e.action == KeyEvent.ACTION_DOWN) cacheTelas[telaAtual]?.let { primeiroCard(it)?.requestFocus() }
            return true
        }
        if (atalhoDoControle(e)) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (e.action == KeyEvent.ACTION_UP) currentFocus?.performClick()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (e.action == KeyEvent.ACTION_UP) tratarVoltar()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_UP) currentFocus?.performLongClick()
                return true
            }
            // Quadrado (PlayStation) / X (Xbox): informações do jogo em foco.
            // Fora de um jogo, o Quadrado não faz nada (antes o Android o tratava como OK e abria o app).
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (e.action == KeyEvent.ACTION_UP) (currentFocus as? TelaPs4)?.informacoes() ?: currentFocus?.let { infoJogo[it] }?.invoke()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- Atalhos do controle ----------

    /**
     * R1 / L1: próxima aba / aba anterior (Início ↔ Jogos ↔ Apps), já com o foco no primeiro card.
     * R2 / L2: pula [PULO_CARDS] cards na fileira em foco (segurar continua pulando; no fim, vai ao último).
     * Start: configurações. Select: pesquisar.
     * true = a tecla era um atalho (já tratado ou ignorado).
     */
    private fun atalhoDoControle(e: KeyEvent): Boolean {
        val codigo = e.keyCode
        if (codigo !in ATALHOS) return false
        // Segurar R2/L2 repete o pulo; os outros só valem uma vez por toque.
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (e.repeatCount > 0 && codigo != KeyEvent.KEYCODE_BUTTON_R2 && codigo != KeyEvent.KEYCODE_BUTTON_L2) return true
        when (codigo) {
            KeyEvent.KEYCODE_BUTTON_R1 -> abaVizinha(1)
            KeyEvent.KEYCODE_BUTTON_L1 -> abaVizinha(-1)
            KeyEvent.KEYCODE_BUTTON_R2 -> pularCards(PULO_CARDS)
            KeyEvent.KEYCODE_BUTTON_L2 -> pularCards(-PULO_CARDS)
            KeyEvent.KEYCODE_BUTTON_START -> abrirConfiguracoes()
            KeyEvent.KEYCODE_BUTTON_SELECT -> mostrar(Tela.BUSCA)
        }
        return true
    }

    /** Aba ao lado (dá a volta: depois de Apps vem o Início), com o foco no primeiro card dela. */
    private fun abaVizinha(passo: Int) {
        // No estilo PS4 não há abas (a Biblioteca é que mostra Jogos e Apps); no Xbox, LB/RB trocam a categoria.
        if (layoutInicio() == 1) return
        if (layoutInicio() == 2) {
            if (telaAtual != Tela.INICIO) mostrar(Tela.INICIO)
            inicioXbox?.trocarHub(passo)
            return
        }
        // Switch e Xbox Series: sem abas. PS5: L1/R1 trocam Jogos e Mídia. Wii: L1/R1 passam as páginas de canais.
        if (layoutInicio() == 3 || layoutInicio() == 6) return
        if (layoutInicio() == 5) {
            if (telaAtual != Tela.INICIO) mostrar(Tela.INICIO)
            inicioPs5?.trocarAba(if (passo > 0) 1 else 0)
            return
        }
        if (layoutInicio() == 4) {
            if (telaAtual != Tela.INICIO) mostrar(Tela.INICIO)
            inicioWii?.pagina(passo)
            return
        }
        val abas = listOf(Tela.INICIO, Tela.JOGOS, Tela.APPS)
        val atual = abas.indexOf(telaAtual).takeIf { it >= 0 } ?: 0
        val nova = abas[(atual + passo + abas.size) % abas.size]
        mostrar(nova)
        cacheTelas[nova]?.let { tela -> tela.post { primeiroCard(tela)?.requestFocus() } }
    }

    /** Pula [n] cards (para a direita se positivo) na fileira do card em foco; nas pontas, para no primeiro/último. */
    private fun pularCards(n: Int) {
        val foco = currentFocus ?: return
        // Sobe até o item que é filho direto da linha de uma fileira (a linha fica dentro de uma rolagem horizontal).
        var item: View = foco
        while (true) {
            val pai = item.parent as? ViewGroup ?: return
            if (pai is LinearLayout && pai.parent is HorizontalScrollView) break
            item = pai
        }
        val linha = item.parent as LinearLayout
        val i = linha.indexOfChild(item)
        val alvoI = (i + n).coerceIn(0, linha.childCount - 1)
        if (alvoI == i) return
        val alvo = linha.getChildAt(alvoI)
        val focavel = if (alvo.isFocusable) alvo else primeiroFocavel(alvo)
        focavel?.requestFocus()
    }

    private fun primeiroFocavel(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) primeiroFocavel(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Primeiro card da tela, de cima para baixo e da esquerda para a direita. */
    /** Foca o card [id] da fileira [linha] (ou o mesmo card em outra fileira, ou o primeiro da tela). */
    private fun focarDeNovo(tela: View, linha: String?, id: String?) {
        fun achar(v: View, tag: String): View? {
            if (v.tag == tag) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) achar(v.getChildAt(i), tag)?.let { return it }
            return null
        }
        val naLinha = if (linha != null && id != null) achar(tela, linha)?.let { achar(it, id) } else null
        (naLinha ?: id?.let { achar(tela, it) } ?: primeiroCard(tela))?.requestFocus()
    }

    private fun primeiroCard(v: View): View? {
        if ((v is CardTV || v is TelaPs4) && v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) primeiroCard(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---------- Dados ----------

    /** Apps primeiro (rápido); depois, em segundo plano, ROMs do cache e catálogo do TMDB. */
    private fun carregarApps() {
        trabalho.execute {
            val lista = Catalogo.carregar(this)
            val romsCache = Roms.lerCache(this)
            val ats = Atalhos.listar(this)
            ui.post {
                if (isDestroyed) return@post
                appsOriginais = lista
                romsOriginais = romsCache
                atalhos = ats
                aplicarPersonalizacao()
                remontar()
                carregarCatalogo()
                carregarCapas()
            }
        }
    }

    /** Capas do SteamGridDB para apps favoritos, jogos Android e ROMs; só busca o que ainda não está guardado. */
    private fun carregarCapas() {
        val banners = favoritosDe(apps).map { val t = Catalogo.nomeParaArte(it); Capas.Pedido(Capas.chaveApp(t), t, exato = true, pacote = it.pacote) } +
            jogosAndroid(apps).map { Capas.Pedido(Capas.chave(it.nome), it.nome) } +
            roms.map { Capas.Pedido(Capas.chave(it.nome), it.nome, it.sistema.anos, arquivo = it.arquivo) } +
            atalhos.map { val n = nomeAtalho(it); Capas.Pedido(Capas.chave(n), n) } +
            // GeForce NOW: só os da fileira (o Xbox Cloud já vem com as artes da loja).
            (gfnNaFileira() + meusNoGfn()).distinctBy { it.id }.map { Capas.Pedido(Capas.chave(it.nome), it.nome) }
        // Com "Capa original (em pé)", pede também a capa em pé de cada jogo (o banner continua no fundo e nas informações).
        // Temas de blocos quadrados também pedem a em pé (reserva da quadrada) e, com chave, a quadrada.
        val quadrado = temaQuadrado()
        val emPe = if (!algumaEmPe() && !quadrado) emptyList() else
            jogosAndroid(apps).map { Capas.Pedido(Capas.chavePoster(it.nome), it.nome, emPe = true) } +
                roms.map { Capas.Pedido(Capas.chavePoster(it.nome), it.nome, it.sistema.anos, emPe = true, arquivo = it.arquivo) } +
                atalhos.map { val n = nomeAtalho(it); Capas.Pedido(Capas.chavePoster(n), n, emPe = true) }
        val quadradas = if (!quadrado || !Capas.ativo()) emptyList() else
            jogosAndroid(apps).map { Capas.Pedido(Capas.chaveQuadrada(it.nome), it.nome, quadrada = true) } +
                roms.map { Capas.Pedido(Capas.chaveQuadrada(it.nome), it.nome, it.sistema.anos, quadrada = true) } +
                atalhos.map { val n = nomeAtalho(it); Capas.Pedido(Capas.chaveQuadrada(n), n, quadrada = true) }
        val pedidos = banners + emPe + quadradas
        if (pedidos.isEmpty()) return
        trabalho.execute {
            // Uma vez, ao passar a casar pelo nome do arquivo (como o Daijishō): refaz as capas do libretro e as não achadas.
            // De novo ao ganhar os banners das lojas dos consoles (v4).
            if (!prefs.getBoolean("capas_daijisho4", false)) {
                Capas.esquecerLibretro(this)
                Capas.esquecerFaltantes(this)
                prefs.edit().putBoolean("capas_daijisho4", true).apply()
            }
            // Banners das lojas passaram a conferir a época do console (o God of War de PS2 pegava o de PS4).
            if (!prefs.getBoolean("capas_lojas_epoca", false)) {
                Capas.esquecerLojas(this)
                prefs.edit().putBoolean("capas_lojas_epoca", true).apply()
            }
            // As que ficam prontas antes (Steam) já aparecem; o resto entra quando chegar.
            val novas = Capas.buscar(this, pedidos) { parte -> ui.post { aplicarCapas(parte) } }
            ui.post { aplicarCapas(novas) }
        }
    }

    private fun aplicarCapas(novas: Map<String, String>) {
        if (isDestroyed || novas == capas) return
        capas = novas
        descartar(Tela.JOGOS)
        descartar(Tela.INICIO)
        mostrar(telaAtual)
    }

    private fun carregarCatalogo() {
        carregarRecomendacoes()
        if (!Tmdb.ativo()) return
        val provedores = servicos(apps.filter { it.pacote !in ocultos() }).map { it.second }
        trabalho.execute {
            val novo = Tmdb.catalogo(this, provedores)
            ui.post {
                if (isDestroyed || novo == catalogo) return@post
                catalogo = novo
                remontar()
            }
        }
    }

    // ---------- Fileiras extras: Stremio, YouTube (SmartTube) e mais Netflix ----------

    private var recs: Map<String, List<ItemRec>> = emptyMap()

    private fun instalado(vararg pacotes: String) = pacotes.firstOrNull { p -> apps.any { it.pacote == p } }
    private fun pacoteStremio() = instalado("com.stremio.one")
    private fun pacoteYoutube() = instalado("org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv", "com.google.android.youtube")
    private fun pacoteNetflix() = instalado("com.netflix.ninja", "com.netflix.mediaclient")

    private fun carregarRecomendacoes() {
        val st = pacoteStremio() != null
        val yt = pacoteYoutube() != null
        val nf = pacoteNetflix() != null
        if (!st && !yt && !nf) return
        trabalho.execute {
            // Cada fileira aparece quando fica pronta (as do Stremio esperam as traduções).
            val novo = Recomendacoes.carregar(this, st, yt, nf) { parte -> ui.post { aplicarRecs(parte) } }
            ui.post { aplicarRecs(novo) }
        }
    }

    private fun aplicarRecs(novo: Map<String, List<ItemRec>>) {
        if (isDestroyed || novo.keys == recs.keys && novo.mapValues { it.value.map { i -> i.nome } } == recs.mapValues { it.value.map { i -> i.nome } }) return
        recs = novo
        descartar(Tela.INICIO)
        mostrar(telaAtual)
    }

    /** (id da seção, título, pacote do app) de cada fileira extra que tem itens. */
    private fun fileirasExtras(): List<Triple<String, String, String>> {
        val l = ArrayList<Triple<String, String, String>>()
        pacoteStremio()?.let { p ->
            l += Triple("stremio_filmes", "Filmes populares no Stremio", p)
            l += Triple("stremio_series", "Séries populares no Stremio", p)
            l += Triple("stremio_lancamentos", "Lançamentos de ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}", p)
            l += Triple("stremio_acao", "Filmes de ação", p)
            l += Triple("stremio_comedia", "Comédias", p)
            l += Triple("stremio_animacao", "Animações", p)
            l += Triple("apple_filmes", "Em alta na Apple TV (iTunes)", p)
        }
        pacoteYoutube()?.let { p -> l += Triple("youtube", "Em alta no YouTube", p) }
        pacoteNetflix()?.let { p ->
            l += Triple("netflix_series", "Séries em alta na Netflix", p)
            l += Triple("netflix_filmes", "Filmes em alta na Netflix", p)
            l += Triple("netflix_top", "Mais bem avaliados na Netflix", p)
        }
        return l.filter { !recs[it.first].isNullOrEmpty() }
    }

    /** Abre o título/vídeo direto no app (Stremio ou SmartTube); se ele não aceitar o link, abre o app. */
    private fun abrirRec(item: ItemRec, pacote: String) {
        val app = apps.firstOrNull { it.pacote == pacote }
        val link = item.link
        if (link != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pacote)
            if (i.resolveActivity(packageManager) != null) abrir(i) else app?.let { abrirApp(it) }
        } else {
            app?.let { abrirApp(it) }
        }
    }

    private fun cardRec(item: ItemRec, pacote: String): View {
        val app = apps.firstOrNull { it.pacote == pacote }
        return cardGrande(item.nome, item.detalhe, app?.icone, app?.cor ?: Paleta.AZUL, item.imagem, { abrirRec(item, pacote) }).also { card ->
            // Trailer no fundo: Stremio já traz o id do YouTube; Netflix pelo TMDB; "Em alta no YouTube" é o próprio vídeo.
            val doLink = item.link?.substringAfter("watch?v=", "")?.takeIf { it.isNotBlank() }
            val yt = item.youtube ?: doLink
            if (yt != null || item.tmdb != null) {
                val tipo = when {
                    item.tmdb?.startsWith("tv") == true || item.link?.contains("/series/") == true -> "Série"
                    doLink != null -> "Vídeo"
                    else -> "Filme"
                }
                filmeDe[card] = FonteFilme(item.nome, item.detalhe, "${app?.nome ?: ""} · $tipo".trimStart(' ', '·'), yt, item.tmdb)
            }
        }
    }

    private fun atualizarRelogio() {
        val c = Calendar.getInstance()
        // "Relógio" (Aparência): 0 = 24 h, 1 = 12 h, 2 = 24 h com a data, 3 = escondido.
        val modo = prefs.getInt("relogio", 0)
        val hora24 = String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val hora: CharSequence = when (modo) {
            1 -> String.format(Locale.ROOT, "%d:%02d %s", (c.get(Calendar.HOUR) + 11) % 12 + 1, c.get(Calendar.MINUTE),
                if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
            // A data numa linha menor, embaixo da hora (do lado, invadiria os ícones da barra).
            2 -> {
                val data = java.text.SimpleDateFormat("EEE, d MMM", Locale.forLanguageTag("pt-BR")).format(c.time)
                    .replace(".", "").replaceFirstChar { it.uppercase() }
                android.text.SpannableString("$hora24\n$data").apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.5f), hora24.length + 1, length, 0)
                }
            }
            else -> hora24
        }
        atualizarInfosTopo()
        inicioPs4?.atualizar()
        inicioXbox?.atualizar()
        inicioSwitch?.atualizar()
        inicioWii?.atualizar()
        inicioPs5?.atualizar()
        inicioSeries?.atualizar()
        relogios.forEach {
            it.text = hora
            it.gravity = Gravity.END
            it.setLineSpacing(if (modo == 2) it.textSize * 0.25f else 0f, 1f)
            it.visibility = if (modo == 3) View.GONE else View.VISIBLE
        }
    }

    // ---------- Ajudantes ----------

    private fun rotuloCategoria(app: AppInfo) = when (app.categoria) {
        Categoria.VIDEO -> "Vídeos"
        Categoria.MUSICA -> "Música"
        Categoria.JOGO -> if (Catalogo.ehEmulador(app.pacote)) "Emulador" else "Jogo"
        Categoria.OUTRO -> "App"
    }

    private fun primeiraFrase(t: String): String {
        val fim = t.indexOfAny(charArrayOf('.', '!', '?'))
        return if (fim in 1..140) t.substring(0, fim + 1) else t.take(140)
    }

    private fun px(ref: Float) = (ref * s).toInt()

    private fun rolagemVertical() = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        // Corta no tamanho da tela: fileiras fora da tela não são desenhadas.
        clipChildren = true
    }

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Paleta.fonte(peso)
        includeFontPadding = false
    }

    private fun TextView.umaLinha() {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun forma(cor: Int, raio: Float) = GradientDrawable().apply {
        cornerRadius = raio * s
        setColor(cor)
    }

    private fun esconderTeclado(v: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun normalizar(t: String) =
        Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    companion object {
        private const val RALO = "ralo"
        private val SETAS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        private val VOLUME = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
        private val ATALHOS = setOf(
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        /** Quantos cards R2 / L2 pulam de uma vez. */
        private const val PULO_CARDS = 5
        private const val ABA_INICIO = "inicio"
        private const val ABA_JOGOS = "jogos"
        private const val ABA_APPS = "apps"
        private const val LINHA_GRADE = "linha_grade"
        private const val SECAO_FAVORITOS = "favoritos"
        private const val SECAO_VIDEOS = "videos"
        private const val SECAO_JOGOS = "jogos"
        private const val ORDEM_FAVORITOS = "favoritos"
        private const val ORDEM_JOGOS = "jogos_android"
        private const val SECAO_RECENTES = "recentes"
        private const val SECAO_CONQUISTAS = "conquistas"
        private const val SECAO_GFN = "nuvem_gfn"
        private const val SECAO_XBOX = "nuvem_xbox"
        private const val SECAO_XBOX_GRATIS = "nuvem_xbox_gratis"
        private const val SECAO_STREAMING = "streaming"
        private const val SECAO_GFN_MEUS = "nuvem_gfn_meus"
        private const val SECAO_XBOX_CONTA = "xbox_conta"
        private const val PEDIDO_PASTA = 41
        private const val PEDIDO_FOTO = 42
        private const val PEDIDO_EXPORTAR = 43
        private const val PEDIDO_IMPORTAR = 44
        private const val PEDIDO_VOZ = 45
        private const val ORDEM_TODOS_JOGOS = "inicio_todos_jogos"
        const val EXTRA_ABA_JOGOS = "aba_jogos"
        const val EXTRA_ULTIMO_JOGO = "ultimo_jogo"
        const val EXTRA_PACOTE_FRENTE = "pacote_frente"
        /** Vindo do menu rápido: abre as configurações (já na seção Root). */
        const val EXTRA_CONFIG = "abrir_config"
        private const val DESCANSO_MAXIMO = 10 * 60_000L // depois disso a tela pode apagar
    }
}
