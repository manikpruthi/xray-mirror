#!/usr/bin/env python3
"""Curated labels + one-line facts. Writes app/src/main/assets/facts.json and checks every
regex/anchor against the packed part names so nothing silently fails to match.

Fields: title, layer, match (regex over part names, case-insensitive), anchor (part that carries
the on-screen label; omit for tap-only entries), fact.
Order matters: the first matching entry wins on tap, and earlier entries win label slots.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
F = []
def e(layer, title, match, fact, anchor=None):
    F.append({"layer": layer, "title": title, "match": match, "fact": fact, **({"anchor": anchor} if anchor else {})})

# ---------------- skeleton
S = "skeleton"
e(S, "Skull (frontal bone)", r"^frontal bone$", "The skull has 22 bones; all but the mandible are locked together by sutures.", "Frontal bone")
e(S, "Parietal bone", r"^parietal bone", "The two parietal bones form the roof and sides of the skull.")
e(S, "Occipital bone", r"^occipital bone$", "The spinal cord leaves the skull through the foramen magnum in this bone.")
e(S, "Temporal bone", r"^temporal bone", "Holds the smallest bones in the body, the ear ossicles, deep inside.")
e(S, "Mandible", r"^mandible$", "The jaw is the only skull bone that moves, and the strongest bone of the face.", "Mandible")
e(S, "Maxilla", r"^maxilla", "The two maxillae hold the upper teeth and form the floor of each eye socket.")
e(S, "Zygomatic bone", r"^zygomatic bone", "The cheekbone; it forms the outer wall of the eye socket.")
e(S, "Nasal bone", r"^nasal bone", "Only the bridge of the nose is bone; the rest of its shape is cartilage.")
e(S, "Teeth", r"(incisor|canine|premolar|molar)", "Enamel, the outer layer of teeth, is the hardest substance in the human body.")
e(S, "Ear ossicles", r"^(malleus|incus|stapes)", "The stapes, about 3 mm long, is the smallest bone in the body.")
e(S, "Hyoid bone", r"^hyoid", "The only bone that doesn't touch another bone; it anchors the tongue.")
e(S, "Clavicle", r"^clavicle", "The collarbone is the most commonly broken bone in the body.", "Clavicle.l")
e(S, "Scapula", r"^scapula", "The shoulder blade attaches to the trunk only through muscles and the clavicle.", "Scapula.r")
e(S, "Sternum", r"(sternum|xiphoid)", "The breastbone has three parts: manubrium, body and xiphoid process.", "Body of sternum")
e(S, "Ribs", r"(rib\.|costal cartilage)", "12 pairs: 7 attach straight to the sternum, 3 indirectly, 2 'float' free.", "Fifth rib.l")
e(S, "Cervical vertebrae", r"(vertebra c|atlas|axis)", "Seven neck vertebrae, the same count as in a giraffe.", "Vertebra C5")
e(S, "Thoracic vertebrae", r"vertebra t", "Twelve vertebrae, each paired with a rib.")
e(S, "Lumbar vertebrae", r"vertebra l", "The five largest vertebrae carry most of the upper body's weight.", "Vertebra L3")
e(S, "Sacrum", r"^sacrum$", "Five vertebrae that fuse into one bone between ages 18 and 30.", "Sacrum")
e(S, "Coccyx", r"^coccyx$", "The tailbone: 3–5 small fused vertebrae.")
e(S, "Pelvis (hip bone)", r"^hip bone", "Each hip bone is three bones fused together: ilium, ischium and pubis.", "Hip bone.l")
e(S, "Humerus", r"^humerus", "The 'funny bone' tingle is the ulnar nerve crossing the back of the humerus's lower end.", "Humerus.r")
e(S, "Radius", r"^radius", "Rotates around the ulna to turn the palm up or down.", "Radius.l")
e(S, "Ulna", r"^ulna", "Its hook-shaped top, the olecranon, is the point of your elbow.")
e(S, "Hand bones", r"(metacarpal|phalanx of .* of hand|scaphoid|lunate|triquetrum|pisiform|trapezi|capitate|hamate)",
  "Each hand has 27 bones: 8 carpals, 5 metacarpals and 14 phalanges.", "Third metacarpal bone.r")
e(S, "Femur", r"^femur", "The thighbone is the longest and strongest bone in the body.", "Femur.r")
e(S, "Patella", r"^patella", "The kneecap is the largest sesamoid bone, a bone that forms inside a tendon.", "Patella.l")
e(S, "Tibia", r"^tibia", "The shinbone carries about 85% of the body's weight through the lower leg.", "Tibia.l")
e(S, "Fibula", r"^fibula", "Carries little weight; surgeons sometimes harvest it as a bone graft.")
e(S, "Foot bones", r"(talus|calcaneus|navicular|cuboid|cuneiform|metatarsal|of foot|sesamoid)",
  "Each foot has 26 bones; the calcaneus (heel bone) is the largest.", "Calcaneus.r")
e(S, "Laryngeal cartilage", r"(thyroid cartilage|cricoid|arytenoid|corniculate)", "The thyroid cartilage forms the Adam's apple.")

# ---------------- muscles
M = "muscles"
e(M, "Frontalis", r"^frontalis", "Raises the eyebrows and wrinkles the forehead.")
e(M, "Temporalis", r"^temporalis", "A fan-shaped chewing muscle you can feel at your temple when you clench.", "Temporalis muscle.l")
e(M, "Masseter", r"masseter", "Relative to its size, one of the strongest muscles in the body; it closes the jaw.", "Superficial part of masseter.r")
e(M, "Orbicularis oculi", r"orbicularis oculi", "Closes the eyelids; it fires every time you blink.")
e(M, "Orbicularis oris", r"orbicularis oris", "The ring muscle that purses the lips.")
e(M, "Zygomaticus", r"^zygomaticus", "The main smiling muscle; it pulls the mouth corners up and out.")
e(M, "Sternocleidomastoid", r"sternocleidomastoid", "Turns the head to the opposite side and tilts it.", "Sternocleidomastoid muscle.l")
e(M, "Trapezius", r"trapezius", "Moves, rotates and steadies the shoulder blades.", "Descending part of trapezius muscle.r")
e(M, "Deltoid", r"deltoid muscle", "Lifts the arm. Its three heads move it forward, sideways and back.", "Acromial part of deltoid muscle.l")
e(M, "Pectoralis major", r"pectoralis major", "Pulls the arm across the chest; the main muscle used in push-ups.", "Sternocostal head of pectoralis major muscle.r")
e(M, "Pectoralis minor", r"pectoralis minor", "Lies under pectoralis major and steadies the scapula.")
e(M, "Serratus anterior", r"serratus anterior", "The 'boxer's muscle': it drives the shoulder blade forward when you punch.", "Serratus anterior muscle.l")
e(M, "Latissimus dorsi", r"latissimus dorsi", "The widest muscle of the back; it powers pull-ups and swimming strokes.", "Latissimus dorsi muscle.r")
e(M, "Rhomboids", r"rhomboid", "Squeeze the shoulder blades together.")
e(M, "Rotator cuff", r"(supraspinatus|infraspinatus|teres minor|subscapularis)", "Four muscles that hold the ball of the humerus in its shallow socket.")
e(M, "Biceps brachii", r"biceps brachii", "Bends the elbow and turns the palm up, the motion of turning a screwdriver.", "Long head of biceps brachii.l")
e(M, "Triceps brachii", r"triceps brachii", "Straightens the elbow; it makes up about two thirds of the upper arm's bulk.", "Lateral head of triceps brachii.r")
e(M, "Brachioradialis", r"brachioradialis", "Bends the elbow most strongly when the thumb points up, as in a hammer curl.", "Brachioradialis muscle.l")
e(M, "Forearm flexors", r"(flexor (carpi|digitorum|pollicis)|palmaris longus|pronator)", "Most of the muscles that bend your fingers sit in the forearm and act through long tendons.")
e(M, "Forearm extensors", r"(extensor (carpi|digitorum|digiti|indicis|pollicis)|supinator|abductor pollicis longus)", "Straighten the wrist and fingers; overuse causes 'tennis elbow'.")
e(M, "Hand muscles", r"(lumbrical|interossei|opponens|abductor pollicis brevis|adductor pollicis|flexor digiti minimi of hand|abductor digiti minimi of hand|flexor pollicis brevis)",
  "Small muscles inside the hand provide fine control. The thumb has its own set.")
e(M, "Rectus abdominis", r"rectus abdominis", "The 'six-pack'; tendon bands across it create the segments.", "Rectus abdominis muscle.l")
e(M, "External oblique", r"external abdominal oblique", "Twists and bends the trunk; its fibres run like hands in front pockets.", "External abdominal oblique muscle.r")
e(M, "Internal oblique / transversus", r"(internal abdominal oblique|transversus abdominis)", "Deep layers that brace the abdomen like a natural corset.")
e(M, "Intercostals", r"intercostal muscles", "Muscles between the ribs that help you breathe.")
e(M, "Diaphragm", r"diaphragm", "The main breathing muscle; it flattens to draw air into the lungs.")
e(M, "Erector spinae", r"(iliocostalis|longissimus|spinalis)", "Long columns along the spine that keep you upright.")
e(M, "Gluteus maximus", r"gluteus maximus", "The largest muscle in the body; it extends the hip when you climb or stand.", "Gluteus maximus muscle.l")
e(M, "Gluteus medius / minimus", r"gluteus (medius|minimus)", "Keep the pelvis level each time you stand on one leg while walking.")
e(M, "Iliopsoas", r"(psoas|iliacus)", "The strongest hip flexor; it lifts the thigh toward the trunk.")
e(M, "Sartorius", r"sartorius", "The longest muscle in the body; it crosses the thigh diagonally.", "Sartorius muscle.r")
e(M, "Quadriceps", r"(rectus femoris|vastus)", "Four muscles that straighten the knee; together the strongest group in the body.", "Rectus femoris muscle.l")
e(M, "Adductors", r"(adductor (longus|brevis|magnus)|gracilis|pectineus)", "Pull the legs together; 'groin strains' tear these.", "Adductor longus.r")
e(M, "Hamstrings", r"(biceps femoris|semitendinosus|semimembranosus)", "Three muscles that bend the knee and extend the hip.", "Long head of biceps femoris.l")
e(M, "Tensor fasciae latae", r"tensor fasciae latae", "Tightens the iliotibial band to steady the knee.")
e(M, "Tibialis anterior", r"tibialis anterior", "Lifts the foot. If it is weak, the toes drag ('foot drop').", "Tibialis anterior muscle.r")
e(M, "Gastrocnemius", r"gastrocnemius", "The calf muscle; it joins the soleus in the Achilles tendon.", "Medial head of gastrocnemius.l")
e(M, "Soleus", r"soleus", "Called the 'second heart' because it pumps blood back up the leg.", "Soleus muscle.r")
e(M, "Achilles tendon", r"calcaneal tendon", "The thickest and strongest tendon in the body.")
e(M, "Fibularis muscles", r"fibularis", "Turn the sole outward and steady the ankle.")
e(M, "Platysma", r"platysma", "A thin sheet over the front of the neck that tenses its skin.")
e(M, "Tongue muscles", r"(genioglossus|hyoglossus|styloglossus)", "The tongue is a group of eight muscles with no bone inside.")
e(M, "Eye muscles", r"(rectus muscle|oblique muscle|levator palpebrae)", "Six muscles aim each eye and make some of the fastest movements in the body.")

# ---------------- arteries
A = "arteries"
e(A, "Aorta", r"(aorta|aortic)", "The largest artery: about 2.5 cm wide where it leaves the heart.", "Ascending aorta")
e(A, "Coronary arteries", r"coronary", "Supply the heart muscle itself; a blockage here causes a heart attack.")
e(A, "Common carotid artery", r"common carotid", "The pulse you feel at the side of your neck.", "Left common carotid artery")
e(A, "Internal carotid artery", r"internal carotid", "Supplies most of the blood to the front of the brain and the eye.")
e(A, "External carotid artery", r"external carotid", "Supplies the face, scalp and jaws.")
e(A, "Vertebral artery", r"vertebral artery", "Runs up through holes in the neck vertebrae to the back of the brain.")
e(A, "Circle of Willis", r"(cerebral artery|communicating artery|basilar artery|cerebral arterial)", "A ring of arteries under the brain that reroutes blood if one artery is blocked.")
e(A, "Subclavian artery", r"subclavian artery", "Carries blood under the collarbone into each arm.", "Right subclavian artery")
e(A, "Axillary artery", r"axillary artery", "The armpit section of the main arm artery.")
e(A, "Brachial artery", r"brachial artery", "The artery a blood-pressure cuff presses on.", "Brachial artery.l")
e(A, "Radial artery", r"radial artery", "The pulse you take at the wrist, on the thumb side.", "Radial artery.r")
e(A, "Ulnar artery", r"ulnar artery", "Joins the radial artery in arches across the palm.")
e(A, "Pulmonary trunk & arteries", r"pulmonary (trunk|artery)|artery of (right|left) lung|lobar artery|segmental artery of", "The only arteries that carry oxygen-poor blood, from the heart to the lungs.", "Pulmonary trunk")
e(A, "Coeliac trunk", r"coeliac|celiac", "Feeds the stomach, liver and spleen.")
e(A, "Renal artery", r"renal artery", "About a fifth of the heart's output goes to the kidneys.", "Left renal artery")
e(A, "Mesenteric arteries", r"mesenteric artery", "Supply the intestines.")
e(A, "Common iliac artery", r"common iliac artery", "The aorta splits into these two at about navel level.", "Common iliac artery.r")
e(A, "Femoral artery", r"femoral artery", "The main artery of the thigh; you can feel its pulse in the groin.", "Femoral artery.l")
e(A, "Popliteal artery", r"popliteal artery", "Runs behind the knee.")
e(A, "Tibial arteries", r"tibial artery", "Supply the lower leg; the posterior one has a pulse behind the ankle bone.", "Anterior tibial artery.r")
e(A, "Dorsalis pedis", r"dorsal(is)? (artery of foot|pedis)", "A pulse on top of the foot that doctors check for circulation.")

# ---------------- veins
V = "veins"
e(V, "Superior vena cava", r"superior vena cava", "Returns blood from the head, neck and arms to the heart.", "Superior vena cava")
e(V, "Inferior vena cava", r"inferior vena cava", "The largest vein; it returns blood from the lower body.", "Inferior vena cava (abdominal part)")
e(V, "Internal jugular vein", r"internal jugular", "Drains the brain; sometimes visible pulsing in the neck.", "Internal jugular vein.r")
e(V, "External jugular vein", r"external jugular", "Often visible bulging in the neck when you strain.")
e(V, "Dural venous sinuses", r"sinus", "Channels between layers of the brain's outer membrane that drain blood from the brain.")
e(V, "Subclavian vein", r"subclavian vein", "A common entry point for central IV lines.")
e(V, "Brachiocephalic vein", r"brachiocephalic vein", "The left and right ones join to form the superior vena cava.")
e(V, "Cephalic vein", r"cephalic vein", "A superficial vein on the thumb side of the arm.", "Cephalic vein.l")
e(V, "Basilic vein", r"basilic vein", "A superficial vein on the little-finger side of the arm.", "Basilic vein.r")
e(V, "Median cubital vein", r"median cubital", "The vein at the inner elbow used for most blood draws.")
e(V, "Pulmonary veins", r"pulmonary vein|vein of (right|left) lung|lobar vein|segmental vein", "The only veins that carry oxygen-rich blood, from the lungs to the heart.")
e(V, "Hepatic portal vein", r"portal vein", "Carries nutrient-rich blood from the gut to the liver for processing.", "Hepatic portal vein")
e(V, "Renal vein", r"renal vein", "Returns filtered blood from the kidneys.")
e(V, "Common iliac vein", r"common iliac vein", "The two join to form the inferior vena cava.")
e(V, "Femoral vein", r"femoral vein", "The main deep vein of the thigh.", "Femoral vein.r")
e(V, "Great saphenous vein", r"great saphenous", "The longest vein in the body, ankle to groin; often used for heart bypass grafts.", "Great saphenous vein.l")
e(V, "Small saphenous vein", r"small saphenous", "Runs up the back of the calf.")
e(V, "Azygos vein", r"azygos", "Drains the chest wall and can bypass a blocked vena cava.")

# ---------------- organs
O = "organs"
e(O, "Heart", r"(ventricle|atrium|papillary|leaflet|valve)", "Beats about 100,000 times a day and pumps roughly 7,000 litres of blood.", "Left ventricle")
e(O, "Lungs", r"lobe of (right|left) lung", "The right lung has three lobes and the left has two, leaving room for the heart.", "Superior lobe of right lung")
e(O, "Left lung", r"lobe of left lung", "Has a notch, the cardiac notch, where the heart sits.", "Inferior lobe of left lung")
e(O, "Trachea", r"^trachea$", "The windpipe, kept open by 16–20 C-shaped rings of cartilage.", "Trachea")
e(O, "Bronchi", r"bronch", "The airways branch about 23 times before reaching the air sacs.")
e(O, "Liver", r"^liver$", "The largest internal organ. It handles more than 500 tasks and can regrow lost tissue.", "Liver")
e(O, "Gallbladder", r"gallbladder|bile duct|cystic duct|hepatic duct", "Stores bile, which helps digest fat.", "Gallbladder")
e(O, "Stomach", r"^stomach$", "Its acid is about as strong as lemon juice or stronger; a mucus layer keeps it from digesting itself.", "Stomach")
e(O, "Oesophagus", r"oesophagus|esophagus", "Muscle waves push food to the stomach in about 7 seconds, even upside down.", "Oesophagus")
e(O, "Spleen", r"^spleen$", "Filters old red blood cells and stores immune cells.", "Spleen")
e(O, "Pancreas", r"pancrea", "Makes digestive enzymes and the hormones insulin and glucagon.", "Pancreas")
e(O, "Kidneys", r"kidney|renal pelvis", "Together they filter about 180 litres of blood plasma a day into 1–2 litres of urine.", "Kidney.l")
e(O, "Adrenal glands", r"suprarenal gland", "Sit on top of the kidneys and make adrenaline and cortisol.")
e(O, "Ureters", r"ureter", "Muscular tubes that carry urine from the kidneys to the bladder.")
e(O, "Urinary bladder", r"urinary bladder", "Usually holds about 400–600 ml.", "Urinary bladder")
e(O, "Urethra", r"urethra", "Carries urine out of the body; it is about 4 cm long in women and 20 cm in men.")
e(O, "Duodenum", r"duodenum", "The first 25 cm of the small intestine, where bile and pancreatic juice enter.")
e(O, "Small intestine", r"(jejunum|ileum)", "About 6 m long. Its folds give it a surface area of roughly 30 m².", "Jejunum")
e(O, "Large intestine", r"(colon|caecum|cecum)", "Absorbs water and is home to trillions of gut bacteria.", "Transverse colon")
e(O, "Appendix", r"appendix", "May act as a reserve of good gut bacteria.")
e(O, "Thyroid gland", r"thyroid gland", "A butterfly-shaped gland whose hormones set the body's metabolic rate.", "Thyroid gland")
e(O, "Parathyroid glands", r"parathyroid", "Four rice-grain-sized glands that control blood calcium.")
e(O, "Thymus", r"thymus", "Trains T cells. It is largest in childhood and mostly fat by old age.")
e(O, "Salivary glands", r"(parotid|submandibular|sublingual) (gland|duct)", "Together they make about 1–1.5 litres of saliva a day.", "Parotid gland.l")
e(O, "Tongue", r"^tongue$", "Its surface carries about 2,000–8,000 taste buds.")
e(O, "Pharynx & larynx", r"(pharynx|epiglottis|soft palate|uvula|tonsil)", "The epiglottis flaps over the airway each time you swallow.")
e(O, "Eye", r"(eyeball|cornea|lens|iris|retina|sclera|vitreous)", "The retina holds about 120 million rods and 6 million cones.", "Vitreous body.l")
e(O, "Pituitary gland", r"hypophysis", "A pea-sized 'master gland' that controls most other hormone glands.")
e(O, "Pineal gland", r"pineal", "Makes melatonin, which helps regulate sleep.")
e(O, "Testis", r"testis|epididymis", "Makes sperm (about 1,500 a second) and testosterone.", "Testis.l")
e(O, "Prostate", r"prostate|seminal|ejaculatory|ductus deferens", "A walnut-sized gland that adds fluid to semen.", "Prostate")
e(O, "Penis", r"penis", "Erection happens when its spongy tissue fills with blood.")
e(O, "Uterus", r"uterus|cervix", "A muscular organ about the size of a pear; in pregnancy it grows about 500-fold.", "Uterus")
e(O, "Ovary", r"ovary", "Each one is born holding about a million egg cells.", "Ovary.l")
e(O, "Uterine (fallopian) tube", r"uterine tube", "Fertilisation usually happens here; finger-like fimbriae catch the released egg.")
e(O, "Vagina", r"vagina", "A muscular, elastic canal from the cervix to the outside of the body.")
e(O, "Mammary gland", r"mammary", "Has 15–20 lobes, each draining through its own duct to the nipple.", "Mammary gland.l")

# ---------------- brain
B = "brain"
e(B, "Frontal lobe", r"(frontal gyrus|frontal pole|orbital gyr|precentral|frontal lobe|gyrus rectus)", "Handles planning, decisions and personality. The precentral gyrus drives voluntary movement.", "Superior frontal gyrus.l")
e(B, "Parietal lobe", r"(postcentral|parietal lobule|supramarginal|angular gyrus|precuneus|parietal lobe)", "Processes touch and body position; the postcentral gyrus is a map of the skin.", "Superior parietal lobule.r")
e(B, "Temporal lobe", r"(temporal gyrus|temporal pole|temporal lobe|fusiform|heschl|transverse temporal)", "Processes hearing, language comprehension and memory.", "Middle temporal gyrus.l")
e(B, "Occipital lobe", r"(occipital gyr|occipital pole|cuneus|lingual gyrus|occipital lobe|calcarine)", "The visual cortex; damage here can cause blindness even when the eyes are healthy.", "Cuneus.r")
e(B, "Limbic system", r"(cingulate|hippocamp|amygdal|fornix|parahippocampal|mammillary)", "Handles emotion and memory. The hippocampus forms new memories.", "Hippocampus.l")
e(B, "Insula", r"insula", "Senses the body's inner state: heartbeat, hunger, disgust.")
e(B, "Corpus callosum", r"corpus callosum", "About 200 million nerve fibres connecting the left and right hemispheres.", "Corpus callosum")
e(B, "Basal ganglia", r"(caudate|putamen|globus pallidus|lentiform|substantia nigra|subthalamic)", "Help start and smooth movement; they are damaged in Parkinson's disease.")
e(B, "Thalamus & hypothalamus", r"(thalam|hypothal|habenula)", "The thalamus relays sensory signals to the cortex; the hypothalamus controls hunger, temperature and hormones.", "Thalamus.l")
e(B, "Cerebellum", r"(cerebell|vermis|lobule|flocculus|culmen|declive|tonsil of cerebellum|nodule|uvula of vermis|lingula|central lobule|tuber|pyramid)", "Holds more than half the brain's neurons in a tenth of its volume, and coordinates movement.", "Biventral lobule.l")
e(B, "Brainstem", r"(midbrain|pons|medulla|peduncle|colliculus|olive|pyramid of medulla)", "Controls breathing, heart rate and consciousness.", "Pons.l")
e(B, "Ventricles", r"(ventricle|aqueduct|choroid)", "Hollow spaces that make about 500 ml of cerebrospinal fluid a day.")
e(B, "Spinal cord", r"spinal cord|cauda equina|conus medullaris|filum", "About 45 cm long and roughly as thick as a finger.")

if __name__ == "__main__":
    meta = json.loads((ROOT / "app/src/main/assets/anatomy.json").read_text())
    names = {p["name"]: L["name"] for L in meta["layers"] for p in L["parts"]}
    bad = 0
    for f in F:
        if "anchor" in f and f["anchor"] not in names:
            print("MISSING ANCHOR", f["title"], f["anchor"]); bad += 1
        elif "anchor" in f and names[f["anchor"]] != f["layer"]:
            print("ANCHOR LAYER", f["title"], f["anchor"], names[f["anchor"]]); bad += 1
        hits = [n for n, l in names.items() if l == f["layer"] and re.search(f["match"], n, re.I)]
        if not hits:
            print("NO MATCH", f["layer"], f["title"], f["match"]); bad += 1
    covered = sum(1 for n, l in names.items() if any(f["layer"] == l and re.search(f["match"], n, re.I) for f in F))
    print(f"{len(F)} entries, {sum('anchor' in f for f in F)} labels, parts with a fact: {covered}/{len(names)}, problems: {bad}")
    (ROOT / "app/src/main/assets/facts.json").write_text(json.dumps(F, ensure_ascii=False, indent=0))
