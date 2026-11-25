package com.gamelaunchertv.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService

/**
 * Menu rápido por cima do jogo (segurar PS), como o do PS4 mas no visual do Android TV: painel à direita,
 * linhas retangulares com foco branco, organizado em páginas (Tela, Som, Controle, Desempenho, Rede,
 * Carregamento, Energia). No topo: o jogo, bateria do controle e do celular, hora, temperatura e memória.
 * B / Voltar sobe uma página (na primeira, fecha); PS fecha (o serviço cuida do PS, então soltar o botão que
 * abriu o menu não o fecha). Com root, é uma janela "sobre outros apps" (permissão dada ao ligar os recursos);
 * sem root, uma janela do serviço de acessibilidade, com só as opções que o Android permite sem root.
 */
class MenuRapido(
    private val ctx: Context, private val app: String?, private val trabalho: ExecutorService,
    /** Com root (tudo) ou pelo serviço de acessibilidade (o que o Android deixa sem root). */
    private val acoes: AcoesSistema,
    private val aoFechar: () -> Unit,
) {
    private val root = acoes.comRoot
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val s = wm.currentWindowMetrics.bounds.let { Paleta.escala(ctx, minOf(it.width(), it.height())) }
    private var raiz: FrameLayout? = null
    private lateinit var painel: ScrollView
    private val jogo = app?.takeIf { it != ctx.packageName }
    /** Estilo PS4: painel azul-escuro, letra fina, seleção com a moldura azul brilhante. */
    private val ps4 = Estilo.ps4(ctx)
    /** Estilo Xbox 360 (o Guia): painel cinza-escuro, seleção verde. */
    private val xbox = Estilo.xbox(ctx)
    /** Switch (painel cinza-escuro, borda azul-clara) e Wii (painel azul, borda azul-clara). */
    private val estiloConsole = prefs.getInt("layout_inicio", 0)
    private val nomeJogo = jogo?.let {
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }
    }

    /** Páginas abertas: cada uma com a linha que a abriu (o foco volta nela). */
    private val pilha = ArrayList<Pair<View, View?>>()
    private var infoSistema: TextView? = null

    /** false = sem permissão de sobrepor (não deu para mostrar). */
    fun mostrar(): Boolean {
        // O serviço de acessibilidade desenha por cima sem a permissão de sobrepor; com root ela é dada ao ligar.
        val acessibilidade = ctx is android.accessibilityservice.AccessibilityService
        if (!acessibilidade && !Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Menu rápido: falta a permissão de sobrepor apps. Desligue e ligue os recursos root.", Toast.LENGTH_LONG).show()
            return false
        }
        val telaW = wm.currentWindowMetrics.bounds.width()
        largura = if (ps4) maxOf(px(640f), (telaW * 0.34f).toInt()).coerceAtMost(telaW)
        else maxOf(px(760f), (telaW * 0.4f).toInt()).coerceAtMost(telaW)
        painel = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            elevation = 24f * s
            isClickable = true
        }
        val fundo = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                when (e.keyCode) {
                    // PS é do serviço (lido direto do controle): aqui só não deixa chegar ao jogo.
                    KeyEvent.KEYCODE_BUTTON_MODE -> return true
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                        if (e.action == KeyEvent.ACTION_UP) voltar()
                        return true
                    }
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            setOnClickListener { fechar() } // toque fora do painel fecha
        }
        escurecer = fundo
        fundo.addView(painel, FrameLayout.LayoutParams(largura, -1, Gravity.START))
        aplicarAparencia()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (acessibilidade) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        return try {
            wm.addView(fundo, params)
            raiz = fundo
            abrir(paginaPrincipal(), null)
            // Entra deslizando do lado em que fica.
            painel.translationX = if (naEsquerda()) -largura.toFloat() else largura.toFloat()
            painel.animate().translationX(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            carregarInfoSistema()
            true
        } catch (_: Exception) {
            false
        }
    }

    private var largura = 0
    private var escurecer: FrameLayout? = null

    /** Lado do painel ("menu_lado": 0 = esquerda, 1 = direita). */
    private fun naEsquerda() = prefs.getInt("menu_lado", 0) == 0

    /** Opacidade do painel em % ("menu_opacidade", de 40 a 100). */
    private fun opacidade() = prefs.getInt("menu_opacidade", 92).coerceIn(40, 100)

    /** Lado e transparência do painel (muda na hora, enquanto a pessoa ajusta). */
    private fun aplicarAparencia() {
        val a = opacidade()
        val escuro = Visuais.painelEscuro(estiloConsole)
        if (escuro != null) {
            val (cima, baixo) = escuro.first to escuro.second
            painel.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Paleta.alfa(cima, a * 255 / 100), Paleta.alfa(baixo, a * 255 / 100)))
        } else if (xbox) {
            painel.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Paleta.alfa(0xFF3A3E42.toInt(), a * 255 / 100), Paleta.alfa(0xFF1F2124.toInt(), a * 255 / 100)))
        } else if (ps4) {
            // Azul-escuro do menu rápido do console, com a borda clara do lado do jogo.
            painel.background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Paleta.alfa(0xFF0F2A66.toInt(), a * 255 / 100), Paleta.alfa(0xFF081634.toInt(), a * 255 / 100))),
            ))
        } else painel.setBackgroundColor(Paleta.alfa(Paleta.painel(), a * 255 / 100))
        // Com o painel transparente, o jogo atrás também escurece menos (para dar para ver).
        escurecer?.setBackgroundColor(Color.argb(110 * a / 100, 0, 0, 0))
        painel.elevation = if (a < 100) 0f else 24f * s
        (painel.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = if (naEsquerda()) Gravity.START else Gravity.END
            painel.layoutParams = it
        }
    }

    fun fechar() {
        val r = raiz ?: return
        raiz = null
        try { wm.removeView(r) } catch (_: Exception) {}
        aoFechar()
    }

    // ---------- Páginas ----------

    private fun abrir(pagina: View, origem: View?) {
        pilha += pagina to origem
        exibir(pagina)
        primeiroFocavel(pagina)?.let { v -> v.post { v.requestFocus() } }
    }

    private fun voltar() {
        if (pilha.size <= 1) return fechar()
        val (_, origem) = pilha.removeAt(pilha.lastIndex)
        exibir(pilha.last().first)
        origem?.post { origem.requestFocus() }
    }

    private fun exibir(v: View) {
        painel.removeAllViews()
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        painel.addView(v)
        painel.scrollTo(0, 0)
    }

    private fun pagina(titulo: String, cabecalho: String = "Menu rápido"): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(44f), px(48f), px(44f), px(60f))
        addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = cabecalho })
        addView(texto(34f, Color.WHITE, if (estiloConsole != 0) 300 else 400).apply {
            if (estiloConsole != 0) typeface = Ps4Desenho.leve
            text = titulo
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f); bottomMargin = px(12f) })
    }

    /** Subpágina: uma linha com "›" que abre [montar]. */
    private fun LinearLayout.pasta(titulo: String, resumo: String, montar: LinearLayout.() -> Unit): LinearLayout {
        lateinit var l: LinearLayout
        l = linha(titulo, resumo, seta = true) {
            abrir(pagina(titulo, "Menu rápido").apply(montar), l)
        }
        addView(l, lp())
        return l
    }

    private fun paginaPrincipal() = pagina(nomeJogo ?: "GameLauncherTV").apply {
        addView(texto(21f, Paleta.AZUL_CLARO, 400).apply {
            text = resumo()
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-1, -2))
        infoSistema = texto(19f, Paleta.TEXTO_SUAVE, 400).also {
            addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f); bottomMargin = px(14f) })
        }

        addView(linha("Voltar ao jogo", null) { fechar() }, lp())
        addView(linha("Ir para o início", "O jogo continua aberto; aperte PS no launcher para voltar a ele") {
            fechar()
            if (jogo != null) prefs.edit().putString("ultimo_jogo", jogo).apply()
            trabalho.execute { acoes.irParaInicio() }
        }, lp())
        if (jogo != null) addView(linha("Fechar o jogo", if (root) "Fecha $nomeJogo agora (o que não foi salvo no jogo se perde)"
            else "Vai para o início e encerra $nomeJogo (sem root, alguns emuladores continuam abertos)") {
            fechar()
            trabalho.execute {
                acoes.fecharApp(jogo)
                acoes.irParaInicio()
            }
        }, lp())
        pasta(if (root) "Apps abertos" else "Apps recentes", "Trocar para outro jogo ou app") { paginaAbertos() }
        addView(linha("Capturar a tela", "Salva em Imagens › GameLauncherTV") { capturar() }, lp())
        if (jogo != null) addView(linha("Modo mouse neste app",
            (if (ServicoAcessibilidade.instancia?.mouse?.ligadoPara(jogo) == true) "Ligado" else "Desligado") +
                " · cursor no direcional, X toca, Círculo volta; Options + botão mapeia o botão") {
            val r = acoes.alternarMouse(jogo)
            fechar()
            Toast.makeText(ctx, when (r) {
                null -> "Ligue o serviço de acessibilidade do GameLauncherTV (Configurações › Menu rápido)"
                true -> "Modo mouse ligado em $nomeJogo"
                false -> "Modo mouse desligado em $nomeJogo"
            }, Toast.LENGTH_LONG).show()
        }, lp())
        addView(linha("Dormir", "Pausa o que toca, liga a economia de energia e apaga a tela (a TV no cabo entra em espera)") {
            fechar()
            trabalho.execute { Dormir.dormir(ctx) { acoes.desligarTela() } }
        }, lp())
        if (root) addView(linhaGravar(), lp())

        pasta("Tela", if (root) "Brilho, brilho automático, taxa da tela" else "Brilho, brilho automático, desligar a tela") { paginaTela() }
        pasta("Som", "Volume, sem som, Não perturbe") { paginaSom() }
        pasta("Controle", controlesDeJogo().map { it.name.removeSuffix(" Touchpad").removeSuffix(" Motion Sensors") }.distinct().joinToString(", ").ifEmpty { "Nenhum controle conectado" }) { paginaControle() }
        pasta("Wi-Fi", "Carregando…") { paginaWifi() }.also { l -> trabalho.execute { estadoWifi().let { e -> ui.post { (l.tag as TextView).text = e } } } }
        pasta("Bluetooth", resumoBluetooth()) { paginaBluetooth() }
        pasta("Desempenho", if (root) "Liberar memória, modo desempenho, temperatura" else "Liberar memória, temperatura") { paginaDesempenho() }
        if (root) pasta("Carregamento", RecursosRoot.MODOS_CARGA[prefs.getInt("carga_modo", 0)]) { paginaCarga() }
        else atalhoAndroid("Bateria", Intent.ACTION_POWER_USAGE_SUMMARY)
        pasta("Aparência do menu", (if (naEsquerda()) "À esquerda" else "À direita") + " · ${opacidade()}% opaco") { paginaAparencia() }
        pasta("Energia", "Desligar a tela, reiniciar, desligar") { paginaEnergia() }
        addView(linha("Configurações do launcher", "Abre as configurações do GameLauncherTV") {
            fechar()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CONFIG, true))
        }, lp())
    }

    /** Fecha o menu e abre uma tela de configurações do Android ([acao]); Voltar nela volta para o jogo. */
    private fun abrirAndroid(acao: String) {
        fechar()
        try {
            ctx.startActivity(Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Esta tela não existe neste aparelho", Toast.LENGTH_SHORT).show()
        }
    }

    private fun LinearLayout.atalhoAndroid(titulo: String, acao: String) =
        addView(linha(titulo, "Abre a tela do Android (Voltar nela volta para o jogo)", seta = true) { abrirAndroid(acao) }, lp())

    /** Sem root, mudar o brilho precisa da permissão "Alterar configurações do sistema" (dada uma vez). */
    private fun podeMudarBrilho() = root || Settings.System.canWrite(ctx)

    private fun LinearLayout.pedirPermissaoBrilho() = addView(linha("Permitir ajustar o brilho",
        "Uma vez só: ligue \"Permitir alteração das configurações do sistema\" para o GameLauncherTV", seta = true) {
        fechar()
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }, lp())

    private fun definirBrilho(f: Float) {
        if (root) return trabalho.execute { RecursosRoot.definirBrilho(f) }
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (f.coerceIn(0.02f, 1f) * 255).toInt())
        } catch (_: Exception) {}
    }

    private fun brilhoAutomatico(sim: Boolean) {
        if (root) return trabalho.execute { RecursosRoot.brilhoAutomatico(sim) }
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (sim) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        } catch (_: Exception) {}
    }

    private fun LinearLayout.paginaTela() {
        if (podeMudarBrilho()) {
            val brilho = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
            addView(deslizante("Brilho", brilho) { f -> definirBrilho(f) }, lp())
            val auto = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 } catch (_: Exception) { false }
            addView(alternar("Brilho automático", auto) { sim -> brilhoAutomatico(sim) }, lp())
        } else pedirPermissaoBrilho()
        if (root) {
            // A configuração da Samsung não é legível por apps: vale a taxa em que a tela está agora.
            val alta = (ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f) > 61f
            addView(alternar("Tela a 120 Hz", alta, "60 Hz gasta menos bateria") { sim -> trabalho.execute { RecursosRoot.taxaAlta(sim) } }, lp())
        }
        addView(linha("Desligar a tela", "O jogo pausa; aperte o botão do celular para voltar") {
            fechar()
            trabalho.execute { acoes.desligarTela() }
        }, lp())
        atalhoAndroid("Mais opções de tela", Settings.ACTION_DISPLAY_SETTINGS)
    }

    private fun LinearLayout.paginaSom() {
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        addView(deslizante("Volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol, passos = maxVol) { f ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * maxVol), 0)
        }, lp())
        addView(alternar("Sem som", audio.isStreamMute(AudioManager.STREAM_MUSIC)) { sim ->
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (sim) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }, lp())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (root || nm.isNotificationPolicyAccessGranted) {
            addView(alternar("Não perturbe", nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
                "Sem notificações aparecendo no jogo") { sim ->
                if (root) trabalho.execute { RecursosRoot.naoPerturbe(sim) }
                else nm.setInterruptionFilter(if (sim) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            }, lp())
        } else addView(linha("Permitir o Não perturbe", "Uma vez só: libere o acesso ao Não perturbe para o GameLauncherTV", seta = true) {
            abrirAndroid(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }, lp())
        atalhoAndroid("Mais opções de som", Settings.ACTION_SOUND_SETTINGS)
    }

    /**
     * Controles de jogo: os que informam bateria (DualShock, DualSense, Xbox…). Controles remotos e teclados USB
     * também se apresentam como "controle", mas só aparecem se não houver nenhum outro.
     */
    private fun controlesDeJogo(): List<android.view.InputDevice> {
        val todos = Controle.conectados().distinctBy { it.descriptor }
        return todos.filter { it.batteryState.isPresent }.ifEmpty { todos }
    }

    private fun LinearLayout.paginaControle() {
        val controles = controlesDeJogo()
        if (controles.isEmpty()) addView(nota("Nenhum controle conectado."))
        for (d in controles) {
            val b = d.batteryState.takeIf { it.isPresent }
            val n = b?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
            val nome = d.name.removeSuffix(" Touchpad").removeSuffix(" Motion Sensors")
            addView(linha(nome, when {
                n == null -> "Bateria desconhecida"
                b.status == android.hardware.BatteryState.STATUS_CHARGING -> "Bateria $n% · carregando"
                else -> "Bateria $n%"
            }) {}, lp())
        }
        lateinit var luz: LinearLayout
        luz = linha("Luz do controle", Controle.LUZES[prefs.getInt("luz_controle", 0)], seta = true) {
            val nova = (prefs.getInt("luz_controle", 0) + 1) % Controle.LUZES.size
            prefs.edit().putInt("luz_controle", nova).apply()
            (luz.tag as TextView).text = Controle.LUZES[nova]
            Controle.controlesMudaram()
            Controle.aplicarLuz(ctx)
        }
        addView(luz, lp())
        lateinit var brilho: LinearLayout
        val rotulos = listOf("Forte", "Médio", "Fraco")
        brilho = linha("Brilho da luz", rotulos[prefs.getInt("brilho_controle", 0).coerceIn(0, 2)], seta = true) {
            val novo = (prefs.getInt("brilho_controle", 0) + 1) % rotulos.size
            prefs.edit().putInt("brilho_controle", novo).apply()
            (brilho.tag as TextView).text = rotulos[novo]
            Controle.controlesMudaram()
            Controle.aplicarLuz(ctx)
        }
        addView(brilho, lp())
        addView(linha("Testar vibração", null) { Controle.vibrar(forte = true) }, lp())
        atalhoAndroid("Parear outro controle", Settings.ACTION_BLUETOOTH_SETTINGS)
        addView(alternar("Vibrar ao abrir um jogo", prefs.getBoolean("vibrar_abrir", false)) { sim ->
            prefs.edit().putBoolean("vibrar_abrir", sim).apply()
        }, lp())
    }

    private fun LinearLayout.paginaDesempenho() {
        val info = nota(infoSistema?.text?.toString().orEmpty())
        addView(info)
        addView(linha("Liberar memória agora", if (root) "Fecha apps em segundo plano e limpa o cache do sistema" else "Encerra os apps em segundo plano") {
            val antes = memoriaLivre()
            Toast.makeText(ctx, "Liberando memória…", Toast.LENGTH_SHORT).show()
            trabalho.execute {
                if (root) RecursosRoot.liberarMemoria() else liberarSemRoot()
                Thread.sleep(800)
                val ganho = (memoriaLivre() - antes).coerceAtLeast(0)
                ui.post {
                    Toast.makeText(ctx, "Memória livre: ${gb(memoriaLivre())} (+${ganho / (1L shl 20)} MB)", Toast.LENGTH_LONG).show()
                    carregarInfoSistema(info)
                }
            }
        }, lp())
        if (!root) return
        addView(alternar("Modo desempenho fixo", prefs.getBoolean("desempenho_fixo_menu", false),
            "Processador sem baixar o ritmo (esquenta e gasta mais)") { sim ->
            prefs.edit().putBoolean("desempenho_fixo_menu", sim).apply()
            trabalho.execute { RecursosRoot.desempenhoFixo(sim) }
        }, lp())
        addView(alternar("Modo desempenho ao abrir jogos", prefs.getBoolean("desempenho_jogo", false),
            "Libera memória e liga o modo desempenho a cada jogo") { sim -> prefs.edit().putBoolean("desempenho_jogo", sim).apply() }, lp())
    }

    /** Sem root: encerra os processos em segundo plano de cada app instalado (menos o jogo e o launcher). */
    private fun liberarSemRoot() {
        val am = ctx.getSystemService(ActivityManager::class.java)
        ctx.packageManager.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            .map { it.packageName }.filter { it != jogo && it != ctx.packageName }
            .forEach { try { am.killBackgroundProcesses(it) } catch (_: Exception) {} }
    }

    private fun global(chave: String) = try { Settings.Global.getInt(ctx.contentResolver, chave) == 1 } catch (_: Exception) { false }

    /**
     * Estado do Wi-Fi. Com root, com o nome da rede e o IP; sem root o Android esconde o nome da rede (só com a
     * permissão de localização), então fica o sinal.
     */
    private fun estadoWifi(): String {
        if (root) return RecursosRoot.estadoWifi()
        if (!global(Settings.Global.WIFI_ON)) return "Desligado"
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Ligado, sem rede conectada"
        val rssi = (caps.transportInfo as? android.net.wifi.WifiInfo)?.rssi
        val sinal = when {
            rssi == null || rssi <= -127 -> null
            rssi >= -55 -> "sinal ótimo"
            rssi >= -67 -> "sinal bom"
            rssi >= -75 -> "sinal fraco"
            else -> "sinal muito fraco"
        }
        return listOfNotNull("Conectado ao Wi-Fi", sinal).joinToString(" · ")
    }

    private fun LinearLayout.paginaWifi() {
        val estado = nota("Carregando…")
        addView(estado)
        trabalho.execute { estadoWifi().let { e -> ui.post { estado.text = e } } }
        if (root) {
            addView(alternar("Wi-Fi", global(Settings.Global.WIFI_ON)) { sim ->
                trabalho.execute {
                    RecursosRoot.wifi(sim)
                    Thread.sleep(2500) // o tempo de conectar de novo
                    estadoWifi().let { e -> ui.post { estado.text = e } }
                }
            }, lp())
            atalhoAndroid("Escolher rede Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
            addView(alternar("Modo avião", global(Settings.Global.AIRPLANE_MODE_ON), "Desliga as redes (o Bluetooth pode continuar)") { sim ->
                trabalho.execute { RecursosRoot.modoAviao(sim) }
            }, lp())
        } else {
            // Sem root o Android não deixa apps ligarem o Wi-Fi: o painel dele faz isso (e lista as redes).
            addView(linha("Ligar, desligar ou trocar de rede", "Abre o painel de Wi-Fi do Android por cima do jogo", seta = true) {
                abrirAndroid(Settings.Panel.ACTION_WIFI)
            }, lp())
            atalhoAndroid("Modo avião", Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        }
        atalhoAndroid("Mais opções de rede", Settings.ACTION_WIRELESS_SETTINGS)
    }

    /** Aparelhos Bluetooth pareados: (nome, conectado, bateria em % ou -1). Precisa da permissão dada com root. */
    private fun aparelhosBluetooth(): List<Triple<String, Boolean, Int>> = try {
        val adaptador = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        // Sem API pública de "conectado": vale quem está ativo agora como controle (com a bateria dele) ou como
        // saída de áudio Bluetooth (fone, caixa de som). O nome do pareamento ("Wireless Controller") é parte
        // do nome do controle ("Sony Interactive Entertainment Wireless Controller").
        val controles = Controle.conectados()
        val audios = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER) }
            .map { it.productName.toString() }
        fun parecido(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && (a.contains(b, true) || b.contains(a, true))
        adaptador.bondedDevices.map { d ->
            val nome = d.name ?: d.address
            val controle = controles.firstOrNull { parecido(it.name, nome) }
            val bateria = controle?.batteryState?.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt() } ?: -1
            Triple(nome, controle != null || audios.any { parecido(it, nome) }, bateria)
        }.sortedByDescending { it.second }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun resumoBluetooth(): String {
        if (!global(Settings.Global.BLUETOOTH_ON)) return "Desligado"
        val conectados = aparelhosBluetooth().filter { it.second }.map { it.first }
        return if (conectados.isEmpty()) "Ligado" else "Conectado: " + conectados.joinToString(", ")
    }

    private fun LinearLayout.paginaBluetooth() {
        if (root) addView(alternar("Bluetooth", global(Settings.Global.BLUETOOTH_ON), "Desligar desconecta o controle sem fio!") { sim ->
            trabalho.execute { RecursosRoot.bluetooth(sim) }
        }, lp())
        else addView(nota(if (global(Settings.Global.BLUETOOTH_ON)) "Bluetooth ligado" else "Bluetooth desligado"))
        // Sem root, a lista precisa da permissão "Dispositivos próximos" (pedida ao ligar o menu nas configurações).
        if (!root && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            addView(nota("Para listar os aparelhos aqui, permita \"Dispositivos próximos\" ao GameLauncherTV."))
        }
        val aparelhos = aparelhosBluetooth()
        if (aparelhos.isNotEmpty()) addView(nota("Aparelhos pareados"))
        for ((nome, conectado, bateria) in aparelhos) {
            addView(linha(nome, (if (conectado) "Conectado" else "Pareado, desconectado") +
                (if (bateria >= 0) " · bateria $bateria%" else "")) { abrirAndroid(Settings.ACTION_BLUETOOTH_SETTINGS) }, lp())
        }
        atalhoAndroid("Parear ou conectar aparelhos", Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    private fun LinearLayout.paginaAbertos() {
        val aviso = nota("Carregando…")
        addView(aviso)
        val alvo = this
        trabalho.execute {
            val abertas = acoes.abertos().filter { it != jogo }
            ui.post {
                aviso.text = when {
                    abertas.isEmpty() -> if (root) "Nenhum outro app aberto." else "Nenhum outro app usado desde que o menu foi ligado."
                    root -> "Escolha para trocar (o atual fica aberto)."
                    else -> "Usados há pouco. Escolha para trocar (o atual fica aberto)."
                }
                for (t in abertas) {
                    val pm = ctx.packageManager
                    val nome = try { pm.getApplicationLabel(pm.getApplicationInfo(t, 0)).toString() } catch (_: Exception) { t }
                    alvo.addView(linha(nome, t) {
                        fechar()
                        if (jogo != null) prefs.edit().putString("ultimo_jogo", jogo).apply()
                        trabalho.execute { acoes.voltarPara(t) }
                    }, lp())
                }
                if (abertas.isNotEmpty() && root) alvo.addView(confirmar("Fechar os outros apps", "Fecha todos os de cima (o jogo atual fica)") {
                    trabalho.execute { abertas.forEach { RecursosRoot.fecharApp(it) } }
                    ui.post { Toast.makeText(ctx, "${abertas.size} app(s) fechado(s)", Toast.LENGTH_SHORT).show() }
                }, lp())
            }
        }
    }

    private fun LinearLayout.paginaAparencia() {
        lateinit var lado: LinearLayout
        lado = linha("Lado do menu", if (naEsquerda()) "Esquerda" else "Direita", seta = true) {
            prefs.edit().putInt("menu_lado", if (naEsquerda()) 1 else 0).apply()
            (lado.tag as TextView).text = if (naEsquerda()) "Esquerda" else "Direita"
            aplicarAparencia()
        }
        addView(lado, lp())
        // 40% a 100%, de 5 em 5.
        addView(deslizante("Opacidade do painel", (opacidade() - 40) / 60f, passos = 12, rotulo = { f -> "${40 + Math.round(f * 60)}%" }) { f ->
            prefs.edit().putInt("menu_opacidade", 40 + Math.round(f * 60)).apply()
            aplicarAparencia()
        }, lp())
        addView(nota("Menos opaco deixa ver o jogo atrás do menu."))
    }

    private fun LinearLayout.paginaCarga() {
        val linhas = ArrayList<LinearLayout>()
        RecursosRoot.MODOS_CARGA.forEachIndexed { i, modo ->
            val l = linha((if (i == prefs.getInt("carga_modo", 0)) "●  " else "○  ") + modo, null) {
                prefs.edit().putInt("carga_modo", i).apply()
                linhas.forEachIndexed { j, outra -> ((outra.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text =
                    (if (j == i) "●  " else "○  ") + RecursosRoot.MODOS_CARGA[j] }
                reaplicarCarga()
            }
            linhas += l
            addView(l, lp())
        }
        lateinit var limite: LinearLayout
        limite = linha("Limite da carga", "${RecursosRoot.LIMITES.getOrElse(prefs.getInt("carga_limite", 2)) { 80 }}% (em \"Limitar a carga\")", seta = true) {
            val novo = (prefs.getInt("carga_limite", 2) + 1) % RecursosRoot.LIMITES.size
            prefs.edit().putInt("carga_limite", novo).apply()
            (limite.tag as TextView).text = "${RecursosRoot.LIMITES[novo]}% (em \"Limitar a carga\")"
            reaplicarCarga()
        }
        addView(limite, lp())
        atalhoAndroid("Bateria do Android", Intent.ACTION_POWER_USAGE_SUMMARY)
    }

    private fun LinearLayout.paginaEnergia() {
        addView(linha("Desligar a tela", "O jogo pausa; aperte o botão do celular para voltar") {
            fechar()
            trabalho.execute { acoes.desligarTela() }
        }, lp())
        if (root) {
            addView(confirmar("Reiniciar", "O jogo fecha sem salvar") { trabalho.execute { RecursosRoot.reiniciar() } }, lp())
            addView(confirmar("Desligar", "O jogo fecha sem salvar") { trabalho.execute { RecursosRoot.desligar() } }, lp())
        } else addView(linha("Reiniciar ou desligar", "Abre o menu de energia do Android", seta = true) {
            fechar()
            acoes.menuEnergia()
        }, lp())
    }

    // ---------- Ações ----------

    private fun capturar() {
        fechar()
        // Espera o menu sumir para não sair na captura.
        ui.postDelayed({
            trabalho.execute {
                acoes.capturar(nomeJogo ?: "tela") { ok ->
                    ui.post { Toast.makeText(ctx, if (ok) "Captura salva em Imagens › GameLauncherTV" else "Não foi possível capturar", Toast.LENGTH_SHORT).show() }
                }
            }
        }, 350)
    }

    private fun linhaGravar(): LinearLayout = if (RecursosRoot.gravando()) {
        val seg = (System.currentTimeMillis() - RecursosRoot.gravandoDesde) / 1000
        linha("Parar a gravação", "Gravando há %d:%02d".format(seg / 60, seg % 60)) {
            fechar()
            trabalho.execute {
                val f = RecursosRoot.pararGravacao()
                if (f != null) MediaScannerConnection.scanFile(ctx, arrayOf(f), arrayOf("video/mp4"), null)
                ui.post { Toast.makeText(ctx, if (f != null) "Vídeo salvo em Vídeos › GameLauncherTV" else "Nada gravado", Toast.LENGTH_SHORT).show() }
            }
        }
    } else linha("Gravar a tela", "Até 3 minutos, em Vídeos › GameLauncherTV. Abra o menu de novo para parar") {
        fechar()
        ui.postDelayed({
            trabalho.execute {
                val ok = RecursosRoot.iniciarGravacao(nomeJogo ?: "tela")
                ui.post { Toast.makeText(ctx, if (ok) "Gravando… (segure PS e escolha Parar)" else "Não foi possível gravar", Toast.LENGTH_SHORT).show() }
            }
        }, 350)
    }

    private fun reaplicarCarga() = trabalho.execute {
        RecursosRoot.soltarCarga(ctx)
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val nivel = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (nivel >= 0) RecursosRoot.aplicarCarga(ctx, nivel, (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private fun memoriaLivre(): Long = ActivityManager.MemoryInfo().also {
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.availMem

    private fun gb(b: Long) = "%.1f GB".format(b / (1L shl 30).toDouble())

    /** "CPU 62 °C · GPU 55 °C · Bateria 34 °C · Memória livre 3,1 GB" (a temperatura vem com root, depois). */
    private fun carregarInfoSistema(extra: TextView? = null) {
        val bateriaTemp = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.div(10)
        fun texto(cpu: Int?, gpu: Int?) = listOfNotNull(cpu?.let { "CPU $it °C" }, gpu?.let { "GPU $it °C" },
            bateriaTemp?.let { "Bateria $it °C" }, "Memória livre ${gb(memoriaLivre())}").joinToString("  ·  ")
        infoSistema?.text = texto(null, null)
        if (!root) {
            // Sem root não dá para ler as zonas térmicas: o Android diz só se está esquentando.
            val calor = when (ctx.getSystemService(android.os.PowerManager::class.java).currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE, android.os.PowerManager.THERMAL_STATUS_LIGHT -> null
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Aquecido"
                else -> "Muito quente (o desempenho cai)"
            }
            val t = listOfNotNull(calor, texto(null, null)).joinToString("  ·  ")
            infoSistema?.text = t
            extra?.text = t
            return
        }
        trabalho.execute {
            val (cpu, gpu) = RecursosRoot.temperaturas()
            ui.post {
                infoSistema?.text = texto(cpu, gpu)
                extra?.text = texto(cpu, gpu)
            }
        }
    }

    /** "🎮 Controle 80%  ·  📱 Celular 77% (carregando)  ·  13:20". */
    private fun resumo(): String {
        val partes = ArrayList<String>()
        // O primeiro que informa bateria (um controle remoto USB também conta como "controle", mas não tem).
        Controle.conectados().firstNotNullOfOrNull { d -> d.batteryState.takeIf { it.isPresent } }?.let { b ->
            val n = b.capacity.takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
            if (n != null) partes += "🎮 Controle $n%" + if (b.status == android.hardware.BatteryState.STATUS_CHARGING) " ⚡" else ""
        }
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        partes += "📱 Celular $nivel%" + when {
            prefs.getBoolean("carga_pausada", false) -> " (na tomada, sem carregar)"
            bm.isCharging -> " (carregando)"
            else -> ""
        }
        partes += java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        return partes.joinToString("  ·  ")
    }

    // ---------- Visual (o mesmo das configurações) ----------

    private fun lp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) }

    private fun nota(t: String) = texto(19f, Paleta.TEXTO_SUAVE, 400).apply {
        text = t
        setLineSpacing(0f, 1.3f)
        setPadding(px(20f), px(4f), px(20f), px(12f))
    }

    /** Liga/desliga: o valor aparece embaixo do nome e OK troca. */
    private fun alternar(titulo: String, inicial: Boolean, ajuda: String? = null, mudar: (Boolean) -> Unit): LinearLayout {
        var valor = inicial
        fun sub() = (if (valor) "Ligado" else "Desligado") + (ajuda?.let { " · $it" } ?: "")
        lateinit var l: LinearLayout
        l = linha(titulo, sub()) {
            valor = !valor
            (l.tag as TextView).text = sub()
            mudar(valor)
        }
        return l
    }

    /** Ação perigosa: o primeiro OK pede confirmação, o segundo (em até 5 s) faz. */
    private fun confirmar(titulo: String, aviso: String, fazer: () -> Unit): LinearLayout {
        var pedido = 0L
        lateinit var l: LinearLayout
        l = linha(titulo, aviso) {
            if (System.currentTimeMillis() - pedido < 5000) {
                fechar()
                fazer()
            } else {
                pedido = System.currentTimeMillis()
                (l.tag as TextView).text = "Aperte de novo para confirmar"
                ui.postDelayed({ (l.tag as TextView).text = aviso }, 5000)
            }
        }
        return l
    }

    /** Linha com nome, uma barra e a porcentagem; ◀ ▶ mudam o valor (de [passos] em [passos]). */
    private fun deslizante(
        titulo: String, inicial: Float, passos: Int = 20, rotulo: (Float) -> String = { "${Math.round(it * 100)}%" }, mudar: (Float) -> Unit,
    ): LinearLayout {
        var valor = inicial.coerceIn(0f, 1f)
        val barra = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (valor * 1000).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Paleta.AZUL_CLARO)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
        }
        lateinit var l: LinearLayout
        l = linha(titulo, "◀  ${rotulo(valor)}  ▶", extra = barra) {}
        l.setOnKeyListener { _, codigo, e ->
            val delta = when (codigo) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (e.action == KeyEvent.ACTION_DOWN) {
                valor = (valor + delta / passos.toFloat()).coerceIn(0f, 1f)
                barra.progress = (valor * 1000).toInt()
                (l.tag as TextView).text = "◀  ${rotulo(valor)}  ▶"
                mudar(valor)
            }
            true
        }
        return l
    }

    private fun linha(titulo: String, sub: String?, seta: Boolean = false, extra: View? = null, acao: () -> Unit): LinearLayout {
        val nome = texto(26f, Color.WHITE, 500).apply {
            text = titulo
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val detalhe = texto(20f, Paleta.TEXTO_SUAVE, 400).apply {
            text = sub.orEmpty()
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            setLineSpacing(0f, 1.2f)
        }
        val textos = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(nome)
            addView(detalhe, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            extra?.let { addView(it, LinearLayout.LayoutParams(-1, px(10f)).apply { topMargin = px(10f) }) }
        }
        val marca = texto(32f, Paleta.TEXTO_SUAVE, 400).apply {
            text = if (seta) "›" else ""
            gravity = Gravity.CENTER
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            tag = detalhe
            setPadding(px(20f), px(16f), px(16f), px(16f))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(marca, LinearLayout.LayoutParams(px(30f), -2))
            val escuro = Visuais.painelEscuro(estiloConsole)
            if (escuro != null) {
                nome.typeface = Ps4Desenho.normal
                background = if (estiloConsole >= 5) SelecaoModerna(wm.currentWindowMetrics.bounds.width() / 1280f, if (estiloConsole == 5) 12f else 4f)
                    else SelecaoBorda(wm.currentWindowMetrics.bounds.width() / 1280f, escuro.third)
                setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
            } else if (xbox) {
                nome.typeface = Ps4Desenho.normal
                background = SelecaoXbox(wm.currentWindowMetrics.bounds.width() / 1280f, escuro = true)
                setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
            } else if (ps4) {
                nome.typeface = Ps4Desenho.normal
                background = SelecaoPs4(wm.currentWindowMetrics.bounds.width() / 1280f, linhaEmbaixo = true)
                setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
            } else {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), retangulo(Color.WHITE))
                    addState(intArrayOf(android.R.attr.state_pressed), retangulo(Color.argb(60, 255, 255, 255)))
                    addState(intArrayOf(), retangulo(Color.TRANSPARENT))
                }
                setOnFocusChangeListener { _, f ->
                    nome.setTextColor(if (f) Paleta.TEXTO_ESCURO else Color.WHITE)
                    detalhe.setTextColor(if (f) Color.argb(200, 32, 33, 36) else Paleta.TEXTO_SUAVE)
                    marca.setTextColor(if (f) Paleta.TEXTO_ESCURO else Paleta.TEXTO_SUAVE)
                }
            }
            setOnClickListener { acao() }
        }
    }

    private fun primeiroFocavel(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) primeiroFocavel(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Paleta.fonte(peso)
        includeFontPadding = false
    }

    private fun retangulo(cor: Int) = GradientDrawable().apply {
        cornerRadius = 6f * s
        setColor(cor)
    }

    private fun px(ref: Float) = (ref * s).toInt()
}
