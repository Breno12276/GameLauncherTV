package com.gamelaunchertv.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Acesso root (Magisk, KernelSU, APatch): um shell "su" que fica aberto e recebe os comandos um por vez.
 * Abrir um "su" a cada comando faria o gerenciador de root avisar a cada vez (e é lento).
 * - [existe]: há um binário "su" no aparelho (não pede nada; serve para mostrar ou não a seção "Root").
 * - [pedir]: abre o shell; o gerenciador pergunta (Magisk) ou já precisa ter liberado o app (KernelSU).
 * - [rodar]: executa um comando e devolve a saída (stdout e stderr juntos) e se deu certo.
 * Tudo fica desligado até a pessoa ligar "Recursos root" nas configurações (preferência "root_ligado").
 */
object Root {

    class Resultado(val saida: String, val ok: Boolean)

    private var processo: Process? = null
    private var escrita: OutputStreamWriter? = null
    private var leitura: BufferedReader? = null
    private const val FIM = "__GLTV_FIM__"

    private val CAMINHOS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")

    /** Gerenciadores de root (o KernelSU só mostra o "su" aos apps já liberados nele). */
    private val GERENCIADORES = listOf("me.weishu.kernelsu", "com.rifsxd.ksunext", "com.topjohnwu.magisk", "io.github.huskydg.magisk",
        "io.github.vvb2060.magisk", "me.bmax.apatch")

    /** O aparelho tem root? (não abre nada, então não dispara o pedido do gerenciador de root.) */
    fun existe(ctx: Context): Boolean = CAMINHOS.any { File(it).exists() } ||
        System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() } ||
        GERENCIADORES.any { p ->
            try { ctx.packageManager.getPackageInfo(p, android.content.pm.PackageManager.PackageInfoFlags.of(0)); true } catch (_: Exception) { false }
        }

    fun ligado(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE).getBoolean("root_ligado", false)

    /** O shell root está aberto e responde como uid 0. */
    @Synchronized
    fun pedir(): Boolean {
        if (processo?.isAlive == true) return true
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            processo = p
            escrita = OutputStreamWriter(p.outputStream)
            leitura = BufferedReader(InputStreamReader(p.inputStream))
            val r = rodarAberto("id -u", 20_000)
            (r.ok && r.saida.trim() == "0").also { if (!it) fechar() }
        } catch (_: Exception) {
            fechar()
            false
        }
    }

    /** Roda [comando] como root; abre o shell se preciso. Sem root: saída vazia e ok = false. */
    @Synchronized
    fun rodar(comando: String, limiteMs: Long = 30_000): Resultado {
        if (processo?.isAlive != true && !pedir()) return Resultado("", false)
        return try {
            rodarAberto(comando, limiteMs)
        } catch (_: Exception) {
            fechar()
            Resultado("", false)
        }
    }

    /** Escreve o comando com um marcador no fim e lê até o marcador (com o código de saída). */
    private fun rodarAberto(comando: String, limiteMs: Long): Resultado {
        val w = escrita ?: return Resultado("", false)
        val r = leitura ?: return Resultado("", false)
        w.write("( $comando ) 2>&1 < /dev/null; echo \"$FIM\$?\"\n")
        w.flush()
        val saida = StringBuilder()
        val limite = System.currentTimeMillis() + limiteMs
        while (true) {
            // Espera sem travar para sempre: um comando preso fecha o shell (o próximo abre outro).
            while (!r.ready()) {
                if (processo?.isAlive != true || System.currentTimeMillis() > limite) {
                    fechar()
                    return Resultado(saida.toString(), false)
                }
                Thread.sleep(15)
            }
            val linha = r.readLine() ?: return Resultado(saida.toString(), false)
            val i = linha.indexOf(FIM)
            if (i >= 0) {
                if (i > 0) saida.append(linha, 0, i).append('\n')
                return Resultado(saida.toString().trimEnd(), linha.substring(i + FIM.length).trim() == "0")
            }
            saida.append(linha).append('\n')
        }
    }

    @Synchronized
    fun fechar() {
        try { escrita?.close() } catch (_: Exception) {}
        try { processo?.destroy() } catch (_: Exception) {}
        processo = null
        escrita = null
        leitura = null
    }

    /** Processo root à parte que fica lendo (ex.: "getevent"); quem chama lê a saída e o encerra. */
    fun abrirLeitura(comando: String): Process? = try {
        ProcessBuilder("su", "-c", comando).redirectErrorStream(true).start()
    } catch (_: Exception) {
        null
    }

    /**
     * Comando demorado (backup, instalação) num "su" à parte, para não segurar o shell principal (menu rápido e
     * carregamento continuam respondendo enquanto isso).
     */
    fun rodarSeparado(comando: String, limiteMs: Long): Resultado {
        val p = abrirLeitura(comando) ?: return Resultado("", false)
        val saida = StringBuilder()
        val leitor = Thread { try { p.inputStream.bufferedReader().forEachLine { saida.append(it).append('\n') } } catch (_: Exception) {} }
        leitor.start()
        val terminou = p.waitFor(limiteMs, TimeUnit.MILLISECONDS)
        if (!terminou) p.destroy()
        leitor.join(2000)
        return Resultado(saida.toString().trimEnd(), terminou && p.exitValue() == 0)
    }

    /** Aspas simples para o shell ("it's" → 'it'\''s'). */
    fun aspas(t: String) = "'" + t.replace("'", "'\\''") + "'"

    /** Espera um processo terminar (para quem roda algo demorado com [abrirLeitura]). */
    fun esperar(p: Process, segundos: Long) = p.waitFor(segundos, TimeUnit.SECONDS)
}
