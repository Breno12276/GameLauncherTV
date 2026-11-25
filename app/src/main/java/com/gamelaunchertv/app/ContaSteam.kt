package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Seus jogos da Steam (API oficial, com a chave e o perfil da própria pessoa), para mostrar quais você pode jogar
 * no GeForce NOW: o GeForce NOW roda os jogos que você já tem nas lojas. O perfil precisa ter "Detalhes do jogo"
 * público. A lista fica guardada por 6 horas.
 */
object ContaSteam {

    private const val API = "https://api.steampowered.com"
    private const val VALIDADE = 6L * 60 * 60 * 1000

    class JogoSteam(val appId: Int, val nome: String, val minutos: Int, val ultimaVez: Long)

    /**
     * O número do ID (17 dígitos) a partir do que a pessoa colou: o número, o link do perfil
     * (steamcommunity.com/profiles/7656… ou /id/nome) ou só o nome do perfil. null = não achou.
     */
    fun idDoPerfil(chave: String, texto: String): String? {
        val t = texto.trim().trimEnd('/')
        Regex("(\\d{17})").find(t)?.let { return it.groupValues[1] }
        val nome = Regex("/id/([^/?#]+)").find(t)?.groupValues?.get(1) ?: t.substringAfterLast('/')
        if (nome.isBlank()) return null
        val r = JSONObject(Tmdb.baixarTexto("$API/ISteamUser/ResolveVanityURL/v1/?key=$chave&vanityurl=${URLEncoder.encode(nome, "UTF-8")}"))
            .optJSONObject("response") ?: return null
        return r.optString("steamid").takeIf { r.optInt("success") == 1 && it.isNotBlank() }
    }

    /** Jogos da conta; null = chave ou perfil inválidos, ou perfil com os jogos privados. */
    fun jogos(chave: String, id: String): List<JogoSteam>? {
        val r = JSONObject(Tmdb.baixarTexto("$API/IPlayerService/GetOwnedGames/v1/?key=$chave&steamid=$id&include_appinfo=1&include_played_free_games=1"))
            .optJSONObject("response") ?: return null
        val a = r.optJSONArray("games") ?: return if (r.has("game_count")) emptyList() else null
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            JogoSteam(o.optInt("appid"), o.optString("name"), o.optInt("playtime_forever"), o.optLong("rtime_last_played") * 1000)
        }
    }

    /** Jogos da conta guardada em Contas e chaves (com cache); vazio sem conta ou sem rede. */
    @Synchronized
    fun meusJogos(ctx: Context): List<JogoSteam> {
        if (!Chaves.ativa(Chaves.Servico.STEAM)) return emptyList()
        val f = File(ctx.filesDir, "steam_jogos.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE) ler(f.readText())?.let { return it }
        val lista = try {
            jogos(Chaves.de(Chaves.Servico.STEAM), Chaves.usuario(Chaves.Servico.STEAM))
        } catch (_: Exception) {
            null
        } ?: return if (f.exists()) ler(f.readText()).orEmpty() else emptyList()
        val a = org.json.JSONArray()
        lista.forEach { a.put(JSONObject().put("a", it.appId).put("n", it.nome).put("m", it.minutos).put("u", it.ultimaVez)) }
        f.writeText(a.toString())
        return lista
    }

    private fun ler(t: String): List<JogoSteam>? = try {
        val a = org.json.JSONArray(t)
        (0 until a.length()).map { a.getJSONObject(it) }.map { JogoSteam(it.getInt("a"), it.optString("n"), it.optInt("m"), it.optLong("u")) }
    } catch (_: Exception) {
        null
    }

    /** Conta trocada: a lista é buscada de novo. */
    fun esquecer(ctx: Context) {
        File(ctx.filesDir, "steam_jogos.json").delete()
    }
}
