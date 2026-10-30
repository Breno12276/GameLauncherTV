package com.exemplo.novahome

import android.content.Context
import android.content.SharedPreferences

/**
 * Chaves de API. A que a pessoa colocou em Configurações → Contas e chaves vale primeiro; sem ela,
 * vale a embutida no APK (só a versão "pessoal" traz chaves, vindas do segredos.properties; a "publico" sai vazia).
 * Sem chave nenhuma, cada parte do app usa só fontes que não precisam de chave (Steam, Wikipédia, Stremio, libretro).
 */
object Chaves {

    enum class Servico(val pref: String, val nome: String, val onde: String) {
        TMDB("chave_tmdb", "TMDB",
            "Filmes e séries em português, fileiras da Netflix e trailers dublados. Grátis em themoviedb.org → Configurações → API (chave v3)."),
        STEAMGRIDDB("chave_steamgriddb", "SteamGridDB",
            "Capas, logos e artes dos jogos e apps que as fontes sem chave não têm. Grátis em steamgriddb.com → Preferências → API."),
        YOUTUBE("chave_youtube", "YouTube",
            "Trailers de jogos fora da Steam e a fileira \"Em alta no YouTube\". Grátis no Google Cloud Console → YouTube Data API v3."),
        RETROACHIEVEMENTS("chave_retroachievements", "RetroAchievements",
            "Suas conquistas nos jogos de emulador, na tela de informações de cada ROM. Conta grátis em retroachievements.org → " +
                "Configurações → \"Chave de API da Web\" (vai junto com o seu nome de usuário)."),
    }

    /** Nome de usuário do RetroAchievements (a chave sozinha não basta: o progresso é de uma pessoa). */
    fun retroUsuario(): String = prefs?.getString("ra_usuario", null).orEmpty().trim()

    fun guardarRetroUsuario(u: String) {
        prefs?.edit()?.putString("ra_usuario", u.trim())?.apply()
    }

    @Volatile private var prefs: SharedPreferences? = null

    fun iniciar(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
    }

    /** Chave que a pessoa colocou (vazia se não colocou). */
    fun propria(s: Servico): String = prefs?.getString(s.pref, null).orEmpty().trim()

    fun embutida(s: Servico): String = when (s) {
        Servico.TMDB -> BuildConfig.TMDB_KEY
        Servico.STEAMGRIDDB -> BuildConfig.STEAMGRIDDB_KEY
        Servico.YOUTUBE -> BuildConfig.YOUTUBE_KEY
        Servico.RETROACHIEVEMENTS -> "" // conta pessoal: nunca vem embutida
    }

    fun de(s: Servico): String = propria(s).ifEmpty { embutida(s) }

    fun guardar(s: Servico, chave: String) {
        prefs?.edit()?.putString(s.pref, chave.trim())?.apply()
    }

    fun tmdb() = de(Servico.TMDB)
    fun steamGrid() = de(Servico.STEAMGRIDDB)
    fun youtube() = de(Servico.YOUTUBE)

    /** Testa a chave [chave] com um pedido simples ao serviço ([usuario]: só o RetroAchievements). true = funcionou. */
    fun testar(s: Servico, chave: String, usuario: String = ""): Boolean = try {
        when (s) {
            Servico.RETROACHIEVEMENTS -> usuario.isNotBlank() && jsonOuNulo(Tmdb.baixarTexto(
                "https://retroachievements.org/API/API_GetUserProfile.php?u=" + java.net.URLEncoder.encode(usuario, "UTF-8") + "&y=$chave"))
                ?.optString("User")?.equals(usuario, ignoreCase = true) == true
            Servico.TMDB -> Tmdb.baixarTexto("https://api.themoviedb.org/3/configuration?api_key=$chave").contains("images")
            Servico.YOUTUBE -> Tmdb.baixarTexto("https://www.googleapis.com/youtube/v3/videos?part=id&id=dQw4w9WgXcQ&key=$chave").contains("items")
            Servico.STEAMGRIDDB -> {
                val c = java.net.URL("https://www.steamgriddb.com/api/v2/search/autocomplete/portal").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Authorization", "Bearer $chave")
                try { c.responseCode == 200 } finally { c.disconnect() }
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun jsonOuNulo(t: String) = try { org.json.JSONObject(t) } catch (_: Exception) { null }
}
