package dev.manik.xraymirror

/** Anatomy layers, in the order they are packed in anatomy.bin. */
enum class Layer(val label: String) {
    SKELETON("Skeleton"), MUSCLES("Muscles"), ARTERIES("Arteries"), VEINS("Veins"), ORGANS("Organs"), BRAIN("Brain")
}

enum class Sex { MALE, FEMALE }
enum class Look { XRAY, SOLID }
enum class Reveal(val label: String) { BODY("Whole body"), HAND_LENS("Lens follows hand"), TOUCH_LENS("Lens follows finger") }

/**
 * Immutable snapshot of everything the user can change. The UI publishes a new copy on every
 * change; the GL thread reads the latest copy once per frame, so no locking is needed.
 */
data class Settings(
    val layers: Set<Layer> = setOf(Layer.SKELETON, Layer.ORGANS),
    val sex: Sex = Sex.MALE,
    val look: Look = Look.XRAY,
    val frontCamera: Boolean = true,
    val showLabels: Boolean = true,
    val showTracking: Boolean = false,
    val brightness: Float = 1.0f,
    /** How much of the real camera image stays visible inside the body outline (0 = none). */
    val realMe: Float = 0.35f,
    /** Softness of the body-outline edge, as a fraction of a small blur radius. */
    val feather: Float = 0.3f,
    val clipToBody: Boolean = true,
    val reveal: Reveal = Reveal.BODY,
    val lensSize: Float = 0.5f,
    val fit: Fit = Fit(),
)

/** Manual fine-tuning of how the model sits on the body ("Fit to my body"). */
data class Fit(
    val size: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    val depth: Float = 1f,
    /** Offsets as a fraction of torso length. */
    val dx: Float = 0f,
    val dy: Float = 0f,
)
