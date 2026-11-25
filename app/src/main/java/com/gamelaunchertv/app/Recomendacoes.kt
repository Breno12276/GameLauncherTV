package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Um item de recomendação: [link] abre direto no app (Stremio, SmartTube); null = abre o app.
 * [youtube]: id do trailer (Stremio já traz); [tmdb]: "movie:123"/"tv:123" para buscar o trailer oficial.
 */
class ItemRec(
    val nome: String, val detalhe: String, val imagem: String?, val link: String?,
    val youtube: String? = null, val tmdb: String? = null,
)

/**
 * Fileiras extras do Início, só por fontes oficiais, cada uma guardada por no máximo 24 h:
 * - Stremio: catálogos públicos oficiais (Cinemeta), sem chave: populares, lançamentos do ano e por gênero.
 *   Título e sinopse em português pelo TMDB (com chave) ou, sem ela, pela Wikipédia do idioma (via Wikidata).
 * - Apple (iTunes): filmes em alta na loja do país, sem chave, com nome, sinopse e trailer em português.
 * - YouTube: vídeos em alta no Brasil pela YouTube Data API (com chave).
 * - Netflix: mais fileiras do TMDB (séries, filmes e mais bem avaliados no Brasil).
 */
object Recomendacoes {

    private const val VALIDADE = 24L * 60 * 60 * 1000
    private const val POR_FILEIRA = 12

    fun youtubeAtivo() = Chaves.youtube().isNotBlank()

    /**
     * Chave da fileira → itens. Usa o cache se tiver menos de 24 h; sem rede, fica com o cache antigo.
     * [parcial] recebe as fileiras já prontas a cada fileira nova (as do Stremio esperam traduções; as outras não precisam).
     */
    @Synchronized
    fun carregar(ctx: Context, stremio: Boolean, youtube: Boolean, netflix: Boolean,
                 parcial: ((Map<String, List<ItemRec>>) -> Unit)? = null): Map<String, List<ItemRec>> {
        val arq = File(ctx.filesDir, "recomendacoes_v2_${Idioma.tmdb()}.json")
        val cache = ler(arq)
        val pedidas = buildList {
            // A da Apple primeiro: já vem traduzida, então aparece logo.
            if (stremio) add("apple_filmes")
            if (stremio) {
                add("stremio_filmes"); add("stremio_series")
                add("stremio_lancamentos"); add("stremio_acao"); add("stremio_comedia"); add("stremio_animacao")
            }
            if (youtube && youtubeAtivo()) add("youtube")
            if (netflix && Tmdb.ativo()) { add("netflix_series"); add("netflix_filmes"); add("netflix_top") }
        }
        val agora = System.currentTimeMillis()
        val saida = HashMap<String, List<ItemRec>>()
        var mudou = false
        var avisou = false
        for (k in pedidas) {
            val c = cache[k]
            if (c != null && agora - c.first < VALIDADE) {
                saida[k] = c.second.take(POR_FILEIRA)
                continue
            }
            val novo = try { buscar(ctx, k) } catch (_: Exception) { null }
            if (!novo.isNullOrEmpty()) {
                saida[k] = novo
                cache[k] = agora to novo
                mudou = true
            } else if (c != null) {
                saida[k] = c.second.take(POR_FILEIRA)
            }
            // Avisa uma vez só, na primeira fileira nova que chegar pela internet: cada aviso remonta o Início
            // (e tira o foco do card), então o resto espera o fim. Do cache tudo fica pronto de uma vez.
            if (!novo.isNullOrEmpty() && !avisou && k != pedidas.last()) {
                avisou = true
                parcial?.invoke(HashMap(saida))
            }
        }
        if (mudou) salvar(arq, cache)
        return saida
    }

    private fun buscar(ctx: Context, k: String): List<ItemRec> = when (k) {
        "stremio_filmes" -> stremio(ctx, "movie")
        "stremio_series" -> stremio(ctx, "series")
        "stremio_lancamentos" -> stremio(ctx, "movie", "year/genre=${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}")
        // Por gênero (o catálogo "imdbRating" do Cinemeta não vem ordenado por nota, então não serve para "mais bem avaliados").
        "stremio_acao" -> stremio(ctx, "movie", "top/genre=Action")
        "stremio_comedia" -> stremio(ctx, "movie", "top/genre=Comedy")
        "stremio_animacao" -> stremio(ctx, "movie", "top/genre=Animation")
        "apple_filmes" -> appleFilmes()
        "youtube" -> youtube()
        "netflix_series" -> netflix("tv", "popularity.desc")
        "netflix_filmes" -> netflix("movie", "popularity.desc")
        "netflix_top" -> netflix("movie", "vote_average.desc&vote_count.gte=300")
        else -> emptyList()
    }

    /** [catalogo]: "top" (populares), "top/genre=Action" (populares do gênero) ou "year/genre=2026" (lançamentos do ano). */
    private fun stremio(ctx: Context, tipo: String, catalogo: String = "top"): List<ItemRec> {
        val metas = JSONObject(Tmdb.baixarTexto("https://v3-cinemeta.strem.io/catalog/$tipo/$catalogo.json")).getJSONArray("metas")
        return (0 until minOf(metas.length(), POR_FILEIRA)).mapNotNull { i ->
            val m = metas.getJSONObject(i)
            val imdb = m.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            var nome = m.optString("name")
            var sinopse = m.optString("description")
            // Título e sinopse em português, quando o TMDB tem.
            val tmdbId = m.optLong("moviedb_id", 0)
            if (Tmdb.ativo() && tmdbId > 0) {
                try {
                    val t = JSONObject(Tmdb.baixarTexto("https://api.themoviedb.org/3/${if (tipo == "movie") "movie" else "tv"}/$tmdbId" +
                        "?api_key=${Chaves.tmdb()}&language=${Idioma.tmdb()}"))
                    t.optString(if (tipo == "movie") "title" else "name").takeIf { it.isNotBlank() }?.let { nome = it }
                    t.optString("overview").takeIf { it.isNotBlank() }?.let { sinopse = it }
                } catch (_: Exception) {
                }
            } else if (Idioma.lingua() != "en") {
                // Sem chave: a página do filme na Wikipédia do idioma do aparelho.
                FontesLivres.traducao(ctx, imdb)?.let { (n, s) ->
                    nome = n
                    if (s.isNotBlank()) sinopse = s
                }
            }
            val fundo = m.optString("background").takeIf { it.startsWith("http") } ?: m.optString("poster").takeIf { it.startsWith("http") }
            // Trailer: o Cinemeta já traz o id do YouTube.
            val yt = m.optJSONArray("trailers")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("type") == "Trailer" }
                    ?: a.optJSONObject(0)
            }?.optString("source")?.takeIf { it.isNotBlank() }
            ItemRec(nome, sinopse, fundo, "stremio:///detail/$tipo/$imdb", yt,
                if (tmdbId > 0) "${if (tipo == "movie") "movie" else "tv"}:$tmdbId" else null)
        }
    }

    /**
     * Filmes em alta na loja da Apple do país do aparelho (feed público e consulta pública do iTunes, sem chave):
     * nome e sinopse no idioma da loja e trailer em MP4 direto ("mp4:…", tocado pelo player do Android).
     * Ao abrir, procura o filme no Stremio.
     */
    private fun appleFilmes(): List<ItemRec> {
        val pais = Idioma.regiao().lowercase()
        val feed = JSONObject(Tmdb.baixarTexto("https://itunes.apple.com/$pais/rss/topmovies/limit=$POR_FILEIRA/json"))
            .optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
        val ids = (0 until feed.length()).mapNotNull { feed.getJSONObject(it).optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id") }
        if (ids.isEmpty()) return emptyList()
        // Uma consulta para todos: sinopse completa, trailer e pôster grande.
        val r = JSONObject(Tmdb.baixarTexto("https://itunes.apple.com/lookup?country=$pais&id=" + ids.joinToString(","))).optJSONArray("results")
            ?: return emptyList()
        val porId = (0 until r.length()).map { r.getJSONObject(it) }.associateBy { it.optLong("trackId").toString() }
        return ids.mapNotNull { id ->
            val o = porId[id] ?: return@mapNotNull null
            val nome = o.optString("trackName").ifBlank { return@mapNotNull null }
            // O pôster vem em pé; o tamanho grande deixa o recorte do card nítido.
            val poster = o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.replace("100x100bb", "1280x1920bb")
            val trailer = o.optString("previewUrl").takeIf { it.startsWith("http") }?.let { "mp4:$it" }
            ItemRec(nome, o.optString("longDescription").ifBlank { o.optString("shortDescription") }, poster,
                "stremio:///search?search=" + java.net.URLEncoder.encode(nome, "UTF-8").replace("+", "%20"), trailer)
        }
    }

    private fun youtube(): List<ItemRec> {
        val r = JSONObject(Tmdb.baixarTexto("https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular" +
            "&regionCode=${Idioma.regiao()}&hl=${Idioma.lingua()}&maxResults=$POR_FILEIRA&key=${Chaves.youtube()}"))
        val itens = r.optJSONArray("items") ?: return emptyList()
        return (0 until itens.length()).mapNotNull { i ->
            val v = itens.getJSONObject(i)
            val s = v.optJSONObject("snippet") ?: return@mapNotNull null
            val miniaturas = s.optJSONObject("thumbnails")
            val img = listOf("maxres", "standard", "high", "medium").firstNotNullOfOrNull {
                miniaturas?.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") }
            }
            ItemRec(s.optString("title"), s.optString("channelTitle"), img, "https://www.youtube.com/watch?v=${v.optString("id")}")
        }
    }

    private fun netflix(tipo: String, ordem: String): List<ItemRec> {
        val url = "https://api.themoviedb.org/3/discover/$tipo?api_key=${Chaves.tmdb()}" +
            "&language=${Idioma.tmdb()}&watch_region=BR&with_watch_providers=8&sort_by=$ordem&page=1"
        val r = JSONObject(Tmdb.baixarTexto(url)).getJSONArray("results")
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            val fundo = o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val nome = o.optString(if (tipo == "movie") "title" else "name").ifBlank { return@mapNotNull null }
            ItemRec(nome, o.optString("overview"), Tmdb.urlImagem(fundo, "w780"), null, null, "$tipo:${o.optLong("id")}")
        }.take(POR_FILEIRA)
    }

    // ---------- Cache ----------

    private fun ler(arq: File): MutableMap<String, Pair<Long, List<ItemRec>>> = try {
        val o = JSONObject(arq.readText())
        o.keys().asSequence().associateWith { k ->
            val f = o.getJSONObject(k)
            val a = f.getJSONArray("l")
            f.getLong("q") to (0 until a.length()).map {
                val x = a.getJSONObject(it)
                ItemRec(x.getString("n"), x.optString("d"), x.optString("i").ifEmpty { null }, x.optString("k").ifEmpty { null },
                    x.optString("y").ifEmpty { null }, x.optString("t").ifEmpty { null })
            }
        }.toMutableMap()
    } catch (_: Exception) {
        HashMap()
    }

    private fun salvar(arq: File, cache: Map<String, Pair<Long, List<ItemRec>>>) {
        val o = JSONObject()
        cache.forEach { (k, v) ->
            val a = JSONArray()
            v.second.forEach { a.put(JSONObject().put("n", it.nome).put("d", it.detalhe).put("i", it.imagem ?: "").put("k", it.link ?: "")
                .put("y", it.youtube ?: "").put("t", it.tmdb ?: "")) }
            o.put(k, JSONObject().put("q", v.first).put("l", a))
        }
        arq.writeText(o.toString())
    }
}
