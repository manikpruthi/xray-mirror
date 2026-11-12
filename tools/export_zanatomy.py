# Headless Blender script: exports Z-Anatomy parts into per-layer .npz dumps.
#   Blender -b Startup.blend --python tools/export_zanatomy.py -- <out_dir>
# Each visible mesh/curve with geometry becomes one "part"; curves (vessels, oesophagus)
# are converted to meshes through the evaluated depsgraph so their bevels become tubes.
import bpy, bmesh, sys, os, re
import numpy as np

out_dir = sys.argv[sys.argv.index("--") + 1]
os.makedirs(out_dir, exist_ok=True)

top = {c.name: c for c in bpy.context.scene.collection.children}

def objs(c):
    out = list(c.objects)
    for ch in c.children:
        out += objs(ch)
    return out

def collection_path(o):
    return "/".join(c.name for c in o.users_collection)

VEIN = re.compile(r"\b(vein|veins|venous|sinus|vena|venae|plexus)\b", re.I)
BRAIN = re.compile(r"(brain|cerebr|cerebell|gyrus|lobe|ventricle of brain|thalam|pons|medulla|midbrain|"
                   r"hippocamp|corpus callosum|caudate|putamen|pallid|amygdal|insula|fornix|"
                   r"pituitary|pineal|colliculus|hypothal|spinal cord|cortex|sulcus|nucleus|tract|"
                   r"lateral ventricle|third ventricle|fourth ventricle|aqueduct|choroid plexus)", re.I)
EYE = re.compile(r"(eyeball|lens|cornea|iris|retina|sclera|vitreous|optic|eye)", re.I)
SKIP_VISCERA = re.compile(r"(peritone|fascia|omentum|mesenter|ligament|region|cavity|recess|pouch|fossa)", re.I)
SKIP_NERVOUS = re.compile(r"(nerve|ganglion|plexus|ramus|branch|root)", re.I)

def layer_for(o, coll_key):
    n = o.name
    if coll_key == "1: Skeletal system":
        return "skeleton"
    if coll_key == "4: Muscular system":
        return "muscles"
    if coll_key == "5: Cardiovascular system":
        if o.type == "MESH" and re.search(r"(atri|ventric|leaflet|papillary|valve|heart|cusp|chordae)", n, re.I):
            return "heart"
        return "veins" if VEIN.search(n) else "arteries"
    if coll_key == "6: Lymphoid organs":
        return "organs" if re.search(r"(spleen|thymus|tonsil)", n, re.I) else None
    if coll_key == "7: Nervous system & Sense organs":
        if o.type == "CURVE" or SKIP_NERVOUS.search(n):
            return None
        if EYE.search(n):
            return "organs"
        return "brain"
    if coll_key == "8: Visceral systems":
        if SKIP_VISCERA.search(n):
            return None
        return "organs"
    return None

# Triangle budgets per layer (after decimation). Tuned for mid-range phones; the Pixel 8 has headroom.
BUDGET = {"skeleton": 130_000, "muscles": 160_000, "arteries": 45_000, "veins": 35_000,
          "organs": 110_000, "heart": 16_000, "brain": 35_000}

# Curve tubes: vessels stay light (6-sided), hollow organs (gut, oesophagus) get rounder tubes.
VISCERA_CURVES = set()
for o in objs(top["8: Visceral systems"]):
    if o.type == "CURVE":
        VISCERA_CURVES.add(o.name)
for o in bpy.data.objects:
    if o.type == "CURVE":
        o.data.bevel_resolution = 2 if o.name in VISCERA_CURVES else 1
        o.data.resolution_u = 6 if o.name in VISCERA_CURVES else 3
        if o.data.bevel_depth > 0 and o.data.bevel_object is None:
            o.data.fill_mode = "FULL"

parts = {}
for key in ["1: Skeletal system", "4: Muscular system", "5: Cardiovascular system",
            "6: Lymphoid organs", "7: Nervous system & Sense organs", "8: Visceral systems"]:
    for o in set(objs(top[key])):
        if o.type not in ("MESH", "CURVE") or o.hide_get() or o.hide_viewport:
            continue
        if o.type == "MESH" and len(o.data.polygons) == 0:
            continue
        if o.type == "CURVE" and len(o.data.splines) == 0:
            continue
        layer = layer_for(o, key)
        if layer:
            parts[o.name] = (o, layer)

print("parts", len(parts))

deps = bpy.context.evaluated_depsgraph_get()

def tri_mesh(o):
    ev = o.evaluated_get(deps)
    me = ev.to_mesh()
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.triangulate(bm, faces=bm.faces)
    bm.transform(o.matrix_world)
    ev.to_mesh_clear()
    return bm

# First pass: triangle counts per layer to derive decimation ratios.
counts = {}
for name, (o, layer) in parts.items():
    bm = tri_mesh(o)
    counts[name] = len(bm.faces)
    bm.free()
layer_total = {}
for name, (o, layer) in parts.items():
    layer_total[layer] = layer_total.get(layer, 0) + counts[name]
print("raw tris", layer_total)

data = {}
for name, (o, layer) in parts.items():
    ratio = min(1.0, BUDGET[layer] / max(1, layer_total[layer]))
    bm = tri_mesh(o)
    if ratio < 0.98 and len(bm.faces) > 60:
        # Collapse decimation through a temp mesh + modifier (bmesh has no decimate op).
        tmp = bpy.data.meshes.new("tmp")
        bm.to_mesh(tmp)
        bm.free()
        tob = bpy.data.objects.new("tmp", tmp)
        bpy.context.scene.collection.objects.link(tob)
        mod = tob.modifiers.new("dec", "DECIMATE")
        mod.ratio = max(ratio, 24 / max(1, counts[name]))
        mod.use_collapse_triangulate = True
        d2 = bpy.context.evaluated_depsgraph_get()
        me = tob.evaluated_get(d2).to_mesh()
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.triangulate(bm, faces=bm.faces)
        tob.evaluated_get(d2).to_mesh_clear()
        bpy.data.objects.remove(tob)
        bpy.data.meshes.remove(tmp)
    bm.verts.index_update()
    bm.normal_update()
    if len(bm.faces) == 0:
        bm.free()
        continue
    v = np.array([vv.co[:] for vv in bm.verts], np.float32)
    nrm = np.array([vv.normal[:] for vv in bm.verts], np.float32)
    f = np.array([[vv.index for vv in fa.verts] for fa in bm.faces], np.int32)
    bm.free()
    data[name] = (layer, collection_path(o), v, nrm, f)

final = {}
for name, (layer, coll, v, n, f) in data.items():
    final[layer] = final.get(layer, 0) + len(f)
print("final tris", final)

names = sorted(data)
np.savez_compressed(
    os.path.join(out_dir, "zanatomy_parts.npz"),
    names=np.array(names),
    layers=np.array([data[n][0] for n in names]),
    colls=np.array([data[n][1] for n in names]),
    **{f"v{i}": data[n][2] for i, n in enumerate(names)},
    **{f"n{i}": data[n][3] for i, n in enumerate(names)},
    **{f"f{i}": data[n][4] for i, n in enumerate(names)},
)
print("wrote", len(names))
