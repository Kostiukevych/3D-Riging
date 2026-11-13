package com.example.facerig

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Анимации для проверки лицевого рига. Работает вместе с FaceRig.kt.
 * Каждая анимация на выходе даёт Map<имяКости, BonePose> — применяй это к костям в своём движке каждый кадр.
 */

interface FaceAnimation {
    val name: String
    val duration: Float
    val loop: Boolean
    fun sample(t: Float): Map<String, BonePose>
}

private fun wrap(t: Float, d: Float, loop: Boolean) = if (loop) t % d else t.coerceIn(0f, d)
private fun bump(x: Float) = sin(PI.toFloat() * x.coerceIn(0f, 1f)) // 0 -> 1 -> 0

// ---------- 1. Проверка КАЖДОЙ кости по очереди ----------
// Кости двигаются по одной (вверх + поворот). Смотришь на экран и видишь, какая кость что двигает.
// Если кость ничего не двигает — она не привязана к вершинам (веса).
class BoneTestAnimation(
    bones: List<FaceBone>,
    private val secondsPerBone: Float = 0.7f
) : FaceAnimation {
    private val deformBones = bones.filter { it.deform }.map { it.name }
    override val name = "bone_test"
    override val loop = true
    override val duration = deformBones.size * secondsPerBone

    fun activeBone(t: Float): String {
        val i = (wrap(t, duration, true) / secondsPerBone).toInt().coerceIn(0, deformBones.lastIndex)
        return deformBones[i]
    }

    override fun sample(t: Float): Map<String, BonePose> {
        val tt = wrap(t, duration, true)
        val i = (tt / secondsPerBone).toInt().coerceIn(0, deformBones.lastIndex)
        val w = bump(tt / secondsPerBone - i)
        val pose = BonePose(Vec3(0f, -0.01f, 0.04f), Vec3(20f, 0f, 15f)) * w
        return mapOf(deformBones[i] to pose)
    }
}

// ---------- 2. Проверка всех выражений ----------
class ExpressionTestAnimation(private val secondsPer: Float = 1.2f) : FaceAnimation {
    private val names = FaceRig.expressions.keys.toList()
    override val name = "expression_test"
    override val loop = true
    override val duration = names.size * secondsPer

    fun activeExpression(t: Float): String =
        names[(wrap(t, duration, true) / secondsPer).toInt().coerceIn(0, names.lastIndex)]

    override fun sample(t: Float): Map<String, BonePose> {
        val tt = wrap(t, duration, true)
        val i = (tt / secondsPer).toInt().coerceIn(0, names.lastIndex)
        return FaceRig.evaluate(mapOf(names[i] to bump(tt / secondsPer - i)))
    }
}

// ---------- 3. Idle: моргание раз в ~3.5 сек + лёгкое движение бровей ----------
class IdleAnimation : FaceAnimation {
    override val name = "idle"
    override val loop = true
    override val duration = 7f
    override fun sample(t: Float): Map<String, BonePose> {
        val period = 3.5f
        val phase = (t % period)
        val blink = if (phase < 0.18f) bump(phase / 0.18f) else 0f
        val brow = 0.15f * (0.5f + 0.5f * sin(t * 0.9f))
        return FaceRig.evaluate(mapOf("blink" to blink, "browUp" to brow))
    }
}

// ---------- 4. Разговор: челюсть + губы ----------
class TalkAnimation : FaceAnimation {
    override val name = "talk"
    override val loop = true
    override val duration = 6f
    override fun sample(t: Float): Map<String, BonePose> {
        val open = abs(sin(t * 9f)) * (0.55f + 0.45f * sin(t * 2.3f))
        val blink = if ((t % 3f) < 0.18f) bump((t % 3f) / 0.18f) else 0f
        return FaceRig.evaluate(mapOf("mouthOpen" to open * 0.7f, "smile" to 0.15f, "blink" to blink))
    }
}

// ---------- 5. Эмоции по ключам (нейтраль → улыбка → удивление → хмурость → нейтраль) ----------
class KeyframeAnimation(
    override val name: String,
    private val keys: List<Pair<Float, Map<String, Float>>>,
    override val loop: Boolean = true
) : FaceAnimation {
    override val duration = keys.last().first

    override fun sample(t: Float): Map<String, BonePose> {
        val tt = wrap(t, duration, loop)
        var a = keys.first()
        var b = keys.last()
        for (k in 0 until keys.size - 1) {
            if (tt >= keys[k].first && tt <= keys[k + 1].first) { a = keys[k]; b = keys[k + 1]; break }
        }
        val span = (b.first - a.first).coerceAtLeast(1e-4f)
        val x = ((tt - a.first) / span).coerceIn(0f, 1f)
        val s = x * x * (3f - 2f * x) // smoothstep
        val names = a.second.keys + b.second.keys
        val w = names.associateWith { n -> (a.second[n] ?: 0f) * (1f - s) + (b.second[n] ?: 0f) * s }
        return FaceRig.evaluate(w)
    }

    companion object {
        fun emotionShow() = KeyframeAnimation(
            "emotion_show",
            listOf(
                0f to emptyMap(),
                1f to mapOf("smile" to 1f),
                2f to mapOf("smile" to 1f, "browUp" to 0.6f),
                3f to mapOf("surprise" to 1f),
                4f to mapOf("frown" to 1f, "browDown" to 0.8f),
                5f to mapOf("winkL" to 1f, "smile" to 0.6f),
                6f to emptyMap()
            )
        )
    }
}

// ---------- Плеер ----------
class FaceAnimator {
    var current: FaceAnimation? = null
        private set
    private var time = 0f

    fun play(a: FaceAnimation) { current = a; time = 0f }

    /** Вызывай каждый кадр. dt — секунды. Возвращает позы костей для применения. */
    fun update(dt: Float): Map<String, BonePose> {
        val a = current ?: return emptyMap()
        time += dt
        if (!a.loop && time > a.duration) return a.sample(a.duration)
        return a.sample(time)
    }

    val currentTime get() = time
}

// ---------- Проверка целостности рига ----------
object FaceRigValidator {
    /** Возвращает список проблем. Пусто = всё в порядке. */
    fun validate(bones: List<FaceBone> = FaceRig.build()): List<String> {
        val problems = mutableListOf<String>()
        val names = bones.map { it.name }
        val set = names.toSet()

        names.groupingBy { it }.eachCount().filter { it.value > 1 }
            .forEach { problems += "Дубликат кости: ${it.key}" }

        bones.forEach { b ->
            if (b.parent != null && b.parent !in set) problems += "Нет родителя '${b.parent}' у кости ${b.name}"
            if (b.head.dist(b.tail) < 1e-4f) problems += "Нулевая длина: ${b.name}"
        }

        FaceRig.expressions.forEach { (expr, map) ->
            map.keys.forEach { key ->
                val ns = if (key.contains("@")) listOf(key.replace("@", ".L"), key.replace("@", ".R")) else listOf(key)
                ns.forEach { n -> if (n !in set) problems += "Выражение '$expr' ссылается на несуществующую кость $n" }
            }
        }
        return problems
    }

    /** Какие deform-кости не участвуют ни в одном выражении (их проверяй через BoneTestAnimation). */
    fun bonesWithoutExpression(bones: List<FaceBone> = FaceRig.build()): List<String> {
        val used = HashSet<String>()
        FaceRig.expressions.values.forEach { m ->
            m.keys.forEach { key ->
                if (key.contains("@")) { used += key.replace("@", ".L"); used += key.replace("@", ".R") } else used += key
            }
        }
        return bones.filter { it.deform && it.name !in used }.map { it.name }
    }
}

/*
Пример использования (в твоём цикле рендера):

    val bones = FaceRig.build()
    println(FaceRigValidator.validate(bones))            // [] = структура ок
    val animator = FaceAnimator()
    val test = BoneTestAnimation(bones)
    animator.play(test)

    // каждый кадр:
    val poses = animator.update(dt)                       // Map<имяКости, BonePose>
    println("Двигается: " + test.activeBone(animator.currentTime))
    // дальше применить poses к костям в движке: bone.position += move; bone.rotation = euler(rotDeg)

Переключение: animator.play(IdleAnimation()), animator.play(TalkAnimation()),
animator.play(KeyframeAnimation.emotionShow()), animator.play(ExpressionTestAnimation()).
*/
