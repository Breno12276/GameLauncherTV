package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uma paisagem do Wikimedia Commons, com o crédito que a licença (CC BY-SA) exige. */
class Paisagem(val url: String, val credito: String)

/**
 * Paisagens premiadas ("Featured pictures of landscapes") do Wikimedia Commons, pela API oficial.
 * A lista fica guardada e é renovada no máximo uma vez por semana.
 */
object Paisagens {

    private const val VALIDADE = 7L * 24 * 60 * 60 * 1000
    private const val CATEGORIA = "Category:Featured_pictures_of_landscapes"

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "paisagens_v1.json")

    @Synchronized
    fun lista(ctx: Context): List<Paisagem> {
        val cache = lerCache(ctx)
        if (cache != null && System.currentTimeMillis() - cache.first < VALIDADE) return cache.second
        return try {
            val nova = buscar()
            if (nova.isNotEmpty()) salvarCache(ctx, nova)
            nova.ifEmpty { cache?.second.orEmpty() }
        } catch (_: Exception) {
            cache?.second.orEmpty()
        }
    }

    /** Duas páginas de 50, só fotos deitadas (sem panorâmicas compridas demais), em ordem embaralhada. */
    private fun buscar(): List<Paisagem> {
        val saida = ArrayList<Paisagem>()
        var continuar: String? = null
        repeat(2) {
            var url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers" +
                "&gcmtitle=${URLEncoder.encode(CATEGORIA, "UTF-8")}&gcmtype=file&gcmlimit=50" +
                "&prop=imageinfo&iiprop=url%7Cextmetadata%7Csize&iiurlwidth=1920" +
                "&iiextmetadatafilter=Artist%7CLicenseShortName"
            continuar?.let { url += "&gcmcontinue=${URLEncoder.encode(it, "UTF-8")}" }
            val r = JSONObject(get(url))
            val paginas = r.optJSONObject("query")?.optJSONObject("pages") ?: return@repeat
            for (k in paginas.keys()) {
                val info = paginas.getJSONObject(k).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val w = info.optInt("width")
                val h = info.optInt("height")
                if (h <= 0 || w.toFloat() / h !in 1.3f..2.4f) continue
                val imagem = info.optString("thumburl").takeIf { it.startsWith("https://") } ?: continue
                val meta = info.optJSONObject("extmetadata")
                val autor = meta?.optJSONObject("Artist")?.optString("value").orEmpty()
                    .replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim().take(60)
                val licenca = meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
                val credito = listOf(if (autor.isNotEmpty()) "Foto: $autor" else "", licenca, "Wikimedia Commons")
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                saida += Paisagem(imagem, credito)
            }
            continuar = r.optJSONObject("continue")?.optString("gcmcontinue")?.takeIf { it.isNotEmpty() } ?: return@repeat
        }
        return saida.shuffled()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        // A Wikimedia pede um User-Agent que identifique o app.
        c.setRequestProperty("User-Agent", "GameLauncherTV/1.6 (launcher pessoal)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun lerCache(ctx: Context): Pair<Long, List<Paisagem>>? = try {
        val o = JSONObject(arquivo(ctx).readText())
        val a = o.getJSONArray("l")
        o.getLong("q") to (0 until a.length()).map { Paisagem(a.getJSONObject(it).getString("u"), a.getJSONObject(it).optString("c")) }
    } catch (_: Exception) {
        null
    }

    private fun salvarCache(ctx: Context, lista: List<Paisagem>) {
        val a = JSONArray()
        lista.forEach { a.put(JSONObject().put("u", it.url).put("c", it.credito)) }
        arquivo(ctx).writeText(JSONObject().put("q", System.currentTimeMillis()).put("l", a).toString())
    }
}
