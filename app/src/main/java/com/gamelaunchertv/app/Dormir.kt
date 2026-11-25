package com.gamelaunchertv.app

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent

/**
 * "Dormir": pausa o que estiver tocando, liga a Economia de energia e apaga a tela. Com o celular ligado na TV
 * pelo cabo, a tela apagada corta o sinal e a maioria das TVs entra em espera sozinha (o celular não consegue
 * desligar a TV direto: o HDMI-CEC não chega até apps de celular). Na volta ao launcher, a economia é desligada.
 */
object Dormir {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    /** [apagarTela]: quem apaga a tela (root ou acessibilidade). */
    fun dormir(ctx: Context, apagarTela: () -> Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        if (prefs(ctx).getBoolean("dormir_economia", true) && economia(ctx, true)) prefs(ctx).edit().putBoolean("dormindo", true).apply()
        apagarTela()
    }

    /** O launcher voltou: se foi o "Dormir" que ligou a economia de energia, desliga. */
    fun acordou(ctx: Context) {
        if (!prefs(ctx).getBoolean("dormindo", false)) return
        prefs(ctx).edit().remove("dormindo").apply()
        Thread { economia(ctx, false) }.start()
    }

    /** Liga/desliga a Economia de energia: com root, ou com a permissão de configurações seguras (dada pelo Shizuku). */
    private fun economia(ctx: Context, ligar: Boolean): Boolean {
        val v = if (ligar) 1 else 0
        if (Root.ligado(ctx)) return try { Root.rodar("settings put global low_power $v"); true } catch (_: Exception) { false }
        return try { Settings.Global.putInt(ctx.contentResolver, "low_power", v) } catch (_: Exception) { false }
    }
}
