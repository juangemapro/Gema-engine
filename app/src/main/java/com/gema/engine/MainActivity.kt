package com.gema.engine

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLES20
import android.opengl.Matrix

private data class SceneObject(
    val name: String,
    val primitive: Primitive,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var rotation: Float = 0f,
    var scale: Float = 1f
)
private enum class Primitive { CUBE, SPHERE }
private enum class ToolMode { MOVE, ROTATE, SCALE }

class MainActivity : Activity() {
    private val scene = mutableListOf(SceneObject("Cube", Primitive.CUBE))
    private var selected = 0
    private var mode = ToolMode.MOVE
    private lateinit var renderer: GemaRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var objectsLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff111720.toInt()
        window.navigationBarColor = 0xff111720.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff111720.toInt())
            setPadding(12, 8, 12, 8)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "GEMA ENGINE"
            textSize = 19f
            setTextColor(0xfff3f6fb.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, 52, 1f))
        header.addView(makeButton("+ Add", "Add primitive") { addPrimitive() })
        root.addView(header)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = GemaRenderer(sceneSnapshot())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xffaebbd0.toInt())
            setPadding(4, 8, 4, 8)
        }
        root.addView(status)

        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        ToolMode.values().forEach { tool ->
            modeRow.addView(makeButton(tool.name, "Select ${tool.name.lowercase()} tool") {
                mode = tool
                refreshUi()
            }, LinearLayout.LayoutParams(0, 48, 1f))
        }
        root.addView(modeRow)

        val editRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        editRow.addView(makeButton("−", "Decrease selected value") { edit(-0.2f) }, LinearLayout.LayoutParams(0, 48, 1f))
        editRow.addView(makeButton("+", "Increase selected value") { edit(0.2f) }, LinearLayout.LayoutParams(0, 48, 1f))
        editRow.addView(makeButton("Next object", "Select next object") {
            if (scene.isNotEmpty()) selected = (selected + 1) % scene.size
            refreshUi()
        }, LinearLayout.LayoutParams(0, 48, 2f))
        root.addView(editRow)

        objectsLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xffd5deeb.toInt())
            setPadding(4, 8, 4, 8)
        }
        root.addView(objectsLabel)
        setContentView(root)
        refreshUi()
    }

    private fun makeButton(label: String, description: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            contentDescription = description
            textSize = 11f
            isAllCaps = false
            setTextColor(0xfff2f5fa.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff293648.toInt()))
            setOnClickListener { action() }
        }

    private fun sceneSnapshot(): List<SceneObject> = scene.map { it.copy() }

    private fun addPrimitive() {
        val next = if (scene.size % 2 == 0) Primitive.SPHERE else Primitive.CUBE
        scene.add(SceneObject(if (next == Primitive.CUBE) "Cube ${scene.size + 1}" else "Sphere ${scene.size + 1}", next, x = scene.size * 0.35f))
        selected = scene.lastIndex
        renderer.scene = sceneSnapshot()
        refreshUi()
    }

    private fun edit(amount: Float) {
        if (scene.isEmpty()) return
        val obj = scene[selected]
        when (mode) {
            ToolMode.MOVE -> obj.x += amount
            ToolMode.ROTATE -> obj.rotation += amount * 45f
            ToolMode.SCALE -> obj.scale = (obj.scale + amount).coerceIn(0.1f, 3f)
        }
        refreshUi()
    }

    private fun refreshUi() {
        val obj = scene.getOrNull(selected)
        status.text = "Mode: ${mode.name}   •   Objects: ${scene.size}"
        objectsLabel.text = if (obj == null) "Empty scene" else
            "Selected: ${obj.name}   |   X: %.1f   Scale: %.1f".format(obj.x, obj.scale)
        renderer.scene = scene
    }

    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onResume() { super.onResume(); glView.onResume() }
}

private class GemaRenderer(@Volatile var scene: List<SceneObject>) : GLSurfaceView.Renderer {
    private var program = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private var cubeBuffer: FloatBuffer? = null
    private var sphereBuffer: FloatBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.071f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vertex = """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        cubeBuffer = buffer(cubeVertices())
        sphereBuffer = buffer(sphereVertices())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height.coerceAtLeast(1))
        Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / height.coerceAtLeast(1), 0.1f, 100f)
        Matrix.setLookAtM(view, 0, 4f, 3f, 7f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
        val colorLocation = GLES20.glGetUniformLocation(program, "uColor")
        scene.forEach { obj ->
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, obj.x, obj.y, obj.z)
            Matrix.rotateM(model, 0, obj.rotation, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, obj.scale, obj.scale, obj.scale)
            Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
            GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
            GLES20.glUniform4f(colorLocation, 0.24f, 0.67f, 0.96f, 1f)
            val mesh = (if (obj.primitive == Primitive.CUBE) cubeBuffer else sphereBuffer) ?: return@forEach
            mesh.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, mesh)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.capacity() / 3)
            GLES20.glDisableVertexAttribArray(position)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun buffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    private fun cubeVertices(): FloatArray {
        val p = arrayOf(
            floatArrayOf(-1f,-1f,-1f), floatArrayOf(1f,-1f,-1f), floatArrayOf(1f,1f,-1f), floatArrayOf(-1f,1f,-1f),
            floatArrayOf(-1f,-1f,1f), floatArrayOf(1f,-1f,1f), floatArrayOf(1f,1f,1f), floatArrayOf(-1f,1f,1f)
        )
        val indices = intArrayOf(0,1,2,0,2,3,4,6,5,4,7,6,0,4,5,0,5,1,3,2,6,3,6,7,1,5,6,1,6,2,0,3,7,0,7,4)
        return indices.flatMap { p[it].asIterable() }.toFloatArray()
    }

    private fun sphereVertices(): FloatArray {
        val out = ArrayList<Float>()
        val lat = 12
        val lon = 18
        fun vertex(theta: Double, phi: Double) {
            out.add((kotlin.math.cos(theta) * kotlin.math.cos(phi)).toFloat())
            out.add(kotlin.math.sin(theta).toFloat())
            out.add((kotlin.math.cos(theta) * kotlin.math.sin(phi)).toFloat())
        }
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = -Math.PI / 2 + i * Math.PI / lat
            val b = -Math.PI / 2 + (i + 1) * Math.PI / lat
            val c = j * 2 * Math.PI / lon
            val d = (j + 1) * 2 * Math.PI / lon
            vertex(a,c); vertex(b,c); vertex(b,d)
            vertex(a,c); vertex(b,d); vertex(a,d)
        }
        return out.toFloatArray()
    }
}
