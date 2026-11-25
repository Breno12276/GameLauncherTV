package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Capas de jogos (de console e de Android) e de apps. Primeiro as fontes sem chave ([FontesLivres]:
 * Steam e libretro-thumbnails); se não tiverem, a API oficial do SteamGridDB, quando há chave
 * (a da pessoa, em Configurações, ou a embutida na versão pessoal). Do SteamGridDB vêm as "grids"
 * deitadas (920×430 ou 460×215) ou, sem elas, o banner largo ("hero").
 * Cada busca fica guardada: achou = não busca mais; não achou = tenta de novo só depois de 7 dias.
 */
object Capas {

    private const val API = "https://www.steamgriddb.com/api/v2"
    private const val NOVA_TENTATIVA = 7L * 24 * 60 * 60 * 1000
    private const val PAUSA = 250L

    fun ativo() = Chaves.steamGrid().isNotBlank()

    fun chave(nome: String) = nome.lowercase().trim()

    class Resultado(val id: Int, val nome: String, val ano: Int?)
    class Arte(val miniatura: String, val url: String)

    /** Busca manual (tela "Trocar capa"): jogos com esse nome no SteamGridDB. */
    fun pesquisar(termo: String): List<Resultado> {
        val r = JSONObject(get("$API/search/autocomplete/${URLEncoder.encode(termo, "UTF-8").replace("+", "%20")}"))
        val a = r.optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Resultado(o.getInt("id"), o.optString("name"), anoDe(o))
        }
    }

    /** Todas as artes deitadas de um jogo: grids 920×430 / 460×215 primeiro, depois os banners largos. */
    fun artes(id: Int): List<Arte> {
        val lista = ArrayList<Arte>()
        for (url in listOf(
            "$API/grids/game/$id?dimensions=920x430,460x215&types=static&nsfw=false",
            // Em pé (capa de biblioteca), para o formato "Em pé" da tela "Trocar capa".
            "$API/grids/game/$id?dimensions=600x900,342x482,660x930&types=static&nsfw=false",
            // Quadradas, para o formato "Quadrada" (temas PS4, PS5, Switch e Xbox Series).
            "$API/grids/game/$id?dimensions=1024x1024,512x512&types=static&nsfw=false",
            "$API/heroes/game/$id?types=static&nsfw=false",
        )) {
            val a = JSONObject(get(url)).optJSONArray("data") ?: continue
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val u = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                lista += Arte(o.optString("thumb").takeIf { it.startsWith("http") } ?: u, u)
            }
        }
        return lista
    }

    private fun anoDe(o: JSONObject) = o.optLong("release_date", 0).takeIf { it > 0 }
        ?.let { java.util.Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(java.util.Calendar.YEAR) }

    /**
     * Um pedido de capa. [anos]: época do console (jogos de emulador). [exato]: só aceita nome idêntico
     * (apps: "Agenda" ou "Store" não podem pegar a arte de um jogo qualquer).
     */
    class Pedido(
        val chave: String, val termo: String, val anos: IntRange? = null, val exato: Boolean = false, val pacote: String? = null,
        /** Capa em pé (caixa do jogo / capa de biblioteca), para "Formato das capas: Capa original". */
        val emPe: Boolean = false,
        /** Nome do arquivo da ROM, com a região: casa com a imagem do libretro como no Daijishō. */
        val arquivo: String = "",
        /** Capa quadrada (os blocos do PS4, PS5, Switch e Xbox Series); só o SteamGridDB tem. */
        val quadrada: Boolean = false,
    )

    fun chaveApp(termo: String) = "app|" + chave(termo)
    fun chavePoster(nome: String) = "poster|" + chave(nome)
    fun chaveQuadrada(nome: String) = "quadrada|" + chave(nome)

    /**
     * Devolve chave → URL da imagem; só busca o que não está no cache. [parcial] recebe o que já está
     * pronto enquanto o resto chega (no máximo a cada 3 s): as capas da Steam não esperam a lista do libretro.
     */
    @Synchronized
    fun buscar(ctx: Context, pedidos: List<Pedido>, parcial: ((Map<String, String>) -> Unit)? = null): Map<String, String> {
        val cache = lerCache(ctx)
        val agora = System.currentTimeMillis()
        var mudou = false
        val pendentes = pedidos.distinctBy { it.chave }.filter { p ->
            val antigo = cache[p.chave]
            antigo == null || (antigo.first.isEmpty() && agora - antigo.second >= NOVA_TENTATIVA)
        }
        fun prontas() = cache.mapNotNull { (k, v) -> v.first.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        // Sem chave primeiro, várias ao mesmo tempo (Steam e libretro aguentam), tratadas na ordem em que
        // terminam; o SteamGridDB depois, um por vez e com pausa, só para o que elas não têm.
        // Fonte preferida de cada pedido (global ou da plataforma). Quem prefere o SteamGridDB não passa pelas fontes
        // sem chave antes: é marcado e buscado no SteamGridDB primeiro, aqui embaixo.
        val PRIMEIRO_SGDB = "\u0000sgdb"
        val paralelo = Executors.newFixedThreadPool(6)
        val prontos = java.util.concurrent.ExecutorCompletionService<Pair<Pedido, String?>>(paralelo)
        pendentes.forEach { p ->
            val fonte = if (p.exato) FonteCapa.AUTOMATICO else fontePreferida(ctx, secaoDoPedido(p))
            prontos.submit {
                p to if (fonte == FonteCapa.STEAMGRIDDB && ativo()) PRIMEIRO_SGDB
                else try { semChave(ctx, p, fonte) } catch (_: Exception) { null }
            }
        }
        paralelo.shutdown()
        var restantes = pendentes.size
        var novidade = false
        var ultimoAviso = 0L
        while (restantes > 0) {
            // Espera a próxima que terminar; se nada chega em 1,5 s (lista grande do libretro), avisa o que já tem.
            val terminado = prontos.poll(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (terminado != null) {
                restantes--
                val (p, livre) = try { terminado.get() } catch (_: Exception) { null to null }
                var url = livre
                if (p != null && url == PRIMEIRO_SGDB) {
                    // SteamGridDB primeiro; sem a capa lá, as fontes sem chave na ordem de sempre.
                    url = try { procurar(p.termo, p.anos, p.exato, p.emPe, p.quadrada) } catch (_: Exception) { null }
                    if (url == "") url = try { semChave(ctx, p) } catch (_: Exception) { null }
                } else if (p != null && url == "" && ativo()) url = try { procurar(p.termo, p.anos, p.exato, p.emPe, p.quadrada) } catch (_: Exception) { null }
                // Falha de rede não conta como "não achou": tenta de novo na próxima abertura.
                if (p != null && url != null) {
                    cache[p.chave] = url to agora
                    mudou = true
                    if (url.isNotEmpty()) novidade = true
                }
            }
            if (parcial != null && novidade && restantes > 0 && (terminado == null || System.currentTimeMillis() - ultimoAviso > 3000)) {
                ultimoAviso = System.currentTimeMillis()
                novidade = false
                parcial(prontas())
            }
        }
        if (mudou) salvarCache(arquivoCache(ctx), cache)
        return prontas()
    }

    // ---------- Fonte preferida (Configurações › Jogos) ----------

    /** Fontes que a pessoa pode pôr em primeiro lugar. AUTOMATICO = a ordem de sempre (sem chave primeiro). */
    enum class FonteCapa(val rotulo: String) {
        AUTOMATICO("Automático (sem chave primeiro)"), STEAMGRIDDB("SteamGridDB"), STEAM("Steam"),
        LOJA("Loja do console (PlayStation, Xbox, eShop)"), LIBRETRO("libretro / Como o Daijishō"),
    }

    /** Seção da aba Jogos do pedido ("rom_PS2"; jogos Android e atalhos: "android"), para a escolha por plataforma. */
    fun secaoDoPedido(p: Pedido) = Sistema.entries.firstOrNull { it.anos == p.anos }?.let { "rom_${it.name}" } ?: "android"

    /** Fonte escolhida para a [secao]: a dela ("fonte_capa:<secao>") ou, sem escolha própria, a global ("fonte_capa"). */
    fun fontePreferida(ctx: Context, secao: String): FonteCapa {
        val p = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
        val i = p.getInt("fonte_capa:$secao", -1).takeIf { it >= 0 } ?: p.getInt("fonte_capa", 0)
        return FonteCapa.entries.getOrElse(i) { FonteCapa.AUTOMATICO }
    }

    /** A preferência mudou: as capas automáticas de [chaves] (ou todas) são buscadas de novo (as escolhidas à mão ficam). */
    @Synchronized
    fun esquecerAutomaticas(ctx: Context, chaves: Set<String>? = null) {
        val f = arquivoCache(ctx)
        salvarCache(f, if (chaves == null) HashMap() else lerCache(ctx, f).filterKeys { it !in chaves })
    }

    /**
     * Capa pelas fontes sem chave: "" = não têm; null = erro de rede. Apps: o logo oficial pela Wikidata.
     * [primeiro]: a fonte preferida vai na frente; as outras seguem na ordem de sempre.
     */
    private fun semChave(ctx: Context, p: Pedido, primeiro: FonteCapa = FonteCapa.AUTOMATICO): String? {
        if (p.exato) return p.pacote?.let { FontesLivres.logoDoApp(it) } ?: ""
        if (p.quadrada) return "" // fontes sem chave não têm capa quadrada
        val sistema = Sistema.entries.firstOrNull { it.anos == p.anos }
        // Cada passo: "" = não tem (segue para o próximo); null = erro de rede; URL = achou.
        val passos: List<Pair<FonteCapa, () -> String?>> = if (p.emPe) listOf(
            // Em pé: a caixa da plataforma (libretro pelo nome do arquivo, como o Daijishō; GameTDB no Switch e afins),
            // a capa em pé da loja e a de biblioteca da Steam (600×900). Nunca a tela-título deitada.
            FonteCapa.LIBRETRO to {
                if (sistema == null) "" else FontesLivres.libretro(ctx, p.termo, p.anos, "Named_Boxarts", reserva = false, arquivo = p.arquivo)
                    ?.ifEmpty { null } ?: FontesLivres.gametdbCapa(ctx, p.termo, sistema) ?: ""
            },
            FonteCapa.LOJA to { if (sistema == null) "" else try { FontesLivres.lojaEmPe(p.termo, sistema) } catch (_: Exception) { "" } },
            FonteCapa.STEAM to {
                if (!FontesLivres.steamServe(p.anos)) "" else FontesLivres.steamId(p.termo)?.let { FontesLivres.steamCapaEmPe(it) }.orEmpty()
            },
        ) else listOf(
            FonteCapa.STEAM to {
                if (!FontesLivres.steamServe(p.anos)) "" else FontesLivres.steamId(p.termo)?.let { FontesLivres.steamCapa(it) }.orEmpty()
            },
            // Banner oficial da loja do console antes da tela-título do libretro, a não ser que a pessoa tenha
            // escolhido outro tipo de imagem do libretro em "Capa das ROMs".
            FonteCapa.LOJA to {
                if (sistema == null || tipoCapaRoms(ctx) != FontesLivres.TIPOS_CAPA[0]) ""
                else try { FontesLivres.lojaBanner(p.termo, sistema) } catch (_: Exception) { "" }
            },
            FonteCapa.LIBRETRO to { if (p.anos == null) "" else FontesLivres.libretro(ctx, p.termo, p.anos, tipoCapaRoms(ctx), arquivo = p.arquivo) },
        )
        var erro = false
        for ((_, passo) in passos.sortedBy { if (it.first == primeiro) 0 else 1 }) {
            val r = try { passo() } catch (_: Exception) { null }
            if (r == null) erro = true else if (r.isNotEmpty()) return r
        }
        return if (erro) null else ""
    }

    /** "Capa das ROMs" escolhida nas configurações: tela-título (padrão, deitada como os cards), caixa ou print. */
    private fun tipoCapaRoms(ctx: Context) = FontesLivres.TIPOS_CAPA.getOrElse(
        ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE).getInt("capa_roms", 0)) { FontesLivres.TIPOS_CAPA[0] }

    /** Tipo de capa das ROMs mudou: as capas do libretro são buscadas de novo (as escolhidas à mão ficam). */
    @Synchronized
    fun esquecerLibretro(ctx: Context) {
        val f = arquivoCache(ctx)
        val c = lerCache(ctx, f)
        salvarCache(f, c.filterValues { "libretro" !in it.first })
    }

    /** Regra das lojas dos consoles mudou: capas e fundos vindos delas são buscados de novo (os escolhidos à mão ficam). */
    @Synchronized
    fun esquecerLojas(ctx: Context) {
        val lojas = listOf("playstation.net", "s-microsoft.com", "nintendo.com")
        for (f in listOf(arquivoCache(ctx), arquivoHerois(ctx))) {
            val c = lerCache(ctx, f)
            salvarCache(f, c.filterValues { v -> lojas.none { it in v.first } })
        }
    }

    /**
     * Arte larga ("hero", feita para fundo) do jogo, para o plano de fundo da aba Jogos.
     * Buscada só quando o jogo é selecionado e guardada à parte. null = sem hero (usa a capa do card).
     */
    @Synchronized
    fun heroi(ctx: Context, nome: String, anos: IntRange?): String? {
        val k = chave(nome)
        val cache = lerCache(ctx, arquivoHerois(ctx))
        val antigo = cache[k]
        val agora = System.currentTimeMillis()
        if (antigo != null && (antigo.first.isNotEmpty() || agora - antigo.second < NOVA_TENTATIVA)) {
            return antigo.first.ifEmpty { null }
        }
        val url = try {
            // Sem chave primeiro: arte larga da Steam ou print do jogo (libretro); depois o SteamGridDB.
            val sistema = Sistema.entries.firstOrNull { it.anos == anos }
            val livre = (if (FontesLivres.steamServe(anos)) FontesLivres.steamId(nome)?.let { FontesLivres.steamHeroi(it) } else null)
                ?: sistema?.let { s -> (try { FontesLivres.lojaBanner(nome, s) } catch (_: Exception) { "" }).ifEmpty { null } }
                ?: (if (anos != null) FontesLivres.libretro(ctx, nome, anos, "Named_Snaps") else null)?.ifEmpty { null }
            livre ?: if (!ativo()) "" else {
                val id = escolherJogo(nome, anos, false)
                if (id == null) "" else imagem("$API/heroes/game/$id?types=static&nsfw=false&humor=false") ?: ""
            }
        } catch (_: Exception) {
            return null // rede: tenta de novo na próxima seleção
        }
        cache[k] = url to agora
        salvarCache(arquivoHerois(ctx), cache)
        return url.ifEmpty { null }
    }

    /** "" = procurou e não achou; null = erro (rede, limite da API). */
    private fun procurar(nome: String, anos: IntRange?, exato: Boolean, emPe: Boolean = false, quadrada: Boolean = false): String? {
        val id = escolherJogo(nome, anos, exato) ?: return ""
        if (quadrada) return imagem("$API/grids/game/$id?dimensions=1024x1024,512x512&types=static&nsfw=false&humor=false") ?: ""
        // Em pé: as grids "de biblioteca" (600×900 e parecidas); sem elas, fica sem (o card mostra o banner inteiro).
        if (emPe) return imagem("$API/grids/game/$id?dimensions=600x900,342x482,660x930&types=static&nsfw=false&humor=false") ?: ""
        imagem("$API/grids/game/$id?dimensions=920x430,460x215&types=static&nsfw=false&humor=false")?.let { return it }
        imagem("$API/heroes/game/$id?types=static&nsfw=false&humor=false")?.let { return it }
        return ""
    }

    /** Id do jogo no SteamGridDB pelas regras de nome e época; null se nenhum resultado servir. */
    fun escolherJogo(nome: String, anos: IntRange?, exato: Boolean): Int? {
        for (termo in (if (exato) listOf(nome) else listOf(nome, simplificar(nome))).distinct()) {
            if (termo.isBlank()) continue
            val busca = JSONObject(get("$API/search/autocomplete/${URLEncoder.encode(termo, "UTF-8").replace("+", "%20")}"))
            Thread.sleep(PAUSA)
            val lista = busca.optJSONArray("data") ?: continue
            val resultados = (0 until minOf(lista.length(), 8)).map { lista.getJSONObject(it) }
            if (resultados.isEmpty()) continue
            // Prefere: mesmo nome + época do console; depois mesmo nome; depois o nome mais parecido
            // (pelo menos 60% das palavras). Sem nenhum parecido, fica sem capa: melhor que a capa errada.
            fun ano(o: JSONObject) = anoDe(o)
            val palavras = normalizar(termo).split(' ').filter { it.isNotEmpty() }.toSet()
            fun mesmoNome(o: JSONObject) = normalizar(o.optString("name")) == normalizar(termo)
            fun naEpoca(o: JSONObject) = anos == null || ano(o)?.let { it in anos } == true
            fun parecido(o: JSONObject): Float {
                val r = normalizar(o.optString("name")).split(' ').toSet()
                return if (palavras.isEmpty()) 0f else palavras.count { it in r }.toFloat() / palavras.size
            }
            val jogo = resultados.firstOrNull { mesmoNome(it) && naEpoca(it) }
                ?: resultados.firstOrNull { mesmoNome(it) }
                ?: resultados.filter { !exato && parecido(it) >= 0.6f }
                    .maxWithOrNull(compareBy<JSONObject>({ parecido(it) }, { if (naEpoca(it)) 1 else 0 }))
                ?: continue
            return jogo.getInt("id")
        }
        return null
    }

    internal fun get2(url: String) = get(url)

    private fun imagem(url: String): String? {
        val r = JSONObject(get(url))
        Thread.sleep(PAUSA)
        return r.optJSONArray("data")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
    }

    /** Ignora os sufixos que o SteamGridDB põe em apps: "Netflix (Website)", "Spotify (Program)". */
    private fun normalizar(t: String) = t.lowercase()
        .replace(Regex("\\s*\\((website|program|app|application|software)\\)"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun simplificar(nome: String) = nome
        .substringBefore(" - ")
        .replace(Regex("(?i)\\b(dub(lado|lagem)?|pt-?br|br|traduzido|tradu[cç][aã]o|mod|edition|edi[cç][aã]o|remix|v\\d+(\\.\\d+)*)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Authorization", "Bearer ${Chaves.steamGrid()}")
        c.setRequestProperty("User-Agent", "GameLauncherTV/1.2 (launcher pessoal)")
        try {
            // 404 na busca = nenhum resultado; trata como lista vazia.
            if (c.responseCode == 404) return "{\"success\":true,\"data\":[]}"
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Chave nova: o que ficou sem capa ("não achou") volta a ser procurado já na próxima busca. */
    @Synchronized
    fun esquecerFaltantes(ctx: Context) {
        for (f in listOf(arquivoCache(ctx), arquivoHerois(ctx))) {
            val c = lerCache(ctx, f)
            if (c.values.any { it.first.isEmpty() }) salvarCache(f, c.filterValues { it.first.isNotEmpty() })
        }
    }

    // ---------- Cache: nome → (url ou "" se não achou, quando buscou) ----------

    // v2: escolha mais exigente; o nome novo força refazer as buscas da v1 (algumas vieram trocadas).
    private fun arquivoCache(ctx: Context) = File(ctx.filesDir, "capas_sgdb_v2.json")
    private fun arquivoHerois(ctx: Context) = File(ctx.filesDir, "herois_sgdb_v1.json")

    private fun lerCache(ctx: Context, arquivo: File = arquivoCache(ctx)): MutableMap<String, Pair<String, Long>> = try {
        val o = JSONObject(arquivo.readText())
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            v.optString("u") to v.optLong("q")
        }.toMutableMap()
    } catch (_: Exception) {
        HashMap()
    }

    private fun salvarCache(arquivo: File, cache: Map<String, Pair<String, Long>>) {
        val o = JSONObject()
        cache.forEach { (k, v) -> o.put(k, JSONObject().put("u", v.first).put("q", v.second)) }
        arquivo.writeText(o.toString())
    }
}
