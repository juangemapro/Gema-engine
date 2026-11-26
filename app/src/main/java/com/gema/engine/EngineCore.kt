package com.gema.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Core scene types shared by the editor, renderer and file codecs. */
data class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(value: Float) = Vec3(x * value, y * value, z * value)
}

data class Transform(
    var position: Vec3 = Vec3(),
    var rotationEuler: Vec3 = Vec3(),
    var scale: Vec3 = Vec3(1f, 1f, 1f)
)

data class MeshData(
    val positions: MutableList<Vec3> = mutableListOf(),
    val triangleIndices: MutableList<Int> = mutableListOf()
)

data class MaterialData(
    var name: String = "Default",
    var baseColor: Vec3 = Vec3(0.7f, 0.7f, 0.7f),
    var roughness: Float = 0.7f,
    var metallic: Float = 0f
)

data class CameraData(
    var name: String = "Camera",
    var transform: Transform = Transform(position = Vec3(0f, 2f, 6f)),
    var fieldOfView: Float = 50f,
    var nearClip: Float = 0.1f,
    var farClip: Float = 1000f
)

data class PhysicsBody(
    var mass: Float = 1f,
    var useGravity: Boolean = true,
    var isStatic: Boolean = false,
    var velocity: Vec3 = Vec3(),
    var restitution: Float = 0.15f
)

data class SceneNode(
    var id: String,
    var name: String,
    var transform: Transform = Transform(),
    var mesh: MeshData? = null,
    var material: MaterialData = MaterialData(),
    var physics: PhysicsBody? = null
)

data class TransformKeyframe(val time: Float, val position: Vec3, val rotation: Vec3, val scale: Vec3)

data class AnimationTrack(
    val nodeId: String,
    val keys: MutableList<TransformKeyframe> = mutableListOf()
)

data class AnimationClip(
    var name: String = "Animation",
    var duration: Float = 5f,
    val tracks: MutableList<AnimationTrack> = mutableListOf()
)

data class SceneDocument(
    var name: String = "Untitled",
    val nodes: MutableList<SceneNode> = mutableListOf(),
    val cameras: MutableList<CameraData> = mutableListOf(),
    val animations: MutableList<AnimationClip> = mutableListOf()
)

/** Deterministic, versioned .gme scene document codec. */
object GmeCodec {
    private const val FORMAT_VERSION = 1

    fun encode(scene: SceneDocument): String {
        val root = JSONObject()
            .put("format", "GEMA_SCENE")
            .put("version", FORMAT_VERSION)
            .put("name", scene.name)
        val nodes = JSONArray()
        scene.nodes.forEach { node ->
            val item = JSONObject()
                .put("id", node.id)
                .put("name", node.name)
                .put("transform", transformToJson(node.transform))
                .put("material", JSONObject()
                    .put("name", node.material.name)
                    .put("color", vecToJson(node.material.baseColor))
                    .put("roughness", node.material.roughness.toDouble())
                    .put("metallic", node.material.metallic.toDouble()))
            node.physics?.let { body ->
                item.put("physics", JSONObject()
                    .put("mass", body.mass.toDouble())
                    .put("gravity", body.useGravity)
                    .put("static", body.isStatic)
                    .put("velocity", vecToJson(body.velocity))
                    .put("restitution", body.restitution.toDouble()))
            }
            node.mesh?.let { mesh ->
                val vertices = JSONArray()
                mesh.positions.forEach { vertices.put(vecToJson(it)) }
                val indices = JSONArray()
                mesh.triangleIndices.forEach { indices.put(it) }
                item.put("mesh", JSONObject().put("positions", vertices).put("indices", indices))
            }
            nodes.put(item)
        }
        root.put("nodes", nodes)
        val cameras = JSONArray()
        scene.cameras.forEach { camera ->
            cameras.put(JSONObject().put("name", camera.name)
                .put("transform", transformToJson(camera.transform))
                .put("fov", camera.fieldOfView.toDouble())
                .put("near", camera.nearClip.toDouble())
                .put("far", camera.farClip.toDouble()))
        }
        root.put("cameras", cameras)
        return root.toString(2)
    }

    fun decode(source: String): SceneDocument {
        val root = JSONObject(source)
        require(root.optString("format") == "GEMA_SCENE") { "Not a Gema scene file" }
        require(root.optInt("version", -1) in 1..FORMAT_VERSION) { "Unsupported GME version" }
        val scene = SceneDocument(name = root.optString("name", "Untitled"))
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val item = nodes.getJSONObject(i)
            val node = SceneNode(
                id = item.optString("id", "node-$i"),
                name = item.optString("name", "Object $i"),
                transform = transformFromJson(item.optJSONObject("transform") ?: JSONObject())
            )
            item.optJSONObject("material")?.let { m ->
                node.material = MaterialData(
                    name = m.optString("name", "Default"),
                    baseColor = vecFromJson(m.optJSONObject("color")),
                    roughness = m.optDouble("roughness", 0.7).toFloat(),
                    metallic = m.optDouble("metallic", 0.0).toFloat()
                )
            }
            item.optJSONObject("physics")?.let { p ->
                node.physics = PhysicsBody(
                    mass = max(0.001f, p.optDouble("mass", 1.0).toFloat()),
                    useGravity = p.optBoolean("gravity", true),
                    isStatic = p.optBoolean("static", false),
                    velocity = vecFromJson(p.optJSONObject("velocity")),
                    restitution = p.optDouble("restitution", 0.15).toFloat().coerceIn(0f, 1f)
                )
            }
            item.optJSONObject("mesh")?.let { m ->
                val mesh = MeshData()
                val vertices = m.optJSONArray("positions") ?: JSONArray()
                for (v in 0 until vertices.length()) mesh.positions.add(vecFromJson(vertices.optJSONObject(v)))
                val indices = m.optJSONArray("indices") ?: JSONArray()
                for (v in 0 until indices.length()) mesh.triangleIndices.add(indices.getInt(v))
                node.mesh = mesh
            }
            scene.nodes.add(node)
        }
        val cameras = root.optJSONArray("cameras") ?: JSONArray()
        for (i in 0 until cameras.length()) {
            val c = cameras.getJSONObject(i)
            scene.cameras.add(CameraData(
                name = c.optString("name", "Camera"),
                transform = transformFromJson(c.optJSONObject("transform") ?: JSONObject()),
                fieldOfView = c.optDouble("fov", 50.0).toFloat().coerceIn(1f, 179f),
                nearClip = c.optDouble("near", 0.1).toFloat(),
                farClip = c.optDouble("far", 1000.0).toFloat()
            ))
        }
        return scene
    }

    private fun vecToJson(v: Vec3) = JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("z", v.z.toDouble())
    private fun vecFromJson(o: JSONObject?) = Vec3(
        o?.optDouble("x", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("y", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("z", 0.0)?.toFloat() ?: 0f
    )
    private fun transformToJson(t: Transform) = JSONObject()
        .put("position", vecToJson(t.position))
        .put("rotation", vecToJson(t.rotationEuler))
        .put("scale", vecToJson(t.scale))
    private fun transformFromJson(o: JSONObject) = Transform(
        vecFromJson(o.optJSONObject("position")),
        vecFromJson(o.optJSONObject("rotation")),
        vecFromJson(o.optJSONObject("scale")).let { if (it == Vec3()) Vec3(1f, 1f, 1f) else it }
    )
}

/** Basic fixed-step gravity and floor response; intended as a starter simulation, not a full rigid-body solver. */
class PhysicsWorld(var gravity: Vec3 = Vec3(0f, -9.81f, 0f)) {
    fun step(nodes: List<SceneNode>, deltaSeconds: Float, floorY: Float = 0f) {
        val dt = deltaSeconds.coerceIn(0f, 1f / 15f)
        nodes.forEach { node ->
            val body = node.physics ?: return@forEach
            if (body.isStatic) return@forEach
            if (body.useGravity) body.velocity = body.velocity + gravity * dt
            node.transform.position = node.transform.position + body.velocity * dt
            if (node.transform.position.y < floorY) {
                node.transform.position.y = floorY
                if (body.velocity.y < 0f) body.velocity.y = -body.velocity.y * body.restitution
                if (kotlin.math.abs(body.velocity.y) < 0.05f) body.velocity.y = 0f
            }
        }
    }
}

/** Linear transform interpolation between neighboring keys. */
object TimelineEvaluator {
    fun apply(clip: AnimationClip, timeSeconds: Float, scene: SceneDocument) {
        val time = timeSeconds.coerceIn(0f, max(0f, clip.duration))
        clip.tracks.forEach { track ->
            val node = scene.nodes.firstOrNull { it.id == track.nodeId } ?: return@forEach
            val keys = track.keys.sortedBy { it.time }
            if (keys.isEmpty()) return@forEach
            val before = keys.lastOrNull { it.time <= time } ?: keys.first()
            val after = keys.firstOrNull { it.time >= time } ?: keys.last()
            val span = after.time - before.time
            val t = if (span <= 0f) 0f else ((time - before.time) / span).coerceIn(0f, 1f)
            node.transform.position = lerp(before.position, after.position, t)
            node.transform.rotationEuler = lerp(before.rotation, after.rotation, t)
            node.transform.scale = lerp(before.scale, after.scale, t)
        }
    }

    private fun lerp(a: Vec3, b: Vec3, t: Float) = Vec3(
        a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t
    )
}

/** Minimal Wavefront OBJ reader/writer. Supports vertices and polygon faces; material/UV/skeleton data are not imported. */
object ObjCodec {
    fun decode(source: String): MeshData {
        val mesh = MeshData()
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    val p = line.split(Regex("\\s+"))
                    if (p.size >= 4) mesh.positions.add(Vec3(p[1].toFloat(), p[2].toFloat(), p[3].toFloat()))
                }
                line.startsWith("f ") -> {
                    val refs = line.substring(2).trim().split(Regex("\\s+"))
                        .map { it.substringBefore('/').toInt() }
                        .map { if (it < 0) mesh.positions.size + it else it - 1 }
                    if (refs.size >= 3) for (i in 1 until refs.size - 1) {
                        mesh.triangleIndices.add(refs[0]); mesh.triangleIndices.add(refs[i]); mesh.triangleIndices.add(refs[i + 1])
                    }
                }
            }
        }
        require(mesh.positions.isNotEmpty()) { "OBJ contains no vertices" }
        require(mesh.triangleIndices.all { it in mesh.positions.indices }) { "OBJ face index is out of range" }
        return mesh
    }

    fun encode(mesh: MeshData): String = buildString {
        appendLine("# Gema Engine OBJ export")
        mesh.positions.forEach { appendLine("v ${it.x} ${it.y} ${it.z}") }
        for (i in mesh.triangleIndices.indices step 3) {
            if (i + 2 < mesh.triangleIndices.size) {
                appendLine("f ${mesh.triangleIndices[i] + 1} ${mesh.triangleIndices[i + 1] + 1} ${mesh.triangleIndices[i + 2] + 1}")
            }
        }
    }
}
