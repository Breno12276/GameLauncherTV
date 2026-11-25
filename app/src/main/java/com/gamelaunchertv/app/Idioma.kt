package com.gamelaunchertv.app

import java.util.Locale

/**
 * Idioma do Android, no formato que cada fonte espera. Lido na hora (se a pessoa trocar o idioma
 * do sistema, as próximas buscas já seguem o novo). Inglês é sempre a reserva.
 */
object Idioma {

    private fun local(): Locale = Locale.getDefault()

    /** Língua: "pt", "en", "es"… */
    fun lingua(): String = local().language.ifBlank { "en" }

    /** País: "BR", "US"… (vazio se o sistema não define). */
    fun pais(): String = local().country

    /** Para o TMDB: "pt-BR". */
    fun tmdb(): String = if (pais().isNotBlank()) "${lingua()}-${pais()}" else lingua()

    /** Para a Wikipédia: subdomínio "pt", "en", "es"… */
    fun wikipedia(): String = lingua()

    /** Para a loja da Steam (nomes próprios dela). */
    fun steam(): String = when (lingua()) {
        "pt" -> if (pais() == "PT") "portuguese" else "brazilian"
        "es" -> if (pais() == "ES") "spanish" else "latam"
        "en" -> "english"
        "fr" -> "french"
        "de" -> "german"
        "it" -> "italian"
        "ja" -> "japanese"
        "ko" -> "koreana"
        "ru" -> "russian"
        "zh" -> if (pais() == "TW" || pais() == "HK") "tchinese" else "schinese"
        else -> "english"
    }

    /** País para a Steam e o YouTube (preços/região); BR se o sistema não define. */
    fun regiao(): String = pais().ifBlank { "BR" }

    /** Palavra "jogo" no idioma do sistema (para buscar na Wikipedia). */
    fun palavraJogo(): String = when (lingua()) {
        "pt" -> "jogo"
        "es" -> "videojuego"
        "fr" -> "jeu vidéo"
        "de" -> "Videospiel"
        "it" -> "videogioco"
        "ja" -> "ゲーム"
        "ko" -> "게임"
        "ru" -> "игра"
        "zh" -> "游戏"
        else -> "video game"
    }

    /** Palavras que indicam um trailer no idioma do sistema (para escolher entre resultados do YouTube). */
    fun marcasNoTitulo(): List<String> = when (lingua()) {
        "pt" -> listOf("dublado", "legendado", "pt-br", "português", "portugues", "pt br")
        "es" -> listOf("español", "espanol", "doblado", "subtitulado", "latino")
        "fr" -> listOf("français", "francais", "vf", "vostfr")
        "de" -> listOf("deutsch", "german")
        "it" -> listOf("italiano", "ita")
        else -> emptyList()
    }

    /** Canal do país/idioma do aparelho ("PlayStation Brasil", "XBOXBR", "Warner Bros. Pictures Brasil"). */
    fun canalLocal(canal: String): Boolean {
        val c = canal.lowercase()
        val palavras = c.split(Regex("[^\\p{L}0-9]+")).filter { it.isNotEmpty() }
        return when (lingua()) {
            "pt" -> if (pais() == "PT") "portugal" in palavras
                else palavras.any { it == "brasil" || it == "brazil" || it == "br" } || c.endsWith("br") || c.endsWith("brasil")
            "es" -> palavras.any { it in setOf("latam", "latino", "latinoamerica", "españa", "espana", "mexico", "méxico") }
            "fr" -> palavras.any { it == "france" || it == "fr" }
            "de" -> palavras.any { it == "deutschland" || it == "de" }
            "it" -> palavras.any { it == "italia" || it == "it" }
            else -> false
        }
    }

    /** Termo de busca do YouTube: "trailer" + a marca do idioma (ex.: "trailer dublado"). */
    fun buscaTrailer(nome: String): String = when (lingua()) {
        "pt" -> "$nome trailer dublado legendado"
        "es" -> "$nome tráiler español"
        "fr" -> "$nome bande annonce"
        "de" -> "$nome trailer deutsch"
        "it" -> "$nome trailer italiano"
        else -> "$nome official trailer"
    }
}
