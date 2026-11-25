package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max

/** [id] e [tipo] ("movie"/"tv") do TMDB: servem para achar o trailer oficial. */
class Titulo(val nome: String, val sinopse: String, val fundo: String, val id: Long = 0, val tipo: String = "movie")

/**
 * Catálogo "em alta no Brasil" por serviço, pela API oficial do TMDB (sem scraping).
 * A chave vem do segredos.properties (fora do Git) via BuildConfig; sem chave, nada é buscado.
 */
object Tmdb {

    private const val VALIDADE = 24L * 60 * 60 * 1000 // no máximo uma atualização por dia
    private const val POR_SERVICO = 16

    fun ativo() = Chaves.tmdb().isNotBlank()

    fun urlImagem(caminho: String, largura: String) = "https://image.tmdb.org/t/p/$largura$caminho"

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "tmdb_catalogo_v2_${Idioma.tmdb()}.json")

    /** Lê o cache; busca de novo só se passou de 24 h ou se apareceu serviço novo. Sem rede, fica com o cache antigo. */
    fun catalogo(ctx: Context, provedores: List<Int>, forcar: Boolean = false): Map<Int, List<Titulo>> {
        if (!ativo() || provedores.isEmpty()) return emptyMap()
        val cache = lerCache(ctx)
        val fresco = cache != null && System.currentTimeMillis() - cache.first < VALIDADE &&
            provedores.all { it in cache.second }
        if (fresco && !forcar) return cache!!.second
        return try {
            val novo = provedores.associateWith { p ->
                val filmes = buscar("movie", p)
                val series = buscar("tv", p)
                // Intercala filmes e séries.
                (0 until max(filmes.size, series.size)).flatMap { listOfNotNull(filmes.getOrNull(it), series.getOrNull(it)) }
                    .take(POR_SERVICO)
            }
            salvarCache(ctx, novo)
            novo
        } catch (_: Exception) {
            cache?.second ?: emptyMap()
        }
    }

    private fun buscar(tipo: String, provedor: Int): List<Titulo> {
        val url = "https://api.themoviedb.org/3/discover/$tipo?api_key=${Chaves.tmdb()}" +
            "&language=${Idioma.tmdb()}&watch_region=BR&with_watch_providers=$provedor&sort_by=popularity.desc&page=1"
        val json = JSONObject(baixarTexto(url))
        val r = json.getJSONArray("results")
        return (0 until r.length()).mapNotNull {
            val o = r.getJSONObject(it)
            val fundo = o.optString("backdrop_path").takeIf { f -> f.startsWith("/") } ?: return@mapNotNull null
            val nome = o.optString(if (tipo == "movie") "title" else "name").ifBlank { return@mapNotNull null }
            Titulo(nome, o.optString("overview"), fundo, o.optLong("id"), tipo)
        }
    }

    private fun lerCache(ctx: Context): Pair<Long, Map<Int, List<Titulo>>>? = try {
        val o = JSONObject(arquivo(ctx).readText())
        val p = o.getJSONObject("provedores")
        val mapa = p.keys().asSequence().associate { k ->
            val a = p.getJSONArray(k)
            k.toInt() to (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                Titulo(t.getString("n"), t.optString("s"), t.getString("f"), t.optLong("i"), t.optString("t", "movie"))
            }
        }
        o.getLong("quando") to mapa
    } catch (_: Exception) {
        null
    }

    private fun salvarCache(ctx: Context, mapa: Map<Int, List<Titulo>>) {
        val p = JSONObject()
        mapa.forEach { (k, lista) ->
            p.put(k.toString(), org.json.JSONArray().apply {
                lista.forEach { put(JSONObject().put("n", it.nome).put("s", it.sinopse).put("f", it.fundo).put("i", it.id).put("t", it.tipo)) }
            })
        }
        arquivo(ctx).writeText(JSONObject().put("quando", System.currentTimeMillis()).put("provedores", p).toString())
    }

    internal fun baixarTexto(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GameLauncherTV/${BuildConfig.VERSION_NAME} (launcher Android TV)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Imagens no tamanho real de quem vai mostrar: decodificadas fora da linha principal,
 * memória limitada (LRU) e cache em disco de ~50 MB que apaga os arquivos mais antigos.
 */
object Imagens {

    // Capas, banners, pôsteres do Início e fundos somam bem mais que 50 MB: com pouco espaço as capas antigas eram
    // apagadas e baixadas de novo a cada volta ao launcher (demora, ou ficava sem capa se a rede falhasse).
    private const val LIMITE_DISCO = 400L * 1024 * 1024
    private val rede = Executors.newFixedThreadPool(3)
    /** Imagens já baixadas: lidas do disco numa fila própria, sem esperar atrás dos downloads lentos. */
    private val disco = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    // 1/16 da memória do app: paisagens e fundos em tela cheia são grandes; o disco segura o resto.
    private val memoria = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(chave: String, valor: Bitmap) = valor.allocationByteCount
    }

    /** [inteira]: não corta — devolve a imagem toda (cobrindo w×h), para o card mostrar inteira ou esticar. */
    fun carregar(ctx: Context, url: String, w: Int, h: Int, inteira: Boolean = false, pronto: (Bitmap) -> Unit) {
        if (w <= 0 || h <= 0) return
        val chave = "$url@${w}x$h" + if (inteira) ":i" else ""
        memoria.get(chave)?.let { pronto(it); return }
        val app = ctx.applicationContext
        val emDisco = jaBaixada(app, url.removePrefix("logo:"))
        (if (emDisco) disco else rede).execute {
            // "logo:<url>": logo oficial de app, desenhado centralizado num banner (recortar cortaria o logo).
            val b = try {
                if (url.startsWith("logo:")) comporLogo(arquivoDe(app, url.removePrefix("logo:")), w, h)
                else decodificar(arquivoDe(app, url), w, h, inteira)
            } catch (_: Exception) { null } ?: return@execute
            memoria.put(chave, b)
            ui.post { pronto(b) }
        }
    }

    fun soltarMemoria() = memoria.evictAll()

    /**
     * Copia uma foto da galeria para dentro do app (a permissão do seletor é temporária),
     * já de pé do jeito certo (EXIF) e reduzida para no máximo 1920 px. Devolve a URL file://.
     */
    fun guardarFoto(ctx: Context, origem: android.net.Uri): String? = try {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(origem)?.use { BitmapFactory.decodeStream(it, null, o) }
        var amostra = 1
        while (max(o.outWidth, o.outHeight) / (amostra * 2) >= 1920) amostra *= 2
        var b = cr.openInputStream(origem)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = amostra })
        } ?: throw IllegalStateException("foto ilegível")
        val giro = cr.openInputStream(origem)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (giro != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(giro) }
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        val pasta = File(ctx.filesDir, "fotos").apply { mkdirs() }
        val f = File(pasta, "foto_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        "file://${f.path}"
    } catch (_: Exception) {
        null
    }

    private fun pasta(ctx: Context) = File(ctx.cacheDir, "imagens").apply { mkdirs() }

    private fun nomeEmDisco(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    private fun jaBaixada(ctx: Context, url: String) =
        url.startsWith("file://") || File(pasta(ctx), nomeEmDisco(url)).exists()

    private fun arquivoDe(ctx: Context, url: String): File {
        // Fotos da galeria já ficam copiadas dentro do app (file://), não passam pela rede.
        if (url.startsWith("file://")) return File(url.removePrefix("file://"))
        val nome = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(pasta(ctx), "$nome.jpg")
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis()) // mais recente = fica no cache
            return f
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        // Servidores lentos (libretro) entregam imagens grandes devagar: mais folga entre pedaços.
        c.readTimeout = 20000
        // O Wikimedia (logos) recusa pedidos sem identificação.
        c.setRequestProperty("User-Agent", "GameLauncherTV/${BuildConfig.VERSION_NAME} (launcher Android TV)")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val tmp = File(f.path + ".tmp")
            c.inputStream.use { entrada -> tmp.outputStream().use { entrada.copyTo(it) } }
            tmp.renameTo(f)
        } finally {
            c.disconnect()
        }
        limitarDisco(ctx)
        return f
    }

    @Synchronized
    private fun limitarDisco(ctx: Context) {
        val arquivos = pasta(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = arquivos.sumOf { it.length() }
        for (f in arquivos) {
            if (total <= LIMITE_DISCO) break
            total -= f.length()
            f.delete()
        }
    }

    /** Banner w×h branco com o logo inteiro no meio (no máximo 72% da largura e 50% da altura). */
    private fun comporLogo(f: File, w: Int, h: Int): Bitmap? {
        val logo = BitmapFactory.decodeFile(f.path) ?: return null
        val escala = minOf(w * 0.72f / logo.width, h * 0.50f / logo.height)
        val lw = logo.width * escala
        val lh = logo.height * escala
        val saida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(saida)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(logo, null, android.graphics.RectF((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        logo.recycle()
        return saida
    }

    /** Decodifica já reduzida e recorta no meio para o tamanho exato (ARGB_8888: sem faixas nos degradês). */
    /** Largura ÷ altura da imagem (baixa para o cache, se preciso; chamar fora da tela). null = não abriu. */
    fun proporcao(ctx: Context, url: String): Float? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arquivoDe(ctx.applicationContext, url).path, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    } catch (_: Exception) {
        null
    }

    private fun decodificar(f: File, w: Int, h: Int, inteira: Boolean = false): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= w && o.outHeight / (amostra * 2) >= h) amostra *= 2
        val origem = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
        val escala = max(w.toFloat() / origem.width, h.toFloat() / origem.height)
        if (inteira) {
            // Sem cortar: a imagem toda, no tamanho que cobre o card (o card decide encaixar ou esticar).
            val iw = (origem.width * escala).toInt().coerceAtLeast(1)
            val ih = (origem.height * escala).toInt().coerceAtLeast(1)
            if (iw == origem.width && ih == origem.height) return origem
            val b = Bitmap.createScaledBitmap(origem, iw, ih, true)
            if (b !== origem) origem.recycle()
            return b
        }
        val cw = (w / escala).toInt()
        val ch = (h / escala).toInt()
        val x = (origem.width - cw) / 2
        val y = (origem.height - ch) / 2
        val saida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(saida).drawBitmap(origem, Rect(x, y, x + cw, y + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        origem.recycle()
        return saida
    }
}
