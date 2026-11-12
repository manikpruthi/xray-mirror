package dev.manik.xraymirror.gl

import android.opengl.GLES30.*
import android.util.Log

object Shaders {
    /** Shared helper: upright-image uv → raw texture uv for a clockwise rotation in degrees. */
    private const val ROT = """
vec2 rawUv(vec2 u, int rot) {
    if (rot == 90) return vec2(u.y, 1.0 - u.x);
    if (rot == 180) return vec2(1.0 - u.x, 1.0 - u.y);
    if (rot == 270) return vec2(1.0 - u.y, u.x);
    return u;
}
"""

    /** Body mask + reveal region, shared by the background and anatomy passes. fy is Y-up. */
    private const val MASK = """
uniform sampler2D uMask;
uniform int uMaskRot;
uniform float uHasMask;
uniform float uFeather;    // in mask uv units
uniform vec3 uLens;        // frame-normalised centre (x, y-up) and radius in frame-height units; z<0 = whole body
uniform float uAspect;     // frame width / height
float bodyMask(vec2 f) {
    if (uHasMask < 0.5) return 1.0;
    vec2 u = rawUv(vec2(f.x, 1.0 - f.y), uMaskRot);
    float m = texture(uMask, u).r * 0.4;
    m += texture(uMask, u + vec2(uFeather, 0.0)).r * 0.15;
    m += texture(uMask, u - vec2(uFeather, 0.0)).r * 0.15;
    m += texture(uMask, u + vec2(0.0, uFeather)).r * 0.15;
    m += texture(uMask, u - vec2(0.0, uFeather)).r * 0.15;
    return smoothstep(0.25, 0.75, m);
}
float revealMask(vec2 f) {
    if (uLens.z < 0.0) return 1.0;
    vec2 d = vec2((f.x - uLens.x) * uAspect, f.y - uLens.y);
    float r = length(d);
    return 1.0 - smoothstep(uLens.z * 0.85, uLens.z, r);
}
"""

    const val QUAD_VS = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

    /** Camera background: screen uv → frame → raw camera texel; dims the body where anatomy shows. */
    val BG_FS = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uCam;
uniform int uCamRot;
uniform vec4 uView;   // (mirrorSign, visibleW/W, visibleH/H, 0)
uniform vec2 uCenter; // view centre in frame-normalised coords (zoom/pan)
uniform float uRealMe;
uniform float uBgDim;
uniform float uHasCam;
uniform float uShowMask;
$ROT
$MASK
void main() {
    vec2 f = vec2(uCenter.x + uView.x * (vUv.x - 0.5) * uView.y, uCenter.y + (vUv.y - 0.5) * uView.z);
    vec3 cam = uHasCam > 0.5 ? texture(uCam, rawUv(vec2(f.x, 1.0 - f.y), uCamRot)).rgb : vec3(0.0);
    float body = bodyMask(f) * revealMask(f);
    vec3 c = cam * mix(uBgDim, uRealMe, body);
    if (uShowMask > 0.5) c = mix(c, vec3(0.1, 1.0, 0.4), bodyMask(f) * 0.35);
    frag = vec4(c, 1.0);
}"""

    const val BLIT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uTex;
uniform vec4 uCrop; // uv offset.xy, uv scale.zw
void main() { frag = vec4(texture(uTex, uCrop.xy + vUv * uCrop.zw).rgb, 1.0); }"""

    /** Composites the anatomy buffer over the background: tone-mapped screen blend (X-ray) or alpha (Solid). */
    const val COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uAnat;
uniform float uSolid;
uniform float uExposure;
void main() {
    vec4 a = texture(uAnat, vUv);
    if (uSolid > 0.5) { frag = a; return; }
    vec3 t = 1.0 - exp(-a.rgb * uExposure);
    frag = vec4(t, 1.0);
}"""

    val ANATOMY_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNrm;
layout(location = 2) in uvec4 aBi;
layout(location = 3) in vec4 aBw;
layout(location = 4) in uint aPid;
uniform mat4 uBones[16];
uniform mat4 uProj;
uniform vec2 uFrame;          // frame size in px
uniform highp sampler2D uParts; // rgb colour, a = visible
uniform int uSelected;
out vec3 vN;
out vec3 vColor;
out vec2 vF;
out float vSel;
flat out float vPidF;
void main() {
    vec4 info = texelFetch(uParts, ivec2(int(aPid) % 2048, int(aPid) / 2048), 0);
    if (info.a < 0.5) { gl_Position = vec4(4.0, 4.0, 4.0, 1.0); return; }
    ivec4 b = ivec4(aBi);
    mat4 s = uBones[b.x] * aBw.x + uBones[b.y] * aBw.y + uBones[b.z] * aBw.z + uBones[b.w] * aBw.w;
    vec4 p = s * vec4(aPos, 1.0);
    vN = normalize(mat3(s) * aNrm.xyz);
    vColor = info.rgb;
    vF = p.xy / uFrame;
    vSel = int(aPid) == uSelected ? 1.0 : 0.0;
    vPidF = float(aPid);
    gl_Position = uProj * p;
}"""

    val XRAY_FS = """#version 300 es
precision highp float;
in vec3 vN;
in vec3 vColor;
in vec2 vF;
in float vSel;
out vec4 frag;
uniform vec3 uTint;
uniform float uPartMix;   // how much per-part colour shows through the layer tint
uniform float uCore;
uniform float uRim;
uniform float uGain;
uniform float uClip;
$ROT
$MASK
void main() {
    float ndv = abs(normalize(vN).z);
    float rim = pow(1.0 - ndv, 2.2);
    vec3 col = mix(uTint, vColor * 1.3, uPartMix);
    col = mix(col, vec3(0.4, 1.0, 0.95), vSel * 0.85);
    float g = (uCore + rim * uRim) * uGain * (1.0 + vSel * 2.5);
    float clip = revealMask(vF) * mix(1.0, bodyMask(vF), uClip);
    frag = vec4(col * g * clip, 1.0);
}"""

    val SOLID_FS = """#version 300 es
precision highp float;
in vec3 vN;
in vec3 vColor;
in vec2 vF;
in float vSel;
out vec4 frag;
uniform float uGain;
uniform float uClip;
$ROT
$MASK
void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    vec3 l = normalize(vec3(-0.35, 0.55, 1.0));
    float diff = max(dot(n, l), 0.0);
    float rim = pow(1.0 - abs(n.z), 3.0);
    vec3 col = vColor * (0.28 + 0.8 * diff) + rim * 0.18 + pow(max(dot(reflect(-l, n), vec3(0, 0, 1)), 0.0), 24.0) * 0.25;
    col = mix(col, vec3(0.3, 1.0, 0.9), vSel * 0.55);
    float clip = revealMask(vF) * mix(1.0, bodyMask(vF), uClip);
    if (clip < 0.02) discard;
    frag = vec4(col * uGain, clip);
}"""

    /** ID pass for tap-to-identify: part id + 1 packed into RGB. */
    /** ID pass for tap-to-identify: part id + 1 in red/green bytes (float maths: some GPUs mishandle uint shifts). */
    const val PICK_FS = """#version 300 es
precision highp float;
flat in float vPidF;
out vec4 frag;
void main() {
    float id = vPidF + 1.0;
    float hi = floor(id / 256.0);
    frag = vec4((id - hi * 256.0) / 255.0, hi / 255.0, 0.0, 1.0);
}"""

    const val FLAT_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform vec2 uScreen;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);
}"""

    const val FLAT_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform float uUseTex;
void main() {
    vec4 t = uUseTex > 0.5 ? texture(uTex, vUv) : vec4(1.0);
    frag = t * uColor;
}"""

    fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = glGetShaderInfoLog(s)
                Log.e("Shaders", "compile failed: $log\n$src")
                error("shader compile failed: $log")
            }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link failed: ${glGetProgramInfoLog(p)}")
        return p
    }
}

/** Caches uniform locations per program. */
class Prog(vs: String, fs: String) {
    val id = Shaders.program(vs, fs)
    private val locs = HashMap<String, Int>()
    fun u(name: String) = locs.getOrPut(name) { glGetUniformLocation(id, name) }
    fun use() = glUseProgram(id)
}
