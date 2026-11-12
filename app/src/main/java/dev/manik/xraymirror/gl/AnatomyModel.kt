package dev.manik.xraymirror.gl

import android.content.Context
import android.opengl.GLES30.*
import dev.manik.xraymirror.Layer
import dev.manik.xraymirror.Sex
import dev.manik.xraymirror.pose.Rig
import dev.manik.xraymirror.pose.V3
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One anatomical structure: a contiguous index range inside its layer's index buffer. */
class Part(
    val id: Int,
    val name: String,
    val layer: Layer,
    /** "m", "f" or "*" (both). */
    val sex: String,
    val first: Int,
    val count: Int,
    val color: FloatArray,
    /** Rest-pose label anchor and its skin weights. */
    val anchor: V3,
    val anchorBones: IntArray,
    val anchorWeights: FloatArray,
) {
    /** Name without the .l/.r side suffix, for display. */
    val displayName: String
        get() {
            val base = name.removeSuffix(".l").removeSuffix(".r").trim()
            val side = when { name.endsWith(".l") -> " (left)"; name.endsWith(".r") -> " (right)"; else -> "" }
            return base.replaceFirstChar { it.uppercase() } + side
        }

    fun visibleFor(sex: Sex) = this.sex == "*" || (this.sex == "m" && sex == Sex.MALE) || (this.sex == "f" && sex == Sex.FEMALE)
}

/**
 * Parsed anatomy.json + anatomy.bin. CPU-side data only; [GpuModel] uploads it. Loading happens
 * off the GL thread, so the UI can show progress while 11 MB is read.
 */
class AnatomyData(context: Context) {
    val parts = ArrayList<Part>()
    val layerParts = HashMap<Layer, List<Part>>()
    val rig: Rig
    val vertexData = HashMap<Layer, ByteBuffer>()
    val indexData = HashMap<Layer, ByteBuffer>()
    val indexCount = HashMap<Layer, Int>()

    init {
        val json = JSONObject(context.assets.open("anatomy.json").bufferedReader().use { it.readText() })
        val bin = context.assets.open("anatomy.bin").use { s ->
            val bytes = s.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).also { it.rewind() }
        }
        val lmJson = json.getJSONObject("landmarks")
        val lms = HashMap<Int, V3>()
        for (k in lmJson.keys()) {
            val a = lmJson.getJSONArray(k)
            lms[k.toInt()] = V3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
        }
        val bones = json.getJSONArray("bones")
        rig = Rig(lms, (0 until bones.length()).map { bones.getJSONObject(it).getString("name") })

        val layers = json.getJSONArray("layers")
        for (li in 0 until layers.length()) {
            val l = layers.getJSONObject(li)
            val layer = Layer.entries.first { it.name.equals(l.getString("name"), true) }
            val vOff = l.getInt("vOffset"); val vCount = l.getInt("vCount")
            val iOff = l.getInt("iOffset"); val iCount = l.getInt("iCount")
            vertexData[layer] = slice(bin, vOff, vCount * STRIDE)
            indexData[layer] = slice(bin, iOff, iCount * 4)
            indexCount[layer] = iCount
            val list = ArrayList<Part>()
            val pj = l.getJSONArray("parts")
            for (pi in 0 until pj.length()) {
                val p = pj.getJSONObject(pi)
                val c = p.getJSONArray("color")
                val av = vOff + p.getInt("anchor") * STRIDE
                val anchor = V3(bin.getFloat(av), bin.getFloat(av + 4), bin.getFloat(av + 8))
                val bi = IntArray(4) { bin.get(av + 16 + it).toInt() and 0xff }
                val bw = FloatArray(4) { (bin.get(av + 20 + it).toInt() and 0xff) / 255f }
                val part = Part(p.getInt("id"), p.getString("name"), layer, p.getString("sex"), p.getInt("first"), p.getInt("count"),
                    floatArrayOf(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat()), anchor, bi, bw)
                list += part
                parts += part
            }
            layerParts[layer] = list
        }
        parts.sortBy { it.id }
    }

    private fun slice(b: ByteBuffer, off: Int, len: Int): ByteBuffer {
        val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        d.position(off); d.limit(off + len)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        /** pos 3×f32, normal 4×s8, bone idx 4×u8, bone weight 4×u8, part id u16, pad u16. */
        const val STRIDE = 28
    }
}

/** GPU buffers: one VAO per layer, plus the part-info texture (colour + visibility). */
class GpuModel(val data: AnatomyData) {
    private val vaos = HashMap<Layer, Int>()
    val partTex: Int
    private val partPixels = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder())
    private var lastSex: Sex? = null

    init {
        for (layer in Layer.entries) {
            val v = data.vertexData[layer] ?: continue
            val ids = IntArray(3)
            glGenVertexArrays(1, ids, 0)
            glGenBuffers(2, ids, 1)
            glBindVertexArray(ids[0])
            glBindBuffer(GL_ARRAY_BUFFER, ids[1])
            glBufferData(GL_ARRAY_BUFFER, v.capacity(), v, GL_STATIC_DRAW)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ids[2])
            val ib = data.indexData.getValue(layer)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib.capacity(), ib, GL_STATIC_DRAW)
            val s = AnatomyData.STRIDE
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, s, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_BYTE, true, s, 12)
            glEnableVertexAttribArray(2); glVertexAttribIPointer(2, 4, GL_UNSIGNED_BYTE, s, 16)
            glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, true, s, 20)
            glEnableVertexAttribArray(4); glVertexAttribIPointer(4, 1, GL_UNSIGNED_SHORT, s, 24)
            glBindVertexArray(0)
            vaos[layer] = ids[0]
        }
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        partTex = t[0]
        glBindTexture(GL_TEXTURE_2D, partTex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2048, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
    }

    /** Re-uploads the part table when the selected sex changes (hides the other sex's organs). */
    fun updateParts(sex: Sex) {
        if (sex == lastSex) return
        lastSex = sex
        partPixels.clear()
        for (i in 0 until 2048) {
            val p = data.parts.getOrNull(i)
            if (p == null) { partPixels.putInt(0); continue }
            partPixels.put((p.color[0] * 255).toInt().toByte())
            partPixels.put((p.color[1] * 255).toInt().toByte())
            partPixels.put((p.color[2] * 255).toInt().toByte())
            partPixels.put(if (p.visibleFor(sex)) 255.toByte() else 0)
        }
        partPixels.rewind()
        glBindTexture(GL_TEXTURE_2D, partTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2048, 1, GL_RGBA, GL_UNSIGNED_BYTE, partPixels)
    }

    fun draw(layer: Layer) {
        val vao = vaos[layer] ?: return
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, data.indexCount.getValue(layer), GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }
}
