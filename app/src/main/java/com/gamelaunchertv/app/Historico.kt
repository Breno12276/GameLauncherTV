package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONObject

/**
 * Histórico de jogo: quando cada jogo foi aberto pela última vez e quanto tempo foi jogado no total.
 * A sessão começa ao abrir um jogo pelo launcher e termina quando o launcher volta à frente; o tempo conta só
 * até 6 horas por sessão (celular esquecido com o jogo aberto não vira 20 horas de jogo).
 * Os jogos são identificados pelo mesmo id dos cards ("rom:…", "app:…", "atalho:…", "nuvem:…").
 */
object Historico {

    private const val MAX_SESSAO = 6L * 60 * 60 * 1000

    class Registro(val id: String, val nome: String, val ultimaVez: Long, val totalMs: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("historico", Context.MODE_PRIVATE)

    private fun ler(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("jogos", "{}")!!) } catch (_: Exception) { JSONObject() }

    /** Um jogo foi aberto agora: conta como "jogado por último" e começa a sessão. */
    @Synchronized
    fun abriu(ctx: Context, id: String, nome: String) {
        val tudo = ler(ctx)
        val o = tudo.optJSONObject(id) ?: JSONObject()
        val agora = System.currentTimeMillis()
        o.put("n", nome).put("u", agora)
        tudo.put(id, o)
        prefs(ctx).edit().putString("jogos", tudo.toString()).putString("sessao_id", id).putLong("sessao_desde", agora).apply()
    }

    /** O launcher voltou à frente: fecha a sessão aberta (se houver) e soma o tempo. */
    @Synchronized
    fun voltou(ctx: Context) {
        val p = prefs(ctx)
        val id = p.getString("sessao_id", null) ?: return
        val dur = (System.currentTimeMillis() - p.getLong("sessao_desde", 0)).coerceIn(0, MAX_SESSAO)
        val tudo = ler(ctx)
        tudo.optJSONObject(id)?.let { o -> o.put("t", o.optLong("t") + dur) }
        // Tempo por dia (para as estatísticas e o limite diário): {"2026-11-17": {"id": ms}}, últimos 90 dias.
        val dias = lerDias(ctx)
        val hoje = dia(System.currentTimeMillis())
        val d = dias.optJSONObject(hoje) ?: JSONObject()
        d.put(id, d.optLong(id) + dur)
        dias.put(hoje, d)
        dias.keys().asSequence().toList().sorted().dropLast(90).forEach { dias.remove(it) }
        p.edit().putString("jogos", tudo.toString()).putString("dias", dias.toString()).remove("sessao_id").remove("sessao_desde").apply()
    }

    private fun lerDias(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("dias", "{}")!!) } catch (_: Exception) { JSONObject() }

    fun dia(t: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(t))

    /** Tempo jogado em cada jogo no dia [dia] ("2026-11-17"). */
    fun noDia(ctx: Context, dia: String): Map<String, Long> {
        val d = lerDias(ctx).optJSONObject(dia) ?: return emptyMap()
        return d.keys().asSequence().associateWith { d.optLong(it) }
    }

    /** Tempo jogado hoje, contando a sessão aberta agora (se houver). */
    fun hojeMs(ctx: Context): Long {
        val p = prefs(ctx)
        val aberta = if (p.getString("sessao_id", null) != null)
            (System.currentTimeMillis() - p.getLong("sessao_desde", System.currentTimeMillis())).coerceIn(0, MAX_SESSAO) else 0L
        return noDia(ctx, dia(System.currentTimeMillis())).values.sum() + aberta
    }

    fun registros(ctx: Context): List<Registro> {
        val tudo = ler(ctx)
        return tudo.keys().asSequence().mapNotNull { id ->
            val o = tudo.optJSONObject(id) ?: return@mapNotNull null
            Registro(id, o.optString("n"), o.optLong("u"), o.optLong("t"))
        }.toList()
    }

    /** Do mais recente ao mais antigo. */
    fun recentes(ctx: Context, limite: Int = 15) = registros(ctx).sortedByDescending { it.ultimaVez }.take(limite)

    fun registro(ctx: Context, id: String) = ler(ctx).optJSONObject(id)?.let { Registro(id, it.optString("n"), it.optLong("u"), it.optLong("t")) }

    /** Tira um jogo do histórico ("Remover de Continuar jogando"). */
    @Synchronized
    fun esquecer(ctx: Context, id: String) {
        val tudo = ler(ctx)
        tudo.remove(id)
        prefs(ctx).edit().putString("jogos", tudo.toString()).apply()
    }

    /** "3 h 20 min", "45 min", "menos de 1 min". */
    fun tempoLegivel(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "menos de 1 min"
            min < 60 -> "$min min"
            else -> "${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
        }
    }

    /** "agora há pouco", "há 2 horas", "ontem", "há 5 dias", "em 3 de nov". */
    fun quandoLegivel(t: Long): String {
        val d = System.currentTimeMillis() - t
        val min = d / 60_000
        return when {
            min < 10 -> "agora há pouco"
            min < 60 -> "há $min min"
            min < 24 * 60 -> "há ${min / 60} h"
            min < 48 * 60 -> "ontem"
            min < 7 * 24 * 60 -> "há ${min / (24 * 60)} dias"
            else -> "em " + java.text.SimpleDateFormat("d 'de' MMM", java.util.Locale.forLanguageTag("pt-BR")).format(java.util.Date(t)).replace(".", "")
        }
    }
}
