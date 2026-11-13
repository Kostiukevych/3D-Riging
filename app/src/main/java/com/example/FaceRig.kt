package com.example

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Лицевой риг в стиле Blender Rigify (имена костей: lid.T.L.001, brow.B.R.002, lip.T.L ...).
 * Чистый Kotlin, без зависимостей от движка — подходит для GitHub Actions.
 *
 * Система координат как в Blender: X — влево персонажа (+X = ".L"), Y — назад (лицо смотрит в -Y), Z — вверх.
 * Единицы: высота головы ≈ 1.0, центр головы в (0,0,0). Потом масштабируется под меш: FaceRig.fitTo(...)
 * Если твой движок использует Y-up, конвертируй: (x, y, z) -> (x, z, -y).
 */

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun mirrorX() = Vec3(-x, y, z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(dot(this))
    fun dist(o: Vec3) = (this - o).length()
}

data class FaceBone(
    val name: String,
    val parent: String?,
    val head: Vec3,
    val tail: Vec3,
    val deform: Boolean = true
)

/** Смещение (в единицах головы) и поворот (градусы, Euler XYZ) кости относительно позы покоя. */
data class BonePose(val move: Vec3 = Vec3(0f, 0f, 0f), val rotDeg: Vec3 = Vec3(0f, 0f, 0f)) {
    operator fun plus(o: BonePose) = BonePose(move + o.move, rotDeg + o.rotDeg)
    operator fun times(s: Float) = BonePose(move * s, rotDeg * s)
}

object FaceRig {

    // ---------- 1. Построение скелета ----------

    fun build(): List<FaceBone> {
        val out = mutableListOf<FaceBone>()

        // "@" в имени = сторона: ".L" и ".R" (зеркало по X). Без "@" кость одна (по центру).
        fun add(name: String, parent: String?, h: Vec3, t: Vec3, deform: Boolean = true) {
            if (name.contains("@")) {
                out += FaceBone(name.replace("@", ".L"), parent?.replace("@", ".L"), h, t, deform)
                out += FaceBone(name.replace("@", ".R"), parent?.replace("@", ".R"), h.mirrorX(), t.mirrorX(), deform)
            } else {
                out += FaceBone(name, parent, h, t, deform)
            }
        }

        // Цепочка костей: base, base.001, base.002 ... по списку точек.
        fun chain(base: String, parent: String?, pts: List<Vec3>) {
            var prev = parent
            for (i in 0 until pts.size - 1) {
                val n = if (i == 0) base else base + ".%03d".format(i)
                add(n, prev, pts[i], pts[i + 1])
                prev = n
            }
        }

        fun v(x: Float, y: Float, z: Float) = Vec3(x, y, z)

        // --- корень ---
        add("face", null, v(0f, 0f, 0f), v(0f, 0f, 0.2f), deform = false)

        // --- нос ---
        chain("nose", "face", listOf(
            v(0f, -0.44f, 0.14f), v(0f, -0.46f, 0.08f), v(0f, -0.49f, 0.02f),
            v(0f, -0.52f, -0.04f), v(0f, -0.50f, -0.08f), v(0f, -0.47f, -0.09f)
        ))
        add("nose@", "nose.002", v(0.06f, -0.47f, -0.06f), v(0.09f, -0.46f, -0.08f))
        add("nose@.001", "nose@", v(0.09f, -0.46f, -0.08f), v(0.07f, -0.44f, -0.08f))

        // --- челюсть и подбородок ---
        add("jaw_master", "face", v(0f, -0.05f, -0.15f), v(0f, -0.40f, -0.38f), deform = false)
        add("jaw", "jaw_master", v(0f, -0.05f, -0.15f), v(0f, -0.40f, -0.38f))
        chain("chin", "jaw", listOf(
            v(0f, -0.43f, -0.30f), v(0f, -0.47f, -0.38f), v(0f, -0.44f, -0.45f)
        ))
        chain("jaw@", "jaw", listOf(
            v(0.22f, -0.05f, -0.12f), v(0.17f, -0.28f, -0.36f), v(0.07f, -0.43f, -0.42f)
        ))

        // --- губы: верхние на лице, нижние на челюсти ---
        chain("lip.T@", "face", listOf(v(0f, -0.48f, -0.16f), v(0.05f, -0.47f, -0.165f), v(0.10f, -0.43f, -0.18f)))
        chain("lip.B@", "jaw", listOf(v(0f, -0.48f, -0.21f), v(0.05f, -0.47f, -0.205f), v(0.10f, -0.43f, -0.19f)))

        // --- язык и зубы ---
        chain("tongue", "jaw", listOf(
            v(0f, -0.28f, -0.20f), v(0f, -0.34f, -0.20f), v(0f, -0.39f, -0.20f), v(0f, -0.42f, -0.20f)
        ))
        add("teeth.T", "face", v(0f, -0.40f, -0.15f), v(0f, -0.45f, -0.15f))
        add("teeth.B", "jaw", v(0f, -0.40f, -0.22f), v(0f, -0.45f, -0.22f))

        // --- глаза и веки ---
        add("eye@", "face", v(0.17f, -0.40f, 0.08f), v(0.17f, -0.47f, 0.08f))
        chain("lid.T@", "face", listOf(
            v(0.11f, -0.42f, 0.08f), v(0.14f, -0.44f, 0.115f), v(0.20f, -0.43f, 0.115f), v(0.24f, -0.39f, 0.08f)
        ))
        chain("lid.B@", "face", listOf(
            v(0.24f, -0.39f, 0.08f), v(0.20f, -0.43f, 0.045f), v(0.14f, -0.44f, 0.045f), v(0.11f, -0.42f, 0.08f)
        ))

        // --- брови, лоб, висок ---
        chain("brow.B@", "face", listOf(
            v(0.09f, -0.43f, 0.15f), v(0.14f, -0.45f, 0.165f), v(0.19f, -0.44f, 0.165f),
            v(0.24f, -0.41f, 0.155f), v(0.28f, -0.36f, 0.13f)
        ))
        chain("brow.T@", "face", listOf(
            v(0.11f, -0.43f, 0.20f), v(0.17f, -0.44f, 0.215f), v(0.23f, -0.41f, 0.205f), v(0.28f, -0.35f, 0.17f)
        ))
        chain("forehead@", "face", listOf(
            v(0.05f, -0.42f, 0.28f), v(0.13f, -0.40f, 0.30f), v(0.21f, -0.35f, 0.28f), v(0.27f, -0.28f, 0.24f)
        ))
        add("temple@", "face", v(0.30f, -0.28f, 0.10f), v(0.31f, -0.20f, 0.0f))

        // --- щёки ---
        chain("cheek.T@", "face", listOf(v(0.22f, -0.38f, 0.0f), v(0.17f, -0.42f, 0.03f), v(0.12f, -0.44f, 0.04f)))
        chain("cheek.B@", "jaw", listOf(v(0.12f, -0.45f, -0.10f), v(0.20f, -0.38f, -0.12f), v(0.24f, -0.28f, -0.18f)))

        // --- уши ---
        chain("ear@", "face", listOf(
            v(0.33f, 0.00f, 0.05f), v(0.36f, 0.02f, 0.12f), v(0.37f, 0.05f, 0.05f),
            v(0.36f, 0.06f, -0.04f), v(0.33f, 0.04f, -0.10f)
        ))
        return out
    }

    /** Подгонка под реальный меш: bboxMin/bboxMax — габариты головы в пространстве модели. */
    fun fitTo(bones: List<FaceBone>, bboxMin: Vec3, bboxMax: Vec3): List<FaceBone> {
        val size = bboxMax - bboxMin
        val center = (bboxMin + bboxMax) * 0.5f
        fun m(p: Vec3) = Vec3(p.x * size.x + center.x, p.y * size.y + center.y, p.z * size.z + center.z)
        return bones.map { it.copy(head = m(it.head), tail = m(it.tail)) }
    }

    // ---------- 2. Выражения (как shape keys, но костями) ----------
    // "@" = обе стороны. Значения — для weight = 1.0. Подбери знаки под свой движок.

    private fun p(mx: Float = 0f, my: Float = 0f, mz: Float = 0f, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) =
        BonePose(Vec3(mx, my, mz), Vec3(rx, ry, rz))

    val expressions: Map<String, Map<String, BonePose>> = mapOf(
        "mouthOpen" to mapOf(
            "jaw" to p(rx = 22f),
            "tongue" to p(rx = -6f)
        ),
        "smile" to mapOf(
            "lip.T@.001" to p(mx = 0.02f, mz = 0.015f),
            "lip.B@.001" to p(mx = 0.02f, mz = 0.012f),
            "cheek.B@" to p(mz = 0.012f),
            "cheek.T@" to p(mz = 0.008f)
        ),
        "frown" to mapOf(
            "lip.T@.001" to p(mx = -0.005f, mz = -0.015f),
            "lip.B@.001" to p(mx = -0.005f, mz = -0.015f),
            "brow.B@" to p(mz = -0.012f)
        ),
        "blink" to mapOf(
            "lid.T@" to p(mz = -0.03f),
            "lid.T@.001" to p(mz = -0.035f),
            "lid.T@.002" to p(mz = -0.03f),
            "lid.B@" to p(mz = 0.008f),
            "lid.B@.001" to p(mz = 0.010f)
        ),
        "browUp" to mapOf(
            "brow.B@" to p(mz = 0.02f), "brow.B@.001" to p(mz = 0.025f), "brow.B@.002" to p(mz = 0.02f),
            "brow.T@" to p(mz = 0.025f), "brow.T@.001" to p(mz = 0.03f), "forehead@" to p(mz = 0.01f)
        ),
        "browDown" to mapOf(
            "brow.B@" to p(mz = -0.02f), "brow.B@.001" to p(mz = -0.025f),
            "brow.T@" to p(mz = -0.02f)
        ),
        "surprise" to mapOf(
            "jaw" to p(rx = 14f),
            "brow.B@" to p(mz = 0.025f), "brow.T@" to p(mz = 0.03f),
            "lid.T@.001" to p(mz = 0.012f)
        ),
        // подмигивания — односторонние
        "winkL" to mapOf("lid.T.L" to p(mz = -0.03f), "lid.T.L.001" to p(mz = -0.035f), "lid.T.L.002" to p(mz = -0.03f)),
        "winkR" to mapOf("lid.T.R" to p(mz = -0.03f), "lid.T.R.001" to p(mz = -0.035f), "lid.T.R.002" to p(mz = -0.03f))
    )

    /** weights: {"smile": 0.8, "blink": 1.0} -> поза каждой кости. */
    fun evaluate(weights: Map<String, Float>): Map<String, BonePose> {
        val result = HashMap<String, BonePose>()
        for ((exprName, w) in weights) {
            val expr = expressions[exprName] ?: continue
            for ((key, pose) in expr) {
                val names = if (key.contains("@")) listOf(key.replace("@", ".L"), key.replace("@", ".R")) else listOf(key)
                for (n in names) result[n] = (result[n] ?: BonePose()) + pose * w
            }
        }
        return result
    }

    // ---------- 3. Автовеса вершин (аналог "Automatic Weights") ----------

    /** Возвращает до maxInfl пар (индекс кости, вес), сумма = 1. Учитываются только deform-кости. */
    fun skinWeights(
        vertex: Vec3,
        bones: List<FaceBone>,
        maxInfl: Int = 4,
        falloff: Float = 0.10f
    ): List<Pair<Int, Float>> {
        val cand = ArrayList<Pair<Int, Float>>()
        for ((i, b) in bones.withIndex()) {
            if (!b.deform) continue
            val d = distToSegment(vertex, b.head, b.tail)
            cand += i to exp(-(d * d) / (falloff * falloff))
        }
        val top = cand.sortedByDescending { it.second }.take(maxInfl)
        val sum = top.sumOf { it.second.toDouble() }.toFloat()
        if (sum < 1e-6f) return emptyList()
        return top.map { it.first to it.second / sum }
    }

    private fun distToSegment(p: Vec3, a: Vec3, b: Vec3): Float {
        val ab = b - a
        val len2 = ab.dot(ab)
        if (len2 < 1e-9f) return p.dist(a)
        val t = max(0f, min(1f, (p - a).dot(ab) / len2))
        return p.dist(a + ab * t)
    }
}
