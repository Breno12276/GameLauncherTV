package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Estatísticas de jogo: hoje, últimos 7 dias, total; gráfico de barras da semana; jogos e plataformas que
 * você mais jogou. Tudo do [Historico] (só conta o que foi aberto pelo launcher).
 */
object Estatisticas {

    /** [plataforma]: nome da plataforma pelo id do card ("rom:…" → "PlayStation 2"). */
    fun tela(ctx: Context, s: Float, plataforma: (String) -> String): View {
        val agora = System.currentTimeMillis()
        val dias = (6 downTo 0).map { agora - it * 24L * 60 * 60 * 1000 }
        val porDia = dias.map { Historico.noDia(ctx, Historico.dia(it)) }
        val totais = porDia.map { it.values.sum() }
        val semana = HashMap<String, Long>()
        porDia.forEach { d -> d.forEach { (id, ms) -> semana[id] = (semana[id] ?: 0L) + ms } }
        val registros = Historico.registros(ctx)
        val nomes = registros.associate { it.id to it.nome }
        val total = registros.sumOf { it.totalMs }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((48 * s).toInt(), (36 * s).toInt(), (48 * s).toInt(), (36 * s).toInt())
        }
        fun texto(t: String, tam: Float, cor: Int = Paleta.TEXTO, peso: Int = 400) = TextView(ctx).apply {
            text = t
            setTextColor(cor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tam * s)
            typeface = Paleta.fonte(peso)
        }
        fun titulo(t: String) = col.addView(texto(t, 30f, peso = 600), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (30 * s).toInt() })

        col.addView(texto("Estatísticas de jogo", 44f, peso = 500))
        // Três números grandes.
        val numeros = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((rotulo, ms) in listOf("Hoje" to Historico.hojeMs(ctx), "Últimos 7 dias" to totais.sum(), "Desde o começo" to total)) {
            val bloco = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            bloco.addView(texto(Historico.tempoLegivel(ms), 40f, Paleta.TEXTO, 600))
            bloco.addView(texto(rotulo, 22f, Paleta.TEXTO_SUAVE))
            numeros.addView(bloco, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(numeros, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (24 * s).toInt() })

        titulo("Esta semana")
        val rotulos = dias.map { java.text.SimpleDateFormat("EEE", java.util.Locale.forLanguageTag("pt-BR")).format(java.util.Date(it)).take(3) }
        col.addView(Barras(ctx, s, totais, rotulos), LinearLayout.LayoutParams(-1, (240 * s).toInt()).apply { topMargin = (12 * s).toInt() })

        fun lista(itens: List<Pair<String, Long>>, vazio: String) {
            if (itens.isEmpty()) {
                col.addView(texto(vazio, 24f, Paleta.TEXTO_SUAVE))
                return
            }
            val maior = itens.maxOf { it.second }.coerceAtLeast(1)
            for ((nome, ms) in itens) {
                val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                linha.addView(texto(nome, 24f), LinearLayout.LayoutParams((420 * s).toInt(), -2))
                linha.addView(View(ctx).apply { background = forma(Paleta.DESTAQUE, 6f * s) },
                    LinearLayout.LayoutParams(((560 * s) * ms / maior).toInt().coerceAtLeast((6 * s).toInt()), (14 * s).toInt()))
                linha.addView(texto(Historico.tempoLegivel(ms), 22f, Paleta.TEXTO_SUAVE), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (16 * s).toInt() })
                col.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * s).toInt() })
            }
        }

        titulo("Mais jogados")
        lista(registros.filter { it.totalMs >= 60_000 }.sortedByDescending { it.totalMs }.take(8).map { it.nome to it.totalMs },
            "Ainda não há tempo de jogo registrado. O tempo conta quando você abre um jogo pelo launcher.")

        titulo("Mais jogados nesta semana")
        lista(semana.entries.filter { it.value >= 60_000 }.sortedByDescending { it.value }.take(5).map { (nomes[it.key] ?: it.key) to it.value },
            "Nada jogado nos últimos 7 dias.")

        titulo("Por plataforma")
        val porPlataforma = registros.groupBy { plataforma(it.id) }.mapValues { (_, l) -> l.sumOf { it.totalMs } }
        lista(porPlataforma.entries.filter { it.value >= 60_000 }.sortedByDescending { it.value }.map { it.key to it.value }, "—")

        return ScrollView(ctx).apply {
            isFocusable = true
            addView(col)
        }
    }

    private fun forma(cor: Int, raio: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(cor)
        cornerRadius = raio
    }

    /** Gráfico de barras dos 7 dias, com o tempo em cima de cada barra. */
    private class Barras(ctx: Context, private val s: Float, private val valores: List<Long>, private val rotulos: List<String>) : View(ctx) {
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            val maior = valores.maxOrNull()?.coerceAtLeast(60_000) ?: 60_000
            val n = valores.size
            val passo = width / n.toFloat()
            val larg = passo * 0.5f
            val baseTexto = height - 6 * s
            val baseBarra = height - 40 * s
            val topo = 34 * s
            tinta.textAlign = Paint.Align.CENTER
            tinta.typeface = Paleta.fonte(400)
            for (i in 0 until n) {
                val cx = passo * i + passo / 2
                val h = (baseBarra - topo) * valores[i] / maior
                tinta.color = if (i == n - 1) Paleta.DESTAQUE else Paleta.alfa(Paleta.DESTAQUE, 150)
                r.set(cx - larg / 2, baseBarra - h.coerceAtLeast(4 * s), cx + larg / 2, baseBarra)
                c.drawRoundRect(r, 8 * s, 8 * s, tinta)
                tinta.color = Paleta.TEXTO_SUAVE
                tinta.textSize = 22 * s
                c.drawText(rotulos[i], cx, baseTexto, tinta)
                if (valores[i] >= 60_000) {
                    tinta.color = Paleta.TEXTO
                    tinta.textSize = 20 * s
                    val min = valores[i] / 60_000
                    c.drawText(if (min >= 60) "${min / 60}h${if (min % 60 > 0) "%02d".format(min % 60) else ""}" else "${min}min",
                        cx, r.top - 8 * s, tinta)
                }
            }
        }
    }
}
