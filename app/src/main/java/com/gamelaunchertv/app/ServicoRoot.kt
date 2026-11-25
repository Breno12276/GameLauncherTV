package com.gamelaunchertv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Serviço dos recursos root, ligado enquanto "Recursos root" estiver ligado (fica de pé mesmo com um jogo na
 * frente, com uma notificação discreta):
 * - Carregamento: a cada mudança da bateria, aplica a regra (limitar / direto da tomada).
 * - Botões do controle por cima de qualquer app (lidos com "getevent", que só observa; o jogo continua recebendo):
 *   PS segurado abre o menu rápido; PS curto vai para o início (como no PS4, se escolhido); PS + Share fecha o jogo.
 */
class ServicoRoot : Service() {

    companion object {
        private const val CANAL = "recursos_root"

        /** Liga ou desliga o serviço conforme a preferência "root_ligado". */
        fun atualizar(ctx: Context) {
            val i = Intent(ctx, ServicoRoot::class.java)
            if (Root.ligado(ctx)) {
                try { ctx.startForegroundService(i) } catch (_: Exception) {}
            } else ctx.stopService(i)
        }

        /** "Botão PS" (preferência "botao_ps"): 0 = como no PS4, 1 = só segurar abre o menu, 2 = desligado. */
        val MODOS_PS = listOf("Como no PS4 (curto: início · segurar: menu)", "Segurar: menu rápido", "Desligado")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newSingleThreadExecutor()
    @Volatile private var ligado = true

    private val bateria = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val naTomada = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (nivel >= 0) trabalho.execute { RecursosRoot.aplicarCarga(this@ServicoRoot, nivel, naTomada) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Recursos root", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Carregamento e botões do controle por cima dos jogos"
            setShowBadge(false)
        })
        val aviso = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("GameLauncherTV")
            .setContentText("Recursos root ativos (segure PS para o menu rápido)")
            .setOngoing(true)
            .build()
        startForeground(1, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(bateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Permissões do menu rápido (sobrepor o jogo, aparelhos Bluetooth): garante a cada início (versões novas pedem mais).
        trabalho.execute {
            Root.rodar("appops set $packageName SYSTEM_ALERT_WINDOW allow; pm grant $packageName android.permission.BLUETOOTH_CONNECT")
        }
        Thread { lerControle() }.apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Root.ligado(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        ligado = false
        acordar()
        try { getSystemService(android.hardware.input.InputManager::class.java).unregisterInputDeviceListener(ouvinteControles) } catch (_: Exception) {}
        try { unregisterReceiver(bateria) } catch (_: Exception) {}
        // Sem o serviço ninguém volta a carga depois: libera agora (nunca deixar o aparelho sem carregar).
        trabalho.execute { RecursosRoot.soltarCarga(this) }
        trabalho.shutdown()
        ui.post { botao.fecharMenu() }
        super.onDestroy()
    }

    // ---------- Botões do controle ----------

    /** Ações com root: fecha qualquer app, troca de tarefa e captura pelo próprio sistema. */
    private val acoes = object : AcoesSistema {
        override val comRoot = true
        override fun appNaFrente() = RecursosRoot.appNaFrente()
        override fun irParaInicio() = RecursosRoot.irParaInicio(this@ServicoRoot)
        override fun voltarPara(pacote: String) = RecursosRoot.voltarPara(this@ServicoRoot, pacote)
        override fun fecharApp(pacote: String) { RecursosRoot.fecharApp(pacote) }
        override fun abertos() = RecursosRoot.tarefas(this@ServicoRoot).map { it.pacote }
        override fun capturar(nome: String, pronto: (Boolean) -> Unit) {
            val f = RecursosRoot.capturar(nome)
            if (f != null) android.media.MediaScannerConnection.scanFile(this@ServicoRoot, arrayOf(f), arrayOf("image/png"), null)
            pronto(f != null)
        }
        override fun desligarTela() { RecursosRoot.desligarTela() }
        override fun menuEnergia() { Root.rodar("input keyevent --longpress KEYCODE_POWER") }
    }

    private val botao by lazy { BotaoPs(this, acoes, trabalho, ui) }

    /** Um leitor por controle com botão PS/Guide ("/dev/input/eventN" → processo "cat"). */
    private val leitores = HashMap<String, Process>()
    private val procurar = Object()

    /** Controle conectado ou desconectado: procura de novo quais dispositivos têm o botão PS. */
    private val ouvinteControles = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = acordar()
        override fun onInputDeviceRemoved(id: Int) = acordar()
        override fun onInputDeviceChanged(id: Int) = Unit
    }

    private fun acordar() = synchronized(procurar) { procurar.notifyAll() }

    /**
     * Mantém um leitor em cada dispositivo de entrada que tem BTN_MODE (o PS do DualShock/DualSense, o Guide do
     * Xbox). Lê os eventos brutos com "cat" (o "getevent" guarda a saída num buffer quando vai para um pipe, e o
     * "soltar" chegava grudado no "apertar").
     */
    private fun lerControle() {
        getSystemService(android.hardware.input.InputManager::class.java).registerInputDeviceListener(ouvinteControles, ui)
        while (ligado) {
            val nos = dispositivosComPs()
            synchronized(leitores) {
                (leitores.keys - nos).forEach { n -> try { leitores.remove(n)?.destroy() } catch (_: Exception) {} }
                (nos - leitores.keys).forEach { n -> Root.abrirLeitura("cat $n")?.let { p -> leitores[n] = p; Thread { lerEventos(n, p) }.start() } }
            }
            synchronized(procurar) { try { procurar.wait(30_000) } catch (_: InterruptedException) {} }
        }
        synchronized(leitores) { leitores.values.forEach { try { it.destroy() } catch (_: Exception) {} }; leitores.clear() }
    }

    /**
     * /proc/bus/input/devices lista cada dispositivo ("H: Handlers=… event20") com o mapa de teclas em hexa
     * ("B: KEY=7fdb000000000000 0 0 0 0", palavras de 64 bits, a mais alta primeiro); ficam os que têm a 316 (BTN_MODE).
     */
    private fun dispositivosComPs(): Set<String> {
        val achados = HashSet<String>()
        for (bloco in Root.rodar("cat /proc/bus/input/devices").saida.split(Regex("\\n\\s*\\n"))) {
            val evento = Regex("Handlers=.*?\\b(event\\d+)").find(bloco)?.groupValues?.get(1) ?: continue
            val palavras = Regex("B: KEY=([0-9a-fA-F ]+)").find(bloco)?.groupValues?.get(1)?.trim()?.split(' ')?.reversed() ?: continue
            val palavra = palavras.getOrNull(316 / 64)?.let { java.lang.Long.parseUnsignedLong(it, 16) } ?: continue
            if ((palavra ushr (316 % 64)) and 1L == 1L) achados += "/dev/input/$evento"
        }
        return achados
    }

    /**
     * Eventos brutos do kernel (struct input_event de 64 bits: tempo 16 bytes, tipo 2, código 2, valor 4).
     * EV_KEY (1): 316 = BTN_MODE (PS), 314 = BTN_SELECT (Share); valor 1 = apertou, 0 = soltou.
     */
    private fun lerEventos(no: String, p: Process) {
        val entrada = java.io.DataInputStream(p.inputStream)
        val buf = ByteArray(24)
        try {
            while (ligado) {
                entrada.readFully(buf)
                val b = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val tipo = b.getShort(16).toInt()
                val codigo = b.getShort(18).toInt() and 0xFFFF
                val valor = b.getInt(20)
                // Analógico esquerdo (EV_ABS 0 = X, 1 = Y), só com o modo mouse ligado num app.
                if (tipo == 3 && (codigo == 0 || codigo == 1)) {
                    val m = ServicoAcessibilidade.instancia?.mouse
                    if (m != null) ui.post { if (codigo == 0) m.analogico(valor, null) else m.analogico(null, valor) }
                    continue
                }
                if (tipo != 1 || valor > 1) continue // valor 2 = repetição da tecla segurada
                val qual = when (codigo) { 316 -> "PS"; 314 -> "SHARE"; 310 -> "L1"; else -> continue }
                ui.post { botao.tratar(qual, valor == 1) }
            }
        } catch (_: Exception) {
        }
        synchronized(leitores) { if (leitores[no] === p) leitores.remove(no) }
        try { p.destroy() } catch (_: Exception) {}
    }

}
