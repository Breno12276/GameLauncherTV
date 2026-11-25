package com.gamelaunchertv.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.widget.Toast
import java.util.concurrent.ExecutorService

/**
 * O que o menu rápido e o botão PS pedem ao sistema. Duas versões: com root ([ServicoRoot]: fecha qualquer app,
 * troca de tarefa, mexe em Wi-Fi, carga…) e sem root ([ServicoAcessibilidade]: o que o Android deixa um serviço
 * de acessibilidade fazer).
 */
interface AcoesSistema {
    val comRoot: Boolean
    /** Pacote do app na frente agora. */
    fun appNaFrente(): String?
    /** Vai para o GameLauncherTV (não para a tela inicial padrão, que pode ser outro launcher). */
    fun irParaInicio()
    /** Traz de volta o app [pacote] como ele estava; false = não deu. */
    fun voltarPara(pacote: String): Boolean
    fun fecharApp(pacote: String)
    /** Apps abertos (com root) ou usados há pouco (sem root), do mais recente ao mais antigo. */
    fun abertos(): List<String>
    /** Captura a tela em Imagens › GameLauncherTV; [pronto] recebe true se salvou. */
    fun capturar(nome: String, pronto: (Boolean) -> Unit)
    fun desligarTela()
    /** Sem root: o menu de energia do sistema (reiniciar / desligar). Com root, o menu tem os próprios. */
    fun menuEnergia()
    /** Modo mouse no app [pacote] (precisa do serviço de acessibilidade): true = ligou, false = desligou, null = serviço desligado. */
    fun alternarMouse(pacote: String): Boolean? = ServicoAcessibilidade.instancia?.mouse?.alternar(pacote)
}

/**
 * Botão PS (e PS + Share) por cima de qualquer app, como no PS4:
 * - segurar PS abre (ou fecha) o menu rápido;
 * - PS curto: com o menu aberto, fecha; num jogo, guarda qual é e vai para o launcher; no launcher, volta ao jogo;
 * - PS + Share fecha o jogo na frente.
 * Quem lê o controle (root ou acessibilidade) chama [tratar] a cada apertar/soltar.
 */
class BotaoPs(
    private val ctx: Context, private val acoes: AcoesSistema, private val trabalho: ExecutorService, private val ui: Handler,
) {
    private val prefs = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
    private var menu: MenuRapido? = null
    private var psDesde = 0L
    private var psUsado = false
    private val SEGURAR_MS = 600L
    private val DUPLO_MS = 350L

    /** Toque curto esperando para ver se vem o segundo (dois toques = abrir o último jogo). */
    private val toqueSimples = Runnable { trabalho.execute { alternarJogoInicio() } }
    private var ultimoToque = 0L

    private fun duploLigado() = prefs.getBoolean("ps_duplo", true)

    /** "Botão PS" (preferência "botao_ps"): 0 = como no PS4, 1 = só segurar abre o menu, 2 = desligado. */
    private fun modoPs() = prefs.getInt("botao_ps", 0)

    private val segurouPs = Runnable {
        if (psDesde != 0L && !psUsado) {
            psUsado = true
            if (modoPs() != 2) alternarMenu()
        }
    }

    /** PS está apertado agora (para quem precisa saber se o Share faz parte do atalho). */
    fun psApertado() = psDesde != 0L

    /** [botao]: "PS" ou "SHARE"; [desceu]: apertou (true) ou soltou (false). Chamar na thread da tela. */
    fun tratar(botao: String, desceu: Boolean) {
        when (botao) {
            "PS" -> if (desceu) {
                psDesde = System.currentTimeMillis()
                psUsado = false
                ui.postDelayed(segurouPs, SEGURAR_MS)
            } else {
                ui.removeCallbacks(segurouPs)
                val curto = !psUsado && psDesde != 0L && System.currentTimeMillis() - psDesde < SEGURAR_MS
                psDesde = 0L
                if (curto) {
                    val agora = System.currentTimeMillis()
                    when {
                        menu != null -> menu?.fechar()
                        modoPs() != 0 -> {}
                        !duploLigado() -> trabalho.execute { alternarJogoInicio() }
                        agora - ultimoToque < DUPLO_MS -> {
                            ui.removeCallbacks(toqueSimples)
                            ultimoToque = 0L
                            trabalho.execute { abrirUltimoJogo() }
                        }
                        else -> {
                            ultimoToque = agora
                            ui.postDelayed(toqueSimples, DUPLO_MS)
                        }
                    }
                }
            }
            // PS + L1: captura de tela do que estiver na frente.
            "L1" -> if (desceu && psDesde != 0L && !psUsado) {
                psUsado = true
                ui.removeCallbacks(segurouPs)
                if (prefs.getBoolean("ps_l1_captura", true)) trabalho.execute {
                    val app = acoes.appNaFrente()
                    val nome = app?.let { p ->
                        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }
                    } ?: "captura"
                    acoes.capturar(nome) { ok ->
                        ui.post { Toast.makeText(ctx, if (ok) "Captura salva em Imagens › GameLauncherTV" else "Não deu para capturar a tela", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            "SHARE" -> if (desceu && psDesde != 0L && !psUsado) {
                psUsado = true
                ui.removeCallbacks(segurouPs)
                if (prefs.getBoolean("ps_share_fecha", true)) trabalho.execute {
                    val app = acoes.appNaFrente()
                    if (app != null && !ehInicio(app)) {
                        acoes.fecharApp(app)
                        acoes.irParaInicio()
                    }
                }
            }
        }
    }

    fun fecharMenu() = menu?.fechar()

    /** O launcher ou outra tela inicial instalada (o NovaHome antigo, o One UI Home…). */
    fun ehInicio(pacote: String): Boolean = pacote == ctx.packageName || pacote in ctx.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.ResolveInfoFlags.of(0),
    ).map { it.activityInfo.packageName }

    private fun alternarJogoInicio() {
        val frente = acoes.appNaFrente() ?: return
        if (ehInicio(frente)) {
            val ultimo = prefs.getString("ultimo_jogo", null)
            val ok = ultimo != null && acoes.voltarPara(ultimo)
            if (!ok) ui.post { Toast.makeText(ctx, "Nenhum jogo aberto para voltar", Toast.LENGTH_SHORT).show() }
        } else {
            prefs.edit().putString("ultimo_jogo", frente).apply()
            acoes.irParaInicio()
        }
    }

    /** Dois toques no PS: abre o último jogo jogado (outro que não o da frente), pelo launcher. */
    private fun abrirUltimoJogo() {
        val frente = acoes.appNaFrente()
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ULTIMO_JOGO, true).putExtra(MainActivity.EXTRA_PACOTE_FRENTE, frente))
    }

    private fun alternarMenu() {
        menu?.let { it.fechar(); return }
        trabalho.execute {
            val app = acoes.appNaFrente()
            ui.post {
                menu = MenuRapido(ctx, app, trabalho, acoes) { menu = null }.also { if (!it.mostrar()) menu = null }
            }
        }
    }
}
