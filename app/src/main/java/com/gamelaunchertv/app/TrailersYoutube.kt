package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Acha o id do trailer no YouTube, por fontes oficiais, priorizando o idioma do aparelho:
 * - Filmes e séries: lista de vídeos do TMDB (chave que já existe), idioma do sistema primeiro.
 * - Jogos sem trailer na Steam: busca da YouTube Data API com termos localizados (ex: "trailer dublado" para pt-BR).
 *   (O Dailymotion foi testado como fonte sem chave, mas o player dele recusa tocar fora de sites cadastrados.)
 * O resultado (achou ou não) fica guardado por 30 dias.
 */
object TrailersYoutube {

    private const val VALIDADE = 30L * 24 * 60 * 60 * 1000

    fun buscaAtiva() = Chaves.youtube().isNotBlank()

    // v5: idioma do aparelho primeiro em tudo (a v4 guardou trailers em inglês dos filmes do Stremio).
    private fun arquivo(ctx: Context) = File(ctx.filesDir, "trailers_youtube_v5.json")

    /**
     * Trailer de filme/série, sempre no idioma do aparelho primeiro:
     * 1. lista oficial do TMDB no idioma do aparelho;
     * 2. busca no YouTube pelo nome traduzido + "dublado/legendado" (só vale vídeo marcado no idioma);
     * 3. em inglês: lista do TMDB e, por fim, [reserva] (o trailer que o Stremio já traz, em inglês).
     * [tmdb] = "movie:123" ou "tv:123" (ou null, quando só há a reserva).
     * Fonte sem chave primeiro quando o idioma é o mesmo: em inglês, o trailer do Stremio vale antes do TMDB.
     */
    fun deFilme(ctx: Context, nome: String, tmdb: String?, reserva: String?): String? {
        val local = Idioma.lingua() != "en"
        if (tmdb == null || !Tmdb.ativo() || (!local && reserva != null)) return reserva
        return guardado(ctx, "filme|${Idioma.tmdb()}|$tmdb") {
            // Falha de rede no TMDB sobe (não guarda nada e tenta de novo depois); a do YouTube (cota) só pula a etapa.
            (if (local) tmdbVideos(tmdb, Idioma.tmdb()) else null)
                ?: (if (local && buscaAtiva()) try { filmeNoYoutube(nome) } catch (_: Exception) { null } else null)
                ?: tmdbVideos(tmdb, "en-US")
                ?: reserva
        }
    }

    /** Trailer oficial > trailer > teaser da lista do TMDB naquele idioma. */
    private fun tmdbVideos(tmdb: String, lingua: String): String? {
        val (tipo, id) = tmdb.split(':', limit = 2).let { it[0] to it[1] }
        val r = JSONObject(Tmdb.baixarTexto("https://api.themoviedb.org/3/$tipo/$id/videos" +
            "?api_key=${Chaves.tmdb()}&language=$lingua")).optJSONArray("results") ?: return null
        val videos = (0 until r.length()).map { r.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        return (videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: videos.firstOrNull { it.optString("type") == "Trailer" }
            ?: videos.firstOrNull { it.optString("type") == "Teaser" })?.optString("key")?.takeIf { it.isNotBlank() }
    }

    /** Vídeos de filme que não são o trailer (crítica, reação, cena solta…). */
    private val PROIBIDAS_FILME = setOf(
        "review", "critica", "analise", "reaction", "react", "reacao", "reagindo", "resumo", "recap", "explicado",
        "explained", "teoria", "teorias", "ending", "final", "cena", "cenas", "clip", "bastidores", "making",
        "entrevista", "interview", "fan", "fanmade", "concept", "parody", "parodia", "shorts", "live", "podcast",
    )

    /** Busca no YouTube pelo nome traduzido; só aceita vídeo marcado no idioma do aparelho (título ou canal). */
    private fun filmeNoYoutube(nome: String): String? {
        val doNome = palavras(nome)
        if (doNome.isEmpty()) return null
        val marcas = Idioma.marcasNoTitulo().flatMap { palavras(it) }.toSet()
        val melhor = pesquisar(Idioma.buscaTrailer(nome)).mapIndexedNotNull { i, (id, titulo, canal) ->
            if (frase(titulo, doNome) == null) return@mapIndexedNotNull null
            val nomeSet = doNome.toSet()
            if (titulo.any { it in PROIBIDAS_FILME && it !in nomeSet }) return@mapIndexedNotNull null
            // Outro número de sequência ("Duna: Parte 2" para "Duna") é outro filme.
            if (titulo.any { it.length <= 3 && it.all(Char::isDigit) && it !in nomeSet && it != "1" }) return@mapIndexedNotNull null
            val noTitulo = titulo.any { it in marcas }
            if (!noTitulo && !Idioma.canalLocal(canal)) return@mapIndexedNotNull null
            var nota = (25 - i) / 5
            if ("trailer" in titulo) nota += 10
            if ("oficial" in titulo || "official" in titulo) nota += 8
            if (Idioma.canalLocal(canal)) nota += 10
            if (noTitulo) nota += 5
            Candidato(id, nota)
        }.maxByOrNull { it.nota }
        return melhor?.id
    }

    /** Resultados da busca do YouTube: (id, palavras do título, canal em minúsculas). */
    private fun pesquisar(q: String): List<Triple<String, List<String>, String>> {
        val params = "part=snippet&type=video&videoEmbeddable=true&maxResults=25&q=${URLEncoder.encode(q, "UTF-8")}" +
            "&relevanceLanguage=${Idioma.lingua()}&regionCode=${Idioma.regiao()}&key=${Chaves.youtube()}"
        val items = JSONObject(Tmdb.baixarTexto("https://www.googleapis.com/youtube/v3/search?$params")).optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val sn = item.optJSONObject("snippet") ?: return@mapNotNull null
            val id = item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(id, palavras(android.text.Html.fromHtml(sn.optString("title"), 0).toString()), sn.optString("channelTitle").lowercase())
        }
    }

    // ---------- Jogos: busca no YouTube e escolha do vídeo certo ----------

    // "x" fica de fora: no título quase sempre é "Xbox Series X", não o décimo jogo.
    private val ROMANOS = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")

    /** Palavras: minúsculas, sem acento nem apóstrofo, romanos viram números e nomes longos viram a sigla usada nos vídeos. */
    private fun palavras(t: String): List<String> {
        val s = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("['’`´]"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\bgrand theft auto\\b"), "gta")
            .replace(Regex("\\bpro evolution soccer\\b"), "pes")
        return s.split(' ').filter { it.isNotEmpty() }.map { ROMANOS[it] ?: it }
    }

    /** Palavras que podem vir logo depois do nome no título de um trailer de verdade. */
    private val GENERICAS = setOf(
        "trailer", "trailers", "official", "oficial", "launch", "lancamento", "gameplay", "reveal", "announce",
        "announcement", "anuncio", "cinematic", "cinematico", "teaser", "e3", "tgs", "gdc", "hd", "1080p", "720p",
        "60fps", "ps1", "ps2", "ps3", "ps4", "ps5", "psp", "vita", "playstation", "xbox", "360", "one", "pc", "switch",
        "nintendo", "wii", "ds", "3ds", "gamecube", "gba", "snes", "nes", "n64", "mega", "drive", "genesis", "boy",
        "android", "ios", "mobile", "remastered", "remasterizado",
        "dublado", "legendado", "pt", "br", "ptbr", "portugues", "english", "game", "jogo", "video", "tv", "commercial",
        "comercial", "debut", "intro", "introducao", "opening", "abertura", "new", "novo", "edition", "edicao",
        "release", "date", "story", "historia", "trailer", "em", "e", "de", "do", "da", "the", "a", "o", "and",
    )

    /** Vídeos que não são o trailer do jogo (só valem se a palavra não fizer parte do nome). */
    private val PROIBIDAS = setOf(
        "review", "analise", "reaction", "react", "reacao", "reagindo", "detonado", "walkthrough", "longplay", "part",
        "parte", "episode", "episodio", "ep", "mod", "mods", "fan", "fanmade", "concept", "conceito", "comparison",
        "comparacao", "vs", "versus", "speedrun", "music", "musica", "soundtrack", "ost", "song", "clipe", "parody",
        "parodia", "movie", "filme", "film", "cinema", "animation", "animacao", "unboxing", "tutorial", "dicas", "tips",
        "top", "ranking", "explained", "explicado", "lore", "resumo", "recap", "ending", "leak", "rumor", "shorts",
        "live", "vivo", "stream", "podcast", "fandub", "completo", "completa", "edit", "editz", "meme",
    )

    /** Palavras que dizem que o vídeo é um trailer (ou abertura/anúncio oficial). */
    private val DE_TRAILER = setOf("trailer", "trailers", "teaser", "intro", "introducao", "abertura", "opening", "anuncio", "announcement", "reveal", "lancamento", "launch")

    /** Canais oficiais (do jogo, da editora ou da plataforma) ganham preferência. */
    private val CANAIS_OFICIAIS = listOf(
        "playstation", "xbox", "nintendo", "ign", "gamespot", "rockstar", "ubisoft", "electronic arts", "ea sports",
        "activision", "call of duty", "bethesda", "bandai namco", "capcom", "sega", "square enix", "konami", "minecraft",
        "mojang", "warner bros. games", "wb games", "2k", "naughty dog", "santa monica", "insomniac", "eidos", "thq",
        "riot games", "blizzard", "epic games", "valve", "cd projekt", "devolver", "focus entertainment", "sony",
    )

    /** [local]: vídeo no idioma do aparelho (marca no título, como "dublado", ou canal do país). */
    private class Candidato(val id: String, val nota: Int, val local: Boolean = false)

    /**
     * Trailer de [nome] no YouTube. O título do vídeo precisa trazer o nome inteiro, sem outro número de
     * sequência ("God of War III" ≠ "God of War 2") e sem subtítulo de outro jogo logo depois ("Minecraft
     * Dungeons", "God of War Ragnarök"). [anos] = época do console: jogo antigo não pega vídeo de PS4/PS5.
     */
    fun deJogo(ctx: Context, nome: String, anos: IntRange? = null): String? {
        if (!buscaAtiva()) return null
        return guardado(ctx, "jogo|${Idioma.tmdb()}|${nome.lowercase().trim()}|${anos?.first ?: 0}") {
            val doNome = palavras(nome)
            val antigo = anos != null && anos.first <= 2005
            val local = Idioma.lingua() != "en"
            val marcas = Idioma.marcasNoTitulo().flatMap { palavras(it) }.toSet()
            // Jogo de emulador: se nada servir, tenta com o console ("God of War ps2 trailer").
            val console = Sistema.entries.firstOrNull { it.anos == anos }?.let { SIGLAS[it] }
            // Primeiro no idioma do aparelho ("… trailer dublado legendado"), depois a busca comum, depois com o console.
            val consultas = listOfNotNull(if (local) Idioma.buscaTrailer(nome) else null, "$nome trailer", console?.let { "$nome $it trailer" })
            val comum = if (local) 1 else 0
            val estrita = ArrayList<Candidato>()
            val folgada = ArrayList<Candidato>()
            for ((n, q) in consultas.withIndex()) {
                pesquisar(q).forEachIndexed { i, (id, titulo, canal) ->
                    val nota = avaliar(titulo, doNome, antigo, canal, i) ?: return@forEachIndexed
                    // No idioma do aparelho só conta se for mesmo um trailer (não gameplay, edição de fã etc.).
                    val noIdioma = local && (titulo.any { it in marcas } || Idioma.canalLocal(canal)) && titulo.any { it in DE_TRAILER }
                    when (frase(titulo, doNome)) {
                        true -> estrita += Candidato(id, nota, noIdioma)
                        // Nome inteiro mas fora de ordem ("Crash Bandicoot: A Ira de Cortex"): só se nada melhor aparecer.
                        null -> if (doNome.all { it in titulo }) folgada += Candidato(id, nota - 15, noIdioma)
                        // Nome seguido de subtítulo de outro jogo ("God of War Ragnarök"): descarta.
                        false -> {}
                    }
                }
                // Achou no idioma do aparelho: fica com ele. Em inglês só depois da busca comum (a 2ª), sem gastar a do console.
                estrita.filter { it.local }.maxByOrNull { it.nota }?.let { return@guardado it.id }
                if (n >= comum) estrita.maxByOrNull { it.nota }?.let { return@guardado it.id }
            }
            (folgada.filter { it.local }.maxByOrNull { it.nota } ?: folgada.maxByOrNull { it.nota })?.id
        }
    }

    private val SIGLAS = mapOf(
        Sistema.PS2 to "ps2", Sistema.PS1 to "ps1", Sistema.PSP to "psp", Sistema.GC to "gamecube", Sistema.N3DS to "3ds",
        Sistema.NDS to "ds", Sistema.GBA to "gba", Sistema.GB to "game boy", Sistema.SNES to "snes", Sistema.NES to "nes",
        Sistema.N64 to "n64", Sistema.MD to "mega drive", Sistema.SWITCH to "switch",
        Sistema.PS3 to "ps3", Sistema.PS4 to "ps4", Sistema.VITA to "ps vita", Sistema.X360 to "xbox 360",
        Sistema.WIIU to "wii u", Sistema.DC to "dreamcast",
    )

    /** Nota do vídeo, ou null se ele claramente não é o trailer deste jogo. */
    private fun avaliar(titulo: List<String>, doNome: List<String>, antigo: Boolean, canal: String, posicao: Int): Int? {
        val nomeSet = doNome.toSet()
        if (titulo.any { it in PROIBIDAS && it !in nomeSet }) return null
        // Números de sequência (1 a 3 dígitos) que não são do nome: é outro jogo da série.
        // "1" fica de fora ("Trailer 1") e "360" é o Xbox.
        if (titulo.any { it.length <= 3 && it.all(Char::isDigit) && it !in nomeSet && it != "1" && it != "360" }) return null
        // Ano no nome ("PES 2017"): outro ano no título é outra edição.
        if (doNome.any { it.length == 4 && it.all(Char::isDigit) } &&
            titulo.any { it.length == 4 && it.all(Char::isDigit) && it.toInt() in 1990..2040 && it !in nomeSet }) return null
        if (antigo && titulo.any { it in setOf("ps4", "ps5", "4k", "remake") && it !in nomeSet }) return null
        var nota = 0
        if ("official" in titulo || "oficial" in titulo) nota += 10
        if ("trailer" in titulo) nota += 10
        if ("teaser" in titulo || "gameplay" in titulo) nota += 3
        if (CANAIS_OFICIAIS.any { canal.contains(it) }) nota += 12
        val marcas = Idioma.marcasNoTitulo().flatMap { palavras(it) }.toSet()
        if (titulo.any { it in marcas }) nota += 5
        // Palavras estranhas ao nome e ao vocabulário de trailer pesam contra (outro jogo, canal de fã…).
        nota -= 3 * titulo.count { it !in nomeSet && it !in GENERICAS && it !in marcas }
        // Ordem de relevância do próprio YouTube como desempate.
        return nota + (25 - posicao) / 5
    }

    /**
     * true: o nome aparece inteiro e em sequência no título, sem subtítulo de outro jogo logo depois.
     * false: aparece, mas seguido de um subtítulo ("God of War Ragnarök"). null: não aparece em sequência.
     */
    private fun frase(titulo: List<String>, doNome: List<String>): Boolean? {
        if (doNome.isEmpty() || titulo.size < doNome.size) return null
        var achou = false
        for (i in 0..titulo.size - doNome.size) {
            if (titulo.subList(i, i + doNome.size) != doNome) continue
            achou = true
            val depois = titulo.getOrNull(i + doNome.size)
            if (depois == null || depois in GENERICAS || Idioma.marcasNoTitulo().any { palavras(it).contains(depois) }) return true
            // Ano logo depois ("God of War 2005") também vale.
            if (depois.length == 4 && depois.all(Char::isDigit)) return true
        }
        return if (achou) false else null
    }

    /** Chave nova ou removida: as escolhas guardadas foram feitas com outras fontes, então começa de novo. */
    @Synchronized
    fun esquecer(ctx: Context) {
        arquivo(ctx).delete()
    }

    @Synchronized
    private fun guardado(ctx: Context, chave: String, buscar: () -> String?): String? {
        val cache = try { JSONObject(arquivo(ctx).readText()) } catch (_: Exception) { JSONObject() }
        cache.optJSONObject(chave)?.let { c ->
            if (System.currentTimeMillis() - c.optLong("q") < VALIDADE) return c.optString("v").ifEmpty { null }
        }
        val v = try { buscar() } catch (_: Exception) { return null } // sem rede: tenta de novo depois
        cache.put(chave, JSONObject().put("q", System.currentTimeMillis()).put("v", v ?: ""))
        arquivo(ctx).writeText(cache.toString())
        return v
    }
}
