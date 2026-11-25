package com.gamelaunchertv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Colocar chaves pelo celular": o launcher abre uma página só na rede local (Wi-Fi) e mostra um QR code
 * com o endereço. A pessoa escaneia com o celular, cola as chaves num formulário e o launcher testa e guarda.
 * - Só responde a quem tem o código secreto do endereço (gerado na hora, muda a cada vez).
 * - Fica aberto só enquanto a janela do QR estiver na tela; fecha sozinho depois de salvar.
 * - Nada sai da rede local: o celular fala direto com o launcher.
 */
class ChavesPeloCelular(private val ctx: Context, private val aoSalvar: (String) -> Unit) {

    private val ui = Handler(Looper.getMainLooper())
    private var servidor: ServerSocket? = null
    @Volatile private var ligado = false
    val codigo: String = (1..16).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
    var endereco: String? = null
        private set

    /** IPv4 do aparelho na rede local (Wi-Fi ou cabo); null sem rede local. */
    private fun ipLocal(): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Liga o servidor; devolve o endereço para o QR, ou null se não há rede local. */
    fun ligar(): String? {
        val ip = ipLocal() ?: return null
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(ip, 0)) // porta livre qualquer
        servidor = s
        ligado = true
        endereco = "http://$ip:${s.localPort}/$codigo"
        Thread {
            while (ligado) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread { atender(c) }.start()
            }
        }.start()
        return endereco
    }

    fun desligar() {
        ligado = false
        try { servidor?.close() } catch (_: Exception) {}
        servidor = null
    }

    private fun atender(c: Socket) = c.use { sock ->
        sock.soTimeout = 15000
        val entrada = sock.getInputStream().buffered()
        // Primeira linha ("POST /codigo HTTP/1.1") e cabeçalhos.
        val linha = lerLinha(entrada) ?: return
        val partes = linha.split(' ')
        if (partes.size < 2) return
        val metodo = partes[0]
        val caminho = partes[1].substringBefore('?').trim('/')
        var tamanho = 0
        while (true) {
            val h = lerLinha(entrada) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) tamanho = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (caminho != codigo) return responder(sock, 404, "<h1>Endereço inválido</h1><p>Escaneie de novo o QR code na TV.</p>")
        if (metodo == "GET") return responder(sock, 200, formulario())
        if (metodo != "POST" || tamanho !in 1..20000) return responder(sock, 400, "<h1>Pedido inválido</h1>")
        val corpo = ByteArray(tamanho)
        var lidos = 0
        while (lidos < tamanho) {
            val n = entrada.read(corpo, lidos, tamanho - lidos)
            if (n < 0) break
            lidos += n
        }
        val campos = String(corpo, 0, lidos, Charsets.UTF_8).split('&').mapNotNull { par ->
            val k = par.substringBefore('=', "")
            if (k.isEmpty()) null else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(par.substringAfter('='), "UTF-8").trim()
        }.toMap()
        responder(sock, 200, salvar(campos))
    }

    /** Testa e guarda cada chave preenchida; devolve a página com o resultado de cada uma. */
    private fun salvar(campos: Map<String, String>): String {
        val linhas = ArrayList<String>()
        for (sv in Chaves.Servico.entries) {
            val chave = campos[sv.pref].orEmpty()
            if (chave.isEmpty()) continue
            val usuario = sv.prefUsuario?.let { campos[it] }.orEmpty()
            // O teste devolve o usuário a guardar (na Steam, o número do ID achado pelo link do perfil).
            val guardado = Chaves.testar(sv, chave, usuario)
            val ok = guardado != null
            if (guardado != null) {
                if (sv.usuario != null) Chaves.guardarUsuario(sv, guardado)
                Chaves.guardar(sv, chave)
            }
            linhas += "<li>${sv.nome}: ${if (ok) "✅ salva" else "❌ não funcionou (confira e envie de novo)"}</li>"
        }
        if (linhas.isEmpty()) return pagina("Nada para salvar", "<p>Preencha pelo menos uma chave.</p><p><a href=\"/$codigo\">Voltar</a></p>")
        val salvas = linhas.count { "✅" in it }
        ui.post { aoSalvar("$salvas de ${linhas.size} chave(s) salva(s)") }
        return pagina("Resultado", "<ul>${linhas.joinToString("")}</ul>" +
            "<p>Pode fechar esta página. ${if (salvas < linhas.size) "<a href=\"/$codigo\">Tentar de novo</a>" else ""}</p>")
    }

    private fun formulario(): String {
        val campos = Chaves.Servico.entries.joinToString("") { sv ->
            val usuario = if (sv.usuario == null) "" else
                "<input name=\"${sv.prefUsuario}\" placeholder=\"${esc(sv.usuario)}\" autocomplete=\"off\" autocapitalize=\"none\">"
            "<fieldset><legend>${sv.nome}</legend><p>${esc(sv.onde)}</p>$usuario" +
                "<input name=\"${sv.pref}\" placeholder=\"Cole a chave aqui\" autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\"></fieldset>"
        }
        return pagina("Chaves do GameLauncherTV",
            "<p>Cole só as que quiser. Cada chave é testada antes de ser guardada na TV. Esta página só existe na sua rede, " +
                "enquanto o QR code estiver na tela.</p><form method=\"post\">$campos<button>Enviar para a TV</button></form>")
    }

    private fun pagina(titulo: String, corpo: String) = """
        <!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$titulo</title><style>
        body{font-family:system-ui,sans-serif;background:#16181d;color:#e8eaed;margin:0;padding:20px;max-width:640px}
        h1{font-weight:500}fieldset{border:1px solid #3c4048;border-radius:12px;margin:14px 0;padding:12px}
        legend{font-weight:600;padding:0 6px}p{color:#aab0b8;font-size:14px}
        input{width:100%;box-sizing:border-box;padding:12px;margin:6px 0;border-radius:8px;border:1px solid #555;background:#23262d;color:#fff;font-size:16px}
        button{width:100%;padding:14px;border:0;border-radius:24px;background:#8ab4f8;color:#111;font-size:17px;font-weight:600;margin-top:10px}
        a{color:#8ab4f8}</style></head><body><h1>$titulo</h1>$corpo</body></html>
    """.trimIndent()

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun responder(sock: Socket, status: Int, html: String) {
        val corpo = html.let { if (it.trimStart().startsWith("<!doctype")) it else pagina("GameLauncherTV", it) }.toByteArray(Charsets.UTF_8)
        val texto = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val cab = "HTTP/1.1 $status $texto\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${corpo.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(cab.toByteArray(Charsets.US_ASCII))
            write(corpo)
            flush()
        }
    }

    private fun lerLinha(e: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = e.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8000) return null
            sb.append(b.toChar())
        }
    }

    companion object {
        /** QR code do [texto] em preto no branco, com [lado] px. */
        fun qr(texto: String, lado: Int): Bitmap {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(texto, com.google.zxing.BarcodeFormat.QR_CODE, lado, lado,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val px = IntArray(lado * lado) { i -> if (m.get(i % lado, i / lado)) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888)
        }
    }
}
