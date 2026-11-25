package com.gamelaunchertv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coleções da pessoa ("Favoritos", "Multiplayer", "Zerar"…): cada uma é uma fileira na aba Jogos com jogos de
 * qualquer plataforma (ROMs, Android, atalhos, nuvem), pelos ids dos cards. Ficam na ordem em que foram criadas.
 */
object Colecoes {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_home", Context.MODE_PRIVATE)

    private fun ler(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("colecoes", "{}")!!) } catch (_: Exception) { JSONObject() }

    private fun salvar(ctx: Context, o: JSONObject) = prefs(ctx).edit().putString("colecoes", o.toString()).apply()

    /** Nome → ids dos jogos (na ordem em que foram adicionados). */
    fun todas(ctx: Context): LinkedHashMap<String, List<String>> {
        val o = ler(ctx)
        val r = LinkedHashMap<String, List<String>>()
        o.keys().forEach { nome -> r[nome] = o.optJSONArray(nome)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }
        return r
    }

    fun contem(ctx: Context, nome: String, id: String) = todas(ctx)[nome]?.contains(id) == true

    /** Põe ou tira o jogo [id] da coleção [nome] (cria a coleção se não existir). */
    fun alternar(ctx: Context, nome: String, id: String): Boolean {
        val o = ler(ctx)
        val ids = (o.optJSONArray(nome)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()).toMutableList()
        val agora = if (id in ids) { ids.remove(id); false } else { ids += id; true }
        o.put(nome, JSONArray(ids))
        salvar(ctx, o)
        return agora
    }

    fun criar(ctx: Context, nome: String) {
        val o = ler(ctx)
        if (!o.has(nome)) o.put(nome, JSONArray())
        salvar(ctx, o)
    }

    fun renomear(ctx: Context, antigo: String, novo: String) {
        val o = ler(ctx)
        val ids = o.optJSONArray(antigo) ?: return
        // Recria na mesma posição (JSONObject não garante ordem ao trocar a chave).
        val r = JSONObject()
        o.keys().forEach { k -> if (k == antigo) r.put(novo, ids) else r.put(k, o.get(k)) }
        salvar(ctx, r)
    }

    fun excluir(ctx: Context, nome: String) {
        val o = ler(ctx)
        o.remove(nome)
        salvar(ctx, o)
    }

    /** Id da seção da aba Jogos de uma coleção (para mover e esconder como as outras). */
    fun secao(nome: String) = "colecao:$nome"
}
