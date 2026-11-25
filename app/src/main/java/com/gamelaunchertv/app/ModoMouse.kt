package com.gamelaunchertv.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs

/**
 * Controle em apps sem suporte a controle, pelo serviço de acessibilidade:
 * - Modo mouse (ligado por app, no menu rápido): um cursor por cima do app. Direcional (e o analógico esquerdo,
 *   com root) move; X toca; Quadrado segura (toque longo); Círculo volta; L1/R1 rolam a tela.
 * - Mapear botões: com o modo mouse ligado, segure Options e aperte um botão (Triângulo, R2, L2, R3, L3…):
 *   aquele botão passa a tocar onde o cursor está, neste app, mesmo com o modo mouse desligado.
 *   Options + Share apaga os botões mapeados do app.
 * O touchpad do DualShock já funciona como touchpad de mouse no Android (não precisa deste modo).
 */
class ModoMouse(private val servico: AccessibilityService, private val ui: Handler) {

    private val prefs = servico.getSharedPreferences("nova_home", Context.MODE_PRIVATE)
    private val wm = servico.getSystemService(WindowManager::class.java)
    private var cursor: View? = null
    private var x = 0f
    private var y = 0f
    private var app: String? = null
    private var optionsApertado = false
    private var optionsUsado = false

    // Analógico (com root): velocidade de -1 a 1 em cada eixo, aplicada 60 vezes por segundo.
    private var vx = 0f
    private var vy = 0f
    private val passoAnalogico = object : Runnable {
        override fun run() {
            if (vx == 0f && vy == 0f || cursor == null) return
            mover(vx * 22f, vy * 22f)
            ui.postDelayed(this, 16)
        }
    }

    private fun appsComMouse() = prefs.getStringSet("mouse_apps", emptySet()).orEmpty()

    fun ligadoPara(pacote: String?) = pacote != null && pacote in appsComMouse()

    /** Liga/desliga o modo mouse no app [pacote] (lembrado para a próxima vez). */
    fun alternar(pacote: String): Boolean {
        val novo = appsComMouse().toMutableSet()
        val ligado = if (pacote in novo) { novo.remove(pacote); false } else { novo.add(pacote); true }
        prefs.edit().putStringSet("mouse_apps", novo).apply()
        appMudou(pacote)
        return ligado
    }

    /** O app na frente mudou: mostra ou esconde o cursor. */
    fun appMudou(pacote: String?) {
        app = pacote
        ui.post { if (ligadoPara(pacote)) mostrarCursor() else esconderCursor() }
    }

    // ---------- Botões mapeados ----------

    private fun mapa(pacote: String): JSONObject = try { JSONObject(prefs.getString("mapa_botoes:$pacote", "{}")!!) } catch (_: Exception) { JSONObject() }

    private fun nomeBotao(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_Y -> "Triângulo"
        KeyEvent.KEYCODE_BUTTON_X -> "Quadrado"
        KeyEvent.KEYCODE_BUTTON_A -> "X"
        KeyEvent.KEYCODE_BUTTON_B -> "Círculo"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        else -> KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")
    }

    /**
     * Um botão do controle (chamado pelo serviço de acessibilidade). true = o modo mouse ou um botão mapeado
     * cuidou dele (o app não recebe).
     */
    fun tecla(e: KeyEvent): Boolean {
        val pacote = app ?: return false
        val k = e.keyCode
        val desceu = e.action == KeyEvent.ACTION_DOWN
        val mouse = ligadoPara(pacote) && cursor != null

        // Botão mapeado neste app: toca no lugar gravado.
        val mapeado = mapa(pacote).optJSONArray(k.toString())
        if (mapeado != null && !(mouse && optionsApertado)) {
            if (desceu && e.repeatCount == 0) {
                val m = servico.resources.displayMetrics
                tocar(mapeado.getDouble(0).toFloat() * m.widthPixels, mapeado.getDouble(1).toFloat() * m.heightPixels, 60)
            }
            return true
        }
        if (!mouse) return false

        if (k == KeyEvent.KEYCODE_BUTTON_START) {
            if (desceu) { optionsApertado = true; optionsUsado = false } else {
                optionsApertado = false
                // Options sozinho: entrega ao app (o jogo pode usar para pausar).
                if (!optionsUsado) tocarTecla(KeyEvent.KEYCODE_BUTTON_START)
            }
            return true
        }
        if (optionsApertado && desceu && e.repeatCount == 0) {
            optionsUsado = true
            if (k == KeyEvent.KEYCODE_BUTTON_SELECT) {
                prefs.edit().remove("mapa_botoes:$pacote").apply()
                aviso("Botões mapeados deste app apagados")
                return true
            }
            if (k in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return true
            val m = servico.resources.displayMetrics
            val novo = mapa(pacote).put(k.toString(), org.json.JSONArray().put((x / m.widthPixels).toDouble()).put((y / m.heightPixels).toDouble()))
            prefs.edit().putString("mapa_botoes:$pacote", novo.toString()).apply()
            aviso("${nomeBotao(k)} agora toca aqui neste app")
            return true
        }
        if (optionsApertado) return true

        val passo = if (e.repeatCount > 10) 46f else if (e.repeatCount > 3) 26f else 12f
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (desceu) mover(0f, -passo); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (desceu) mover(0f, passo); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (desceu) mover(-passo, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (desceu) mover(passo, 0f); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> { if (desceu && e.repeatCount == 0) tocar(x, y, 50); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { if (desceu && e.repeatCount == 0) tocar(x, y, 650); return true }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (desceu && e.repeatCount == 0) servico.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (desceu) rolar(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (desceu) rolar(1); return true }
        }
        return false
    }

    /** Analógico esquerdo (valores crus de 0 a 255, centro 128), lido com root. */
    fun analogico(eixoX: Int?, eixoY: Int?) {
        fun norm(v: Int) = ((v - 128) / 127f).let { if (abs(it) < 0.18f) 0f else it }
        eixoX?.let { vx = norm(it) }
        eixoY?.let { vy = norm(it) }
        ui.removeCallbacks(passoAnalogico)
        if ((vx != 0f || vy != 0f) && cursor != null) ui.post(passoAnalogico)
    }

    // ---------- Cursor ----------

    private fun mostrarCursor() {
        if (cursor != null) return
        val m = servico.resources.displayMetrics
        if (x == 0f && y == 0f) { x = m.widthPixels / 2f; y = m.heightPixels / 2f }
        val tam = (m.density * 28).toInt()
        val v = Seta(servico)
        val lp = WindowManager.LayoutParams(tam, tam, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = this@ModoMouse.x.toInt()
            this.y = this@ModoMouse.y.toInt()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(v, lp)
            cursor = v
            aviso("Modo mouse: direcional move, X toca, Quadrado segura, Círculo volta")
        } catch (_: Exception) {
        }
    }

    private fun esconderCursor() {
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cursor = null
        vx = 0f
        vy = 0f
    }

    private fun mover(dx: Float, dy: Float) {
        val v = cursor ?: return
        val m = servico.resources.displayMetrics
        x = (x + dx * m.density / 2.5f).coerceIn(0f, m.widthPixels - 1f)
        y = (y + dy * m.density / 2.5f).coerceIn(0f, m.heightPixels - 1f)
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = x.toInt()
        lp.y = y.toInt()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun tocar(px: Float, py: Float, ms: Long) {
        val p = Path().apply { moveTo(px, py) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        servico.dispatchGesture(g, null, null)
    }

    private fun rolar(direcao: Int) {
        val m = servico.resources.displayMetrics
        val d = m.heightPixels * 0.3f
        val p = Path().apply { moveTo(x, y); lineTo(x, (y - direcao * d).coerceIn(1f, m.heightPixels - 1f)) }
        servico.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null)
    }

    /** Entrega uma tecla ao app (Options sozinho no modo mouse). Sem root não dá para injetar tecla: fica sem efeito. */
    private fun tocarTecla(k: Int) {
        if (Root.ligado(servico)) Thread { try { Root.rodar("input keyevent $k") } catch (_: Exception) {} }.start()
    }

    private fun aviso(t: String) = ui.post { Toast.makeText(servico, t, Toast.LENGTH_SHORT).show() }

    /** A setinha do cursor (branca com borda escura, a ponta no canto de cima à esquerda). */
    private class Seta(ctx: Context) : View(ctx) {
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            p.reset()
            p.moveTo(2f, 2f)
            p.lineTo(2f, w * 0.85f)
            p.lineTo(w * 0.3f, w * 0.62f)
            p.lineTo(w * 0.5f, w * 0.98f)
            p.lineTo(w * 0.62f, w * 0.92f)
            p.lineTo(w * 0.43f, w * 0.56f)
            p.lineTo(w * 0.78f, w * 0.56f)
            p.close()
            tinta.style = Paint.Style.FILL
            tinta.color = Color.WHITE
            c.drawPath(p, tinta)
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = w * 0.06f
            tinta.color = Color.BLACK
            c.drawPath(p, tinta)
        }
    }
}
