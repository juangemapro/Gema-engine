package com.gema.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.sqrt

private const val BG = 0xff101720.toInt()
private const val SURFACE = 0xff192431.toInt()
private const val SURFACE_2 = 0xff223244.toInt()
private const val ACCENT = 0xff55c7ed.toInt()
private const val TEXT = 0xfff1f5fa.toInt()
private const val MUTED = 0xffa6b4c5.toInt()

private var sketchCanvas: SketchCanvasView? = null
private var active2DLayerIndex = 0

private class SketchCanvasView(context: android.content.Context, val layer: Layer2D) : View(context) {
    private val density = resources.displayMetrics.density
    private var current: DrawStroke2D? = null
    private var penColor = 0xfff1f5fa.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setPenColor(color: Int) { penColor = color }
    fun undo() { if (layer.strokes.isNotEmpty()) layer.strokes.removeAt(layer.strokes.lastIndex); invalidate() }
    fun clearLayer() { layer.strokes.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff0c131b.toInt())
        if (!layer.visible) return
        layer.strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            paint.color = stroke.color
            paint.strokeWidth = stroke.width * density
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x * width, first.y * height)
            stroke.points.drop(1).forEach { path.lineTo(it.x * width, it.y * height) }
            if (stroke.points.size == 1) {
                canvas.drawCircle(first.x * width, first.y * height, paint.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
                paint.style = Paint.Style.STROKE
            } else canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = DrawStroke2D(penColor, 4f)
                current?.points?.add(Vec3((event.x / width.coerceAtLeast(1)).coerceIn(0f,1f),
                    (event.y / height.coerceAtLeast(1)).coerceIn(0f,1f), 0f))
                current?.let { layer.strokes.add(it) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                current?.points?.add(Vec3((event.x / width.coerceAtLeast(1)).coerceIn(0f,1f),
                    (event.y / height.coerceAtLeast(1)).coerceIn(0f,1f), 0f))
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) current = null
                return true
            }
        }
        return true
    }
}

private data class RenderItem(
    val id: String, val name: String, val vertices: FloatArray,
    val position: Vec3, val rotation: Vec3, val scale: Vec3,
    val color: Vec3, val selected: Boolean
)

private class OrbitGLView(
    activity: Activity,
    private val onOrbit: (Float, Float) -> Unit,
    private val onZoom: (Float) -> Unit
) : GLSurfaceView(activity) {
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_POINTER_DOWN -> { if (event.pointerCount >= 2) lastDistance = spacing(event); return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = spacing(event)
                    if (lastDistance > 0f && d > 0f) onZoom(d / lastDistance)
                    lastDistance = d
                } else {
                    onOrbit(event.x - lastX, event.y - lastY)
                    lastX = event.x; lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                lastDistance = 0f; performClick(); return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}

class MainActivity : Activity() {
    private var project = EditorProject()
    private var selectedId: String? = null
    private var activeTab = "Scene"
    private var transformMode = "MOVE"
    private var playing = false
    private var simulating = false
    private var currentTime = 0f
    private lateinit var root: LinearLayout
    private lateinit var renderer: GemaRenderer
    private lateinit var glView: OrbitGLView
    private lateinit var viewportInfo: TextView
    private lateinit var panel: LinearLayout
    private lateinit var timelineSeek: SeekBar
    private lateinit var timelineLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val physicsWorld = PhysicsWorld()
    private var lastFrame = 0L
    private var saveKind = ""
    private var textureTarget: String? = null

    private val frameLoop = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 1f / 30f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrame = now
            if (simulating) physicsWorld.step(project.scene.nodes, dt)
            if (playing) {
                val clip = activeClip()
                currentTime += dt
                if (currentTime > clip.duration) currentTime = 0f
                TimelineEvaluator.apply(clip, currentTime, project.scene)
                updateTimelineUi()
            }
            if (simulating || playing) { pushSceneToRenderer(); handler.postDelayed(this, 33L) }
            else lastFrame = 0L
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        project.scene.cameras.add(CameraData())
        addObject("Cube", "CUBE", Vec3(0f, 1f, 0f), true)
        showHome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun newRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun text(value: String, size: Float = 13f, color: Int = TEXT, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    private fun btn(value: String, action: () -> Unit, primary: Boolean = false) =
        Button(this).apply {
            text = value; textSize = 11f; isAllCaps = false; minHeight = dp(40)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextColor(if (primary) 0xff07131b.toInt() else TEXT)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(if (primary) ACCENT else SURFACE_2))
            setOnClickListener { action() }
        }
    private fun section(title: String, sub: String? = null) {
        panel.addView(text(title, 15f, TEXT, true))
        if (sub != null) panel.addView(text(sub, 10f, MUTED))
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun showHome() {
        stopTasks()
        root = newRoot()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("GEMA ENGINE", 20f, TEXT, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(text("STUDIO  •  MOBILE", 10f, ACCENT, true))
        root.addView(header)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(20)) }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(20)); setBackgroundColor(SURFACE)
        }
        hero.addView(text("Crea sin límites.", 28f, TEXT, true))
        hero.addView(text("Modela, anima y construye mundos 3D desde tu móvil.", 14f, MUTED))
        hero.addView(text("MODELADO  •  ANIMACIÓN  •  MATERIALES  •  SHADERS", 10f, ACCENT, true))
        body.addView(hero)
        val create = btn("＋   Crear nuevo proyecto", {
            project = EditorProject(name = "Mi proyecto")
            project.scene.cameras.add(CameraData())
            addObject("Cube", "CUBE", Vec3(0f, 1f, 0f), true)
            openEditor("Scene")
        }, true)
        body.addView(create, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        body.addView(btn("Abrir proyecto  ·  GME / GLB / GLTF / OBJ", { openDocument() }),
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        body.addView(btn("Continuar en el editor", { openEditor("Scene") }),
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        body.addView(text("Tu espacio creativo", 18f, TEXT, true),
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        homeCard(body, "Modelado y escena", "Primitivas, jerarquía, transformaciones y cámaras", "Abrir editor") { openEditor("Scene") }
        homeCard(body, "Animación y rigging", "Timeline, keyframes, armatures y huesos", "Ver herramientas") { openEditor("Animation") }
        homeCard(body, "Materiales y render", "Colores, texturas, efectos y ajustes visuales", "Ver materiales") { openEditor("Materials") }
        homeCard(body, "Código y shaders", "Editor GLSL ES y ejemplos de shaders", "Abrir shaders") { openEditor("Shaders") }
        body.addView(text("Gema Engine 0.2.0  •  Vista previa de desarrollo", 10f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(8))
        })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun homeCard(body: LinearLayout, title: String, description: String, cta: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(SURFACE)
        }
        card.addView(text(title, 16f, TEXT, true))
        card.addView(text(description, 12f, MUTED))
        card.addView(btn(cta + "  →", action), LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun openEditor(tab: String = activeTab) {
        activeTab = tab
        root = newRoot()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(btn("‹", { showHome() }), LinearLayout.LayoutParams(dp(46), dp(44)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(project.name, 15f, TEXT, true))
        titleBox.addView(text("ESCENA 3D  •  " + project.scene.nodes.size + " objetos", 9f, MUTED))
        top.addView(titleBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(btn("Guardar", { saveGme() }, true))
        root.addView(top)

        val canvas = FrameLayout(this)
        glView = OrbitGLView(this, { dx, dy -> renderer.orbit(dx, dy) }, { factor -> renderer.zoom(factor) }).apply {
            setEGLContextClientVersion(2)
            renderer = GemaRenderer()
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        canvas.addView(glView, FrameLayout.LayoutParams(-1, -1))
        val badge = text("PERSPECTIVA  •  SOLID", 10f, ACCENT, true).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5)); setBackgroundColor(0xcc14212d.toInt())
        }
        canvas.addView(badge, FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), 0, 0)
        })
        viewportInfo = text("Arrastra para orbitar  ·  Pellizca para zoom", 10f).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5)); setBackgroundColor(0xcc14212d.toInt())
        }
        canvas.addView(viewportInfo, FrameLayout.LayoutParams(-2, dp(28), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, 0, dp(8))
        })
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        listOf("MOVE", "ROTATE", "SCALE").forEach { mode ->
            tools.addView(btn(if (transformMode == mode) "● " + mode else mode, {
                transformMode = mode; openEditor(activeTab)
            }, transformMode == mode), LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        root.addView(tools)

        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf("Scene", "Add", "Transform", "Materials", "Animation", "Physics", "Rig", "Camera", "Effects", "2D", "Assets", "Shaders", "Export").forEach { name ->
            tabs.addView(btn(if (activeTab == name) "● " + name else name, {
                activeTab = name; openEditor(name)
            }, activeTab == name))
        }
        tabsScroll.addView(tabs)
        root.addView(tabsScroll, LinearLayout.LayoutParams(-1, dp(46)))

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(SURFACE) }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(222)))
        buildPanel()
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(text("GME 0.2.0", 9f, MUTED), LinearLayout.LayoutParams(0, dp(24), 1f))
        footer.addView(text(project.scene.nodes.size.toString() + " OBJ  ·  " +
            project.rigs.sumOf { it.bones.size } + " HUESOS  ·  " + project.scene.cameras.size + " CAM", 9f, MUTED))
        root.addView(footer)
        pushSceneToRenderer()
        setContentView(root)
    }

    private fun buildPanel() {
        panel.removeAllViews()
        when (activeTab) {
            "Scene" -> scenePanel()
            "Add" -> addPanel()
            "Transform" -> transformPanel()
            "Materials" -> materialsPanel()
            "Animation" -> animationPanel()
            "Physics" -> physicsPanel()
            "Rig" -> rigPanel()
            "Camera" -> cameraPanel()
            "Effects" -> effectsPanel()
            "2D" -> layers2DPanel()
            "Assets" -> assetsPanel()
            "Shaders" -> shadersPanel()
            "Export" -> exportPanel()
        }
    }

    private fun scenePanel() {
        section("Jerarquía de escena", "Selecciona, renombra, duplica o elimina objetos")
        val actions = LinearLayout(this)
        actions.addView(btn("+ Añadir", { openEditor("Add") }, true))
        actions.addView(btn("Duplicar", { duplicateSelected(); openEditor("Scene") }))
        actions.addView(btn("Eliminar", { deleteSelected() }))
        panel.addView(actions)
        project.scene.nodes.forEach { node ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
            row.addView(text((if (node.id == selectedId) "◆  " else "◇  ") + node.name, 13f,
                if (node.id == selectedId) ACCENT else TEXT, node.id == selectedId), LinearLayout.LayoutParams(0, dp(38), 1f))
            row.addView(btn("⋯", { objectActions(node) }))
            row.setOnClickListener { selectedId = node.id; openEditor("Scene") }
            row.setBackgroundColor(if (node.id == selectedId) 0xff263a4e.toInt() else Color.TRANSPARENT)
            panel.addView(row)
        }
        panel.addView(text("Cámaras: " + project.scene.cameras.size + "  ·  Luces: " + project.lights.size +
            "  ·  Armatures: " + project.rigs.size, 10f, MUTED))
    }

    private fun objectActions(node: SceneNode) {
        AlertDialog.Builder(this).setTitle(node.name)
            .setItems(arrayOf("Seleccionar", "Renombrar", "Duplicar", "Eliminar", "Añadir física")) { _, choice ->
                when (choice) {
                    0 -> selectedId = node.id
                    1 -> renameNode(node)
                    2 -> duplicateNode(node)
                    3 -> { project.scene.nodes.removeAll { it.id == node.id }; project.primitiveKinds.remove(node.id); selectedId = project.scene.nodes.firstOrNull()?.id }
                    4 -> node.physics = node.physics ?: PhysicsBody()
                }
                openEditor("Scene")
            }.show()
    }

    private fun renameNode(node: SceneNode) {
        val input = EditText(this).apply { setText(node.name); hint = "Nombre del objeto" }
        AlertDialog.Builder(this).setTitle("Renombrar objeto").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ -> node.name = input.text.toString().ifBlank { node.name }; openEditor("Scene") }.show()
    }

    private fun addPanel() {
        section("Añadir a la escena", "Primitivas listas para editar")
        val kinds = listOf(
            listOf("Cubo" to "CUBE", "Esfera" to "SPHERE", "Plano" to "PLANE"),
            listOf("Cilindro" to "CYLINDER", "Cono" to "CONE", "Toro" to "TORUS")
        )
        kinds.forEach { line ->
            val row = LinearLayout(this)
            line.forEach { pair ->
                row.addView(btn("+ " + pair.first, {
                    addObject(pair.first, pair.second, Vec3(0f, 1f, 0f), true); openEditor("Scene")
                }, true), LinearLayout.LayoutParams(0, dp(42), 1f))
            }
            panel.addView(row)
        }
        val extra = LinearLayout(this)
        extra.addView(btn("+ Cámara", { project.scene.cameras.add(CameraData("Camera " + (project.scene.cameras.size + 1))); openEditor("Camera") }))
        extra.addView(btn("+ Luz", { project.lights.add(EditorLight("Point Light " + (project.lights.size + 1))); openEditor("Camera") }))
        extra.addView(btn("+ Hueso", { addBone(); openEditor("Rig") }))
        panel.addView(extra)
        panel.addView(text("Siguiente: texto 3D, curvas, metaballs y primitivas paramétricas.", 10f, MUTED))
    }

    private fun transformPanel() {
        val node = selectedNode() ?: run { section("Transformación", "Selecciona un objeto"); return }
        section("Transformación", node.name)
        val p = node.transform.position; val r = node.transform.rotationEuler; val s = node.transform.scale
        panel.addView(text("Posición  X %.2f  Y %.2f  Z %.2f".format(p.x,p.y,p.z), 12f))
        axisButtons("Mover") { axis, value -> when(axis) { 0 -> p.x += value; 1 -> p.y += value; else -> p.z += value } }
        panel.addView(text("Rotación  X %.1f°  Y %.1f°  Z %.1f°".format(r.x,r.y,r.z), 12f))
        axisButtons("Rotar") { axis, value -> when(axis) { 0 -> r.x += value*15f; 1 -> r.y += value*15f; else -> r.z += value*15f } }
        panel.addView(text("Escala  X %.2f  Y %.2f  Z %.2f".format(s.x,s.y,s.z), 12f))
        axisButtons("Escalar") { axis, value ->
            val factor = if (value > 0) 1.1f else 0.9f
            when(axis) { 0 -> s.x *= factor; 1 -> s.y *= factor; else -> s.z *= factor }
        }
        panel.addView(btn("Editar valores exactos", { transformDialog(node) }))
    }

    private fun axisButtons(title: String, update: (Int, Float) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(title, 10f, MUTED), LinearLayout.LayoutParams(dp(60), dp(36)))
        listOf("X","Y","Z").forEachIndexed { axis, name ->
            row.addView(btn(name + "−", { update(axis, -1f); pushSceneToRenderer(); openEditor("Transform") }))
            row.addView(btn(name + "+", { update(axis, 1f); pushSceneToRenderer(); openEditor("Transform") }))
        }
        panel.addView(row)
    }

    private fun transformDialog(node: SceneNode) {
        val values = listOf(node.transform.position.x,node.transform.position.y,node.transform.position.z,
            node.transform.rotationEuler.x,node.transform.rotationEuler.y,node.transform.rotationEuler.z,
            node.transform.scale.x,node.transform.scale.y,node.transform.scale.z)
        val names = listOf("Pos X","Pos Y","Pos Z","Rot X","Rot Y","Rot Z","Scale X","Scale Y","Scale Z")
        val fields = mutableListOf<EditText>()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0) }
        values.forEachIndexed { i, value ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(names[i], 11f, MUTED), LinearLayout.LayoutParams(dp(70), dp(40)))
            val field = EditText(this).apply { setSingleLine(true); inputType = 8194; setText(value.toString()) }
            fields.add(field); row.addView(field, LinearLayout.LayoutParams(0, dp(40), 1f)); box.addView(row)
        }
        AlertDialog.Builder(this).setTitle("Transformación avanzada").setView(box)
            .setNegativeButton("Cancelar", null).setPositiveButton("Aplicar") { _, _ ->
                val v = fields.map { it.text.toString().toFloatOrNull() ?: 0f }
                node.transform.position = Vec3(v[0],v[1],v[2])
                node.transform.rotationEuler = Vec3(v[3],v[4],v[5])
                node.transform.scale = Vec3(v[6].coerceAtLeast(0.01f),v[7].coerceAtLeast(0.01f),v[8].coerceAtLeast(0.01f))
                pushSceneToRenderer(); openEditor("Transform")
            }.show()
    }

    private fun materialsPanel() {
        val node = selectedNode() ?: run { section("Materiales", "Selecciona un objeto"); return }
        section("Materiales y texturas", node.name)
        panel.addView(text("Color base", 11f, MUTED))
        val colors = listOf(0xffdbe5ef.toInt(),0xff55c7ed.toInt(),0xffffa45b.toInt(),0xff9b82ef.toInt(),
            0xff62d6a1.toInt(),0xffe76f8a.toInt(),0xfff0d36a.toInt(),0xff596a7c.toInt())
        val swatches = LinearLayout(this)
        colors.forEach { color ->
            val b = btn("●", {
                node.material.baseColor = Vec3(Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f)
                pushSceneToRenderer()
            })
            b.setTextColor(color); swatches.addView(b, LinearLayout.LayoutParams(0,dp(40),1f))
        }
        panel.addView(swatches)
        panel.addView(text("Rugosidad  %.2f".format(node.material.roughness), 11f, MUTED))
        panel.addView(SeekBar(this).apply {
            max=100; progress=(node.material.roughness*100).toInt()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { node.material.roughness=v/100f }
                override fun onStartTrackingTouch(s:SeekBar?) {}
                override fun onStopTrackingTouch(s:SeekBar?) { pushSceneToRenderer() }
            })
        })
        panel.addView(text("Metallicidad  %.2f".format(node.material.metallic), 11f, MUTED))
        panel.addView(SeekBar(this).apply {
            max=100; progress=(node.material.metallic*100).toInt()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { node.material.metallic=v/100f }
                override fun onStartTrackingTouch(s:SeekBar?) {}
                override fun onStopTrackingTouch(s:SeekBar?) { pushSceneToRenderer() }
            })
        })
        panel.addView(btn("+ Asignar textura PNG/JPG", { openTexturePicker() }))
        panel.addView(text(project.textureUris[node.id]?.let { "Textura vinculada: " + it } ?: "Slot de textura listo; muestreo UV pendiente.", 10f, MUTED))
    }

    private fun animationPanel() {
        val node = selectedNode()
        val clip = activeClip()
        section("Timeline de animación", "Clips, reproducción y keyframes de transformación")
        val controls = LinearLayout(this)
        controls.addView(btn(if (playing) "Ⅱ Pausa" else "▶ Play", { togglePlayback() }, true))
        controls.addView(btn("+ Keyframe", { addKeyframe() }))
        controls.addView(btn("Nuevo clip", {
            project.scene.animations.add(AnimationClip("Animation " + (project.scene.animations.size + 1)))
            openEditor("Animation")
        }))
        panel.addView(controls)
        timelineLabel = text("Tiempo %.2f s / %.1f s".format(currentTime,clip.duration), 11f, ACCENT, true)
        panel.addView(timelineLabel)
        timelineSeek = SeekBar(this).apply {
            max=1000; progress=((currentTime/clip.duration.coerceAtLeast(0.01f))*1000).toInt()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean) {
                    if (user) {
                        currentTime=clip.duration*v/1000f
                        TimelineEvaluator.apply(clip,currentTime,project.scene)
                        updateTimelineUi(); pushSceneToRenderer()
                    }
                }
                override fun onStartTrackingTouch(s:SeekBar?) {}
                override fun onStopTrackingTouch(s:SeekBar?) {}
            })
        }
        panel.addView(timelineSeek)
        panel.addView(text("Clip: " + clip.name + "  ·  Pistas: " + clip.tracks.size +
            "  ·  Keyframes: " + clip.tracks.sumOf { it.keys.size },10f,MUTED))
        if (node != null) panel.addView(text("Seleccionado: " + node.name,10f,MUTED))
        panel.addView(text("Interpolación lineal activa. Curvas Bézier y edición por canales quedan para la próxima etapa.",10f,MUTED))
    }

    private fun physicsPanel() {
        val node = selectedNode() ?: run { section("Físicas", "Selecciona un objeto"); return }
        section("Físicas", "Gravedad, masa y respuesta básica al suelo")
        val body = node.physics
        panel.addView(btn(if (body == null) "Activar cuerpo físico" else "Desactivar cuerpo físico", {
            node.physics = if (node.physics == null) PhysicsBody() else null; openEditor("Physics")
        }, true))
        if (body != null) {
            panel.addView(text("Masa: %.2f kg".format(body.mass),11f,MUTED))
            panel.addView(SeekBar(this).apply {
                max=100; progress=(body.mass.coerceIn(0.1f,10f)*10).toInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { body.mass=max(0.1f,v/10f) }
                    override fun onStartTrackingTouch(s:SeekBar?) {}
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(btn(if (body.useGravity) "Gravedad: Activada" else "Gravedad: Desactivada", { body.useGravity=!body.useGravity; openEditor("Physics") }))
            panel.addView(btn(if (body.isStatic) "Cuerpo estático" else "Cuerpo dinámico", { body.isStatic=!body.isStatic; openEditor("Physics") }))
            panel.addView(btn(if (simulating) "Ⅱ Detener simulación" else "▶ Simular escena", { togglePhysics() }, true))
            panel.addView(btn("Reiniciar posición", { node.transform.position=Vec3(0f,2f,0f); body.velocity=Vec3(); pushSceneToRenderer() }))
        }
        panel.addView(text("Física inicial: gravedad, masa, velocidad y rebote contra suelo plano. No es aún un solver completo.",10f,MUTED))
    }

    private fun rigPanel() {
        section("Rigging y esqueletos", "Jerarquía básica de huesos y armatures")
        val actions=LinearLayout(this)
        actions.addView(btn("+ Crear armature", { project.rigs.add(EditorRig("Armature " + (project.rigs.size+1))); addBone(); openEditor("Rig") }, true))
        actions.addView(btn("+ Añadir hueso", { addBone(); openEditor("Rig") }))
        panel.addView(actions)
        project.rigs.forEach { rig ->
            panel.addView(text(rig.name + "  ·  " + rig.bones.size + " huesos",13f,ACCENT,true))
            rig.bones.forEachIndexed { index,bone ->
                panel.addView(text("  " + (index+1) + ". " + bone.name + "  ·  padre " + bone.parentIndex,11f,TEXT))
            }
            panel.addView(btn("Añadir hueso hijo", { rig.bones.add(RigBone("Bone " + (rig.bones.size+1),rig.bones.lastIndex)); openEditor("Rig") }))
        }
        panel.addView(btn("Vincular malla (estructura)", {
            toast("Los armatures y huesos se guardan en .gme. Skinning y pesos aún no deforman la malla.")
        }))
        panel.addView(text("Incluye jerarquía y coordenadas head/tail. Skinning, weight painting, IK y retargeting pendientes.",10f,MUTED))
    }

    private fun cameraPanel() {
        section("Cámaras e iluminación", "Cámaras de escena y luces configurables")
        project.scene.cameras.forEachIndexed { index,camera ->
            panel.addView(text(camera.name + "  ·  FOV " + camera.fieldOfView.toInt() + "°",12f,TEXT))
            panel.addView(btn("Enfocar cámara " + (index+1), { renderer.setCameraTarget(camera.transform.position); toast("Vista enfocada") }))
        }
        panel.addView(btn("+ Añadir cámara", { project.scene.cameras.add(CameraData("Camera " + (project.scene.cameras.size+1))); openEditor("Camera") }, true))
        project.lights.forEach { light -> panel.addView(text(light.kind + ": " + light.name + " · " + light.intensity,11f,ACCENT)) }
        panel.addView(btn("+ Luz puntual", { project.lights.add(EditorLight()); openEditor("Camera") }))
        panel.addView(btn("+ Luz direccional", { project.lights.add(EditorLight(kind="DIRECTIONAL",name="Sun")); openEditor("Camera") }))
        panel.addView(text("Cámaras y luces se guardan en .gme. Iluminación física aún no está conectada al shader del visor.",10f,MUTED))
    }


    private fun effectsPanel() {
        section("Render y efectos", "Opciones persistentes del proyecto")
        val effects = project.effects
        fun toggle(title: String, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            panel.addView(btn(title + if (getter()) "  ·  ON" else "  ·  OFF", {
                setter(!getter()); openEditor("Effects")
            }))
        }
        toggle("Bloom", { effects.bloom }) { effects.bloom = it }
        toggle("Viñeta", { effects.vignette }) { effects.vignette = it }
        toggle("Niebla", { effects.fog }) { effects.fog = it }
        toggle("Wireframe", { effects.wireframe }) { effects.wireframe = it }
        toggle("Contorno de selección", { effects.outlines }) { effects.outlines = it }
        toggle("Sombras", { effects.shadows }) { effects.shadows = it }
        toggle("Oclusión ambiental", { effects.ambientOcclusion }) { effects.ambientOcclusion = it }
        panel.addView(text("Calidad de render: " + effects.quality, 11f, MUTED))
        val quality = LinearLayout(this)
        listOf("LOW", "MEDIUM", "HIGH").forEach { q ->
            quality.addView(btn(q, { effects.quality = q; openEditor("Effects") }, effects.quality == q),
                LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        panel.addView(quality)
        panel.addView(text("Los ajustes se guardan en .gme. Los toggles son configuración de proyecto; varios efectos todavía no tienen postprocesado conectado al render.", 10f, MUTED))
    }

    private fun layers2DPanel() {
        if (project.layers2D.isEmpty()) project.layers2D.add(Layer2D("Layer 1"))
        active2DLayerIndex = active2DLayerIndex.coerceIn(0, project.layers2D.lastIndex)
        val layer = project.layers2D[active2DLayerIndex]
        section("Lienzo 2D", "Dibujo libre por capas · se guarda dentro del proyecto GME")
        val layerRow = LinearLayout(this)
        project.layers2D.forEachIndexed { index, item ->
            layerRow.addView(btn(if (index == active2DLayerIndex) "● " + item.name else item.name, {
                active2DLayerIndex = index; openEditor("2D")
            }, index == active2DLayerIndex))
        }
        panel.addView(layerRow)
        val sketch = SketchCanvasView(this, layer).apply { setBackgroundColor(0xff0c131b.toInt()) }
        sketchCanvas = sketch
        panel.addView(sketch, LinearLayout.LayoutParams(-1, dp(116)))
        val colors = LinearLayout(this)
        listOf(0xfff1f5fa.toInt(), 0xff55c7ed.toInt(), 0xffffa45b.toInt(), 0xff62d6a1.toInt(), 0xffe76f8a.toInt()).forEach { color ->
            val swatch = btn("●", { sketch.setPenColor(color) })
            swatch.setTextColor(color)
            colors.addView(swatch, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        panel.addView(colors)
        val actions = LinearLayout(this)
        actions.addView(btn("+ Capa", {
            project.layers2D.add(Layer2D("Layer " + (project.layers2D.size + 1)))
            active2DLayerIndex = project.layers2D.lastIndex; openEditor("2D")
        }, true))
        actions.addView(btn("Deshacer", { sketch.undo() }))
        actions.addView(btn("Limpiar", { sketch.clearLayer() }))
        panel.addView(actions)
        panel.addView(btn("Exportar lienzo PNG", { createDocument("image/png", project.name + "_2D.png", 105) }))
        panel.addView(text("Dibujo rasterizado básico sobre capas GME. Herramientas vectoriales, texto editable, imagen importada y composición con la escena 3D están pendientes.", 10f, MUTED))
    }

    private fun assetsPanel() {
        section("Biblioteca del proyecto", "Modelos, texturas y recursos usados")
        panel.addView(btn("+ Importar recurso", { openDocument() }, true))
        project.scene.nodes.forEach { node ->
            panel.addView(text("▣  " + node.name + "  ·  " + (project.primitiveKinds[node.id] ?: "MESH"), 11f, TEXT))
        }
        project.textureUris.forEach { (nodeId, uri) ->
            val name = project.scene.nodes.firstOrNull { it.id == nodeId }?.name ?: nodeId
            panel.addView(text("▧  " + name + "  ·  textura vinculada", 11f, MUTED))
        }
        panel.addView(text("Recursos externos se referencian por URI; el empaquetado automático de texturas dentro del .gme está pendiente.", 10f, MUTED))
    }

    private fun shadersPanel() {
        section("Gema Shader Lab", "Editor GLSL ES 1.00 · fragment shaders")
        val editor=EditText(this).apply {
            setText(project.shaderSource); textSize=11f; typeface=android.graphics.Typeface.MONOSPACE
            setTextColor(0xffd7e7f7.toInt()); setBackgroundColor(0xff0c131b.toInt())
            minLines=5; gravity=Gravity.TOP or Gravity.START; setPadding(dp(10),dp(8),dp(10),dp(8))
        }
        panel.addView(editor,LinearLayout.LayoutParams(-1,dp(112)))
        val actions=LinearLayout(this)
        actions.addView(btn("Validar y aplicar", {
            project.shaderSource=editor.text.toString()
            glView.queueEvent {
                val error=renderer.applyFragmentShader(project.shaderSource)
                runOnUiThread {
                    if (error==null) toast("Shader compilado y aplicado.")
                    else toast("Error GLSL: " + error)
                }
            }
        },true))
        actions.addView(btn("Ejemplo Toon", { editor.setText(ShaderLibrary.EXAMPLE_TOON); project.shaderSource=ShaderLibrary.EXAMPLE_TOON }))
        actions.addView(btn("Restaurar", {
            editor.setText(ShaderLibrary.DEFAULT_FRAGMENT); project.shaderSource=ShaderLibrary.DEFAULT_FRAGMENT
            glView.queueEvent { renderer.applyFragmentShader(project.shaderSource) }
        }))
        panel.addView(actions)
        panel.addView(text("Usa uniform vec4 uColor. Compila en el dispositivo. Shader nodes y lenguaje GSL propio están en desarrollo.",10f,MUTED))
    }

    private fun exportPanel() {
        section("Importar y exportar", "Formatos disponibles desde el selector del dispositivo")
        panel.addView(btn("+ Importar modelo / proyecto", { openDocument() }, true))
        panel.addView(btn("Guardar proyecto .gme", { saveGme() }))
        panel.addView(btn("Exportar malla OBJ", { exportDocument("obj") }))
        panel.addView(btn("Exportar escena glTF", { exportDocument("gltf") }))
        panel.addView(btn("Exportar escena GLB", { exportDocument("glb") }))
        panel.addView(btn("Capturar imagen PNG", { createImageDocument() }))
        panel.addView(btn("Exportar vídeo MP4", {
            AlertDialog.Builder(this).setTitle("Exportación de vídeo")
                .setMessage("El apartado está disponible en el menú, pero el codificador MP4 y la exportación por secuencia de frames aún no están implementados.")
                .setPositiveButton("Entendido",null).show()
        }))
        panel.addView(text("Formatos de malla: .obj, .stl, .ply ASCII, FBX ASCII y glTF 2.0/.glb básico. Proyecto nativo: .gme. FBX binario, Blender y Prisma todavía no.",10f,MUTED))
    }

    private fun addObject(name: String, kind: String, position: Vec3, select: Boolean) {
        val id=UUID.randomUUID().toString()
        project.scene.nodes.add(SceneNode(id,name,Transform(position=position),mesh=MeshFactory.create(kind)))
        project.primitiveKinds[id]=kind
        if (select) selectedId=id
        pushSceneToRenderer()
    }
    private fun selectedNode()=project.scene.nodes.firstOrNull { it.id==selectedId }
    private fun duplicateSelected() { selectedNode()?.let { duplicateNode(it) } }
    private fun duplicateNode(node: SceneNode) {
        val id=UUID.randomUUID().toString()
        val oldMesh=node.mesh
        val copy=node.copy(
            id=id,name=node.name+" Copy",
            transform=Transform(node.transform.position.copy(x=node.transform.position.x+0.8f),
                node.transform.rotationEuler.copy(),node.transform.scale.copy()),
            mesh=oldMesh?.copy(positions=oldMesh.positions.map { it.copy() }.toMutableList(),triangleIndices=oldMesh.triangleIndices.toMutableList()),
            material=node.material.copy(baseColor=node.material.baseColor.copy()),
            physics=node.physics?.copy(velocity=node.physics!!.velocity.copy())
        )
        project.scene.nodes.add(copy)
        project.primitiveKinds[id]=project.primitiveKinds[node.id] ?: "MESH"
        selectedId=id
    }
    private fun deleteSelected() {
        val id=selectedId ?: return
        project.scene.nodes.removeAll { it.id==id }; project.primitiveKinds.remove(id); project.textureUris.remove(id)
        selectedId=project.scene.nodes.firstOrNull()?.id; pushSceneToRenderer(); openEditor("Scene")
    }
    private fun addBone() {
        if (project.rigs.isEmpty()) project.rigs.add(EditorRig())
        val rig=project.rigs.last()
        val parent = rig.bones.lastOrNull()
        val head = parent?.tail?.copy() ?: Vec3(0f, 0f, 0f)
        val tail = head.copy(y = head.y + 0.65f)
        rig.bones.add(RigBone("Bone " + (rig.bones.size+1),if (rig.bones.isEmpty()) -1 else rig.bones.lastIndex,head,tail))
        val id = UUID.randomUUID().toString()
        val center = Vec3((head.x+tail.x)/2f,(head.y+tail.y)/2f,(head.z+tail.z)/2f)
        val boneNode = SceneNode(id,rig.name + " / " + rig.bones.last().name,
            Transform(position=center,scale=Vec3(0.09f,0.32f,0.09f)),
            mesh=MeshFactory.create("CUBE"),
            material=MaterialData("Bone",Vec3(0.98f,0.55f,0.22f)))
        project.scene.nodes.add(boneNode)
        project.primitiveKinds[id]="BONE"
        selectedId=id
    }
    private fun activeClip(): AnimationClip {
        if (project.scene.animations.isEmpty()) project.scene.animations.add(AnimationClip())
        return project.scene.animations.first()
    }
    private fun addKeyframe() {
        val node=selectedNode() ?: run { toast("Selecciona un objeto primero"); return }
        val clip=activeClip()
        var track=clip.tracks.firstOrNull { it.nodeId==node.id }
        if (track==null) { track=AnimationTrack(node.id); clip.tracks.add(track) }
        track.keys.removeAll { kotlin.math.abs(it.time-currentTime)<0.001f }
        track.keys.add(TransformKeyframe(currentTime,node.transform.position.copy(),node.transform.rotationEuler.copy(),node.transform.scale.copy()))
        track.keys.sortBy { it.time }; updateTimelineUi(); toast("Keyframe añadido")
    }
    private fun updateTimelineUi() {
        if (::timelineLabel.isInitialized) timelineLabel.text="Tiempo %.2f s / %.1f s".format(currentTime,activeClip().duration)
        if (::timelineSeek.isInitialized) timelineSeek.progress=((currentTime/activeClip().duration.coerceAtLeast(0.01f))*1000).toInt().coerceIn(0,1000)
    }
    private fun togglePlayback() { playing=!playing; updateLoop(); if(activeTab=="Animation") openEditor("Animation") }
    private fun togglePhysics() { simulating=!simulating; updateLoop(); if(activeTab=="Physics") openEditor("Physics") }
    private fun updateLoop() {
        if (playing || simulating) { lastFrame=0L; handler.removeCallbacks(frameLoop); handler.post(frameLoop) }
        else handler.removeCallbacks(frameLoop)
    }
    private fun stopTasks() { playing=false; simulating=false; handler.removeCallbacks(frameLoop) }

    private fun pushSceneToRenderer() {
        if (!::renderer.isInitialized) return
        renderer.items=project.scene.nodes.map { node ->
            val mesh=node.mesh ?: MeshFactory.create(project.primitiveKinds[node.id] ?: "CUBE")
            val indices: List<Int> = if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
            val vertices=FloatArray(indices.size*3)
            indices.forEachIndexed { i,index ->
                val p=mesh.positions.getOrNull(index) ?: Vec3()
                vertices[i*3]=p.x; vertices[i*3+1]=p.y; vertices[i*3+2]=p.z
            }
            RenderItem(node.id,node.name,vertices,node.transform.position.copy(),node.transform.rotationEuler.copy(),
                node.transform.scale.copy(),node.material.baseColor.copy(),node.id==selectedId)
        }
        if (::viewportInfo.isInitialized) viewportInfo.text=project.scene.nodes.size.toString()+" objetos  ·  "+project.rigs.sumOf { it.bones.size }+" huesos"
    }

    private fun openDocument() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("model/gltf+json","model/gltf-binary","application/json","text/plain","application/octet-stream"))
        }
        startActivityForResult(intent,100)
    }
    private fun openTexturePicker() {
        textureTarget=selectedId
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="image/*" }
        startActivityForResult(intent,104)
    }
    private fun saveGme() { saveKind="gme"; createDocument("application/json",project.name.replace(" ","_")+".gme",101) }
    private fun exportDocument(kind:String) {
        val node=selectedNode() ?: run { toast("Selecciona una malla para exportar"); return }
        saveKind=kind
        val mime=when(kind) { "obj", "stl" -> "text/plain"; "gltf" -> "model/gltf+json"; else -> "model/gltf-binary" }
        createDocument(mime,node.name.replace(" ","_")+"."+kind,102)
    }
    private fun createImageDocument() { saveKind="png"; createDocument("image/png",project.name.replace(" ","_")+".png",103) }
    private fun createDocument(mime:String,name:String,request:Int) {
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type=mime; putExtra(Intent.EXTRA_TITLE,name)
        }
        startActivityForResult(intent,request)
    }

    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null) return
        val uri=data.data!!
        when(requestCode) {
            100 -> importFromUri(uri)
            101 -> writeText(uri,ProjectCodec.encode(project),"Proyecto Gema guardado")
            102 -> exportSelected(uri)
            103 -> captureViewport(uri)
            104 -> {
                textureTarget?.let { project.textureUris[it]=uri.toString() }
                toast("Textura vinculada; muestreo UV pendiente."); openEditor("Materials")
            }
            105 -> save2DCanvas(uri)
        }
    }
    private fun importFromUri(uri:Uri) {
        try {
            val name=displayName(uri)
            val bytes=contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el archivo.")
            when(name.substringAfterLast('.',"asset").lowercase()) {
                "gme" -> {
                    project=ProjectCodec.decode(String(bytes,Charsets.UTF_8))
                    selectedId=project.scene.nodes.firstOrNull()?.id
                    toast("Proyecto GME cargado"); openEditor("Scene")
                }
                else -> {
                    val mesh=AssetPipeline.importMesh(name,bytes)
                    val id=UUID.randomUUID().toString()
                    project.scene.nodes.add(SceneNode(id,name.substringBeforeLast('.'),Transform(position=Vec3(0f,1f,0f)),mesh=mesh))
                    project.primitiveKinds[id]="IMPORTED"; selectedId=id
                    toast("Malla importada: "+name); openEditor("Scene")
                }
            }
        } catch(e:Exception) {
            AlertDialog.Builder(this).setTitle("No se pudo importar").setMessage(e.message ?: "Formato no compatible.")
                .setPositiveButton("Entendido",null).show()
        }
    }
    private fun exportSelected(uri:Uri) {
        try {
            val node=selectedNode() ?: error("No hay malla seleccionada.")
            val mesh=project.meshForExport(node)
            val bytes=when(saveKind) {
                "obj" -> ObjCodec.encode(mesh).toByteArray(Charsets.UTF_8)
                "gltf" -> GltfCodec.encode(mesh,false)
                "stl" -> StlCodec.encode(mesh).toByteArray(Charsets.UTF_8)
                else -> GltfCodec.encode(mesh,true)
            }
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No se pudo escribir el archivo.")
            toast("Exportación ."+saveKind+" terminada")
        } catch(e:Exception) { toast("Error exportando: "+e.message) }
    }
    private fun writeText(uri:Uri,value:String,message:String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(value.toByteArray(Charsets.UTF_8)) } ?: error("No se pudo guardar.")
            toast(message)
        } catch(e:Exception) { toast("Error guardando: "+e.message) }
    }
    private fun captureViewport(uri:Uri) {
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O) { toast("La captura necesita Android 8 o posterior."); return }
        val bitmap=Bitmap.createBitmap(max(1,glView.width),max(1,glView.height),Bitmap.Config.ARGB_8888)
        PixelCopy.request(glView,bitmap,{ result ->
            if(result==PixelCopy.SUCCESS) {
                try { contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; toast("Captura PNG guardada") }
                catch(e:Exception) { toast("Error de captura: "+e.message) }
            } else toast("No se pudo capturar el visor (código "+result+")")
            bitmap.recycle()
        },handler)
    }
    private fun save2DCanvas(uri: Uri) {
        val view = sketchCanvas ?: run { toast("Abre primero el lienzo 2D."); return }
        val bitmap = Bitmap.createBitmap(max(1, view.width), max(1, view.height), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        try {
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Lienzo 2D exportado como PNG")
        } catch (error: Exception) {
            toast("Error exportando lienzo: " + error.message)
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayName(uri:Uri):String {
        var name=uri.lastPathSegment ?: "asset"
        contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) name=it.getString(0) ?: name
        }
        return name
    }
    override fun onPause() { super.onPause(); if(::glView.isInitialized) glView.onPause(); stopTasks() }
    override fun onResume() { super.onResume(); if(::glView.isInitialized) glView.onResume() }
}

private class GemaRenderer : GLSurfaceView.Renderer {
    @Volatile var items:List<RenderItem> = emptyList()
    private var program=0
    private val projection=FloatArray(16)
    private val view=FloatArray(16)
    private val model=FloatArray(16)
    private val viewModel=FloatArray(16)
    private val mvp=FloatArray(16)
    @Volatile private var yaw=38f
    @Volatile private var pitch=22f
    @Volatile private var distance=8f
    @Volatile private var target=Vec3()
    private var grid:FloatBuffer?=null
    private data class Cache(val hash:Int,val buffer:FloatBuffer,val count:Int)
    private val meshes=HashMap<String,Cache>()
    private var shaderError="Shader inválido"

    fun orbit(dx:Float,dy:Float) { yaw+=dx*0.45f; pitch=(pitch+dy*0.35f).coerceIn(-80f,80f) }
    fun zoom(factor:Float) { distance=(distance/factor.coerceIn(0.5f,2f)).coerceIn(2.2f,25f) }
    fun setCameraTarget(v:Vec3) { target=v.copy() }

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        GLES20.glClearColor(0.047f,0.064f,0.082f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        program=createProgram(ShaderLibrary.DEFAULT_FRAGMENT)
        grid=makeBuffer(gridVertices())
    }
    override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int) {
        GLES20.glViewport(0,0,width,height.coerceAtLeast(1))
        Matrix.perspectiveM(projection,0,45f,width.toFloat()/height.coerceAtLeast(1),0.1f,150f)
    }
    override fun onDrawFrame(gl:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if(program==0) return
        val y=Math.toRadians(yaw.toDouble()); val p=Math.toRadians(pitch.toDouble())
        val t=target
        val cx=t.x+distance*kotlin.math.cos(p).toFloat()*kotlin.math.sin(y).toFloat()
        val cy=t.y+distance*kotlin.math.sin(p).toFloat()
        val cz=t.z+distance*kotlin.math.cos(p).toFloat()*kotlin.math.cos(y).toFloat()
        Matrix.setLookAtM(view,0,cx,cy,cz,t.x,t.y,t.z,0f,1f,0f)
        GLES20.glUseProgram(program)
        val position=GLES20.glGetAttribLocation(program,"aPosition")
        val matrix=GLES20.glGetUniformLocation(program,"uMvp")
        val color=GLES20.glGetUniformLocation(program,"uColor")
        Matrix.setIdentityM(model,0)
        Matrix.multiplyMM(viewModel,0,view,0,model,0)
        Matrix.multiplyMM(mvp,0,projection,0,viewModel,0)
        GLES20.glUniformMatrix4fv(matrix,1,false,mvp,0)
        GLES20.glUniform4f(color,0.17f,0.23f,0.29f,1f)
        grid?.let {
            it.position(0); GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,it)
            GLES20.glDrawArrays(GLES20.GL_LINES,0,it.capacity()/3)
            GLES20.glDisableVertexAttribArray(position)
        }
        items.forEach { item ->
            if(item.vertices.isEmpty()) return@forEach
            val hash=item.vertices.contentHashCode()
            var cache=meshes[item.id]
            if(cache==null || cache.hash!=hash) {
                cache=Cache(hash,makeBuffer(item.vertices),item.vertices.size/3)
                meshes[item.id]=cache
            }
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,item.position.x,item.position.y,item.position.z)
            Matrix.rotateM(model,0,item.rotation.x,1f,0f,0f)
            Matrix.rotateM(model,0,item.rotation.y,0f,1f,0f)
            Matrix.rotateM(model,0,item.rotation.z,0f,0f,1f)
            Matrix.scaleM(model,0,item.scale.x,item.scale.y,item.scale.z)
            Matrix.multiplyMM(viewModel,0,view,0,model,0)
            Matrix.multiplyMM(mvp,0,projection,0,viewModel,0)
            GLES20.glUniformMatrix4fv(matrix,1,false,mvp,0)
            val tint=if(item.selected) 1.16f else 1f
            GLES20.glUniform4f(color,(item.color.x*tint).coerceIn(0f,1f),
                (item.color.y*tint).coerceIn(0f,1f),(item.color.z*tint).coerceIn(0f,1f),1f)
            cache!!.buffer.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,cache.buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,cache.count)
            GLES20.glDisableVertexAttribArray(position)
        }
    }
    fun applyFragmentShader(source:String):String? {
        val next=createProgram(source)
        if(next==0) return shaderError
        val old=program; program=next
        if(old!=0) GLES20.glDeleteProgram(old)
        return null
    }
    private fun createProgram(fragment:String):Int {
        val vertex="""
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }
        """.trimIndent()
        val vs=compile(GLES20.GL_VERTEX_SHADER,vertex)
        val fs=compile(GLES20.GL_FRAGMENT_SHADER,fragment)
        if(vs==0 || fs==0) return 0
        val candidate=GLES20.glCreateProgram()
        GLES20.glAttachShader(candidate,vs); GLES20.glAttachShader(candidate,fs); GLES20.glLinkProgram(candidate)
        val status=IntArray(1); GLES20.glGetProgramiv(candidate,GLES20.GL_LINK_STATUS,status,0)
        val log=GLES20.glGetProgramInfoLog(candidate)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        if(status[0]==0) { shaderError=log.ifBlank { "No se pudo enlazar el programa." }; GLES20.glDeleteProgram(candidate); return 0 }
        return candidate
    }
    private fun compile(type:Int,source:String):Int {
        val shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
        val status=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
        if(status[0]==0) { shaderError=GLES20.glGetShaderInfoLog(shader).ifBlank { "Error GLSL" }; GLES20.glDeleteShader(shader); return 0 }
        return shader
    }
    private fun makeBuffer(values:FloatArray):FloatBuffer =
        ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private fun gridVertices():FloatArray {
        val out=ArrayList<Float>()
        for(i in -10..10) {
            out.add(i.toFloat());out.add(0f);out.add(-10f);out.add(i.toFloat());out.add(0f);out.add(10f)
            out.add(-10f);out.add(0f);out.add(i.toFloat());out.add(10f);out.add(0f);out.add(i.toFloat())
        }
        return out.toFloatArray()
    }
}
