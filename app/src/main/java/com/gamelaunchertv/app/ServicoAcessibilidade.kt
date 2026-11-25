package com.gamelaunchertv.app

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Botão PS e menu rápido SEM root, por um serviço de acessibilidade (a pessoa liga uma vez em Acessibilidade).
 * O Android entrega a ele os botões de qualquer app (o PS e o Share somem para o jogo, o resto passa), avisa qual
 * app está na frente e deixa desenhar o menu por cima de tudo, capturar a tela, bloquear a tela e abrir o menu de
 * energia. Com os recursos root ligados, quem cuida do controle é o [ServicoRoot] (este fica quieto).
 */
class ServicoAcessibilidade : AccessibilityService() {

    companion object {
        /** O serviço está ligado e rodando (para as configurações mostrarem o estado). */
        @Volatile var ativo = false
            private set
        /** O serviço rodando (o modo mouse e o "Dormir" sem root usam). */
        @Volatile var instancia: ServicoAcessibilidade? = null
            private set
    }

    val mouse by lazy { ModoMouse(this, ui) }

    /** Perfil infantil: acabou o tempo de jogo de hoje com um jogo aberto pelo launcher na frente → volta ao launcher. */
    private val conferirLimite = object : Runnable {
        override fun run() {
            val resta = Perfis.restanteMin(this@ServicoAcessibilidade)
            val emJogo = getSharedPreferences("historico", MODE_PRIVATE).getString("sessao_id", null) != null
            val frente = naFrente
            if (resta != null && resta <= 0 && emJogo && frente != null && frente != packageName && !botao.ehInicio(frente)) {
                android.widget.Toast.makeText(this@ServicoAcessibilidade, "O tempo de jogo de hoje acabou", android.widget.Toast.LENGTH_LONG).show()
                acoes.irParaInicio()
            }
            ui.postDelayed(this, 60_000)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    private var naFrente: String? = null
    /** Apps usados há pouco (sem root não dá para ver as tarefas abertas), do mais recente ao mais antigo. */
    private val recentes = ArrayList<String>()

    private val acoes = object : AcoesSistema {
        override val comRoot = false
        override fun appNaFrente() = naFrente

        override fun irParaInicio() {
            ui.post {
                try {
                    startActivity(Intent(this@ServicoAcessibilidade, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                } catch (_: Exception) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }

        /** Abrir o app pelo ícone traz a tarefa que já estava aberta, do jeito que ficou. */
        override fun voltarPara(pacote: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pacote) ?: return false
            ui.post {
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                } catch (_: Exception) {
                }
            }
            return true
        }

        /** Sem root só dá para encerrar apps em segundo plano: vai para o início e encerra o jogo em seguida. */
        override fun fecharApp(pacote: String) {
            irParaInicio()
            ui.postDelayed({
                try { getSystemService(ActivityManager::class.java).killBackgroundProcesses(pacote) } catch (_: Exception) {}
            }, 800)
        }

        override fun abertos() = synchronized(recentes) { recentes.toList() }

        override fun capturar(nome: String, pronto: (Boolean) -> Unit) {
            takeScreenshot(Display.DEFAULT_DISPLAY, trabalho, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    pronto(bmp != null && salvarImagem(bmp, nome))
                }
                override fun onFailure(codigo: Int) = pronto(false)
            })
        }

        override fun desligarTela() { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
        override fun menuEnergia() { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }
    }

    private val botao by lazy { BotaoPs(this, acoes, trabalho, ui) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ativo = true
        instancia = this
        ui.postDelayed(conferirLimite, 60_000)
    }

    override fun onDestroy() {
        ativo = false
        instancia = null
        ui.removeCallbacks(conferirLimite)
        botao.fecharMenu()
        trabalho.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /** Guarda o app na frente (ignora o teclado, a barra do sistema e o próprio menu). */
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == "com.android.systemui" || p.contains("inputmethod") || p.contains("honeyboard")) return
        // O menu rápido é deste app, mas o launcher também: só conta como "na frente" quando é uma tela cheia nossa.
        if (p == packageName && e.className?.toString()?.endsWith("MainActivity") != true) return
        naFrente = p
        mouse.appMudou(p)
        if (p != packageName && !botao.ehInicio(p)) synchronized(recentes) {
            recentes.remove(p)
            recentes.add(0, p)
            while (recentes.size > 12) recentes.removeAt(recentes.lastIndex)
        }
    }

    /**
     * Botões de qualquer app. PS e PS + Share são do launcher (o jogo não recebe); o resto passa direto.
     * Repetições da tecla segurada são ignoradas (o tempo do "segurar" é contado aqui).
     */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        // Com root, o ServicoRoot lê o PS direto; o modo mouse e os botões mapeados valem nos dois casos.
        if (Root.ligado(this)) return mouse.tecla(e)
        val qual = when {
            e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE -> "PS"
            e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && botao.psApertado() -> "SHARE"
            e.keyCode == KeyEvent.KEYCODE_BUTTON_L1 && botao.psApertado() -> "L1"
            else -> return mouse.tecla(e)
        }
        if (e.repeatCount == 0 && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP)) {
            val desceu = e.action == KeyEvent.ACTION_DOWN
            ui.post { botao.tratar(qual, desceu) }
        }
        return true
    }

    /** Salva a captura em Imagens › GameLauncherTV (MediaStore: não precisa de permissão de arquivos). */
    private fun salvarImagem(bmp: Bitmap, nome: String): Boolean = try {
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${nome.replace(Regex("[^\\w-]+"), "_").take(40)}_${System.currentTimeMillis() / 1000}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GameLauncherTV")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
        uri != null && contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
    } catch (_: Exception) {
        false
    }
}
