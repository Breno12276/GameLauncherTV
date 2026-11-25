package com.gamelaunchertv.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Uma imagem da galeria: miniatura (para a fileira) e a grande (para o fundo). */
class Foto(val mini: String, val grande: String, val tipo: String)

/**
 * Tudo que se sabe de um jogo. [campos]: ficha ("Desenvolvedora" → "Santa Monica Studio");
 * [selos]: destaques curtos (Metacritic 74, 18+, Multijogador…); [galeria]: prints e artes.
 * [trailer]: URL do trailer da Steam (HLS) ou null. [trailerYoutube]: ID do YouTube ou null.
 */
class DadosJogo(
    val titulo: String,
    val sinopse: String,
    val linha: String,
    val trailer: String?,
    val fonte: String,
    val campos: List<Pair<String, String>> = emptyList(),
    val selos: List<String> = emptyList(),
    val galeria: List<Foto> = emptyList(),
    val logo: String? = null,
    val heroi: String? = null,
    val trailerYoutube: String? = null,
)

/**
 * Sinopse, ficha, prints, artes e trailer de jogos, por fontes oficiais e sem chave nova:
 * - Steam (loja da Valve, sem chave): sinopse em português, ficha, Metacritic, modos, conquistas, prints,
 *   trailer, logo e arte larga. Só quando o nome bate e o jogo não é de console antigo (o "God of War" de PS2 não é o da Steam).
 * - Wikipédia em português (sem chave): resumo e a ficha da caixa de informações (desenvolvedora, plataforma…).
 * - SteamGridDB (só com chave): logo e arte larga quando a Steam não tem, mais artes e grids para a galeria.
 * Guardado por 7 dias.
 */
object InfoJogos {

    private const val VALIDADE = 7L * 24 * 60 * 60 * 1000

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "info_jogos_v4_${Idioma.tmdb()}.json")

    @Synchronized
    fun buscar(ctx: Context, nome: String, anos: IntRange?): DadosJogo? {
        val chave = nome.lowercase().trim() + "|" + (anos?.first ?: 0)
        val cache = try { JSONObject(arquivo(ctx).readText()) } catch (_: Exception) { JSONObject() }
        cache.optJSONObject(chave)?.let { c ->
            if (System.currentTimeMillis() - c.optLong("q") < VALIDADE) return ler(c)
        }
        val base = (if (anos == null || anos.first >= 2012) try { steam(nome) } catch (_: Exception) { null } else null)
            ?: try { wikipedia(nome) } catch (_: Exception) { null }
        val sgdb = try { steamGrid(nome, anos) } catch (_: Exception) { null }
        if (base == null && sgdb == null) return null
        // O trailer do YouTube (quando não há o da Steam) fica com o TrailersYoutube, que tem cache próprio.
        val d = DadosJogo(
            titulo = base?.titulo ?: nome,
            sinopse = base?.sinopse.orEmpty(),
            linha = base?.linha.orEmpty(),
            trailer = base?.trailer,
            fonte = listOfNotNull(base?.fonte, if (sgdb != null) "SteamGridDB" else null).joinToString(" · "),
            campos = base?.campos.orEmpty(),
            selos = base?.selos.orEmpty(),
            galeria = base?.galeria.orEmpty() + sgdb?.galeria.orEmpty(),
            // Sem chave primeiro (Steam); o SteamGridDB completa o que faltar.
            logo = base?.logo ?: sgdb?.logo,
            heroi = base?.heroi ?: sgdb?.heroi,
        )
        cache.put(chave, gravar(d))
        arquivo(ctx).writeText(cache.toString())
        return d
    }

    // ---------- Steam ----------

    private fun normalizar(t: String) = t.lowercase()
        .replace(Regex("[™®]"), "")
        .replace(Regex("\\bii\\b"), "2").replace(Regex("\\biii\\b"), "3").replace(Regex("\\biv\\b"), "4")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun lista(a: JSONArray?, campo: String? = null): List<String> =
        a?.let { (0 until it.length()).map { i -> if (campo == null) it.getString(i) else it.getJSONObject(i).optString(campo) } }.orEmpty()

    private fun steam(nome: String): DadosJogo? {
        val busca = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/storesearch/?term=" +
            URLEncoder.encode(nome, "UTF-8") + "&l=${Idioma.steam()}&cc=${Idioma.regiao()}"))
        val itens = busca.optJSONArray("items") ?: return null
        val alvo = normalizar(nome)
        val id = (0 until itens.length()).map { itens.getJSONObject(it) }
            .firstOrNull { normalizar(it.optString("name")) == alvo }?.optInt("id") ?: return null
        val raiz = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/appdetails?appids=$id&l=${Idioma.steam()}&cc=${Idioma.regiao()}"))
        val g = raiz.optJSONObject(id.toString())?.optJSONObject("data") ?: return null
        val sinopse = g.optString("short_description").replace(Regex("<[^>]+>"), "").trim()
        val devs = lista(g.optJSONArray("developers"))
        val pubs = lista(g.optJSONArray("publishers"))
        val generos = lista(g.optJSONArray("genres"), "description")
        val categorias = lista(g.optJSONArray("categories"), "description")
        val data = g.optJSONObject("release_date")?.optString("date").orEmpty()
        val plataformas = g.optJSONObject("platforms")?.let { p ->
            listOfNotNull(if (p.optBoolean("windows")) "Windows" else null, if (p.optBoolean("mac")) "macOS" else null,
                if (p.optBoolean("linux")) "Linux" else null)
        }.orEmpty()
        // A Steam põe uma nota depois de <br> ("*idiomas com suporte total de áudio"): fica só a lista.
        val idiomas = g.optString("supported_languages").substringBefore("<br").replace(Regex("<[^>]+>"), "").replace("*", "")
        val campos = listOfNotNull(
            devs.joinToString(", ").ifBlank { null }?.let { "Desenvolvedora" to it },
            pubs.joinToString(", ").ifBlank { null }?.let { "Publicadora" to it },
            data.ifBlank { null }?.let { "Lançamento" to it },
            generos.joinToString(", ").ifBlank { null }?.let { "Gêneros" to it },
            plataformas.joinToString(", ").ifBlank { null }?.let { "Plataformas (Steam)" to it },
            idiomas.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(8).joinToString(", ").ifBlank { null }
                ?.let { "Idiomas" to it },
        )
        val selos = ArrayList<String>()
        g.optJSONObject("metacritic")?.optInt("score")?.takeIf { it > 0 }?.let { selos += "Metacritic $it" }
        g.optString("required_age").toIntOrNull()?.takeIf { it > 0 }?.let { selos += "$it+" }
        for (c in listOf("Um jogador", "Multijogador", "Cooperativo", "Compat. total com controle", "Compat. parcial com controle")) {
            if (c in categorias) selos += c.replace("Compat. total com controle", "Controle").replace("Compat. parcial com controle", "Controle parcial")
        }
        if (idiomas.contains("Português", ignoreCase = true)) selos += "Português"
        g.optJSONObject("achievements")?.optInt("total")?.takeIf { it > 0 }?.let { selos += "$it conquistas" }
        val prints = g.optJSONArray("screenshots")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { s ->
                val mini = s.optString("path_thumbnail").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                Foto(mini, s.optString("path_full").ifBlank { mini }, "Print")
            }
        }.orEmpty()
        val filme = g.optJSONArray("movies")?.optJSONObject(0)
        val video = filme?.optString("hls_h264")?.takeIf { it.startsWith("http") }
            ?: filme?.optJSONObject("mp4")?.let { it.optString("max").ifBlank { it.optString("480") } }?.takeIf { it.startsWith("http") }
        val linha = listOfNotNull(data.ifBlank { null }, devs.firstOrNull(), generos.take(3).joinToString(", ").ifBlank { null })
            .joinToString("  ·  ")
        // Logo e arte larga direto do servidor de imagens da Steam (sem chave).
        return DadosJogo(g.optString("name", nome), sinopse, linha, video, "Steam", campos, selos, prints,
            logo = FontesLivres.steamLogo(id), heroi = FontesLivres.steamHeroi(id))
    }

    // ---------- Wikipédia ----------

    private fun wikipedia(nome: String): DadosJogo? {
        val busca = JSONObject(Tmdb.baixarTexto("https://${Idioma.wikipedia()}.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=6" +
            "&srsearch=" + URLEncoder.encode("$nome ${Idioma.palavraJogo()}", "UTF-8")))
        val resultados = busca.optJSONObject("query")?.optJSONArray("search") ?: return null
        if (resultados.length() == 0) return null
        val tokens = normalizar(nome).split(' ').filter { it.isNotEmpty() }
        val palavras = tokens.filter { it.length > 1 }.toSet()
        val numeros = tokens.filter { it.all(Char::isDigit) }.toSet()
        // O título da página precisa ter as palavras principais do nome e os MESMOS números de
        // sequência ("God of War III" não pode virar o "God of War" de 2018).
        val titulo = (0 until resultados.length()).map { resultados.getJSONObject(it).optString("title") }
            .firstOrNull { t ->
                val n = normalizar(t.substringBefore(" (")).split(' ').filter { it.isNotEmpty() }
                val nNumeros = n.filter { it.all(Char::isDigit) }.toSet()
                palavras.count { it in n } >= (palavras.size * 0.6f) && nNumeros == numeros
            }
            ?: return null
        val pagina = URLEncoder.encode(titulo.replace(' ', '_'), "UTF-8")
        val r = JSONObject(Tmdb.baixarTexto("https://${Idioma.wikipedia()}.wikipedia.org/api/rest_v1/page/summary/$pagina"))
        val resumo = r.optString("extract").ifBlank { return null }
        val desc = r.optString("description").replaceFirstChar { it.uppercase() }
        val campos = try { fichaWikipedia(pagina) } catch (_: Exception) { emptyList() }
        val capa = r.optJSONObject("originalimage")?.optString("source")?.takeIf { it.startsWith("http") }
        val galeria = listOfNotNull(capa?.let { Foto(r.optJSONObject("thumbnail")?.optString("source") ?: it, it, "Capa") })
        val selos = campos.firstOrNull { it.first == "Modos" }?.second?.split(", ").orEmpty()
        return DadosJogo(r.optString("title", nome), resumo, desc, null, "Wikipédia", campos, selos, galeria)
    }

    /** Lê a caixa de informações ({{Info/Jogo eletrônico}}) da página e limpa a marcação wiki. */
    private fun fichaWikipedia(pagina: String): List<Pair<String, String>> {
        val wt = JSONObject(Tmdb.baixarTexto("https://${Idioma.wikipedia()}.wikipedia.org/w/api.php?action=parse&format=json&prop=wikitext&section=0&page=$pagina"))
            .getJSONObject("parse").getJSONObject("wikitext").getString("*")
        val nomes = linkedMapOf(
            "desenvolvedora" to "Desenvolvedora", "publicadora" to "Publicadora", "lançamento" to "Lançamento",
            "gênero" to "Gêneros", "plataforma" to "Plataformas", "conversões" to "Conversões", "modos" to "Modos",
            "série" to "Série", "diretor" to "Direção", "compositor" to "Música", "motor" to "Motor",
        )
        val valores = HashMap<String, String>()
        for (l in wt.lines()) {
            val m = Regex("^\\s*\\|\\s*([^=|]+?)\\s*=\\s*(.*)$").find(l) ?: continue
            val chave = m.groupValues[1].lowercase()
            if (chave in nomes) limparWiki(m.groupValues[2]).takeIf { it.isNotBlank() }?.let { valores[chave] = it }
        }
        return nomes.mapNotNull { (k, rotulo) -> valores[k]?.let { rotulo to it } }
    }

    private fun limparWiki(v: String): String {
        var t = v
        // {{lançamento de jogo|AN|16 de março de 2010|EU|…}} → primeira data.
        Regex("\\{\\{\\s*lançamento de jogo\\|([^}]*)\\}\\}", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val partes = m.groupValues[1].split('|').map { it.trim() }
            val data = partes.firstOrNull { p -> p.any(Char::isDigit) } ?: ""
            t = t.replace(m.value, data)
        }
        t = t.replace(Regex("\\{\\{[^}]*\\}\\}"), "")
            .replace(Regex("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]"), "$1")
            .replace(Regex("\\[\\[([^\\]]*)\\]\\]"), "$1")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), ", ")
            .replace(Regex("<[^>]+>"), "")
            .replace("'''", "").replace("''", "")
            .replace(Regex("\\s*,\\s*,"), ",")
        return semBarras(t.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").take(140))
    }

    /**
     * Sobra de predefinição de várias linhas ("|PS3, Xbox 360|17 de setembro de 2013|PS4|…"): fica só a primeira
     * parte com um ano (a data); sem ano, as partes juntadas por vírgula.
     */
    private fun semBarras(v: String): String {
        if ('|' !in v) return v
        val partes = v.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return partes.firstOrNull { Regex("\\b\\d{4}\\b").containsMatchIn(it) } ?: partes.joinToString(", ")
    }

    // ---------- SteamGridDB: logo, heroes e grids ----------

    private class Grid(val logo: String?, val heroi: String?, val galeria: List<Foto>)

    private fun steamGrid(nome: String, anos: IntRange?): Grid? {
        if (!Capas.ativo()) return null
        val id = Capas.escolherJogo(nome, anos, false) ?: return null
        val api = "https://www.steamgriddb.com/api/v2"
        fun itens(url: String, max: Int): List<JSONObject> = try {
            val a = JSONObject(Capas.get2(url)).optJSONArray("data")
            if (a == null) emptyList() else (0 until minOf(a.length(), max)).map { a.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
        val logo = itens("$api/logos/game/$id?types=static&nsfw=false", 1).firstOrNull()?.optString("url")?.takeIf { it.startsWith("http") }
        val herois = itens("$api/heroes/game/$id?types=static&nsfw=false&humor=false", 6)
            .mapNotNull { o -> o.optString("url").takeIf { it.startsWith("http") }?.let { Foto(o.optString("thumb").ifBlank { it }, it, "Arte") } }
        val grids = itens("$api/grids/game/$id?dimensions=920x430,460x215&types=static&nsfw=false&humor=false", 6)
            .mapNotNull { o -> o.optString("url").takeIf { it.startsWith("http") }?.let { Foto(o.optString("thumb").ifBlank { it }, it, "Capa") } }
        return Grid(logo, herois.firstOrNull()?.grande, herois + grids)
    }

    // ---------- Cache ----------

    private fun gravar(d: DadosJogo): JSONObject = JSONObject()
        .put("q", System.currentTimeMillis()).put("t", d.titulo).put("s", d.sinopse).put("l", d.linha)
        .put("v", d.trailer ?: "").put("f", d.fonte).put("lg", d.logo ?: "").put("h", d.heroi ?: "")
        .put("yt", d.trailerYoutube ?: "")
        .put("c", JSONArray().apply { d.campos.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("e", JSONArray().apply { d.selos.forEach { put(it) } })
        .put("g", JSONArray().apply { d.galeria.forEach { put(JSONArray().put(it.mini).put(it.grande).put(it.tipo)) } })

    private fun ler(c: JSONObject): DadosJogo {
        val campos = c.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to semBarras(p.getString(1)) } } }.orEmpty()
        val selos = c.optJSONArray("e")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val galeria = c.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> Foto(p.getString(0), p.getString(1), p.optString(2)) } } }.orEmpty()
        return DadosJogo(c.optString("t"), c.optString("s"), c.optString("l"), c.optString("v").ifEmpty { null }, c.optString("f"),
            campos, selos, galeria, c.optString("lg").ifEmpty { null }, c.optString("h").ifEmpty { null },
            c.optString("yt").ifEmpty { null })
    }

    // ---------- Trailer ----------

    private const val LIMITE_TRAILERS = 150L * 1024 * 1024
    private const val PEDACOS = 15 // 15 × 3 s ≈ 45 s de trailer

    /**
     * Os trailers da Steam vêm em HLS com pedaços fMP4 e áudio separado, que o player nativo não
     * toca. Baixa só o vídeo (480p) dos primeiros ~45 s e junta init + pedaços num MP4 fragmentado
     * comum, que o MediaPlayer toca. Fica guardado (até 150 MB, apagando os mais antigos).
     */
    fun prepararTrailer(ctx: Context, master: String): File? = try {
        val pasta = File(ctx.cacheDir, "trailers").apply { mkdirs() }
        val f = File(pasta, "t" + Integer.toHexString(master.substringBefore('?').hashCode()) + ".mp4")
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis())
            f
        } else {
            val baseMaster = master.substringBefore('?').substringBeforeLast('/') + "/"
            val linhas = Tmdb.baixarTexto(master).lines()
            // Variantes de vídeo: a linha seguinte a cada #EXT-X-STREAM-INF. Prefere 480p.
            val variantes = linhas.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") }
                .mapNotNull { (i, info) -> linhas.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() }?.let { info to it } }
            val escolhida = (variantes.firstOrNull { "x480" in it.first } ?: variantes.lastOrNull())?.second
                ?: throw IllegalStateException("sem variante")
            val urlVariante = if (escolhida.startsWith("http")) escolhida else baseMaster + escolhida
            val baseVariante = urlVariante.substringBefore('?').substringBeforeLast('/') + "/"
            val playlist = Tmdb.baixarTexto(urlVariante).lines()
            val init = playlist.firstOrNull { it.startsWith("#EXT-X-MAP") }?.substringAfter("URI=\"")?.substringBefore('"')
            val pedacos = playlist.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(PEDACOS)
            if (pedacos.isEmpty()) throw IllegalStateException("sem pedaços")
            val tmp = File(pasta, f.name + ".tmp")
            tmp.outputStream().use { saida ->
                for (p in listOfNotNull(init) + pedacos) {
                    val url = if (p.startsWith("http")) p else baseVariante + p
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 15000
                    try {
                        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                        c.inputStream.use { it.copyTo(saida) }
                    } finally {
                        c.disconnect()
                    }
                }
            }
            tmp.renameTo(f)
            // Limite do cache de trailers: apaga os mais antigos.
            val todos = pasta.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.lastModified() }.orEmpty()
            var total = todos.sumOf { it.length() }
            for (x in todos) {
                if (total <= LIMITE_TRAILERS) break
                if (x == f) continue
                total -= x.length()
                x.delete()
            }
            f
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Áudio do trailer da Steam: vem num fluxo separado (#EXT-X-MEDIA TYPE=AUDIO). Baixa os mesmos
     * ~45 s num MP4 de áudio que o MediaPlayer toca junto com o vídeo. null se não houver áudio.
     */
    fun prepararAudio(ctx: Context, master: String): File? = try {
        val pasta = File(ctx.cacheDir, "trailers").apply { mkdirs() }
        val f = File(pasta, "a" + Integer.toHexString(master.substringBefore('?').hashCode()) + ".mp4")
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis())
            f
        } else {
            val baseMaster = master.substringBefore('?').substringBeforeLast('/') + "/"
            val uri = Tmdb.baixarTexto(master).lines()
                .firstOrNull { it.startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in it && "URI=\"" in it }
                ?.substringAfter("URI=\"")?.substringBefore('"') ?: throw IllegalStateException("sem áudio")
            val urlAudio = if (uri.startsWith("http")) uri else baseMaster + uri
            val base = urlAudio.substringBefore('?').substringBeforeLast('/') + "/"
            val playlist = Tmdb.baixarTexto(urlAudio).lines()
            val init = playlist.firstOrNull { it.startsWith("#EXT-X-MAP") }?.substringAfter("URI=\"")?.substringBefore('"')
            val pedacos = playlist.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(PEDACOS)
            if (pedacos.isEmpty()) throw IllegalStateException("sem pedaços")
            val tmp = File(pasta, f.name + ".tmp")
            tmp.outputStream().use { saida ->
                for (p in listOfNotNull(init) + pedacos) {
                    val c = java.net.URL(if (p.startsWith("http")) p else base + p).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 15000
                    try {
                        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                        c.inputStream.use { it.copyTo(saida) }
                    } finally {
                        c.disconnect()
                    }
                }
            }
            tmp.renameTo(f)
            f
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Som dos trailers: começa em 50% e sobe aos poucos até 100% (~6 s), como pedido.
 * Para o áudio da Steam, toca o arquivo de áudio num MediaPlayer próprio, junto com o vídeo.
 */
object SomTrailer {

    const val INICIAL = 0.5f
    const val SUBIDA_MS = 6000L

    fun ligado(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE).getBoolean("som_trailers", true)

    /** Anima o volume de 50% a 100%; [aplicar] recebe 0..1. Devolve o animador (para cancelar). */
    fun subir(aplicar: (Float) -> Unit): android.animation.ValueAnimator =
        android.animation.ValueAnimator.ofFloat(INICIAL, 1f).apply {
            duration = SUBIDA_MS
            addUpdateListener { aplicar(it.animatedValue as Float) }
            start()
        }

    /** Prepara um MediaPlayer só de áudio, em loop; [pronto] quando der para começar. */
    fun tocadorDeAudio(arquivo: File, pronto: (android.media.MediaPlayer) -> Unit): android.media.MediaPlayer? = try {
        android.media.MediaPlayer().apply {
            setDataSource(arquivo.path)
            isLooping = true
            setVolume(INICIAL, INICIAL)
            setOnPreparedListener { pronto(it) }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Tela de informações (botão Quadrado / "Informações do jogo"), no estilo das páginas de jogo dos
 * consoles: trailer (ou arte larga) no fundo; à esquerda logo do jogo, selos, sinopse, ficha e
 * botões; embaixo a galeria com prints de gameplay e artes do SteamGridDB (OK/toque mostra no fundo).
 */
class TelaInfoJogo(
    private val act: Activity,
    private val s: Float,
    private val nome: String,
    private val anos: IntRange?,
    private val arte: String?,
    private val aoJogar: () -> Unit,
    private val pacoteYoutube: String?,
    private val extras: List<Pair<String, String>> = emptyList(),
    /** Opções de edição do jogo (capa, fundo, nome, emulador…), no botão "Editar jogo". */
    private val opcoes: List<Pair<String, () -> Unit>> = emptyList(),
    /** Arte de fundo escolhida à mão: fica no lugar da arte larga automática. */
    private val fundoFixo: String? = null,
) {
    private val dialogo = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    private val ui = Handler(Looper.getMainLooper())
    private val trabalho = Executors.newFixedThreadPool(2)
    private lateinit var video: VideoView
    private lateinit var imagem: ImageView
    private lateinit var cabeca: FrameLayout
    private lateinit var titulo: TextView
    private lateinit var logo: ImageView
    private lateinit var linha: TextView
    private lateinit var selos: Fluxo
    private lateinit var sinopse: TextView
    private lateinit var ficha: LinearLayout
    private lateinit var fonte: TextView
    private lateinit var tituloGaleria: TextView
    private lateinit var tituloConquistas: TextView
    private lateinit var conquistas: LinearLayout
    private lateinit var galeria: LinearLayout
    private lateinit var botaoTrailer: TextView
    private var temTrailer = false
    private var audio: android.media.MediaPlayer? = null
    private var subida: android.animation.Animator? = null
    private val telaW get() = act.resources.displayMetrics.widthPixels
    /** Estilo PS4: véu azul, título em letra fina e botões com a moldura azul brilhante. */
    private val ps4 = Estilo.ps4(act)
    private val xbox = Estilo.xbox(act)
    private val estiloConsole = act.getSharedPreferences("nova_home", 0).getInt("layout_inicio", 0)
    private val corVeu get() = when (estiloConsole) {
        1 -> 0xFF123A86.toInt()
        2 -> 0xFF1E2124.toInt()
        3 -> 0xFF202020.toInt()
        4 -> 0xFF1B4D7A.toInt()
        5 -> 0xFF0B0C10.toInt()
        6 -> 0xFF111213.toInt()
        else -> Paleta.FUNDO
    }
    private val telaH get() = act.resources.displayMetrics.heightPixels

    fun mostrar() {
        val raiz = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        // Vídeo embaixo e a arte por cima; quando o trailer começa, a arte some e revela o vídeo.
        video = VideoView(act)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        imagem = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        raiz.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        // Véu: escuro à esquerda (texto) e embaixo (galeria), transparente à direita.
        raiz.addView(View(act).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Paleta.alfa(corVeu, 245), Paleta.alfa(corVeu, 190), Paleta.alfa(corVeu, 0))),
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Paleta.alfa(corVeu, 235))),
            ))
        }, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(107f), px(70f), px(107f), px(60f))
        }
        val larguraTexto = (telaW * 0.55f).toInt()

        // Logo do jogo (SteamGridDB) no lugar do título, quando existe.
        cabeca = FrameLayout(act)
        titulo = texto(60f, Color.WHITE, 500).apply {
            if (ps4) typeface = Ps4Desenho.leve
            text = nome
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        logo = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            visibility = View.GONE
        }
        cabeca.addView(titulo, FrameLayout.LayoutParams(-1, -2))
        cabeca.addView(logo, FrameLayout.LayoutParams(px(600f), px(170f)))
        col.addView(cabeca, LinearLayout.LayoutParams(larguraTexto, -2))

        linha = texto(26f, Paleta.TEXTO_SUAVE, 400)
        col.addView(linha, LinearLayout.LayoutParams(larguraTexto, -2).apply { topMargin = px(16f) })
        selos = Fluxo(act, px(10f))
        col.addView(selos, LinearLayout.LayoutParams(larguraTexto, -2).apply { topMargin = px(16f) })
        sinopse = texto(27f, Paleta.TEXTO, 400).apply {
            text = "Buscando informações…"
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
        }
        col.addView(sinopse, LinearLayout.LayoutParams(larguraTexto, -2).apply { topMargin = px(22f) })

        val botoes = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val jogar = botao("▶  Jogar") { dialogo.dismiss(); aoJogar() }
        botoes.addView(jogar)
        botaoTrailer = botao("🎬  Trailer") { voltarAoTrailer() }.apply { visibility = View.GONE }
        botoes.addView(botaoTrailer, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(18f) })
        if (pacoteYoutube != null) {
            botoes.addView(botao("Trailer no YouTube") {
                val q = URLEncoder.encode("$nome trailer", "UTF-8")
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
                    .setPackage(pacoteYoutube).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { act.startActivity(i) } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(18f) })
        }
        if (opcoes.isNotEmpty()) {
            botoes.addView(botao("✎  Editar jogo") { escolherOpcao() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(18f) })
        }
        botoes.addView(botao("Fechar") { dialogo.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(18f) })
        col.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(30f) })

        // Ficha em duas colunas (rótulo suave, valor claro).
        ficha = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ficha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(36f) })

        // Conquistas do RetroAchievements (só ROMs, com a conta da pessoa).
        tituloConquistas = texto(26f, Paleta.TEXTO, 500).apply {
            visibility = View.GONE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(tituloConquistas, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(34f) })
        conquistas = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(14f), px(40f), px(6f))
        }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(conquistas)
        }, LinearLayout.LayoutParams(-1, -2))

        tituloGaleria = texto(26f, Paleta.TEXTO, 500).apply {
            text = "Galeria"
            visibility = View.GONE
        }
        col.addView(tituloGaleria, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(34f) })
        galeria = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, px(14f), px(40f), px(14f))
        }
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            addView(galeria)
        }, LinearLayout.LayoutParams(-1, -2))

        fonte = texto(20f, Paleta.TEXTO_SUAVE, 400)
        col.addView(fonte, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })

        raiz.addView(ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }, FrameLayout.LayoutParams(-1, -1))

        // Dados que o launcher já tem (ex.: plataforma, emulador, arquivo da ROM).
        preencherFicha(extras)

        dialogo.setContentView(raiz)
        dialogo.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            insetsController?.hide(WindowInsets.Type.systemBars())
        }
        // Quadrado de novo também fecha (vai e volta, como no console).
        dialogo.setOnKeyListener { _, codigo, e ->
            if (codigo == KeyEvent.KEYCODE_BUTTON_X && e.action == KeyEvent.ACTION_UP) { dialogo.dismiss(); true } else false
        }
        dialogo.setOnDismissListener {
            ui.removeCallbacksAndMessages(null)
            try { video.stopPlayback() } catch (_: Exception) {}
            subida?.cancel()
            try { audio?.release() } catch (_: Exception) {}
            audio = null
            trabalho.shutdownNow()
        }
        dialogo.show()
        jogar.post { if (!jogar.isInTouchMode) jogar.requestFocus() }

        (fundoFixo ?: arte)?.let { mostrarNoFundo(it) }
        trabalho.execute {
            val d = InfoJogos.buscar(act, nome, anos)
            ui.post { if (dialogo.isShowing) aplicar(d) }
        }
        if (RetroConquistas.ativo() && RetroConquistas.temSuporte(anos)) trabalho.execute {
            val r = RetroConquistas.progresso(act, nome, anos)
            ui.post { if (dialogo.isShowing) mostrarConquistas(r) }
        }
    }

    /** Selo "🏆 12/45", título com os pontos e a fileira de medalhas (no foco, o nome e a descrição de cada uma). */
    private fun mostrarConquistas(r: ProgressoRetro?) {
        tituloConquistas.visibility = View.VISIBLE
        if (r == null || r.total == 0) {
            tituloConquistas.text = "RetroAchievements: este jogo não foi encontrado lá (ou ainda não tem conquistas)."
            tituloConquistas.setTextColor(Paleta.TEXTO_SUAVE)
            return
        }
        selos.addView(selo("🏆 ${r.ganhas}/${r.total}"), 0)
        val resumo = "Conquistas · ${r.ganhas} de ${r.total} · ${r.pontosGanhos} de ${r.pontos} pontos (RetroAchievements)"
        tituloConquistas.text = resumo
        val lado = px(96f)
        // Ganhas primeiro, como nos perfis do RetroAchievements.
        for (c in r.conquistas.sortedByDescending { it.ganha }) {
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isFocusable = true
                isClickable = true
                contentDescription = c.titulo
                alpha = if (c.ganha) 1f else 0.55f
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = 10f * s
                        setStroke((4f * s).toInt().coerceAtLeast(3), Color.WHITE)
                    })
                }
                setPadding(px(6f), px(6f), px(6f), px(6f))
                setOnFocusChangeListener { _, f ->
                    tituloConquistas.text = if (f) "${if (c.ganha) "✔" else "🔒"}  ${c.titulo} (${c.pontos} pts) — ${c.descricao}" else resumo
                }
                setOnClickListener { requestFocus() }
            }
            Imagens.carregar(act, c.medalha, lado, lado, inteira = true) { img.setImageBitmap(it) }
            conquistas.addView(img, LinearLayout.LayoutParams(lado, lado).apply { rightMargin = px(12f) })
        }
    }

    /**
     * "Editar jogo": lista das opções. A tela de informações fecha antes, porque cada opção abre a sua
     * própria tela (seletor de capa, lista de emuladores…) e o card muda por trás.
     */
    private fun escolherOpcao() {
        android.app.AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Editar \"$nome\"")
            .setItems(opcoes.map { it.first }.toTypedArray()) { _, i ->
                dialogo.dismiss()
                opcoes[i].second()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicar(d: DadosJogo?) {
        if (d == null) {
            sinopse.text = "Não encontrei informações de \"$nome\". Se o nome estiver diferente do oficial, renomeie o jogo e tente de novo."
            return
        }
        linha.text = d.linha
        sinopse.text = d.sinopse.ifBlank { "Sem sinopse." }
        sinopse.maxLines = if (d.campos.size > 4) 6 else 9
        d.selos.forEach { selos.addView(selo(it)) }
        preencherFicha(d.campos + extras)
        d.logo?.let { url ->
            Imagens.carregar(act, url, px(600f), px(170f), inteira = true) { b ->
                logo.setImageBitmap(b)
                logo.visibility = View.VISIBLE
                titulo.visibility = View.INVISIBLE
            }
        }
        // Sem trailer: a arte larga do SteamGridDB no fundo, se for melhor que a capa.
        if (d.trailer == null && fundoFixo == null) d.heroi?.let { mostrarNoFundo(it) }
        montarGaleria(d.galeria)
        fonte.text = "Informações: ${d.fonte}"
        val t = d.trailer ?: return
        fonte.text = "Informações: ${d.fonte}  ·  carregando trailer…"
        trabalho.execute {
            val arquivo = InfoJogos.prepararTrailer(act, t)
            val somArq = if (arquivo != null && SomTrailer.ligado(act)) InfoJogos.prepararAudio(act, t) else null
            ui.post {
                if (!dialogo.isShowing) return@post
                fonte.text = "Informações: ${d.fonte}" + if (arquivo != null) "  ·  trailer: Steam" else ""
                if (arquivo == null) return@post
                temTrailer = true
                // Trailer no fundo, repetindo; o som (arquivo separado) começa junto, em 50% e subindo.
                var videoPronto = false
                var audioPronto = somArq == null
                fun comecar() {
                    if (!videoPronto || !audioPronto || !dialogo.isShowing) return
                    imagem.animate().alpha(0f).setDuration(600).start()
                    video.start()
                    audio?.let { a ->
                        a.start()
                        subida = SomTrailer.subir { v -> try { a.setVolume(v, v) } catch (_: Exception) {} }
                    }
                }
                if (somArq != null) audio = SomTrailer.tocadorDeAudio(somArq) { audioPronto = true; comecar() }
                if (audio == null) audioPronto = true
                video.setVideoPath(arquivo.path)
                video.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    videoPronto = true
                    comecar()
                }
                video.setOnErrorListener { _, _, _ -> true }
            }
        }
    }

    private fun preencherFicha(campos: List<Pair<String, String>>) {
        ficha.removeAllViews()
        // Duas colunas de pares rótulo/valor.
        campos.distinctBy { it.first }.chunked(2).forEach { par ->
            val linhaFicha = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            par.forEach { (rotulo, valor) ->
                val caixa = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                caixa.addView(texto(20f, Paleta.TEXTO_SUAVE, 500).apply { text = rotulo.uppercase() })
                caixa.addView(texto(24f, Paleta.TEXTO, 400).apply {
                    text = valor
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
                linhaFicha.addView(caixa, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(40f) })
            }
            if (par.size == 1) linhaFicha.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            ficha.addView(linhaFicha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        }
    }

    private fun montarGaleria(fotos: List<Foto>) {
        if (fotos.isEmpty()) return
        tituloGaleria.text = "Galeria  ·  ${fotos.size} imagens"
        tituloGaleria.visibility = View.VISIBLE
        val w = px(320f)
        val h = px(180f)
        fotos.forEachIndexed { i, f ->
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.argb(40, 255, 255, 255))
                isFocusable = true
                isClickable = true
                defaultFocusHighlightEnabled = false
                contentDescription = f.tipo
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 12f * s)
                }
                clipToOutline = true
                foreground = StateListDrawable().apply {
                    val borda = GradientDrawable().apply {
                        cornerRadius = 12f * s
                        setStroke((4f * s).toInt().coerceAtLeast(3), Color.WHITE)
                    }
                    addState(intArrayOf(android.R.attr.state_focused), borda)
                    addState(intArrayOf(android.R.attr.state_pressed), borda)
                }
                setOnFocusChangeListener { v, foco -> v.animate().scaleX(if (foco) 1.08f else 1f).scaleY(if (foco) 1.08f else 1f).setDuration(150).start() }
                setOnClickListener { verFoto(f) }
            }
            Imagens.carregar(act, f.mini, w, h) { img.setImageBitmap(it) }
            galeria.addView(img, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = px(18f) })
        }
    }

    /** Mostra a imagem escolhida em tela cheia no fundo (pausa o trailer). */
    private fun verFoto(f: Foto) {
        if (temTrailer) {
            try { video.pause() } catch (_: Exception) {}
            try { audio?.pause() } catch (_: Exception) {}
            botaoTrailer.visibility = View.VISIBLE
        }
        mostrarNoFundo(f.grande)
    }

    private fun voltarAoTrailer() {
        botaoTrailer.visibility = View.GONE
        imagem.animate().alpha(0f).setDuration(400).start()
        try { video.start() } catch (_: Exception) {}
        try { audio?.start() } catch (_: Exception) {}
    }

    private fun mostrarNoFundo(url: String) {
        Imagens.carregar(act, url, telaW, telaH) { b ->
            imagem.setImageBitmap(b)
            imagem.animate().alpha(1f).setDuration(350).start()
        }
    }

    // ---------- Visual ----------

    private fun px(ref: Float) = (ref * s).toInt()

    private fun texto(tamanho: Float, cor: Int, peso: Int) = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tamanho * s)
        setTextColor(cor)
        typeface = Paleta.fonte(peso)
        includeFontPadding = false
        setShadowLayer(8f * s, 0f, 2f * s, Color.argb(150, 0, 0, 0))
    }

    private fun forma(cor: Int, raio: Float = 40f) = GradientDrawable().apply {
        cornerRadius = raio * s
        setColor(cor)
    }

    /** Selo pequeno (Metacritic 74, 18+, Multijogador…): Metacritic e idade ganham cor própria. */
    private fun selo(t: String) = texto(21f, Color.WHITE, 500).apply {
        text = t
        setShadowLayer(0f, 0f, 0f, 0)
        setPadding(px(16f), px(8f), px(16f), px(8f))
        val cor = when {
            t.startsWith("Metacritic") -> {
                val n = t.filter(Char::isDigit).toIntOrNull() ?: 0
                when { n >= 75 -> 0xFF2E7D32.toInt(); n >= 50 -> 0xFFF9A825.toInt(); else -> 0xFFC62828.toInt() }
            }
            t.endsWith("+") -> 0xFFB71C1C.toInt()
            else -> Color.argb(60, 255, 255, 255)
        }
        background = forma(cor, 10f)
    }

    /** Pílula: branca com texto escuro no foco (como as abas), translúcida fora dele. */
    private fun botao(rotulo: String, acao: () -> Unit) = texto(26f, Color.WHITE, 500).apply {
        text = rotulo
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        setShadowLayer(0f, 0f, 0f, 0)
        setPadding(px(34f), px(18f), px(34f), px(18f))
        val escuro = Visuais.painelEscuro(estiloConsole)
        if (escuro != null) {
            typeface = Ps4Desenho.normal
            background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(0xC0303030.toInt()),
                if (estiloConsole >= 5) SelecaoModerna(telaW / 1280f, if (estiloConsole == 5) 22f else 4f)
                else SelecaoBorda(telaW / 1280f, escuro.third)))
            setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
        } else if (xbox) {
            typeface = Ps4Desenho.normal
            background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(0xC0303438.toInt()), SelecaoXbox(telaW / 1280f, escuro = true)))
            setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
        } else if (ps4) {
            typeface = Ps4Desenho.normal
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                ColorDrawable(0xD00B1A3C.toInt()), SelecaoPs4(telaW / 1280f),
            ))
            setOnFocusChangeListener { _, f -> if (f) Sons.tocar(Sons.Evento.MOVER) }
        } else {
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), forma(Color.WHITE))
                addState(intArrayOf(android.R.attr.state_pressed), forma(Color.argb(90, 255, 255, 255)))
                addState(intArrayOf(), forma(Color.argb(55, 255, 255, 255)))
            }
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Paleta.TEXTO_ESCURO else Color.WHITE) }
        }
        setOnClickListener { acao() }
    }
}
