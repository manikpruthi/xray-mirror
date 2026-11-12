package dev.manik.xraymirror.pose

import dev.manik.xraymirror.Fit

/** One pose result from MediaPipe, in the upright camera frame. */
class PoseFrame(
    /** 33 × (x, y) normalised image coordinates. */
    val image: FloatArray,
    /** 33 visibility scores. */
    val visibility: FloatArray,
    /** 33 × (x, y, z) metric world coordinates, hip-centred, y down, z away from camera. */
    val world: FloatArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)

/** Rest-pose data of the anatomy model, read from anatomy.json. */
class Rig(val landmarks: Map<Int, V3>, val boneNames: List<String>) {
    fun lm(i: Int) = landmarks.getValue(i)
}

/**
 * Turns MediaPipe landmarks into 16 bone matrices that bend the rest-pose anatomy onto the
 * person in "frame space": camera-frame pixels with Y up and Z toward the viewer.
 *
 * Torso and head take their full orientation from landmark triangles (hips, shoulders, ears+nose).
 * Limbs inherit their parent's rotation and add the shortest swing that points the bone at the
 * next joint, plus forearm/hand twist from the index–pinky line. Each bone is then stretched
 * along its own axis so its far end lands on the tracked joint, whatever the person's proportions.
 */
class BodyFit(private val rig: Rig) {
    val boneCount = rig.boneNames.size
    /** Column-major 4×4 per bone, ready for glUniformMatrix4fv. */
    val boneMatrices = FloatArray(boneCount * 16)
    /** Smoothed landmarks in frame space (for the tracking overlay and the hand lens). */
    val points = Array(33) { V3.ZERO }
    val visibility = FloatArray(33)
    var scale = 0f; private set
    var torsoLength = 0f; private set
    var valid = false; private set

    private val filters = Array(99) { OneEuro() }
    private var lastTs = 0L

    // Rest-pose helpers -------------------------------------------------------------------
    private fun r(i: Int) = rig.lm(i)
    private val rHipM = V3.mid(r(23), r(24))
    private val rShM = V3.mid(r(11), r(12))
    private val rEarM = V3.mid(r(7), r(8))
    private val rTorso = (rShM - rHipM).len()

    private fun torsoFrame(up: V3, side: V3) = M3.frame(up, side)
    private fun headFrame(l7: V3, l8: V3, nose: V3): M3 {
        val x = l7 - l8
        val fwd = nose - V3.mid(l7, l8)
        return M3.frame(fwd cross x, x)
    }

    private val restPelvis = torsoFrame(rShM - rHipM, r(23) - r(24))
    private val restChest = torsoFrame(rShM - rHipM, r(11) - r(12))
    private val restHead = headFrame(r(7), r(8), r(0))

    fun reset() { filters.forEach { it.reset() }; valid = false }

    fun update(f: PoseFrame, fit: Fit) {
        val dt = if (lastTs == 0L) 0f else ((f.timestampNs - lastTs) / 1e9f).coerceIn(0.001f, 0.5f)
        if (dt >= 0.5f) filters.forEach { it.reset() }
        lastTs = f.timestampNs

        // Pixels per metre: compare image-space and world-space lengths of well-seen segments.
        val ratios = ArrayList<Float>(12)
        for ((a, b) in PAIRS) {
            if (f.visibility[a] < 0.5f || f.visibility[b] < 0.5f) continue
            val ix = (f.image[a * 2] - f.image[b * 2]) * f.width
            val iy = (f.image[a * 2 + 1] - f.image[b * 2 + 1]) * f.height
            val wx = f.world[a * 3] - f.world[b * 3]
            val wy = f.world[a * 3 + 1] - f.world[b * 3 + 1]
            val wl = kotlin.math.sqrt(wx * wx + wy * wy)
            if (wl > 0.06f) ratios += kotlin.math.sqrt(ix * ix + iy * iy) / wl
        }
        val ppm = if (ratios.isEmpty()) f.height / 2.2f else ratios.sorted()[ratios.size / 2]

        for (i in 0 until 33) {
            val x = f.image[i * 2] * f.width
            val y = (1f - f.image[i * 2 + 1]) * f.height
            val z = -f.world[i * 3 + 2] * ppm
            points[i] = V3(filters[i * 3].filter(x, dt), filters[i * 3 + 1].filter(y, dt), filters[i * 3 + 2].filter(z, dt))
            visibility[i] = f.visibility[i]
        }
        solve(fit)
    }

    /** Use already-placed frame-space points as-is (demo mannequin); no smoothing. */
    fun updateDirect(pts: FloatArray, fit: Fit) {
        for (i in 0 until 33) { points[i] = V3(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]); visibility[i] = 1f }
        filters.forEach { it.reset() }
        lastTs = 0L
        solve(fit)
    }

    private fun smooth01(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /**
     * Close-ups only show head and shoulders; MediaPipe's guesses for the hidden hips and legs
     * are unreliable and would swing the whole torso. Rebuild the lower body hanging below the
     * shoulders (oriented by the head and shoulder line) and blend it in as visibility drops.
     */
    private fun fillHidden() {
        val p = points
        val wHip = smooth01(minOf(visibility[23], visibility[24]), 0.35f, 0.75f)
        if (wHip >= 0.999f) return
        val side = p[11] - p[12]
        val kS = side.len() / (r(11) - r(12)).len()
        val fwd = p[0] - V3.mid(p[7], p[8])
        var up = (fwd cross side)
        if (up.len() < 1e-3f || visibility[0] < 0.3f) up = V3(0f, 1f, 0f)
        up = up.norm()
        // Keep the synthetic torso close to vertical: people rarely lean far in a mirror.
        up = (up + V3(0f, 1f, 0f)).norm()
        val rot = M3.frame(up, side) * restChest.transpose()
        val shM = V3.mid(p[11], p[12])
        val hipM = shM - up * (rTorso * kS)
        for (i in 23..32) {
            val synth = hipM + rot * (r(i) - rHipM) * kS
            val w = minOf(wHip, smooth01(visibility[i], 0.35f, 0.75f))
            p[i] = V3.lerp(synth, p[i], w)
        }
    }

    private fun solve(fit: Fit) {
        fillHidden()
        val p = points
        val hipM = V3.mid(p[23], p[24]); val shM = V3.mid(p[11], p[12]); val earM = V3.mid(p[7], p[8])
        torsoLength = (shM - hipM).len()

        // Global body scale (px per model metre) from 3D segment lengths — robust to turning sideways.
        val ks = ArrayList<Float>()
        fun seg(a: Int, b: Int) {
            if (visibility[a] > 0.5f && visibility[b] > 0.5f) ks += (p[a] - p[b]).len() / (r(a) - r(b)).len()
        }
        if (visibility[23] > 0.5f && visibility[24] > 0.5f) ks += torsoLength / rTorso
        seg(11, 12); seg(11, 13); seg(12, 14); seg(13, 15); seg(14, 16); seg(23, 25); seg(24, 26); seg(25, 27); seg(26, 28)
        if (ks.isEmpty()) ks += (p[11] - p[12]).len() / (r(11) - r(12)).len()
        val k = ks.sorted()[ks.size / 2] * fit.size
        // The head is often much nearer the camera than the body; size it by its own ear distance.
        val kHead = if (visibility[7] > 0.5f && visibility[8] > 0.5f)
            ((p[7] - p[8]).len() / (r(7) - r(8)).len() * fit.size).coerceIn(0.7f * k, 1.5f * k) else k
        scale = k

        val rPelvis = torsoFrame(shM - hipM, p[23] - p[24]) * restPelvis.transpose()
        val rChest = torsoFrame(shM - hipM, p[11] - p[12]) * restChest.transpose()
        val rNeck = M3.swing(rChest * (rEarM - rShM), earM - shM) * rChest
        val rHead = headFrame(p[7], p[8], p[0]) * restHead.transpose()

        val offset = V3(fit.dx * torsoLength, fit.dy * torsoLength, 0f)
        val chestHead0 = V3.lerp(rHipM, rShM, 0.45f)
        val chestHead = V3.lerp(hipM, shM, 0.45f)

        fun axial(obs: Float, rest: Float) = (obs / rest).coerceIn(0.6f * k, 1.6f * k) * fit.height
        set(0, rPelvis, rHipM, hipM, (rShM - rHipM), axial(torsoLength, rTorso), k, fit, offset)
        set(1, rChest, chestHead0, chestHead, (rShM - rHipM), axial(torsoLength, rTorso), k, fit, offset)
        set(2, rNeck, rShM, shM, rEarM - rShM, axial((earM - shM).len(), (rEarM - rShM).len()), k, fit, offset)
        set(3, rHead, rEarM, earM, V3(0f, 1f, 0f), kHead * fit.height, kHead, fit, offset)

        for (side in 0..1) {
            val sh = 11 + side; val el = 13 + side; val wr = 15 + side; val pk = 17 + side; val ix = 19 + side
            val hip = 23 + side; val kn = 25 + side; val an = 27 + side; val ft = 31 + side
            // Arm chain.
            val rUa = M3.swing(rChest * (r(el) - r(sh)), p[el] - p[sh]) * rChest
            val rFaS = M3.swing(rUa * (r(wr) - r(el)), p[wr] - p[el]) * rUa
            val palm0 = r(ix) - r(pk)
            val palm = p[ix] - p[pk]
            val handsSeen = visibility[ix] > 0.5f && visibility[pk] > 0.5f
            val faAxis = (p[wr] - p[el]).norm()
            val faTwist = if (handsSeen) M3.twistAngle(rFaS * palm0, palm, faAxis) else 0f
            val rFa = M3.axisAngle(faAxis, faTwist * 0.5f) * rFaS
            val hand0 = V3.mid(r(ix), r(pk)); val hand = V3.mid(p[ix], p[pk])
            val rHs = M3.swing(rFaS * (hand0 - r(wr)), hand - p[wr]) * rFaS
            val hAxis = (hand - p[wr]).norm()
            val hTwist = if (handsSeen) M3.twistAngle(rHs * palm0, palm, hAxis) else 0f
            val rHand = M3.axisAngle(hAxis, hTwist) * rHs
            set(4 + side, rUa, r(sh), p[sh], r(el) - r(sh), axial((p[el] - p[sh]).len(), (r(el) - r(sh)).len()), k, fit, offset)
            set(6 + side, rFa, r(el), p[el], r(wr) - r(el), axial((p[wr] - p[el]).len(), (r(wr) - r(el)).len()), k, fit, offset)
            set(8 + side, rHand, r(wr), p[wr], hand0 - r(wr), k * fit.height, k, fit, offset)
            // Leg chain.
            val rTh = M3.swing(rPelvis * (r(kn) - r(hip)), p[kn] - p[hip]) * rPelvis
            val rSh = M3.swing(rTh * (r(an) - r(kn)), p[an] - p[kn]) * rTh
            val rFt = M3.swing(rSh * (r(ft) - r(an)), p[ft] - p[an]) * rSh
            set(10 + side, rTh, r(hip), p[hip], r(kn) - r(hip), axial((p[kn] - p[hip]).len(), (r(kn) - r(hip)).len()), k, fit, offset)
            set(12 + side, rSh, r(kn), p[kn], r(an) - r(kn), axial((p[an] - p[kn]).len(), (r(an) - r(kn)).len()), k, fit, offset)
            set(14 + side, rFt, r(an), p[an], r(ft) - r(an), k * fit.height, k, fit, offset)
        }
        valid = true
    }

    /**
     * M(x) = R · S · (x − h0) + h, where S scales by `axial` along the rest bone direction d0,
     * by k across it, and by the user's width/depth factors along model X/Z.
     */
    private fun set(bone: Int, rot: M3, h0: V3, h: V3, restDir: V3, axial: Float, k: Float, fit: Fit, offset: V3) {
        val d = restDir.norm()
        val s = FloatArray(9)
        val dd = floatArrayOf(d.x, d.y, d.z)
        for (i in 0..2) for (j in 0..2) s[i * 3 + j] = (if (i == j) k else 0f) + (axial - k) * dd[i] * dd[j]
        val sfit = M3(floatArrayOf(fit.width, 0f, 0f, 0f, 1f, 0f, 0f, 0f, fit.depth))
        val a = rot * M3(s) * sfit
        val t = h + offset - a * h0
        val o = bone * 16
        val m = a.m
        boneMatrices[o + 0] = m[0]; boneMatrices[o + 1] = m[3]; boneMatrices[o + 2] = m[6]; boneMatrices[o + 3] = 0f
        boneMatrices[o + 4] = m[1]; boneMatrices[o + 5] = m[4]; boneMatrices[o + 6] = m[7]; boneMatrices[o + 7] = 0f
        boneMatrices[o + 8] = m[2]; boneMatrices[o + 9] = m[5]; boneMatrices[o + 10] = m[8]; boneMatrices[o + 11] = 0f
        boneMatrices[o + 12] = t.x; boneMatrices[o + 13] = t.y; boneMatrices[o + 14] = t.z; boneMatrices[o + 15] = 1f
    }

    /** Transform a rest-pose point with up to four bone weights (CPU mirror of the vertex shader). */
    fun skin(pos: V3, bones: IntArray, weights: FloatArray): V3 {
        var x = 0f; var y = 0f; var z = 0f
        for (i in 0 until 4) {
            val w = weights[i]
            if (w <= 0f) continue
            val o = bones[i] * 16
            val m = boneMatrices
            x += w * (m[o] * pos.x + m[o + 4] * pos.y + m[o + 8] * pos.z + m[o + 12])
            y += w * (m[o + 1] * pos.x + m[o + 5] * pos.y + m[o + 9] * pos.z + m[o + 13])
            z += w * (m[o + 2] * pos.x + m[o + 6] * pos.y + m[o + 10] * pos.z + m[o + 14])
        }
        return V3(x, y, z)
    }

    companion object {
        private val PAIRS = listOf(11 to 12, 23 to 24, 11 to 23, 12 to 24, 11 to 13, 12 to 14,
            13 to 15, 14 to 16, 23 to 25, 24 to 26, 25 to 27, 26 to 28)
    }
}
