package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sua conta Xbox pelo OpenXBL (xbl.io): serviço independente em que a pessoa entra com a conta Microsoft e recebe
 * uma chave de API. O launcher lê o perfil (gamertag, gamerscore) e os jogos jogados com o progresso das
 * conquistas. As respostas seguem o formato da API do Xbox Live ("profileUsers", "titles"); ficam guardadas por
 * 15 minutos (o plano grátis do OpenXBL limita os pedidos por hora).
 */
object ContaXbox {

    private const val API = "https://xbl.io/api/v2"
    private const val VALIDADE = 15L * 60 * 1000

    class Perfil(val gamertag: String, val gamerscore: String, val avatar: String?)

    /** Um jogo da conta: [progresso] de 0 a 100; [ultimaVez] em ms. */
    class Jogo(val titleId: String, val nome: String, val imagem: String?, val ganhas: Int, val total: Int,
               val pontos: Int, val pontosTotal: Int, val progresso: Int, val ultimaVez: Long)

    fun ativa() = Chaves.ativa(Chaves.Servico.XBOX)

    private fun pedir(ctx: Context, caminho: String, arquivo: String): String? {
        val f = File(ctx.filesDir, arquivo)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE) return f.readText()
        return try {
            val c = URL("$API/$caminho").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("X-Authorization", Chaves.de(Chaves.Servico.XBOX))
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            try {
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText().also { f.writeText(it) }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            if (f.exists()) f.readText() else null
        }
    }

    fun perfil(ctx: Context): Perfil? {
        if (!ativa()) return null
        val o = try { JSONObject(pedir(ctx, "account", "xbox_perfil.json") ?: return null) } catch (_: Exception) { return null }
        val u = o.optJSONArray("profileUsers")?.optJSONObject(0) ?: o
        val conf = u.optJSONArray("settings")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .associate { it.optString("id") to it.optString("value") }
        val tag = conf["ModernGamertag"]?.ifBlank { null } ?: conf["Gamertag"] ?: u.optString("gamertag")
        if (tag.isNullOrBlank()) return null
        return Perfil(tag, conf["Gamerscore"] ?: u.optString("gamerscore"), conf["GameDisplayPicRaw"]?.ifBlank { null })
    }

    /** Jogos com conquistas, do jogado por último ao mais antigo. */
    fun jogos(ctx: Context): List<Jogo> {
        if (!ativa()) return emptyList()
        val o = try { JSONObject(pedir(ctx, "achievements", "xbox_jogos.json") ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val titulos: JSONArray = o.optJSONArray("titles") ?: return emptyList()
        return (0 until titulos.length()).map { titulos.getJSONObject(it) }.mapNotNull { t ->
            val a = t.optJSONObject("achievement") ?: return@mapNotNull null
            val nome = t.optString("name").ifBlank { return@mapNotNull null }
            val quando = t.optJSONObject("titleHistory")?.optString("lastTimePlayed")?.let { iso ->
                try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }
            } ?: 0L
            Jogo(t.optString("titleId"), nome.replace(Regex("[®™]"), "").trim(), t.optString("displayImage").ifBlank { null },
                a.optInt("currentAchievements"), a.optInt("totalAchievements"), a.optInt("currentGamerscore"), a.optInt("totalGamerscore"),
                a.optInt("progressPercentage"), quando)
        }.sortedByDescending { it.ultimaVez }
    }

    /** Conta trocada: perfil e jogos são buscados de novo. */
    fun esquecer(ctx: Context) {
        File(ctx.filesDir, "xbox_perfil.json").delete()
        File(ctx.filesDir, "xbox_jogos.json").delete()
    }
}
