package com.gema.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max

data class ImportedPart(
    val name: String,
    val mesh: MeshData,
    val material: MaterialData = MaterialData(),
    val transform: Transform = Transform(),
    val nodeIndex: Int = -1,
    val parentNodeIndex: Int = -1,
    val rigIndex: Int = -1
)

data class ImportedAsset(
    val name: String,
    val parts: MutableList<ImportedPart> = mutableListOf(),
    val rigs: MutableList<EditorRig> = mutableListOf(),
    val animations: MutableList<AnimationClip> = mutableListOf()
)

object AssetImporter {
    fun import(fileName: String, bytes: ByteArray, resolveExternal: ((String) -> ByteArray?)? = null): ImportedAsset {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "obj" -> ObjMtlImporter.decode(fileName, String(bytes, Charsets.UTF_8), resolveExternal)
            "gltf" -> GltfSceneImporter.decode(fileName, String(bytes, Charsets.UTF_8), null, resolveExternal)
            "glb" -> GltfSceneImporter.decodeGlb(fileName, bytes, resolveExternal)
            "stl" -> ImportedAsset(fileName.substringBeforeLast('.')).apply {
                parts.add(ImportedPart(fileName.substringBeforeLast('.'), StlCodec.decode(bytes)))
            }
            "ply" -> ImportedAsset(fileName.substringBeforeLast('.')).apply {
                parts.add(ImportedPart(fileName.substringBeforeLast('.'), PlyCodec.decode(String(bytes, Charsets.UTF_8))))
            }
            "fbx" -> ImportedAsset(fileName.substringBeforeLast('.')).apply {
                parts.add(ImportedPart(fileName.substringBeforeLast('.'), FbxAsciiCodec.decode(String(bytes, Charsets.UTF_8))))
            }
            "blend" -> throw IllegalArgumentException("Los archivos .blend no tienen un parser Android integrado. Exporta desde Blender como GLB para conservar mallas, materiales, texturas, jerarquías y rigs.")
            "prisma" -> throw IllegalArgumentException("El formato de proyecto Prisma no está documentado públicamente aquí. Exporta desde Prisma a OBJ o GLB para importar los recursos.")
            else -> throw IllegalArgumentException("Formato no compatible. Gema Engine acepta GME, OBJ/MTL, GLB, glTF, STL, PLY ASCII y FBX ASCII.")
        }
    }
}

/** OBJ reader with object/group separation, material libraries, per-corner UVs/normals and embedded texture payloads. */
object ObjMtlImporter {
    private data class Corner(val position: Int, val uv: Int, val normal: Int)
    private data class GroupKey(val objectName: String, val materialName: String)
    private data class GroupBuilder(val key: GroupKey, val mesh: MeshData = MeshData())

    fun decode(fileName: String, source: String, resolveExternal: ((String) -> ByteArray?)? = null): ImportedAsset {
        val positions = mutableListOf<Vec3>()
        val uvs = mutableListOf<Vec2>()
        val normals = mutableListOf<Vec3>()
        val materialLibraries = mutableListOf<String>()
        val materialSources = mutableMapOf<String, MutableMap<String, String>>()
        val groups = linkedMapOf<GroupKey, GroupBuilder>()
        var objectName = fileName.substringBeforeLast('.')
        var materialName = "Default"

        fun group(): GroupBuilder {
            val key = GroupKey(objectName.ifBlank { "Object" }, materialName.ifBlank { "Default" })
            return groups.getOrPut(key) { GroupBuilder(key) }
        }

        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            val command = parts[0]
            val value = parts.getOrNull(1)?.trim().orEmpty()
            when (command) {
                "v" -> {
                    val v = value.split(Regex("\\s+"))
                    if (v.size >= 3) positions.add(Vec3(v[0].toFloat(), v[1].toFloat(), v[2].toFloat()))
                }
                "vt" -> {
                    val v = value.split(Regex("\\s+"))
                    if (v.size >= 2) uvs.add(Vec2(v[0].toFloat(), 1f - v[1].toFloat()))
                }
                "vn" -> {
                    val v = value.split(Regex("\\s+"))
                    if (v.size >= 3) normals.add(Vec3(v[0].toFloat(), v[1].toFloat(), v[2].toFloat()))
                }
                "o", "g" -> objectName = value.substringBefore(' ').ifBlank { "Object" }
                "mtllib" -> value.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { materialLibraries.add(it) }
                "usemtl" -> materialName = value
                "f" -> {
                    val corners = value.split(Regex("\\s+")).map { token ->
                        val refs = token.split('/')
                        fun index(rawIndex: String, size: Int): Int {
                            if (rawIndex.isBlank()) return -1
                            val n = rawIndex.toInt()
                            return if (n < 0) size + n else n - 1
                        }
                        Corner(index(refs.getOrElse(0) { "" }, positions.size),
                            index(refs.getOrElse(1) { "" }, uvs.size),
                            index(refs.getOrElse(2) { "" }, normals.size))
                    }
                    if (corners.size < 3) return@forEach
                    val builder = group()
                    for (i in 1 until corners.size - 1) {
                        listOf(corners[0], corners[i], corners[i + 1]).forEach { corner ->
                            require(corner.position in positions.indices) { "OBJ contiene un índice de vértice inválido." }
                            builder.mesh.positions.add(positions[corner.position])
                            builder.mesh.texCoords0.add(if (corner.uv in uvs.indices) uvs[corner.uv] else Vec2())
                            builder.mesh.normals.add(if (corner.normal in normals.indices) normals[corner.normal] else Vec3())
                            builder.mesh.triangleIndices.add(builder.mesh.positions.lastIndex)
                        }
                    }
                }
            }
        }
        require(positions.isNotEmpty() && groups.isNotEmpty()) { "El OBJ no contiene caras 3D válidas." }

        materialLibraries.forEach { libraryName ->
            val bytes = resolveExternal?.invoke(libraryName) ?: return@forEach
            var active: String? = null
            String(bytes, Charsets.UTF_8).lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(Regex("\\s+"), limit = 2)
                val command = parts[0]
                val value = parts.getOrNull(1)?.trim().orEmpty()
                when (command) {
                    "newmtl" -> {
                        active = value
                        materialSources[value] = mutableMapOf()
                    }
                    "Kd", "Ke", "Pr", "Pm", "d", "Tr", "map_Kd", "map_Bump", "bump" ->
                        active?.let { materialSources[it]?.put(command, value) }
                }
            }
        }

        val result = ImportedAsset(fileName.substringBeforeLast('.'))
        groups.values.forEach { builder ->
            val props = materialSources[builder.key.materialName].orEmpty()
            val color = props["Kd"]?.split(Regex("\\s+"))?.take(3)?.mapNotNull { it.toFloatOrNull() }
            val emissive = props["Ke"]?.split(Regex("\\s+"))?.take(3)?.mapNotNull { it.toFloatOrNull() }
            val texturePath = props["map_Kd"]?.substringAfterLast(' ')?.trim()
            val textureBytes = texturePath?.let { resolveExternal?.invoke(it) }
            val textureUri = textureBytes?.let { dataUri(it, mimeFromName(texturePath)) }
            val material = MaterialData(
                name = builder.key.materialName,
                baseColor = if (color?.size == 3) Vec3(color[0], color[1], color[2]) else Vec3(0.8f,0.8f,0.8f),
                roughness = props["Pr"]?.toFloatOrNull() ?: 0.7f,
                metallic = props["Pm"]?.toFloatOrNull() ?: 0f,
                baseColorTextureUri = textureUri,
                emissiveColor = if (emissive?.size == 3) Vec3(emissive[0],emissive[1],emissive[2]) else Vec3(),
                alphaMode = if ((props["d"]?.toFloatOrNull() ?: 1f) < 1f || (props["Tr"]?.toFloatOrNull() ?: 0f) > 0f) "BLEND" else "OPAQUE"
            )
            result.parts.add(ImportedPart(builder.key.objectName, builder.mesh, material))
        }
        return result
    }

    internal fun dataUri(bytes: ByteArray, mime: String): String =
        "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    internal fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}

/** Scene-level glTF 2.0 reader: multiple nodes, mesh primitives, PBR factors, images, UVs and skinning attributes. */
object GltfSceneImporter {
    fun decodeGlb(fileName: String, bytes: ByteArray, resolver: ((String) -> ByteArray?)? = null): ImportedAsset {
        require(bytes.size >= 20) { "El archivo GLB es demasiado pequeño." }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46546C67) { "Cabecera GLB incorrecta." }
        require(buffer.int == 2) { "Solo se admite glTF 2.0." }
        val totalLength = buffer.int
        require(totalLength in 20..bytes.size) { "Longitud GLB inválida." }
        var json: String? = null
        var bin: ByteArray? = null
        while (buffer.position() + 8 <= totalLength) {
            val length = buffer.int
            val type = buffer.int
            require(length >= 0 && buffer.position().toLong() + length <= totalLength.toLong()) { "Chunk GLB inválido." }
            val chunk = ByteArray(length)
            buffer.get(chunk)
            when (type) {
                0x4E4F534A -> json = String(chunk, Charsets.UTF_8).trimEnd('\u0000', ' ')
                0x004E4942 -> bin = chunk
            }
        }
        return decode(fileName, requireNotNull(json) { "El GLB no contiene JSON." }, bin, resolver)
    }

    fun decode(fileName: String, jsonText: String, binaryChunk: ByteArray?, resolver: ((String) -> ByteArray?)? = null): ImportedAsset {
        val root = JSONObject(jsonText)
        require(root.optJSONObject("asset")?.optString("version")?.startsWith("2") == true) { "Solo se admite glTF 2.x." }
        val buffers = readBuffers(root, binaryChunk, resolver)
        val views = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val images = readImages(root, buffers, views, resolver)
        val materials = readMaterials(root, images)
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val parents = IntArray(nodes.length()) { -1 }
        for (i in 0 until nodes.length()) {
            val children = nodes.getJSONObject(i).optJSONArray("children") ?: JSONArray()
            for (j in 0 until children.length()) {
                val child = children.optInt(j, -1)
                if (child in parents.indices) parents[child] = i
            }
        }
        val skins = readSkins(root, nodes, parents)
        val asset = ImportedAsset(fileName.substringBeforeLast('.'))
        asset.rigs.addAll(skins)
        asset.animations.addAll(readAnimations(root, nodes, buffers, views, accessors))
        val sceneIndex = root.optInt("scene", 0)
        val scenes = root.optJSONArray("scenes")
        val selectedRoots = scenes?.optJSONObject(sceneIndex)?.optJSONArray("nodes")
        val visibleNodes = linkedSetOf<Int>()
        fun visit(index: Int) {
            if (index !in 0 until nodes.length() || !visibleNodes.add(index)) return
            val children = nodes.getJSONObject(index).optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) visit(children.optInt(i, -1))
        }
        if (selectedRoots != null) {
            for (i in 0 until selectedRoots.length()) visit(selectedRoots.optInt(i, -1))
        } else {
            for (i in 0 until nodes.length()) if (parents[i] < 0) visit(i)
        }
        visibleNodes.forEach { nodeIndex ->
            val node = nodes.getJSONObject(nodeIndex)
            val meshIndex = node.optInt("mesh", -1)
            if (meshIndex !in 0 until meshes.length()) return@forEach
            val meshJson = meshes.getJSONObject(meshIndex)
            val primitives = meshJson.optJSONArray("primitives") ?: JSONArray()
            for (primitiveIndex in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(primitiveIndex)
                val mode = primitive.optInt("mode", 4)
                val attrs = primitive.optJSONObject("attributes") ?: continue
                if (!attrs.has("POSITION")) continue
                val geometry = readPrimitive(primitive, attrs, AccessorReader(buffers, views, accessors))
                triangulateMode(geometry, mode)
                val materialIndex = primitive.optInt("material", -1)
                val material = materials.getOrNull(materialIndex)?.copy(
                    baseColor = materials[materialIndex].baseColor.copy(),
                    emissiveColor = materials[materialIndex].emissiveColor.copy()
                ) ?: MaterialData()
                val nodeTransform = readNodeTransform(node)
                val partName = node.optString("name", meshJson.optString("name", "Mesh " + meshIndex))
                asset.parts.add(ImportedPart(
                    name = partName + if (primitives.length() > 1) " / Part " + (primitiveIndex + 1) else "",
                    mesh = geometry,
                    material = material,
                    transform = nodeTransform,
                    nodeIndex = nodeIndex,
                    parentNodeIndex = parents[nodeIndex],
                    rigIndex = node.optInt("skin", -1)
                ))
            }
        }
        return asset
    }

    private fun readBuffers(root: JSONObject, binaryChunk: ByteArray?, resolver: ((String) -> ByteArray?)?): List<ByteArray> {
        val descriptions = root.optJSONArray("buffers") ?: JSONArray()
        val output = mutableListOf<ByteArray>()
        for (i in 0 until descriptions.length()) {
            val item = descriptions.getJSONObject(i)
            val uri = item.optString("uri", "")
            val data = when {
                uri.startsWith("data:") && uri.contains("base64,") ->
                    Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
                i == 0 && binaryChunk != null -> binaryChunk
                uri.isNotBlank() -> resolver?.invoke(uri)
                    ?: throw IllegalArgumentException("Falta el buffer externo '" + uri + "'. Abre la carpeta que contiene el glTF y sus recursos.")
                else -> throw IllegalArgumentException("No se encontró el buffer " + i + " del glTF.")
            }
            require(data.size >= item.optInt("byteLength", 0)) { "El buffer glTF está truncado." }
            output.add(data)
        }
        return output
    }

    private fun readImages(root: JSONObject, buffers: List<ByteArray>, views: JSONArray, resolver: ((String) -> ByteArray?)?): List<String?> {
        val images = root.optJSONArray("images") ?: return emptyList()
        return (0 until images.length()).map { index ->
            val image = images.getJSONObject(index)
            val mime = image.optString("mimeType", ObjMtlImporter.mimeFromName(image.optString("uri", "texture.png")))
            val uri = image.optString("uri", "")
            val bytes = when {
                uri.startsWith("data:") && uri.contains("base64,") -> null
                uri.isNotBlank() -> resolver?.invoke(uri)
                image.has("bufferView") -> {
                    val view = views.getJSONObject(image.getInt("bufferView"))
                    val source = buffers[view.optInt("buffer", 0)]
                    val offset = view.optInt("byteOffset", 0)
                    val length = view.getInt("byteLength")
                    require(offset >= 0 && length >= 0 && offset + length <= source.size) { "Image bufferView inválido." }
                    source.copyOfRange(offset, offset + length)
                }
                else -> null
            }
            when {
                uri.startsWith("data:") -> uri
                bytes != null -> ObjMtlImporter.dataUri(bytes, mime)
                else -> null
            }
        }
    }

    private fun readMaterials(root: JSONObject, images: List<String?>): List<MaterialData> {
        val textures = root.optJSONArray("textures") ?: JSONArray()
        val sourceMaterials = root.optJSONArray("materials") ?: JSONArray()
        return (0 until sourceMaterials.length()).map { index ->
            val material = sourceMaterials.getJSONObject(index)
            val pbr = material.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
            val baseFactor = pbr.optJSONArray("baseColorFactor")
            val base = if (baseFactor != null && baseFactor.length() >= 3)
                Vec3(baseFactor.getDouble(0).toFloat(),baseFactor.getDouble(1).toFloat(),baseFactor.getDouble(2).toFloat())
                else Vec3(0.8f,0.8f,0.8f)
            fun textureUri(slot: JSONObject?): String? {
                if (slot == null) return null
                val textureIndex = slot.optInt("index", -1)
                if (textureIndex !in 0 until textures.length()) return null
                val sourceIndex = textures.getJSONObject(textureIndex).optInt("source", -1)
                return images.getOrNull(sourceIndex)
            }
            val emissive = material.optJSONArray("emissiveFactor")
            MaterialData(
                name = material.optString("name", "Material " + index),
                baseColor = base,
                roughness = pbr.optDouble("roughnessFactor", 0.7).toFloat(),
                metallic = pbr.optDouble("metallicFactor", 0.0).toFloat(),
                baseColorTextureUri = textureUri(pbr.optJSONObject("baseColorTexture")),
                normalTextureUri = textureUri(material.optJSONObject("normalTexture")),
                emissiveColor = if (emissive != null && emissive.length() >= 3)
                    Vec3(emissive.getDouble(0).toFloat(),emissive.getDouble(1).toFloat(),emissive.getDouble(2).toFloat()) else Vec3(),
                alphaMode = material.optString("alphaMode", "OPAQUE"),
                alphaCutoff = material.optDouble("alphaCutoff", 0.5).toFloat()
            )
        }
    }

    private fun readPrimitive(primitive: JSONObject, attributes: JSONObject, reader: AccessorReader): MeshData {
        val mesh = MeshData()
        val positions = reader.read(attributes.getInt("POSITION"))
        require(positions.components == 3) { "POSITION debe ser VEC3." }
        for (i in 0 until positions.count) mesh.positions.add(Vec3(positions.values[i*3],positions.values[i*3+1],positions.values[i*3+2]))
        attributes.optInt("NORMAL", -1).takeIf { it >= 0 }?.let { index ->
            val values = reader.read(index)
            if (values.components == 3) for (i in 0 until values.count) mesh.normals.add(Vec3(values.values[i*3],values.values[i*3+1],values.values[i*3+2]))
        }
        attributes.optInt("TEXCOORD_0", -1).takeIf { it >= 0 }?.let { index ->
            val values = reader.read(index)
            if (values.components == 2) for (i in 0 until values.count) mesh.texCoords0.add(Vec2(values.values[i*2],1f-values.values[i*2+1]))
        }
        attributes.optInt("JOINTS_0", -1).takeIf { it >= 0 }?.let { index ->
            val values = reader.read(index)
            if (values.components == 4) for (i in 0 until values.count) {
                mesh.joints0.add(IntArray(4) { j -> values.values[i*4+j].toInt() })
            }
        }
        attributes.optInt("WEIGHTS_0", -1).takeIf { it >= 0 }?.let { index ->
            val values = reader.read(index)
            if (values.components == 4) for (i in 0 until values.count) {
                val raw = Vec4(values.values[i*4],values.values[i*4+1],values.values[i*4+2],values.values[i*4+3])
                val sum = raw.x+raw.y+raw.z+raw.w
                mesh.weights0.add(if (sum > 0f) Vec4(raw.x/sum,raw.y/sum,raw.z/sum,raw.w/sum) else Vec4(1f,0f,0f,0f))
            }
        }
        val indices = primitive.optInt("indices", -1)
        if (indices >= 0) {
            val values = reader.read(indices)
            values.values.forEach { mesh.triangleIndices.add(it.toInt()) }
        } else mesh.triangleIndices.addAll(mesh.positions.indices)
        require(mesh.triangleIndices.all { it in mesh.positions.indices }) { "Índice de malla glTF fuera de rango." }
        return mesh
    }

    private fun triangulateMode(mesh: MeshData, mode: Int) {
        val input = mesh.triangleIndices.toList()
        if (mode == 4) {
            require(input.size % 3 == 0) { "Triángulos glTF incompletos." }
            return
        }
        val output = mutableListOf<Int>()
        when (mode) {
            5 -> for (i in 0 until input.size - 2) {
                if (i % 2 == 0) { output.add(input[i]); output.add(input[i+1]); output.add(input[i+2]) }
                else { output.add(input[i+1]); output.add(input[i]); output.add(input[i+2]) }
            }
            6 -> for (i in 1 until input.size - 1) {
                output.add(input[0]); output.add(input[i]); output.add(input[i+1])
            }
            else -> throw IllegalArgumentException("Modo de primitiva glTF " + mode + " no compatible.")
        }
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(output)
    }

    private fun readNodeTransform(node: JSONObject): Transform {
        val translation = node.optJSONArray("translation")
        val scale = node.optJSONArray("scale")
        val rotation = node.optJSONArray("rotation")
        val position = if (translation != null && translation.length() >= 3)
            Vec3(translation.getDouble(0).toFloat(),translation.getDouble(1).toFloat(),translation.getDouble(2).toFloat()) else Vec3()
        val s = if (scale != null && scale.length() >= 3)
            Vec3(scale.getDouble(0).toFloat(),scale.getDouble(1).toFloat(),scale.getDouble(2).toFloat()) else Vec3(1f,1f,1f)
        val euler = if (rotation != null && rotation.length() >= 4) {
            val x=rotation.getDouble(0); val y=rotation.getDouble(1); val z=rotation.getDouble(2); val w=rotation.getDouble(3)
            val sinr=2*(w*x+y*z); val cosr=1-2*(x*x+y*y)
            val sinp=2*(w*y-z*x)
            val roll=kotlin.math.atan2(sinr,cosr)
            val pitch=if (kotlin.math.abs(sinp)>=1) kotlin.math.sign(sinp)*Math.PI/2 else kotlin.math.asin(sinp)
            val siny=2*(w*z+x*y); val cosy=1-2*(y*y+z*z)
            val yaw=kotlin.math.atan2(siny,cosy)
            Vec3(Math.toDegrees(roll).toFloat(),Math.toDegrees(pitch).toFloat(),Math.toDegrees(yaw).toFloat())
        } else Vec3()
        return Transform(position,euler,s)
    }

    private data class MutableKey(
        var position: Vec3,
        var rotation: Vec3,
        var scale: Vec3
    )

    private fun readAnimations(
        root: JSONObject,
        nodes: JSONArray,
        buffers: List<ByteArray>,
        views: JSONArray,
        accessors: JSONArray
    ): List<AnimationClip> {
        val animations = root.optJSONArray("animations") ?: return emptyList()
        val reader = AccessorReader(buffers, views, accessors)
        val clips = mutableListOf<AnimationClip>()
        for (animationIndex in 0 until animations.length()) {
            val animation = animations.getJSONObject(animationIndex)
            val samplers = animation.optJSONArray("samplers") ?: JSONArray()
            val channels = animation.optJSONArray("channels") ?: JSONArray()
            val trackKeys = linkedMapOf<Int, MutableMap<Float, MutableKey>>()
            var maxTime = 0f
            for (channelIndex in 0 until channels.length()) {
                val channel = channels.getJSONObject(channelIndex)
                val target = channel.optJSONObject("target") ?: continue
                val nodeIndex = target.optInt("node", -1)
                if (nodeIndex !in 0 until nodes.length()) continue
                val path = target.optString("path")
                if (path !in setOf("translation", "rotation", "scale")) continue
                val samplerIndex = channel.optInt("sampler", -1)
                if (samplerIndex !in 0 until samplers.length()) continue
                val sampler = samplers.getJSONObject(samplerIndex)
                val input = reader.read(sampler.getInt("input"))
                val output = reader.read(sampler.getInt("output"))
                val interpolation = sampler.optString("interpolation", "LINEAR")
                val base = readNodeTransform(nodes.getJSONObject(nodeIndex))
                val map = trackKeys.getOrPut(nodeIndex) { linkedMapOf() }
                for (keyIndex in 0 until input.count) {
                    val time = input.values[keyIndex]
                    maxTime = max(maxTime, time)
                    val key = map.getOrPut(time) { MutableKey(base.position.copy(), base.rotationEuler.copy(), base.scale.copy()) }
                    val outputIndex = if (interpolation == "CUBICSPLINE") keyIndex * 3 + 1 else keyIndex
                    val components = output.components
                    val offset = outputIndex * components
                    if (offset + components > output.values.size) continue
                    when (path) {
                        "translation" -> if (components >= 3) key.position = Vec3(output.values[offset],output.values[offset+1],output.values[offset+2])
                        "scale" -> if (components >= 3) key.scale = Vec3(output.values[offset],output.values[offset+1],output.values[offset+2])
                        "rotation" -> if (components >= 4) key.rotation = quaternionToEuler(
                            output.values[offset],output.values[offset+1],output.values[offset+2],output.values[offset+3])
                    }
                }
            }
            val clip = AnimationClip(animation.optString("name", "Animation " + (animationIndex + 1)), max(maxTime,0.1f))
            trackKeys.forEach { (nodeIndex, keys) ->
                val track = AnimationTrack("gltfNode:" + nodeIndex)
                keys.toSortedMap().forEach { (time,key) ->
                    track.keys.add(TransformKeyframe(time,key.position,key.rotation,key.scale))
                }
                clip.tracks.add(track)
            }
            if (clip.tracks.isNotEmpty()) clips.add(clip)
        }
        return clips
    }

    private fun quaternionToEuler(x: Float, y: Float, z: Float, w: Float): Vec3 {
        val sinr = 2f * (w*x + y*z)
        val cosr = 1f - 2f * (x*x + y*y)
        val sinp = 2f * (w*y - z*x)
        val roll = kotlin.math.atan2(sinr, cosr)
        val pitch = if (kotlin.math.abs(sinp) >= 1f) kotlin.math.sign(sinp) * Math.PI.toFloat()/2f else kotlin.math.asin(sinp)
        val siny = 2f * (w*z + x*y)
        val cosy = 1f - 2f * (y*y + z*z)
        val yaw = kotlin.math.atan2(siny, cosy)
        return Vec3(Math.toDegrees(roll.toDouble()).toFloat(),Math.toDegrees(pitch.toDouble()).toFloat(),Math.toDegrees(yaw.toDouble()).toFloat())
    }

    private fun readSkins(root: JSONObject, nodes: JSONArray, parents: IntArray): List<EditorRig> {
        val skins = root.optJSONArray("skins") ?: return emptyList()
        val output = mutableListOf<EditorRig>()
        for (skinIndex in 0 until skins.length()) {
            val skin = skins.getJSONObject(skinIndex)
            val joints = skin.optJSONArray("joints") ?: JSONArray()
            val map = mutableMapOf<Int,Int>()
            for (i in 0 until joints.length()) map[joints.optInt(i)] = i
            val bones = mutableListOf<RigBone>()
            for (i in 0 until joints.length()) {
                val nodeIndex = joints.optInt(i)
                val node = nodes.optJSONObject(nodeIndex) ?: JSONObject()
                val transform = readNodeTransform(node)
                val childBoneNode = map.keys.firstOrNull { parents.getOrNull(it) == nodeIndex }
                val tail = if (childBoneNode != null) readNodeTransform(nodes.getJSONObject(childBoneNode)).position
                    else transform.position.copy(y=transform.position.y+0.25f)
                bones.add(RigBone(node.optString("name","Bone " + i),
                    map[parents.getOrNull(nodeIndex) ?: -1] ?: -1,transform.position,tail,nodeIndex))
            }
            output.add(EditorRig(skin.optString("name","Armature " + skinIndex),bones))
        }
        return output
    }
}

private class AccessorReader(
    private val buffers: List<ByteArray>,
    private val views: JSONArray,
    private val accessors: JSONArray
) {
    data class Values(val count: Int, val components: Int, val values: FloatArray)

    fun read(index: Int): Values {
        require(index in 0 until accessors.length()) { "Accessor glTF fuera de rango." }
        val accessor = accessors.getJSONObject(index)
        val viewIndex = accessor.optInt("bufferView", -1)
        require(viewIndex in 0 until views.length()) { "Accessor sin bufferView." }
        val view = views.getJSONObject(viewIndex)
        val bufferIndex = view.optInt("buffer", 0)
        val source = buffers.getOrNull(bufferIndex) ?: throw IllegalArgumentException("Buffer glTF inexistente.")
        val type = accessor.optString("type")
        val components = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> throw IllegalArgumentException("Accessor type " + type + " no soportado.") }
        val componentType = accessor.getInt("componentType")
        val componentBytes = when (componentType) { 5120,5121 -> 1; 5122,5123 -> 2; 5125,5126 -> 4; else -> throw IllegalArgumentException("Componente glTF " + componentType + " no soportado.") }
        val count = accessor.getInt("count")
        val packedStride = components * componentBytes
        val stride = view.optInt("byteStride",packedStride)
        val start = view.optInt("byteOffset",0)+accessor.optInt("byteOffset",0)
        require(count >= 0 && stride >= packedStride && start >= 0 &&
            start.toLong()+(count-1).coerceAtLeast(0).toLong()*stride+packedStride <= source.size.toLong()) {
            "Accessor glTF fuera de los límites del buffer."
        }
        val input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(count*components)
        val normalized = accessor.optBoolean("normalized",false)
        for (i in 0 until count) for (j in 0 until components) {
            val offset=start+i*stride+j*componentBytes
            val value = when(componentType) {
                5120 -> input.get(offset).toFloat().let { if(normalized) max(-1f,it/127f) else it }
                5121 -> (input.get(offset).toInt() and 0xff).toFloat().let { if(normalized) it/255f else it }
                5122 -> input.getShort(offset).toFloat().let { if(normalized) max(-1f,it/32767f) else it }
                5123 -> (input.getShort(offset).toInt() and 0xffff).toFloat().let { if(normalized) it/65535f else it }
                5125 -> input.getInt(offset).toLong().toFloat()
                5126 -> input.getFloat(offset)
                else -> 0f
            }
            values[i*components+j]=value
        }
        return Values(count,components,values)
    }
}
