package com.gamelaunchertv.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Backup das configurações do launcher num arquivo JSON: preferências (aparência, favoritos, ordem das seções,
 * coleções, chaves e contas), capas e nomes trocados à mão, histórico de jogo, perfis e as fotos da galeria
 * usadas como capa ou fundo. Importar substitui tudo isso e reabre o launcher.
 * Também guarda/restaura os dados de cada perfil ([Perfis]).
 */
object Backup {

    /** Arquivos de preferências que entram no backup. */
    val ARQUIVOS = listOf("nova_home", "personalizado", "historico", "perfis")

    fun prefsParaJson(p: SharedPreferences, pular: (String) -> Boolean = { false }): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            if (pular(k)) continue
            val item = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            o.put(k, item)
        }
        return o
    }

    /** Troca o conteúdo de [p] pelo de [o] (menos as chaves [manter], que ficam como estão). */
    fun jsonParaPrefs(o: JSONObject, p: SharedPreferences, manter: (String) -> Boolean = { false }) {
        val e = p.edit()
        p.all.keys.filterNot(manter).forEach { e.remove(it) }
        for (k in o.keys()) {
            if (manter(k)) continue
            val item = o.optJSONObject(k) ?: continue
            when (item.optString("t")) {
                "s" -> e.putString(k, item.optString("v"))
                "i" -> e.putInt(k, item.optInt("v"))
                "l" -> e.putLong(k, item.optLong("v"))
                "b" -> e.putBoolean(k, item.optBoolean("v"))
                "f" -> e.putFloat(k, item.optDouble("v").toFloat())
                "ss" -> e.putStringSet(k, item.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
            }
        }
        e.commit()
    }

    fun exportar(ctx: Context, saida: OutputStream) {
        val raiz = JSONObject().put("app", "GameLauncherTV").put("versao", 1).put("quando", System.currentTimeMillis())
        val prefs = JSONObject()
        for (nome in ARQUIVOS) prefs.put(nome, prefsParaJson(ctx.getSharedPreferences(nome, Context.MODE_PRIVATE)))
        raiz.put("prefs", prefs)
        // Fotos da galeria (capas e fundos escolhidos): já reduzidas, cabem no arquivo.
        val fotos = JSONObject()
        File(ctx.filesDir, "fotos").listFiles()?.forEach { f ->
            fotos.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        raiz.put("fotos", fotos)
        // Dados guardados dos outros perfis.
        val perfis = JSONObject()
        File(ctx.filesDir, "perfis").listFiles()?.forEach { f -> perfis.put(f.name, f.readText()) }
        raiz.put("perfis", perfis)
        saida.bufferedWriter().use { it.write(raiz.toString()) }
    }

    /** true = importou. Arquivo de outro app ou corrompido: false, sem mexer em nada. */
    fun importar(ctx: Context, entrada: InputStream): Boolean {
        val raiz = try { JSONObject(entrada.bufferedReader().use { it.readText() }) } catch (_: Exception) { return false }
        if (raiz.optString("app") != "GameLauncherTV") return false
        val prefs = raiz.optJSONObject("prefs") ?: return false
        val pastaFotos = File(ctx.filesDir, "fotos").apply { mkdirs() }
        raiz.optJSONObject("fotos")?.let { fotos ->
            for (nome in fotos.keys()) {
                if (nome.contains('/') || nome.contains("..")) continue
                try { File(pastaFotos, nome).writeBytes(Base64.decode(fotos.getString(nome), Base64.NO_WRAP)) } catch (_: Exception) {}
            }
        }
        raiz.optJSONObject("perfis")?.let { perfis ->
            val pasta = File(ctx.filesDir, "perfis").apply { mkdirs() }
            for (nome in perfis.keys()) if (!nome.contains('/') && !nome.contains("..")) File(pasta, nome).writeText(perfis.getString(nome))
        }
        for (nome in ARQUIVOS) {
            val o = prefs.optJSONObject(nome) ?: continue
            jsonParaPrefs(o, ctx.getSharedPreferences(nome, Context.MODE_PRIVATE))
        }
        // As fotos podem ter vindo de outro aparelho: o caminho guardado aponta para a pasta deste app.
        corrigirCaminhosFotos(ctx)
        return true
    }

    private fun corrigirCaminhosFotos(ctx: Context) {
        val certo = "file://" + File(ctx.filesDir, "fotos").path + "/"
        val padrao = Regex("file://[^\"]*/fotos/")
        for (nome in listOf("nova_home", "personalizado")) {
            val p = ctx.getSharedPreferences(nome, Context.MODE_PRIVATE)
            val e = p.edit()
            for ((k, v) in p.all) if (v is String && v.contains("/fotos/") && v.contains("file://")) e.putString(k, v.replace(padrao, certo))
            e.commit()
        }
    }
}
