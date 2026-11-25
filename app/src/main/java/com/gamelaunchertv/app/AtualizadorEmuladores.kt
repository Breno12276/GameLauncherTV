package com.gamelaunchertv.app

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instala e atualiza emuladores a partir das releases oficiais no GitHub (API pública, sem chave), instalando
 * com root ("pm install"), sem a tela de confirmação. Só repositórios que publicam o APK de Android na release.
 * O pacote de cada um é descoberto no próprio APK baixado e guardado ("emu_pacote:<repo>"), então serve mesmo
 * quando o projeto troca o nome do pacote.
 */
object AtualizadorEmuladores {

    /** [filtro]: parte do nome do APK quando a release tem mais de um (ex.: "vanilla" no Azahar). */
    class Emulador(val nome: String, val plataformas: String, val repo: String, val pacotes: List<String>, val filtro: String? = null)

    val LISTA = listOf(
        Emulador("NetherSX2", "PS2", "Trixarian/NetherSX2-patch", listOf("xyz.aethersx2.android")),
        Emulador("aPS3e", "PS3", "aenu1/aps3e", listOf("aenu.aps3e")),
        Emulador("Vita3K", "PS Vita", "Vita3K/Vita3K-Android", listOf("org.vita3k.emulator")),
        Emulador("Azahar", "3DS", "azahar-emu/azahar", listOf("org.azahar_emu.azahar", "io.github.lime3ds.android"), filtro = "vanilla"),
        Emulador("melonDS", "DS", "rafaelvcaetano/melonDS-android", listOf("me.magnum.melonds")),
        Emulador("Cemu", "Wii U", "SSimco/Cemu", listOf("info.cemu.cemu", "info.cemu.Cemu")),
        Emulador("Flycast", "Dreamcast", "flyinghead/flycast", listOf("com.flycast.emulator")),
        Emulador("Lemuroid", "Retrô (vários)", "Swordfish90/Lemuroid", listOf("com.swordfish.lemuroid")),
        Emulador("Winlator", "Windows (PC)", "brunodev85/winlator", listOf("com.winlator")),
    )

    /** Situação de um emulador: versão instalada (null = não instalado), última release e o endereço do APK. */
    class Situacao(val emu: Emulador, val pacote: String?, val instalada: String?, val ultima: String?, val apk: String?) {
        val atualizado get() = instalada != null && ultima != null && apk != null && mesmaVersao(instalada, ultima)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    private fun versao(ctx: Context, pacote: String): String? = try {
        ctx.packageManager.getPackageInfo(pacote, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** "v2.2n" e "2.2n-4248" são a mesma versão: compara só os números e letras do começo. */
    private fun mesmaVersao(instalada: String, tag: String): Boolean {
        fun base(v: String) = v.lowercase().removePrefix("v").removePrefix(".").substringBefore(' ').substringBefore('-')
        val a = base(instalada)
        val b = base(tag)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    /** Consulta a última release de cada emulador (9 pedidos; o GitHub deixa 60 por hora sem chave). */
    fun consultar(ctx: Context): List<Situacao> = LISTA.map { e ->
        val pacote = (listOfNotNull(prefs(ctx).getString("emu_pacote:${e.repo}", null)) + e.pacotes).firstOrNull { versao(ctx, it) != null }
        val (tag, apk) = try {
            val r = JSONObject(Tmdb.baixarTexto("https://api.github.com/repos/${e.repo}/releases/latest"))
            val assets = r.optJSONArray("assets")
            val apks = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            // O que tiver o filtro; senão o que for de 64 bits ou universal; senão o primeiro.
            val escolhido = apks.firstOrNull { a -> e.filtro != null && e.filtro in a.optString("name").lowercase() }
                ?: apks.firstOrNull { a -> a.optString("name").lowercase().let { "arm64" in it || "universal" in it } }
                ?: apks.firstOrNull()
            r.optString("tag_name").ifBlank { null } to escolhido?.optString("browser_download_url")
        } catch (_: Exception) {
            null to null
        }
        Situacao(e, pacote, pacote?.let { versao(ctx, it) }, tag, apk)
    }

    /**
     * Baixa o APK e instala com root. Devolve null se deu certo, ou a mensagem de erro. Assinatura diferente
     * (instalado de outro lugar) não deixa atualizar por cima: só desinstalando antes (e os dados vão junto).
     */
    fun instalar(ctx: Context, s: Situacao, aoProgredir: (Int) -> Unit = {}): String? {
        val url = s.apk ?: return "Esta versão não tem APK para Android."
        val arquivo = File(ctx.cacheDir, "emulador.apk")
        try {
            var c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            // O GitHub manda para outro servidor (objects.githubusercontent.com).
            if (c.responseCode in 300..399) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                c = URL(destino).openConnection() as HttpURLConnection
            }
            if (c.responseCode != 200) return "Download falhou (HTTP ${c.responseCode})."
            val total = c.contentLengthLong
            c.inputStream.use { entrada ->
                arquivo.outputStream().use { saida ->
                    val buf = ByteArray(64 * 1024)
                    var lido = 0L
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        lido += n
                        if (total > 0) aoProgredir((lido * 100 / total).toInt())
                    }
                }
            }
            c.disconnect()
        } catch (e: Exception) {
            return "Download falhou: ${e.message}"
        }
        // O pacote vem do próprio APK (e confere que é um APK de verdade antes de instalar).
        val info = ctx.packageManager.getPackageArchiveInfo(arquivo.path, PackageManager.PackageInfoFlags.of(0))
            ?: return "O arquivo baixado não é um APK válido.".also { arquivo.delete() }
        val tmp = "/data/local/tmp/gltv_emulador.apk"
        val r = Root.rodarSeparado("cp ${Root.aspas(arquivo.path)} $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp", 5 * 60_000L)
        arquivo.delete()
        if (!r.saida.contains("Success")) {
            return if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in r.saida)
                "A versão instalada tem outra assinatura (veio de outro lugar). Faça backup dos saves, desinstale e instale de novo por aqui."
            else "A instalação falhou: " + r.saida.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        }
        prefs(ctx).edit().putString("emu_pacote:${s.emu.repo}", info.packageName).apply()
        return null
    }
}
