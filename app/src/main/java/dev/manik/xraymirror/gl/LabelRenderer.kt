package dev.manik.xraymirror.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws callout labels like an anatomy atlas: a dot on the structure, a thin leader line,
 * and a text tag stacked in a column at the screen edge. Text is rasterised once per label
 * with Android's Canvas and cached as a texture, so a frame costs a few quads.
 */
class LabelRenderer(private val density: Float) {
    class Callout(val key: String, val text: String, val color: Int, val ax: Float, val ay: Float, val partId: Int, val selected: Boolean = false)
    /** Screen rectangle of a drawn tag and the part it names, for tap hit-testing. */
    class Hit(val partId: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    private class Tex(val id: Int, val w: Int, val h: Int)
    private val cache = HashMap<String, Tex>()
    private val prog = Prog(Shaders.FLAT_VS, Shaders.FLAT_FS)
    private val vbo: Int
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val smoothY = HashMap<String, Float>()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12.5f * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0060A12.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val b = IntArray(1)
        glGenBuffers(1, b, 0)
        vbo = b[0]
    }

    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0157A8C.toInt() }

    private fun texFor(text: String, color: Int, selected: Boolean = false): Tex = cache.getOrPut("$text|$color|$selected") {
        val pad = 7f * density; val dot = 7f * density
        val tw = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val w = (pad * 2 + dot + 6 * density + tw).toInt() + 1
        val h = (fm.descent - fm.ascent + pad * 1.2f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, if (selected) selPaint else bgPaint)
        dotPaint.color = color
        c.drawCircle(pad + dot / 2, h / 2f, dot / 2, dotPaint)
        c.drawText(text, pad + dot + 6 * density, h / 2f - (fm.ascent + fm.descent) / 2, textPaint)
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        Tex(t[0], w, h)
    }

    /** Lays out callouts into left/right columns and draws them in screen pixels (y down). */
    fun draw(callouts: List<Callout>, sw: Int, sh: Int, topInset: Float, bottomY: Float): List<Hit> {
        if (callouts.isEmpty()) return emptyList()
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        prog.use()
        glUniform2f(prog.u("uScreen"), sw.toFloat(), sh.toFloat())
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1)

        val margin = 10f * density
        val placed = ArrayList<Triple<Callout, Tex, FloatArray>>() // x, y of the tag's top-left
        for (left in listOf(true, false)) {
            val col = callouts.filter { (it.ax < sw / 2f) == left }.sortedBy { it.ay }
            var y = topInset
            for (c in col) {
                val t = texFor(c.text, c.color, c.selected)
                val target = (c.ay - t.h / 2f)
                val prev = smoothY[c.key] ?: target
                var ty = prev + (target - prev) * 0.35f
                ty = maxOf(ty, y)
                ty = minOf(ty, bottomY - t.h)
                smoothY[c.key] = ty
                y = ty + t.h + 4f * density
                val tx = if (left) margin else sw - margin - t.w
                placed += Triple(c, t, floatArrayOf(tx, ty))
            }
        }
        // Leader lines + anchor dots.
        buf.clear()
        for ((c, t, p) in placed) {
            val ex = if (p[0] < sw / 2f) p[0] + t.w else p[0]
            val ey = p[1] + t.h / 2f
            buf.put(floatArrayOf(c.ax, c.ay, 0f, 0f, ex, ey, 0f, 0f))
        }
        val lineVerts = buf.position() / 4
        val r = 2.6f * density
        for ((c, _, _) in placed) quad(c.ax - r, c.ay - r, c.ax + r, c.ay + r)
        buf.flip()
        glBufferData(GL_ARRAY_BUFFER, buf.limit() * 4, buf, GL_STREAM_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glUniform1f(prog.u("uUseTex"), 0f)
        glUniform4f(prog.u("uColor"), 0.75f, 0.92f, 1f, 0.75f)
        glLineWidth(1.5f * density)
        glDrawArrays(GL_LINES, 0, lineVerts)
        glUniform4f(prog.u("uColor"), 0.85f, 0.97f, 1f, 1f)
        glDrawArrays(GL_TRIANGLES, lineVerts, placed.size * 6)

        // Tags.
        glUniform1f(prog.u("uUseTex"), 1f)
        glUniform4f(prog.u("uColor"), 1f, 1f, 1f, 1f)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(prog.u("uTex"), 0)
        for ((_, t, p) in placed) {
            buf.clear()
            quad(p[0], p[1], p[0] + t.w, p[1] + t.h)
            buf.flip()
            glBufferData(GL_ARRAY_BUFFER, buf.limit() * 4, buf, GL_STREAM_DRAW)
            glBindTexture(GL_TEXTURE_2D, t.id)
            glDrawArrays(GL_TRIANGLES, 0, 6)
        }
        glDisable(GL_BLEND)
        val pad = 6f * density
        return placed.map { (c, t, p) -> Hit(c.partId, p[0] - pad, p[1] - pad, p[0] + t.w + pad, p[1] + t.h + pad) }
    }

    /** Plain line segments (x0, y0, x1, y1) in screen pixels — the tracking overlay. */
    fun drawSegments(segs: List<FloatArray>, sw: Int, sh: Int) {
        if (segs.isEmpty()) return
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        prog.use()
        glUniform2f(prog.u("uScreen"), sw.toFloat(), sh.toFloat())
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1)
        buf.clear()
        for (sg in segs) buf.put(floatArrayOf(sg[0], sg[1], 0f, 0f, sg[2], sg[3], 0f, 0f))
        buf.flip()
        glBufferData(GL_ARRAY_BUFFER, buf.limit() * 4, buf, GL_STREAM_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glUniform1f(prog.u("uUseTex"), 0f)
        glUniform4f(prog.u("uColor"), 0.2f, 1f, 0.45f, 1f)
        glLineWidth(3f * density)
        glDrawArrays(GL_LINES, 0, segs.size * 2)
        glDisable(GL_BLEND)
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float) {
        buf.put(floatArrayOf(x0, y0, 0f, 0f, x1, y0, 1f, 0f, x1, y1, 1f, 1f, x0, y0, 0f, 0f, x1, y1, 1f, 1f, x0, y1, 0f, 1f))
    }

    fun forgetSmoothing() = smoothY.clear()
}
