package com.gamelaunchertv.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Chaves de API. A que a pessoa colocou em Configurações → Contas e chaves vale primeiro; sem ela,
 * vale a embutida no APK (só a versão "pessoal" traz chaves, vindas do segredos.properties; a "publico" sai vazia).
 * Sem chave nenhuma, cada parte do app usa só fontes que não precisam de chave (Steam, Wikipédia, Stremio, libretro).
 * Contas pessoais (RetroAchievements, Steam, Xbox) nunca vêm embutidas; algumas pedem também um usuário ([Servico.usuario]).
 */
object Chaves {

    /** [usuario]: texto do campo de usuário que vai junto com a chave (null = só a chave); guardado em [prefUsuario]. */
    enum class Servico(val pref: String, val nome: String, val onde: String, val usuario: String? = null, val prefUsuario: String? = null) {
        TMDB("chave_tmdb", "TMDB",
            "Filmes e séries em português, fileiras da Netflix e trailers dublados. Grátis em themoviedb.org → Configurações → API (chave v3)."),
        STEAMGRIDDB("chave_steamgriddb", "SteamGridDB",
            "Capas, logos e artes dos jogos e apps que as fontes sem chave não têm. Grátis em steamgriddb.com → Preferências → API."),
        YOUTUBE("chave_youtube", "YouTube",
            "Trailers de jogos fora da Steam e a fileira \"Em alta no YouTube\". Grátis no Google Cloud Console → YouTube Data API v3."),
        RETROACHIEVEMENTS("chave_retroachievements", "RetroAchievements",
            "Suas conquistas nos jogos de emulador, na tela de informações de cada ROM. Conta grátis em retroachievements.org → " +
                "Configurações → \"Chave de API da Web\" (vai junto com o seu nome de usuário).",
            "Seu nome de usuário", "ra_usuario"),
        STEAM("chave_steam", "Steam",
            "Seus jogos da Steam que rodam no GeForce NOW, numa fileira só deles. Chave grátis em steamcommunity.com/dev/apikey " +
                "(em \"domínio\" vale qualquer nome). Vai junto com o seu perfil: o link (steamcommunity.com/id/…) ou o número do ID. " +
                "Em Privacidade do perfil, \"Detalhes do jogo\" precisa estar como Público.",
            "Link do seu perfil ou ID da Steam", "steam_id"),
        XBOX("chave_openxbl", "Xbox (OpenXBL)",
            "Seu perfil, jogos recentes e conquistas do Xbox. Entre em xbl.io com a sua conta Microsoft e copie a chave de API " +
                "(o OpenXBL é um serviço independente; o plano grátis tem limite de pedidos por hora)."),
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
        // Contas pessoais: nunca vêm embutidas.
        Servico.RETROACHIEVEMENTS, Servico.STEAM, Servico.XBOX -> ""
    }

    fun de(s: Servico): String = propria(s).ifEmpty { embutida(s) }

    fun guardar(s: Servico, chave: String) {
        prefs?.edit()?.putString(s.pref, chave.trim())?.apply()
    }

    /** Usuário que vai junto com a chave (vazio se o serviço não pede ou não foi colocado). */
    fun usuario(s: Servico): String = s.prefUsuario?.let { prefs?.getString(it, null) }.orEmpty().trim()

    fun guardarUsuario(s: Servico, u: String) {
        s.prefUsuario?.let { prefs?.edit()?.putString(it, u.trim())?.apply() }
    }

    /** Pronta para usar: com a chave e, se o serviço pede, o usuário. */
    fun ativa(s: Servico) = de(s).isNotEmpty() && (s.usuario == null || usuario(s).isNotEmpty())

    /** Nome de usuário do RetroAchievements (a chave sozinha não basta: o progresso é de uma pessoa). */
    fun retroUsuario(): String = usuario(Servico.RETROACHIEVEMENTS)

    fun guardarRetroUsuario(u: String) = guardarUsuario(Servico.RETROACHIEVEMENTS, u)

    fun tmdb() = de(Servico.TMDB)
    fun steamGrid() = de(Servico.STEAMGRIDDB)
    fun youtube() = de(Servico.YOUTUBE)

    /**
     * Testa a chave [chave] com um pedido simples ao serviço ([usuario]: RetroAchievements e Steam). Devolve null se
     * não funcionou, ou o usuário a guardar (na Steam, o número do ID, achado a partir do link ou do nome do perfil).
     */
    fun testar(s: Servico, chave: String, usuario: String = ""): String? = try {
        when (s) {
            Servico.RETROACHIEVEMENTS -> usuario.takeIf {
                it.isNotBlank() && jsonOuNulo(Tmdb.baixarTexto(
                    "https://retroachievements.org/API/API_GetUserProfile.php?u=" + java.net.URLEncoder.encode(it, "UTF-8") + "&y=$chave"))
                    ?.optString("User")?.equals(it, ignoreCase = true) == true
            }
            Servico.TMDB -> "".takeIf { Tmdb.baixarTexto("https://api.themoviedb.org/3/configuration?api_key=$chave").contains("images") }
            Servico.YOUTUBE -> "".takeIf {
                Tmdb.baixarTexto("https://www.googleapis.com/youtube/v3/videos?part=id&id=dQw4w9WgXcQ&key=$chave").contains("items")
            }
            Servico.STEAMGRIDDB -> "".takeIf { codigo("https://www.steamgriddb.com/api/v2/search/autocomplete/portal", "Authorization", "Bearer $chave") == 200 }
            Servico.STEAM -> ContaSteam.idDoPerfil(chave, usuario)?.takeIf { ContaSteam.jogos(chave, it) != null }
            Servico.XBOX -> "".takeIf { codigo("https://xbl.io/api/v2/account", "X-Authorization", chave) == 200 }
        }
    } catch (_: Exception) {
        null
    }

    private fun codigo(url: String, cabecalho: String, valor: String): Int {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty(cabecalho, valor)
        c.setRequestProperty("Accept", "application/json")
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun jsonOuNulo(t: String) = try { org.json.JSONObject(t) } catch (_: Exception) { null }
}
