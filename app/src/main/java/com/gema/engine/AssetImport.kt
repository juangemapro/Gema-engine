package com.gema.engine

import android.util.Base64
import android.opengl.Matrix
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

data class ImportedHierarchyNode(
    val name: String,
    val transform: Transform,
    val sourceNodeIndex: Int,
    val parentNodeIndex: Int = -1
)

data class ImportedAsset(
    val name: String,
    val parts: MutableList<ImportedPart> = mutableListOf(),
    val hierarchyNodes: MutableList<ImportedHierarchyNode> = mutableListOf(),
    val cameras: MutableList<CameraData> = mutableListOf(),
    val lights: MutableList<EditorLight> = mutableListOf(),
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
                parts.add(ImportedPart(fileName.substringBeforeLast('.'), PlyCodec.decode(bytes)))
            }
            "fbx" -> FbxAsciiSceneImporter.decode(fileName, String(bytes, Charsets.UTF_8))
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
                "mtllib" -> Regex("\\\"([^\\\"]+)\\\"|([^\\s]+)").findAll(value).forEach { match ->
                    materialLibraries.add(match.groups[1]?.value ?: match.groups[2]?.value.orEmpty())
                }
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
            val bytes = resolveExternal?.invoke(libraryName)
                ?: throw IllegalArgumentException("Falta la biblioteca de materiales '" + libraryName + "'. Selecciona primero la carpeta que contiene el OBJ, su MTL y sus texturas.")
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
                    "Kd", "Ke", "Pr", "Pm", "d", "Tr", "map_Kd", "map_Bump", "bump", "map_Ke", "map_Pr", "map_Pm", "map_AO" ->
                        active?.let { materialSources[it]?.put(command, value) }
                }
            }
        }

        val result = ImportedAsset(fileName.substringBeforeLast('.'))
        groups.values.forEach { builder ->
            val props = materialSources[builder.key.materialName].orEmpty()
            val color = props["Kd"]?.split(Regex("\\s+"))?.take(3)?.mapNotNull { it.toFloatOrNull() }
            val emissive = props["Ke"]?.split(Regex("\\s+"))?.take(3)?.mapNotNull { it.toFloatOrNull() }
            fun loadMap(property:String):String? {
                val path=props[property]?.let { materialMapFileName(it) }?.takeIf { it.isNotBlank() } ?: return null
                val bytes=resolveExternal?.invoke(path)
                    ?: throw IllegalArgumentException("Falta la textura '" + path + "' indicada por el MTL. Carga la carpeta de recursos del modelo.")
                return dataUri(bytes,mimeFromName(path))
            }
            val textureUri=loadMap("map_Kd")
            val normalUri=loadMap("map_Bump") ?: loadMap("bump")
            val emissiveUri=loadMap("map_Ke")
            val mrUri=loadMap("map_Pr") ?: loadMap("map_Pm")
            val aoUri=loadMap("map_AO")
            val material = MaterialData(
                name = builder.key.materialName,
                baseColor = if (color?.size == 3) Vec3(color[0], color[1], color[2]) else Vec3(0.8f,0.8f,0.8f),
                roughness = props["Pr"]?.toFloatOrNull() ?: 0.7f,
                metallic = props["Pm"]?.toFloatOrNull() ?: 0f,
                baseColorTextureUri = textureUri,
                normalTextureUri = normalUri,
                metallicRoughnessTextureUri = mrUri,
                occlusionTextureUri = aoUri,
                emissiveTextureUri = emissiveUri,
                emissiveColor = if (emissive?.size == 3) Vec3(emissive[0],emissive[1],emissive[2]) else Vec3(),
                alphaMode = if ((props["d"]?.toFloatOrNull() ?: 1f) < 1f || (props["Tr"]?.toFloatOrNull() ?: 0f) > 0f) "BLEND" else "OPAQUE",
                baseColorAlpha = (props["d"]?.toFloatOrNull() ?: (1f-(props["Tr"]?.toFloatOrNull() ?: 0f))).coerceIn(0f,1f)
            )
            result.parts.add(ImportedPart(builder.key.objectName, builder.mesh, material))
        }
        return result
    }

    private fun materialMapFileName(value:String):String {
        val tokens=Regex("""[^\s"]+|"[^"]*"""").findAll(value.trim())
            .map { it.value.trim('"') }.toList()
        if(tokens.isEmpty()) return ""
        var index=0
        while(index<tokens.size && tokens[index].startsWith("-")) {
            val option=tokens[index++].lowercase()
            when(option) {
                "-o","-s","-t" -> {
                    var consumed=0
                    while(index<tokens.size && consumed<3 && tokens[index].toFloatOrNull()!=null) {
                        index++; consumed++
                    }
                }
                "-mm" -> repeat(2) { if(index<tokens.size && tokens[index].toFloatOrNull()!=null) index++ }
                "-blendu","-blendv","-boost","-texres","-clamp","-bm","-imfchan","-type" ->
                    if(index<tokens.size) index++
                else -> if(index<tokens.size) index++
            }
        }
        return tokens.drop(index).joinToString(" ").trim()
    }

    internal fun dataUri(bytes: ByteArray, mime: String): String =
        "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    internal fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        else -> "image/png"
    }
}

/** Partial FBX ASCII scene import: multiple Geometry/Model blocks and model transform hierarchy. */
object FbxAsciiSceneImporter {
    private data class Block(val id: String, val name: String, val subtype: String, val body: String)
    private data class ModelInfo(val block: Block, val transform: Transform)

    fun decode(fileName: String, source: String): ImportedAsset {
        require(!source.startsWith("Kaydara FBX Binary")) {
            "Este archivo FBX es binario. En esta versión se admite FBX ASCII; convierte el binario a FBX ASCII o GLB."
        }
        val geometries = readBlocks(source, "Geometry")
        val modelBlocks = readBlocks(source, "Model")
        require(geometries.isNotEmpty()) { "El FBX ASCII no contiene geometrías 3D." }
        val models = modelBlocks.filter { it.subtype.equals("Mesh",true) || it.subtype.equals("LimbNode",true) || it.subtype.equals("Null",true) }
        val modelIndex = models.mapIndexed { index, model -> model.id to index }.toMap()
        val modelTransforms = models.map { parseModelTransform(it.body) }
        val geometryOwners = mutableMapOf<String,String>()
        val modelParents = mutableMapOf<String,String>()
        Regex("""C:\s*"OO"\s*,\s*(-?\d+)\s*,\s*(-?\d+)""").findAll(source).forEach { match ->
            val child=match.groupValues[1]
            val parent=match.groupValues[2]
            if (geometries.any { it.id==child }) geometryOwners[child]=parent
            if (models.any { it.id==child } && models.any { it.id==parent }) modelParents[child]=parent
        }
        val asset=ImportedAsset(fileName.substringBeforeLast('.'))
        models.forEachIndexed { index, model ->
            asset.hierarchyNodes.add(ImportedHierarchyNode(
                model.name.ifBlank { "FBX Node " + model.id },
                modelTransforms[index],
                index,
                modelParents[model.id]?.let { modelIndex[it] } ?: -1
            ))
        }
        val limbModels=models.filter { it.subtype.equals("LimbNode",true) }
        val limbIds=limbModels.map { it.id }.toSet()
        val childrenByParent=modelParents.entries.groupBy({it.value},{it.key})
        val rigIndexByModel=mutableMapOf<String,Int>()
        val rigRootIds=limbModels.filter { modelParents[it.id] !in limbIds }.map { it.id }
        rigRootIds.forEach { rootId ->
            val ordered=mutableListOf<String>()
            fun visit(id:String) {
                if (id in ordered) return
                ordered.add(id)
                childrenByParent[id].orEmpty().filter { it in limbIds }.forEach { visit(it) }
            }
            visit(rootId)
            val boneIndex=ordered.mapIndexed { index,id -> id to index }.toMap()
            val bones=ordered.mapIndexed { index,id ->
                val model=models.first { it.id==id }
                val transform=modelTransforms[modelIndex.getValue(id)]
                val childId=childrenByParent[id].orEmpty().firstOrNull { it in boneIndex }
                val tail=childId?.let { child -> modelTransforms[modelIndex.getValue(child)].position.copy() } ?: Vec3(0f,0.25f,0f)
                RigBone(
                    name=model.name.ifBlank { "Bone " + index },
                    parentIndex=boneIndex[modelParents[id]] ?: -1,
                    head=Vec3(),
                    tail=tail,
                    sourceNodeIndex=modelIndex.getValue(id),
                    restTransform=transform
                )
            }
            val rig=EditorRig(models.first { it.id==rootId }.name.ifBlank { "FBX Armature" },bones)
            val newRigIndex=asset.rigs.size
            ordered.forEach { rigIndexByModel[it]=newRigIndex }
            asset.rigs.add(rig)
        }
        geometries.forEach { geometry ->
            if (!geometry.subtype.equals("Mesh",true)) return@forEach
            val mesh=parseGeometry(geometry.body)
            if (mesh.positions.isEmpty() || mesh.triangleIndices.isEmpty()) return@forEach
            val modelId=geometryOwners[geometry.id]
            val model=modelId?.let { id -> models.firstOrNull { it.id==id } }
            val sourceNode=modelId?.let { modelIndex[it] } ?: -1
            val parentId=modelId?.let { modelParents[it] }
            val parentSource=parentId?.let { modelIndex[it] } ?: -1
            val transform=modelId?.let { id ->
                val index=modelIndex[id]
                if (index!=null) modelTransforms.getOrNull(index) else null
            } ?: Transform()
            val modelName=model?.name?.takeIf { it.isNotBlank() }
            var currentParent=parentId
            var linkedRig=-1
            while(currentParent!=null && linkedRig<0) {
                linkedRig=rigIndexByModel[currentParent] ?: -1
                currentParent=modelParents[currentParent]
            }
            asset.parts.add(ImportedPart(
                name=modelName ?: geometry.name.ifBlank { "FBX Mesh " + geometry.id },
                mesh=mesh,
                material=MaterialData(modelName ?: "FBX Material"),
                transform=transform,
                nodeIndex=sourceNode,
                parentNodeIndex=parentSource,
                rigIndex=linkedRig
            ))
        }
        require(asset.parts.isNotEmpty()) { "No se encontraron caras triangulables en las geometrías FBX." }
        return asset
    }

    private fun readBlocks(source:String, kind:String):List<Block> {
        val result=mutableListOf<Block>()
        val startRegex=Regex(kind + """:\s*(-?\d+)\s*,\s*"[^"]*::([^"]*)"\s*,\s*"([^"]*)"\s*\{""")
        startRegex.findAll(source).forEach { match ->
            val open=match.range.last
            var depth=0
            var end=-1
            for (i in open until source.length) {
                when(source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if(depth==0) { end=i; break }
                    }
                }
            }
            if(end>open) {
                result.add(Block(match.groupValues[1],match.groupValues[2],match.groupValues[3],
                    source.substring(open+1,end)))
            }
        }
        return result
    }

    private fun parseGeometry(body:String):MeshData {
        fun array(name:String):List<String> {
            val match=Regex(name + """:\s*\*\d+\s*\{\s*a\s*:\s*([^}]*)\}""",RegexOption.DOT_MATCHES_ALL).find(body)
                ?: return emptyList()
            return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val vertexValues=array("Vertices")
        val polygonValues=array("PolygonVertexIndex")
        require(vertexValues.size>=9 && vertexValues.size%3==0) { "FBX Geometry tiene una lista de vértices inválida." }
        val mesh=MeshData()
        vertexValues.map { it.toFloat() }.chunked(3).forEach { mesh.positions.add(Vec3(it[0],it[1],it[2])) }
        val polygon=mutableListOf<Int>()
        polygonValues.forEach { raw ->
            val encoded=raw.toInt()
            val index=if(encoded<0) -encoded-1 else encoded
            require(index in mesh.positions.indices) { "FBX Geometry contiene índices fuera de rango." }
            polygon.add(index)
            if(encoded<0) {
                if(polygon.size>=3) for(i in 1 until polygon.size-1) {
                    mesh.triangleIndices.add(polygon[0]);mesh.triangleIndices.add(polygon[i]);mesh.triangleIndices.add(polygon[i+1])
                }
                polygon.clear()
            }
        }
        return mesh
    }

    private fun parseModelTransform(body:String):Transform {
        fun property(name:String, default:Vec3):Vec3 {
            val line=Regex("P:\\s*" + Regex.escape(name) + "[^\\r\\n]*").find(body)?.value ?: return default
            val numbers=Regex("""-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?""")
                .findAll(line.substringAfterLast("\"A\""))
                .mapNotNull { it.value.toFloatOrNull() }.toList()
            return if(numbers.size>=3) Vec3(numbers[0],numbers[1],numbers[2]) else default
        }
        return Transform(
            position=property("Lcl Translation",Vec3()),
            rotationEuler=property("Lcl Rotation",Vec3()),
            scale=property("Lcl Scaling",Vec3(1f,1f,1f))
        )
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

        // Only data reachable from the active glTF scene is imported into the editor.
        val skins = readSkins(root, nodes, parents, AccessorReader(buffers, views, accessors))
        val asset = ImportedAsset(fileName.substringBeforeLast('.'))
        asset.rigs.addAll(skins)
        asset.cameras.addAll(readCameras(root,nodes,visibleNodes.toList(),parents))
        asset.lights.addAll(readPunctualLights(root,nodes,visibleNodes.toList(),parents))
        visibleNodes.forEach { nodeIndex ->
            val node=nodes.getJSONObject(nodeIndex)
            asset.hierarchyNodes.add(ImportedHierarchyNode(
                node.optString("name","Node " + nodeIndex),
                readNodeTransform(node),
                nodeIndex,
                parents[nodeIndex]
            ))
        }
        asset.animations.addAll(readAnimations(root, nodes, buffers, views, accessors))
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
                val rigIndex=node.optInt("skin",-1)
                if(rigIndex>=0) {
                    val rig=asset.rigs.getOrNull(rigIndex)
                        ?: throw IllegalArgumentException("El nodo glTF referencia un skin inexistente.")
                    geometry.joints0.forEachIndexed { vertex,joints ->
                        val weights=geometry.weights0.getOrNull(vertex) ?: Vec4()
                        val influences=floatArrayOf(weights.x,weights.y,weights.z,weights.w)
                        for(influence in 0..3) if(influences[influence]>0f) {
                            require(joints[influence] in rig.bones.indices) {
                                "Un vértice referencia un joint que no existe en el skin."
                            }
                        }
                    }
                } else require(geometry.joints0.isEmpty()) {
                    "La malla contiene pesos de huesos pero el nodo no referencia un skin."
                }
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
                    rigIndex = rigIndex
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
                    ?: throw IllegalArgumentException("Falta la imagen externa '" + uri + "'. Selecciona la carpeta de recursos del glTF.")
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
            val alphaFactor = if (baseFactor != null && baseFactor.length() >= 4) baseFactor.getDouble(3).toFloat() else 1f
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
                metallicRoughnessTextureUri = textureUri(pbr.optJSONObject("metallicRoughnessTexture")),
                occlusionTextureUri = textureUri(material.optJSONObject("occlusionTexture")),
                occlusionStrength = material.optJSONObject("occlusionTexture")?.optDouble("strength",1.0)?.toFloat()?.coerceIn(0f,1f) ?: 1f,
                emissiveTextureUri = textureUri(material.optJSONObject("emissiveTexture")),
                normalScale = material.optJSONObject("normalTexture")?.optDouble("scale",1.0)?.toFloat() ?: 1f,
                emissiveColor = if (emissive != null && emissive.length() >= 3)
                    Vec3(emissive.getDouble(0).toFloat(),emissive.getDouble(1).toFloat(),emissive.getDouble(2).toFloat()) else Vec3(),
                alphaMode = material.optString("alphaMode", "OPAQUE"),
                alphaCutoff = material.optDouble("alphaCutoff", 0.5).toFloat(),
                baseColorAlpha = alphaFactor.coerceIn(0f,1f)
            )
        }
    }

    private fun readPrimitive(primitive:JSONObject,attributes:JSONObject,reader:AccessorReader):MeshData {
        val mesh=MeshData()
        val positions=reader.read(attributes.getInt("POSITION"))
        require(positions.components==3 && positions.componentType==5126 && !positions.normalized) {
            "POSITION debe usar FLOAT VEC3."
        }
        for(i in 0 until positions.count) {
            val x=positions.values[i*3]
            val y=positions.values[i*3+1]
            val z=positions.values[i*3+2]
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "POSITION contiene valores no finitos." }
            mesh.positions.add(Vec3(x,y,z))
        }
        fun readOptional(name:String,components:Int):AccessorReader.Values? {
            val index=attributes.optInt(name,-1)
            if(index<0) return null
            val values=reader.read(index)
            require(values.components==components) { name+" debe tener "+components+" componentes." }
            require(values.count==positions.count) { name+" no coincide con POSITION." }
            return values
        }
        readOptional("NORMAL",3)?.let { values ->
            require(values.componentType==5126 && !values.normalized) { "NORMAL debe usar FLOAT VEC3." }
            for(i in 0 until values.count) {
                val x=values.values[i*3];val y=values.values[i*3+1];val z=values.values[i*3+2]
                require(x.isFinite() && y.isFinite() && z.isFinite()) { "NORMAL contiene valores no finitos." }
                mesh.normals.add(Vec3(x,y,z))
            }
        }
        readOptional("TEXCOORD_0",2)?.let { values ->
            require(
                (values.componentType==5126 && !values.normalized) ||
                (values.componentType in setOf(5121,5123) && values.normalized)
            ) { "TEXCOORD_0 debe usar FLOAT o UNSIGNED_BYTE/SHORT normalizado." }
            for(i in 0 until values.count) {
                val u=values.values[i*2];val v=values.values[i*2+1]
                require(u.isFinite() && v.isFinite()) { "TEXCOORD_0 contiene valores no finitos." }
                mesh.texCoords0.add(Vec2(u,1f-v))
            }
        }
        readOptional("TANGENT",4)?.let { values ->
            require(values.componentType==5126 && !values.normalized) { "TANGENT debe usar FLOAT VEC4." }
            for(i in 0 until values.count) {
                val x=values.values[i*4];val y=values.values[i*4+1]
                val z=values.values[i*4+2];val w=values.values[i*4+3]
                require(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
                    "TANGENT contiene valores no finitos."
                }
                mesh.tangents0.add(Vec4(x,y,z,w))
            }
        }
        val jointSets=listOf("JOINTS_0","JOINTS_1").map { readOptional(it,4) }
        val weightSets=listOf("WEIGHTS_0","WEIGHTS_1").map { readOptional(it,4) }
        require(jointSets[0]!=null && weightSets[0]!=null || jointSets[0]==null && weightSets[0]==null) {
            "JOINTS_0 y WEIGHTS_0 deben estar presentes juntos."
        }
        require(jointSets[1]!=null && weightSets[1]!=null || jointSets[1]==null && weightSets[1]==null) {
            "JOINTS_1 y WEIGHTS_1 deben estar presentes juntos."
        }
        require(jointSets[1]==null || jointSets[0]!=null) { "JOINTS_1 requiere JOINTS_0." }
        jointSets.forEachIndexed { setIndex,joints ->
            if(joints!=null) require(joints.componentType in setOf(5121,5123) && !joints.normalized) {
                "JOINTS_"+setIndex+" debe usar UNSIGNED_BYTE o UNSIGNED_SHORT sin normalizar."
            }
        }
        weightSets.forEachIndexed { setIndex,weights ->
            if(weights!=null) require(
                (weights.componentType==5126 && !weights.normalized) ||
                (weights.componentType in setOf(5121,5123) && weights.normalized)
            ) { "WEIGHTS_"+setIndex+" debe usar FLOAT o UNSIGNED_BYTE/SHORT normalizado." }
        }
        if(jointSets[0]!=null && weightSets[0]!=null) {
            for(vertex in 0 until positions.count) {
                val combined=linkedMapOf<Int,Float>()
                for(setIndex in 0..1) {
                    val jointValues=jointSets[setIndex] ?: continue
                    val weightValues=weightSets[setIndex] ?: continue
                    for(component in 0..3) {
                        val rawJoint=jointValues.values[vertex*4+component]
                        val weight=weightValues.values[vertex*4+component]
                        require(rawJoint.isFinite() && rawJoint>=0f &&
                            rawJoint<=65535f && rawJoint==kotlin.math.floor(rawJoint)) {
                            "JOINTS_"+setIndex+" contiene un índice de hueso inválido."
                        }
                        require(weight.isFinite() && weight>=0f) {
                            "WEIGHTS_"+setIndex+" contiene un peso inválido."
                        }
                        if(weight>0f) {
                            val joint=rawJoint.toInt()
                            combined[joint]=(combined[joint] ?: 0f)+weight
                        }
                    }
                }
                val strongest=combined.entries.sortedByDescending { it.value }.take(4)
                val total=strongest.sumOf { it.value.toDouble() }.toFloat()
                val joints=IntArray(4)
                val weights=FloatArray(4)
                if(total>0.000001f) strongest.forEachIndexed { index,entry ->
                    joints[index]=entry.key
                    weights[index]=entry.value/total
                }
                mesh.joints0.add(joints)
                mesh.weights0.add(Vec4(weights[0],weights[1],weights[2],weights[3]))
            }
        }

        val indexAccessor=primitive.optInt("indices",-1)
        if(indexAccessor>=0) {
            val values=reader.read(indexAccessor)
            require(values.components==1 && values.componentType in setOf(5121,5123,5125) && !values.normalized) {
                "El accessor de índices debe ser SCALAR UNSIGNED_BYTE/SHORT/INT."
            }
            values.values.forEach { value ->
                require(value.isFinite() && value>=0f && value==kotlin.math.floor(value)) {
                    "El accessor de índices contiene un valor inválido."
                }
                mesh.triangleIndices.add(value.toInt())
            }
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
        val matrix = node.optJSONArray("matrix")
        if (matrix != null && matrix.length() == 16) {
            val m = FloatArray(16) { index -> matrix.getDouble(index).toFloat() }
            val sx = kotlin.math.sqrt(m[0]*m[0]+m[1]*m[1]+m[2]*m[2]).coerceAtLeast(0.000001f)
            val sy = kotlin.math.sqrt(m[4]*m[4]+m[5]*m[5]+m[6]*m[6]).coerceAtLeast(0.000001f)
            val sz = kotlin.math.sqrt(m[8]*m[8]+m[9]*m[9]+m[10]*m[10]).coerceAtLeast(0.000001f)
            val r00=m[0]/sx; val r01=m[4]/sy; val r02=m[8]/sz
            val r10=m[1]/sx; val r11=m[5]/sy; val r12=m[9]/sz
            val r20=m[2]/sx; val r21=m[6]/sy; val r22=m[10]/sz
            val trace=r00+r11+r22
            val (qx,qy,qz,qw)=when {
                trace>0f -> {
                    val s=kotlin.math.sqrt(trace+1f)*2f
                    floatArrayOf((r21-r12)/s,(r02-r20)/s,(r10-r01)/s,0.25f*s)
                }
                r00>r11 && r00>r22 -> {
                    val s=kotlin.math.sqrt(1f+r00-r11-r22)*2f
                    floatArrayOf(0.25f*s,(r01+r10)/s,(r02+r20)/s,(r21-r12)/s)
                }
                r11>r22 -> {
                    val s=kotlin.math.sqrt(1f+r11-r00-r22)*2f
                    floatArrayOf((r01+r10)/s,0.25f*s,(r12+r21)/s,(r02-r20)/s)
                }
                else -> {
                    val s=kotlin.math.sqrt(1f+r22-r00-r11)*2f
                    floatArrayOf((r02+r20)/s,(r12+r21)/s,0.25f*s,(r10-r01)/s)
                }
            }
            val euler=quaternionToEuler(qx,qy,qz,qw)
            return Transform(Vec3(m[12],m[13],m[14]),euler,Vec3(sx,sy,sz))
        }
        val translation = node.optJSONArray("translation")
        val scale = node.optJSONArray("scale")
        val rotation = node.optJSONArray("rotation")
        val position = if (translation != null && translation.length() >= 3)
            Vec3(translation.getDouble(0).toFloat(),translation.getDouble(1).toFloat(),translation.getDouble(2).toFloat()) else Vec3()
        val s = if (scale != null && scale.length() >= 3)
            Vec3(scale.getDouble(0).toFloat(),scale.getDouble(1).toFloat(),scale.getDouble(2).toFloat()) else Vec3(1f,1f,1f)
        val euler = if (rotation != null && rotation.length() >= 4)
            quaternionToEuler(rotation.getDouble(0).toFloat(),rotation.getDouble(1).toFloat(),
                rotation.getDouble(2).toFloat(),rotation.getDouble(3).toFloat()) else Vec3()
        return Transform(position,euler,s)
    }

    private data class MutableKey(
        var position:Vec3,
        var rotation:Vec3,
        var scale:Vec3,
        var rotationQuaternion:Vec4? = null
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
            val trackInterpolations = linkedMapOf<Int,MutableMap<String,String>>()
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
                trackInterpolations.getOrPut(nodeIndex) { linkedMapOf() }[path] =
                    if(interpolation=="STEP") "STEP" else "LINEAR"
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
                        "rotation" -> if(components>=4) {
                            val quaternion=Vec4(
                                output.values[offset],output.values[offset+1],
                                output.values[offset+2],output.values[offset+3]
                            )
                            key.rotationQuaternion=quaternion
                            key.rotation=RotationMath.quaternionToEuler(quaternion)
                        }
                    }
                }
            }
            val clip = AnimationClip(animation.optString("name", "Animation " + (animationIndex + 1)), max(maxTime,0.1f))
            trackKeys.forEach { (nodeIndex, keys) ->
                val interpolation=trackInterpolations[nodeIndex].orEmpty()
                val track = AnimationTrack(
                    nodeId="gltfNode:" + nodeIndex,
                    positionInterpolation=interpolation["translation"] ?: "LINEAR",
                    rotationInterpolation=interpolation["rotation"] ?: "LINEAR",
                    scaleInterpolation=interpolation["scale"] ?: "LINEAR"
                )
                keys.toSortedMap().forEach { (time,key) ->
                    track.keys.add(TransformKeyframe(
                        time,key.position,key.rotation,key.scale,key.rotationQuaternion?.copy()
                    ))
                }
                clip.tracks.add(track)
            }
            if (clip.tracks.isNotEmpty()) clips.add(clip)
        }
        return clips
    }

    private fun nodeWorldTransform(
        nodes:JSONArray,
        parents:IntArray,
        nodeIndex:Int,
        cache:MutableMap<Int,FloatArray> = mutableMapOf()
    ):Transform {
        fun localMatrix(index:Int):FloatArray {
            val transform=readNodeTransform(nodes.getJSONObject(index))
            val matrix=FloatArray(16)
            Matrix.setIdentityM(matrix,0)
            Matrix.translateM(matrix,0,transform.position.x,transform.position.y,transform.position.z)
            Matrix.rotateM(matrix,0,transform.rotationEuler.x,1f,0f,0f)
            Matrix.rotateM(matrix,0,transform.rotationEuler.y,0f,1f,0f)
            Matrix.rotateM(matrix,0,transform.rotationEuler.z,0f,0f,1f)
            Matrix.scaleM(matrix,0,transform.scale.x,transform.scale.y,transform.scale.z)
            return matrix
        }
        fun global(index:Int,visiting:MutableSet<Int>):FloatArray {
            require(index in 0 until nodes.length()) { "glTF node index is outside nodes array." }
            cache[index]?.let { return it.copyOf() }
            require(visiting.add(index)) { "glTF hierarchy contains a cycle." }
            val local=localMatrix(index)
            val parent=parents.getOrElse(index) { -1 }
            val result=if(parent in 0 until nodes.length()) {
                val parentMatrix=global(parent,visiting)
                FloatArray(16).also { Matrix.multiplyMM(it,0,parentMatrix,0,local,0) }
            } else local
            visiting.remove(index)
            cache[index]=result
            return result.copyOf()
        }
        val matrix=global(nodeIndex,mutableSetOf())
        val json=JSONArray()
        matrix.forEach { json.put(it.toDouble()) }
        return readNodeTransform(JSONObject().put("matrix",json))
    }

    private fun readCameras(
        root:JSONObject,
        nodes:JSONArray,
        visibleNodes:List<Int>,
        parents:IntArray
    ):List<CameraData> {
        val definitions=root.optJSONArray("cameras") ?: return emptyList()
        val result=mutableListOf<CameraData>()
        visibleNodes.forEach { nodeIndex ->
            val node=nodes.optJSONObject(nodeIndex) ?: return@forEach
            val cameraIndex=node.optInt("camera",-1)
            if(cameraIndex !in 0 until definitions.length()) return@forEach
            val definition=definitions.getJSONObject(cameraIndex)
            val perspective=definition.optJSONObject("perspective") ?: JSONObject()
            val fov=Math.toDegrees(perspective.optDouble("yfov",Math.toRadians(50.0))).toFloat().coerceIn(1f,179f)
            result.add(CameraData(
                name=node.optString("name",definition.optString("name","Camera")),
                transform=nodeWorldTransform(nodes,parents,nodeIndex),
                fieldOfView=fov,
                nearClip=perspective.optDouble("znear",0.1).toFloat(),
                farClip=perspective.optDouble("zfar",1000.0).toFloat()
            ))
        }
        return result
    }

    private fun readPunctualLights(
        root:JSONObject,
        nodes:JSONArray,
        visibleNodes:List<Int>,
        parents:IntArray
    ):List<EditorLight> {
        val definitions=root.optJSONObject("extensions")?.optJSONObject("KHR_lights_punctual")?.optJSONArray("lights")
            ?: return emptyList()
        val result=mutableListOf<EditorLight>()
        visibleNodes.forEach { nodeIndex ->
            val node=nodes.optJSONObject(nodeIndex) ?: return@forEach
            val lightIndex=node.optJSONObject("extensions")?.optJSONObject("KHR_lights_punctual")?.optInt("light",-1) ?: -1
            if(lightIndex !in 0 until definitions.length()) return@forEach
            val definition=definitions.getJSONObject(lightIndex)
            val color=definition.optJSONArray("color")
            val rgb=if(color!=null && color.length()>=3) Vec3(
                color.getDouble(0).toFloat(),color.getDouble(1).toFloat(),color.getDouble(2).toFloat()
            ) else Vec3(1f,1f,1f)
            val kind=if(definition.optString("type")=="directional") "DIRECTIONAL" else "POINT"
            val transform=nodeWorldTransform(nodes,parents,nodeIndex)
            val lightPosition=if(kind=="DIRECTIONAL") {
                val rotation=FloatArray(16)
                Matrix.setIdentityM(rotation,0)
                Matrix.rotateM(rotation,0,transform.rotationEuler.x,1f,0f,0f)
                Matrix.rotateM(rotation,0,transform.rotationEuler.y,0f,1f,0f)
                Matrix.rotateM(rotation,0,transform.rotationEuler.z,0f,0f,1f)
                val direction=FloatArray(4)
                // The renderer stores the vector from the surface toward a directional light;
                // glTF's local -Z is the direction in which light rays travel.
                Matrix.multiplyMV(direction,0,rotation,0,floatArrayOf(0f,0f,1f,0f),0)
                val length=kotlin.math.sqrt(direction[0]*direction[0]+direction[1]*direction[1]+direction[2]*direction[2])
                    .coerceAtLeast(0.000001f)
                Vec3(direction[0]/length,direction[1]/length,direction[2]/length)
            } else transform.position
            result.add(EditorLight(
                name=node.optString("name",definition.optString("name","Light")),
                kind=kind,
                position=lightPosition,
                color=rgb,
                intensity=definition.optDouble("intensity",1.0).toFloat()
            ))
        }
        return result
    }

    private fun readSkins(root: JSONObject, nodes: JSONArray, parents: IntArray, reader: AccessorReader): List<EditorRig> {
        val skins = root.optJSONArray("skins") ?: return emptyList()
        val output = mutableListOf<EditorRig>()
        for (skinIndex in 0 until skins.length()) {
            val skin = skins.getJSONObject(skinIndex)
            val joints = skin.optJSONArray("joints") ?: JSONArray()
            val jointNodeIndices=(0 until joints.length()).map { joints.optInt(it,-1) }
            require(jointNodeIndices.isNotEmpty() && jointNodeIndices.all { it in 0 until nodes.length() }) {
                "El skin glTF contiene un índice de joint fuera de nodes."
            }
            require(jointNodeIndices.distinct().size==jointNodeIndices.size) {
                "El skin glTF contiene joints duplicados."
            }
            val map=jointNodeIndices.mapIndexed { index,nodeIndex -> nodeIndex to index }.toMap()
            val inverseMatrices = mutableListOf<FloatArray?>()
            val inverseAccessor = skin.optInt("inverseBindMatrices", -1)
            if (inverseAccessor >= 0) {
                val values = reader.read(inverseAccessor)
                require(values.components==16 && values.componentType==5126 && !values.normalized) {
                    "inverseBindMatrices debe ser MAT4 FLOAT."
                }
                require(values.count==jointNodeIndices.size) {
                    "La cantidad de inverseBindMatrices no coincide con los joints del skin."
                }
                for (i in 0 until values.count) {
                    val matrix=values.values.copyOfRange(i*16,(i+1)*16)
                    require(matrix.all { it.isFinite() }) { "inverseBindMatrices contiene valores no finitos." }
                    inverseMatrices.add(matrix)
                }
            }

            val globalCache=arrayOfNulls<FloatArray>(nodes.length())
            fun matrixFromTransform(transform:Transform):FloatArray {
                val matrix=FloatArray(16)
                Matrix.setIdentityM(matrix,0)
                Matrix.translateM(matrix,0,transform.position.x,transform.position.y,transform.position.z)
                Matrix.rotateM(matrix,0,transform.rotationEuler.x,1f,0f,0f)
                Matrix.rotateM(matrix,0,transform.rotationEuler.y,0f,1f,0f)
                Matrix.rotateM(matrix,0,transform.rotationEuler.z,0f,0f,1f)
                Matrix.scaleM(matrix,0,transform.scale.x,transform.scale.y,transform.scale.z)
                return matrix
            }
            fun nodeGlobalMatrix(nodeIndex:Int,visiting:MutableSet<Int> = mutableSetOf()):FloatArray {
                require(nodeIndex in 0 until nodes.length()) { "glTF joint node index is outside nodes array." }
                globalCache[nodeIndex]?.let { return it.copyOf() }
                require(visiting.add(nodeIndex)) { "glTF node hierarchy contains a cycle." }
                val local=matrixFromTransform(readNodeTransform(nodes.getJSONObject(nodeIndex)))
                val parent=parents.getOrElse(nodeIndex) { -1 }
                val global=if(parent in 0 until nodes.length()) {
                    val parentMatrix=nodeGlobalMatrix(parent,visiting)
                    FloatArray(16).also { Matrix.multiplyMM(it,0,parentMatrix,0,local,0) }
                } else local
                visiting.remove(nodeIndex)
                globalCache[nodeIndex]=global
                return global.copyOf()
            }
            fun nearestJointParent(nodeIndex:Int):Int? {
                var ancestor=parents.getOrElse(nodeIndex) { -1 }
                val visited=mutableSetOf<Int>()
                while(ancestor in 0 until nodes.length() && visited.add(ancestor)) {
                    if(ancestor in map) return ancestor
                    ancestor=parents.getOrElse(ancestor) { -1 }
                }
                return null
            }
            fun transformFromMatrix(matrix:FloatArray):Transform {
                val array=JSONArray()
                matrix.forEach { array.put(it.toDouble()) }
                return readNodeTransform(JSONObject().put("matrix",array))
            }

            val bones = mutableListOf<RigBone>()
            for (i in jointNodeIndices.indices) {
                val nodeIndex=jointNodeIndices[i]
                if(nodeIndex !in 0 until nodes.length()) continue
                val node=nodes.getJSONObject(nodeIndex)
                val parentJointNode=nearestJointParent(nodeIndex)
                val world=nodeGlobalMatrix(nodeIndex)
                val local=if(parentJointNode!=null) {
                    val inverseParent=FloatArray(16)
                    if(Matrix.invertM(inverseParent,0,nodeGlobalMatrix(parentJointNode),0)) {
                        FloatArray(16).also { Matrix.multiplyMM(it,0,inverseParent,0,world,0) }
                    } else world
                } else world
                val restTransform=transformFromMatrix(local)

                val childJointNode=jointNodeIndices.firstOrNull { candidate ->
                    candidate!=nodeIndex && nearestJointParent(candidate)==nodeIndex
                }
                val tail=if(childJointNode!=null) {
                    val inverseBone=FloatArray(16)
                    if(Matrix.invertM(inverseBone,0,world,0)) {
                        val relativeChild=FloatArray(16)
                        Matrix.multiplyMM(relativeChild,0,inverseBone,0,nodeGlobalMatrix(childJointNode),0)
                        transformFromMatrix(relativeChild).position
                    } else Vec3(0f,0.25f,0f)
                } else Vec3(0f,0.25f,0f)

                bones.add(RigBone(
                    name=node.optString("name","Bone " + i),
                    parentIndex=parentJointNode?.let { map[it] } ?: -1,
                    head=Vec3(),
                    tail=tail,
                    sourceNodeIndex=nodeIndex,
                    restTransform=restTransform,
                    inverseBindMatrix=inverseMatrices.getOrNull(i)
                ))
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
    data class Values(
        val count:Int,
        val components:Int,
        val values:FloatArray,
        val componentType:Int,
        val normalized:Boolean
    )

    fun read(index: Int): Values {
        require(index in 0 until accessors.length()) { "Accessor glTF fuera de rango." }
        val accessor=accessors.getJSONObject(index)
        val type=accessor.optString("type")
        val components=when(type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT4" -> 16
            else -> throw IllegalArgumentException("Accessor type " + type + " no soportado.")
        }
        val componentType=accessor.getInt("componentType")
        val componentBytes=componentSize(componentType)
        val count=accessor.getInt("count")
        require(count>=0 && count.toLong()*components<=20_000_000L) {
            "Accessor glTF supera el límite de datos admitido para un dispositivo móvil."
        }
        val normalized=accessor.optBoolean("normalized",false)
        val packedStride=components*componentBytes
        val values=FloatArray(count*components)

        // An accessor without a base bufferView starts as a zero-filled array. Sparse
        // accessors may then overwrite only the listed elements.
        val viewIndex=accessor.optInt("bufferView",-1)
        if(viewIndex>=0) {
            require(viewIndex in 0 until views.length()) { "Accessor bufferView glTF fuera de rango." }
            val view=views.getJSONObject(viewIndex)
            val source=bufferForView(view)
            val stride=view.optInt("byteStride",packedStride)
            val viewOffset=view.optInt("byteOffset",0)
            val viewLength=view.optInt("byteLength",-1)
            val accessorOffset=accessor.optInt("byteOffset",0)
            val start=viewOffset.toLong()+accessorOffset.toLong()
            val requiredBytes=if(count>0) (count-1).toLong()*stride+packedStride else 0L
            require(stride>=packedStride && viewOffset>=0 && viewLength>=0 && accessorOffset>=0 &&
                accessorOffset.toLong()+requiredBytes<=viewLength.toLong() &&
                start+requiredBytes<=source.size.toLong()) {
                "Accessor glTF fuera de los límites del buffer."
            }
            val input=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
            val startOffset=start.toInt()
            for(i in 0 until count) for(j in 0 until components) {
                values[i*components+j]=readComponent(input,startOffset+i*stride+j*componentBytes,componentType,normalized)
            }
        }

        accessor.optJSONObject("sparse")?.let { sparse ->
            val sparseCount=sparse.optInt("count",-1)
            require(sparseCount in 0..count) { "Sparse accessor glTF tiene count inválido." }
            val indices=sparse.getJSONObject("indices")
            val sparseIndexType=indices.getInt("componentType")
            require(sparseIndexType in setOf(5121,5123,5125)) {
                "Sparse accessor glTF requiere índices unsigned byte, short o int."
            }
            val indexView=indices.getInt("bufferView")
            require(indexView in 0 until views.length()) { "Sparse indices bufferView fuera de rango." }
            val indexBytes=componentSize(sparseIndexType)
            val indexViewJson=views.getJSONObject(indexView)
            val indexSource=bufferForView(indexViewJson)
            val indexStart=indices.optInt("byteOffset",0)
            val indexViewOffset=indexViewJson.optInt("byteOffset",0)
            val indexViewLength=indexViewJson.optInt("byteLength",-1)
            val absoluteIndexStart=indexViewOffset.toLong()+indexStart.toLong()
            val indexByteLength=sparseCount.toLong()*indexBytes
            require(indexStart>=0 && indexViewOffset>=0 && indexViewLength>=0 &&
                indexStart.toLong()+indexByteLength<=indexViewLength.toLong() &&
                absoluteIndexStart+indexByteLength<=indexSource.size.toLong()) {
                "Sparse indices glTF fuera de los límites del buffer."
            }
            val indexBuffer=ByteBuffer.wrap(indexSource).order(ByteOrder.LITTLE_ENDIAN)

            val sparseValues=sparse.getJSONObject("values")
            val valueView=sparseValues.getInt("bufferView")
            require(valueView in 0 until views.length()) { "Sparse values bufferView fuera de rango." }
            val valueViewJson=views.getJSONObject(valueView)
            val valueSource=bufferForView(valueViewJson)
            val valueStart=sparseValues.optInt("byteOffset",0)
            val valueViewOffset=valueViewJson.optInt("byteOffset",0)
            val valueViewLength=valueViewJson.optInt("byteLength",-1)
            val absoluteValueStart=valueViewOffset.toLong()+valueStart.toLong()
            val sparseValueByteLength=sparseCount.toLong()*packedStride
            require(valueStart>=0 && valueViewOffset>=0 && valueViewLength>=0 &&
                valueStart.toLong()+sparseValueByteLength<=valueViewLength.toLong() &&
                absoluteValueStart+sparseValueByteLength<=valueSource.size.toLong()) {
                "Sparse values glTF fuera de los límites del buffer."
            }
            val valueBuffer=ByteBuffer.wrap(valueSource).order(ByteOrder.LITTLE_ENDIAN)

            for(i in 0 until sparseCount) {
                val target=readComponent(indexBuffer,absoluteIndexStart.toInt()+i*indexBytes,sparseIndexType,false).toInt()
                require(target in 0 until count) { "Sparse accessor index fuera de rango." }
                for(component in 0 until components) {
                    values[target*components+component]=readComponent(
                        valueBuffer,absoluteValueStart.toInt()+(i*components+component)*componentBytes,componentType,normalized
                    )
                }
            }
        }
        return Values(count,components,values,componentType,normalized)
    }

    private fun bufferForView(view:JSONObject):ByteArray {
        val bufferIndex=view.optInt("buffer",0)
        return buffers.getOrNull(bufferIndex)
            ?: throw IllegalArgumentException("Buffer glTF inexistente.")
    }

    private fun componentSize(type:Int):Int=when(type) {
        5120,5121 -> 1
        5122,5123 -> 2
        5125,5126 -> 4
        else -> throw IllegalArgumentException("Componente glTF " + type + " no soportado.")
    }

    private fun readComponent(
        input:ByteBuffer,
        offset:Int,
        componentType:Int,
        normalized:Boolean
    ):Float=when(componentType) {
        5120 -> input.get(offset).toFloat().let { if(normalized) max(-1f,it/127f) else it }
        5121 -> (input.get(offset).toInt() and 0xff).toFloat().let { if(normalized) it/255f else it }
        5122 -> input.getShort(offset).toFloat().let { if(normalized) max(-1f,it/32767f) else it }
        5123 -> (input.getShort(offset).toInt() and 0xffff).toFloat().let { if(normalized) it/65535f else it }
        5125 -> input.getInt(offset).toLong().toFloat()
        5126 -> input.getFloat(offset)
        else -> 0f
    }
}

data class ObjSceneBundle(
    val objText: String,
    val mtlText: String,
    val textures: Map<String,ByteArray>
)

/** Scene-wide OBJ/MTL export with transformed multi-object geometry and texture files. */
object ObjSceneExporter {
    fun encode(project: EditorProject, transformProvider: (SceneNode) -> Transform): ObjSceneBundle {
        val obj=StringBuilder()
        val mtl=StringBuilder()
        val textureFiles=linkedMapOf<String,ByteArray>()
        val textureNames=linkedMapOf<String,String>()
        obj.appendLine("# Gema Engine scene export")
        obj.appendLine("mtllib GemaScene.mtl")
        var vertexOffset=1
        var uvOffset=1
        var normalOffset=1
        var exported=0
        project.scene.nodes.forEachIndexed { nodeIndex,node ->
            if (project.primitiveKinds[node.id] == "BONE_PROXY") return@forEachIndexed
            val mesh=node.mesh ?: return@forEachIndexed
            if (mesh.positions.isEmpty()) return@forEachIndexed
            val transform=transformProvider(node)
            val materialName=safeName(node.material.name) + "_" + nodeIndex
            obj.appendLine()
            obj.appendLine("o " + safeName(node.name))
            obj.appendLine("g " + safeName(node.name))
            obj.appendLine("usemtl " + materialName)
            mesh.positions.forEach { point ->
                val world=transformPoint(point,transform)
                obj.appendLine("v ${world.x} ${world.y} ${world.z}")
            }
            val hasUvs=mesh.texCoords0.size==mesh.positions.size
            val hasNormals=mesh.normals.size==mesh.positions.size
            if (hasUvs) mesh.texCoords0.forEach { uv -> obj.appendLine("vt ${uv.x} ${1f-uv.y}") }
            if (hasNormals) mesh.normals.forEach { normal ->
                val world=transformNormal(normal,transform)
                obj.appendLine("vn ${world.x} ${world.y} ${world.z}")
            }
            val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
            for (i in indices.indices step 3) {
                if (i+2>=indices.size) break
                val face=(0..2).map { corner -> indices[i+corner] }
                if (face.any { it !in mesh.positions.indices }) continue
                val tokens=face.map { index ->
                    val vertex=vertexOffset+index
                    val uv=if(hasUvs) uvOffset+index else -1
                    val normal=if(hasNormals) normalOffset+index else -1
                    when {
                        uv>=0 && normal>=0 -> "$vertex/$uv/$normal"
                        uv>=0 -> "$vertex/$uv"
                        normal>=0 -> "$vertex//$normal"
                        else -> vertex.toString()
                    }
                }
                obj.appendLine("f " + tokens.joinToString(" "))
            }
            vertexOffset+=mesh.positions.size
            if(hasUvs) uvOffset+=mesh.texCoords0.size
            if(hasNormals) normalOffset+=mesh.normals.size

            val material=node.material
            mtl.appendLine("newmtl " + materialName)
            mtl.appendLine("Kd ${material.baseColor.x} ${material.baseColor.y} ${material.baseColor.z}")
            mtl.appendLine("Ke ${material.emissiveColor.x} ${material.emissiveColor.y} ${material.emissiveColor.z}")
            mtl.appendLine("Pr ${material.roughness}")
            mtl.appendLine("Pm ${material.metallic}")
            if(material.alphaMode=="BLEND") mtl.appendLine("d ${material.baseColorAlpha}")
            val textureUri=material.baseColorTextureUri ?: project.textureUris[node.id]
            fun exportMap(uri:String?,property:String) {
                if(uri.isNullOrBlank() || !uri.startsWith("data:") || !uri.contains("base64,")) return
                val textureName=textureNames.getOrPut(uri) {
                    val ext=when {
                        uri.startsWith("data:image/jpeg") -> "jpg"
                        uri.startsWith("data:image/webp") -> "webp"
                        else -> "png"
                    }
                    "GemaTexture_" + (textureNames.size+1) + "." + ext
                }
                val bytes=Base64.decode(uri.substringAfter("base64,"),Base64.DEFAULT)
                textureFiles[textureName]=bytes
                mtl.appendLine(property + " " + textureName)
            }
            exportMap(textureUri,"map_Kd")
            exportMap(material.normalTextureUri,"map_Bump")
            exportMap(material.emissiveTextureUri,"map_Ke")
            exportMap(material.metallicRoughnessTextureUri,"map_Pr")
            exportMap(material.occlusionTextureUri,"map_AO")
            mtl.appendLine()
            exported++
        }
        require(exported>0) { "La escena no contiene mallas exportables." }
        return ObjSceneBundle(obj.toString(),mtl.toString(),textureFiles)
    }

    private fun safeName(value:String):String =
        value.trim().replace(Regex("[^A-Za-z0-9_.-]+"),"_").ifBlank { "Object" }

    private fun transformPoint(point:Vec3,transform:Transform):Vec3 {
        val sx=point.x*transform.scale.x
        val sy=point.y*transform.scale.y
        val sz=point.z*transform.scale.z
        val rx=Math.toRadians(transform.rotationEuler.x.toDouble())
        val ry=Math.toRadians(transform.rotationEuler.y.toDouble())
        val rz=Math.toRadians(transform.rotationEuler.z.toDouble())
        val y1=sy*kotlin.math.cos(rx).toFloat()-sz*kotlin.math.sin(rx).toFloat()
        val z1=sy*kotlin.math.sin(rx).toFloat()+sz*kotlin.math.cos(rx).toFloat()
        val x2=sx*kotlin.math.cos(ry).toFloat()+z1*kotlin.math.sin(ry).toFloat()
        val z2=-sx*kotlin.math.sin(ry).toFloat()+z1*kotlin.math.cos(ry).toFloat()
        val x3=x2*kotlin.math.cos(rz).toFloat()-y1*kotlin.math.sin(rz).toFloat()
        val y3=x2*kotlin.math.sin(rz).toFloat()+y1*kotlin.math.cos(rz).toFloat()
        return Vec3(transform.position.x+x3,transform.position.y+y3,transform.position.z+z2)
    }

    private fun transformNormal(normal:Vec3,transform:Transform):Vec3 {
        val adjusted=Vec3(normal.x/transform.scale.x.coerceAtLeast(0.0001f),
            normal.y/transform.scale.y.coerceAtLeast(0.0001f),
            normal.z/transform.scale.z.coerceAtLeast(0.0001f))
        val point=transformPoint(adjusted,transform.copy(position=Vec3(),scale=Vec3(1f,1f,1f)))
        val length=kotlin.math.sqrt(point.x*point.x+point.y*point.y+point.z*point.z).coerceAtLeast(0.00001f)
        return Vec3(point.x/length,point.y/length,point.z/length)
    }
}

/** Self-contained glTF 2.0 / GLB scene exporter with multiple objects, PBR factors, UVs and skinning data. */
object GltfSceneExporter {
    fun encode(project: EditorProject, asGlb: Boolean): ByteArray {
        val binary = java.io.ByteArrayOutputStream()
        val views = JSONArray()
        val accessors = JSONArray()
        val materialJson = JSONArray()
        val imagesJson = JSONArray()
        val texturesJson = JSONArray()
        val meshesJson = JSONArray()
        val nodesJson = JSONArray()
        val skinsJson = JSONArray()
        val animationJson = JSONArray()
        val textureIndices = linkedMapOf<String,Int>()
        val materialIndices = linkedMapOf<MaterialData,Int>()
        val nodeIndices = project.scene.nodes.mapIndexed { index,node -> node.id to index }.toMap()
        val meshNodeIndices = linkedMapOf<String,Int>()

        fun alignBinary() {
            while (binary.size() % 4 != 0) binary.write(0)
        }
        fun addView(bytes: ByteArray, target: Int? = null): Int {
            alignBinary()
            val offset = binary.size()
            binary.write(bytes)
            val view = JSONObject().put("buffer",0).put("byteOffset",offset).put("byteLength",bytes.size)
            if (target != null) view.put("target",target)
            views.put(view)
            return views.length()-1
        }
        fun addFloatAccessor(values: FloatArray, count: Int, type: String, target: Int? = null, bounds: Pair<FloatArray,FloatArray>? = null): Int {
            val bytes = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { bytes.putFloat(it) }
            val view = addView(bytes.array(),target)
            val accessor = JSONObject().put("bufferView",view).put("componentType",5126).put("count",count).put("type",type)
            bounds?.let {
                accessor.put("min",JSONArray().also { array -> it.first.forEach { value -> array.put(value.toDouble()) } })
                accessor.put("max",JSONArray().also { array -> it.second.forEach { value -> array.put(value.toDouble()) } })
            }
            accessors.put(accessor)
            return accessors.length()-1
        }
        fun addUnsignedAccessor(values: IntArray, componentType: Int, count: Int, type: String, target: Int? = null): Int {
            val bytes = if (componentType == 5123) {
                ByteBuffer.allocate(values.size*2).order(ByteOrder.LITTLE_ENDIAN).also { out ->
                    values.forEach { out.putShort(it.coerceIn(0,65535).toShort()) }
                }.array()
            } else {
                ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).also { out -> values.forEach { out.putInt(it) } }.array()
            }
            val view=addView(bytes,target)
            accessors.put(JSONObject().put("bufferView",view).put("componentType",componentType).put("count",count).put("type",type))
            return accessors.length()-1
        }
        fun addMaterial(material: MaterialData): Int {
            materialIndices[material]?.let { return it }
            fun textureSlot(uri: String?): JSONObject? {
                if (uri.isNullOrBlank()) return null
                val texIndex = textureIndices.getOrPut(uri) {
                    val imageIndex=imagesJson.length()
                    imagesJson.put(JSONObject().put("uri",uri))
                    val index=texturesJson.length()
                    texturesJson.put(JSONObject().put("source",imageIndex))
                    index
                }
                return JSONObject().put("index",texIndex)
            }
            val pbr = JSONObject()
                .put("baseColorFactor",JSONArray().put(material.baseColor.x.toDouble()).put(material.baseColor.y.toDouble())
                    .put(material.baseColor.z.toDouble()).put(material.baseColorAlpha.toDouble()))
                .put("metallicFactor",material.metallic.toDouble())
                .put("roughnessFactor",material.roughness.toDouble())
            textureSlot(material.baseColorTextureUri)?.let { pbr.put("baseColorTexture",it) }
            textureSlot(material.metallicRoughnessTextureUri)?.let { pbr.put("metallicRoughnessTexture",it) }
            val output = JSONObject().put("name",material.name).put("pbrMetallicRoughness",pbr)
                .put("emissiveFactor",JSONArray().put(material.emissiveColor.x.toDouble())
                    .put(material.emissiveColor.y.toDouble()).put(material.emissiveColor.z.toDouble()))
                .put("alphaMode",material.alphaMode).put("alphaCutoff",material.alphaCutoff.toDouble())
            textureSlot(material.normalTextureUri)?.let { output.put("normalTexture",it.put("scale",material.normalScale.toDouble())) }
            textureSlot(material.occlusionTextureUri)?.let {
                output.put("occlusionTexture",it.put("strength",material.occlusionStrength.coerceIn(0f,1f).toDouble()))
            }
            textureSlot(material.emissiveTextureUri)?.let { output.put("emissiveTexture",it) }
            materialJson.put(output)
            val result=materialJson.length()-1
            materialIndices[material]=result
            return result
        }

        project.scene.nodes.forEach { node ->
            val nodeJson=JSONObject().put("name",node.name)
            val translation=node.transform.position
            val rotation=node.transform.rotationEuler
            val scale=node.transform.scale
            val quaternion=eulerToQuaternion(rotation)
            nodeJson.put("translation",JSONArray().put(translation.x.toDouble()).put(translation.y.toDouble()).put(translation.z.toDouble()))
            nodeJson.put("rotation",JSONArray().put(quaternion[0].toDouble()).put(quaternion[1].toDouble())
                .put(quaternion[2].toDouble()).put(quaternion[3].toDouble()))
            nodeJson.put("scale",JSONArray().put(scale.x.toDouble()).put(scale.y.toDouble()).put(scale.z.toDouble()))
            val mesh=node.mesh
            if (mesh != null && mesh.positions.isNotEmpty() && project.primitiveKinds[node.id] != "BONE_PROXY") {
                val positions=FloatArray(mesh.positions.size*3)
                mesh.positions.forEachIndexed { i,v ->
                    positions[i*3]=v.x;positions[i*3+1]=v.y;positions[i*3+2]=v.z
                }
                val minValues=floatArrayOf(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY)
                val maxValues=floatArrayOf(Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY)
                mesh.positions.forEach { v ->
                    minValues[0]=kotlin.math.min(minValues[0],v.x); minValues[1]=kotlin.math.min(minValues[1],v.y); minValues[2]=kotlin.math.min(minValues[2],v.z)
                    maxValues[0]=kotlin.math.max(maxValues[0],v.x); maxValues[1]=kotlin.math.max(maxValues[1],v.y); maxValues[2]=kotlin.math.max(maxValues[2],v.z)
                }
                val attributes=JSONObject()
                attributes.put("POSITION",addFloatAccessor(positions,mesh.positions.size,"VEC3",34962,Pair(minValues,maxValues)))
                if (mesh.normals.size==mesh.positions.size) {
                    val values=FloatArray(mesh.normals.size*3)
                    mesh.normals.forEachIndexed { i,v -> values[i*3]=v.x;values[i*3+1]=v.y;values[i*3+2]=v.z }
                    attributes.put("NORMAL",addFloatAccessor(values,mesh.normals.size,"VEC3",34962))
                }
                if (mesh.texCoords0.size==mesh.positions.size) {
                    val values=FloatArray(mesh.texCoords0.size*2)
                    mesh.texCoords0.forEachIndexed { i,v -> values[i*2]=v.x;values[i*2+1]=1f-v.y }
                    attributes.put("TEXCOORD_0",addFloatAccessor(values,mesh.texCoords0.size,"VEC2",34962))
                }
                if(mesh.tangents0.size==mesh.positions.size) {
                    val values=FloatArray(mesh.tangents0.size*4)
                    mesh.tangents0.forEachIndexed { i,v ->
                        values[i*4]=v.x;values[i*4+1]=v.y;values[i*4+2]=v.z;values[i*4+3]=v.w
                    }
                    attributes.put("TANGENT",addFloatAccessor(values,mesh.tangents0.size,"VEC4",34962))
                }
                if (mesh.joints0.size==mesh.positions.size && mesh.weights0.size==mesh.positions.size) {
                    val jointValues=IntArray(mesh.joints0.size*4)
                    val weightValues=FloatArray(mesh.weights0.size*4)
                    mesh.joints0.forEachIndexed { i,v -> for (j in 0..3) jointValues[i*4+j]=v.getOrElse(j){0} }
                    mesh.weights0.forEachIndexed { i,v ->
                        weightValues[i*4]=v.x;weightValues[i*4+1]=v.y;weightValues[i*4+2]=v.z;weightValues[i*4+3]=v.w
                    }
                    attributes.put("JOINTS_0",addUnsignedAccessor(jointValues,5123,mesh.joints0.size,"VEC4",34962))
                    attributes.put("WEIGHTS_0",addFloatAccessor(weightValues,mesh.weights0.size,"VEC4",34962))
                }
                val indexValues=if (mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
                val useShort=mesh.positions.size<=65535
                val indexAccessor=addUnsignedAccessor(indexValues.toIntArray(),if(useShort)5123 else 5125,indexValues.size,"SCALAR",34963)
                val renderMaterial = node.material.copy(
                    baseColorTextureUri = node.material.baseColorTextureUri ?: project.textureUris[node.id]
                )
                val primitive=JSONObject().put("attributes",attributes).put("indices",indexAccessor)
                    .put("material",addMaterial(renderMaterial)).put("mode",4)
                meshesJson.put(JSONObject().put("name",node.name).put("primitives",JSONArray().put(primitive)))
                val meshIndex=meshesJson.length()-1
                nodeJson.put("mesh",meshIndex)
                meshNodeIndices[node.id]=nodeIndices[node.id] ?: -1
            }
            nodesJson.put(nodeJson)
        }

        val cameraJson=JSONArray()
        val extraCameraNodeIndices=mutableListOf<Int>()
        project.scene.cameras.forEach { camera ->
            val cameraIndex=cameraJson.length()
            cameraJson.put(JSONObject().put("name",camera.name).put("type","perspective")
                .put("perspective",JSONObject()
                    .put("yfov",Math.toRadians(camera.fieldOfView.toDouble()))
                    .put("znear",camera.nearClip.toDouble()).put("zfar",camera.farClip.toDouble())))
            val transform=camera.transform
            val quaternion=eulerToQuaternion(transform.rotationEuler)
            val node=JSONObject().put("name",camera.name)
                .put("camera",cameraIndex)
                .put("translation",JSONArray().put(transform.position.x.toDouble()).put(transform.position.y.toDouble()).put(transform.position.z.toDouble()))
                .put("rotation",JSONArray().put(quaternion[0].toDouble()).put(quaternion[1].toDouble()).put(quaternion[2].toDouble()).put(quaternion[3].toDouble()))
                .put("scale",JSONArray().put(transform.scale.x.toDouble()).put(transform.scale.y.toDouble()).put(transform.scale.z.toDouble()))
            extraCameraNodeIndices.add(nodesJson.length())
            nodesJson.put(node)
        }

        val punctualLights=JSONArray()
        val extraLightNodeIndices=mutableListOf<Int>()
        project.lights.forEach { light ->
            val lightIndex=punctualLights.length()
            val type=if(light.kind=="DIRECTIONAL") "directional" else "point"
            punctualLights.put(JSONObject().put("name",light.name).put("type",type)
                .put("color",JSONArray().put(light.color.x.toDouble()).put(light.color.y.toDouble()).put(light.color.z.toDouble()))
                .put("intensity",light.intensity.toDouble()))
            val node=JSONObject().put("name",light.name)
                .put("extensions",JSONObject().put("KHR_lights_punctual",JSONObject().put("light",lightIndex)))
            if(type=="directional") {
                val direction=light.position
                val length=kotlin.math.sqrt(direction.x*direction.x+direction.y*direction.y+direction.z*direction.z)
                    .coerceAtLeast(0.000001f)
                val towardLight=Vec3(direction.x/length,direction.y/length,direction.z/length)
                val horizontal=kotlin.math.sqrt(towardLight.x*towardLight.x+towardLight.z*towardLight.z)
                // Editor direction is the world-space local +Z vector of the glTF light node.
                val rotation=Vec3(
                    Math.toDegrees(kotlin.math.atan2(-towardLight.y,horizontal).toDouble()).toFloat(),
                    Math.toDegrees(kotlin.math.atan2(towardLight.x,towardLight.z).toDouble()).toFloat(),
                    0f
                )
                val quaternion=RotationMath.eulerToQuaternion(rotation)
                node.put("rotation",JSONArray().put(quaternion.x.toDouble()).put(quaternion.y.toDouble())
                    .put(quaternion.z.toDouble()).put(quaternion.w.toDouble()))
            } else {
                node.put("translation",JSONArray().put(light.position.x.toDouble())
                    .put(light.position.y.toDouble()).put(light.position.z.toDouble()))
            }
            extraLightNodeIndices.add(nodesJson.length())
            nodesJson.put(node)
        }

        // Build glTF node hierarchy after every node has a stable index.
        project.scene.nodes.forEachIndexed { index,node ->
            val parentId = node.parentId
            if (parentId != null) {
                val parentIndex=nodeIndices[parentId]
                if (parentIndex != null) {
                    val parent=nodesJson.getJSONObject(parentIndex)
                    val children=parent.optJSONArray("children") ?: JSONArray().also { parent.put("children",it) }
                    children.put(index)
                }
            }
        }

        project.rigs.forEach { rig ->
            val jointNodeIndices=rig.bones.mapNotNull { bone -> bone.proxyNodeId?.let { nodeIndices[it] } }
            if (jointNodeIndices.isEmpty() || jointNodeIndices.size != rig.bones.size) return@forEach
            val inverseValues=FloatArray(rig.bones.size*16)
            rig.bones.forEachIndexed { index,bone ->
                val values=bone.inverseBindMatrix
                if (values != null && values.size==16) {
                    for (i in 0..15) inverseValues[index*16+i]=values[i]
                } else {
                    inverseValues[index*16]=1f;inverseValues[index*16+5]=1f
                    inverseValues[index*16+10]=1f;inverseValues[index*16+15]=1f
                }
            }
            val inverseAccessor=addFloatAccessor(inverseValues,rig.bones.size,"MAT4")
            val skin=JSONObject().put("name",rig.name)
                .put("joints",JSONArray().also { array -> jointNodeIndices.forEach { array.put(it) } })
                .put("inverseBindMatrices",inverseAccessor)
            rig.bones.indexOfFirst { it.parentIndex < 0 }.takeIf { it >= 0 }?.let { skin.put("skeleton",jointNodeIndices[it]) }
            skinsJson.put(skin)
            val skinIndex=skinsJson.length()-1
            val boundMeshes = (rig.boundMeshIds + listOfNotNull(rig.boundMeshId)).distinct()
            boundMeshes.forEach { meshId ->
                nodeIndices[meshId]?.let { nodeIndex -> nodesJson.getJSONObject(nodeIndex).put("skin",skinIndex) }
            }
        }

        project.scene.animations.forEach { clip ->
            val samplers=JSONArray()
            val channels=JSONArray()
            clip.tracks.forEach trackLoop@ { track ->
                val nodeIndex=nodeIndices[track.nodeId] ?: return@trackLoop
                if (track.keys.isEmpty()) return@trackLoop
                val keys=track.keys.sortedBy { it.time }
                val times=FloatArray(keys.size) { keys[it].time }
                val inputAccessor=addFloatAccessor(times,keys.size,"SCALAR")
                val translation=FloatArray(keys.size*3)
                val scale=FloatArray(keys.size*3)
                val rotation=FloatArray(keys.size*4)
                keys.forEachIndexed { i,key ->
                    translation[i*3]=key.position.x;translation[i*3+1]=key.position.y;translation[i*3+2]=key.position.z
                    scale[i*3]=key.scale.x;scale[i*3+1]=key.scale.y;scale[i*3+2]=key.scale.z
                    val q=key.rotationQuaternion ?: RotationMath.eulerToQuaternion(key.rotation)
                    rotation[i*4]=q.x;rotation[i*4+1]=q.y;rotation[i*4+2]=q.z;rotation[i*4+3]=q.w
                }
                fun channel(path:String,values:FloatArray,type:String,interpolation:String) {
                    val outputAccessor=addFloatAccessor(values,keys.size,type)
                    val samplerIndex=samplers.length()
                    samplers.put(JSONObject().put("input",inputAccessor).put("output",outputAccessor)
                        .put("interpolation",if(interpolation=="STEP") "STEP" else "LINEAR"))
                    channels.put(JSONObject().put("sampler",samplerIndex)
                        .put("target",JSONObject().put("node",nodeIndex).put("path",path)))
                }
                channel("translation",translation,"VEC3",track.positionInterpolation)
                channel("rotation",rotation,"VEC4",track.rotationInterpolation)
                channel("scale",scale,"VEC3",track.scaleInterpolation)
            }
            if (channels.length()>0) animationJson.put(JSONObject().put("name",clip.name).put("samplers",samplers).put("channels",channels))
        }

        val rootNodes=JSONArray()
        project.scene.nodes.forEachIndexed { index,node ->
            val parentId = node.parentId
            if (parentId == null || nodeIndices[parentId] == null) rootNodes.put(index)
        }
        extraCameraNodeIndices.forEach { rootNodes.put(it) }
        extraLightNodeIndices.forEach { rootNodes.put(it) }
        val root=JSONObject()
            .put("asset",JSONObject().put("version","2.0").put("generator","Gema Engine"))
            .put("scene",0)
            .put("scenes",JSONArray().put(JSONObject().put("name",project.name).put("nodes",rootNodes)))
            .put("nodes",nodesJson)
            .put("meshes",meshesJson)
            .put("bufferViews",views)
            .put("accessors",accessors)
            .put("buffers",JSONArray().put(JSONObject().put("byteLength",binary.size())))
        if(cameraJson.length()>0) root.put("cameras",cameraJson)
        if(punctualLights.length()>0) {
            root.put("extensionsUsed",JSONArray().put("KHR_lights_punctual"))
            root.put("extensions",JSONObject().put("KHR_lights_punctual",JSONObject().put("lights",punctualLights)))
        }
        if (materialJson.length()>0) root.put("materials",materialJson)
        if (imagesJson.length()>0) {
            root.put("images",imagesJson)
            root.put("textures",texturesJson)
        }
        if (skinsJson.length()>0) root.put("skins",skinsJson)
        if (animationJson.length()>0) root.put("animations",animationJson)

        val binBytes=binary.toByteArray()
        if (!asGlb) {
            root.getJSONArray("buffers").getJSONObject(0).put("uri",
                "data:application/octet-stream;base64," + Base64.encodeToString(binBytes,Base64.NO_WRAP))
            return root.toString(2).toByteArray(Charsets.UTF_8)
        }
        val jsonBytes=root.toString().toByteArray(Charsets.UTF_8)
        val jsonLength=(jsonBytes.size+3) and -4
        val binaryLength=(binBytes.size+3) and -4
        val output=ByteBuffer.allocate(12+8+jsonLength+8+binaryLength).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(0x46546C67);output.putInt(2);output.putInt(output.capacity())
        output.putInt(jsonLength);output.putInt(0x4E4F534A);output.put(jsonBytes)
        repeat(jsonLength-jsonBytes.size){output.put(0x20)}
        output.putInt(binaryLength);output.putInt(0x004E4942);output.put(binBytes)
        repeat(binaryLength-binBytes.size){output.put(0)}
        return output.array()
    }

    private fun eulerToQuaternion(rotation:Vec3):FloatArray {
        val q=RotationMath.eulerToQuaternion(rotation)
        return floatArrayOf(q.x,q.y,q.z,q.w)
    }
}
