package com.gamelaunchertv.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * O que o launcher faz com root. Cada parte só mexe no sistema quando a pessoa liga a opção, e guarda o valor
 * anterior para desfazer ao desligar (ou ao voltar do jogo, no caso do modo desempenho).
 */
object RecursosRoot {

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    // ---------- Carregamento ----------

    /** Modos de "Carregamento" (preferência "carga_modo"). */
    val MODOS_CARGA = listOf("Normal", "Limitar a carga", "Direto da tomada (bypass)")
    val LIMITES = listOf(60, 70, 80, 90)

    /**
     * Interruptores de carga conhecidos, na ordem de preferência: (arquivo, valor que pausa, valor que volta).
     * - pass_through (Samsung, o "Pausar carregamento USB PD" do Game Booster): o aparelho roda direto da tomada,
     *   sem passar pela bateria (precisa de carregador PD/PPS, de 25 W ou mais).
     * - batt_slate_mode (Samsung): pausa a carga; o carregador continua alimentando o aparelho.
     * - charging_enabled / input_suspend / battery_charging_enabled: outros fabricantes. input_suspend corta a
     *   entrada inteira (gasta bateria), então só serve para limitar, não para bypass.
     */
    private class Interruptor(val arquivo: String, val pausa: String, val volta: String, val bypass: Boolean)

    private val INTERRUPTORES = listOf(
        Interruptor("/sys/class/power_supply/battery/pass_through", "1", "0", bypass = true),
        Interruptor("/sys/class/power_supply/battery/batt_slate_mode", "1", "0", bypass = true),
        Interruptor("/sys/class/power_supply/battery/charging_enabled", "0", "1", bypass = true),
        Interruptor("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1", bypass = true),
        Interruptor("/sys/class/power_supply/battery/input_suspend", "1", "0", bypass = false),
    )

    private var interruptores: List<Interruptor>? = null

    /** Interruptores que existem neste aparelho (lidos uma vez, com root). */
    private fun disponiveis(): List<Interruptor> {
        interruptores?.let { return it }
        val r = Root.rodar(INTERRUPTORES.joinToString("; ") { "[ -w ${it.arquivo} ] && echo ${it.arquivo}" })
        if (!r.ok && r.saida.isEmpty()) return emptyList()
        val achados = r.saida.lines().map { it.trim() }.toSet()
        return INTERRUPTORES.filter { it.arquivo in achados }.also { interruptores = it }
    }

    /** Nome curto do interruptor usado, para mostrar nas configurações ("pass_through + batt_slate_mode"). */
    fun interruptorCarga(): String? = disponiveis().takeIf { it.isNotEmpty() }?.joinToString(" + ") { File(it.arquivo).name }

    private var pausado: Boolean? = null

    /**
     * Pausa ou volta a carga. Com bypass, usa os interruptores que mantêm o aparelho na tomada (pass_through e
     * slate juntos: se o carregador não for PD, o slate ainda segura a carga); para limitar, qualquer um serve.
     */
    private fun pausar(ctx: Context, sim: Boolean, bypass: Boolean) {
        if (pausado == sim) return
        val lista = disponiveis().filter { !bypass || it.bypass }.ifEmpty { return }
        val slate = lista.filter { it.arquivo.endsWith("batt_slate_mode") }
        // Pausar: no bypass, o primeiro (pass_through) junto com o slate; para limitar, a pausa simples (slate) basta.
        // Voltar a carga libera todos (algum pode ter ficado pausado por um modo anterior).
        val alvo = when {
            !sim -> disponiveis()
            bypass -> (listOf(lista.first()) + slate).distinct()
            else -> slate.ifEmpty { listOf(lista.first()) }
        }
        Root.rodar(alvo.joinToString("; ") { "echo ${if (sim) it.pausa else it.volta} > ${it.arquivo}" })
        pausado = sim
        prefs(ctx).edit().putBoolean("carga_pausada", sim).apply()
    }

    /**
     * Regra de carga a cada mudança da bateria ([nivel] em %, [naTomada]).
     * - Limitar: para no limite e volta 10 pontos abaixo (não fica ligando e desligando).
     * - Bypass: na tomada, não carrega; abaixo de 20% carrega até 30% para não ficar sem reserva.
     */
    fun aplicarCarga(ctx: Context, nivel: Int, naTomada: Boolean) {
        val p = prefs(ctx)
        val modo = p.getInt("carga_modo", 0)
        if (!Root.ligado(ctx) || modo == 0) {
            if (pausado == true || p.getBoolean("carga_pausada", false)) { pausado = null; pausar(ctx, false, false) }
            return
        }
        val limite = LIMITES.getOrElse(p.getInt("carga_limite", 2)) { 80 }
        val agora = pausado ?: p.getBoolean("carga_pausada", false)
        val pausar = when (modo) {
            1 -> if (agora) nivel > limite - 10 else nivel >= limite
            else -> if (agora) nivel > 20 else nivel >= 30
        }
        // Fora da tomada não há o que pausar: libera (na volta à tomada a regra decide de novo).
        pausar(ctx, pausar && naTomada, bypass = modo == 2)
    }

    /** Desliga tudo da carga (ao desligar a opção ou os recursos root). */
    fun soltarCarga(ctx: Context) {
        pausado = null
        if (disponiveis().isNotEmpty()) pausar(ctx, false, false)
    }

    // ---------- Modo desempenho ao abrir um jogo ----------

    val TAXAS = listOf("Não mudar", "60 Hz", "120 Hz")

    /**
     * Antes de abrir o jogo de [pacote]: fecha apps em segundo plano, libera memória, liga o modo de desempenho
     * fixo do Android e o modo "desempenho" do Game Mode para o jogo, trava a taxa da tela e, se escolhido,
     * silencia as notificações. Os valores anteriores ficam guardados para [fimDoJogo].
     * Roda os comandos na hora (chamar fora da tela principal e abrir o jogo depois: o "am kill-all" não pode
     * pegar o jogo começando).
     */
    fun desempenhoLigado(ctx: Context) = Root.ligado(ctx) && prefs(ctx).getBoolean("desempenho_jogo", false)

    fun inicioDoJogo(ctx: Context, pacote: String) {
        val p = prefs(ctx)
        if (!desempenhoLigado(ctx)) return
        val cmds = ArrayList<String>()
        cmds += "am kill-all"
        cmds += "sync; echo 3 > /proc/sys/vm/drop_caches"
        cmds += "cmd power set-fixed-performance-mode-enabled true"
        cmds += "cmd game mode performance ${Root.aspas(pacote)}"
        val taxa = p.getInt("taxa_jogo", 0)
        if (taxa > 0 && !p.contains("taxa_antes")) {
            // Guarda "pico|mínima|modo Samsung" de antes para desfazer.
            val antes = Root.rodar("settings get system peak_refresh_rate; settings get system min_refresh_rate; settings get secure refresh_rate_mode").saida
            p.edit().putString("taxa_antes", antes.lines().joinToString("|")).apply()
            val hz = if (taxa == 1) "60.0" else "120.0"
            cmds += "settings put system peak_refresh_rate $hz; settings put system min_refresh_rate $hz"
            // One UI: 0 = padrão (60 Hz), 1 = adaptável (até 120 Hz).
            cmds += "settings put secure refresh_rate_mode ${if (taxa == 1) 0 else 1}"
        }
        if (p.getBoolean("silenciar_jogo", false)) cmds += "cmd notification set_dnd priority"
        p.edit().putString("jogo_em_curso", pacote).apply()
        cmds.forEach { Root.rodar(it, 20_000) }
    }

    /** Voltou ao launcher: desfaz o que [inicioDoJogo] mudou (chamar fora da tela principal). */
    fun fimDoJogo(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains("jogo_em_curso")) return
        val cmds = ArrayList<String>()
        cmds += "cmd power set-fixed-performance-mode-enabled false"
        p.getString("taxa_antes", null)?.let { antes ->
            val (pico, minima, modo) = (antes.split('|') + listOf("null", "null", "null")).take(3)
            fun volta(tabela: String, chave: String, v: String) =
                if (v == "null" || v.isBlank()) "settings delete $tabela $chave" else "settings put $tabela $chave $v"
            cmds += volta("system", "peak_refresh_rate", pico)
            cmds += volta("system", "min_refresh_rate", minima)
            cmds += volta("secure", "refresh_rate_mode", modo)
        }
        if (p.getBoolean("silenciar_jogo", false)) cmds += "cmd notification set_dnd off"
        p.edit().remove("jogo_em_curso").remove("taxa_antes").apply()
        cmds.forEach { Root.rodar(it, 20_000) }
    }

    // ---------- Resolução e densidade da tela ----------

    class Tela(val fisicaL: Int, val fisicaA: Int, val atualL: Int, val atualA: Int, val densFisica: Int, val densAtual: Int)

    /** Lê "wm size" e "wm density" (física e a trocada, se houver). */
    fun tela(): Tela? {
        val r = Root.rodar("wm size; wm density")
        if (!r.ok) return null
        fun tam(rotulo: String) = Regex("$rotulo size: (\\d+)x(\\d+)").find(r.saida)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
        fun dens(rotulo: String) = Regex("$rotulo density: (\\d+)").find(r.saida)?.groupValues?.get(1)?.toInt()
        val fisica = tam("Physical") ?: return null
        val atual = tam("Override") ?: fisica
        val df = dens("Physical") ?: return null
        return Tela(fisica.first, fisica.second, atual.first, atual.second, df, dens("Override") ?: df)
    }

    /**
     * Uma resolução: [curto] × [longo] em pixels (0 × 0 = a original). As de TV são 16:9, como o Second Screen faz
     * para a imagem preencher a TV; a última segue a proporção do celular.
     */
    class Resolucao(val curto: Int, val longo: Int, val rotulo: String)

    val RESOLUCOES = listOf(
        Resolucao(0, 0, "Original do aparelho"),
        Resolucao(1080, 1920, "TV 1080p (1920×1080)"),
        Resolucao(900, 1600, "TV 900p (1600×900)"),
        Resolucao(720, 1280, "TV 720p (1280×720)"),
        Resolucao(540, 960, "TV 540p (960×540)"),
        Resolucao(720, 1560, "Celular 720p (1560×720)"),
    )
    val DENSIDADES = listOf(0 to "Original do aparelho", 480 to "480", 400 to "400", 320 to "320", 280 to "280", 240 to "240", 200 to "200")

    /**
     * "Modo TV" de um toque, como o perfil de TV do Second Screen: 1280×720 com densidade 240 (tudo no tamanho
     * de uma TV, leve para os emuladores).
     */
    val MODO_TV = Resolucao(720, 1280, "TV 720p") to 240

    /** Aplica a resolução [r] (null = não mexe) e a [densidade] (0 = original, null = não mexe). */
    fun definirResolucao(r: Resolucao?, densidade: Int?): Boolean {
        val cmds = ArrayList<String>()
        // O "wm size" é em retrato (lado curto primeiro), como a tela física do celular.
        if (r != null) cmds += if (r.curto == 0) "wm size reset" else "wm size ${r.curto}x${r.longo}"
        if (densidade != null) cmds += if (densidade == 0) "wm density reset" else "wm density $densidade"
        return cmds.isEmpty() || Root.rodar(cmds.joinToString("; ")).ok
    }

    /** Volta exatamente para um tamanho e densidade lidos antes (o "Desfazer" depois de trocar). */
    fun voltarTela(t: Tela) {
        val tamanho = if (t.atualL == t.fisicaL && t.atualA == t.fisicaA) "wm size reset" else "wm size ${t.atualL}x${t.atualA}"
        val dens = if (t.densAtual == t.densFisica) "wm density reset" else "wm density ${t.densAtual}"
        Root.rodar("$tamanho; $dens")
    }

    // ---------- Barra de navegação e tela inicial ----------

    /**
     * Esconde a barra de navegação: navegação por gestos sem a barrinha (One UI) / sem a dica de gestos (Android).
     * O modo de antes fica guardado em "nav_antes" para voltar.
     */
    fun esconderNavegacao(ctx: Context, sim: Boolean) {
        val p = prefs(ctx)
        if (sim) {
            if (!p.contains("nav_antes")) {
                val antes = Root.rodar("settings get global navigation_bar_gesture_while_hidden; settings get global navigation_bar_gesture_hint; " +
                    "cmd overlay list android | grep -i navbar | grep '\\[x\\]'").saida
                p.edit().putString("nav_antes", antes.lines().joinToString("|")).apply()
            }
            Root.rodar("settings put global navigation_bar_gesture_while_hidden 1; settings put global navigation_bar_gesture_hint 0; " +
                "cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
        } else {
            val antes = p.getString("nav_antes", null)?.split('|').orEmpty()
            fun volta(chave: String, v: String?) =
                if (v == null || v == "null" || v.isBlank()) "settings delete global $chave" else "settings put global $chave $v"
            val cmds = mutableListOf(volta("navigation_bar_gesture_while_hidden", antes.getOrNull(0)),
                volta("navigation_bar_gesture_hint", antes.getOrNull(1)))
            // Volta o modo de navegação que estava marcado ([x] nome.do.overlay).
            antes.drop(2).firstNotNullOfOrNull { Regex("\\[x\\]\\s+(\\S+)").find(it)?.groupValues?.get(1) }
                ?.let { cmds += "cmd overlay enable-exclusive --category $it" }
            Root.rodar(cmds.joinToString("; "))
            p.edit().remove("nav_antes").apply()
        }
    }

    /** Fixa o launcher como tela inicial (o botão Início sempre volta para ele, sem perguntar). */
    fun travarInicio(ctx: Context): Boolean =
        Root.rodar("cmd package set-home-activity ${ctx.packageName}/${MainActivity::class.java.name}").ok

    // ---------- Menu rápido ----------

    /** App em primeiro plano agora (pacote), pelo dumpsys. */
    fun appNaFrente(): String? {
        val r = Root.rodar("dumpsys activity activities | grep -m1 topResumedActivity")
        return Regex("u\\d+ ([\\w.]+)/").find(r.saida)?.groupValues?.get(1)
    }

    /** Uma tarefa aberta (o que aparece nos recentes): id e pacote. */
    class Tarefa(val id: Int, val pacote: String)

    /**
     * Apps abertos, do mais recente ao mais antigo, sem telas iniciais, sem o próprio launcher e sem a tela de
     * recentes ("Recent #1: Task{… #355 type=standard A=10295:xyz.aethersx2.android}").
     */
    fun tarefas(ctx: Context): List<Tarefa> {
        val r = Root.rodar("dumpsys activity recents | grep 'Recent #'")
        return r.saida.lines().mapNotNull { l ->
            val m = Regex("#(\\d+) type=standard A=\\d+:([\\w.]+)").find(l) ?: return@mapNotNull null
            Tarefa(m.groupValues[1].toInt(), m.groupValues[2])
        }.filter { it.pacote != ctx.packageName }.distinctBy { it.pacote }
    }

    /** Traz de volta o app [pacote] como ele estava (a tarefa aberta, não uma tela nova). false = não está aberto. */
    fun voltarPara(ctx: Context, pacote: String): Boolean {
        val t = tarefas(ctx).firstOrNull { it.pacote == pacote } ?: return false
        return try {
            ctx.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(t.id, 0)
            true
        } catch (_: Exception) {
            // Sem a permissão de reordenar: o Android traz a tarefa existente ao abrir o app pelo ícone.
            Root.rodar("monkey -p ${Root.aspas(pacote)} -c android.intent.category.LAUNCHER 1").ok
        }
    }

    /** Vai para o GameLauncherTV (não para a tela inicial padrão, que pode ser outro launcher). */
    fun irParaInicio(ctx: Context) {
        Root.rodar("am start -n ${ctx.packageName}/${MainActivity::class.java.name} -a android.intent.action.MAIN -c android.intent.category.HOME")
    }

    /** "Conectado a Sitio_5G · sinal ótimo · 192.168.1.106" (ou "Desligado" / "Ligado, sem rede"). */
    fun estadoWifi(): String {
        val r = Root.rodar("cmd wifi status").saida
        if ("Wifi is disabled" in r) return "Desligado"
        val rede = Regex("connected to \"([^\"]*)\"").find(r)?.groupValues?.get(1) ?: return "Ligado, sem rede conectada"
        val rssi = Regex("RSSI: (-?\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull()
        val sinal = when {
            rssi == null -> null
            rssi >= -55 -> "sinal ótimo"
            rssi >= -67 -> "sinal bom"
            rssi >= -75 -> "sinal fraco"
            else -> "sinal muito fraco"
        }
        val ip = Regex("IP: /([\\d.]+)").find(r)?.groupValues?.get(1)
        return listOfNotNull("Conectado a $rede", sinal, ip).joinToString(" · ")
    }

    fun fecharApp(pacote: String) = Root.rodar("am force-stop ${Root.aspas(pacote)}").ok

    /** Brilho da tela de 0 a 1 (desliga o brilho automático para valer o escolhido). */
    fun definirBrilho(fracao: Float) {
        val v = (fracao.coerceIn(0.02f, 1f) * 255).toInt()
        Root.rodar("settings put system screen_brightness_mode 0; settings put system screen_brightness $v")
    }

    /** Temperatura (°C) mais alta da CPU e da GPU, pelas zonas térmicas do kernel; null se o aparelho não mostra. */
    fun temperaturas(): Pair<Int?, Int?> {
        val r = Root.rodar("for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type)=\$(cat \$z/temp)\"; done 2>/dev/null")
        val zonas = r.saida.lines().mapNotNull { l ->
            val (tipo, v) = l.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (v.trim().toIntOrNull() ?: return@mapNotNull null).let { tipo.trim().lowercase() to (if (it > 1000) it / 1000 else it) }
        }.filter { it.second in 1..150 }
        fun maior(vararg prefixos: String) = zonas.filter { (t, _) -> prefixos.any { t.startsWith(it) } }.maxOfOrNull { it.second }
        return maior("cpu", "big", "mid", "little") to maior("gpu", "g3d")
    }

    /** Fecha apps em segundo plano e limpa o cache de arquivos do sistema (o mesmo do modo desempenho). */
    fun liberarMemoria() = Root.rodar("am kill-all; sync; echo 3 > /proc/sys/vm/drop_caches").ok

    fun desempenhoFixo(sim: Boolean) = Root.rodar("cmd power set-fixed-performance-mode-enabled $sim").ok

    fun brilhoAutomatico(sim: Boolean) = Root.rodar("settings put system screen_brightness_mode ${if (sim) 1 else 0}").ok

    /** Taxa da tela (One UI: 0 = padrão 60 Hz, 1 = adaptável até 120 Hz). */
    fun taxaAlta(sim: Boolean) = Root.rodar("settings put secure refresh_rate_mode ${if (sim) 1 else 0}; " +
        "settings put system peak_refresh_rate ${if (sim) "120.0" else "60.0"}").ok

    fun taxaAltaAgora() = Root.rodar("settings get secure refresh_rate_mode").saida.trim() != "0"

    fun wifi(sim: Boolean) = Root.rodar("svc wifi ${if (sim) "enable" else "disable"}").ok
    fun bluetooth(sim: Boolean) = Root.rodar("svc bluetooth ${if (sim) "enable" else "disable"}").ok
    fun modoAviao(sim: Boolean) = Root.rodar("cmd connectivity airplane-mode ${if (sim) "enable" else "disable"}").ok
    fun naoPerturbe(sim: Boolean) = Root.rodar("cmd notification set_dnd ${if (sim) "priority" else "off"}").ok

    fun desligarTela() = Root.rodar("input keyevent 26")
    fun reiniciar() = Root.rodar("svc power reboot")
    fun desligar() = Root.rodar("svc power shutdown")

    // ---------- Gravação de tela (até 3 minutos, o limite do screenrecord) ----------

    private var gravacao: Process? = null
    private var arquivoGravacao: String? = null
    var gravandoDesde = 0L
        private set

    fun gravando() = gravacao?.isAlive == true

    /** Começa a gravar a tela em Vídeos/GameLauncherTV (para sozinho em 3 minutos). */
    fun iniciarGravacao(nome: String): Boolean {
        if (gravando()) return true
        val pasta = "/sdcard/Movies/GameLauncherTV"
        Root.rodar("mkdir -p $pasta")
        val arquivo = "$pasta/${nome.replace(Regex("[^\\w-]+"), "_").take(40)}_${System.currentTimeMillis() / 1000}.mp4"
        gravacao = Root.abrirLeitura("screenrecord --time-limit 180 --bit-rate 12000000 ${Root.aspas(arquivo)}") ?: return false
        arquivoGravacao = arquivo
        gravandoDesde = System.currentTimeMillis()
        return true
    }

    /** Para a gravação (SIGINT: o screenrecord fecha o vídeo direito); devolve o arquivo. */
    fun pararGravacao(): String? {
        if (gravacao == null) return null
        Root.rodar("pkill -INT screenrecord")
        gravacao?.let { Root.esperar(it, 5) }
        gravacao = null
        gravandoDesde = 0L
        return arquivoGravacao
    }

    /** Captura de tela em Imagens/GameLauncherTV; devolve o caminho ou null. */
    fun capturar(nome: String): String? {
        val pasta = "/sdcard/Pictures/GameLauncherTV"
        val arquivo = "$pasta/${nome.replace(Regex("[^\\w-]+"), "_").take(40)}_${System.currentTimeMillis() / 1000}.png"
        return if (Root.rodar("mkdir -p $pasta; screencap -p ${Root.aspas(arquivo)}").ok) arquivo else null
    }
}
