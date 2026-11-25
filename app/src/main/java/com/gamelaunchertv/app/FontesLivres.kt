package com.gamelaunchertv.app

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
        val lista = (0 until itens.length()).map { itens.getJSONObject(it) }
        // Nome idêntico; senão, o mesmo nome com só um sufixo de edição na loja ("Grand Theft Auto V Legacy",
        // "… Enhanced", "… Definitive Edition"): a loja renomeia jogos antigos e o card ficava sem capa.
        val jogo = lista.firstOrNull { normalizar(it.optString("name")) == alvo }
            ?: lista.firstOrNull { o ->
                val n = normalizar(o.optString("name"))
                n.startsWith("$alvo ") && n.removePrefix("$alvo ").split(' ').all { it in SUFIXOS_EDICAO }
            }
        return jogo?.optInt("id")?.takeIf { it > 0 }
    }

    /** Palavras que a Steam acrescenta ao nome de um jogo sem mudar o jogo. */
    private val SUFIXOS_EDICAO = setOf(
        "legacy", "enhanced", "edition", "definitive", "remastered", "complete", "goty", "game", "of", "the", "year",
        "deluxe", "ultimate", "standard", "classic", "anniversary", "hd", "redux", "directors", "director", "cut", "special",
    )

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

    // ---------- Lojas dos consoles (buscas públicas das lojas, sem chave): artes largas oficiais ----------

    /**
     * Um jogo numa loja: nome, plataformas e as artes, já vindas na busca. [banner]: a arte larga boa para card
     * deitado (null se a loja só tem fundo borrado); [emPe]: capa em pé da loja, se houver.
     */
    class JogoLoja(
        val nome: String, val plataformas: List<String>, val artes: List<Capas.Arte>, val banner: String?, val emPe: String? = null,
        /** Ano de lançamento na loja: separa o God of War de PS2 (2005) do de PS4 (2018), que têm o mesmo nome. */
        val ano: Int? = null,
    )

    /**
     * PlayStation Store (a API pública antiga "chihiro", que a loja ainda responde): PS4, PS3, Vita e clássicos
     * de PS1/PS2. Tipos de imagem: 12 = arte 1920×1080, 13 = 1280×720, 14 = em pé (PS3), 10 e 1 = quadradas.
     * Nos jogos de PS3 as artes largas são só o fundo borrado do tema.
     */
    fun psPesquisar(termo: String): List<JogoLoja> {
        val r = JSONObject(Tmdb.baixarTexto("https://store.playstation.com/store/api/chihiro/00_09_000/tumbler/US/en/999/" +
            caminho(termo) + "?suggested_size=12&mode=game"))
        val links = r.optJSONArray("links") ?: return emptyList()
        return (0 until links.length()).map { links.getJSONObject(it) }.mapNotNull { o ->
            val imgs = o.optJSONArray("images") ?: return@mapNotNull null
            val porTipo = (0 until imgs.length()).map { imgs.getJSONObject(it) }.associate { it.optInt("type") to it.optString("url") }
            val artes = listOf(12, 13, 14, 10, 1).mapNotNull { porTipo[it]?.takeIf { u -> u.startsWith("http") } }.map { Capas.Arte(it, it) }
            val plat = o.optJSONArray("playable_platform")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            // Arte larga boa só de PS4/PS5 (códigos CUSA/PPSA, ou o servidor novo "vulcan"); a de PS3 é o fundo borrado do tema.
            val banner = porTipo[12]?.takeIf { it.startsWith("http") && ("vulcan" in it || "/CUSA" in it || "/PPSA" in it) }
            if (artes.isEmpty()) null else JogoLoja(o.optString("name"), plat, artes, banner,
                ano = o.optString("release_date").take(4).toIntOrNull())
        }
    }

    /** Microsoft Store (catálogo público do Xbox): Xbox 360 retrocompatível, Xbox One/Series e PC. */
    fun xboxPesquisar(termo: String, exato: Boolean = false): List<JogoLoja> {
        fun sugerir(t: String) = JSONObject(Tmdb.baixarTexto("https://displaycatalog.mp.microsoft.com/v7.0/productFamilies/autosuggest" +
            "?market=US&languages=en-US&productFamilyNames=Games&query=" + caminho(t))).optJSONArray("Results")?.let { r ->
            (0 until r.length()).flatMap { i ->
                val p = r.getJSONObject(i).optJSONArray("Products") ?: return@flatMap emptyList<String>()
                (0 until p.length()).map { p.getJSONObject(it).optString("ProductId") }
            }
        }.orEmpty().filter { it.isNotBlank() }
        // A sugestão só acha nomes que começam igual: na busca manual, tenta de novo sem o subtítulo e com as 2 primeiras palavras.
        val tentativas = if (exato) listOf(termo) else listOf(termo, termo.substringBefore(" - ").substringBefore(":"),
            termo.split(' ').filter { it.isNotBlank() }.take(2).joinToString(" ")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val ids = tentativas.asSequence().map { sugerir(it) }.firstOrNull { it.isNotEmpty() }.orEmpty().distinct().take(10)
        if (ids.isEmpty()) return emptyList()
        val produtos = JSONObject(Tmdb.baixarTexto("https://displaycatalog.mp.microsoft.com/v7.0/products?market=US&languages=en-US&bigIds=" +
            ids.joinToString(","))).optJSONArray("Products") ?: return emptyList()
        // Com título primeiro (banner pronto), depois a arte sem título, a capa em pé, a quadrada e alguns prints.
        val ordem = listOf("TitledHeroArt", "SuperHeroArt", "Poster", "BoxArt", "Screenshot")
        return (0 until produtos.length()).map { produtos.getJSONObject(it) }.mapNotNull { p ->
            val loc = p.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: return@mapNotNull null
            val imgs = loc.optJSONArray("Images") ?: return@mapNotNull null
            val lista = (0 until imgs.length()).map { imgs.getJSONObject(it) }
                .filter { it.optString("ImagePurpose") in ordem && it.optString("Uri").startsWith("//") }
                .sortedBy { ordem.indexOf(it.optString("ImagePurpose")) }
            val artes = (lista.filter { it.optString("ImagePurpose") != "Screenshot" } + lista.filter { it.optString("ImagePurpose") == "Screenshot" }.take(4))
                .map { val u = "https:" + it.optString("Uri"); Capas.Arte("$u?w=640", u) }
            fun de(tipo: String) = lista.firstOrNull { it.optString("ImagePurpose") == tipo }?.let { "https:" + it.optString("Uri") }
            val ano = p.optJSONArray("MarketProperties")?.optJSONObject(0)?.optString("OriginalReleaseDate")?.take(4)?.toIntOrNull()
            if (artes.isEmpty()) null else JogoLoja(loc.optString("ProductTitle"), emptyList(), artes, de("TitledHeroArt") ?: de("SuperHeroArt"), de("Poster"), ano)
        }
    }

    /**
     * eShop da Nintendo (busca pública do site europeu): Switch, Wii U, 3DS e os clássicos relançados (NES, SNES,
     * N64, GBA, DS). A arte 2:1 vem em até 1600 px de largura.
     */
    fun nintendoPesquisar(termo: String): List<JogoLoja> {
        val r = JSONObject(Tmdb.baixarTexto("https://searching.nintendo-europe.com/en/select?fq=type:GAME&rows=12&wt=json" +
            "&fl=title,system_names_txt,image_url_h2x1_s,image_url_sq_s&q=" + URLEncoder.encode(termo, "UTF-8")))
        val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.mapNotNull { d ->
            val larga = d.optString("image_url_h2x1_s").takeIf { it.startsWith("http") }
            val artes = listOfNotNull(
                larga?.let { Capas.Arte(it, it.replace("image500w", "image1600w")) },
                d.optString("image_url_sq_s").takeIf { it.startsWith("http") }?.let { Capas.Arte(it, it) },
            )
            val plat = d.optJSONArray("system_names_txt")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (artes.isEmpty()) null else JogoLoja(d.optString("title"), plat, artes, larga?.replace("image500w", "image1600w"))
        }
    }

    /** Loja do console da plataforma (para o banner automático e a ordem das fontes em "Trocar capa"). */
    enum class Loja { PLAYSTATION, XBOX, NINTENDO }

    fun lojaDe(sistema: Sistema?): Loja? = when (sistema) {
        Sistema.PS1, Sistema.PS2, Sistema.PSP, Sistema.PS3, Sistema.PS4, Sistema.VITA -> Loja.PLAYSTATION
        Sistema.X360 -> Loja.XBOX
        Sistema.SWITCH, Sistema.WIIU, Sistema.N3DS, Sistema.NDS, Sistema.GBA, Sistema.SNES, Sistema.NES, Sistema.N64 -> Loja.NINTENDO
        else -> null
    }

    /** Como a eShop chama cada plataforma (o mesmo jogo existe em vários consoles). */
    private val NOMES_NINTENDO = mapOf(
        Sistema.SWITCH to listOf("Nintendo Switch"), Sistema.WIIU to listOf("Wii U"),
        Sistema.N3DS to listOf("Nintendo 3DS", "New Nintendo 3DS"), Sistema.NDS to listOf("Nintendo DS"),
        Sistema.GBA to listOf("Game Boy Advance"), Sistema.SNES to listOf("Super Nintendo Entertainment System"),
        Sistema.NES to listOf("Nintendo Entertainment System", "NES"), Sistema.N64 to listOf("Nintendo 64"),
    )

    /** Jogos da loja do console de [sistema] com o nome igual ao do jogo (sem "™", "®" ou " for Nintendo Switch"). */
    private fun naLoja(nome: String, sistema: Sistema): List<JogoLoja> {
        val alvo = chaveNome(nome)
        fun igual(t: String) = chaveNome(t.replace(Regex("\\s+for Nintendo Switch.*$", RegexOption.IGNORE_CASE), "")) == alvo
        // Mesmo nome em outra geração (remake, reboot): só o lançado na época do console da ROM.
        fun naEpoca(j: JogoLoja) = j.ano != null && j.ano in sistema.anos
        return when (lojaDe(sistema)) {
            Loja.PLAYSTATION -> psPesquisar(nome).filter { igual(it.nome) && naEpoca(it) }
            Loja.XBOX -> xboxPesquisar(nome, exato = true).filter { igual(it.nome) && naEpoca(it) }
            // O mesmo nome existe em vários consoles: só o da plataforma da ROM.
            Loja.NINTENDO -> nintendoPesquisar(nome).filter { j -> igual(j.nome) && j.plataformas.any { it in NOMES_NINTENDO[sistema].orEmpty() } }
            null -> emptyList()
        }
    }

    /** Banner automático (deitado) da loja do console. "" = a loja não tem; erro de rede sobe. */
    fun lojaBanner(nome: String, sistema: Sistema) = naLoja(nome, sistema).firstNotNullOfOrNull { it.banner }.orEmpty()

    /** Capa em pé da loja (Microsoft Store). "" = não tem; erro de rede sobe. */
    fun lojaEmPe(nome: String, sistema: Sistema) =
        if (lojaDe(sistema) != Loja.XBOX) "" else naLoja(nome, sistema).firstNotNullOfOrNull { it.emPe }.orEmpty()

    /** Busca manual (tela "Trocar capa"): jogos da loja com esse nome. */
    fun steamPesquisar(termo: String): List<Capas.Resultado> {
        val busca = JSONObject(Tmdb.baixarTexto("https://store.steampowered.com/api/storesearch/?term=" +
            URLEncoder.encode(termo, "UTF-8") + "&l=english&cc=${Idioma.regiao()}"))
        val itens = busca.optJSONArray("items") ?: return emptyList()
        return (0 until itens.length()).map { itens.getJSONObject(it) }.mapNotNull { o ->
            o.optInt("id").takeIf { it > 0 }?.let { Capas.Resultado(it, o.optString("name"), null) }
        }
    }

    /** Todas as artes do jogo na Steam: capa, capa grande, capa em pé da biblioteca, arte larga, fundo da página e prints. */
    fun steamArtes(id: Int): List<Capas.Arte> {
        val lista = ArrayList<Capas.Arte>()
        for (tipo in listOf("header", "main_capsule", "library_capsule", "library_hero", "page_background")) {
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

    /** Região preferida quando o mesmo jogo tem várias versões: a do arquivo da ROM ([preferida]), se houver. */
    private fun pesoRegiao(arquivo: String, preferida: String? = null): Int {
        val r = arquivo.lowercase()
        return when {
            preferida != null && "($preferida" in r -> -1
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
     * Como o Daijishō (e o próprio RetroArch) acham a imagem: o nome do arquivo da ROM sem a extensão, com os
     * caracteres que o banco troca por "_" ("Mario & Luigi" → "Mario _ Luigi"), igual ao nome da imagem.
     */
    private fun nomeLibretro(arquivo: String) = arquivo.substringBeforeLast('.').replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    /** Região do nome do arquivo ("Sonic (USA, Europe).md" → "usa"); null sem região. */
    private fun regiaoDe(arquivo: String) =
        Regex("\\(([^)]+)\\)").find(arquivo)?.groupValues?.get(1)?.substringBefore(',')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Nome para comparar, sem região e com o artigo no lugar: "Legend of Zelda, The - A Link to the Past"
     * (o jeito do banco) casa com "The Legend of Zelda - A Link to the Past".
     */
    private fun chaveNome(t: String): String {
        val base = t.substringBefore(" (").substringBefore(" [")
        val m = Regex("^(.+?), (The|A|An)(\\s-\\s.*)?$", RegexOption.IGNORE_CASE).find(base)
        return normalizar(if (m != null) "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}" else base)
    }

    /**
     * Imagem do jogo de console [anos]: [tipo] "Named_Titles" (tela-título), "Named_Boxarts" (caixa), "Named_Snaps"
     * (print) ou "Named_Logos". Como no Daijishō, primeiro o nome exato do [arquivo] da ROM (mesma região e versão);
     * depois o mesmo jogo pelo [nome], na região do arquivo se houver. Sem esse tipo, a tela-título (se [reserva]).
     * "" = o banco não tem o jogo; null = erro de rede.
     */
    fun libretro(
        ctx: Context, nome: String, anos: IntRange?, tipo: String = "Named_Titles", reserva: Boolean = true, arquivo: String = "",
    ): String? {
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return ""
        val exato = if (arquivo.isEmpty()) "" else nomeLibretro(arquivo)
        val preferida = regiaoDe(arquivo)
        val alvo = chaveNome(nome)
        for (pasta in PASTAS[sistema].orEmpty()) {
            val escolhido = exato.takeIf { it.isNotEmpty() && it in (conjunto(ctx, pasta, tipo) ?: return null) }
                ?: (porNome(ctx, pasta, tipo) ?: return null)[alvo]?.minBy { pesoRegiao(it, preferida) } ?: continue
            return resolver(urlImagem(pasta, tipo, escolhido)) ?: ""
        }
        // Nem todo jogo tem caixa: a tela-título no lugar; mas não numa capa em pé ([reserva] = false).
        if (reserva && tipo != "Named_Titles") return libretro(ctx, nome, anos, "Named_Titles", false, arquivo)
        return ""
    }

    /** Um jogo do banco libretro: [pasta] do sistema e [arquivo] (nome sem ".png", com a região). [exato]: é o nome do arquivo da ROM. */
    class JogoLibretro(val pasta: String, val arquivo: String, val exato: Boolean = false)

    /** Busca manual: jogos do sistema de [anos] que têm todas as palavras de [termo] (uma entrada por região). */
    fun libretroPesquisar(ctx: Context, termo: String, anos: IntRange?): List<JogoLibretro>? {
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return emptyList()
        val palavras = chaveNome(termo).split(' ').filter { it.isNotEmpty() }
        if (palavras.isEmpty()) return emptyList()
        val achados = ArrayList<JogoLibretro>()
        for (pasta in PASTAS[sistema].orEmpty()) {
            val grupos = porNome(ctx, pasta, "Named_Titles") ?: return null
            grupos.filterKeys { base -> val b = base.split(' ').toSet(); palavras.all { it in b } }.values.flatten()
                .sortedWith(compareBy({ it.substringBefore(" (").length }, { pesoRegiao(it) }))
                .forEach { achados += JogoLibretro(pasta, it) }
        }
        return achados
    }

    /**
     * Busca manual "Como o Daijishō": primeiro a imagem com o nome exato do [arquivo] da ROM (caixa ou tela-título),
     * depois as outras regiões do mesmo jogo e, por fim, os jogos com as palavras de [termo] (se a pessoa editou o nome).
     */
    fun daijishoPesquisar(ctx: Context, arquivo: String, termo: String, anos: IntRange?): List<JogoLibretro>? {
        val sistema = Sistema.entries.firstOrNull { it.anos == anos } ?: return emptyList()
        val exato = nomeLibretro(arquivo)
        val preferida = regiaoDe(arquivo)
        val achados = LinkedHashMap<String, JogoLibretro>()
        for (pasta in PASTAS[sistema].orEmpty()) for (tipo in listOf("Named_Boxarts", "Named_Titles")) {
            if (exato.isNotEmpty() && exato in (conjunto(ctx, pasta, tipo) ?: return null))
                achados.putIfAbsent("$pasta|$exato", JogoLibretro(pasta, exato, exato = true))
            (porNome(ctx, pasta, tipo) ?: return null)[chaveNome(termo)]?.sortedBy { pesoRegiao(it, preferida) }
                ?.forEach { achados.putIfAbsent("$pasta|$it", JogoLibretro(pasta, it)) }
        }
        libretroPesquisar(ctx, termo, anos)?.forEach { achados.putIfAbsent("${it.pasta}|${it.arquivo}", it) }
        return achados.values.toList()
    }

    /**
     * Artes do jogo que existem no banco, na ordem de [tipos]: tela-título, print e caixa (para cards deitados);
     * na busca "Como o Daijishō", caixa primeiro e o logo junto.
     */
    fun libretroArtes(j: JogoLibretro, tipos: List<String> = listOf("Named_Titles", "Named_Snaps", "Named_Boxarts")): List<Capas.Arte> =
        tipos.map { urlImagem(j.pasta, it, j.arquivo) }
            .parallelStream().map { resolvida(it) ?: "" }.collect(java.util.stream.Collectors.toList()) // todas de uma vez, na ordem
            .filter { it.isNotEmpty() }.distinct().map { Capas.Arte(it, it) }

    val TIPOS_DAIJISHO = listOf("Named_Boxarts", "Named_Titles", "Named_Snaps", "Named_Logos")

    fun libretroServe(anos: IntRange?) = Sistema.entries.firstOrNull { it.anos == anos }?.let { PASTAS.containsKey(it) } == true

    /** Plataformas que o banco libretro tem (para escolher na busca manual de jogos que não são ROM). */
    fun sistemasLibretro() = Sistema.entries.filter { PASTAS.containsKey(it) }

    /** Listas já lidas nesta sessão, por "pasta|tipo" (milhares de nomes: ler do disco a cada jogo custava caro). */
    private val indices = HashMap<String, List<String>>()
    private val agrupados = HashMap<String, Map<String, List<String>>>()
    private val conjuntos = HashMap<String, Set<String>>()
    /** Pastas da raiz de cada repositório (nome → sha), para listar vários tipos com um pedido a menos. */
    private val raizes = HashMap<String, Map<String, String>>()

    /** Nome comparável (sem a região) → arquivos daquele jogo; montado uma vez por sistema e tipo. */
    @Synchronized
    private fun porNome(ctx: Context, pasta: String, tipo: String): Map<String, List<String>>? {
        agrupados["$pasta|$tipo"]?.let { return it }
        val nomes = indice(ctx, pasta, tipo) ?: return null
        return nomes.groupBy { chaveNome(it) }.also { agrupados["$pasta|$tipo"] = it }
    }

    /** Os nomes como estão no banco, para conferir o nome exato do arquivo da ROM. */
    @Synchronized
    private fun conjunto(ctx: Context, pasta: String, tipo: String): Set<String>? {
        conjuntos["$pasta|$tipo"]?.let { return it }
        return (indice(ctx, pasta, tipo) ?: return null).toHashSet().also { conjuntos["$pasta|$tipo"] = it }
    }

    /** Nomes (sem ".png") das imagens de um tipo num sistema; a lista é grande, então fica guardada por 30 dias. */
    @Synchronized
    private fun indice(ctx: Context, pasta: String, tipo: String): List<String>? {
        val k = "$pasta|$tipo"
        indices[k]?.let { return it }
        // As telas-título mantêm o nome de arquivo antigo (a lista já baixada continua valendo).
        val f = File(ctx.filesDir, "libretro_" + pasta.replace(Regex("[^A-Za-z0-9]+"), "_") +
            (if (tipo == "Named_Titles") "" else "_$tipo") + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE_INDICE) {
            try {
                val a = JSONArray(f.readText())
                return (0 until a.length()).map { a.getString(it) }.also { indices[k] = it }
            } catch (_: Exception) {
            }
        }
        // GitHub primeiro (~5 s; limite de 60 pedidos por hora sem chave); se falhar, a listagem do servidor do libretro (lenta).
        val nomes = (try { listaGithub(pasta, tipo) } catch (_: Exception) { null })
            ?: try { listaLibretro(pasta, tipo) } catch (_: Exception) { return null }
        // Sem nenhuma tela-título é falha da listagem; sem caixas ou logos, o sistema simplesmente não tem.
        if (nomes.isEmpty() && tipo == "Named_Titles") return null
        f.writeText(JSONArray(nomes).toString())
        indices[k] = nomes
        return nomes
    }

    /** Nomes das imagens pela API pública do GitHub: a pasta raiz do repositório (uma vez) e depois a do [tipo]. */
    private fun listaGithub(pasta: String, tipo: String): List<String>? {
        val api = "https://api.github.com/repos/libretro-thumbnails/${pasta.replace(' ', '_')}/git/trees"
        val raiz = raizes[pasta] ?: run {
            val a = JSONObject(Tmdb.baixarTexto("$api/master")).optJSONArray("tree") ?: return null
            (0 until a.length()).map { a.getJSONObject(it) }.associate { it.optString("path") to it.optString("sha") }
                .also { raizes[pasta] = it }
        }
        val sha = raiz[tipo] ?: return emptyList() // o repositório não tem esse tipo de imagem
        val t = JSONObject(Tmdb.baixarTexto("$api/$sha"))
        if (t.optBoolean("truncated")) return null
        val itens = t.optJSONArray("tree") ?: return null
        return (0 until itens.length()).map { itens.getJSONObject(it).optString("path") }
            .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
    }

    private fun listaLibretro(pasta: String, tipo: String): List<String> {
        val html = Tmdb.baixarTexto("$LIBRETRO/${caminho(pasta)}/$tipo/")
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

    /**
     * Frente da caixa do jogo pelo nome (capas em pé automáticas e "Como o Daijishō" das plataformas sem libretro,
     * como o Switch): só título igual, para não pôr a caixa de outro jogo. "" = não tem; null = erro de rede.
     */
    fun gametdbCapa(ctx: Context, nome: String, sistema: Sistema): String? {
        val (_, pasta) = GAMETDB[sistema] ?: return ""
        val lista = listaTdb(ctx, sistema) ?: return null
        val alvo = chaveNome(nome)
        val ids = lista.filter { chaveNome(it.second) == alvo }.map { it.first }.sortedBy { pesoIdTdb(it) }
        val ext = if (pasta == "wii" || pasta == "wiiu") "png" else "jpg"
        for (id in ids.take(3)) for (tipo in listOf("coverHQ", "coverM")) for (r in listOf("US", "EN", "JA")) {
            "https://art.gametdb.com/$pasta/$tipo/$r/$id.$ext".takeIf { existe(it) }?.let { return it }
        }
        return ""
    }

    /** Busca "Como o Daijishō" nas plataformas sem libretro: o título igual ao nome do arquivo primeiro, depois as palavras. */
    fun gametdbPorArquivo(ctx: Context, nome: String, termo: String, sistema: Sistema): List<JogoTdb>? {
        val lista = gametdbPesquisar(ctx, termo, sistema) ?: return null
        val alvo = chaveNome(nome)
        return lista.sortedBy { if (chaveNome(it.titulo) == alvo) 0 else 1 }
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

    /**
     * Endereço final de uma imagem do banco libretro. Regiões repetidas são "atalhos" do Git: o GitHub devolve
     * só o nome do arquivo de destino ("God of War (Asia).png"), em texto. null = não existe; erro de rede sobe.
     */
    private fun resolver(url: String, saltos: Int = 3): String? {
        fun abrir(metodo: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 6000
            readTimeout = 6000
        }
        // HEAD primeiro: a imagem de verdade não é baixada aqui, só o texto do atalho.
        val h = abrir("HEAD")
        try {
            if (h.responseCode != 200) return null
            if (h.contentType?.startsWith("text/") != true || h.contentLength > 1024) return url
        } finally {
            h.disconnect()
        }
        val c = abrir("GET")
        val destino = try { c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
        if (saltos == 0 || !destino.endsWith(".png")) return null
        // O destino é relativo à pasta da imagem ("x.png" ou "../Named_Boxarts/x.png").
        val relativo = destino.split('/').joinToString("/") { if (it == "..") it else caminho(it) }
        return resolver(URL(URL(url.substringBeforeLast('/') + "/"), relativo).toString(), saltos - 1)
    }

    /** [resolver] para listas: sem rede ou sem a imagem, fica de fora. */
    private fun resolvida(url: String) = try { resolver(url) } catch (_: Exception) { null }
}
