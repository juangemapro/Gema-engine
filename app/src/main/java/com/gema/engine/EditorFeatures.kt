package com.gema.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class RigBone(
    var name: String,
    var parentIndex: Int = -1,
    var head: Vec3 = Vec3(),
    var tail: Vec3 = Vec3(0f, 0.5f, 0f),
    var sourceNodeIndex: Int = -1,
    var restTransform: Transform = Transform(),
    var inverseBindMatrix: FloatArray? = null,
    var proxyNodeId: String? = null
)

data class EditorRig(
    var name: String = "Armature",
    val bones: MutableList<RigBone> = mutableListOf(),
    var boundMeshId: String? = null,
    val boundMeshIds: MutableList<String> = mutableListOf()
)

data class EditorLight(
    var name: String = "Point Light",
    var kind: String = "POINT",
    var position: Vec3 = Vec3(2f, 3f, 2f),
    var color: Vec3 = Vec3(1f, 1f, 1f),
    var intensity: Float = 1f
)

data class RenderEffects(
    var bloom: Boolean = false,
    var vignette: Boolean = false,
    var fog: Boolean = false,
    var wireframe: Boolean = false,
    var outlines: Boolean = true,
    var shadows: Boolean = true,
    var ambientOcclusion: Boolean = false,
    var quality: String = "MEDIUM"
)

data class DrawStroke2D(
    var color: Int = 0xfff1f5fa.toInt(),
    var width: Float = 4f,
    val points: MutableList<Vec3> = mutableListOf()
)

data class Layer2D(
    var name: String,
    var kind: String = "VECTOR",
    var visible: Boolean = true,
    var opacity: Float = 1f,
    val strokes: MutableList<DrawStroke2D> = mutableListOf()
)

data class EditorProject(
    var name: String = "Untitled",
    var scene: SceneDocument = SceneDocument(),
    val primitiveKinds: MutableMap<String, String> = mutableMapOf(),
    val rigs: MutableList<EditorRig> = mutableListOf(),
    val lights: MutableList<EditorLight> = mutableListOf(),
    val textureUris: MutableMap<String, String> = mutableMapOf(),
    val layers2D: MutableList<Layer2D> = mutableListOf(),
    var effects: RenderEffects = RenderEffects(),
    var shaderSource: String = ShaderLibrary.DEFAULT_GSL,
    var shaderMode: String = "GSL"
)

object ShaderLibrary {
    const val DEFAULT_GSL = """shader GemaSurface {
    output = uColor;
}"""
    const val EXAMPLE_TOON_GSL = """shader ToonSurface {
    output = vec4(floor(uColor.r * 4.0) / 4.0, uColor.g, uColor.b, uColor.a);
}"""
    const val DEFAULT_FRAGMENT = """precision mediump float;
uniform vec4 uColor;
uniform sampler2D uTexture;
uniform float uUseTexture;
uniform sampler2D uNormalTexture;
uniform float uUseNormalTexture;
uniform float uNormalScale;
uniform sampler2D uMetallicRoughnessTexture;
uniform float uUseMetallicRoughnessTexture;
uniform sampler2D uOcclusionTexture;
uniform float uUseOcclusionTexture;
uniform sampler2D uEmissiveTexture;
uniform float uUseEmissiveTexture;
uniform float uRoughness;
uniform float uMetallic;
uniform vec3 uEmissive;
uniform float uAlphaMode;
uniform float uAlphaCutoff;
uniform vec3 uCameraPosition;
uniform int uLightCount;
uniform vec3 uLightPosition[4];
uniform vec3 uLightColor[4];
uniform float uLightIntensity[4];
uniform float uLightType[4];
uniform float uFogEnabled;
uniform float uFogDensity;
uniform vec3 uFogColor;
uniform float uVignetteEnabled;
uniform float uVignetteStrength;
uniform vec2 uViewport;
varying vec2 vTexCoord;
varying vec3 vNormal;
varying vec3 vTangent;
varying float vTangentHandedness;
varying vec3 vWorldPosition;

void main() {
    vec4 base = uColor * mix(vec4(1.0), texture2D(uTexture, vTexCoord), uUseTexture);
    if (uAlphaMode > 0.5 && base.a < uAlphaCutoff) discard;
    vec3 n = normalize(vNormal);
    if (uUseNormalTexture > 0.5) {
        vec3 t = normalize(vTangent - n * dot(n, vTangent));
        vec3 b = normalize(cross(n, t)) * vTangentHandedness;
        vec3 mapped = texture2D(uNormalTexture, vTexCoord).xyz * 2.0 - 1.0;
        mapped.xy *= uNormalScale;
        n = normalize(mat3(t, b, n) * mapped);
    }
    vec4 mr = texture2D(uMetallicRoughnessTexture, vTexCoord);
    float roughness = clamp(uRoughness * mix(1.0, mr.g, uUseMetallicRoughnessTexture), 0.04, 1.0);
    float metallic = clamp(uMetallic * mix(1.0, mr.b, uUseMetallicRoughnessTexture), 0.0, 1.0);
    float occlusion = mix(1.0, texture2D(uOcclusionTexture, vTexCoord).r, uUseOcclusionTexture);
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    vec3 f0 = mix(vec3(0.04), base.rgb, metallic);
    vec3 diffuse = base.rgb * (1.0 - metallic) * 0.12 * occlusion;
    vec3 specular = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i < uLightCount) {
            vec3 delta = uLightPosition[i] - vWorldPosition;
            float distanceToLight = length(delta);
            vec3 l = normalize(mix(delta, uLightPosition[i], 1.0 - uLightType[i]));
            float attenuation = mix(1.0, 1.0 / (1.0 + 0.075 * distanceToLight * distanceToLight), uLightType[i]);
            float ndotl = max(dot(n, l), 0.0);
            vec3 h = normalize(l + v);
            float shininess = mix(128.0, 4.0, roughness * roughness);
            float specularPower = pow(max(dot(n, h), 0.0), shininess);
            vec3 radiance = uLightColor[i] * uLightIntensity[i] * attenuation;
            diffuse += base.rgb * (1.0 - metallic) * ndotl * radiance * occlusion;
            specular += f0 * specularPower * radiance;
        }
    }
    vec3 emissiveMap = mix(vec3(1.0), texture2D(uEmissiveTexture, vTexCoord).rgb, uUseEmissiveTexture);
    vec3 emissive = uEmissive * emissiveMap;
    vec4 finalColor=vec4(diffuse+specular+emissive,base.a);
    float fogFactor=1.0-exp(-uFogDensity*length(uCameraPosition-vWorldPosition));
    finalColor.rgb=mix(finalColor.rgb,uFogColor,clamp(fogFactor*uFogEnabled,0.0,0.92));
    vec2 centered=(gl_FragCoord.xy/max(uViewport,vec2(1.0)))-0.5;
    float vignette=1.0-smoothstep(0.2,0.72,length(centered));
    finalColor.rgb*=mix(1.0,vignette,uVignetteEnabled*uVignetteStrength);
    gl_FragColor=finalColor;
}"""
    const val EXAMPLE_TOON = """precision mediump float;
uniform vec4 uColor;
uniform sampler2D uTexture;
uniform float uUseTexture;
uniform sampler2D uNormalTexture;
uniform float uUseNormalTexture;
uniform float uNormalScale;
uniform sampler2D uMetallicRoughnessTexture;
uniform float uUseMetallicRoughnessTexture;
uniform sampler2D uOcclusionTexture;
uniform float uUseOcclusionTexture;
uniform sampler2D uEmissiveTexture;
uniform float uUseEmissiveTexture;
uniform float uRoughness;
uniform float uMetallic;
uniform vec3 uEmissive;
uniform float uAlphaMode;
uniform float uAlphaCutoff;
uniform vec3 uCameraPosition;
uniform int uLightCount;
uniform vec3 uLightPosition[4];
uniform vec3 uLightColor[4];
uniform float uLightIntensity[4];
uniform float uLightType[4];
uniform float uFogEnabled;
uniform float uFogDensity;
uniform vec3 uFogColor;
uniform float uVignetteEnabled;
uniform float uVignetteStrength;
uniform vec2 uViewport;
varying vec2 vTexCoord;
varying vec3 vNormal;
varying vec3 vTangent;
varying float vTangentHandedness;
varying vec3 vWorldPosition;

void main() {
    vec4 base = uColor * mix(vec4(1.0), texture2D(uTexture, vTexCoord), uUseTexture);
    if (uAlphaMode > 0.5 && base.a < uAlphaCutoff) discard;
    vec3 n=normalize(vNormal);
    if (uUseNormalTexture > 0.5) {
        vec3 t=normalize(vTangent-n*dot(n,vTangent));
        vec3 b=normalize(cross(n,t))*vTangentHandedness;
        vec3 mapped=texture2D(uNormalTexture,vTexCoord).xyz*2.0-1.0;
        mapped.xy*=uNormalScale;
        n=normalize(mat3(t,b,n)*mapped);
    }
    vec4 mr=texture2D(uMetallicRoughnessTexture,vTexCoord);
    float metallic=clamp(uMetallic*mix(1.0,mr.b,uUseMetallicRoughnessTexture),0.0,1.0);
    float ao=mix(1.0,texture2D(uOcclusionTexture,vTexCoord).r,uUseOcclusionTexture);
    vec3 emissive=uEmissive*mix(vec3(1.0),texture2D(uEmissiveTexture,vTexCoord).rgb,uUseEmissiveTexture);
    vec3 diffuse=base.rgb*(1.0-metallic)*0.12*ao;
    vec3 specular=vec3(0.0);
    vec3 viewDirection=normalize(uCameraPosition-vWorldPosition);
    for(int i=0;i<4;i++) {
        if(i<uLightCount) {
            vec3 delta=uLightPosition[i]-vWorldPosition;
            float distanceToLight=length(delta);
            vec3 lightDirection=normalize(mix(delta,uLightPosition[i],1.0-uLightType[i]));
            float attenuation=mix(1.0,1.0/(1.0+0.075*distanceToLight*distanceToLight),uLightType[i]);
            float light=max(dot(n,lightDirection),0.0);
            float bands=floor((0.18+0.82*light)*4.0)/4.0;
            vec3 radiance=uLightColor[i]*uLightIntensity[i]*attenuation;
            diffuse+=base.rgb*bands*(1.0-metallic)*ao*radiance;
            vec3 halfway=normalize(lightDirection+viewDirection);
            specular+=mix(vec3(0.04),base.rgb,metallic)*pow(max(dot(n,halfway),0.0),mix(32.0,4.0,clamp(uRoughness,0.04,1.0)))*radiance;
        }
    }
    vec4 finalColor=vec4(diffuse+specular+emissive,base.a);
    float fogFactor=1.0-exp(-uFogDensity*length(uCameraPosition-vWorldPosition));
    finalColor.rgb=mix(finalColor.rgb,uFogColor,clamp(fogFactor*uFogEnabled,0.0,0.92));
    vec2 centered=(gl_FragCoord.xy/max(uViewport,vec2(1.0)))-0.5;
    float vignette=1.0-smoothstep(0.2,0.72,length(centered));
    finalColor.rgb*=mix(1.0,vignette,uVignetteEnabled*uVignetteStrength);
    gl_FragColor=finalColor;
}"""
}

/** Tiny beginner-friendly GSL expression compiler; output is validated and transpiled to GLSL ES. */
object GslCompiler {
    private val generatedLightingHeader = """uniform vec3 uCameraPosition;
uniform int uLightCount;
uniform vec3 uLightPosition[4];
uniform vec3 uLightColor[4];
uniform float uLightIntensity[4];
uniform float uLightType[4];
uniform float uFogEnabled;
uniform float uFogDensity;
uniform vec3 uFogColor;
uniform float uVignetteEnabled;
uniform float uVignetteStrength;
uniform vec2 uViewport;
varying vec2 vTexCoord;
varying vec3 vNormal;
varying vec3 vTangent;
varying float vTangentHandedness;
varying vec3 vWorldPosition;"""
    private val allowedNames = setOf(
        "uColor", "vec2", "vec3", "vec4", "float", "floor", "ceil", "abs",
        "min", "max", "clamp", "mix", "sin", "cos", "pow", "sqrt", "step", "smoothstep",
        "r", "g", "b", "a", "x", "y", "z", "w", "xy", "xyz", "rgb", "rgba"
    )

    fun compile(source: String): String {
        val open = source.indexOf('{')
        val close = source.lastIndexOf('}')
        require(source.trimStart().startsWith("shader ") && open >= 0 && close > open) {
            "GSL debe usar: shader Nombre { output = expresión; }"
        }
        val body = source.substring(open + 1, close)
        val outputStart = body.indexOf("output")
        require(outputStart >= 0) { "Falta la instrucción output." }
        val equals = body.indexOf('=', outputStart)
        val semicolon = body.indexOf(';', equals + 1)
        require(equals > outputStart && semicolon > equals) { "La salida debe terminar con punto y coma." }
        val expression = body.substring(equals + 1, semicolon).trim()
        require(expression.isNotBlank() && Regex("[A-Za-z0-9_.,+*/()\\-\\s]+").matches(expression)) {
            "La expresión contiene caracteres no permitidos."
        }
        val identifiers = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(expression).map { it.value }.toSet()
        require(identifiers.all { it in allowedNames }) {
            "Identificador GSL no reconocido: " + identifiers.first { it !in allowedNames }
        }
        return """precision mediump float;
uniform vec4 uColor;
uniform sampler2D uTexture;
uniform float uUseTexture;
uniform sampler2D uNormalTexture;
uniform float uUseNormalTexture;
uniform float uNormalScale;
uniform sampler2D uMetallicRoughnessTexture;
uniform float uUseMetallicRoughnessTexture;
uniform sampler2D uOcclusionTexture;
uniform float uUseOcclusionTexture;
uniform sampler2D uEmissiveTexture;
uniform float uUseEmissiveTexture;
uniform float uRoughness;
uniform float uMetallic;
uniform vec3 uEmissive;
uniform float uAlphaMode;
uniform float uAlphaCutoff;
$generatedLightingHeader
void main() {
    vec4 base = ($expression) * mix(vec4(1.0), texture2D(uTexture, vTexCoord), uUseTexture);
    if (uAlphaMode > 0.5 && base.a < uAlphaCutoff) discard;
    vec3 n=normalize(vNormal);
    if (uUseNormalTexture > 0.5) {
        vec3 t=normalize(vTangent-n*dot(n,vTangent));
        vec3 b=normalize(cross(n,t))*vTangentHandedness;
        vec3 mapped=texture2D(uNormalTexture,vTexCoord).xyz*2.0-1.0;
        mapped.xy*=uNormalScale;
        n=normalize(mat3(t,b,n)*mapped);
    }
    vec4 mr=texture2D(uMetallicRoughnessTexture,vTexCoord);
    float roughness=clamp(uRoughness*mix(1.0,mr.g,uUseMetallicRoughnessTexture),0.04,1.0);
    float metallic=clamp(uMetallic*mix(1.0,mr.b,uUseMetallicRoughnessTexture),0.0,1.0);
    float ao=mix(1.0,texture2D(uOcclusionTexture,vTexCoord).r,uUseOcclusionTexture);
    vec3 v=normalize(uCameraPosition-vWorldPosition);
    vec3 f0=mix(vec3(0.04),base.rgb,metallic);
    vec3 diffuse=base.rgb*(1.0-metallic)*0.12*ao;
    vec3 specular=vec3(0.0);
    for(int i=0;i<4;i++) {
        if(i<uLightCount) {
            vec3 delta=uLightPosition[i]-vWorldPosition;
            float distanceToLight=length(delta);
            vec3 l=normalize(mix(delta,uLightPosition[i],1.0-uLightType[i]));
            float attenuation=mix(1.0,1.0/(1.0+0.075*distanceToLight*distanceToLight),uLightType[i]);
            float ndotl=max(dot(n,l),0.0);
            vec3 h=normalize(l+v);
            float specPower=pow(max(dot(n,h),0.0),mix(128.0,4.0,roughness*roughness));
            vec3 radiance=uLightColor[i]*uLightIntensity[i]*attenuation;
            diffuse+=base.rgb*(1.0-metallic)*ndotl*radiance*ao;
            specular+=f0*specPower*radiance;
        }
    }
    vec3 emissive=uEmissive*mix(vec3(1.0),texture2D(uEmissiveTexture,vTexCoord).rgb,uUseEmissiveTexture);
    vec4 finalColor=vec4(diffuse+specular+emissive,base.a);
    float fogFactor=1.0-exp(-uFogDensity*length(uCameraPosition-vWorldPosition));
    finalColor.rgb=mix(finalColor.rgb,uFogColor,clamp(fogFactor*uFogEnabled,0.0,0.92));
    vec2 centered=(gl_FragCoord.xy/max(uViewport,vec2(1.0)))-0.5;
    float vignette=1.0-smoothstep(0.2,0.72,length(centered));
    finalColor.rgb*=mix(1.0,vignette,uVignetteEnabled*uVignetteStrength);
    gl_FragColor=finalColor;
}"""
    }
}

/** Project container: extends the .gme scene document without breaking its versioned base format. */
object ProjectCodec {
    fun encode(project: EditorProject): String {
        val root = JSONObject(GmeCodec.encode(project.scene))
        root.put("projectVersion", 2)
        root.put("projectName", project.name)
        val kinds = JSONArray()
        project.primitiveKinds.forEach { (id, kind) -> kinds.put(JSONObject().put("id", id).put("kind", kind)) }
        root.put("primitiveKinds", kinds)
        val rigs = JSONArray()
        project.rigs.forEach { rig ->
            val bones = JSONArray()
            rig.bones.forEach { bone ->
                bones.put(JSONObject().put("name", bone.name).put("parent", bone.parentIndex)
                    .put("head", vec(bone.head)).put("tail", vec(bone.tail)).put("sourceNodeIndex", bone.sourceNodeIndex)
                    .put("restTransform", JSONObject().put("position",vec(bone.restTransform.position))
                        .put("rotation",vec(bone.restTransform.rotationEuler)).put("scale",vec(bone.restTransform.scale)))
                    .put("inverseBind", bone.inverseBindMatrix?.let { values -> JSONArray().also { array -> values.forEach { array.put(it.toDouble()) } } })
                    .put("proxyNodeId", bone.proxyNodeId))
            }
            rigs.put(JSONObject().put("name", rig.name).put("boundMeshId", rig.boundMeshId)
                .put("boundMeshIds", JSONArray().also { array -> rig.boundMeshIds.forEach { array.put(it) } })
                .put("bones", bones))
        }
        root.put("rigs", rigs)
        val lights = JSONArray()
        project.lights.forEach { light ->
            lights.put(JSONObject().put("name", light.name).put("kind", light.kind)
                .put("position", vec(light.position)).put("color", vec(light.color))
                .put("intensity", light.intensity.toDouble()))
        }
        root.put("lights", lights)
        val textures = JSONObject()
        project.textureUris.forEach { (id, uri) -> textures.put(id, uri) }
        root.put("textures", textures)
        val layers = JSONArray()
        project.layers2D.forEach { layer ->
            val strokes = JSONArray()
            layer.strokes.forEach { stroke ->
                val points = JSONArray()
                stroke.points.forEach { points.put(vec(it)) }
                strokes.put(JSONObject().put("color", stroke.color).put("width", stroke.width.toDouble()).put("points", points))
            }
            layers.put(JSONObject().put("name", layer.name).put("kind", layer.kind)
                .put("visible", layer.visible).put("opacity", layer.opacity.toDouble()).put("strokes", strokes))
        }
        root.put("layers2D", layers)
        root.put("effects", JSONObject().put("bloom", project.effects.bloom)
            .put("vignette", project.effects.vignette).put("fog", project.effects.fog)
            .put("wireframe", project.effects.wireframe).put("outlines", project.effects.outlines)
            .put("shadows", project.effects.shadows).put("ao", project.effects.ambientOcclusion)
            .put("quality", project.effects.quality))
        root.put("shaderSource", project.shaderSource)
        root.put("shaderMode", project.shaderMode)
        return root.toString(2)
    }

    fun decode(source: String): EditorProject {
        val root = JSONObject(source)
        val scene = GmeCodec.decode(source)
        val project = EditorProject(name = root.optString("projectName", scene.name), scene = scene)
        val kinds = root.optJSONArray("primitiveKinds") ?: JSONArray()
        for (i in 0 until kinds.length()) {
            val item = kinds.getJSONObject(i)
            project.primitiveKinds[item.optString("id")] = item.optString("kind", "MESH")
        }
        val rigs = root.optJSONArray("rigs") ?: JSONArray()
        for (i in 0 until rigs.length()) {
            val item = rigs.getJSONObject(i)
            val rig = EditorRig(name = item.optString("name", "Armature"))
            rig.boundMeshId = item.optString("boundMeshId").takeIf { it.isNotBlank() && it != "null" }
            val boundMeshes = item.optJSONArray("boundMeshIds") ?: JSONArray()
            for (meshIndex in 0 until boundMeshes.length()) rig.boundMeshIds.add(boundMeshes.optString(meshIndex))
            if (rig.boundMeshId != null && rig.boundMeshId !in rig.boundMeshIds) rig.boundMeshIds.add(rig.boundMeshId!!)
            val bones = item.optJSONArray("bones") ?: JSONArray()
            for (j in 0 until bones.length()) {
                val bone = bones.getJSONObject(j)
                rig.bones.add(RigBone(
                    name = bone.optString("name", "Bone"),
                    parentIndex = bone.optInt("parent", -1),
                    head = readVec(bone.optJSONObject("head")),
                    tail = readVec(bone.optJSONObject("tail")),
                    sourceNodeIndex = bone.optInt("sourceNodeIndex", -1),
                    restTransform = bone.optJSONObject("restTransform")?.let { transform ->
                        Transform(readVec(transform.optJSONObject("position")),readVec(transform.optJSONObject("rotation")),
                            readVec(transform.optJSONObject("scale")).let { if (it == Vec3()) Vec3(1f,1f,1f) else it })
                    } ?: Transform(),
                    inverseBindMatrix = bone.optJSONArray("inverseBind")?.let { array ->
                        FloatArray(array.length()) { index -> array.optDouble(index).toFloat() }
                    },
                    proxyNodeId = bone.optString("proxyNodeId").takeIf { it.isNotBlank() && it != "null" }
                ))
            }
            project.rigs.add(rig)
        }
        val lights = root.optJSONArray("lights") ?: JSONArray()
        for (i in 0 until lights.length()) {
            val item = lights.getJSONObject(i)
            project.lights.add(EditorLight(
                name = item.optString("name", "Light"), kind = item.optString("kind", "POINT"),
                position = readVec(item.optJSONObject("position")), color = readVec(item.optJSONObject("color")),
                intensity = item.optDouble("intensity", 1.0).toFloat()
            ))
        }
        val textures = root.optJSONObject("textures") ?: JSONObject()
        textures.keys().forEach { project.textureUris[it] = textures.optString(it) }
        val layers = root.optJSONArray("layers2D") ?: JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val decoded = Layer2D(layer.optString("name", "Layer"),
                layer.optString("kind", "VECTOR"), layer.optBoolean("visible", true),
                layer.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f))
            val strokes = layer.optJSONArray("strokes") ?: JSONArray()
            for (s in 0 until strokes.length()) {
                val strokeJson = strokes.getJSONObject(s)
                val stroke = DrawStroke2D(strokeJson.optInt("color", 0xfff1f5fa.toInt()),
                    strokeJson.optDouble("width", 4.0).toFloat())
                val points = strokeJson.optJSONArray("points") ?: JSONArray()
                for (p in 0 until points.length()) stroke.points.add(readVec(points.optJSONObject(p)))
                decoded.strokes.add(stroke)
            }
            project.layers2D.add(decoded)
        }
        root.optJSONObject("effects")?.let {
            project.effects = RenderEffects(
                bloom = it.optBoolean("bloom"), vignette = it.optBoolean("vignette"),
                fog = it.optBoolean("fog"), wireframe = it.optBoolean("wireframe"),
                outlines = it.optBoolean("outlines", true), shadows = it.optBoolean("shadows", true),
                ambientOcclusion = it.optBoolean("ao"), quality = it.optString("quality", "MEDIUM")
            )
        }
        project.shaderSource = root.optString("shaderSource", ShaderLibrary.DEFAULT_GSL)
        project.shaderMode = root.optString("shaderMode", "GSL")
        return project
    }

    private fun vec(v: Vec3) = JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("z", v.z.toDouble())
    private fun readVec(o: JSONObject?) = Vec3(
        o?.optDouble("x", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("y", 0.0)?.toFloat() ?: 0f,
        o?.optDouble("z", 0.0)?.toFloat() ?: 0f
    )
}

/** Basic triangle-level mesh editing utilities for the mobile edit mode. */
object MeshEditing {
    fun uniqueEdges(mesh:MeshData):List<Pair<Int,Int>> {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        val result=mutableListOf<Pair<Int,Int>>()
        val seen=HashSet<Long>()
        for(face in 0 until indices.size/3) {
            val base=face*3
            for(edgeIndex in 0..2) {
                val first=indices[base+edgeIndex]
                val second=indices[base+(edgeIndex+1)%3]
                if(first==second || first !in mesh.positions.indices || second !in mesh.positions.indices) continue
                val low=minOf(first,second)
                val high=maxOf(first,second)
                val key=(low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
                if(seen.add(key)) result.add(low to high)
            }
        }
        return result
    }

    fun deleteVertex(mesh:MeshData,vertexIndex:Int):Boolean {
        if(vertexIndex !in mesh.positions.indices) return false
        val oldCount=mesh.positions.size
        val sourceIndices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        val remapped=mutableListOf<Int>()
        for(i in sourceIndices.indices step 3) {
            if(i+2>=sourceIndices.size) break
            val triangle=(0..2).map { sourceIndices[i+it] }
            if(vertexIndex in triangle) continue
            triangle.forEach { index -> remapped.add(if(index>vertexIndex) index-1 else index) }
        }
        mesh.positions.removeAt(vertexIndex)
        if(mesh.normals.size==oldCount) mesh.normals.removeAt(vertexIndex) else mesh.normals.clear()
        if(mesh.texCoords0.size==oldCount) mesh.texCoords0.removeAt(vertexIndex) else mesh.texCoords0.clear()
        if(mesh.tangents0.size==oldCount) mesh.tangents0.removeAt(vertexIndex) else mesh.tangents0.clear()
        if(mesh.joints0.size==oldCount) mesh.joints0.removeAt(vertexIndex) else mesh.joints0.clear()
        if(mesh.weights0.size==oldCount) mesh.weights0.removeAt(vertexIndex) else mesh.weights0.clear()
        if(remapped.isEmpty()) mesh.positions.clear()
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(remapped)
        recalculateNormals(mesh)
        return true
    }

    fun insetTriangle(mesh:MeshData,faceIndex:Int,factor:Float=0.65f):Boolean {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toMutableList() else mesh.triangleIndices.toMutableList()
        val offset=faceIndex*3
        if(offset<0 || offset+2>=indices.size) return false
        val old=intArrayOf(indices[offset],indices[offset+1],indices[offset+2])
        if(old.any { it !in mesh.positions.indices }) return false
        val safeFactor=factor.coerceIn(0.05f,0.95f)
        val sourcePoints=old.map { mesh.positions[it].copy() }
        val center=Vec3(
            (sourcePoints[0].x+sourcePoints[1].x+sourcePoints[2].x)/3f,
            (sourcePoints[0].y+sourcePoints[1].y+sourcePoints[2].y)/3f,
            (sourcePoints[0].z+sourcePoints[1].z+sourcePoints[2].z)/3f
        )
        val hasUvs=mesh.texCoords0.size==mesh.positions.size
        val hasTangents=mesh.tangents0.size==mesh.positions.size
        val hasJoints=mesh.joints0.size==mesh.positions.size
        val hasWeights=mesh.weights0.size==mesh.positions.size
        val inner=IntArray(3)
        for(corner in 0..2) {
            val source=old[corner]
            val point=sourcePoints[corner]
            inner[corner]=mesh.positions.size
            mesh.positions.add(Vec3(
                center.x+(point.x-center.x)*safeFactor,
                center.y+(point.y-center.y)*safeFactor,
                center.z+(point.z-center.z)*safeFactor
            ))
            if(hasUvs) {
                val uv=mesh.texCoords0[source]
                val centroid=Vec2(
                    (mesh.texCoords0[old[0]].x+mesh.texCoords0[old[1]].x+mesh.texCoords0[old[2]].x)/3f,
                    (mesh.texCoords0[old[0]].y+mesh.texCoords0[old[1]].y+mesh.texCoords0[old[2]].y)/3f
                )
                mesh.texCoords0.add(Vec2(
                    centroid.x+(uv.x-centroid.x)*safeFactor,
                    centroid.y+(uv.y-centroid.y)*safeFactor
                ))
            }
            if(hasTangents) mesh.tangents0.add(mesh.tangents0[source].copy())
            if(hasJoints) mesh.joints0.add(mesh.joints0[source].copyOf())
            if(hasWeights) mesh.weights0.add(mesh.weights0[source].copy())
        }
        for(edge in 0..2) {
            val next=(edge+1)%3
            indices.add(old[edge]);indices.add(old[next]);indices.add(inner[next])
            indices.add(old[edge]);indices.add(inner[next]);indices.add(inner[edge])
        }
        indices[offset]=inner[0]
        indices[offset+1]=inner[1]
        indices[offset+2]=inner[2]
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(indices)
        recalculateNormals(mesh)
        return true
    }

    fun deleteTriangle(mesh:MeshData,faceIndex:Int):Boolean {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toMutableList() else mesh.triangleIndices.toMutableList()
        val offset=faceIndex*3
        if(offset<0 || offset+2>=indices.size) return false
        indices.subList(offset,offset+3).clear()
        if(indices.isEmpty()) {
            mesh.positions.clear()
            mesh.normals.clear()
            mesh.texCoords0.clear()
            mesh.tangents0.clear()
            mesh.joints0.clear()
            mesh.weights0.clear()
        }
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(indices)
        recalculateNormals(mesh)
        return true
    }

    fun mergeEdgeVertices(mesh:MeshData,edgeIndex:Int):Boolean {
        val edge=uniqueEdges(mesh).getOrNull(edgeIndex) ?: return false
        val keep=edge.first
        val drop=edge.second
        val oldCount=mesh.positions.size
        val a=mesh.positions[keep]
        val b=mesh.positions[drop]
        mesh.positions[keep]=Vec3((a.x+b.x)*0.5f,(a.y+b.y)*0.5f,(a.z+b.z)*0.5f)

        if(mesh.texCoords0.size==oldCount) {
            val uvA=mesh.texCoords0[keep];val uvB=mesh.texCoords0[drop]
            mesh.texCoords0[keep]=Vec2((uvA.x+uvB.x)*0.5f,(uvA.y+uvB.y)*0.5f)
        }
        if(mesh.normals.size==oldCount) {
            val nA=mesh.normals[keep];val nB=mesh.normals[drop]
            val nx=nA.x+nB.x;val ny=nA.y+nB.y;val nz=nA.z+nB.z
            val length=kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(0.00001f)
            mesh.normals[keep]=Vec3(nx/length,ny/length,nz/length)
        }
        if(mesh.tangents0.size==oldCount) {
            val ta=mesh.tangents0[keep];val tb=mesh.tangents0[drop]
            val tx=ta.x+tb.x;val ty=ta.y+tb.y;val tz=ta.z+tb.z
            val length=kotlin.math.sqrt(tx*tx+ty*ty+tz*tz).coerceAtLeast(0.00001f)
            mesh.tangents0[keep]=Vec4(tx/length,ty/length,tz/length,if(ta.w+tb.w<0f) -1f else 1f)
        }
        if(mesh.joints0.size==oldCount && mesh.weights0.size==oldCount) {
            val merged=linkedMapOf<Int,Float>()
            fun addInfluences(vertex:Int) {
                val joints=mesh.joints0[vertex]
                val weights=mesh.weights0[vertex]
                val values=floatArrayOf(weights.x,weights.y,weights.z,weights.w)
                for(slot in 0..3) {
                    val joint=joints.getOrElse(slot){-1}
                    val weight=values[slot]*0.5f
                    if(joint>=0 && weight>0f) merged[joint]=(merged[joint] ?: 0f)+weight
                }
            }
            addInfluences(keep);addInfluences(drop)
            val strongest=merged.entries.sortedByDescending { it.value }.take(4)
            val total=strongest.sumOf { it.value.toDouble() }.toFloat()
            val joints=IntArray(4);val weights=FloatArray(4)
            if(total>0f) strongest.forEachIndexed { slot,entry ->
                joints[slot]=entry.key;weights[slot]=entry.value/total
            }
            mesh.joints0[keep]=joints
            mesh.weights0[keep]=Vec4(weights[0],weights[1],weights[2],weights[3])
        }

        val sourceIndices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        val remapped=mutableListOf<Int>()
        for(i in sourceIndices.indices step 3) {
            if(i+2>=sourceIndices.size) break
            val triangle=(0..2).map { corner ->
                val index=sourceIndices[i+corner]
                when {
                    index==drop -> keep
                    index>drop -> index-1
                    else -> index
                }
            }
            if(triangle.distinct().size==3) remapped.addAll(triangle)
        }
        mesh.positions.removeAt(drop)
        if(mesh.normals.size==oldCount) mesh.normals.removeAt(drop) else mesh.normals.clear()
        if(mesh.texCoords0.size==oldCount) mesh.texCoords0.removeAt(drop) else mesh.texCoords0.clear()
        if(mesh.tangents0.size==oldCount) mesh.tangents0.removeAt(drop) else mesh.tangents0.clear()
        if(mesh.joints0.size==oldCount) mesh.joints0.removeAt(drop) else mesh.joints0.clear()
        if(mesh.weights0.size==oldCount) mesh.weights0.removeAt(drop) else mesh.weights0.clear()
        if(remapped.isEmpty()) mesh.positions.clear()
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(remapped)
        recalculateNormals(mesh)
        return true
    }

    fun recalculateNormals(mesh: MeshData) {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        val normals=MutableList(mesh.positions.size) { Vec3() }
        for(i in indices.indices step 3) {
            if(i+2>=indices.size) break
            val ia=indices[i]; val ib=indices[i+1]; val ic=indices[i+2]
            if(ia !in mesh.positions.indices || ib !in mesh.positions.indices || ic !in mesh.positions.indices) continue
            val a=mesh.positions[ia]; val b=mesh.positions[ib]; val c=mesh.positions[ic]
            val ab=Vec3(b.x-a.x,b.y-a.y,b.z-a.z)
            val ac=Vec3(c.x-a.x,c.y-a.y,c.z-a.z)
            val n=Vec3(ab.y*ac.z-ab.z*ac.y,ab.z*ac.x-ab.x*ac.z,ab.x*ac.y-ab.y*ac.x)
            listOf(ia,ib,ic).forEach { index ->
                normals[index].x+=n.x; normals[index].y+=n.y; normals[index].z+=n.z
            }
        }
        normals.forEach { n ->
            val length=kotlin.math.sqrt(n.x*n.x+n.y*n.y+n.z*n.z).coerceAtLeast(0.00001f)
            n.x/=length; n.y/=length; n.z/=length
        }
        mesh.normals.clear()
        mesh.normals.addAll(normals)
    }

    fun extrudeTriangle(mesh: MeshData, faceIndex: Int, distance: Float): Boolean {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toMutableList() else mesh.triangleIndices.toMutableList()
        val offset=faceIndex*3
        if(offset<0 || offset+2>=indices.size) return false
        val old=intArrayOf(indices[offset],indices[offset+1],indices[offset+2])
        if(old.any { it !in mesh.positions.indices }) return false
        val a=mesh.positions[old[0]]; val b=mesh.positions[old[1]]; val c=mesh.positions[old[2]]
        val ab=Vec3(b.x-a.x,b.y-a.y,b.z-a.z)
        val ac=Vec3(c.x-a.x,c.y-a.y,c.z-a.z)
        val normal=Vec3(ab.y*ac.z-ab.z*ac.y,ab.z*ac.x-ab.x*ac.z,ab.x*ac.y-ab.y*ac.x)
        val magnitude=kotlin.math.sqrt(normal.x*normal.x+normal.y*normal.y+normal.z*normal.z).coerceAtLeast(0.00001f)
        normal.x=normal.x/magnitude*distance
        normal.y=normal.y/magnitude*distance
        normal.z=normal.z/magnitude*distance
        val hasUvs=mesh.texCoords0.size==mesh.positions.size
        val hasTangents=mesh.tangents0.size==mesh.positions.size
        val hasJoints=mesh.joints0.size==mesh.positions.size
        val hasWeights=mesh.weights0.size==mesh.positions.size
        val added=IntArray(3)
        for(corner in 0..2) {
            val source=old[corner]
            val p=mesh.positions[source]
            added[corner]=mesh.positions.size
            mesh.positions.add(Vec3(p.x+normal.x,p.y+normal.y,p.z+normal.z))
            if(hasUvs) mesh.texCoords0.add(mesh.texCoords0[source].copy())
            if(hasTangents) mesh.tangents0.add(mesh.tangents0[source].copy())
            if(hasJoints) mesh.joints0.add(mesh.joints0[source].copyOf())
            if(hasWeights) mesh.weights0.add(mesh.weights0[source].copy())
        }
        for(edge in 0..2) {
            val next=(edge+1)%3
            indices.add(old[edge]); indices.add(old[next]); indices.add(added[next])
            indices.add(old[edge]); indices.add(added[next]); indices.add(added[edge])
        }
        indices.add(added[0]); indices.add(added[1]); indices.add(added[2])
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(indices)
        recalculateNormals(mesh)
        return true
    }

    fun flipNormals(mesh: MeshData) {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toMutableList() else mesh.triangleIndices.toMutableList()
        for(i in indices.indices step 3) {
            if(i+2>=indices.size) break
            val temp=indices[i+1]; indices[i+1]=indices[i+2]; indices[i+2]=temp
        }
        mesh.triangleIndices.clear()
        mesh.triangleIndices.addAll(indices)
        mesh.normals.forEach { it.x=-it.x; it.y=-it.y; it.z=-it.z }
    }
}

object MeshFactory {
    fun create(kind: String): MeshData {
        val normalized=kind.uppercase()
        val mesh=when (normalized) {
            "SPHERE" -> sphere()
            "CAPSULE" -> capsule()
            "PLANE" -> plane()
            "CYLINDER" -> cylinder(false)
            "CONE" -> cylinder(true)
            "TORUS" -> torus()
            else -> cube()
        }
        addSurfaceAttributes(mesh,normalized)
        return mesh
    }

    private fun normalize(v:Vec3):Vec3 {
        val length=kotlin.math.sqrt(v.x*v.x+v.y*v.y+v.z*v.z).coerceAtLeast(0.00001f)
        return Vec3(v.x/length,v.y/length,v.z/length)
    }

    private fun addSurfaceAttributes(mesh:MeshData,kind:String) {
        if(mesh.positions.isEmpty()) return
        if(mesh.normals.size!=mesh.positions.size) {
            when(kind) {
                "SPHERE" -> mesh.normals.addAll(mesh.positions.map { normalize(it) })
                "CAPSULE" -> mesh.normals.addAll(mesh.positions.map { point ->
                    val centerY=if(point.y<0f) -0.5f else 0.5f
                    normalize(Vec3(point.x,point.y-centerY,point.z))
                })
                "TORUS" -> mesh.normals.addAll(mesh.positions.map { point ->
                    val angle=kotlin.math.atan2(point.z,point.x)
                    normalize(Vec3(
                        point.x-0.68f*kotlin.math.cos(angle).toFloat(),
                        point.y,
                        point.z-0.68f*kotlin.math.sin(angle).toFloat()
                    ))
                })
                else -> MeshEditing.recalculateNormals(mesh)
            }
        }
        if(mesh.texCoords0.size==mesh.positions.size) return
        val uv=MutableList(mesh.positions.size) { Vec2() }
        when(kind) {
            "SPHERE","CAPSULE" -> {
                mesh.positions.forEachIndexed { index,p ->
                    val normal=mesh.normals.getOrNull(index) ?: normalize(p)
                    uv[index]=Vec2(
                        (kotlin.math.atan2(normal.z,normal.x)/(2f*Math.PI.toFloat())+0.5f),
                        (0.5f-kotlin.math.asin(normal.y.coerceIn(-1f,1f))/Math.PI.toFloat())
                    )
                }
                unwrapTriangleSeams(mesh,uv,0)
            }
            "TORUS" -> {
                mesh.positions.forEachIndexed { index,p ->
                    val around=kotlin.math.atan2(p.z,p.x)
                    val radial=kotlin.math.sqrt(p.x*p.x+p.z*p.z)-0.68f
                    val section=kotlin.math.atan2(p.y,radial)
                    uv[index]=Vec2(
                        around/(2f*Math.PI.toFloat())+0.5f,
                        section/(2f*Math.PI.toFloat())+0.5f
                    )
                }
                unwrapTriangleSeams(mesh,uv,0)
                unwrapTriangleSeams(mesh,uv,1)
            }
            else -> {
                val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
                for(i in indices.indices step 3) {
                    if(i+2>=indices.size) break
                    val ids=listOf(indices[i],indices[i+1],indices[i+2])
                    if(ids.any { it !in mesh.positions.indices }) continue
                    val normal=mesh.normals.getOrNull(ids[0]) ?: Vec3(0f,1f,0f)
                    val axis=kotlin.math.abs(normal.x).let { ax ->
                        val ay=kotlin.math.abs(normal.y);val az=kotlin.math.abs(normal.z)
                        when { ax>=ay && ax>=az -> 0; ay>=az -> 1; else -> 2 }
                    }
                    ids.forEach { index ->
                        val p=mesh.positions[index]
                        uv[index]=when(axis) {
                            0 -> Vec2((p.z+1f)*0.5f,1f-(p.y+1f)*0.5f)
                            1 -> Vec2((p.x+1f)*0.5f,1f-(p.z+1f)*0.5f)
                            else -> Vec2((p.x+1f)*0.5f,1f-(p.y+1f)*0.5f)
                        }
                    }
                }
            }
        }
        mesh.texCoords0.addAll(uv)
    }

    private fun unwrapTriangleSeams(mesh:MeshData,uv:MutableList<Vec2>,axis:Int) {
        val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        for(i in indices.indices step 3) {
            if(i+2>=indices.size) break
            val ids=listOf(indices[i],indices[i+1],indices[i+2])
            if(ids.any { it !in uv.indices }) continue
            val values=ids.map { if(axis==0) uv[it].x else uv[it].y }
            if(values.maxOrNull()!!-values.minOrNull()!!>0.5f) {
                ids.forEach { index ->
                    val value=if(axis==0) uv[index].x else uv[index].y
                    if(value<0.5f) {
                        if(axis==0) uv[index].x=value+1f else uv[index].y=value+1f
                    }
                }
            }
        }
    }

    private fun meshFromTriangles(points: List<Vec3>): MeshData {
        val mesh = MeshData()
        mesh.positions.addAll(points)
        mesh.triangleIndices.addAll(points.indices)
        return mesh
    }

    fun cube(): MeshData {
        val p = arrayOf(
            Vec3(-1f,-1f,-1f), Vec3(1f,-1f,-1f), Vec3(1f,1f,-1f), Vec3(-1f,1f,-1f),
            Vec3(-1f,-1f,1f), Vec3(1f,-1f,1f), Vec3(1f,1f,1f), Vec3(-1f,1f,1f)
        )
        val ix = intArrayOf(0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,3,2,6,3,6,7,1,5,6,1,6,2,0,3,7,0,7,4)
        return meshFromTriangles(ix.map { p[it] })
    }

    fun plane(): MeshData = meshFromTriangles(listOf(
        Vec3(-1f,0f,-1f), Vec3(1f,0f,1f), Vec3(1f,0f,-1f),
        Vec3(-1f,0f,-1f), Vec3(-1f,0f,1f), Vec3(1f,0f,1f)
    ))

    fun sphere(): MeshData {
        val out = mutableListOf<Vec3>()
        val lat = 18
        val lon = 28
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = -PI / 2 + i * PI / lat
            val b = -PI / 2 + (i + 1) * PI / lat
            val c = j * 2 * PI / lon
            val d = (j + 1) * 2 * PI / lon
            fun v(t: Double, p: Double) = Vec3((cos(t) * cos(p)).toFloat(), sin(t).toFloat(), (cos(t) * sin(p)).toFloat())
            out.add(v(a,c)); out.add(v(b,c)); out.add(v(b,d))
            out.add(v(a,c)); out.add(v(b,d)); out.add(v(a,d))
        }
        return meshFromTriangles(out)
    }

    fun capsule(): MeshData {
        val out=mutableListOf<Vec3>()
        val hemisphere=10
        val longitude=28
        val rings=hemisphere*2
        for(i in 0 until rings) for(j in 0 until longitude) {
            val theta0=-PI/2+i*PI/rings
            val theta1=-PI/2+(i+1)*PI/rings
            val phi0=2*PI*j/longitude
            val phi1=2*PI*(j+1)/longitude
            fun point(theta:Double,phi:Double):Vec3 {
                val y=if(theta<0) 0.5*sin(theta)-0.5 else 0.5*sin(theta)+0.5
                val r=0.5*cos(theta)
                return Vec3((r*cos(phi)).toFloat(),y.toFloat(),(r*sin(phi)).toFloat())
            }
            val a=point(theta0,phi0);val b=point(theta1,phi0)
            val c=point(theta1,phi1);val d=point(theta0,phi1)
            out.add(a);out.add(b);out.add(c)
            out.add(a);out.add(c);out.add(d)
        }
        return meshFromTriangles(out)
    }

    private fun cylinder(cone: Boolean): MeshData {
        val out = mutableListOf<Vec3>()
        val segments = 32
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            val b = 2 * PI * (i + 1) / segments
            val p0 = Vec3(cos(a).toFloat(), -1f, sin(a).toFloat())
            val p1 = Vec3(cos(b).toFloat(), -1f, sin(b).toFloat())
            val top0 = if (cone) Vec3(0f,1f,0f) else Vec3(cos(a).toFloat(),1f,sin(a).toFloat())
            val top1 = if (cone) Vec3(0f,1f,0f) else Vec3(cos(b).toFloat(),1f,sin(b).toFloat())
            out.add(p0); out.add(p1); out.add(top1)
            out.add(p0); out.add(top1); out.add(top0)
            out.add(Vec3(0f,-1f,0f)); out.add(p1); out.add(p0)
            if (!cone) { out.add(Vec3(0f,1f,0f)); out.add(top0); out.add(top1) }
        }
        return meshFromTriangles(out)
    }

    private fun torus(): MeshData {
        val out = mutableListOf<Vec3>()
        val major = 24; val minor = 10
        for (i in 0 until major) for (j in 0 until minor) {
            val a = 2 * PI * i / major; val b = 2 * PI * (i + 1) / major
            val c = 2 * PI * j / minor; val d = 2 * PI * (j + 1) / minor
            fun v(u: Double, w: Double): Vec3 {
                val r = 0.68 + 0.24 * cos(w)
                return Vec3((r * cos(u)).toFloat(), (0.24 * sin(w)).toFloat(), (r * sin(u)).toFloat())
            }
            out.add(v(a,c)); out.add(v(b,c)); out.add(v(b,d))
            out.add(v(a,c)); out.add(v(b,d)); out.add(v(a,d))
        }
        return meshFromTriangles(out)
    }
}

/** Import for real, implemented paths: .obj, .gltf and .glb. Other listed formats are recognized but report a clear limitation. */
object AssetPipeline {
    fun importMesh(fileName: String, bytes: ByteArray): MeshData {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "obj" -> ObjCodec.decode(String(bytes, Charsets.UTF_8))
            "gltf" -> GltfCodec.decode(String(bytes, Charsets.UTF_8), null)
            "glb" -> GltfCodec.decodeGlb(bytes)
            "stl" -> StlCodec.decode(bytes)
            "ply" -> PlyCodec.decode(String(bytes, Charsets.UTF_8))
            "gme" -> throw IllegalArgumentException("Use the GME project loader for .gme files.")
            "fbx" -> FbxAsciiCodec.decode(String(bytes, Charsets.UTF_8))
            "blend" -> throw IllegalArgumentException("Blender .blend files need a conversion bridge; direct parsing is not included yet.")
            "prisma" -> throw IllegalArgumentException("Prisma project files are not a documented public interchange format here yet.")
            else -> throw IllegalArgumentException("Unsupported file type. Try .gme, .obj, .gltf or .glb.")
        }
    }
}

/** ASCII and binary STL triangle mesh reader/writer. Normals and color extensions are not retained. */
object StlCodec {
    fun decode(bytes: ByteArray): MeshData {
        if (bytes.size >= 84) {
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = header.getInt(80).toLong() and 0xffffffffL
            if (count > 0 && count <= 2_000_000 && 84L + count * 50L <= bytes.size.toLong()) {
                val mesh = MeshData()
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (face in 0 until count.toInt()) {
                    val start = 84 + face * 50 + 12
                    repeat(3) { vertex ->
                        val offset = start + vertex * 12
                        mesh.positions.add(Vec3(data.getFloat(offset), data.getFloat(offset + 4), data.getFloat(offset + 8)))
                        mesh.triangleIndices.add(mesh.positions.lastIndex)
                    }
                }
                return mesh
            }
        }
        val source = String(bytes, Charsets.UTF_8)
        val points = mutableListOf<Vec3>()
        source.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4 && parts[0].equals("vertex", true)) {
                points.add(Vec3(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
            }
        }
        require(points.isNotEmpty() && points.size % 3 == 0) { "STL ASCII has no valid triangle vertices." }
        val mesh = MeshData()
        mesh.positions.addAll(points)
        mesh.triangleIndices.addAll(points.indices)
        return mesh
    }

    fun encode(mesh: MeshData): String = buildString {
        appendLine("solid GemaEngine")
        val indices = if (mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        for (i in indices.indices step 3) {
            if (i + 2 >= indices.size) break
            val a = mesh.positions[indices[i]]
            val b = mesh.positions[indices[i + 1]]
            val c = mesh.positions[indices[i + 2]]
            appendLine(" facet normal 0 0 0")
            appendLine("  outer loop")
            appendLine("   vertex ${a.x} ${a.y} ${a.z}")
            appendLine("   vertex ${b.x} ${b.y} ${b.z}")
            appendLine("   vertex ${c.x} ${c.y} ${c.z}")
            appendLine("  endloop")
            appendLine(" endfacet")
        }
        appendLine("endsolid GemaEngine")
    }
}

/** ASCII PLY importer supporting x/y/z vertex properties and polygon faces. */
object PlyCodec {
    private data class Property(
        val name:String,
        val scalarType:String?=null,
        val listCountType:String?=null,
        val listValueType:String?=null
    )
    private data class Element(val name:String,val count:Int,val properties:MutableList<Property> = mutableListOf())

    fun decode(bytes:ByteArray):MeshData {
        val endToken="end_header".toByteArray(Charsets.US_ASCII)
        var end=-1
        outer@ for(i in 0..bytes.size-endToken.size) {
            for(j in endToken.indices) if(bytes[i+j]!=endToken[j]) continue@outer
            end=i+endToken.size
            break
        }
        require(end>0) { "PLY header has no end_header marker." }
        var dataOffset=end
        while(dataOffset<bytes.size && (bytes[dataOffset]==10.toByte() || bytes[dataOffset]==13.toByte())) dataOffset++
        val header=String(bytes,0,end,Charsets.US_ASCII)
        val format=Regex("""format\s+(ascii|binary_little_endian|binary_big_endian)\s+1\.0""")
            .find(header)?.groupValues?.get(1) ?: throw IllegalArgumentException("Unsupported PLY format/version.")
        val elements=mutableListOf<Element>()
        var current:Element?=null
        header.lineSequence().forEach { raw ->
            val line=raw.trim()
            when {
                line.startsWith("element ") -> {
                    val parts=line.split(Regex("\\s+"))
                    if(parts.size>=3) {
                        val count=parts[2].toIntOrNull() ?: throw IllegalArgumentException("PLY element count inválido.")
                        require(count>=0 && count<=20_000_000) { "PLY element supera el límite admitido." }
                        Element(parts[1],count).also { elements.add(it);current=it }
                    }
                }
                line.startsWith("property list ") -> {
                    val parts=line.split(Regex("\\s+"))
                    if(parts.size>=5) current?.properties?.add(Property(parts[4],null,parts[2],parts[3]))
                }
                line.startsWith("property ") -> {
                    val parts=line.split(Regex("\\s+"))
                    if(parts.size>=3) current?.properties?.add(Property(parts[2],parts[1]))
                }
            }
        }
        if(format=="ascii") return decodeAsciiBody(bytes,dataOffset,elements)
        val order=if(format=="binary_little_endian") ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer=ByteBuffer.wrap(bytes).order(order)
        buffer.position(dataOffset)
        val mesh=MeshData()
        val facePolygons=mutableListOf<List<Int>>()
        elements.forEach { element ->
            repeat(element.count.coerceAtLeast(0)) {
                var x=0f;var y=0f;var z=0f
                var vertexX=false;var vertexY=false;var vertexZ=false
                var faceIndices:List<Int>?=null
                element.properties.forEach { property ->
                    if(property.scalarType!=null) {
                        val value=readPlyScalar(buffer,property.scalarType)
                        if(element.name=="vertex") when(property.name) {
                            "x" -> { x=value.toFloat();vertexX=true }
                            "y" -> { y=value.toFloat();vertexY=true }
                            "z" -> { z=value.toFloat();vertexZ=true }
                        }
                    } else {
                        val count=readPlyScalar(buffer,property.listCountType ?: "uchar").toInt().coerceIn(0,1_000_000)
                        val values=ArrayList<Int>(count)
                        repeat(count) {
                            val value=readPlyScalar(buffer,property.listValueType ?: "int").toInt()
                            if(property.name=="vertex_indices" || property.name=="vertex_index") values.add(value)
                        }
                        if(element.name=="face" && (property.name=="vertex_indices" || property.name=="vertex_index"))
                            faceIndices=values
                    }
                }
                if(element.name=="vertex") {
                    require(vertexX && vertexY && vertexZ) { "PLY vertices must define x/y/z properties." }
                    mesh.positions.add(Vec3(x,y,z))
                } else if(element.name=="face" && faceIndices!=null) {
                    facePolygons.add(faceIndices!!)
                }
            }
        }
        require(mesh.positions.isNotEmpty()) { "PLY file contains no vertices." }
        facePolygons.forEach { polygon ->
            require(polygon.all { it in 0 until mesh.positions.size }) { "PLY face index outside vertex array." }
            if(polygon.size>=3) for(i in 1 until polygon.size-1) {
                mesh.triangleIndices.add(polygon[0]);mesh.triangleIndices.add(polygon[i]);mesh.triangleIndices.add(polygon[i+1])
            }
        }
        if(mesh.triangleIndices.isEmpty()) mesh.triangleIndices.addAll(mesh.positions.indices)
        return mesh
    }

    private fun decodeAsciiBody(bytes:ByteArray,offset:Int,elements:List<Element>):MeshData {
        val body=String(bytes,offset,bytes.size-offset,Charsets.US_ASCII)
        val tokens=body.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .flatMap { it.trim().split(Regex("""\s+""")).asSequence() }
            .filter { it.isNotBlank() }
            .iterator()
        fun nextToken():String=tokens.takeIf { it.hasNext() }?.next()
            ?: throw IllegalArgumentException("PLY ASCII data is truncated.")
        val mesh=MeshData()
        val facePolygons=mutableListOf<List<Int>>()
        elements.forEach { element ->
            repeat(element.count) {
                var x=0f;var y=0f;var z=0f
                var vertexX=false;var vertexY=false;var vertexZ=false
                var faceIndices:List<Int>?=null
                element.properties.forEach { property ->
                    if(property.scalarType!=null) {
                        val value=nextToken().toDoubleOrNull()
                            ?: throw IllegalArgumentException("PLY ASCII scalar value is invalid.")
                        if(element.name=="vertex") when(property.name) {
                            "x" -> { x=value.toFloat();vertexX=true }
                            "y" -> { y=value.toFloat();vertexY=true }
                            "z" -> { z=value.toFloat();vertexZ=true }
                        }
                    } else {
                        val count=nextToken().toIntOrNull()
                            ?: throw IllegalArgumentException("PLY ASCII list count is invalid.")
                        require(count in 0..1_000_000) { "PLY ASCII list exceeds the supported size." }
                        val values=ArrayList<Int>(if(property.name=="vertex_indices" || property.name=="vertex_index") count else 0)
                        repeat(count) {
                            val token=nextToken()
                            if(property.name=="vertex_indices" || property.name=="vertex_index") {
                                values.add(token.toIntOrNull() ?: throw IllegalArgumentException("PLY face index is invalid."))
                            }
                        }
                        if(element.name=="face" && (property.name=="vertex_indices" || property.name=="vertex_index"))
                            faceIndices=values
                    }
                }
                if(element.name=="vertex") {
                    require(vertexX && vertexY && vertexZ) { "PLY vertices must define x/y/z properties." }
                    mesh.positions.add(Vec3(x,y,z))
                } else if(element.name=="face" && faceIndices!=null) {
                    facePolygons.add(faceIndices!!)
                }
            }
        }
        require(mesh.positions.isNotEmpty()) { "PLY file contains no vertices." }
        facePolygons.forEach { polygon ->
            require(polygon.all { it in 0 until mesh.positions.size }) { "PLY face index outside vertex array." }
            if(polygon.size>=3) for(i in 1 until polygon.size-1) {
                mesh.triangleIndices.add(polygon[0]);mesh.triangleIndices.add(polygon[i]);mesh.triangleIndices.add(polygon[i+1])
            }
        }
        if(mesh.triangleIndices.isEmpty()) mesh.triangleIndices.addAll(mesh.positions.indices)
        return mesh
    }

    private fun readPlyScalar(buffer:ByteBuffer,type:String):Double = when(type.lowercase()) {
        "char","int8" -> buffer.get().toDouble()
        "uchar","uint8" -> (buffer.get().toInt() and 0xff).toDouble()
        "short","int16" -> buffer.short.toDouble()
        "ushort","uint16" -> (buffer.short.toInt() and 0xffff).toDouble()
        "int","int32" -> buffer.int.toDouble()
        "uint","uint32" -> (buffer.int.toLong() and 0xffffffffL).toDouble()
        "float","float32" -> buffer.float.toDouble()
        "double","float64" -> buffer.double
        else -> throw IllegalArgumentException("Unsupported PLY scalar type: " + type)
    }

    fun encode(mesh: MeshData): String = buildString {
        appendLine("ply")
        appendLine("format ascii 1.0")
        appendLine("element vertex " + mesh.positions.size)
        appendLine("property float x")
        appendLine("property float y")
        appendLine("property float z")
        val indices = if (mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        appendLine("element face " + (indices.size / 3))
        appendLine("property list uchar int vertex_indices")
        appendLine("end_header")
        mesh.positions.forEach { appendLine("${it.x} ${it.y} ${it.z}") }
        for (i in indices.indices step 3) {
            if (i + 2 < indices.size) appendLine("3 ${indices[i]} ${indices[i + 1]} ${indices[i + 2]}")
        }
    }

    fun decode(source: String): MeshData = decode(source.toByteArray(Charsets.UTF_8))
}

/** Partial ASCII FBX mesh reader. Binary FBX, materials, rigs and animation stacks are not supported. */
object FbxAsciiCodec {
    fun decode(source: String): MeshData {
        require(!source.startsWith("Kaydara FBX Binary")) {
            "Este archivo FBX es binario. La importación disponible en esta versión solo procesa FBX ASCII."
        }
        fun values(name: String): List<String> {
            val pattern = Regex(name + """:\s*\*\d+\s*\{\s*a\s*:\s*([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(source) ?: return emptyList()
            return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val rawVertices = values("Vertices")
        require(rawVertices.size >= 9 && rawVertices.size % 3 == 0) { "FBX ASCII has no supported vertex array." }
        val positions = rawVertices.map { it.toFloat() }.chunked(3).map { Vec3(it[0], it[1], it[2]) }
        val faceValues = values("PolygonVertexIndex")
        require(faceValues.isNotEmpty()) { "FBX ASCII has no polygon index array." }
        val mesh = MeshData()
        mesh.positions.addAll(positions)
        val polygon = mutableListOf<Int>()
        faceValues.forEach { token ->
            val encoded = token.toInt()
            val index = if (encoded < 0) -encoded - 1 else encoded
            require(index in positions.indices) { "FBX polygon index is outside vertex array." }
            polygon.add(index)
            if (encoded < 0) {
                if (polygon.size >= 3) {
                    for (i in 1 until polygon.size - 1) {
                        mesh.triangleIndices.add(polygon[0])
                        mesh.triangleIndices.add(polygon[i])
                        mesh.triangleIndices.add(polygon[i + 1])
                    }
                }
                polygon.clear()
            }
        }
        require(mesh.triangleIndices.isNotEmpty()) { "FBX contains no triangulatable polygons." }
        return mesh
    }
}

object GltfCodec {
    fun decodeGlb(bytes: ByteArray): MeshData {
        require(bytes.size >= 20) { "GLB file is too small" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46546C67) { "Invalid GLB header" }
        require(buffer.int == 2) { "Only glTF 2.0 GLB is supported" }
        val totalLength = buffer.int
        require(totalLength <= bytes.size && totalLength >= 20) { "Invalid GLB length" }
        var json: String? = null
        var binary: ByteArray? = null
        while (buffer.position() + 8 <= totalLength) {
            val length = buffer.int
            val type = buffer.int
            require(length >= 0 && buffer.position() + length <= totalLength) { "Invalid GLB chunk" }
            val chunk = ByteArray(length)
            buffer.get(chunk)
            when (type) {
                0x4E4F534A -> json = String(chunk, Charsets.UTF_8).trimEnd('\u0000', ' ')
                0x004E4942 -> binary = chunk
            }
        }
        return decode(requireNotNull(json) { "GLB JSON chunk is missing" }, binary)
    }

    fun decode(jsonText: String, binaryChunk: ByteArray?): MeshData {
        val root = JSONObject(jsonText)
        require(root.optJSONObject("asset")?.optString("version")?.startsWith("2") == true) { "Only glTF 2.x is supported" }
        val buffersJson = root.optJSONArray("buffers") ?: JSONArray()
        val binaryBuffers = ArrayList<ByteArray>()
        for (i in 0 until buffersJson.length()) {
            val item = buffersJson.getJSONObject(i)
            val uri = item.optString("uri", "")
            val bytes = when {
                uri.startsWith("data:") && uri.contains("base64,") -> Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
                i == 0 && binaryChunk != null -> binaryChunk
                else -> throw IllegalArgumentException("This glTF references an external buffer. Embed it as a GLB or data URI first.")
            }
            binaryBuffers.add(bytes)
        }
        val views = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        require(meshes.length() > 0) { "glTF contains no meshes" }
        val primitive = meshes.getJSONObject(0).optJSONArray("primitives")?.optJSONObject(0)
            ?: throw IllegalArgumentException("glTF mesh has no primitive")
        val attributes = primitive.getJSONObject("attributes")
        val positionAccessor = accessors.getJSONObject(attributes.getInt("POSITION"))
        val positionView = views.getJSONObject(positionAccessor.getInt("bufferView"))
        val vertexBytes = binaryBuffers[positionView.optInt("buffer", 0)]
        val vertexOffset = positionView.optInt("byteOffset", 0) + positionAccessor.optInt("byteOffset", 0)
        val stride = positionView.optInt("byteStride", 12)
        val vertexCount = positionAccessor.getInt("count")
        require(positionAccessor.optString("type") == "VEC3" && positionAccessor.optInt("componentType") == 5126) {
            "Only FLOAT VEC3 POSITION accessors are supported in this importer."
        }
        val mesh = MeshData()
        val vertexBuffer = ByteBuffer.wrap(vertexBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until vertexCount) {
            val offset = vertexOffset + i * stride
            mesh.positions.add(Vec3(vertexBuffer.getFloat(offset), vertexBuffer.getFloat(offset + 4), vertexBuffer.getFloat(offset + 8)))
        }
        val indexAccessorId = primitive.optInt("indices", -1)
        if (indexAccessorId >= 0) {
            val accessor = accessors.getJSONObject(indexAccessorId)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val data = ByteBuffer.wrap(binaryBuffers[view.optInt("buffer", 0)]).order(ByteOrder.LITTLE_ENDIAN)
            val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            val count = accessor.getInt("count")
            val component = accessor.getInt("componentType")
            for (i in 0 until count) {
                val at = offset + i * when (component) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> throw IllegalArgumentException("Unsupported glTF index type") }
                val value = when (component) {
                    5121 -> data.get(at).toInt() and 0xff
                    5123 -> data.getShort(at).toInt() and 0xffff
                    else -> data.getInt(at)
                }
                mesh.triangleIndices.add(value)
            }
        } else {
            mesh.triangleIndices.addAll(mesh.positions.indices)
        }
        require(mesh.triangleIndices.all { it in mesh.positions.indices }) { "glTF index is outside the vertex buffer" }
        return mesh
    }

    fun encode(mesh: MeshData, asGlb: Boolean): ByteArray {
        require(mesh.positions.isNotEmpty()) { "Cannot export an empty mesh" }
        val indices = if (mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
        val vertexBytes = ByteBuffer.allocate(mesh.positions.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        mesh.positions.forEach { vertexBytes.putFloat(it.x); vertexBytes.putFloat(it.y); vertexBytes.putFloat(it.z) }
        while (vertexBytes.position() % 4 != 0) vertexBytes.put(0)
        val indexOffset = vertexBytes.position()
        indices.forEach { vertexBytes.putInt(it) }
        val binary = vertexBytes.array()
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "Gema Engine"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
            .put("meshes", JSONArray().put(JSONObject().put("primitives",
                JSONArray().put(JSONObject().put("attributes", JSONObject().put("POSITION", 0)).put("indices", 1)))))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", binary.size)))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer",0).put("byteOffset",0).put("byteLength",indexOffset).put("target",34962))
                .put(JSONObject().put("buffer",0).put("byteOffset",indexOffset).put("byteLength",binary.size-indexOffset).put("target",34963)))
            .put("accessors", JSONArray()
                .put(JSONObject().put("bufferView",0).put("componentType",5126).put("count",mesh.positions.size).put("type","VEC3"))
                .put(JSONObject().put("bufferView",1).put("componentType",5125).put("count",indices.size).put("type","SCALAR")))
        if (!asGlb) {
            json.getJSONArray("buffers").getJSONObject(0).put("uri", "data:application/octet-stream;base64," + Base64.encodeToString(binary, Base64.NO_WRAP))
            return json.toString(2).toByteArray(Charsets.UTF_8)
        }
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonLength = (jsonBytes.size + 3) and -4
        val binLength = (binary.size + 3) and -4
        val output = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + binLength).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(0x46546C67); output.putInt(2); output.putInt(output.capacity())
        output.putInt(jsonLength); output.putInt(0x4E4F534A); output.put(jsonBytes)
        repeat(jsonLength - jsonBytes.size) { output.put(0x20) }
        output.putInt(binLength); output.putInt(0x004E4942); output.put(binary)
        repeat(binLength - binary.size) { output.put(0) }
        return output.array()
    }
}

fun EditorProject.meshForExport(node: SceneNode): MeshData {
    return node.mesh ?: MeshFactory.create(primitiveKinds[node.id] ?: "CUBE")
}
