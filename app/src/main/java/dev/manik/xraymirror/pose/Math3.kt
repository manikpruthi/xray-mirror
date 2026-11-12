package dev.manik.xraymirror.pose

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immutable 3-vector. A few hundred are created per frame, which ART handles easily. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun div(s: Float) = V3(x / s, y / s, z / s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(this dot this)
    fun norm(): V3 { val l = len(); return if (l < 1e-9f) V3(0f, 1f, 0f) else this / l }

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        fun mid(a: V3, b: V3) = (a + b) * 0.5f
        fun lerp(a: V3, b: V3, t: Float) = a + (b - a) * t
    }
}

/** Row-major 3x3 matrix. */
class M3(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
    operator fun times(v: V3) = V3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: M3): M3 {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return M3(r)
    }

    fun transpose() = M3(floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    companion object {
        val I get() = M3()

        /** Matrix whose columns are the given axes. */
        fun cols(a: V3, b: V3, c: V3) = M3(floatArrayOf(a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z))

        /** Rodrigues rotation about a unit axis. */
        fun axisAngle(axis: V3, angle: Float): M3 {
            val (x, y, z) = axis.norm()
            val c = cos(angle); val s = sin(angle); val t = 1 - c
            return M3(floatArrayOf(
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c,
            ))
        }

        /** Shortest rotation taking direction a onto direction b (no twist about the axis). */
        fun swing(a: V3, b: V3): M3 {
            val an = a.norm(); val bn = b.norm()
            val axis = an cross bn
            val s = axis.len()
            val c = (an dot bn).coerceIn(-1f, 1f)
            if (s < 1e-6f) {
                if (c > 0) return I
                // 180°: any perpendicular axis.
                val p = if (kotlin.math.abs(an.x) < 0.9f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)
                return axisAngle((an cross p).norm(), Math.PI.toFloat())
            }
            return axisAngle(axis / s, atan2(s, c))
        }

        /** Orthonormal frame from a primary axis and a roughly perpendicular secondary axis. */
        fun frame(primary: V3, secondary: V3): M3 {
            val e1 = primary.norm()
            var e2 = secondary - e1 * (secondary dot e1)
            if (e2.len() < 1e-5f) e2 = (if (kotlin.math.abs(e1.x) < 0.9f) V3(1f, 0f, 0f) else V3(0f, 0f, 1f)) - e1 * e1.x
            e2 = e2.norm()
            val e3 = e1 cross e2
            return cols(e1, e2, e3)
        }

        /** Signed angle from u to v around unit axis n (both projected onto the plane ⟂ n). */
        fun twistAngle(u: V3, v: V3, n: V3): Float {
            val up = u - n * (u dot n); val vp = v - n * (v dot n)
            if (up.len() < 1e-5f || vp.len() < 1e-5f) return 0f
            return atan2(n dot (up cross vp), up dot vp)
        }

        @Suppress("unused")
        fun angleBetween(a: V3, b: V3) = acos((a.norm() dot b.norm()).coerceIn(-1f, 1f))
    }
}
