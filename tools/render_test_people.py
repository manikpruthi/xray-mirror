# Headless Blender: renders skin-toned figures of the Z-Anatomy model to test pose tracking
# without a real person. Outputs portrait PNGs (front, side, arms raised) for the app's
# test-image hook.
#   Blender -b Startup.blend --python tools/render_test_people.py -- <out_dir>
import bpy, sys, os, math, mathutils

out = sys.argv[sys.argv.index("--") + 1]
os.makedirs(out, exist_ok=True)
scene = bpy.context.scene
top = {c.name: c for c in scene.collection.children}

def objs(c):
    o = list(c.objects)
    for ch in c.children:
        o += objs(ch)
    return o

keep = set()
for key in ["4: Muscular system", "1: Skeletal system"]:
    for o in objs(top[key]):
        if o.type == "MESH" and not o.hide_get() and len(o.data.polygons) > 0:
            keep.add(o.name)
for o in scene.objects:
    o.hide_render = o.name not in keep

scene.render.engine = "BLENDER_WORKBENCH"
scene.display.shading.light = "STUDIO"
scene.display.shading.color_type = "SINGLE"
scene.display.shading.single_color = (0.80, 0.58, 0.46)
scene.display.shading.show_cavity = True
scene.render.resolution_x = 720
scene.render.resolution_y = 1280
scene.render.film_transparent = False
world = bpy.data.worlds.new("w") if scene.world is None else scene.world
scene.world = world
scene.display.shading.background_type = "VIEWPORT"
scene.display.shading.background_color = (0.32, 0.34, 0.37)

cam_data = bpy.data.cameras.new("testcam")
cam_data.lens = 35
cam = bpy.data.objects.new("testcam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam

def shoot(name, yaw_deg):
    r = 3.6
    a = math.radians(yaw_deg)
    # Model faces -Y; camera in front at -Y, rotated around Z by yaw.
    cam.location = (r * math.sin(a), -r * math.cos(a), 0.95)
    direction = mathutils.Vector((0, 0, 0.9)) - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = os.path.join(out, name)
    bpy.ops.render.render(write_still=True)

shoot("person_front.png", 0)
shoot("person_side.png", 90)
shoot("person_quarter.png", 35)

# Crude "arms raised" pose: rotate everything on each arm about the shoulder (good enough for a test).
for side, sgn in (("l", 1), ("r", -1)):
    hum = bpy.data.objects[f"Humerus.{side}"]
    bb = [hum.matrix_world @ mathutils.Vector(c) for c in hum.bound_box]
    shoulder = max(bb, key=lambda v: v.z)
    pivot = mathutils.Vector((shoulder.x, (min(v.y for v in bb) + max(v.y for v in bb)) / 2, shoulder.z - 0.02))
    rot = mathutils.Matrix.Translation(pivot) @ mathutils.Matrix.Rotation(math.radians(130) * sgn, 4, "Y") @ mathutils.Matrix.Translation(-pivot)
    for n in keep:
        o = bpy.data.objects[n]
        c = sum((o.matrix_world @ mathutils.Vector(x) for x in o.bound_box), mathutils.Vector()) / 8
        if sgn * c.x > abs(shoulder.x) - 0.02 and c.z < shoulder.z + 0.05 and c.z > 0.55:
            o.matrix_world = rot @ o.matrix_world
shoot("person_arms_up.png", 0)
print("done")
