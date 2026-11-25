package com.gamelaunchertv.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jogos na nuvem, com catálogos públicos (sem chave):
 * - GeForce NOW: a lista de jogos suportados que a NVIDIA publica (nome, gêneros, loja). Abre pelo link de
 *   "deeplink" do GeForce NOW (o app, se instalado; senão o site).
 * - Xbox Cloud Gaming: a lista "Mais populares na nuvem" do catálogo do Game Pass (a mesma de xbox.com/play),
 *   com nome e artes pelo catálogo público da Microsoft Store. Abre pelo link de "jogar" de cada jogo.
 * As listas ficam guardadas por 1 dia.
 */
object Nuvem {

    private const val VALIDADE = 24L * 60 * 60 * 1000

    enum class Servico(val nome: String, val cor: Int, val pacotes: List<String>) {
        GFN("GeForce NOW", 0xFF2E7D07.toInt(), listOf("com.nvidia.geforcenow")),
        XBOX("Xbox Cloud Gaming", 0xFF107C10.toInt(), listOf("com.gamepass", "com.microsoft.xboxone.smartglass")),
    }

    /** Um jogo na nuvem: [capa] já vem da loja (Xbox); no GeForce NOW a capa sai da busca normal de capas. */
    class Jogo(
        val servico: Servico, val id: String, val nome: String, val generos: List<String>, val capa: String?, val otimizado: Boolean,
        /** Id do jogo na Steam (GeForce NOW): cruza com os seus jogos da Steam. */
        val steamId: Int? = null,
    ) {
        /** Id do card ("nuvem:gfn:100013311"): o mesmo no histórico e nas coleções. */
        val idCard get() = "nuvem:${servico.name.lowercase()}:$id"
    }

    // ---------- GeForce NOW ----------

    fun gfn(ctx: Context): List<Jogo> {
        val texto = baixarComCache(ctx, "nuvem_gfn.json",
            "https://static.nvidiagrid.net/supported-public-game-list/locales/gfnpc-${Idioma.lingua()}-${Idioma.regiao()}.json")
            ?: baixarComCache(ctx, "nuvem_gfn.json", "https://static.nvidiagrid.net/supported-public-game-list/locales/gfnpc-en-US.json")
            ?: return emptyList()
        val a = try { JSONArray(texto) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("status") != "AVAILABLE") return@mapNotNull null
            val generos = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty()
            Jogo(Servico.GFN, o.optLong("id").toString(), o.optString("title").replace(Regex("[®™]"), "").trim(), generos, null,
                o.optBoolean("isFullyOptimized"), Regex("/app/(\\d+)").find(o.optString("steamUrl"))?.groupValues?.get(1)?.toIntOrNull())
        }
    }

    // ---------- Xbox Cloud Gaming ----------

    /** "Mais populares na nuvem" (a primeira fileira de xbox.com/play). */
    private const val LISTA_XBOX = "6a589fa0-d493-472b-8e20-3813699d7056"

    /** "Todos os jogos" da nuvem (inclui os gratuitos, que não são do Game Pass). */
    private const val LISTA_XBOX_TODOS = "29a81209-df6f-41fd-a528-2ae6b91f719c"

    fun xbox(ctx: Context, limite: Int = 60): List<Jogo> = catalogoXbox(ctx, LISTA_XBOX, "nuvem_xbox.json", limite, false)

    /**
     * Jogos gratuitos no Xbox Cloud Gaming (Fortnite, Warzone, Genshin…): jogáveis só com a conta Microsoft, sem Game Pass.
     * Sai de "Todos os jogos" ficando só os que a loja vende por 0 (os do Game Pass e EA Play também aparecem com preço 0,
     * mas sem a opção de compra; esses ficam de fora).
     */
    fun xboxGratis(ctx: Context): List<Jogo> = catalogoXbox(ctx, LISTA_XBOX_TODOS, "nuvem_xbox_gratis.json", 1000, true)

    private fun gratuito(p: JSONObject): Boolean {
        val skus = p.optJSONArray("DisplaySkuAvailabilities") ?: return false
        val precos = (0 until skus.length()).map { skus.getJSONObject(it) }
            .filter { it.optJSONObject("Sku")?.optJSONObject("Properties")?.optBoolean("IsTrial") != true }
            .flatMap { s -> s.optJSONArray("Availabilities")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { a -> a.optJSONArray("Actions")?.let { ac -> (0 until ac.length()).any { ac.optString(it) == "Purchase" } } == true }
            .map { it.optJSONObject("OrderManagementData")?.optJSONObject("Price")?.optDouble("ListPrice", 0.0) ?: 0.0 }
        return precos.isNotEmpty() && precos.max() == 0.0
    }

    private fun catalogoXbox(ctx: Context, listaId: String, arquivo: String, limite: Int, soGratis: Boolean): List<Jogo> {
        val f = File(ctx.filesDir, arquivo)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE) lerXbox(f.readText())?.let { return it }
        val lista = try {
            val a = JSONArray(Tmdb.baixarTexto("https://catalog.gamepass.com/sigls/v2?id=$listaId&language=pt-BR&market=${Idioma.regiao()}"))
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.take(limite)
        } catch (_: Exception) {
            return if (f.exists()) lerXbox(f.readText()).orEmpty() else emptyList()
        }
        // Nome e artes no catálogo da Microsoft Store, 20 por pedido.
        val jogos = JSONArray()
        for (grupo in lista.chunked(20)) {
            val produtos = try {
                JSONObject(Tmdb.baixarTexto("https://displaycatalog.mp.microsoft.com/v7.0/products?market=${Idioma.regiao()}" +
                    "&languages=pt-BR,en-US&bigIds=" + grupo.joinToString(","))).optJSONArray("Products")
            } catch (_: Exception) { null } ?: continue
            val porId = (0 until produtos.length()).map { produtos.getJSONObject(it) }.associateBy { it.optString("ProductId") }
            for (id in grupo) {
                val p = porId[id] ?: continue
                if (soGratis && !gratuito(p)) continue
                val loc = p.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: continue
                val imgs = loc.optJSONArray("Images")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                fun img(tipo: String) = imgs.firstOrNull { it.optString("ImagePurpose") == tipo }?.optString("Uri")?.let { "https:$it" }
                val generos = p.optJSONObject("Properties")?.optString("Category")
                jogos.put(JSONObject().put("id", id).put("n", loc.optString("ProductTitle"))
                    .put("c", img("TitledHeroArt") ?: img("SuperHeroArt") ?: img("BoxArt") ?: "")
                    .put("g", generos ?: ""))
            }
        }
        if (jogos.length() > 0) f.writeText(jogos.toString())
        return lerXbox(jogos.toString()).orEmpty()
    }

    private fun lerXbox(texto: String): List<Jogo>? = try {
        val a = JSONArray(texto)
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Jogo(Servico.XBOX, o.getString("id"), o.optString("n").replace(Regex("[®™]"), "").trim(),
                listOfNotNull(o.optString("g").ifBlank { null }), o.optString("c").ifBlank { null }, false)
        }
    } catch (_: Exception) {
        null
    }

    // ---------- Abrir ----------

    /** Link que abre o jogo direto no serviço. */
    fun link(j: Jogo): String = when (j.servico) {
        Servico.GFN -> "https://play.geforcenow.com/mall/#/deeplink?game-id=${j.id}"
        Servico.XBOX -> {
            val slug = java.text.Normalizer.normalize(j.nome.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "jogo" }
            // Endereço final do jogo (o de www.xbox.com/play/launch redireciona para ele): é o que o app web abre.
            "https://play.xbox.com/stream/${j.id}/$slug"
        }
    }

    /** O app do serviço, se instalado (senão o link abre no navegador). */
    fun appInstalado(ctx: Context, s: Servico) = s.pacotes.firstOrNull { p ->
        try { ctx.packageManager.getPackageInfo(p, PackageManager.PackageInfoFlags.of(0)); true } catch (_: Exception) { false }
    }

    /**
     * App web instalado pelo navegador ("Instalar app" no Chrome) que abre [url]: roda em tela cheia, sem a barra do
     * navegador (o do Xbox, "Play XBOX", pede tela cheia). null = não instalado.
     */
    fun appWeb(ctx: Context, url: String): String? = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.ResolveInfoFlags.of(0)).map { it.activityInfo.packageName }.firstOrNull { "webapk" in it }
    } catch (_: Exception) {
        null
    }

    /** Página inicial do serviço, para instalar o app web pelo Chrome. */
    fun paginaInicial(s: Servico) = if (s == Servico.XBOX) "https://play.xbox.com/" else "https://play.geforcenow.com/"

    fun intent(ctx: Context, j: Jogo): Intent {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link(j))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Com o app web instalado, abre nele (tela cheia).
        appWeb(ctx, link(j))?.let { return i.setPackage(it) }
        // O GeForce NOW abre os próprios links; o Xbox Cloud roda no navegador (o app do Xbox só abre a loja).
        if (j.servico == Servico.GFN) appInstalado(ctx, j.servico)?.let { i.setPackage(it) }
        // O Xbox Cloud só roda nos navegadores aceitos pela Microsoft (no Brave a página fica em branco).
        if (j.servico == Servico.XBOX || appInstalado(ctx, j.servico) == null) navegadorXbox(ctx)?.let { i.setPackage(it) }
        return i
    }

    private val NAVEGADORES_XBOX = listOf("com.microsoft.emmx", "com.android.chrome", "com.sec.android.app.sbrowser")

    fun navegadorXbox(ctx: Context) = NAVEGADORES_XBOX.firstOrNull { p ->
        try { ctx.packageManager.getApplicationInfo(p, PackageManager.ApplicationInfoFlags.of(0)).enabled } catch (_: Exception) { false }
    }

    // ---------- Apps de streaming (Steam Link, Moonlight, PS Remote Play…) ----------

    /** Palavras do pacote de apps de jogar na nuvem ou pelo PC/console de casa. */
    private val PALAVRAS_STREAMING = listOf("steamlink", "moonlight", "remoteplay", "chiaki", "boosteroid", "geforcenow",
        "gamepass", "xboxone.smartglass", "parsec", "shadow", "rainway", "greenlight", "xcloud")

    fun ehStreaming(pacote: String) = PALAVRAS_STREAMING.any { pacote.lowercase().contains(it) }

    // ---------- Teste de conexão antes de jogar ----------

    class Conexao(val wifi: Boolean, val ghz5: Boolean?, val sinal: Int?, val pingMs: Long?) {
        /** Problemas que atrapalham jogo na nuvem (vazio = boa para jogar). */
        val problemas: List<String> get() = buildList {
            if (!wifi) add("Você não está no Wi-Fi (dados móveis gastam muito e oscilam).")
            if (ghz5 == false) add("Wi-Fi de 2,4 GHz: para jogar na nuvem, o de 5 GHz é bem melhor.")
            if (sinal != null && sinal < -70) add("Sinal do Wi-Fi fraco ($sinal dBm). Chegue mais perto do roteador.")
            if (pingMs == null) add("Não deu para medir a conexão com o serviço.")
            else if (pingMs > 80) add("Latência alta ($pingMs ms): o jogo pode responder com atraso.")
        }
        val resumo get() = listOfNotNull(
            if (wifi) "Wi-Fi" + (ghz5?.let { if (it) " 5 GHz" else " 2,4 GHz" } ?: "") else "Dados móveis",
            sinal?.let { "sinal $it dBm" }, pingMs?.let { "$it ms" },
        ).joinToString(" · ")
    }

    /** Mede a conexão (chamar fora da tela): tipo e força do Wi-Fi e o tempo de resposta do servidor do serviço. */
    fun testar(ctx: Context, s: Servico): Conexao {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = caps?.transportInfo as? android.net.wifi.WifiInfo
        val ghz5 = info?.frequency?.takeIf { it > 0 }?.let { it >= 4900 }
        val sinal = info?.rssi?.takeIf { it in -126..-1 }
        val host = if (s == Servico.GFN) "https://play.geforcenow.com/" else "https://www.xbox.com/favicon.ico"
        // A melhor de 3 medições (a primeira inclui abrir a conexão).
        val ping = (1..3).mapNotNull {
            try {
                val c = URL(host).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 3000
                c.readTimeout = 3000
                val ini = System.nanoTime()
                c.responseCode
                val ms = (System.nanoTime() - ini) / 1_000_000
                c.disconnect()
                ms
            } catch (_: Exception) {
                null
            }
        }.minOrNull()
        return Conexao(wifi, ghz5, sinal, ping)
    }

    // ---------- Ajudantes ----------

    private fun baixarComCache(ctx: Context, arquivo: String, url: String): String? {
        val f = File(ctx.filesDir, arquivo)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE) return f.readText()
        return try {
            Tmdb.baixarTexto(url).also { if (it.trimStart().startsWith("[")) f.writeText(it) }.takeIf { it.trimStart().startsWith("[") }
        } catch (_: Exception) {
            if (f.exists()) f.readText() else null
        }
    }
}
