package com.gamelaunchertv.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject
import java.io.File

/**
 * Informações pequenas embaixo do relógio: clima (wttr.in, sem chave, pela localização aproximada da rede) e
 * temperatura do celular (da bateria). A bateria do controle já aparece no ícone do controle, na barra.
 * O clima fica guardado por 30 minutos.
 */
object Widgets {

    /** "Informações no topo" (preferência "widgets"). */
    val MODOS = listOf("Desligado", "Clima", "Clima e temperatura do celular", "Só a temperatura do celular")

    private const val VALIDADE = 30L * 60 * 1000
    @Volatile private var buscando = false

    class Clima(val graus: Int, val icone: String, val descricao: String)

    /** Clima guardado (null se nunca buscou); se estiver velho, busca de novo em segundo plano e chama [mudou]. */
    fun clima(ctx: Context, mudou: () -> Unit): Clima? {
        val f = File(ctx.filesDir, "clima.json")
        val atual = if (f.exists()) ler(f.readText()) else null
        if ((!f.exists() || System.currentTimeMillis() - f.lastModified() > VALIDADE) && !buscando) {
            buscando = true
            Thread {
                try {
                    val t = Tmdb.baixarTexto("https://wttr.in/?format=j1&lang=pt")
                    if (ler(t) != null) {
                        f.writeText(t)
                        mudou()
                    }
                } catch (_: Exception) {
                } finally {
                    buscando = false
                }
            }.start()
        }
        return atual
    }

    private fun ler(t: String): Clima? = try {
        val c = JSONObject(t).getJSONArray("current_condition").getJSONObject(0)
        val codigo = c.optInt("weatherCode")
        val desc = c.optJSONArray("lang_pt")?.optJSONObject(0)?.optString("value")
            ?: c.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value").orEmpty()
        Clima(c.getString("temp_C").toInt(), codigo.toString(), desc)
    } catch (_: Exception) {
        null
    }

    /** Códigos do WWO (usados pelo wttr.in) → emoji. */
    private fun icone(c: Int): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (c == 113 && (h >= 18 || h < 6)) "🌙" else iconeDia(c)
    }

    private fun iconeDia(c: Int) = when (c) {
        113 -> "☀"
        116 -> "⛅"
        119, 122 -> "☁"
        143, 248, 260 -> "🌫"
        200, 386, 389, 392, 395 -> "⛈"
        in 176..359 -> "🌧"
        in 362..377 -> "🌨"
        else -> "🌡"
    }

    /** Temperatura da bateria do celular, em °C (null se o Android não informar). */
    fun temperaturaCelular(ctx: Context): Int? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10
    }

    /** O texto da linha de informações conforme o modo; vazio = nada a mostrar. */
    fun texto(ctx: Context, modo: Int, mudou: () -> Unit): String {
        val partes = ArrayList<String>()
        // Com mais de um perfil, o nome de quem está jogando (e o tempo que resta, no perfil infantil com limite).
        if (Perfis.todos(ctx).size > 1) {
            val resta = Perfis.restanteMin(ctx)
            partes += "👤 ${Perfis.atual(ctx).nome}" + (resta?.let { " · ${it.coerceAtLeast(0)} min" } ?: "")
        }
        if (modo == 0) return partes.joinToString("   ")
        if (modo == 1 || modo == 2) clima(ctx, mudou)?.let { partes += "${icone(it.icone.toIntOrNull() ?: 0)} ${it.graus}°" }
        if (modo == 2 || modo == 3) temperaturaCelular(ctx)?.let { t -> partes += (if (t >= 42) "🔥" else "📱") + " $t°C" }
        return partes.joinToString("   ")
    }
}
