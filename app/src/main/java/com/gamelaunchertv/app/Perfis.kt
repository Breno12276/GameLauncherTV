package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Perfis: cada pessoa da casa com os próprios favoritos, coleções, ordem das seções, aparência, sons, histórico
 * de jogo e contas (Steam, Xbox, RetroAchievements). Ficam de todos: capas e nomes trocados à mão, pastas de
 * ROMs, emuladores, root, botão PS e as chaves de TMDB/SteamGridDB/YouTube.
 * Trocar de perfil guarda os dados do atual num arquivo, carrega os do outro e reabre o launcher.
 *
 * Controle parental (no mesmo arquivo de preferências "perfis", que não troca com o perfil):
 * - PIN de 4 dígitos; perfis "infantis" pedem o PIN para abrir as configurações e para trocar de perfil;
 * - limite de tempo de jogo por dia em cada perfil infantil;
 * - jogos marcados como "só para adultos" somem nos perfis infantis.
 */
object Perfis {

    class Perfil(val id: String, val nome: String, val infantil: Boolean, val limiteMin: Int)

    fun prefs(ctx: Context) = ctx.getSharedPreferences("perfis", Context.MODE_PRIVATE)

    /** Chaves de "nova_home" que são de cada pessoa. */
    fun pessoal(k: String): Boolean =
        k in PESSOAIS || PREFIXOS.any { k.startsWith(it) }

    private val PESSOAIS = setOf(
        "fav_add", "fav_fora", "ocultos", "colecoes", "jogos_add", "jogos_del", "roms_ocultas",
        "tema", "cor_fundo", "cor_destaque", "fonte", "zoom_foco", "raio_cards", "tam_apps", "tam_cards", "tam_interface",
        "colunas_apps", "ajuste_img", "banner_tv", "relogio", "widgets", "fundo", "fundo_animado", "sons_pacote", "sons_volume",
        "destaque", "intervalo_destaque", "trailers_auto", "som_trailers", "ocio_min", "formato_capas",
        Chaves.Servico.STEAM.pref, "steam_id", Chaves.Servico.XBOX.pref, Chaves.Servico.RETROACHIEVEMENTS.pref, "ra_usuario",
    )
    private val PREFIXOS = listOf("ordem_", "ordem_secoes_", "ordem_modo:", "filtro:", "formato:")

    fun todos(ctx: Context): List<Perfil> {
        val a = try { JSONArray(prefs(ctx).getString("lista", "[]")) } catch (_: Exception) { JSONArray() }
        val lista = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Perfil(it.getString("id"), it.optString("nome"), it.optBoolean("infantil"), it.optInt("limite"))
        }
        return lista.ifEmpty { listOf(Perfil("p1", "Principal", false, 0)) }
    }

    private fun guardarLista(ctx: Context, l: List<Perfil>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("nome", it.nome).put("infantil", it.infantil).put("limite", it.limiteMin)) }
        prefs(ctx).edit().putString("lista", a.toString()).apply()
    }

    fun atualId(ctx: Context) = prefs(ctx).getString("atual", null) ?: todos(ctx).first().id
    fun atual(ctx: Context) = todos(ctx).firstOrNull { it.id == atualId(ctx) } ?: todos(ctx).first()

    fun criar(ctx: Context, nome: String, infantil: Boolean): Perfil {
        val l = todos(ctx)
        val id = "p" + (System.currentTimeMillis() % 10_000_000)
        val p = Perfil(id, nome, infantil, if (infantil) 120 else 0)
        guardarLista(ctx, l + p)
        return p
    }

    fun alterar(ctx: Context, p: Perfil) = guardarLista(ctx, todos(ctx).map { if (it.id == p.id) p else it })

    /** Exclui um perfil (não o atual) e os dados dele. */
    fun excluir(ctx: Context, id: String) {
        if (id == atualId(ctx)) return
        guardarLista(ctx, todos(ctx).filter { it.id != id })
        arquivo(ctx, id).delete()
    }

    private fun arquivo(ctx: Context, id: String) = File(File(ctx.filesDir, "perfis").apply { mkdirs() }, "$id.json")

    /** Guarda os dados do perfil atual, carrega os de [id] (vazios = começa do padrão) e marca como atual. */
    fun trocar(ctx: Context, id: String) {
        val nova = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
        val hist = ctx.getSharedPreferences("historico", Context.MODE_PRIVATE)
        val atual = atualId(ctx)
        // Garante que a lista exista com o perfil atual antes de trocar (primeira vez: só o "Principal").
        guardarLista(ctx, todos(ctx))
        val dados = JSONObject()
            .put("nova_home", Backup.prefsParaJson(nova) { !pessoal(it) })
            .put("historico", Backup.prefsParaJson(hist))
        arquivo(ctx, atual).writeText(dados.toString())

        val novo = try { JSONObject(arquivo(ctx, id).readText()) } catch (_: Exception) { JSONObject() }
        Backup.jsonParaPrefs(novo.optJSONObject("nova_home") ?: JSONObject(), nova) { !pessoal(it) }
        Backup.jsonParaPrefs(novo.optJSONObject("historico") ?: JSONObject(), hist)
        prefs(ctx).edit().putString("atual", id).commit()
        // Listas guardadas das contas do outro perfil.
        ContaSteam.esquecer(ctx)
        ContaXbox.esquecer(ctx)
        File(ctx.filesDir, "ra_recentes.json").delete()
    }

    // ---------- Controle parental ----------

    fun pin(ctx: Context): String? = prefs(ctx).getString("pin", null)?.ifEmpty { null }
    fun definirPin(ctx: Context, pin: String?) = prefs(ctx).edit().putString("pin", pin.orEmpty()).apply()

    /** O perfil atual é infantil e há PIN: configurações e troca de perfil pedem o PIN. */
    fun protegido(ctx: Context) = atual(ctx).infantil && pin(ctx) != null

    fun adultos(ctx: Context): Set<String> = prefs(ctx).getStringSet("adultos", emptySet()).orEmpty()

    fun marcarAdulto(ctx: Context, id: String, sim: Boolean) {
        val s = adultos(ctx).toMutableSet()
        if (sim) s.add(id) else s.remove(id)
        prefs(ctx).edit().putStringSet("adultos", s).apply()
    }

    /** O jogo [id] some neste perfil (perfil infantil e jogo marcado para adultos). */
    fun escondido(ctx: Context, id: String?) = id != null && atual(ctx).infantil && id in adultos(ctx)

    /** Minutos de jogo que ainda restam hoje no perfil infantil (null = sem limite). Desbloqueio com PIN vale até o fim do dia. */
    fun restanteMin(ctx: Context): Long? {
        val p = atual(ctx)
        if (!p.infantil || p.limiteMin <= 0) return null
        if (prefs(ctx).getString("liberado_dia", null) == Historico.dia(System.currentTimeMillis())) return null
        return p.limiteMin - Historico.hojeMs(ctx) / 60_000
    }

    fun liberarHoje(ctx: Context) = prefs(ctx).edit().putString("liberado_dia", Historico.dia(System.currentTimeMillis())).apply()

    val LIMITES = listOf(0, 30, 60, 90, 120, 180, 240)
    fun rotuloLimite(min: Int) = if (min <= 0) "Sem limite" else if (min < 60) "$min min por dia" else "${min / 60} h${if (min % 60 > 0) " ${min % 60}" else ""} por dia"
}
