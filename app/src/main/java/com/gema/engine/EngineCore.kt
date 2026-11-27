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

data class Vec2(var x: Float = 0f, var y: Float = 0f)

data class Vec4(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 0f)

/** Shared quaternion math. The editor stores Euler values in the Rx * Ry * Rz convention. */
object RotationMath {
    fun eulerToQuaternion(rotation:Vec3):Vec4 {
        val x=Math.toRadians(rotation.x.toDouble())/2.0
        val y=Math.toRadians(rotation.y.toDouble())/2.0
        val z=Math.toRadians(rotation.z.toDouble())/2.0
        val cx=kotlin.math.cos(x);val sx=kotlin.math.sin(x)
        val cy=kotlin.math.cos(y);val sy=kotlin.math.sin(y)
        val cz=kotlin.math.cos(z);val sz=kotlin.math.sin(z)
        return Vec4(
            (sx*cy*cz-cx*sy*sz).toFloat(),
            (cx*sy*cz+sx*cy*sz).toFloat(),
            (cx*cy*sz-sx*sy*cz).toFloat(),
            (cx*cy*cz+sx*sy*sz).toFloat()
        )
    }

    fun quaternionToEuler(value:Vec4):Vec3 {
        val length=kotlin.math.sqrt(value.x*value.x+value.y*value.y+value.z*value.z+value.w*value.w)
            .coerceAtLeast(0.000001f)
        val x=value.x/length;val y=value.y/length;val z=value.z/length;val w=value.w/length
        val r00=1f-2f*(y*y+z*z)
        val r01=2f*(x*y-z*w)
        val r02=2f*(x*z+y*w)
        val r10=2f*(x*y+z*w)
        val r11=1f-2f*(x*x+z*z)
        val r12=2f*(y*z-x*w)
        val angleY=kotlin.math.asin(r02.coerceIn(-1f,1f))
        val cosY=kotlin.math.cos(angleY)
        val angleX:Float
        val angleZ:Float
        if(kotlin.math.abs(cosY)>0.00001f) {
            angleX=kotlin.math.atan2(-r12,1f-2f*(x*x+y*y))
            angleZ=kotlin.math.atan2(-r01,r00)
        } else {
            angleX=(if(r02>=0f) 1f else -1f)*kotlin.math.atan2(r10,r11)
            angleZ=0f
        }
        val degrees=180f/Math.PI.toFloat()
        return Vec3(angleX*degrees,angleY*degrees,angleZ*degrees)
    }

    fun slerp(first:Vec4,second:Vec4,t:Float):Vec4 {
        val a=normalize(first)
        var b=normalize(second)
        var cosine=a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w
        if(cosine<0f) {
            b=Vec4(-b.x,-b.y,-b.z,-b.w)
            cosine=-cosine
        }
        val amount=t.coerceIn(0f,1f)
        if(cosine>0.9995f) {
            return normalize(Vec4(
                a.x+(b.x-a.x)*amount,
                a.y+(b.y-a.y)*amount,
                a.z+(b.z-a.z)*amount,
                a.w+(b.w-a.w)*amount
            ))
        }
        val angle=kotlin.math.acos(cosine.coerceIn(-1f,1f))
        val sinAngle=kotlin.math.sin(angle).coerceAtLeast(0.000001f)
        val wa=kotlin.math.sin((1f-amount)*angle)/sinAngle
        val wb=kotlin.math.sin(amount*angle)/sinAngle
        return normalize(Vec4(
            a.x*wa+b.x*wb,a.y*wa+b.y*wb,a.z*wa+b.z*wb,a.w*wa+b.w*wb
        ))
    }

    private fun normalize(value:Vec4):Vec4 {
        val length=kotlin.math.sqrt(value.x*value.x+value.y*value.y+value.z*value.z+value.w*value.w)
            .coerceAtLeast(0.000001f)
        return Vec4(value.x/length,value.y/length,value.z/length,value.w/length)
    }
}

data class Transform(
    var position: Vec3 = Vec3(),
    var rotationEuler: Vec3 = Vec3(),
    var scale: Vec3 = Vec3(1f, 1f, 1f)
)

data class MeshData(
    val positions: MutableList<Vec3> = mutableListOf(),
    val triangleIndices: MutableList<Int> = mutableListOf(),
    val normals: MutableList<Vec3> = mutableListOf(),
    val texCoords0: MutableList<Vec2> = mutableListOf(),
    val tangents0: MutableList<Vec4> = mutableListOf(),
    val joints0: MutableList<IntArray> = mutableListOf(),
    val weights0: MutableList<Vec4> = mutableListOf()
)

data class MaterialData(
    var name: String = "Default",
    var baseColor: Vec3 = Vec3(0.7f, 0.7f, 0.7f),
    var roughness: Float = 0.7f,
    var metallic: Float = 0f,
    var baseColorTextureUri: String? = null,
    var normalTextureUri: String? = null,
    var metallicRoughnessTextureUri: String? = null,
    var occlusionTextureUri: String? = null,
    var occlusionStrength: Float = 1f,
    var emissiveTextureUri: String? = null,
    var normalScale: Float = 1f,
    var emissiveColor: Vec3 = Vec3(),
    var alphaMode: String = "OPAQUE",
    var alphaCutoff: Float = 0.5f,
    var baseColorAlpha: Float = 1f
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
    var restitution: Float = 0.15f,
    var collisionShape: String = "SPHERE",
    var colliderRadius: Float = 0.5f,
    var friction: Float = 0.45f
)

data class SceneNode(
    var id: String,
    var name: String,
    var transform: Transform = Transform(),
    var mesh: MeshData? = null,
    var material: MaterialData = MaterialData(),
    var physics: PhysicsBody? = null,
    var parentId: String? = null
)

data class TransformKeyframe(
    val time:Float,
    val position:Vec3,
    val rotation:Vec3,
    val scale:Vec3,
    val rotationQuaternion:Vec4? = null
)

data class AnimationTrack(
    val nodeId: String,
    val keys: MutableList<TransformKeyframe> = mutableListOf(),
    var positionInterpolation: String = "LINEAR",
    var rotationInterpolation: String = "LINEAR",
    var scaleInterpolation: String = "LINEAR"
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
                .put("parentId", node.parentId)
                .put("transform", transformToJson(node.transform))
                .put("material", JSONObject()
                    .put("name", node.material.name)
                    .put("color", vecToJson(node.material.baseColor))
                    .put("roughness", node.material.roughness.toDouble())
                    .put("metallic", node.material.metallic.toDouble())
                    .put("baseColorTexture", node.material.baseColorTextureUri)
                    .put("normalTexture", node.material.normalTextureUri)
                    .put("metallicRoughnessTexture", node.material.metallicRoughnessTextureUri)
                    .put("occlusionTexture", node.material.occlusionTextureUri)
                    .put("occlusionStrength",node.material.occlusionStrength.toDouble())
                    .put("emissiveTexture", node.material.emissiveTextureUri)
                    .put("normalScale", node.material.normalScale.toDouble())
                    .put("emissive", vecToJson(node.material.emissiveColor))
                    .put("alphaMode", node.material.alphaMode)
                    .put("alphaCutoff", node.material.alphaCutoff.toDouble())
                    .put("baseColorAlpha", node.material.baseColorAlpha.toDouble()))
                .put("parentId", node.parentId)
            node.physics?.let { body ->
                item.put("physics", JSONObject()
                    .put("mass", body.mass.toDouble())
                    .put("gravity", body.useGravity)
                    .put("static", body.isStatic)
                    .put("velocity", vecToJson(body.velocity))
                    .put("restitution", body.restitution.toDouble())
                    .put("collisionShape",body.collisionShape)
                    .put("colliderRadius",body.colliderRadius.toDouble())
                    .put("friction",body.friction.toDouble()))
            }
            node.mesh?.let { mesh ->
                val vertices = JSONArray()
                mesh.positions.forEach { vertices.put(vecToJson(it)) }
                val indices = JSONArray()
                mesh.triangleIndices.forEach { indices.put(it) }
                val normals = JSONArray()
                mesh.normals.forEach { normals.put(vecToJson(it)) }
                val uvs = JSONArray()
                mesh.texCoords0.forEach { uvs.put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())) }
                val tangents = JSONArray()
                mesh.tangents0.forEach { values -> tangents.put(JSONObject()
                    .put("x",values.x.toDouble()).put("y",values.y.toDouble())
                    .put("z",values.z.toDouble()).put("w",values.w.toDouble())) }
                val joints = JSONArray()
                mesh.joints0.forEach { values -> joints.put(JSONArray().also { arr -> values.forEach { arr.put(it) } }) }
                val weights = JSONArray()
                mesh.weights0.forEach { values -> weights.put(JSONObject()
                    .put("x", values.x.toDouble()).put("y", values.y.toDouble())
                    .put("z", values.z.toDouble()).put("w", values.w.toDouble())) }
                item.put("mesh", JSONObject().put("positions", vertices).put("indices", indices)
                    .put("normals", normals).put("texCoords0", uvs).put("tangents0",tangents).put("joints0", joints).put("weights0", weights))
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
        val clips = JSONArray()
        scene.animations.forEach { clip ->
            val clipJson = JSONObject().put("name", clip.name).put("duration", clip.duration.toDouble())
            val tracks = JSONArray()
            clip.tracks.forEach { track ->
                val keys = JSONArray()
                track.keys.forEach { key ->
                    val keyJson=JSONObject().put("time",key.time.toDouble())
                        .put("position",vecToJson(key.position))
                        .put("rotation",vecToJson(key.rotation))
                        .put("scale",vecToJson(key.scale))
                    key.rotationQuaternion?.let { keyJson.put("rotationQuaternion",vec4ToJson(it)) }
                    keys.put(keyJson)
                }
                tracks.put(JSONObject()
                    .put("nodeId", track.nodeId)
                    .put("positionInterpolation",track.positionInterpolation)
                    .put("rotationInterpolation",track.rotationInterpolation)
                    .put("scaleInterpolation",track.scaleInterpolation)
                    .put("keys", keys))
            }
            clipJson.put("tracks", tracks)
            clips.put(clipJson)
        }
        root.put("animations", clips)
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
                transform = transformFromJson(item.optJSONObject("transform") ?: JSONObject()),
                parentId = item.optString("parentId").takeIf { it.isNotBlank() && it != "null" }
            )
            item.optJSONObject("material")?.let { m ->
                node.material = MaterialData(
                    name = m.optString("name", "Default"),
                    baseColor = vecFromJson(m.optJSONObject("color")),
                    roughness = m.optDouble("roughness", 0.7).toFloat(),
                    metallic = m.optDouble("metallic", 0.0).toFloat(),
                    baseColorTextureUri = m.optString("baseColorTexture").takeIf { it.isNotBlank() && it != "null" },
                    normalTextureUri = m.optString("normalTexture").takeIf { it.isNotBlank() && it != "null" },
                    metallicRoughnessTextureUri = m.optString("metallicRoughnessTexture").takeIf { it.isNotBlank() && it != "null" },
                    occlusionTextureUri = m.optString("occlusionTexture").takeIf { it.isNotBlank() && it != "null" },
                    occlusionStrength = m.optDouble("occlusionStrength",1.0).toFloat().coerceIn(0f,1f),
                    emissiveTextureUri = m.optString("emissiveTexture").takeIf { it.isNotBlank() && it != "null" },
                    normalScale = m.optDouble("normalScale",1.0).toFloat(),
                    emissiveColor = vecFromJson(m.optJSONObject("emissive")),
                    alphaMode = m.optString("alphaMode", "OPAQUE"),
                    alphaCutoff = m.optDouble("alphaCutoff", 0.5).toFloat(),
                    baseColorAlpha = m.optDouble("baseColorAlpha", 1.0).toFloat().coerceIn(0f,1f)
                )
            }
            item.optJSONObject("physics")?.let { p ->
                node.physics = PhysicsBody(
                    mass = max(0.001f, p.optDouble("mass", 1.0).toFloat()),
                    useGravity = p.optBoolean("gravity", true),
                    isStatic = p.optBoolean("static", false),
                    velocity = vecFromJson(p.optJSONObject("velocity")),
                    restitution = p.optDouble("restitution", 0.15).toFloat().coerceIn(0f, 1f),
                    collisionShape = p.optString("collisionShape","SPHERE"),
                    colliderRadius = p.optDouble("colliderRadius",0.5).toFloat().coerceIn(0.01f,1000f),
                    friction = p.optDouble("friction",0.45).toFloat().coerceIn(0f,1f)
                )
            }
            item.optJSONObject("mesh")?.let { m ->
                val mesh = MeshData()
                val vertices = m.optJSONArray("positions") ?: JSONArray()
                for (v in 0 until vertices.length()) mesh.positions.add(vecFromJson(vertices.optJSONObject(v)))
                val indices = m.optJSONArray("indices") ?: JSONArray()
                for (v in 0 until indices.length()) mesh.triangleIndices.add(indices.getInt(v))
                val normals = m.optJSONArray("normals") ?: JSONArray()
                for (v in 0 until normals.length()) mesh.normals.add(vecFromJson(normals.optJSONObject(v)))
                val uvs = m.optJSONArray("texCoords0") ?: JSONArray()
                for (v in 0 until uvs.length()) {
                    val uv = uvs.optJSONObject(v) ?: JSONObject()
                    mesh.texCoords0.add(Vec2(uv.optDouble("x").toFloat(), uv.optDouble("y").toFloat()))
                }
                val tangents = m.optJSONArray("tangents0") ?: JSONArray()
                for (v in 0 until tangents.length()) {
                    val tangent=tangents.optJSONObject(v) ?: JSONObject()
                    mesh.tangents0.add(Vec4(tangent.optDouble("x").toFloat(),tangent.optDouble("y").toFloat(),
                        tangent.optDouble("z").toFloat(),tangent.optDouble("w",1.0).toFloat()))
                }
                val joints = m.optJSONArray("joints0") ?: JSONArray()
                for (v in 0 until joints.length()) {
                    val arr = joints.optJSONArray(v) ?: JSONArray()
                    mesh.joints0.add(IntArray(arr.length()) { index -> arr.optInt(index) })
                }
                val weights = m.optJSONArray("weights0") ?: JSONArray()
                for (v in 0 until weights.length()) {
                    val weight = weights.optJSONObject(v) ?: JSONObject()
                    mesh.weights0.add(Vec4(weight.optDouble("x").toFloat(), weight.optDouble("y").toFloat(),
                        weight.optDouble("z").toFloat(), weight.optDouble("w").toFloat()))
                }
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
        val clips = root.optJSONArray("animations") ?: JSONArray()
        for (i in 0 until clips.length()) {
            val clipJson = clips.getJSONObject(i)
            val clip = AnimationClip(
                name = clipJson.optString("name", "Animation"),
                duration = clipJson.optDouble("duration", 5.0).toFloat().coerceAtLeast(0f)
            )
            val tracks = clipJson.optJSONArray("tracks") ?: JSONArray()
            for (t in 0 until tracks.length()) {
                val trackJson = tracks.getJSONObject(t)
                val track = AnimationTrack(
                    nodeId = trackJson.optString("nodeId"),
                    positionInterpolation=trackJson.optString("positionInterpolation","LINEAR"),
                    rotationInterpolation=trackJson.optString("rotationInterpolation","LINEAR"),
                    scaleInterpolation=trackJson.optString("scaleInterpolation","LINEAR")
                )
                val keys = trackJson.optJSONArray("keys") ?: JSONArray()
                for (k in 0 until keys.length()) {
                    val key = keys.getJSONObject(k)
                    track.keys.add(TransformKeyframe(
                        time=key.optDouble("time",0.0).toFloat(),
                        position=vecFromJson(key.optJSONObject("position")),
                        rotation=vecFromJson(key.optJSONObject("rotation")),
                        scale=key.optJSONObject("scale")?.let { vecFromJson(it) } ?: Vec3(1f,1f,1f),
                        rotationQuaternion=key.optJSONObject("rotationQuaternion")?.let { vec4FromJson(it) }
                    ))
                }
                clip.tracks.add(track)
            }
            scene.animations.add(clip)
        }
        return scene
    }

    private fun vecToJson(v: Vec3) = JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("z", v.z.toDouble())
    private fun vecFromJson(o: JSONObject?) = Vec3(
        o?.optDouble("x", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("y", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("z", 0.0)?.toFloat() ?: 0f
    )
    private fun vec4ToJson(v:Vec4)=JSONObject()
        .put("x",v.x.toDouble()).put("y",v.y.toDouble())
        .put("z",v.z.toDouble()).put("w",v.w.toDouble())
    private fun vec4FromJson(o:JSONObject?)=Vec4(
        o?.optDouble("x",0.0)?.toFloat() ?: 0f,
        o?.optDouble("y",0.0)?.toFloat() ?: 0f,
        o?.optDouble("z",0.0)?.toFloat() ?: 0f,
        o?.optDouble("w",1.0)?.toFloat() ?: 1f
    )
    private fun transformToJson(t: Transform) = JSONObject()
        .put("position", vecToJson(t.position))
        .put("rotation", vecToJson(t.rotationEuler))
        .put("scale", vecToJson(t.scale))
    private fun transformFromJson(o: JSONObject) = Transform(
        vecFromJson(o.optJSONObject("position")),
        vecFromJson(o.optJSONObject("rotation")),
        o.optJSONObject("scale")?.let { vecFromJson(it) } ?: Vec3(1f, 1f, 1f)
    )
}

/** Simple fixed-step rigid sphere simulation with ground and pair contacts. */
class PhysicsWorld(
    var gravity: Vec3 = Vec3(0f, -9.81f, 0f),
    var solverIterations: Int = 4
) {
    fun step(nodes: List<SceneNode>, deltaSeconds: Float, floorY: Float = 0f) {
        val dt = deltaSeconds.coerceIn(0f, 1f / 15f)
        nodes.forEach { node ->
            val body = node.physics ?: return@forEach
            if (body.isStatic) return@forEach
            if (body.useGravity) body.velocity = body.velocity + gravity * dt
            node.transform.position = node.transform.position + body.velocity * dt
            resolveGround(node,body,floorY)
        }
        repeat(solverIterations.coerceIn(1,8)) {
            for (i in nodes.indices) {
                val a=nodes[i]
                val bodyA=a.physics ?: continue
                for (j in i+1 until nodes.size) {
                    val b=nodes[j]
                    val bodyB=b.physics ?: continue
                    resolvePair(a,bodyA,b,bodyB)
                }
            }
        }
    }

    private fun halfExtents(node:SceneNode,body:PhysicsBody)=Vec3(
        kotlin.math.abs(node.transform.scale.x)*body.colliderRadius,
        kotlin.math.abs(node.transform.scale.y)*body.colliderRadius,
        kotlin.math.abs(node.transform.scale.z)*body.colliderRadius
    )

    private fun radius(node:SceneNode,body:PhysicsBody):Float {
        if(body.collisionShape=="BOX") return halfExtents(node,body).y
        val scale=maxOf(kotlin.math.abs(node.transform.scale.x),
            kotlin.math.abs(node.transform.scale.y),kotlin.math.abs(node.transform.scale.z))
        return body.colliderRadius*scale
    }

    private fun resolveGround(node:SceneNode,body:PhysicsBody,floorY:Float) {
        val r=radius(node,body)
        val bottom=node.transform.position.y-r
        if(bottom>=floorY) return
        node.transform.position.y=floorY+r
        if(body.velocity.y<0f) {
            body.velocity.y=-body.velocity.y*body.restitution.coerceIn(0f,1f)
            val damping=1f-body.friction.coerceIn(0f,1f)*0.12f
            body.velocity.x*=damping
            body.velocity.z*=damping
            if(kotlin.math.abs(body.velocity.y)<0.08f) body.velocity.y=0f
        }
    }

    private data class Contact(val normal:Vec3,val penetration:Float)

    private fun sphereBoxContact(sphere:SceneNode,sphereBody:PhysicsBody,box:SceneNode,boxBody:PhysicsBody):Contact? {
        val s=sphere.transform.position
        val b=box.transform.position
        val h=halfExtents(box,boxBody)
        val q=Vec3(
            s.x.coerceIn(b.x-h.x,b.x+h.x),
            s.y.coerceIn(b.y-h.y,b.y+h.y),
            s.z.coerceIn(b.z-h.z,b.z+h.z)
        )
        val delta=Vec3(q.x-s.x,q.y-s.y,q.z-s.z)
        val distanceSquared=delta.x*delta.x+delta.y*delta.y+delta.z*delta.z
        val r=radius(sphere,sphereBody)
        if(distanceSquared>=r*r) return null
        if(distanceSquared<0.0000001f) {
            val px=h.x-kotlin.math.abs(s.x-b.x)
            val py=h.y-kotlin.math.abs(s.y-b.y)
            val pz=h.z-kotlin.math.abs(s.z-b.z)
            return when(minOf(px,py,pz)) {
                px -> Contact(Vec3(if(s.x>=b.x) -1f else 1f,0f,0f),r+px)
                py -> Contact(Vec3(0f,if(s.y>=b.y) -1f else 1f,0f),r+py)
                else -> Contact(Vec3(0f,0f,if(s.z>=b.z) -1f else 1f),r+pz)
            }
        }
        val distance=kotlin.math.sqrt(distanceSquared)
        return Contact(Vec3(delta.x/distance,delta.y/distance,delta.z/distance),r-distance)
    }

    private fun findContact(a:SceneNode,bodyA:PhysicsBody,b:SceneNode,bodyB:PhysicsBody):Contact? {
        val aBox=bodyA.collisionShape=="BOX"
        val bBox=bodyB.collisionShape=="BOX"
        if(!aBox && !bBox) {
            val pa=a.transform.position; val pb=b.transform.position
            val delta=Vec3(pb.x-pa.x,pb.y-pa.y,pb.z-pa.z)
            val distanceSquared=delta.x*delta.x+delta.y*delta.y+delta.z*delta.z
            val minDistance=radius(a,bodyA)+radius(b,bodyB)
            if(distanceSquared>=minDistance*minDistance) return null
            val distance=kotlin.math.sqrt(distanceSquared).coerceAtLeast(0.0001f)
            return Contact(Vec3(delta.x/distance,delta.y/distance,delta.z/distance),minDistance-distance)
        }
        if(!aBox && bBox) return sphereBoxContact(a,bodyA,b,bodyB)
        if(aBox && !bBox) {
            val contact=sphereBoxContact(b,bodyB,a,bodyA) ?: return null
            return Contact(Vec3(-contact.normal.x,-contact.normal.y,-contact.normal.z),contact.penetration)
        }
        val pa=a.transform.position; val pb=b.transform.position
        val ah=halfExtents(a,bodyA); val bh=halfExtents(b,bodyB)
        val dx=pb.x-pa.x; val dy=pb.y-pa.y; val dz=pb.z-pa.z
        val ox=ah.x+bh.x-kotlin.math.abs(dx)
        val oy=ah.y+bh.y-kotlin.math.abs(dy)
        val oz=ah.z+bh.z-kotlin.math.abs(dz)
        if(ox<=0f || oy<=0f || oz<=0f) return null
        return when(minOf(ox,oy,oz)) {
            ox -> Contact(Vec3(if(dx>=0f) 1f else -1f,0f,0f),ox)
            oy -> Contact(Vec3(0f,if(dy>=0f) 1f else -1f,0f),oy)
            else -> Contact(Vec3(0f,0f,if(dz>=0f) 1f else -1f),oz)
        }
    }

    private fun resolvePair(a:SceneNode,bodyA:PhysicsBody,b:SceneNode,bodyB:PhysicsBody) {
        if(bodyA.isStatic && bodyB.isStatic) return
        val contact=findContact(a,bodyA,b,bodyB) ?: return
        val nx=contact.normal.x; val ny=contact.normal.y; val nz=contact.normal.z
        val invMassA=if(bodyA.isStatic) 0f else 1f/bodyA.mass.coerceAtLeast(0.001f)
        val invMassB=if(bodyB.isStatic) 0f else 1f/bodyB.mass.coerceAtLeast(0.001f)
        val invTotal=invMassA+invMassB
        if(invTotal<=0f) return
        val correction=contact.penetration*0.82f/invTotal
        if(invMassA>0f) {
            a.transform.position.x-=nx*correction*invMassA
            a.transform.position.y-=ny*correction*invMassA
            a.transform.position.z-=nz*correction*invMassA
        }
        if(invMassB>0f) {
            b.transform.position.x+=nx*correction*invMassB
            b.transform.position.y+=ny*correction*invMassB
            b.transform.position.z+=nz*correction*invMassB
        }
        val relative=Vec3(bodyB.velocity.x-bodyA.velocity.x,bodyB.velocity.y-bodyA.velocity.y,bodyB.velocity.z-bodyA.velocity.z)
        val velocityAlongNormal=relative.x*nx+relative.y*ny+relative.z*nz
        if(velocityAlongNormal>0f) return
        val restitution=min(bodyA.restitution,bodyB.restitution).coerceIn(0f,1f)
        val impulseMagnitude=-(1f+restitution)*velocityAlongNormal/invTotal
        val ix=nx*impulseMagnitude; val iy=ny*impulseMagnitude; val iz=nz*impulseMagnitude
        if(invMassA>0f) {
            bodyA.velocity.x-=ix*invMassA
            bodyA.velocity.y-=iy*invMassA
            bodyA.velocity.z-=iz*invMassA
        }
        if(invMassB>0f) {
            bodyB.velocity.x+=ix*invMassB
            bodyB.velocity.y+=iy*invMassB
            bodyB.velocity.z+=iz*invMassB
        }
        val tangent=Vec3(relative.x-velocityAlongNormal*nx,relative.y-velocityAlongNormal*ny,relative.z-velocityAlongNormal*nz)
        val tangentLength=kotlin.math.sqrt(tangent.x*tangent.x+tangent.y*tangent.y+tangent.z*tangent.z)
        if(tangentLength>0.0001f) {
            val tx=tangent.x/tangentLength; val ty=tangent.y/tangentLength; val tz=tangent.z/tangentLength
            val friction=min(bodyA.friction,bodyB.friction).coerceIn(0f,1f)
            val frictionImpulse=min(impulseMagnitude*friction,tangentLength/invTotal)
            if(invMassA>0f) {
                bodyA.velocity.x+=tx*frictionImpulse*invMassA
                bodyA.velocity.y+=ty*frictionImpulse*invMassA
                bodyA.velocity.z+=tz*frictionImpulse*invMassA
            }
            if(invMassB>0f) {
                bodyB.velocity.x-=tx*frictionImpulse*invMassB
                bodyB.velocity.y-=ty*frictionImpulse*invMassB
                bodyB.velocity.z-=tz*frictionImpulse*invMassB
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
            node.transform.position = interpolate(before.position,after.position,t,track.positionInterpolation)
            node.transform.rotationEuler = when {
                track.rotationInterpolation=="STEP" -> before.rotation.copy()
                before.rotationQuaternion!=null && after.rotationQuaternion!=null ->
                    RotationMath.quaternionToEuler(RotationMath.slerp(before.rotationQuaternion,after.rotationQuaternion,t))
                else -> lerp(before.rotation,after.rotation,t)
            }
            node.transform.scale = interpolate(before.scale,after.scale,t,track.scaleInterpolation)
        }
    }

    private fun interpolate(a:Vec3,b:Vec3,t:Float,mode:String):Vec3 =
        if(mode=="STEP") a.copy() else lerp(a,b,t)

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
