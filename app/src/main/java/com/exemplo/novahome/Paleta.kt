package com.exemplo.novahome

import android.graphics.Color

object Paleta {
    val AZUL = 0xFF3D8BFF.toInt()

    // Cor de fundo do launcher (padrão: azul-marinho do Android TV Home 2020). Escolhida nas
    // Configurações; em "Automático" vem do tema do Android (Material You). Ver [definirFundo].
    var FUNDO = 0xFF0E1729.toInt()
        private set

    /** Opções de cor de fundo: índice 0 = automático (tema do Android). */
    val CORES_FUNDO = listOf(
        "Automático" to 0,
        "Azul-marinho" to 0xFF0E1729.toInt(),
        "Preto" to 0xFF08090C.toInt(),
        "Grafite" to 0xFF1B1E24.toInt(),
        "Roxo" to 0xFF1A1030.toInt(),
        "Verde" to 0xFF0B1B17.toInt(),
        "Vinho" to 0xFF220D16.toInt(),
    )

    /**
     * Aplica a cor escolhida. No automático usa a cor de destaque do tema do Android (Material You,
     * muda com o papel de parede), escurecida para ficar como fundo de TV.
     */
    fun definirFundo(ctx: android.content.Context, indice: Int) {
        val i = indice.coerceIn(0, CORES_FUNDO.lastIndex)
        FUNDO = if (i == 0) {
            try {
                val tema = ctx.getColor(android.R.color.system_accent1_900)
                val hsv = FloatArray(3)
                Color.colorToHSV(tema, hsv)
                hsv[1] = minOf(hsv[1], 0.75f)
                hsv[2] = minOf(hsv[2], 0.16f)
                Color.HSVToColor(hsv)
            } catch (_: Exception) {
                CORES_FUNDO[1].second
            }
        } else {
            CORES_FUNDO[i].second
        }
    }

    /** Cor de destaque dos itens selecionados (pílulas, opção escolhida). Ver [definirDestaque]. */
    var DESTAQUE = 0xFF1A73E8.toInt()
        private set

    /** Opções de cor de destaque: índice 0 = automático (tema do Android). */
    val CORES_DESTAQUE = listOf(
        "Automático" to 0,
        "Azul" to 0xFF1A73E8.toInt(),
        "Ciano" to 0xFF0097A7.toInt(),
        "Verde" to 0xFF1E8E3E.toInt(),
        "Laranja" to 0xFFE8710A.toInt(),
        "Vermelho" to 0xFFD93025.toInt(),
        "Rosa" to 0xFFD81B60.toInt(),
        "Roxo" to 0xFF8E24AA.toInt(),
        "Cinza" to 0xFF5F6368.toInt(),
    )

    /** No automático usa a cor de destaque do tema do Android (Material You), num tom que o texto branco lê bem. */
    fun definirDestaque(ctx: android.content.Context, indice: Int) {
        val i = indice.coerceIn(0, CORES_DESTAQUE.lastIndex)
        DESTAQUE = if (i == 0) {
            try {
                ctx.getColor(android.R.color.system_accent1_600)
            } catch (_: Exception) {
                CORES_DESTAQUE[1].second
            }
        } else {
            CORES_DESTAQUE[i].second
        }
    }

    /** Fundo dos painéis laterais: a cor de fundo um pouco mais clara, opaca. */
    fun painel(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(FUNDO, hsv)
        hsv[2] = minOf(1f, hsv[2] + 0.04f)
        return Color.HSVToColor(hsv)
    }

    val AZUL_CLARO = 0xFF8AB4F8.toInt()
    val TEXTO = 0xFFE8EAED.toInt()
    val TEXTO_SUAVE = 0xFFA5ACBA.toInt()
    val ABA_INATIVA = 0xFF9AA3B5.toInt()
    val TEXTO_ESCURO = 0xFF202124.toInt()

    fun alfa(cor: Int, a: Int) = (cor and 0x00FFFFFF) or (a shl 24)

    /** Mesma cor com outro brilho (e saturação contida), para fundos de banner, card e destaque. */
    fun tom(cor: Int, brilho: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cor, hsv)
        hsv[1] = minOf(hsv[1], 0.85f)
        hsv[2] = brilho
        return Color.HSVToColor(hsv)
    }
}
