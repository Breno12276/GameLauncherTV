package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Color
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Luz e vibração dos controles (Android 12+: InputDevice.lightsManager / vibratorManager).
 * Controles como o DualShock 4 e o DualSense têm uma luz RGB; cada controle ganha uma sessão de luz,
 * que o Android desfaz sozinho quando o app fecha.
 * Opções (preferências "luz_controle", "brilho_controle", "vibrar_abrir"):
 * - Luz: cor do jogo em foco (a da plataforma/app), cor de destaque do launcher, uma cor fixa ou desligada.
 */
object Controle {

    /** Rótulos das opções de luz, na ordem salva em "luz_controle". */
    val LUZES = listOf("Cor do jogo em foco", "Cor do launcher", "Branca", "Azul", "Vermelha", "Verde", "Roxa", "Laranja", "Desligada")
    private val CORES_FIXAS = mapOf(2 to Color.WHITE, 3 to 0xFF1E5BFF.toInt(), 4 to 0xFFFF1A1A.toInt(), 5 to 0xFF00D84A.toInt(),
        6 to 0xFF9B30FF.toInt(), 7 to 0xFFFF7A00.toInt())
    val BRILHOS = listOf(1f, 0.5f, 0.2f)

    private val sessoes = HashMap<Int, LightsManager.LightsSession>()
    private var ultimaCor: Int? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    /** Controles externos conectados (gamepad/joystick). */
    fun conectados(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { d ->
        !d.isVirtual && d.isExternal &&
            (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    private fun luzesRgb(d: InputDevice): List<Light> = try {
        d.lightsManager.lights.filter { it.hasRgbControl() }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Nome de cada controle, uma vez só: o Android divide o DualShock em três (o controle, "Touchpad" e
     * "Motion Sensors"), então as partes ficam de fora e o nome repetido também.
     */
    fun nomes(): List<String> = conectados().map { it.name.removeSuffix(" Touchpad").removeSuffix(" Motion Sensors") }
        .map { it.removeSuffix(" Keyboard").removeSuffix(" Consumer Control").removeSuffix(" System Control") }.distinct()

    fun temLuz() = conectados().any { luzesRgb(it).isNotEmpty() }
    fun temVibracao() = conectados().any { try { it.vibratorManager.vibratorIds.isNotEmpty() } catch (_: Exception) { false } }

    /**
     * Aplica a luz escolhida. [corDoFoco]: cor do jogo/app em foco (só vale na opção "Cor do jogo em foco");
     * sem ela, fica a cor de destaque do launcher.
     */
    fun aplicarLuz(ctx: Context, corDoFoco: Int? = null) {
        val opcao = prefs(ctx).getInt("luz_controle", 0)
        val base = when (opcao) {
            0 -> corDoFoco ?: Paleta.DESTAQUE
            1 -> Paleta.DESTAQUE
            8 -> Color.BLACK
            else -> CORES_FIXAS[opcao] ?: Paleta.DESTAQUE
        }
        val brilho = BRILHOS.getOrElse(prefs(ctx).getInt("brilho_controle", 0)) { 1f }
        val cor = Color.rgb((Color.red(base) * brilho).toInt(), (Color.green(base) * brilho).toInt(), (Color.blue(base) * brilho).toInt())
        if (cor == ultimaCor) return
        ultimaCor = cor
        for (d in conectados()) {
            val luzes = luzesRgb(d)
            if (luzes.isEmpty()) continue
            try {
                val sessao = sessoes.getOrPut(d.id) { d.lightsManager.openSession() }
                val pedido = LightsRequest.Builder()
                luzes.forEach { pedido.addLight(it, LightState.Builder().setColor(cor).build()) }
                sessao.requestLights(pedido.build())
            } catch (_: Exception) {
                sessoes.remove(d.id)
            }
        }
    }

    /** Controle novo conectado (ou desconectado): a próxima aplicação refaz as sessões. */
    fun controlesMudaram() {
        val ids = conectados().map { it.id }.toSet()
        sessoes.keys.filter { it !in ids }.forEach { id -> try { sessoes.remove(id)?.close() } catch (_: Exception) {} }
        ultimaCor = null
    }

    /** Devolve a luz ao normal do controle (o app saiu ou a opção mudou para "Desligada" e quer soltar). */
    fun soltarLuz() {
        sessoes.values.forEach { try { it.close() } catch (_: Exception) {} }
        sessoes.clear()
        ultimaCor = null
    }

    /** Vibração curta em todos os controles que vibram ([forte]: o teste das configurações). */
    fun vibrar(forte: Boolean = false) {
        val efeito = VibrationEffect.createOneShot(if (forte) 350L else 90L, if (forte) 255 else 160)
        for (d in conectados()) {
            try {
                val vm = d.vibratorManager
                if (vm.vibratorIds.isEmpty()) continue
                vm.defaultVibrator.vibrate(efeito)
            } catch (_: Exception) {
            }
        }
    }

    /** Ao abrir um jogo: vibração curta, se a opção estiver ligada. */
    fun aoAbrirJogo(ctx: Context) {
        if (prefs(ctx).getBoolean("vibrar_abrir", false)) vibrar()
    }
}
