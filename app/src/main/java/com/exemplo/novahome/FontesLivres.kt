package com.exemplo.novahome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Imagens de jogos por fontes que não pedem chave (usadas antes do SteamGridDB):
 * - Steam: capa (header), arte larga (library_hero) e logo, direto do servidor de imagens da loja.
 *   Só para nome idêntico na loja e jogos que não são de console antigo.
 * - libretro-thumbnails (o banco do RetroArch, pelo espelho no GitHub): tela-título, caixa e prints dos jogos
 *   de console, pelo nome da ROM.
 */
object FontesLivres {

    private const val CDN = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps"
    private const val LIBRETRO = "https://thumbnails.libretro.com"
    private const val VALIDADE_INDICE = 30L * 24 * 60 * 60 * 1000

    private fun normalizar(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[™®'’]"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    // ---------- Steam ----------

    /** Jogo de console antigo não usa a Steam ("God of War" de PS2 não é o de 2018). */
    fun steamServe(anos: IntRange?) = anos == null || anos.first >= 2012

    /** Id do jogo na loja da Steam com o mesmo nome; null se não há. Erro de rede sobe. */
    fun steamId(nome: String): Int? {
        val busca = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/storesearch/?term=" +
            URLEncoder.encode(nome, "UTF-8") + "&l=english&cc=${Idioma.regiao()}"))
        val itens = busca.optJSONArray("items") ?: return null
        val alvo = normalizar(nome)
        return (0 until itens.length()).map { itens.getJSONObject(it) }
            .firstOrNull { normalizar(it.optString("name")) == alvo }?.optInt("id")?.takeIf { it > 0 }
    }

    /**
     * Endereços das imagens do jogo na loja (API pública da Steam, sem chave). Jogos novos guardam cada
     * imagem num caminho com código ("…/4080220/ff6a…/header.jpg"), então o caminho simples dá 404.
     */
    private fun steamArte(id: Int, tipo: String): String? {
        val pedido = URLEncoder.encode("{\"ids\":[{\"appid\":$id}],\"context\":{\"country_code\":\"${Idioma.regiao()}\"}," +
            "\"data_request\":{\"include_assets\":true}}", "UTF-8")
        val a = JSONObject(Tmdb.baixarTexto("https://api.steampowered.com/IStoreBrowseService/GetItems/v1?input_json=$pedido"))
            .optJSONObject("response")?.optJSONArray("store_items")?.optJSONObject(0)?.optJSONObject("assets") ?: return null
        val arquivo = a.optString(tipo).ifBlank { return null }
        val formato = a.optString("asset_url_format").ifBlank { "steam/apps/$id/\${FILENAME}" }
        return "https://shared.akamai.steamstatic.com/store_item_assets/" + formato.replace("\${FILENAME}", arquivo)
    }

    // ---------- App Store da Apple (busca e consulta públicas do iTunes, sem chave) ----------

    /** Busca manual: apps/jogos da App Store do país com esse nome (id → nome). */
    fun appStorePesquisar(termo: String): List<Capas.Resultado> {
        val r = JSONObject(Tmdb.baixarTexto("https://itunes.apple.com/search?entity=software&limit=12&country=" +
            Idioma.regiao().lowercase() + "&term=" + URLEncoder.encode(termo, "UTF-8"))).optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { o ->
            o.optLong("trackId").takeIf { it > 0 }?.let { Capas.Resultado(it.toInt(), o.optString("trackName"), null) }
        }
    }

    /** Prints (iPhone e iPad) e o ícone grande do app, na maior resolução que o servidor de imagens da Apple entrega. */
    fun appStoreArtes(id: Int): List<Capas.Arte> {
        val o = JSONObject(Tmdb.baixarTexto("https://itunes.apple.com/lookup?country=${Idioma.regiao().lowercase()}&id=$id"))
            .optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
        fun grande(u: String) = u.replace(Regex("/\\d+x\\d+bb\\.(png|jpg)$"), "/1600x1600bb.jpg")
        fun lista(campo: String) = o.optJSONArray(campo)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val prints = (lista("ipadScreenshotUrls") + lista("screenshotUrls")).filter { it.startsWith("http") }
            .map { Capas.Arte(it, grande(it)) }
        val icone = o.optString("artworkUrl512").takeIf { it.startsWith("http") }?.let { Capas.Arte(it, it) }
        return prints + listOfNotNull(icone)
    }

    /** Busca manual (tela "Trocar capa"): jogos da loja com esse nome. */
    fun steamPesquisar(termo: String): List<Capas.Resultado> {
        val busca = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/storesearch/?term=" +
            URLEncoder.encode(termo, "UTF-8") + "&l=english&cc=${Idioma.regiao()}"))
        val itens = busca.optJSONArray("items") ?: return emptyList()
        return (0 until itens.length()).map { itens.getJSONObject(it) }.mapNotNull { o ->
            o.optInt("id").takeIf { it > 0 }?.let { Capas.Resultado(it, o.optString("name"), null) }
        }
    }

    /** Todas as artes deitadas do jogo na Steam: capa, capa grande, arte larga, fundo da página e prints. */
    fun steamArtes(id: Int): List<Capas.Arte> {
        val lista = ArrayList<Capas.Arte>()
        for (tipo in listOf("header", "main_capsule", "library_hero", "page_background")) {
            try { steamArte(id, tipo) } catch (_: Exception) { null }?.let { lista += Capas.Arte(it, it) }
        }
        try {
            val g = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/appdetails?appids=$id&filters=screenshots"))
                .optJSONObject(id.toString())?.optJSONObject("data")
            val a = g?.optJSONArray("screenshots")
            if (a != null) for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                val grande = s.optString("path_full").takeIf { it.startsWith("http") } ?: continue
                lista += Capas.Arte(s.optString("path_thumbnail").takeIf { it.startsWith("http") } ?: grande, grande)
            }
        } catch (_: Exception) {
        }
        return lista
    }

    /** Capa deitada (460×215, como as do SteamGridDB). */
    fun steamCapa(id: Int) = steamArte(id, "header")
    fun steamHeroi(id: Int) = try { steamArte(id, "library_hero") } catch (_: Exception) { null }
    /** Capa em pé da biblioteca da Steam (600×900). */
    fun steamCapaEmPe(id: Int) = steamArte(id, "library_capsule")
    /** A API não lista o logo: só os jogos com o caminho antigo têm (os outros ficam com o do SteamGridDB, se houver chave). */
    fun steamLogo(id: Int) = "$CDN/$id/logo.png".takeIf { existe(it) }

    // ---------- libretro-thumbnails ----------

    private val PASTAS = mapOf(
        Sistema.PS2 to listOf("Sony - PlayStation 2"),
        Sistema.PS1 to listOf("Sony - PlayStation"),
        Sistema.PSP to listOf("Sony - PlayStation Portable"),
        Sistema.GC to listOf("Nintendo - GameCube", "Nintendo - Wii"),
        Sistema.N3DS to listOf("Nintendo - Nintendo 3DS"),
        Sistema.NDS to listOf("Nintendo - Nintendo DS"),
        Sistema.GBA to listOf("Nintendo - Game Boy Advance"),
        Sistema.GB to listOf("Nintendo - Game Boy", "Nintendo - Game Boy Color"),
        Sistema.SNES to listOf("Nintendo - Super Nintendo Entertainment System"),
        Sistema.NES to listOf("Nintendo - Nintendo Entertainment System"),
        Sistema.N64 to listOf("Nintendo - Nintendo 64"),
        Sistema.MD to listOf("Sega - Mega Drive - Genesis"),
        Sistema.PS3 to listOf("Sony - PlayStation 3"),
        Sistema.PS4 to listOf("Sony - PlayStation 4"),
        Sistema.VITA to listOf("Sony - PlayStation Vita"),
        Sistema.X360 to listOf("Microsoft - Xbox 360"),
        Sistema.WIIU to listOf("Nintendo - Wii U"),
        Sistema.DC to listOf("Sega - Dreamcast"),
    )

    /** Região preferida quando o mesmo jogo tem várias versões. */
    private fun pesoRegiao(arquivo: String): Int {
        val r = arquivo.lowercase()
        return when {
            "(brazil" in r -> 0
            "(usa" in r -> 1
            "(world" in r -> 2
            "(europe" in r -> 3
            else -> 4
        } * 10 + (if ("(v" in r || "(rev" in r || "(beta" in r || "(demo" in r || "(proto" in r) 1 else 0)
    }

    /** Tipos de imagem do banco, na ordem das opções "Capa das ROMs" das configurações. */
    val TIPOS_CAPA = listOf("Named_Titles", "Named_Boxarts", "Named_Snaps")

    /**
     * Imagens do banco pelo espelho no GitHub (o mesmo que apps como o Daijishō usam): muito mais rápido que
     * o servidor do libretro (0,4 s contra até 24 s por imagem). Cada sistema é um repositório, "Sony_-_PlayStation_2".
     */
    private fun urlImagem(pasta: String, tipo: String, arquivo: String) =
        "https://raw.githubusercontent.com/libretro-thumbnails/${pasta.replace(' ', '_')}/master/$tipo/${caminho(arquivo)}.png"

    /**
     * Imagem do jogo de console [anos], pelo nome: [tipo] "Named_Titles" (tela-título), "Named_Boxarts" (caixa)
     * ou "Named_Snaps" (print). Se não houver desse tipo, a tela-título. "" = o banco não tem o jogo; null = erro de rede.
     */
    fun libretro(ctx: Context, nome: String, anos: IntRange?, tipo: String = "Named_Titles", reserva: Boolean = true): String? {
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return ""
        val alvo = normalizar(nome)
        for (pasta in PASTAS[sistema].orEmpty()) {
            val grupos = porNome(ctx, pasta) ?: return null
            // Várias regiões: a preferida que tenha esse tipo de imagem (nem toda versão tem caixa).
            val versoes = grupos[alvo]?.sortedBy { pesoRegiao(it) } ?: continue
            // A lista é das telas-título: elas sempre existem; os outros tipos precisam ser conferidos.
            if (tipo != "Named_Titles") versoes.take(3).firstNotNullOfOrNull { v -> urlImagem(pasta, tipo, v).takeIf { existe(it) } }?.let { return it }
            // [reserva] = false (capa em pé): sem caixa, fica sem, em vez de pôr a tela-título deitada num card em pé.
            return if (reserva) urlImagem(pasta, "Named_Titles", versoes.first()) else ""
        }
        return ""
    }

    /** Um jogo do banco libretro: [pasta] do sistema e [arquivo] (nome sem ".png", com a região). */
    class JogoLibretro(val pasta: String, val arquivo: String)

    /** Busca manual: jogos do sistema de [anos] que têm todas as palavras de [termo] (uma entrada por região). */
    fun libretroPesquisar(ctx: Context, termo: String, anos: IntRange?): List<JogoLibretro>? {
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return emptyList()
        val palavras = normalizar(termo).split(' ').filter { it.isNotEmpty() }
        if (palavras.isEmpty()) return emptyList()
        val achados = ArrayList<JogoLibretro>()
        for (pasta in PASTAS[sistema].orEmpty()) {
            val grupos = porNome(ctx, pasta) ?: return null
            grupos.filterKeys { base -> val b = base.split(' ').toSet(); palavras.all { it in b } }.values.flatten()
                .sortedWith(compareBy({ it.substringBefore(" (").length }, { pesoRegiao(it) }))
                .forEach { achados += JogoLibretro(pasta, it) }
        }
        return achados
    }

    /** Tela-título, print e capa da caixa (em pé) do jogo; só as que existem no banco. */
    fun libretroArtes(j: JogoLibretro): List<Capas.Arte> =
        listOf("Named_Titles", "Named_Snaps", "Named_Boxarts").map { urlImagem(j.pasta, it, j.arquivo) }
            .parallelStream().filter { existe(it) }.collect(java.util.stream.Collectors.toList()) // as três de uma vez, na ordem
            .map { Capas.Arte(it, it) }

    fun libretroServe(anos: IntRange?) = Sistema.entries.firstOrNull { it.anos == anos }?.let { PASTAS.containsKey(it) } == true

    /** Plataformas que o banco libretro tem (para escolher na busca manual de jogos que não são ROM). */
    fun sistemasLibretro() = Sistema.entries.filter { PASTAS.containsKey(it) }

    /** Listas já lidas nesta sessão (milhares de nomes: ler do disco a cada jogo custava caro). */
    private val indices = HashMap<String, List<String>>()
    private val agrupados = HashMap<String, Map<String, List<String>>>()

    /** Nome normalizado (sem a região) → arquivos daquele jogo; montado uma vez por sistema. */
    @Synchronized
    private fun porNome(ctx: Context, pasta: String): Map<String, List<String>>? {
        agrupados[pasta]?.let { return it }
        val nomes = indice(ctx, pasta) ?: return null
        return nomes.groupBy { normalizar(it.substringBefore(" (")) }.also { agrupados[pasta] = it }
    }

    /** Nomes (sem ".png") das telas-título de um sistema; a lista é grande, então fica guardada por 30 dias. */
    @Synchronized
    private fun indice(ctx: Context, pasta: String): List<String>? {
        indices[pasta]?.let { return it }
        val f = File(ctx.filesDir, "libretro_" + pasta.replace(Regex("[^A-Za-z0-9]+"), "_") + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE_INDICE) {
            try {
                val a = JSONArray(f.readText())
                return (0 until a.length()).map { a.getString(it) }.also { indices[pasta] = it }
            } catch (_: Exception) {
            }
        }
        // GitHub primeiro (2 pedidos, ~5 s; limite de 60 por hora sem chave); se falhar, a listagem do servidor do libretro (lenta).
        val nomes = (try { listaGithub(pasta) } catch (_: Exception) { null })
            ?: try { listaLibretro(pasta) } catch (_: Exception) { return null }
        if (nomes.isEmpty()) return null
        f.writeText(JSONArray(nomes).toString())
        indices[pasta] = nomes
        return nomes
    }

    /** Nomes das telas-título pela API pública do GitHub: a pasta raiz do repositório e depois a "Named_Titles". */
    private fun listaGithub(pasta: String): List<String>? {
        val api = "https://api.github.com/repos/libretro-thumbnails/${pasta.replace(' ', '_')}/git/trees"
        val raiz = JSONObject(Tmdb.baixarTexto("$api/master")).optJSONArray("tree") ?: return null
        val sha = (0 until raiz.length()).map { raiz.getJSONObject(it) }
            .firstOrNull { it.optString("path") == "Named_Titles" }?.optString("sha") ?: return null
        val t = JSONObject(Tmdb.baixarTexto("$api/$sha"))
        if (t.optBoolean("truncated")) return null
        val itens = t.optJSONArray("tree") ?: return null
        return (0 until itens.length()).map { itens.getJSONObject(it).optString("path") }
            .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
    }

    private fun listaLibretro(pasta: String): List<String> {
        val html = Tmdb.baixarTexto("$LIBRETRO/${caminho(pasta)}/Named_Titles/")
        return Regex("href=\"([^\"]+)\\.png\"").findAll(html)
            .map { java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }
            .distinct().toList()
    }

    // ---------- GameTDB (Wii/GameCube, Wii U, 3DS, DS, Switch, PS3: listas e capas abertas, sem chave) ----------

    /** Plataforma → (lista de títulos, pasta das capas em art.gametdb.com). */
    private val GAMETDB = mapOf(
        Sistema.GC to ("wiitdb" to "wii"), Sistema.WIIU to ("wiiutdb" to "wiiu"), Sistema.N3DS to ("3dstdb" to "3ds"),
        Sistema.NDS to ("dstdb" to "ds"), Sistema.SWITCH to ("switchtdb" to "switch"), Sistema.PS3 to ("ps3tdb" to "ps3"),
    )

    fun sistemasGametdb() = Sistema.entries.filter { GAMETDB.containsKey(it) }

    /** Um jogo do GameTDB: o nome e os ids de cada região (o mesmo jogo tem um id por país). */
    class JogoTdb(val sistema: Sistema, val titulo: String, val ids: List<String>)

    private val listasTdb = HashMap<Sistema, List<Pair<String, String>>>()

    /** (id, título) da plataforma, guardado por 30 dias ("RMCE01 = Mario Kart Wii"). */
    @Synchronized
    private fun listaTdb(ctx: Context, sistema: Sistema): List<Pair<String, String>>? {
        listasTdb[sistema]?.let { return it }
        val (lista, _) = GAMETDB[sistema] ?: return emptyList()
        val f = File(ctx.filesDir, "gametdb_$lista.txt")
        val texto = if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE_INDICE) f.readText() else try {
            Tmdb.baixarTexto("https://www.gametdb.com/$lista.txt?LANG=EN").also { f.writeText(it) }
        } catch (_: Exception) {
            if (f.exists()) f.readText() else return null
        }
        return texto.lines().drop(1).mapNotNull { l ->
            val i = l.indexOf(" = ")
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 3).trim()
        }.also { listasTdb[sistema] = it }
    }

    /** Busca manual: jogos da plataforma com todas as palavras de [termo] (um por título, com os ids das regiões). */
    fun gametdbPesquisar(ctx: Context, termo: String, sistema: Sistema): List<JogoTdb>? {
        val palavras = normalizar(termo).split(' ').filter { it.isNotEmpty() }
        if (palavras.isEmpty()) return emptyList()
        val lista = listaTdb(ctx, sistema) ?: return null
        return lista.filter { (_, t) -> val n = normalizar(t).split(' ').toSet(); palavras.all { it in n } }
            .groupBy({ it.second }, { it.first })
            .map { (titulo, ids) -> JogoTdb(sistema, titulo, ids.sortedBy { pesoIdTdb(it) }) }
            .sortedBy { it.titulo.length }
    }

    /** Região pelo id (4ª letra nos de Wii, 5ª nos de Switch): EUA e Brasil primeiro. */
    private fun pesoIdTdb(id: String): Int {
        val r = if (id.length >= 6) id[3] else id.lastOrNull() ?: ' '
        return when (r) { 'E' -> 0; 'P', 'X', 'Y' -> 1; else -> 2 }
    }

    /** Capa, capa média, capa completa (frente e verso) e caixa 3D, na primeira região que tiver cada uma. */
    fun gametdbArtes(j: JogoTdb): List<Capas.Arte> {
        val pasta = GAMETDB[j.sistema]?.second ?: return emptyList()
        val ext = if (pasta == "wii" || pasta == "wiiu") "png" else "jpg"
        // Poucas combinações: cada uma é uma consulta ao servidor (a "cover" pequena e a "coverfull" comum repetiriam as outras).
        val regioes = listOf("US", "EN", "JA")
        val tipos = listOf("coverHQ", "coverM", "coverfullHQ", "cover3D")
        val id = j.ids.first()
        return tipos.parallelStream().map { tipo ->
            regioes.firstNotNullOfOrNull { r -> "https://art.gametdb.com/$pasta/$tipo/$r/$id.$ext".takeIf { existe(it) } }
        }.collect(java.util.stream.Collectors.toList()).filterNotNull().distinct().map { Capas.Arte(it, it) }
    }

    // ---------- Internet Archive (capas escaneadas por colecionadores, sem chave) ----------

    /**
     * Busca manual: itens de imagem com as palavras do nome no título e "cover", "box" ou "scan". Por palavras (todas
     * precisam aparecer), não a frase exata: "Assassins Creed" não acharia "Assassin's Creed".
     */
    fun archivePesquisar(termo: String): List<Capas.Resultado> {
        val vazias = setOf("the", "of", "and", "a", "an", "de", "do", "da", "e", "o")
        val palavras = normalizar(termo).split(' ').filter { it.length > 1 && it !in vazias }.ifEmpty { return emptyList() }
        val q = URLEncoder.encode("title:(${palavras.joinToString(" ")}) AND mediatype:(image) AND (cover OR box OR scan OR boxart)", "UTF-8")
        val r = JSONObject(Tmdb.baixarTexto("https://archive.org/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&rows=15&output=json"))
            .optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapIndexed { i, o ->
            // O id do item é texto: guarda no nome ("título|id") e usa a posição como número.
            Capas.Resultado(i, o.optString("title") + "|" + o.optString("identifier"), null)
        }
    }

    /** Imagens do item (até 12, sem os arquivos gigantes de 1200 dpi acima de 8 MB). */
    fun archiveArtes(identificador: String): List<Capas.Arte> {
        val m = JSONObject(Tmdb.baixarTexto("https://archive.org/metadata/" + URLEncoder.encode(identificador, "UTF-8")))
        val arquivos = m.optJSONArray("files") ?: return emptyList()
        return (0 until arquivos.length()).map { arquivos.getJSONObject(it) }.filter { f ->
            val nome = f.optString("name").lowercase()
            (nome.endsWith(".jpg") || nome.endsWith(".jpeg") || nome.endsWith(".png")) && !nome.contains("_thumb") &&
                (f.optString("size").toLongOrNull() ?: 0) in 1..8_000_000
        }.take(12).map { f ->
            val url = "https://archive.org/download/" + caminho(identificador) + "/" + f.optString("name").split('/').joinToString("/") { caminho(it) }
            Capas.Arte(url, url)
        }
    }

    // ---------- Wikidata e Wikipédia (logos de apps, sinopses de filmes) ----------

    private var ultimoWikidata = 0L

    /** Pedido à Wikidata, um por vez e com pausa: ela bloqueia quem pede rápido demais. */
    @Synchronized
    private fun wikidata(parametros: String): JSONObject {
        val espera = 700 - (System.currentTimeMillis() - ultimoWikidata)
        if (espera > 0) Thread.sleep(espera)
        ultimoWikidata = System.currentTimeMillis()
        return JSONObject(Tmdb.baixarTexto("https://www.wikidata.org/w/api.php?format=json&maxlag=5&$parametros"))
    }

    /** Item da Wikidata que tem a propriedade [prop] com o valor [valor] (ex.: P3418 = pacote da Play Store). */
    private fun itemPor(prop: String, valor: String): String? =
        wikidata("action=query&list=search&srlimit=1&srsearch=" + URLEncoder.encode("haswbstatement:$prop=$valor", "UTF-8"))
            .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.takeIf { it.startsWith("Q") }

    /**
     * Logo oficial do app pelo pacote da Play Store (Wikidata P3418 → logo P154, arquivo do Wikimedia Commons).
     * Vem com o prefixo "logo:" para o card desenhar o logo centralizado num banner, em vez de recortar.
     * "" = não tem; erro de rede sobe.
     */
    fun logoDoApp(pacote: String): String {
        val q = itemPor("P3418", pacote) ?: return ""
        val logos = wikidata("action=wbgetentities&props=claims&ids=$q").optJSONObject("entities")?.optJSONObject(q)
            ?.optJSONObject("claims")?.optJSONArray("P154") ?: return ""
        val lista = (0 until logos.length()).map { logos.getJSONObject(it) }
        // O logo atual: o marcado como preferido, senão um sem data de fim (o mais novo), senão o último.
        fun inicio(o: JSONObject) = o.optJSONObject("qualifiers")?.optJSONArray("P580")?.optJSONObject(0)
            ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time").orEmpty()
        val atual = lista.firstOrNull { it.optString("rank") == "preferred" }
            ?: lista.filter { it.optJSONObject("qualifiers")?.has("P582") != true }.maxByOrNull { inicio(it) }
            ?: lista.lastOrNull() ?: return ""
        val arquivo = atual.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifBlank { null } ?: return ""
        return "logo:https://commons.wikimedia.org/wiki/Special:FilePath/" + caminho(arquivo) + "?width=640"
    }

    /**
     * Título e sinopse de um filme/série no idioma do aparelho, sem chave: IMDb → Wikidata → Wikipédia do idioma.
     * Guardado por 90 dias (achou ou não), para não pedir de novo os mesmos títulos todo dia.
     */
    @Synchronized
    fun traducao(ctx: Context, imdb: String): Pair<String, String>? {
        val f = File(ctx.filesDir, "traducoes_${Idioma.wikipedia()}_v1.json")
        val cache = try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
        cache.optJSONObject(imdb)?.let { c ->
            if (System.currentTimeMillis() - c.optLong("q") < 90L * 24 * 60 * 60 * 1000) {
                return c.optString("n").ifBlank { null }?.let { it to c.optString("s") }
            }
        }
        val achado = try {
            val wiki = "${Idioma.wikipedia()}wiki"
            val q = itemPor("P345", imdb)
            val pagina = q?.let {
                wikidata("action=wbgetentities&props=sitelinks&sitefilter=$wiki&ids=$it").optJSONObject("entities")
                    ?.optJSONObject(it)?.optJSONObject("sitelinks")?.optJSONObject(wiki)?.optString("title")
            }
            pagina?.let {
                val r = JSONObject(Tmdb.baixarTexto("https://${Idioma.wikipedia()}.wikipedia.org/api/rest_v1/page/summary/" +
                    URLEncoder.encode(it.replace(' ', '_'), "UTF-8")))
                // "Duna (filme de 2021)" → "Duna".
                r.optString("title").substringBefore(" (").ifBlank { null }?.let { t -> t to r.optString("extract") }
            }
        } catch (_: Exception) {
            return null // rede: tenta de novo depois, sem guardar
        }
        cache.put(imdb, JSONObject().put("q", System.currentTimeMillis()).put("n", achado?.first ?: "").put("s", achado?.second ?: ""))
        f.writeText(cache.toString())
        return achado
    }

    /** Pedaço de URL com espaços e símbolos codificados. */
    private fun caminho(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    private fun existe(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 6000
        c.readTimeout = 6000
        try { c.responseCode == 200 } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }
}
