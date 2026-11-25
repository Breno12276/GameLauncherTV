package com.gamelaunchertv.app

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
        "Azul profundo" to 0xFF06143A.toInt(),
        "Cinza-azulado" to 0xFF1C2430.toInt(),
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
        "Azul intenso" to 0xFF0070D1.toInt(),
        "Verde-limão" to 0xFF4EA81C.toInt(),
        "Azul-claro" to 0xFF2E9BE6.toInt(),
    )

    /**
     * Temas prontos: combinação de cores, sons, bordas, fonte e zoom aplicada de uma vez (cada item continua
     * ajustável depois). Só cores e formas inspiradas no clima de cada console; nada copiado deles.
     * Valores: índices de CORES_FUNDO, CORES_DESTAQUE, Sons.PACOTES, raio (0–4), FONTES, ZOOMS_FOCO.
     */
    class Tema(val nome: String, val fundo: Int, val destaque: Int, val som: Int, val raio: Int, val fonte: Int, val zoom: Int,
               val animado: Boolean, val layout: Int, val crt: Boolean = false)

    /** [Tema.layout]: tela inicial (0 = fileiras do Android TV, 1 = PS4, 2 = Xbox 360, 3 = Switch, 4 = Wii, 5 = PS5, 6 = Xbox Series). */
    val TEMAS = listOf(
        Tema("Padrão (Android TV)", 1, 1, 0, 1, 0, 2, false, 0),
        Tema("Estilo PS4", 7, 9, 1, 1, 0, 2, true, 1),
        Tema("Estilo Xbox 360", 5, 10, 2, 0, 3, 1, false, 2),
        Tema("Estilo Switch", 3, 5, 4, 3, 0, 1, false, 3),
        Tema("Estilo Wii", 8, 11, 2, 4, 2, 1, false, 4),
        Tema("Estilo PS5", 7, 9, 1, 3, 0, 2, false, 5),
        Tema("Estilo Xbox Series", 5, 10, 2, 1, 3, 1, false, 6),
        Tema("Retrô", 2, 4, 3, 0, 5, 3, false, 0, crt = true),
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

    /**
     * Escala da interface (1 = tela de referência de 1127 px de altura). Segue a altura da tela e também a densidade
     * (DPI): a referência é o Modo TV (720 px com densidade 240); DPI maior aumenta tudo, menor diminui.
     */
    fun escala(ctx: android.content.Context, menorLado: Int): Float {
        val dpi = ctx.resources.configuration.densityDpi.takeIf { it > 0 } ?: return menorLado / 1127f * tamanhoInterface(ctx)
        val fator = (dpi * 3f / menorLado).coerceIn(0.5f, 1.6f) // 720 px a 240 = 1
        return menorLado / 1127f * fator * tamanhoInterface(ctx)
    }

    // ---------- Personalização global (Configurações › Aparência) ----------

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("nova_home", android.content.Context.MODE_PRIVATE)

    /** "Tamanho da interface": multiplica tudo (textos, cards, painéis), em cima da densidade. */
    val TAMANHOS_INTERFACE = listOf("Pequeno" to 0.85f, "Normal" to 1f, "Grande" to 1.15f, "Enorme" to 1.3f)
    fun tamanhoInterface(ctx: android.content.Context) = TAMANHOS_INTERFACE.getOrElse(prefs(ctx).getInt("tam_interface", 1)) { TAMANHOS_INTERFACE[1] }.second

    /** Fonte de todos os textos do launcher (as do Android, sem baixar nada). */
    val FONTES = listOf("Padrão" to "sans-serif", "Condensada" to "sans-serif-condensed", "Moderna" to "roboto-flex",
        "Source Sans" to "source-sans-pro", "Clássica (serifa)" to "serif", "Monoespaçada" to "monospace")
    var fonteBase: android.graphics.Typeface = android.graphics.Typeface.SANS_SERIF
        private set

    fun definirFonte(ctx: android.content.Context) {
        val nome = FONTES.getOrElse(prefs(ctx).getInt("fonte", 0)) { FONTES[0] }.second
        fonteBase = android.graphics.Typeface.create(nome, android.graphics.Typeface.NORMAL)
    }

    /** Fonte no [peso] (400 normal, 500 médio, 700 negrito). */
    fun fonte(peso: Int): android.graphics.Typeface = android.graphics.Typeface.create(fonteBase, peso, false)

    /** "Zoom no card selecionado": quanto o card cresce no foco (multiplica o crescimento padrão). */
    val ZOOMS_FOCO = listOf("Sem zoom" to 0f, "Leve" to 0.6f, "Normal" to 1f, "Forte" to 1.5f)
    var zoomFoco = 1f
        private set

    fun definirZoom(ctx: android.content.Context) {
        zoomFoco = ZOOMS_FOCO.getOrElse(prefs(ctx).getInt("zoom_foco", 2)) { ZOOMS_FOCO[2] }.second
    }

    /** Mesma cor com outro brilho (e saturação contida), para fundos de banner, card e destaque. */
    fun tom(cor: Int, brilho: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cor, hsv)
        hsv[1] = minOf(hsv[1], 0.85f)
        hsv[2] = brilho
        return Color.HSVToColor(hsv)
    }
}
