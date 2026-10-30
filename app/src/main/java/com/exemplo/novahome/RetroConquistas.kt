package com.exemplo.novahome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Uma conquista do RetroAchievements: [medalha] já é a imagem certa (colorida se ganha, apagada se não). */
class Conquista(val titulo: String, val descricao: String, val pontos: Int, val ganha: Boolean, val medalha: String)

/** Progresso da pessoa num jogo. */
class ProgressoRetro(val jogo: String, val total: Int, val ganhas: Int, val pontos: Int, val pontosGanhos: Int, val conquistas: List<Conquista>)

/**
 * Conquistas do RetroAchievements (API web oficial, com o usuário e a chave da própria pessoa, em
 * Configurações → Contas e chaves). O launcher só mostra o progresso; quem dá as conquistas é o emulador
 * com a conta logada (RetroArch, PCSX2/NetherSX2, DuckStation, PPSSPP…).
 * O jogo é achado pelo nome dentro da lista do console (guardada por 30 dias); o progresso, por 10 minutos.
 */
object RetroConquistas {

    private const val API = "https://retroachievements.org/API"
    private const val MEDIA = "https://media.retroachievements.org/Badge"
    private const val VALIDADE_LISTA = 30L * 24 * 60 * 60 * 1000
    private const val VALIDADE_PROGRESSO = 10L * 60 * 1000

    /** Id de cada plataforma no RetroAchievements (Switch não tem). */
    private val CONSOLES = mapOf(
        Sistema.MD to listOf(1), Sistema.N64 to listOf(2), Sistema.SNES to listOf(3), Sistema.GB to listOf(4, 6),
        Sistema.GBA to listOf(5), Sistema.NES to listOf(7), Sistema.PS1 to listOf(12), Sistema.GC to listOf(16, 19),
        Sistema.NDS to listOf(18), Sistema.PS2 to listOf(21), Sistema.PSP to listOf(41), Sistema.DC to listOf(40),
    )

    fun ativo() = Chaves.retroUsuario().isNotBlank() && Chaves.de(Chaves.Servico.RETROACHIEVEMENTS).isNotBlank()

    fun temSuporte(anos: IntRange?) = Sistema.entries.firstOrNull { it.anos == anos }?.let { CONSOLES.containsKey(it) } == true

    private fun normalizar(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("\\(.*?\\)|\\[.*?\\]|~.*?~"), " ") // regiões, tags e "~Hack~" do RetroAchievements
        .replace(Regex("^(the|a)\\s+|,\\s*(the|a)$"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun chave() = Chaves.de(Chaves.Servico.RETROACHIEVEMENTS)

    /** Progresso da pessoa no jogo [nome] da época [anos]; null = sem conta, sem o jogo lá, ou sem rede. */
    @Synchronized
    fun progresso(ctx: Context, nome: String, anos: IntRange?): ProgressoRetro? {
        if (!ativo()) return null
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return null
        val alvo = normalizar(nome)
        val id = CONSOLES[sistema].orEmpty().firstNotNullOfOrNull { console ->
            lista(ctx, console)?.firstOrNull { (titulo, _) -> normalizar(titulo) == alvo }?.second
        } ?: return null
        val f = File(ctx.cacheDir, "retro_${id}_${Chaves.retroUsuario().lowercase()}.json")
        val texto = if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE_PROGRESSO) f.readText() else try {
            Tmdb.baixarTexto("$API/API_GetGameInfoAndUserProgress.php?g=$id&u=${URLEncoder.encode(Chaves.retroUsuario(), "UTF-8")}&y=${chave()}")
                .also { f.writeText(it) }
        } catch (_: Exception) {
            return null
        }
        val o = JSONObject(texto)
        val lista = o.optJSONObject("Achievements")?.let { a ->
            a.keys().asSequence().map { a.getJSONObject(it) }.toList()
        }.orEmpty().sortedBy { it.optInt("DisplayOrder") }
        val conquistas = lista.map { c ->
            val ganha = c.optString("DateEarned").isNotBlank() || c.optString("DateEarnedHardcore").isNotBlank()
            val badge = c.optString("BadgeName")
            Conquista(c.optString("Title"), c.optString("Description"), c.optInt("Points"), ganha,
                "$MEDIA/$badge${if (ganha) "" else "_lock"}.png")
        }
        return ProgressoRetro(o.optString("Title", nome), conquistas.size, conquistas.count { it.ganha },
            conquistas.sumOf { it.pontos }, conquistas.filter { it.ganha }.sumOf { it.pontos }, conquistas)
    }

    /** (título, id) dos jogos com conquistas do console, guardados por 30 dias. */
    private fun lista(ctx: Context, console: Int): List<Pair<String, Int>>? {
        val f = File(ctx.filesDir, "retro_console_$console.json")
        val texto = if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE_LISTA) f.readText() else try {
            Tmdb.baixarTexto("$API/API_GetGameList.php?i=$console&f=1&y=${chave()}").also { f.writeText(it) }
        } catch (_: Exception) {
            if (f.exists()) f.readText() else return null
        }
        val a = try { JSONArray(texto) } catch (_: Exception) { return null }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("Title") to it.optInt("ID") }
    }
}
