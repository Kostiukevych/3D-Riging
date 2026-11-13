package com.example.facerig

/**
 * Подгонка лицевого рига под модель: авто (по габаритам головы) и ручная (по 6 маркерам).
 * Зависит только от FaceRig.kt.
 *
 * Пространства координат:
 *  - FaceRig работает в координатах Blender (Z вверх, лицо смотрит в -Y).
 *  - glTF / Mesh2Motion: Y вверх, лицо смотрит в +Z.
 */

fun blenderToGltf(v: Vec3) = Vec3(v.x, v.z, -v.y)
fun gltfToBlender(v: Vec3) = Vec3(v.x, -v.z, v.y)

/** 6 опорных точек лица. "Левый" = сторона .L (персонаж смотрит на нас — это его левая, +X). */
data class FaceMarkers(
    val eyeL: Vec3, val eyeR: Vec3, val noseTip: Vec3,
    val mouthL: Vec3, val mouthR: Vec3, val chin: Vec3
) {
    fun toList() = listOf(eyeL, eyeR, noseTip, mouthL, mouthR, chin)
    fun map(f: (Vec3) -> Vec3) = FaceMarkers(f(eyeL), f(eyeR), f(noseTip), f(mouthL), f(mouthR), f(chin))

    companion object {
        fun fromList(l: List<Vec3>) = FaceMarkers(l[0], l[1], l[2], l[3], l[4], l[5])

        /** Положение маркеров по текущим костям (начальные точки для перетаскивания). */
        fun fromBones(bones: List<FaceBone>): FaceMarkers {
            val by = bones.associateBy { it.name }
            fun b(n: String) = by.getValue(n)
            return FaceMarkers(
                eyeL = b("eye.L").head,
                eyeR = b("eye.R").head,
                noseTip = b("nose.003").head,
                mouthL = b("lip.T.L.001").tail,
                mouthR = b("lip.T.R.001").tail,
                chin = b("chin.001").tail
            )
        }
    }
}

object FaceFit {

    /** АВТО: шаблон из 83 костей, растянутый под габариты головы (в координатах Blender). */
    fun autoFit(bboxMinBlender: Vec3, bboxMaxBlender: Vec3): List<FaceBone> =
        FaceRig.fitTo(FaceRig.build(), bboxMinBlender, bboxMaxBlender)

    /**
     * РУЧНАЯ: сдвигает все кости так, чтобы 6 опорных точек совпали с маркерами.
     * Остальные кости смещаются плавно (метод Шепарда). Уши, лоб и виски закреплены якорями.
     * fitted и markers — в одном пространстве (Blender).
     */
    fun manualFit(fitted: List<FaceBone>, markers: FaceMarkers): List<FaceBone> {
        val src = FaceMarkers.fromBones(fitted).toList()
        val dst = markers.toList()
        val by = fitted.associateBy { it.name }
        val anchorNames = listOf("face", "ear.L", "ear.R", "temple.L", "temple.R", "forehead.L.002", "forehead.R.002")
        val anchors = anchorNames.mapNotNull { by[it]?.head }

        val pts = src + anchors
        val offs = dst.indices.map { dst[it] - src[it] } + anchors.map { Vec3(0f, 0f, 0f) }
        val scale = src[0].dist(src[5]).coerceAtLeast(1e-4f)
        val eps = (0.002f * scale) * (0.002f * scale)

        fun disp(p: Vec3): Vec3 {
            var sx = 0f; var sy = 0f; var sz = 0f; var sw = 0f
            for (i in pts.indices) {
                val d = p.dist(pts[i])
                val w = 1f / (d * d + eps)
                sx += offs[i].x * w; sy += offs[i].y * w; sz += offs[i].z * w; sw += w
            }
            return Vec3(sx / sw, sy / sw, sz / sw)
        }
        return fitted.map { it.copy(head = it.head + disp(it.head), tail = it.tail + disp(it.tail)) }
    }
}
