#!/usr/bin/env python3
"""Turn the Blender export into the app's runtime assets.

Input : .assets-src/export/zanatomy_parts.npz  (from tools/export_zanatomy.py)
Output: app/src/main/assets/anatomy.bin   (vertex + index buffers, all layers)
        app/src/main/assets/anatomy.json  (part table, rest landmarks, bones)

Steps: filter parts -> add procedural female organs -> derive rest-pose landmarks
from the skeleton -> skin every vertex to 17 bones -> quantize & pack.
Coordinates are converted from Blender (Z up, facing -Y) to app space
(Y up, facing +Z, person's left at +X), in metres.
"""
import json, re, struct, sys
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / ".assets-src/export/zanatomy_parts.npz"
OUT = ROOT / "app/src/main/assets"

# ---------------------------------------------------------------- filtering
DROP = {
    "muscles": re.compile(r"(fascia(?!e)|bursa|sheath|septum|retinacul|aponeuros|ligament|tract|tendinous arch|"
                          r"^\(|cruciform|tarsus\.|common tendinous ring|trochlea)", re.I),
    "organs": re.compile(r"(segment of liver|pleura|taenia|meso-?appendix|mesocolon|suspensory|"
                         r"anterior chamber|anterior segment of eyeball|posterior segment of eyeball|^\()", re.I),
    "brain": re.compile(r"(sulcus|ganglia|auditory tube|lacrimal|falx|cochlea|semicircular|vestibul|"
                        r"tract|fasciculus|horn of spinal|central canal|commissure)", re.I),
    "skeleton": re.compile(r"(cells of ethmoid|sinus of)", re.I),
}
MALE_ONLY = re.compile(r"(penis|testis|epididymis|prostate|seminal|ductus deferens|ejaculatory|scrot|^urethra$)", re.I)

def to_app(v):  # Blender (x, y, z) -> app (x, z, -y)
    return np.stack([v[:, 0], v[:, 2], -v[:, 1]], 1).astype(np.float32)

# ---------------------------------------------------------------- load
raw = np.load(SRC)
parts = []
for i, (name, layer) in enumerate(zip(raw["names"], raw["layers"])):
    name, layer = str(name), str(layer)
    dr = DROP.get(layer)
    if dr and dr.search(name):
        continue
    v = to_app(raw[f"v{i}"])
    n = to_app(raw[f"n{i}"])
    f = raw[f"f{i}"].astype(np.int32)
    if layer == "heart":
        layer = "organs"
    sex = "m" if MALE_ONLY.search(name) else "*"
    parts.append(dict(name=name, layer=layer, sex=sex, v=v, n=n, f=f))
print("kept parts", len(parts))
P = {p["name"]: p for p in parts}

def verts(*names):
    return np.concatenate([P[n]["v"] for n in names if n in P])

# ---------------------------------------------------------------- rest landmarks (MediaPipe indices)
def end_mean(v, axis, top, span=0.025):
    """Mean of the vertices within `span` of the extreme along `axis` (top=True for max)."""
    c = v[:, axis]
    m = c.max() if top else c.min()
    sel = v[(c >= m - span)] if top else v[(c <= m + span)]
    return sel.mean(0)

LM = {}
for s, side in (("l", 1), ("r", -1)):
    hum = verts(f"Humerus.{s}")
    fem = verts(f"Femur.{s}")
    rad = verts(f"Radius.{s}", f"Ulna.{s}")
    tib = verts(f"Tibia.{s}", f"Fibula.{s}")
    # Glenohumeral centre: top of humerus pulled slightly medial; hip: femoral head (medial top).
    sh = end_mean(hum, 1, True, 0.035)
    el = end_mean(hum, 1, False, 0.02)
    wr = end_mean(rad, 1, False, 0.015)
    top_f = fem[fem[:, 1] > fem[:, 1].max() - 0.07]
    head = top_f[np.argsort(np.abs(top_f[:, 0]))[: max(8, len(top_f) // 3)]].mean(0)
    kn = end_mean(fem, 1, False, 0.025)
    an = verts(f"Talus.{s}").mean(0)
    cal = verts(f"Calcaneus.{s}")
    heel = cal[np.argsort(cal[:, 2])[:20]].mean(0)          # most posterior (app -Z is back)
    toes = verts(f"Distal phalanx of first finger of foot.{s}", f"Distal phalanx of second finger of foot.{s}")
    ftip = toes[np.argsort(-toes[:, 2])[:20]].mean(0)
    idx = end_mean(verts(f"Second metacarpal bone.{s}"), 1, False, 0.01)
    pnk = end_mean(verts(f"Fifth metacarpal bone.{s}"), 1, False, 0.01)
    thb = end_mean(verts(f"First metacarpal bone.{s}"), 1, False, 0.01)
    eye = verts(f"Vitreous body.{s}").mean(0) if f"Vitreous body.{s}" in P else None
    tmp = verts(f"Temporal bone.{s}")
    ear = tmp[np.argsort(-side * tmp[:, 0])[:60]].mean(0)
    # MediaPipe: odd = left, even = right
    o = 0 if s == "l" else 1
    LM.update({11 + o: sh, 13 + o: el, 15 + o: wr, 17 + o: pnk, 19 + o: idx, 21 + o: thb,
               23 + o: head, 25 + o: kn, 27 + o: an, 29 + o: heel, 31 + o: ftip, 7 + o: ear, 2 + 3 * o: eye})
nas = verts("Nasal bone.l", "Nasal bone.r")
LM[0] = nas[np.argsort(-nas[:, 2])[:30]].mean(0) + np.array([0, -0.02, 0.01], np.float32)  # nose tip-ish
for k in LM:
    LM[k] = LM[k].astype(np.float32)

def mid(a, b): return (LM[a] + LM[b]) / 2

# ---------------------------------------------------------------- female organs (procedural)
def ellipsoid(c, r, nu=18, nv=12, bulge=None):
    u = np.linspace(0, 2 * np.pi, nu, endpoint=False)
    t = np.linspace(0, np.pi, nv)
    uu, tt = np.meshgrid(u, t)
    d = np.stack([np.sin(tt) * np.cos(uu), np.cos(tt), np.sin(tt) * np.sin(uu)], -1).reshape(-1, 3)
    if bulge is not None:
        d = bulge(d)
    v = c + d * r
    f = []
    for i in range(nv - 1):
        for j in range(nu):
            a, b = i * nu + j, i * nu + (j + 1) % nu
            c2, d2 = a + nu, b + nu
            f += [[a, c2, b], [b, c2, d2]]
    return v.astype(np.float32), np.array(f, np.int32)

def tube(path, radius, nu=10):
    path = np.asarray(path, np.float32)
    vs, fs = [], []
    for i, p in enumerate(path):
        t = path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)]
        t /= np.linalg.norm(t) + 1e-9
        a = np.cross(t, [0, 0, 1]) if abs(t[2]) < 0.9 else np.cross(t, [1, 0, 0])
        a /= np.linalg.norm(a)
        b = np.cross(t, a)
        r = radius[i] if hasattr(radius, "__len__") else radius
        for j in range(nu):
            ang = 2 * np.pi * j / nu
            vs.append(p + r * (np.cos(ang) * a + np.sin(ang) * b))
    for i in range(len(path) - 1):
        for j in range(nu):
            a, b = i * nu + j, i * nu + (j + 1) % nu
            fs += [[a, a + nu, b], [b, a + nu, b + nu]]
    return np.array(vs, np.float32), np.array(fs, np.int32)

def normals(v, f):
    n = np.zeros_like(v)
    fn = np.cross(v[f[:, 1]] - v[f[:, 0]], v[f[:, 2]] - v[f[:, 0]])
    for k in range(3):
        np.add.at(n, f[:, k], fn)
    return (n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-9)).astype(np.float32)

def add_part(name, layer, sex, v, f):
    # Our primitives may wind either way; flip if normals point inward on average.
    n = normals(v, f)
    if np.mean(np.sum((v - v.mean(0)) * n, 1)) < 0:
        f = f[:, ::-1].copy()
        n = -n
    p = dict(name=name, layer=layer, sex=sex, v=v, n=n, f=f)
    parts.append(p)
    P[name] = p

bl = verts("Urinary bladder")
bc = bl.mean(0)
# Uterus: pear shape, posterior-superior to the bladder, tilted forward (anteverted).
uc = bc + np.array([0, 0.035, -0.035], np.float32)
def pear(d):
    d = d.copy()
    d[:, 0] *= 1 + 0.35 * np.clip(d[:, 1], 0, 1)   # fundus wider than cervix
    return d
uv, uf = ellipsoid(np.zeros(3, np.float32), np.array([0.026, 0.04, 0.02], np.float32), bulge=pear)
tilt = np.radians(-35)
R = np.array([[1, 0, 0], [0, np.cos(tilt), -np.sin(tilt)], [0, np.sin(tilt), np.cos(tilt)]], np.float32)
add_part("Uterus", "organs", "f", uv @ R.T + uc, uf)
cv, cf = tube([uc + R @ np.array([0, -0.035, 0]), uc + R @ np.array([0, -0.065, 0]) + [0, 0, -0.005]], 0.012)
add_part("Cervix of uterus", "organs", "f", cv, cf)
vag_top = uc + R @ np.array([0, -0.065, 0], np.float32)
# The vagina ends at the vestibule, just below and behind the pubic symphysis.
hips = verts("Hip bone.l", "Hip bone.r")
sym = hips[np.abs(hips[:, 0]) < 0.015]
sym_bottom = sym[np.argmin(sym[:, 1])]
vag_end = np.array([0, sym_bottom[1] - 0.012, sym_bottom[2] - 0.03], np.float32)
vv, vf = tube([vag_top, V := (vag_top + vag_end) / 2 + [0, 0, 0.004], vag_end], [0.014, 0.013, 0.011])
add_part("Vagina", "organs", "f", vv, vf)
# Female urethra: ~4 cm from the bladder neck to just in front of the vaginal opening.
neck = bl[np.argmin(bl[:, 1])]
uv2, uf2 = tube([neck, (neck + vag_end) / 2 + [0, 0, 0.018], vag_end + [0, 0.004, 0.02]], 0.0035, 8)
add_part("Urethra (female)", "organs", "f", uv2, uf2)
fundus = uc + R @ np.array([0, 0.035, 0], np.float32)
for s, sx in (("l", 1), ("r", -1)):
    ov_c = uc + np.array([sx * 0.065, 0.01, -0.01], np.float32)
    ov, of = ellipsoid(ov_c, np.array([0.009, 0.016, 0.008], np.float32), 12, 8)
    add_part(f"Ovary.{s}", "organs", "f", ov, of)
    path = [fundus + [sx * 0.025, 0, 0], fundus + [sx * 0.055, 0.012, -0.004],
            ov_c + [sx * 0.012, 0.03, 0.005], ov_c + [sx * 0.024, 0.008, 0.008]]
    pts = []
    for a, b in zip(path[:-1], path[1:]):
        for t in np.linspace(0, 1, 6, endpoint=False):
            pts.append(a * (1 - t) + b * t)
    pts.append(path[-1])
    tv, tf = tube(pts, np.linspace(0.0025, 0.006, len(pts)), 8)
    add_part(f"Uterine tube.{s}", "organs", "f", tv, tf)
    # Mammary gland: lobed dome in front of pectoralis major at the 4th intercostal level.
    pec = verts(f"Sternocostal head of pectoralis major muscle.{s}")
    nip_y = LM[11 + (0 if s == "l" else 1)][1] - 0.15
    band = pec[np.abs(pec[:, 1] - nip_y) < 0.03]
    front = band[np.argsort(-band[:, 2])[:30]].mean(0) if len(band) > 30 else pec.mean(0)
    cx = LM[11 + (0 if s == "l" else 1)][0] * 0.62
    mc = np.array([cx, nip_y, front[2] + 0.005], np.float32)
    def lobes(d):
        d = d.copy()
        ang = np.arctan2(d[:, 1], d[:, 0])
        d *= (1 + 0.12 * np.cos(ang * 7) * np.clip(d[:, 2], 0, 1))[:, None]
        d[:, 2] = np.where(d[:, 2] < 0, d[:, 2] * 0.2, d[:, 2])  # flat back against the chest wall
        return d
    mv, mf = ellipsoid(mc, np.array([0.06, 0.055, 0.045], np.float32), 24, 14, bulge=lobes)
    add_part(f"Mammary gland.{s}", "organs", "f", mv, mf)

# ---------------------------------------------------------------- bones (skin joints)
# Each bone: head landmark position, tail position, and how its frame's secondary axis is built.
hipM, shM, earM = mid(23, 24), mid(11, 12), mid(7, 8)
BONES = [  # name, head, tail
    ("pelvis", hipM, hipM + (shM - hipM) * 0.45),
    ("chest", hipM + (shM - hipM) * 0.45, shM),
    ("neck", shM, earM),
    ("head", earM, earM + np.array([0, 0.12, 0], np.float32)),
    ("upperarm.l", LM[11], LM[13]), ("upperarm.r", LM[12], LM[14]),
    ("forearm.l", LM[13], LM[15]), ("forearm.r", LM[14], LM[16]),
    ("hand.l", LM[15], (LM[17] + LM[19]) / 2), ("hand.r", LM[16], (LM[18] + LM[20]) / 2),
    ("thigh.l", LM[23], LM[25]), ("thigh.r", LM[24], LM[26]),
    ("shin.l", LM[25], LM[27]), ("shin.r", LM[26], LM[28]),
    ("foot.l", LM[27], LM[31]), ("foot.r", LM[28], LM[32]),
]
BI = {b[0]: i for i, b in enumerate(BONES)}
heads = np.array([b[1] for b in BONES], np.float32)
tails = np.array([b[2] for b in BONES], np.float32)

def seg_dist(p):
    """Distance from points p (N,3) to every bone segment -> (N, B)."""
    ab = tails - heads
    t = np.einsum("nbk,bk->nb", p[:, None, :] - heads[None], ab) / np.sum(ab * ab, 1)[None]
    t = np.clip(t, 0, 1)
    q = heads[None] + t[..., None] * ab[None]
    return np.linalg.norm(p[:, None, :] - q, axis=2), t

RIGID = [  # skeleton name pattern -> bone (bones never bend)
    (r"humerus\.(l|r)", "upperarm"), (r"(radius|ulna)\.(l|r)", "forearm"),
    (r"(carpal|metacarpal|phalanx of .* of hand|scaphoid|lunate|triquetrum|pisiform|trapezi|capitate|hamate)", "hand"),
    (r"(femur|patella)\.(l|r)", "thigh"), (r"(tibia|fibula)\.(l|r)", "shin"),
    (r"(talus|calcaneus|navicular|cuboid|cuneiform|metatarsal|of foot|sesamoid)", "foot"),
    (r"(hip bone|sacrum|coccyx|vertebra l[345])", "pelvis"),
    (r"(rib|sternum|manubrium|xiphoid|vertebra t|vertebra l[12]|clavicle|scapula|costal)", "chest"),
    (r"(vertebra c[3-7]|axis|atlas|hyoid|thyroid cartilage|cricoid|arytenoid|corniculate)", "neck"),
]

def skin(p):
    v = p["v"]
    name = p["name"].lower()
    side = "l" if name.endswith(".l") else "r" if name.endswith(".r") else None
    if p["layer"] == "skeleton":
        bone = "head"
        for pat, b in RIGID:
            if re.search(pat, name):
                bone = b
                break
        if bone in ("upperarm", "forearm", "hand", "thigh", "shin", "foot"):
            bone += "." + (side or ("l" if v[:, 0].mean() > 0 else "r"))
        idx = np.zeros((len(v), 4), np.uint8); idx[:, 0] = BI[bone]
        w = np.zeros((len(v), 4), np.uint8); w[:, 0] = 255
        return idx, w
    d, t = seg_dist(v)
    # Keep limbs off the wrong side of the body: penalise bones of the opposite side.
    for bname, bi in BI.items():
        if bname.endswith(".l"):
            d[v[:, 0] < 0.02, bi] += 1.0
        elif bname.endswith(".r"):
            d[v[:, 0] > -0.02, bi] += 1.0
    if p["layer"] == "brain" or re.search(r"(eye|lens|cornea|iris|retina|sclera|vitreous|tongue|parotid|"
                                          r"sublingual|submandibular|palat|pharyn|tonsil|nasal|gingiva|uvula)", name):
        d[:] = 10; d[:, BI["head"]] = 0
    wgt = 1.0 / np.maximum(d, 0.004) ** 4
    order = np.argsort(-wgt, 1)[:, :4]
    top = np.take_along_axis(wgt, order, 1)
    top /= top.sum(1, keepdims=True)
    q = np.round(top * 255).astype(np.int32)
    q[:, 0] += 255 - q.sum(1)
    return order.astype(np.uint8), np.clip(q, 0, 255).astype(np.uint8)

# ---------------------------------------------------------------- colours (solid look)
COLOR_RULES = [
    (r"liver", (0.55, 0.18, 0.14)), (r"lung|bronch|trachea", (0.93, 0.62, 0.66)),
    (r"stomach|oesophagus|duoden|jejun|ileum|colon|caecum|appendix|rect", (0.88, 0.55, 0.5)),
    (r"kidney|renal", (0.6, 0.2, 0.18)), (r"spleen", (0.45, 0.15, 0.25)),
    (r"pancrea", (0.95, 0.8, 0.55)), (r"gallbladder|bile", (0.35, 0.6, 0.3)),
    (r"bladder|ureter|urethra", (0.95, 0.8, 0.45)), (r"atri|ventric|papillary|leaflet|heart", (0.75, 0.12, 0.14)),
    (r"thyroid|thymus|parathyroid|suprarenal|pituitary|hypophysis|pineal", (0.85, 0.45, 0.35)),
    (r"eye|sclera|vitreous|lens|cornea", (0.95, 0.95, 0.93)), (r"iris", (0.35, 0.5, 0.7)),
    (r"uter|ovar|vagin|cervix|mammary|testis|penis|prostat|seminal|epididym", (0.9, 0.58, 0.62)),
]
LAYER_COLOR = {"skeleton": (0.93, 0.9, 0.82), "muscles": (0.72, 0.2, 0.18), "arteries": (0.85, 0.1, 0.1),
               "veins": (0.15, 0.3, 0.85), "organs": (0.85, 0.5, 0.45), "brain": (0.92, 0.75, 0.72)}

def color_of(p):
    for pat, c in COLOR_RULES:
        if re.search(pat, p["name"], re.I):
            return c
    if p["layer"] == "muscles" and re.search(r"tendon", p["name"], re.I):
        return (0.92, 0.88, 0.8)
    return LAYER_COLOR[p["layer"]]

# ---------------------------------------------------------------- pack
LAYERS = ["skeleton", "muscles", "arteries", "veins", "organs", "brain"]
OUT.mkdir(parents=True, exist_ok=True)
blob = bytearray()
meta = {"layers": [], "landmarks": {str(k): [round(float(x), 5) for x in v] for k, v in LM.items()},
        "bones": [{"name": b[0], "head": [float(x) for x in b[1]], "tail": [float(x) for x in b[2]]} for b in BONES]}
part_id = 0
for layer in LAYERS:
    lp = sorted([p for p in parts if p["layer"] == layer], key=lambda p: p["name"])
    vbuf, ibuf, table = [], [], []
    base = 0
    istart = 0
    for p in lp:
        idx, w = skin(p)
        n8 = np.clip(np.round(p["n"] * 127), -127, 127).astype(np.int8)
        rec = np.zeros(len(p["v"]), dtype=[("pos", "<f4", 3), ("nrm", "i1", 4), ("bi", "u1", 4), ("bw", "u1", 4), ("pid", "<u2"), ("pad", "<u2")])
        rec["pos"] = p["v"]; rec["nrm"][:, :3] = n8; rec["pid"] = part_id
        rec["bi"] = idx; rec["bw"] = w
        vbuf.append(rec)
        ibuf.append((p["f"] + base).astype("<u4").reshape(-1))
        # Label anchor: vertex closest to the centroid, so it rides on the surface.
        c = p["v"].mean(0)
        anchor = int(np.argmin(np.linalg.norm(p["v"] - c, axis=1)))
        table.append({"id": part_id, "name": p["name"], "sex": p["sex"], "first": istart, "count": int(p["f"].size),
                      "anchor": base + anchor, "color": [round(x, 3) for x in color_of(p)]})
        base += len(p["v"]); istart += p["f"].size; part_id += 1
    vb = np.concatenate(vbuf).tobytes()
    ib = np.concatenate(ibuf).tobytes()
    meta["layers"].append({"name": layer, "vOffset": len(blob), "vCount": base, "iOffset": len(blob) + len(vb),
                           "iCount": istart, "parts": table})
    blob += vb + ib
    print(f"{layer:9s} parts={len(lp):4d} verts={base:7d} tris={istart // 3:7d}")
(OUT / "anatomy.bin").write_bytes(bytes(blob))
(OUT / "anatomy.json").write_text(json.dumps(meta, separators=(",", ":")))
print("anatomy.bin", len(blob) // 1024, "KiB; parts", part_id)
