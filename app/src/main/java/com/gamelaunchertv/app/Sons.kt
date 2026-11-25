package com.gamelaunchertv.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons de navegação (mover o foco, abrir, voltar). Gerados aqui mesmo, por síntese (nenhum arquivo de som de
 * console): cada pacote é uma receita de tons. Ficam em WAV no cache e tocam pelo SoundPool, que tem atraso baixo.
 */
object Sons {

    enum class Evento { MOVER, ABRIR, VOLTAR }

    /** Um tom: frequência inicial e final (Hz), duração (ms), forma da onda e volume relativo. */
    class Tom(val de: Double, val ate: Double, val ms: Int, val onda: Int = SENO, val vol: Double = 1.0, val atraso: Int = 0)

    private const val SENO = 0
    private const val QUADRADA = 1
    private const val TRIANGULO = 2

    class Pacote(val nome: String, val receitas: Map<Evento, List<Tom>>)

    val PACOTES = listOf(
        Pacote("Suave", mapOf(
            Evento.MOVER to listOf(Tom(1400.0, 1400.0, 28, vol = 0.35)),
            Evento.ABRIR to listOf(Tom(660.0, 990.0, 120, vol = 0.6)),
            Evento.VOLTAR to listOf(Tom(880.0, 520.0, 110, vol = 0.5)),
        )),
        Pacote("Cristal", mapOf(
            Evento.MOVER to listOf(Tom(2093.0, 2093.0, 45, TRIANGULO, 0.3), Tom(3136.0, 3136.0, 40, SENO, 0.15, 8)),
            Evento.ABRIR to listOf(Tom(1046.0, 1046.0, 160, TRIANGULO, 0.45), Tom(1568.0, 1568.0, 200, SENO, 0.35, 70)),
            Evento.VOLTAR to listOf(Tom(1568.0, 1568.0, 120, TRIANGULO, 0.4), Tom(1046.0, 1046.0, 150, SENO, 0.3, 60)),
        )),
        Pacote("Bolha", mapOf(
            Evento.MOVER to listOf(Tom(500.0, 900.0, 45, SENO, 0.45)),
            Evento.ABRIR to listOf(Tom(400.0, 1200.0, 150, SENO, 0.6)),
            Evento.VOLTAR to listOf(Tom(1000.0, 350.0, 140, SENO, 0.5)),
        )),
        Pacote("Arcade", mapOf(
            Evento.MOVER to listOf(Tom(1760.0, 1760.0, 22, QUADRADA, 0.18)),
            Evento.ABRIR to listOf(Tom(880.0, 880.0, 60, QUADRADA, 0.22), Tom(1320.0, 1320.0, 90, QUADRADA, 0.22, 60)),
            Evento.VOLTAR to listOf(Tom(1320.0, 1320.0, 60, QUADRADA, 0.2), Tom(660.0, 660.0, 90, QUADRADA, 0.2, 60)),
        )),
        Pacote("Madeira", mapOf(
            Evento.MOVER to listOf(Tom(780.0, 700.0, 25, TRIANGULO, 0.5)),
            Evento.ABRIR to listOf(Tom(520.0, 520.0, 70, TRIANGULO, 0.6), Tom(780.0, 780.0, 90, TRIANGULO, 0.5, 55)),
            Evento.VOLTAR to listOf(Tom(780.0, 780.0, 70, TRIANGULO, 0.5), Tom(520.0, 520.0, 90, TRIANGULO, 0.5, 55)),
        )),
    )

    /** Volume (preferência "sons_volume"): 0 = desligado. */
    val VOLUMES = listOf("Desligado" to 0f, "Baixo" to 0.25f, "Médio" to 0.5f, "Alto" to 0.85f)

    private var pool: SoundPool? = null
    private var carregado = -1
    private val ids = HashMap<Evento, Int>()
    private var volume = 0f
    private val ultimo = HashMap<Evento, Long>()

    /** Lê as preferências e prepara o pacote escolhido (chamar ao abrir e ao mudar a opção). */
    fun configurar(ctx: Context) {
        val prefs = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
        volume = VOLUMES[prefs.getInt("sons_volume", 0).coerceIn(0, VOLUMES.lastIndex)].second
        val pacote = prefs.getInt("sons_pacote", 0).coerceIn(0, PACOTES.lastIndex)
        if (volume == 0f) {
            pool?.release()
            pool = null
            carregado = -1
            return
        }
        if (pacote == carregado && pool != null) return
        pool?.release()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        ids.clear()
        val pasta = File(ctx.cacheDir, "sons").apply { mkdirs() }
        for ((ev, tons) in PACOTES[pacote].receitas) {
            val f = File(pasta, "p${pacote}_${ev.name.lowercase()}.wav")
            if (!f.exists()) gravarWav(f, sintetizar(tons))
            ids[ev] = p.load(f.path, 1)
        }
        pool = p
        carregado = pacote
    }

    fun tocar(ev: Evento) {
        val p = pool ?: return
        if (volume == 0f) return
        // Segurar o direcional passa por vários cards por segundo: um som a cada 45 ms no máximo.
        // (e abrir/voltar não tocam duas vezes seguidas quando dois caminhos do código pedem o mesmo som).
        val agora = System.currentTimeMillis()
        if (agora - (ultimo[ev] ?: 0L) < if (ev == Evento.MOVER) 45 else 300) return
        ultimo[ev] = agora
        ids[ev]?.let { p.play(it, volume, volume, 1, 0, 1f) }
    }

    /** Toca uma amostra de [pacote] (na escolha do pacote nas configurações). */
    fun amostra(ctx: Context) {
        configurar(ctx)
        tocar(Evento.ABRIR)
    }

    private const val TAXA = 22050

    private fun sintetizar(tons: List<Tom>): ShortArray {
        val totalMs = tons.maxOf { it.atraso + it.ms } + 10
        val n = TAXA * totalMs / 1000
        val mix = DoubleArray(n)
        for (t in tons) {
            val ini = TAXA * t.atraso / 1000
            val len = TAXA * t.ms / 1000
            var fase = 0.0
            for (i in 0 until len) {
                val k = ini + i
                if (k >= n) break
                val prog = i.toDouble() / len
                val freq = t.de + (t.ate - t.de) * prog
                fase += 2 * PI * freq / TAXA
                val x = when (t.onda) {
                    QUADRADA -> if (sin(fase) >= 0) 1.0 else -1.0
                    TRIANGULO -> 2 / PI * kotlin.math.asin(sin(fase))
                    else -> sin(fase)
                }
                // Ataque de 3 ms e queda exponencial: sem estalos no começo nem no fim.
                val ataque = (i / (TAXA * 0.003)).coerceAtMost(1.0)
                val queda = exp(-4.0 * prog) * (1 - prog)
                mix[k] += x * t.vol * ataque * queda
            }
        }
        return ShortArray(n) { (mix[it].coerceIn(-1.0, 1.0) * 30000).toInt().toShort() }
    }

    private fun gravarWav(f: File, amostras: ShortArray) {
        val dados = amostras.size * 2
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            r.writeBytes("RIFF"); i32(36 + dados); r.writeBytes("WAVE")
            r.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(TAXA); i32(TAXA * 2); i16(2); i16(16)
            r.writeBytes("data"); i32(dados)
            val b = ByteArray(dados)
            for (i in amostras.indices) {
                b[i * 2] = amostras[i].toInt().toByte()
                b[i * 2 + 1] = (amostras[i].toInt() shr 8).toByte()
            }
            r.write(b)
        }
    }
}
