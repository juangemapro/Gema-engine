package com.gema.engine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
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
import android.opengl.GLUtils
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
    fun undo() { if (layer.strokes.isNotEmpty()) { onStrokeStart(); layer.strokes.removeAt(layer.strokes.lastIndex) }; invalidate() }
    fun clearLayer() { if (layer.strokes.isNotEmpty()) onStrokeStart(); layer.strokes.clear(); invalidate() }

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
                onStrokeStart()
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

private data class GizmoAxis(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

private data class RenderItem(
    val id: String, val name: String, val vertices: FloatArray,
    val sourceVertexIndices: IntArray,
    val wireVertices: FloatArray,
    val normals: FloatArray,
    val tangents: FloatArray,
    val texCoords: FloatArray,
    val textureUri: String?,
    val normalTextureUri: String?,
    val normalScale: Float,
    val metallicRoughnessTextureUri: String?,
    val occlusionTextureUri: String?,
    val emissiveTextureUri: String?,
    val position: Vec3, val rotation: Vec3, val scale: Vec3,
    val color: Vec3,
    val emissive: Vec3,
    val roughness: Float,
    val metallic: Float,
    val alpha: Float,
    val alphaMode: String,
    val alphaCutoff: Float,
    val selected: Boolean,
    val wireframe: Boolean, val outlines: Boolean
)

private class OrbitGLView(
    activity: Activity,
    private val onOrbit: (Float, Float) -> Unit,
    private val onZoom: (Float) -> Unit,
    private val onPan: (Float, Float) -> Unit,
    private val onTap: (Float, Float, Float, Float) -> Unit
) : GLSurfaceView(activity) {
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastDistance = 0f
    private var lastCenterX = 0f
    private var lastCenterY = 0f
    private var moved = false
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; downX = event.x; downY = event.y; moved = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastDistance = spacing(event)
                    lastCenterX = (event.getX(0)+event.getX(1))*0.5f
                    lastCenterY = (event.getY(0)+event.getY(1))*0.5f
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val d = spacing(event)
                    val centerX = (event.getX(0)+event.getX(1))*0.5f
                    val centerY = (event.getY(0)+event.getY(1))*0.5f
                    if (lastDistance > 0f && d > 0f) onZoom(d / lastDistance)
                    if (lastCenterX != 0f || lastCenterY != 0f) onPan(centerX-lastCenterX,centerY-lastCenterY)
                    lastDistance = d
                    lastCenterX = centerX
                    lastCenterY = centerY
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) > 12f) moved = true
                    if (moved) onOrbit(dx, dy)
                    lastX = event.x; lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap(event.x, event.y, width.toFloat(), height.toFloat())
                lastDistance = 0f; performClick(); return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> { lastDistance = 0f; moved = true; return true }
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

/** Touch-friendly transform handles drawn over the 3D viewport. */
private class TransformGizmoView(
    context: android.content.Context,
    private val modeProvider: () -> String,
    private val enabledProvider: () -> Boolean,
    private val pivotProvider: () -> Pair<Float,Float>?,
    private val axesProvider: () -> List<GizmoAxis>?,
    private val onStart: () -> Unit,
    private val onDrag: (Int, Float) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var activeAxis = -1
    private var previousX = 0f
    private var previousY = 0f
    private var previousAngle = 0f
    private val length get() = 76f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabledProvider()) return
        val pivot = pivotProvider()
        val cx = pivot?.first ?: width * 0.5f
        val cy = pivot?.second ?: height * 0.52f
        val mode = modeProvider()
        paint.strokeWidth = 3.5f * density
        paint.color = 0xfff15b65.toInt()
        if (mode == "ROTATE") {
            listOf(42f, 58f, 74f).forEachIndexed { index, radius ->
                paint.color = axisColor(index)
                canvas.drawCircle(cx, cy, radius * density, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xffffffff.toInt()
            canvas.drawCircle(cx, cy, 5f * density, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        val axes = axesProvider()
        val endpoints = axes?.takeIf { it.size == 3 } ?: listOf(
            GizmoAxis(cx,cy,cx+length,cy),
            GizmoAxis(cx,cy,cx,cy-length),
            GizmoAxis(cx,cy,cx-length*0.72f,cy+length*0.72f)
        )
        endpoints.forEachIndexed { index, axis ->
            paint.color = axisColor(index)
            canvas.drawLine(axis.startX, axis.startY, axis.endX, axis.endY, paint)
            if (mode == "SCALE") {
                paint.style = Paint.Style.FILL
                val size = 9f * density
                canvas.drawRect(axis.endX-size,axis.endY-size,axis.endX+size,axis.endY+size,paint)
                paint.style = Paint.Style.STROKE
            } else {
                val angle = kotlin.math.atan2(axis.endY-axis.startY,axis.endX-axis.startX)
                val wing = 13f*density
                val back = 15f*density
                val cosine = kotlin.math.cos(angle).toFloat()
                val sine = kotlin.math.sin(angle).toFloat()
                val leftX = axis.endX-cosine*back+kotlin.math.cos(angle+Math.PI/2).toFloat()*wing
                val leftY = axis.endY-sine*back+kotlin.math.sin(angle+Math.PI/2).toFloat()*wing
                val rightX = axis.endX-cosine*back+kotlin.math.cos(angle-Math.PI/2).toFloat()*wing
                val rightY = axis.endY-sine*back+kotlin.math.sin(angle-Math.PI/2).toFloat()*wing
                canvas.drawLine(axis.endX,axis.endY,leftX,leftY,paint)
                canvas.drawLine(axis.endX,axis.endY,rightX,rightY,paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xffffffff.toInt()
        canvas.drawCircle(cx, cy, 5f * density, paint)
        paint.style = Paint.Style.STROKE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledProvider()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeAxis = hitTest(event.x, event.y)
                if (activeAxis < 0) return false
                onStart()
                previousX = event.x
                previousY = event.y
                val initialPivot = pivotProvider() ?: Pair(width*0.5f,height*0.52f)
                previousAngle = kotlin.math.atan2(event.y-initialPivot.second,event.x-initialPivot.first)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeAxis < 0) return false
                val dx = event.x - previousX
                val dy = event.y - previousY
                val delta = if (modeProvider() == "ROTATE") {
                    val pivot = pivotProvider() ?: Pair(width*0.5f,height*0.52f)
                    val angle = kotlin.math.atan2(event.y-pivot.second,event.x-pivot.first)
                    var difference = angle-previousAngle
                    while (difference > Math.PI.toFloat()) difference -= (2*Math.PI).toFloat()
                    while (difference < -Math.PI.toFloat()) difference += (2*Math.PI).toFloat()
                    previousAngle = angle
                    difference / Math.PI.toFloat()
                } else {
                    val axes = axesProvider()?.getOrNull(activeAxis)
                    if (axes != null) {
                        val vx=axes.endX-axes.startX
                        val vy=axes.endY-axes.startY
                        val length2=(vx*vx+vy*vy).coerceAtLeast(1f)
                        (dx*vx+dy*vy)/kotlin.math.sqrt(length2)/(95f*density)
                    } else when (activeAxis) {
                        0 -> dx/(95f*density)
                        1 -> -dy/(95f*density)
                        else -> (dx-dy)/(135f*density)
                    }
                }
                onDrag(activeAxis,delta)
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeAxis = -1
                invalidate()
                performClick()
                return true
            }
        }
        return activeAxis >= 0
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        val pivot = pivotProvider()
        val cx = pivot?.first ?: width * 0.5f
        val cy = pivot?.second ?: height * 0.52f
        if (modeProvider() == "ROTATE") {
            val radius = kotlin.math.sqrt((x-cx)*(x-cx) + (y-cy)*(y-cy)) / density
            val rings = listOf(42f,58f,74f)
            val closest = rings.indices.minByOrNull { kotlin.math.abs(radius-rings[it]) } ?: return -1
            return if (kotlin.math.abs(radius-rings[closest]) <= 15f) closest else -1
        }
        val axes = axesProvider()?.takeIf { it.size == 3 } ?: listOf(
            GizmoAxis(cx,cy,cx+length,cy),
            GizmoAxis(cx,cy,cx,cy-length),
            GizmoAxis(cx,cy,cx-length*0.72f,cy+length*0.72f)
        )
        val distances = axes.map { axis ->
            val vx=axis.endX-axis.startX
            val vy=axis.endY-axis.startY
            val denom=(vx*vx+vy*vy).coerceAtLeast(1f)
            val t=(((x-axis.startX)*vx+(y-axis.startY)*vy)/denom).coerceIn(0f,1f)
            val px=axis.startX+t*vx
            val py=axis.startY+t*vy
            val dx=x-px; val dy=y-py
            dx*dx+dy*dy
        }
        val nearest = distances.indices.minByOrNull { distances[it] } ?: return -1
        return if (distances[nearest] <= (20f*density)*(20f*density)) nearest else -1
    }

    private fun axisColor(axis: Int): Int = when(axis) {
        0 -> 0xfffa626c.toInt()
        1 -> 0xff61d991.toInt()
        else -> 0xff5c9dff.toInt()
    }
}

class MainActivity : Activity() {
    private var project = EditorProject()
    private val autosaveFileName = "gema_autosave.gme"
    private var hasAutosave = false
    private var selectedId: String? = null
    private var activeTab = "Scene"
    private var transformMode = "MOVE"
    private var transformSpace = "LOCAL"
    private var snapEnabled = false
    private val snapRemainders = FloatArray(3)
    private val undoHistory = ArrayDeque<String>()
    private val redoHistory = ArrayDeque<String>()
    private var restoringHistory = false
    private var meshEditMode = false
    private var meshSelectionMode = "VERTEX"
    private var selectedBoneIndex = 0
    private var selectedVertexIndex = 0
    private var selectedFaceIndex = 0
    private var playing = false
    private var simulating = false
    private var currentTime = 0f
    private lateinit var root: LinearLayout
    private lateinit var renderer: GemaRenderer
    private lateinit var glView: OrbitGLView
    private lateinit var viewportInfo: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelWeightPreview: TextView
    private lateinit var timelineSeek: SeekBar
    private lateinit var timelineLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val physicsWorld = PhysicsWorld()
    private var lastFrame = 0L
    private var saveKind = ""
    private var textureTarget: String? = null
    private var textureSlotTarget: String = "BASE_COLOR"
    private var editorOpen = false
    private lateinit var editorTitle: TextView
    private lateinit var footerInfo: TextView
    private lateinit var tabStrip: LinearLayout
    private val tabButtons = linkedMapOf<String,Button>()
    private val modeButtons = linkedMapOf<String,Button>()
    private var assetsTreeUri: Uri? = null
    private var exportFolderUri: Uri? = null
    private val externalAssets = linkedMapOf<String, ByteArray>()
    private val importedNodeIds = linkedMapOf<Int, String>()
    private var gizmoView: TransformGizmoView? = null

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
        hasAutosave = restoreAutosave()
        if (!hasAutosave) {
            project.scene.cameras.add(CameraData())
            addObject("Cube", "CUBE", Vec3(0f, 1f, 0f), true)
        } else {
            if (project.scene.cameras.isEmpty()) project.scene.cameras.add(CameraData())
            selectedId = project.scene.nodes.firstOrNull()?.id
        }
        showHome()
    }

    private fun saveAutosave() {
        try {
            openFileOutput(autosaveFileName, MODE_PRIVATE).use { output ->
                output.write(ProjectCodec.encode(project).toByteArray(Charsets.UTF_8))
            }
            hasAutosave=true
        } catch (_: Exception) {
            // Quick save is best-effort; explicit .gme export remains available.
        }
    }

    private fun restoreAutosave(): Boolean {
        return try {
            openFileInput(autosaveFileName).use { input ->
                val json=input.readBytes().toString(Charsets.UTF_8)
                project=ProjectCodec.decode(json)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteAutosave() {
        deleteFile(autosaveFileName)
        hasAutosave=false
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

    private fun recordUndo() {
        if (restoringHistory) return
        try {
            undoHistory.addLast(ProjectCodec.encode(project))
            while (undoHistory.size > 12) undoHistory.removeFirst()
            redoHistory.clear()
        } catch (error: Exception) {
            toast("No se pudo crear el punto de restauración: " + error.message)
        }
    }

    private fun undoProject() {
        if (undoHistory.isEmpty()) { toast("No hay más acciones para deshacer."); return }
        try {
            redoHistory.addLast(ProjectCodec.encode(project))
            restoringHistory = true
            project = ProjectCodec.decode(undoHistory.removeLast())
            restoringHistory = false
            selectedId = selectedId?.takeIf { id -> project.scene.nodes.any { it.id == id } }
                ?: project.scene.nodes.firstOrNull()?.id
            pushSceneToRenderer()
            openEditor(activeTab)
            toast("Acción deshecha")
        } catch (error: Exception) {
            restoringHistory = false
            toast("No se pudo deshacer: " + error.message)
        }
    }

    private fun redoProject() {
        if (redoHistory.isEmpty()) { toast("No hay acciones para rehacer."); return }
        try {
            undoHistory.addLast(ProjectCodec.encode(project))
            restoringHistory = true
            project = ProjectCodec.decode(redoHistory.removeLast())
            restoringHistory = false
            selectedId = selectedId?.takeIf { id -> project.scene.nodes.any { it.id == id } }
                ?: project.scene.nodes.firstOrNull()?.id
            pushSceneToRenderer()
            openEditor(activeTab)
            toast("Acción rehecha")
        } catch (error: Exception) {
            restoringHistory = false
            toast("No se pudo rehacer: " + error.message)
        }
    }

    private fun showHome() {
        stopTasks()
        editorOpen = false
        if (::glView.isInitialized) glView.onPause()
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
        body.addView(btn(if (hasAutosave) "Continuar con el último proyecto guardado" else "Continuar en el editor", {
            openEditor("Scene")
        }, hasAutosave), LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        if (hasAutosave) {
            body.addView(btn("Eliminar autoguardado local", {
                AlertDialog.Builder(this).setTitle("Eliminar copia local")
                    .setMessage("Esto elimina la copia automática del dispositivo. Los archivos .gme exportados no se borran.")
                    .setNegativeButton("Cancelar",null)
                    .setPositiveButton("Eliminar") { _, _ -> deleteAutosave(); toast("Autoguardado eliminado"); showHome() }.show()
            }))
        }
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
        if (editorOpen && ::panel.isInitialized) {
            editorTitle.text = project.name
            updateTabButtons()
            updateModeButtons()
            buildPanel()
            pushSceneToRenderer()
            gizmoView?.invalidate()
            footerInfo.text = project.scene.nodes.size.toString() + " OBJ  ·  " +
                project.rigs.sumOf { it.bones.size } + " HUESOS  ·  " + project.scene.cameras.size + " CAM"
            return
        }
        editorOpen = true
        root = newRoot()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(btn("‹", { showHome() }), LinearLayout.LayoutParams(dp(46), dp(44)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editorTitle = text(project.name + "  ✎", 15f, TEXT, true).apply {
            setOnClickListener { renameProject() }
        }
        titleBox.addView(editorTitle)
        titleBox.addView(text("ESCENA 3D  •  " + project.scene.nodes.size + " objetos", 9f, MUTED))
        top.addView(titleBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(btn("Guardar", { saveGme() }, true))
        root.addView(top)

        val canvas = FrameLayout(this)
        glView = OrbitGLView(this, { dx, dy -> renderer.orbit(dx, dy) }, { factor -> renderer.zoom(factor) },
            { dx, dy -> renderer.pan(dx, dy) },
            { x, y, w, h ->
                if(meshEditMode) {
                    if(meshSelectionMode=="VERTEX") {
                        val vertex=renderer.pickVertex(x,y,w,h)
                        if(vertex!=null) runOnUiThread {
                            selectedVertexIndex=vertex
                            activeTab="Transform"
                            openEditor("Transform")
                        }
                    } else {
                        val face=renderer.pickFace(x,y,w,h)
                        if(face!=null) runOnUiThread {
                            selectedFaceIndex=face
                            activeTab="Transform"
                            openEditor("Transform")
                        }
                    }
                } else {
                    renderer.pick(x,y,w,h)?.let { picked ->
                        selectedId=picked
                        pushSceneToRenderer()
                        runOnUiThread { refreshViewportSelection() }
                    }
                }
            }
        ).apply {
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
        gizmoView = TransformGizmoView(this,
            { transformMode },
            { selectedNode() != null && !meshEditMode },
            { selectedNode()?.let { renderer.projectPoint(worldTransform(it), glView.width.toFloat(), glView.height.toFloat()) } },
            { selectedNode()?.let { renderer.projectAxes(worldTransform(it), glView.width.toFloat(), glView.height.toFloat(), transformSpace == "LOCAL") } },
            { recordUndo() },
            { axis, delta -> applyGizmoDelta(axis, delta) }
        )
        canvas.addView(gizmoView, FrameLayout.LayoutParams(-1, -1))
        val cameraBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xcc14212d.toInt())
        }
        listOf("Front","Side","Top","Persp","Focus").forEach { preset ->
            cameraBar.addView(btn(preset, {
                when (preset) {
                    "Focus" -> selectedNode()?.let { renderer.setCameraTarget(worldTransform(it).position) }
                    else -> renderer.setViewPreset(preset)
                }
                gizmoView.invalidate()
            }), LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        canvas.addView(cameraBar, FrameLayout.LayoutParams(-1, dp(36), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(120), dp(4), dp(4), 0)
        })
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        modeButtons.clear()
        listOf("MOVE", "ROTATE", "SCALE").forEach { mode ->
            val modeButton = btn(if (transformMode == mode) "● " + mode else mode, {
                transformMode = mode
                updateModeButtons()
                gizmoView?.invalidate()
            }, transformMode == mode)
            modeButtons[mode] = modeButton
            tools.addView(modeButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        root.addView(tools)
        val precisionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        precisionRow.addView(btn("Espacio: " + transformSpace, {
            transformSpace = if (transformSpace == "LOCAL") "WORLD" else "LOCAL"
            gizmoView?.invalidate()
            updatePrecisionButtons()
        }))
        precisionRow.addView(btn(if (snapEnabled) "Imán: ON" else "Imán: OFF", {
            snapEnabled = !snapEnabled
            snapRemainders.fill(0f)
            updatePrecisionButtons()
        }))
        root.addView(precisionRow)

        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tabButtons.clear()
        listOf("Scene", "Add", "Transform", "Materials", "Animation", "Physics", "Rig", "Camera", "Effects", "2D", "Assets", "Shaders", "Export").forEach { name ->
            val tabButton = btn(if (activeTab == name) "● " + name else name, { openEditor(name) }, activeTab == name)
            tabButtons[name] = tabButton
            tabStrip.addView(tabButton)
        }
        tabsScroll.addView(tabStrip)
        root.addView(tabsScroll, LinearLayout.LayoutParams(-1, dp(46)))

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(SURFACE) }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(222)))
        buildPanel()
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(text("GME 0.3 DEV", 9f, MUTED), LinearLayout.LayoutParams(0, dp(24), 1f))
        footerInfo = text(project.scene.nodes.size.toString() + " OBJ  ·  " +
            project.rigs.sumOf { it.bones.size } + " HUESOS  ·  " + project.scene.cameras.size + " CAM", 9f, MUTED)
        footer.addView(footerInfo)
        root.addView(footer)
        pushSceneToRenderer()
        setContentView(root)
        glView.onResume()
    }

    private fun updateTabButtons() {
        tabButtons.forEach { (name,button) ->
            button.text = if (activeTab == name) "● " + name else name
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                if (activeTab == name) ACCENT else SURFACE_2))
            button.setTextColor(if (activeTab == name) 0xff07131b.toInt() else TEXT)
        }
    }

    private fun updatePrecisionButtons() {
        if (!editorOpen) return
        val row = root.getChildAt(3) as? LinearLayout ?: return
        if (row.childCount >= 2) {
            (row.getChildAt(0) as? Button)?.text = "Espacio: " + transformSpace
            (row.getChildAt(1) as? Button)?.text = if (snapEnabled) "Imán: ON" else "Imán: OFF"
        }
    }

    private fun updateModeButtons() {
        modeButtons.forEach { (name,button) ->
            button.text = if (transformMode == name) "● " + name else name
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                if (transformMode == name) ACCENT else SURFACE_2))
            button.setTextColor(if (transformMode == name) 0xff07131b.toInt() else TEXT)
        }
    }

    private fun snapDelta(axis: Int, value: Float, step: Float): Float {
        if (!snapEnabled) return value
        val total = snapRemainders[axis] + value
        val steps = (total / step).toInt()
        val applied = steps * step
        snapRemainders[axis] = total - applied
        return applied
    }

    private fun applyGizmoDelta(axis: Int, delta: Float) {
        val node = selectedNode() ?: return
        when (transformMode) {
            "MOVE" -> {
                val amount = snapDelta(axis, delta * 1.6f, 0.25f)
                val direction = when (axis) {
                    0 -> Vec3(1f,0f,0f)
                    1 -> Vec3(0f,1f,0f)
                    else -> Vec3(0f,0f,1f)
                }
                val localAmount = if (transformSpace == "LOCAL") direction * amount else {
                    val parent = project.scene.nodes.firstOrNull { it.id == node.parentId }
                    if (parent == null) direction * amount else {
                        val inverse = FloatArray(16)
                        val matrix = transformMatrix(worldTransform(parent))
                        if (Matrix.invertM(inverse,0,matrix,0)) {
                            val out=FloatArray(4)
                            Matrix.multiplyMV(out,0,inverse,0,floatArrayOf(direction.x,direction.y,direction.z,0f),0)
                            Vec3(out[0]*amount,out[1]*amount,out[2]*amount)
                        } else direction * amount
                    }
                }
                node.transform.position.x += localAmount.x
                node.transform.position.y += localAmount.y
                node.transform.position.z += localAmount.z
            }
            "ROTATE" -> {
                val amount = snapDelta(axis,delta*90f,15f)
                when (axis) {
                    0 -> node.transform.rotationEuler.x += amount
                    1 -> node.transform.rotationEuler.y += amount
                    else -> node.transform.rotationEuler.z += amount
                }
            }
            "SCALE" -> {
                val amount = snapDelta(axis,delta*0.25f,0.1f)
                val factor=(1f+amount).coerceIn(0.1f,3f)
                when (axis) {
                    0 -> node.transform.scale.x = (node.transform.scale.x*factor).coerceIn(0.01f,100f)
                    1 -> node.transform.scale.y = (node.transform.scale.y*factor).coerceIn(0.01f,100f)
                    else -> node.transform.scale.z = (node.transform.scale.z*factor).coerceIn(0.01f,100f)
                }
            }
        }
        pushSceneToRenderer()
        gizmoView?.invalidate()
    }

    private fun refreshViewportSelection() {
        viewportInfo.text = selectedNode()?.name ?: "Sin selección"
        gizmoView?.invalidate()
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
        val history = LinearLayout(this)
        history.addView(btn("↶ Deshacer", { undoProject() }))
        history.addView(btn("↷ Rehacer", { redoProject() }))
        panel.addView(history)
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
                    3 -> { recordUndo(); project.scene.nodes.removeAll { it.id == node.id }; project.primitiveKinds.remove(node.id); selectedId = project.scene.nodes.firstOrNull()?.id }
                    4 -> node.physics = node.physics ?: PhysicsBody()
                }
                openEditor("Scene")
            }.show()
    }

    private fun renameProject() {
        val input=EditText(this).apply { setText(project.name); hint="Nombre del proyecto" }
        AlertDialog.Builder(this).setTitle("Renombrar proyecto").setView(input)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Guardar") { _, _ ->
                recordUndo()
                project.name=input.text.toString().ifBlank { project.name }
                editorTitle.text=project.name+"  ✎"
                saveAutosave()
                toast("Proyecto renombrado")
            }.show()
    }

    private fun renameNode(node: SceneNode) {
        val input = EditText(this).apply { setText(node.name); hint = "Nombre del objeto" }
        AlertDialog.Builder(this).setTitle("Renombrar objeto").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ -> recordUndo(); node.name = input.text.toString().ifBlank { node.name }; openEditor("Scene") }.show()
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
        panel.addView(btn(if (meshEditMode) "● Modo edición de malla" else "Modo objeto · activar edición de malla", {
            meshEditMode = !meshEditMode
            if (meshEditMode && node.mesh == null) node.mesh = MeshFactory.create(project.primitiveKinds[node.id] ?: "CUBE")
            selectedVertexIndex = 0
            selectedFaceIndex = 0
            openEditor("Transform")
        }, meshEditMode))
        if (meshEditMode) {
            val mesh = node.mesh ?: MeshFactory.create("CUBE").also { node.mesh = it }
            val selectionRow=LinearLayout(this)
            selectionRow.addView(btn("Vértices", { meshSelectionMode="VERTEX"; openEditor("Transform") },meshSelectionMode=="VERTEX"))
            selectionRow.addView(btn("Caras", { meshSelectionMode="FACE"; openEditor("Transform") },meshSelectionMode=="FACE"))
            panel.addView(selectionRow)
            if(meshSelectionMode=="VERTEX") {
                selectedVertexIndex = selectedVertexIndex.coerceIn(0, (mesh.positions.size - 1).coerceAtLeast(0))
                val vertex = mesh.positions.getOrNull(selectedVertexIndex)
                if (vertex != null) {
                    panel.addView(text("Vértice " + (selectedVertexIndex + 1) + " / " + mesh.positions.size +
                        "  ·  X %.2f  Y %.2f  Z %.2f".format(vertex.x,vertex.y,vertex.z), 11f, ACCENT, true))
                    val row = LinearLayout(this)
                    row.addView(btn("◀", { selectedVertexIndex = (selectedVertexIndex - 1).coerceAtLeast(0); openEditor("Transform") }))
                    row.addView(btn("▶", { selectedVertexIndex = (selectedVertexIndex + 1).coerceAtMost(mesh.positions.lastIndex); openEditor("Transform") }))
                    row.addView(btn("X−", { recordUndo(); vertex.x -= 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    row.addView(btn("X+", { recordUndo(); vertex.x += 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    row.addView(btn("Y−", { recordUndo(); vertex.y -= 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    row.addView(btn("Y+", { recordUndo(); vertex.y += 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    row.addView(btn("Z−", { recordUndo(); vertex.z -= 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    row.addView(btn("Z+", { recordUndo(); vertex.z += 0.1f; pushSceneToRenderer(); openEditor("Transform") }))
                    panel.addView(row)
                }
            } else {
                val indices=if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
                val faceCount=indices.size/3
                if(faceCount>0) {
                    selectedFaceIndex=selectedFaceIndex.coerceIn(0,faceCount-1)
                    val start=selectedFaceIndex*3
                    panel.addView(text("Cara " + (selectedFaceIndex+1) + " / " + faceCount,11f,ACCENT,true))
                    panel.addView(text("Índices: " + indices[start] + ", " + indices[start+1] + ", " + indices[start+2],10f,MUTED))
                    val faceRow=LinearLayout(this)
                    faceRow.addView(btn("◀ Cara", { selectedFaceIndex=(selectedFaceIndex-1).coerceAtLeast(0); openEditor("Transform") }))
                    faceRow.addView(btn("Cara ▶", { selectedFaceIndex=(selectedFaceIndex+1).coerceAtMost(faceCount-1); openEditor("Transform") }))
                    faceRow.addView(btn("Extruir", {
                        recordUndo()
                        if(MeshEditing.extrudeTriangle(mesh,selectedFaceIndex,0.25f)) {
                            selectedFaceIndex=faceCount
                            pushSceneToRenderer()
                            toast("Cara extruida")
                            openEditor("Transform")
                        }
                    },true))
                    panel.addView(faceRow)
                }
            }
            val topologyRow=LinearLayout(this)
            topologyRow.addView(btn("Recalcular normales", {
                recordUndo(); MeshEditing.recalculateNormals(mesh); pushSceneToRenderer(); toast("Normales recalculadas")
            }))
            topologyRow.addView(btn("Invertir normales", {
                recordUndo(); MeshEditing.flipNormals(mesh); pushSceneToRenderer(); toast("Normales invertidas")
            }))
            panel.addView(topologyRow)
            panel.addView(text("Edición de vértices y extrusión básica de caras triangulares. No incluye todavía bevel, loop cuts ni subdivisión Catmull-Clark.",10f,MUTED))
        }
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
                recordUndo()
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
                recordUndo()
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
                override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                override fun onStopTrackingTouch(s:SeekBar?) { pushSceneToRenderer() }
            })
        })
        panel.addView(text("Metallicidad  %.2f".format(node.material.metallic), 11f, MUTED))
        panel.addView(SeekBar(this).apply {
            max=100; progress=(node.material.metallic*100).toInt()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { node.material.metallic=v/100f }
                override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                override fun onStopTrackingTouch(s:SeekBar?) { pushSceneToRenderer() }
            })
        })
        panel.addView(text("Opacidad  %.2f".format(node.material.baseColorAlpha),11f,MUTED))
        panel.addView(SeekBar(this).apply {
            max=100; progress=(node.material.baseColorAlpha*100).toInt()
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { node.material.baseColorAlpha=v/100f }
                override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                override fun onStopTrackingTouch(s:SeekBar?) { pushSceneToRenderer() }
            })
        })
        panel.addView(btn("Modo alpha: " + node.material.alphaMode, {
            recordUndo()
            node.material.alphaMode=when(node.material.alphaMode) { "OPAQUE" -> "BLEND"; "BLEND" -> "MASK"; else -> "OPAQUE" }
            openEditor("Materials")
        }))
        val textureButtons=LinearLayout(this)
        listOf("BASE_COLOR" to "Albedo","NORMAL" to "Normal","METALLIC_ROUGHNESS" to "Metal/Rough",
            "OCCLUSION" to "AO","EMISSIVE" to "Emisión").forEach { slot ->
            textureButtons.addView(btn("+ " + slot.second, { openTexturePicker(slot.first) }),
                LinearLayout.LayoutParams(0,dp(40),1f))
        }
        panel.addView(textureButtons)
        val status=buildList {
            if(node.material.baseColorTextureUri!=null || project.textureUris[node.id]!=null) add("Albedo")
            if(node.material.normalTextureUri!=null) add("Normal")
            if(node.material.metallicRoughnessTextureUri!=null) add("Metal/Rough")
            if(node.material.occlusionTextureUri!=null) add("AO")
            if(node.material.emissiveTextureUri!=null) add("Emisión")
        }.joinToString(" · ")
        panel.addView(text(if(status.isBlank()) "Sin mapas de textura asignados." else "Mapas: " + status,10f,MUTED))
    }

    private fun animationPanel() {
        val node = selectedNode()
        val clip = activeClip()
        section("Timeline de animación", "Clips, reproducción y keyframes de transformación")
        val controls = LinearLayout(this)
        controls.addView(btn(if (playing) "Ⅱ Pausa" else "▶ Play", { togglePlayback() }, true))
        controls.addView(btn("+ Keyframe", { addKeyframe() }))
        controls.addView(btn("Nuevo clip", {
            recordUndo()
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
            recordUndo()
            node.physics = if (node.physics == null) PhysicsBody() else null; openEditor("Physics")
        }, true))
        if (body != null) {
            panel.addView(text("Masa: %.2f kg".format(body.mass),11f,MUTED))
            panel.addView(SeekBar(this).apply {
                max=100; progress=(body.mass.coerceIn(0.1f,10f)*10).toInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { body.mass=max(0.1f,v/10f) }
                    override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(text("Radio de colisión: %.2f".format(body.colliderRadius),11f,MUTED))
            panel.addView(SeekBar(this).apply {
                max=200; progress=(body.colliderRadius.coerceIn(0.05f,10f)*20).toInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { body.colliderRadius=max(0.05f,v/20f) }
                    override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(text("Rebote: %.2f".format(body.restitution),11f,MUTED))
            panel.addView(SeekBar(this).apply {
                max=100; progress=(body.restitution*100).toInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { body.restitution=v/100f }
                    override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(text("Fricción: %.2f".format(body.friction),11f,MUTED))
            panel.addView(SeekBar(this).apply {
                max=100; progress=(body.friction*100).toInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { body.friction=v/100f }
                    override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(btn("Colisionador: " + body.collisionShape, {
                recordUndo()
                body.collisionShape=if(body.collisionShape=="SPHERE") "BOX" else "SPHERE"
                toast("La simulación de contacto usa esferas; BOX se conserva como configuración de proyecto.")
                openEditor("Physics")
            }))
            panel.addView(btn(if (body.useGravity) "Gravedad: Activada" else "Gravedad: Desactivada", { recordUndo(); body.useGravity=!body.useGravity; openEditor("Physics") }))
            panel.addView(btn(if (body.isStatic) "Cuerpo estático" else "Cuerpo dinámico", { recordUndo(); body.isStatic=!body.isStatic; openEditor("Physics") }))
            panel.addView(btn(if (simulating) "Ⅱ Detener simulación" else "▶ Simular escena", { togglePhysics() }, true))
            panel.addView(btn("Reiniciar posición", { recordUndo(); node.transform.position=Vec3(0f,2f,0f); body.velocity=Vec3(); pushSceneToRenderer() }))
        }
        panel.addView(text("Simulación con gravedad, contactos esfera-esfera, suelo, masa, restitución y fricción. El colisionador BOX se guarda pero aún usa contacto esférico aproximado.",10f,MUTED))
    }

    private fun rigPanel() {
        section("Rigging y esqueletos", "Jerarquía de huesos, vinculación y pesos por vértice")
        val actions=LinearLayout(this)
        actions.addView(btn("+ Crear armature", {
            recordUndo()
            project.rigs.add(EditorRig("Armature " + (project.rigs.size+1)))
            addBone(recordHistory=false)
            openEditor("Rig")
        }, true))
        actions.addView(btn("+ Añadir hueso", { addBone(); openEditor("Rig") }))
        panel.addView(actions)
        project.rigs.forEach { rig ->
            panel.addView(text(rig.name + "  ·  " + rig.bones.size + " huesos",13f,ACCENT,true))
            rig.bones.forEachIndexed { index,bone ->
                val boneRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                boneRow.addView(text("  " + (index+1) + ". " + bone.name + "  ·  padre " + bone.parentIndex,11f,TEXT),
                    LinearLayout.LayoutParams(0,dp(38),1f))
                boneRow.addView(btn("Editar", {
                    val proxy=bone.proxyNodeId?.let { id -> project.scene.nodes.firstOrNull { it.id==id } }
                    if (proxy!=null) {
                        selectedId=proxy.id
                        openEditor("Transform")
                    } else toast("Este hueso no tiene un nodo editable asociado.")
                }))
                panel.addView(boneRow)
            }
            val boundNames=rig.boundMeshIds.mapNotNull { id -> project.scene.nodes.firstOrNull { it.id==id }?.name }
            panel.addView(text("Mallas vinculadas: " + boundNames.joinToString().ifBlank { "ninguna" },10f,MUTED))
            val row=LinearLayout(this)
            row.addView(btn("Vincular seleccionada", {
                val node=selectedNode()
                if (node?.mesh==null) toast("Selecciona una malla con geometría.")
                else {
                    recordUndo()
                    if (rig.boundMeshId==null) rig.boundMeshId=node.id
                    if (node.id !in rig.boundMeshIds) rig.boundMeshIds.add(node.id)
                    bindRigToMesh(rig,node)
                    openEditor("Rig")
                }
            }))
            row.addView(btn("Auto-pesos", {
                val node=selectedNode()
                if (node==null || node.mesh==null) toast("Selecciona una malla primero.")
                else {
                    recordUndo()
                    if (node.id !in rig.boundMeshIds) rig.boundMeshIds.add(node.id)
                    if (rig.boundMeshId==null) rig.boundMeshId=node.id
                    bindRigToMesh(rig,node)
                    autoWeightMesh(rig,node)
                    pushSceneToRenderer()
                    toast("Pesos automáticos asignados a " + node.mesh!!.positions.size + " vértices.")
                }
            }, true))
            panel.addView(row)
            panel.addView(btn("Limpiar pesos de malla seleccionada", {
                val node=selectedNode()
                if (node?.mesh==null) toast("Selecciona una malla primero.")
                else {
                    recordUndo()
                    node.mesh!!.joints0.clear()
                    node.mesh!!.weights0.clear()
                    pushSceneToRenderer()
                    toast("Pesos eliminados.")
                }
            }))
            val weightMesh=selectedNode()?.mesh
            if(weightMesh!=null && rig.bones.isNotEmpty()) {
                selectedBoneIndex=selectedBoneIndex.coerceIn(0,rig.bones.lastIndex)
                panel.addView(text("Pintura de pesos · " + rig.bones[selectedBoneIndex].name,11f,ACCENT,true))
                val boneControls=LinearLayout(this)
                boneControls.addView(btn("◀ Hueso", { selectedBoneIndex=(selectedBoneIndex-1).coerceAtLeast(0); openEditor("Rig") }))
                boneControls.addView(btn("Hueso ▶", { selectedBoneIndex=(selectedBoneIndex+1).coerceAtMost(rig.bones.lastIndex); openEditor("Rig") }))
                panel.addView(boneControls)
                if(weightMesh.positions.isNotEmpty()) {
                    val vertexIndex=selectedVertexIndex.coerceIn(0,weightMesh.positions.lastIndex)
                    val current=weightMesh.weights0.getOrNull(vertexIndex)
                    val joints=weightMesh.joints0.getOrNull(vertexIndex)
                    val currentWeight=if(current==null || joints==null) 0f else {
                        val values=floatArrayOf(current.x,current.y,current.z,current.w)
                        joints.indices.filter { joints[it]==selectedBoneIndex }.sumOf { values[it].toDouble() }.toFloat()
                    }
                    panel.addView(text("Vértice " + (vertexIndex+1) + " · peso " + "%.2f".format(currentWeight),10f,MUTED))
                    panel.addView(SeekBar(this).apply {
                        max=100; progress=(currentWeight*100).toInt()
                        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) {
                                if(u) panelWeightPreview.text="Peso propuesto: %.2f".format(v/100f)
                            }
                            override fun onStartTrackingTouch(s:SeekBar?) { recordUndo() }
                            override fun onStopTrackingTouch(s:SeekBar?) {
                                setVertexBoneWeight(weightMesh,vertexIndex,selectedBoneIndex,(s?.progress ?: 0)/100f)
                                pushSceneToRenderer()
                                openEditor("Rig")
                            }
                        })
                    })
                    panelWeightPreview=text("Peso propuesto: %.2f".format(currentWeight),10f,MUTED)
                    panel.addView(panelWeightPreview)
                }
            }
        }
        panel.addView(text("Skinning CPU disponible para mallas con cuatro influencias. Los pesos automáticos son una aproximación inicial; para rigs complejos ajusta los pesos antes de producción.",10f,MUTED))
    }

    private fun cameraPanel() {
        section("Cámaras e iluminación", "Cámaras de escena y luces configurables")
        project.scene.cameras.forEachIndexed { index,camera ->
            panel.addView(text(camera.name + "  ·  FOV " + camera.fieldOfView.toInt() + "°",12f,TEXT,true))
            panel.addView(btn("Usar cámara " + (index+1), {
                renderer.setCameraTarget(camera.transform.position)
                renderer.setCameraSettings(camera.fieldOfView,camera.nearClip,camera.farClip)
                toast("Cámara activa: " + camera.name)
            }, true))
            panel.addView(SeekBar(this).apply {
                max=178; progress=(camera.fieldOfView.toInt()-1).coerceIn(0,178)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) {
                        camera.fieldOfView=(v+1).toFloat()
                        renderer.setCameraSettings(camera.fieldOfView,camera.nearClip,camera.farClip)
                    }
                    override fun onStartTrackingTouch(s:SeekBar?) {}
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            panel.addView(btn("Enfocar cámara " + (index+1), {
                renderer.setCameraTarget(camera.transform.position); toast("Vista enfocada en " + camera.name)
            }))
            val position = LinearLayout(this)
            listOf("X−","X+","Y−","Y+","Z−","Z+").forEachIndexed { i, label ->
                position.addView(btn(label, {
                    val amount=if(i%2==0) -0.25f else 0.25f
                    recordUndo()
                    when(i/2) { 0 -> camera.transform.position.x+=amount; 1 -> camera.transform.position.y+=amount; else -> camera.transform.position.z+=amount }
                    renderer.setCameraTarget(camera.transform.position)
                }))
            }
            panel.addView(position)
        }
        panel.addView(btn("+ Añadir cámara", {
            recordUndo(); project.scene.cameras.add(CameraData("Camera " + (project.scene.cameras.size+1))); openEditor("Camera")
        }, true))
        project.lights.forEach { light ->
            panel.addView(text(light.kind + ": " + light.name + " · Intensidad " + "%.1f".format(light.intensity),11f,ACCENT))
            panel.addView(SeekBar(this).apply {
                max=100; progress=(light.intensity.coerceIn(0f,10f)*10).toInt()
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { light.intensity=v/10f }
                    override fun onStartTrackingTouch(s:SeekBar?) {}
                    override fun onStopTrackingTouch(s:SeekBar?) {}
                })
            })
            val colors=LinearLayout(this)
            listOf(Vec3(1f,1f,1f),Vec3(1f,0.75f,0.5f),Vec3(0.5f,0.7f,1f)).forEachIndexed { i,color ->
                colors.addView(btn(listOf("Blanca","Cálida","Fría")[i], { recordUndo(); light.color=color.copy() }))
            }
            panel.addView(colors)
        }
        panel.addView(btn("+ Luz puntual", { recordUndo(); project.lights.add(EditorLight()); openEditor("Camera") }))
        panel.addView(btn("+ Luz direccional", { recordUndo(); project.lights.add(EditorLight(kind="DIRECTIONAL",name="Sun")); openEditor("Camera") }))
        panel.addView(text("Cámaras y luces se guardan en .gme. El visor aún no calcula iluminación física de escena ni sombras por luz.",10f,MUTED))
    }


    private fun effectsPanel() {
        section("Render y efectos", "Opciones persistentes del proyecto")
        val effects = project.effects
        fun toggle(title: String, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            panel.addView(btn(title + if (getter()) "  ·  ON" else "  ·  OFF", {
                recordUndo(); setter(!getter()); openEditor("Effects")
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
        val sketch = SketchCanvasView(this, layer) { recordUndo() }.apply { setBackgroundColor(0xff0c131b.toInt()) }
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
            recordUndo()
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
        panel.addView(btn("Cargar carpeta de texturas / MTL / buffers", { openAssetsFolder() }))
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
        section("Gema Shader Lab", "Lenguaje GSL sencillo o GLSL ES 1.00")
        val modeRow = LinearLayout(this)
        modeRow.addView(btn("GSL", {
            project.shaderMode = "GSL"
            if (!project.shaderSource.trimStart().startsWith("shader ")) project.shaderSource = ShaderLibrary.DEFAULT_GSL
            openEditor("Shaders")
        }, project.shaderMode == "GSL"))
        modeRow.addView(btn("GLSL ES", {
            project.shaderMode = "GLSL"
            if (project.shaderSource.trimStart().startsWith("shader ")) project.shaderSource = ShaderLibrary.DEFAULT_FRAGMENT
            openEditor("Shaders")
        }, project.shaderMode == "GLSL"))
        panel.addView(modeRow)
        val editor=EditText(this).apply {
            setText(project.shaderSource); textSize=11f; typeface=android.graphics.Typeface.MONOSPACE
            setTextColor(0xffd7e7f7.toInt()); setBackgroundColor(0xff0c131b.toInt())
            minLines=5; gravity=Gravity.TOP or Gravity.START; setPadding(dp(10),dp(8),dp(10),dp(8))
        }
        panel.addView(editor,LinearLayout.LayoutParams(-1,dp(112)))
        val actions=LinearLayout(this)
        actions.addView(btn("Validar y aplicar", {
            recordUndo()
            project.shaderSource=editor.text.toString()
            val glsl = try {
                if (project.shaderMode == "GSL") GslCompiler.compile(project.shaderSource) else project.shaderSource
            } catch (error: Exception) {
                toast("Error GSL: " + error.message)
                return@btn
            }
            glView.queueEvent {
                val error=renderer.applyFragmentShader(glsl)
                runOnUiThread {
                    if (error==null) toast("Shader compilado y aplicado.")
                    else toast("Error GLSL: " + error)
                }
            }
        },true))
        actions.addView(btn("Ejemplo Toon", {
            val example = if (project.shaderMode == "GSL") ShaderLibrary.EXAMPLE_TOON_GSL else ShaderLibrary.EXAMPLE_TOON
            recordUndo(); editor.setText(example); project.shaderSource=example
        }))
        actions.addView(btn("Restaurar", {
            val defaultSource = if (project.shaderMode == "GSL") ShaderLibrary.DEFAULT_GSL else ShaderLibrary.DEFAULT_FRAGMENT
            recordUndo(); editor.setText(defaultSource); project.shaderSource=defaultSource
            val glsl = if (project.shaderMode == "GSL") GslCompiler.compile(defaultSource) else defaultSource
            glView.queueEvent { renderer.applyFragmentShader(glsl) }
        }))
        panel.addView(actions)
        panel.addView(text(if (project.shaderMode == "GSL")
            "GSL: shader Nombre { output = uColor; }. Salida compatible con expresiones y funciones seguras; se traduce automáticamente a GLSL."
            else "GLSL ES 1.00: usa uniform vec4 uColor y gl_FragColor.",10f,MUTED))
    }

    private fun exportPanel() {
        section("Importar y exportar", "Formatos disponibles desde el selector del dispositivo")
        panel.addView(btn("+ Importar modelo / proyecto", { openDocument() }, true))
        panel.addView(btn("Vincular carpeta de recursos externos", { openAssetsFolder() }))
        panel.addView(btn("Guardar proyecto .gme", { saveGme() }))
        panel.addView(btn("Exportar malla OBJ", { exportDocument("obj") }))
        panel.addView(btn("Exportar malla glTF", { exportDocument("gltf") }))
        panel.addView(btn("Exportar malla GLB", { exportDocument("glb") }))
        panel.addView(btn("Exportar malla STL", { exportDocument("stl") }))
        panel.addView(btn("Exportar malla PLY", { exportDocument("ply") }))
        panel.addView(btn("Exportar ESCENA OBJ + MTL + texturas", { openSceneObjFolder() }))
        panel.addView(btn("Exportar ESCENA completa GLB", { exportCompleteScene("glb") }, true))
        panel.addView(btn("Exportar ESCENA completa glTF", { exportCompleteScene("gltf") }))
        panel.addView(btn("Capturar imagen PNG", { createImageDocument() }))
        panel.addView(btn("Exportar vídeo MP4", {
            AlertDialog.Builder(this).setTitle("Exportación de vídeo")
                .setMessage("El apartado está disponible en el menú, pero el codificador MP4 y la exportación por secuencia de frames aún no están implementados.")
                .setPositiveButton("Entendido",null).show()
        }))
        panel.addView(text("Formatos de malla: .obj, .stl, .ply ASCII, FBX ASCII y glTF 2.0/.glb básico. Proyecto nativo: .gme. FBX binario, Blender y Prisma todavía no.",10f,MUTED))
    }

    private fun addObject(name: String, kind: String, position: Vec3, select: Boolean) {
        recordUndo()
        val id=UUID.randomUUID().toString()
        project.scene.nodes.add(SceneNode(id,name,Transform(position=position),mesh=MeshFactory.create(kind)))
        project.primitiveKinds[id]=kind
        if (select) selectedId=id
        pushSceneToRenderer()
    }
    private fun boneSegmentMesh(tail: Vec3): MeshData {
        val length = kotlin.math.sqrt(tail.x*tail.x+tail.y*tail.y+tail.z*tail.z).coerceAtLeast(0.05f)
        val direction = Vec3(tail.x/length,tail.y/length,tail.z/length)
        val reference = if (kotlin.math.abs(direction.y) < 0.92f) Vec3(0f,1f,0f) else Vec3(1f,0f,0f)
        fun cross(a:Vec3,b:Vec3)=Vec3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
        val side0 = cross(direction,reference)
        val sideLength = kotlin.math.sqrt(side0.x*side0.x+side0.y*side0.y+side0.z*side0.z).coerceAtLeast(0.0001f)
        val side = Vec3(side0.x/sideLength*0.035f,side0.y/sideLength*0.035f,side0.z/sideLength*0.035f)
        val up0 = cross(direction,side)
        val up = Vec3(up0.x/0.035f*0.035f,up0.y/0.035f*0.035f,up0.z/0.035f*0.035f)
        val base = listOf(
            Vec3(side.x+up.x,side.y+up.y,side.z+up.z),
            Vec3(-side.x+up.x,-side.y+up.y,-side.z+up.z),
            Vec3(-side.x-up.x,-side.y-up.y,-side.z-up.z),
            Vec3(side.x-up.x,side.y-up.y,side.z-up.z)
        )
        val tip = listOf(
            Vec3(tail.x+side.x*0.12f,tail.y+side.y*0.12f,tail.z+side.z*0.12f),
            Vec3(tail.x-side.x*0.12f,tail.y-side.y*0.12f,tail.z-side.z*0.12f),
            Vec3(tail.x-side.x*0.12f,tail.y-side.y*0.12f,tail.z-side.z*0.12f),
            Vec3(tail.x+side.x*0.12f,tail.y+side.y*0.12f,tail.z+side.z*0.12f)
        )
        val mesh=MeshData()
        fun tri(a:Vec3,b:Vec3,c:Vec3) {
            val start=mesh.positions.size
            mesh.positions.add(a);mesh.positions.add(b);mesh.positions.add(c)
            mesh.triangleIndices.add(start);mesh.triangleIndices.add(start+1);mesh.triangleIndices.add(start+2)
        }
        for (i in 0..3) {
            val next=(i+1)%4
            tri(base[i],base[next],tip[next])
            tri(base[i],tip[next],tip[i])
        }
        tri(tip[0],tip[1],tip[2]);tri(tip[0],tip[2],tip[3])
        return mesh
    }

    private fun selectedNode()=project.scene.nodes.firstOrNull { it.id==selectedId }
    private fun duplicateSelected() { selectedNode()?.let { duplicateNode(it) } }
    private fun duplicateNode(node: SceneNode) {
        recordUndo()
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
        recordUndo()
        project.scene.nodes.removeAll { it.id==id }; project.primitiveKinds.remove(id); project.textureUris.remove(id)
        selectedId=project.scene.nodes.firstOrNull()?.id; pushSceneToRenderer(); openEditor("Scene")
    }
    private fun setVertexBoneWeight(mesh:MeshData,vertexIndex:Int,boneIndex:Int,requestedWeight:Float) {
        if(vertexIndex !in mesh.positions.indices || boneIndex<0) return
        while(mesh.joints0.size<mesh.positions.size) mesh.joints0.add(IntArray(4))
        while(mesh.weights0.size<mesh.positions.size) mesh.weights0.add(Vec4())
        val joints=mesh.joints0[vertexIndex].copyOf()
        val old=mesh.weights0[vertexIndex]
        val weights=floatArrayOf(old.x,old.y,old.z,old.w)
        var slot=joints.indexOf(boneIndex)
        if(slot<0) {
            slot=weights.indices.minByOrNull { weights[it] } ?: 0
            joints[slot]=boneIndex
            weights[slot]=0f
        }
        weights[slot]=requestedWeight.coerceIn(0f,1f)
        val total=weights.sum().coerceAtLeast(0.00001f)
        for(i in weights.indices) weights[i]/=total
        mesh.joints0[vertexIndex]=joints
        mesh.weights0[vertexIndex]=Vec4(weights[0],weights[1],weights[2],weights[3])
    }

    private fun addBone(recordHistory: Boolean = true) {
        if (recordHistory) recordUndo()
        if (project.rigs.isEmpty()) project.rigs.add(EditorRig())
        val rig=project.rigs.last()
        val parent=rig.bones.lastOrNull()
        val localHead=parent?.tail?.copy() ?: Vec3()
        val localTail=Vec3(0f,0.65f,0f)
        val bone=RigBone(
            name="Bone " + (rig.bones.size+1),
            parentIndex=if (rig.bones.isEmpty()) -1 else rig.bones.lastIndex,
            head=Vec3(),
            tail=localTail,
            restTransform=Transform(position=localHead)
        )
        val id=UUID.randomUUID().toString()
        val proxy=SceneNode(
            id,
            rig.name + " / " + bone.name,
            bone.restTransform.copy(position=bone.restTransform.position.copy()),
            boneSegmentMesh(bone.tail),
            MaterialData("Bone",Vec3(0.98f,0.55f,0.22f)),
            parentId=parent?.proxyNodeId
        )
        bone.proxyNodeId=id
        rig.bones.add(bone)
        project.scene.nodes.add(proxy)
        project.primitiveKinds[id]="BONE_PROXY"
        selectedId=id
        pushSceneToRenderer()
    }

    private fun bindRigToMesh(rig: EditorRig, node: SceneNode) {
        if (rig.boundMeshId==null) rig.boundMeshId=node.id
        if (node.id !in rig.boundMeshIds) rig.boundMeshIds.add(node.id)
        val meshMatrix=transformMatrix(worldTransform(node))
        rig.bones.forEach { bone ->
            val proxy=bone.proxyNodeId?.let { id -> project.scene.nodes.firstOrNull { it.id==id } } ?: return@forEach
            val boneMatrix=transformMatrix(worldTransform(proxy))
            val inverseBone=FloatArray(16)
            if (Matrix.invertM(inverseBone,0,boneMatrix,0)) {
                val inverseBind=FloatArray(16)
                Matrix.multiplyMM(inverseBind,0,inverseBone,0,meshMatrix,0)
                bone.inverseBindMatrix=inverseBind
            }
        }
    }

    private fun autoWeightMesh(rig: EditorRig, node: SceneNode) {
        val mesh=node.mesh ?: return
        if (rig.bones.isEmpty()) { toast("El armature no tiene huesos."); return }
        val boneSegments=rig.bones.map { bone ->
            val proxy=bone.proxyNodeId?.let { id -> project.scene.nodes.firstOrNull { it.id==id } }
            val world=proxy?.let { worldTransform(it) } ?: bone.restTransform
            val matrix=transformMatrix(world)
            val tail=FloatArray(4)
            Matrix.multiplyMV(tail,0,matrix,0,floatArrayOf(bone.tail.x,bone.tail.y,bone.tail.z,1f),0)
            val inverseMesh=FloatArray(16)
            val meshWorld=transformMatrix(worldTransform(node))
            if (!Matrix.invertM(inverseMesh,0,meshWorld,0)) inverseMesh.fill(0f)
            val head=FloatArray(4)
            Matrix.multiplyMV(head,0,inverseMesh,0,floatArrayOf(world.position.x,world.position.y,world.position.z,1f),0)
            val end=FloatArray(4)
            Matrix.multiplyMV(end,0,inverseMesh,0,tail,0)
            Pair(Vec3(head[0],head[1],head[2]),Vec3(end[0],end[1],end[2]))
        }
        mesh.joints0.clear()
        mesh.weights0.clear()
        mesh.positions.forEach { vertex ->
            val distances=boneSegments.mapIndexed { index,segment ->
                val a=segment.first; val b=segment.second
                val ab=Vec3(b.x-a.x,b.y-a.y,b.z-a.z)
                val av=Vec3(vertex.x-a.x,vertex.y-a.y,vertex.z-a.z)
                val length2=ab.x*ab.x+ab.y*ab.y+ab.z*ab.z
                val t=if(length2>0.000001f) ((av.x*ab.x+av.y*ab.y+av.z*ab.z)/length2).coerceIn(0f,1f) else 0f
                val closest=Vec3(a.x+ab.x*t,a.y+ab.y*t,a.z+ab.z*t)
                val dx=vertex.x-closest.x; val dy=vertex.y-closest.y; val dz=vertex.z-closest.z
                index to (dx*dx+dy*dy+dz*dz)
            }.sortedBy { it.second }.take(4)
            val raw=distances.map { 1f/(it.second+0.0001f) }
            val sum=raw.sum().coerceAtLeast(0.000001f)
            val joints=IntArray(4)
            val weights=FloatArray(4)
            distances.forEachIndexed { index,pair ->
                joints[index]=pair.first
                weights[index]=raw[index]/sum
            }
            mesh.joints0.add(joints)
            mesh.weights0.add(Vec4(weights[0],weights[1],weights[2],weights[3]))
        }
    }
    private fun activeClip(): AnimationClip {
        if (project.scene.animations.isEmpty()) project.scene.animations.add(AnimationClip())
        return project.scene.animations.first()
    }
    private fun addKeyframe() {
        val node=selectedNode() ?: run { toast("Selecciona un objeto primero"); return }
        val clip=activeClip()
        var track=clip.tracks.firstOrNull { it.nodeId==node.id }
        recordUndo()
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

    private fun worldTransform(node: SceneNode, visited: MutableSet<String> = mutableSetOf()): Transform {
        if (!visited.add(node.id)) return node.transform
        val parent = project.scene.nodes.firstOrNull { it.id == node.parentId } ?: return node.transform
        val p = worldTransform(parent, visited)
        val local = node.transform
        val sx = local.position.x * p.scale.x
        val sy = local.position.y * p.scale.y
        val sz = local.position.z * p.scale.z
        val rx = Math.toRadians(p.rotationEuler.x.toDouble())
        val ry = Math.toRadians(p.rotationEuler.y.toDouble())
        val rz = Math.toRadians(p.rotationEuler.z.toDouble())
        val x1 = sx
        val y1 = sy * kotlin.math.cos(rx).toFloat() - sz * kotlin.math.sin(rx).toFloat()
        val z1 = sy * kotlin.math.sin(rx).toFloat() + sz * kotlin.math.cos(rx).toFloat()
        val x2 = x1 * kotlin.math.cos(ry).toFloat() + z1 * kotlin.math.sin(ry).toFloat()
        val y2 = y1
        val z2 = -x1 * kotlin.math.sin(ry).toFloat() + z1 * kotlin.math.cos(ry).toFloat()
        val x3 = x2 * kotlin.math.cos(rz).toFloat() - y2 * kotlin.math.sin(rz).toFloat()
        val y3 = x2 * kotlin.math.sin(rz).toFloat() + y2 * kotlin.math.cos(rz).toFloat()
        return Transform(
            Vec3(p.position.x+x3,p.position.y+y3,p.position.z+z2),
            Vec3(p.rotationEuler.x+local.rotationEuler.x,p.rotationEuler.y+local.rotationEuler.y,p.rotationEuler.z+local.rotationEuler.z),
            Vec3(p.scale.x*local.scale.x,p.scale.y*local.scale.y,p.scale.z*local.scale.z)
        )
    }

    private fun transformMatrix(transform: Transform): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix,0)
        Matrix.translateM(matrix,0,transform.position.x,transform.position.y,transform.position.z)
        Matrix.rotateM(matrix,0,transform.rotationEuler.x,1f,0f,0f)
        Matrix.rotateM(matrix,0,transform.rotationEuler.y,0f,1f,0f)
        Matrix.rotateM(matrix,0,transform.rotationEuler.z,0f,0f,1f)
        Matrix.scaleM(matrix,0,transform.scale.x,transform.scale.y,transform.scale.z)
        return matrix
    }

    /** CPU skinning fallback. Uses four influences and inverse-bind matrices from glTF skins. */
    private fun deformMeshForRig(mesh: MeshData, rig: EditorRig, meshTransform: Transform): MeshData {
        if (mesh.joints0.size != mesh.positions.size || mesh.weights0.size != mesh.positions.size || rig.bones.isEmpty()) return mesh
        val inverseMesh = FloatArray(16)
        if (!Matrix.invertM(inverseMesh,0,transformMatrix(meshTransform),0)) return mesh
        val boneMatrices = rig.bones.map { bone ->
            val boneNode = project.scene.nodes.firstOrNull { it.id == bone.proxyNodeId }
            val current = boneNode?.let { worldTransform(it) } ?: bone.restTransform
            val boneWorld = transformMatrix(current)
            val inverseBind = bone.inverseBindMatrix ?: FloatArray(16).also { Matrix.setIdentityM(it,0) }
            val worldBind = FloatArray(16)
            val meshSpace = FloatArray(16)
            Matrix.multiplyMM(worldBind,0,boneWorld,0,inverseBind,0)
            Matrix.multiplyMM(meshSpace,0,inverseMesh,0,worldBind,0)
            meshSpace
        }
        fun weightedDirection(vertexIndex:Int, direction:Vec3):Vec3 {
            val joints=mesh.joints0.getOrNull(vertexIndex) ?: return direction.copy()
            val weights=mesh.weights0.getOrNull(vertexIndex) ?: return direction.copy()
            val weightValues=floatArrayOf(weights.x,weights.y,weights.z,weights.w)
            val source=floatArrayOf(direction.x,direction.y,direction.z,0f)
            val result=FloatArray(4)
            var total=0f
            for(influence in 0..3) {
                val weight=weightValues[influence]
                val matrix=boneMatrices.getOrNull(joints.getOrElse(influence){-1})
                if(weight<=0f || matrix==null) continue
                val transformed=FloatArray(4)
                Matrix.multiplyMV(transformed,0,matrix,0,source,0)
                result[0]+=transformed[0]*weight
                result[1]+=transformed[1]*weight
                result[2]+=transformed[2]*weight
                total+=weight
            }
            if(total<=0f) return direction.copy()
            val length=kotlin.math.sqrt(result[0]*result[0]+result[1]*result[1]+result[2]*result[2]).coerceAtLeast(0.00001f)
            return Vec3(result[0]/length,result[1]/length,result[2]/length)
        }

        val positions=mutableListOf<Vec3>()
        val normals=mutableListOf<Vec3>()
        val tangents=mutableListOf<Vec4>()
        mesh.positions.forEachIndexed { vertexIndex,point ->
            val joints=mesh.joints0.getOrNull(vertexIndex)
            val weights=mesh.weights0.getOrNull(vertexIndex)
            if(joints==null || weights==null) {
                positions.add(point.copy())
            } else {
                val weightValues=floatArrayOf(weights.x,weights.y,weights.z,weights.w)
                val base=floatArrayOf(point.x,point.y,point.z,1f)
                val result=FloatArray(4)
                var total=0f
                for(influence in 0..3) {
                    val weight=weightValues[influence]
                    val matrix=boneMatrices.getOrNull(joints.getOrElse(influence){-1})
                    if(weight<=0f || matrix==null) continue
                    val transformed=FloatArray(4)
                    Matrix.multiplyMV(transformed,0,matrix,0,base,0)
                    result[0]+=transformed[0]*weight
                    result[1]+=transformed[1]*weight
                    result[2]+=transformed[2]*weight
                    total+=weight
                }
                positions.add(if(total>0f) Vec3(result[0]/total,result[1]/total,result[2]/total) else point.copy())
            }
            mesh.normals.getOrNull(vertexIndex)?.let { normals.add(weightedDirection(vertexIndex,it)) }
            mesh.tangents0.getOrNull(vertexIndex)?.let { tangent ->
                val direction=weightedDirection(vertexIndex,Vec3(tangent.x,tangent.y,tangent.z))
                tangents.add(Vec4(direction.x,direction.y,direction.z,tangent.w))
            }
        }
        return mesh.copy(
            positions=positions,
            normals=if(normals.size==mesh.normals.size) normals else mesh.normals,
            tangents0=if(tangents.size==mesh.tangents0.size) tangents else mesh.tangents0
        )
    }

    private fun pushSceneToRenderer() {
        if (!::renderer.isInitialized) return
        renderer.lights=project.lights.map { it.copy(position=it.position.copy(),color=it.color.copy()) }
        renderer.effects=project.effects.copy()
        renderer.items=project.scene.nodes.map { node ->
            val sourceMesh=node.mesh ?: if (project.primitiveKinds[node.id] == "EMPTY") MeshData()
                else MeshFactory.create(project.primitiveKinds[node.id] ?: "CUBE")
            val transform = worldTransform(node)
            val rig = project.rigs.firstOrNull { it.boundMeshId == node.id || node.id in it.boundMeshIds }
            val mesh = if (rig != null) deformMeshForRig(sourceMesh, rig, transform) else sourceMesh
            val indices: List<Int> = if(mesh.triangleIndices.isEmpty()) mesh.positions.indices.toList() else mesh.triangleIndices
            val vertices=FloatArray(indices.size*3)
            val sourceVertexIndices=indices.toIntArray()
            indices.forEachIndexed { i,index ->
                val p=mesh.positions.getOrNull(index) ?: Vec3()
                vertices[i*3]=p.x; vertices[i*3+1]=p.y; vertices[i*3+2]=p.z
            }
            val wire = FloatArray((indices.size / 3) * 18)
            val normals = FloatArray(indices.size * 3)
            val tangents = FloatArray(indices.size * 4)
            val texCoords = FloatArray(indices.size * 2)
            var wireOffset = 0
            fun putWire(point: Vec3) {
                wire[wireOffset++] = point.x; wire[wireOffset++] = point.y; wire[wireOffset++] = point.z
            }
            indices.forEachIndexed { i, index ->
                val uv = mesh.texCoords0.getOrNull(index) ?: Vec2()
                texCoords[i * 2] = uv.x
                texCoords[i * 2 + 1] = uv.y
            }
            for (i in indices.indices step 3) {
                if (i + 2 < indices.size) {
                    val ia=indices[i]; val ib=indices[i+1]; val ic=indices[i+2]
                    val a=mesh.positions[ia]; val b=mesh.positions[ib]; val d=mesh.positions[ic]
                    putWire(a); putWire(b); putWire(b); putWire(d); putWire(d); putWire(a)
                    val ab=Vec3(b.x-a.x,b.y-a.y,b.z-a.z)
                    val ac=Vec3(d.x-a.x,d.y-a.y,d.z-a.z)
                    val cross=Vec3(ab.y*ac.z-ab.z*ac.y,ab.z*ac.x-ab.x*ac.z,ab.x*ac.y-ab.y*ac.x)
                    val magnitude=kotlin.math.sqrt(cross.x*cross.x+cross.y*cross.y+cross.z*cross.z).coerceAtLeast(0.00001f)
                    val face=Vec3(cross.x/magnitude,cross.y/magnitude,cross.z/magnitude)
                    val triangle=listOf(ia,ib,ic)
                    val uvA=mesh.texCoords0.getOrNull(ia) ?: Vec2()
                    val uvB=mesh.texCoords0.getOrNull(ib) ?: Vec2()
                    val uvC=mesh.texCoords0.getOrNull(ic) ?: Vec2()
                    val du1=uvB.x-uvA.x; val dv1=uvB.y-uvA.y
                    val du2=uvC.x-uvA.x; val dv2=uvC.y-uvA.y
                    val determinant=du1*dv2-du2*dv1
                    val tangentRaw=if(kotlin.math.abs(determinant)>0.000001f) {
                        val inverse=1f/determinant
                        Vec3((ab.x*dv2-ac.x*dv1)*inverse,(ab.y*dv2-ac.y*dv1)*inverse,(ab.z*dv2-ac.z*dv1)*inverse)
                    } else Vec3(1f,0f,0f)
                    val bitangentRaw=if(kotlin.math.abs(determinant)>0.000001f) {
                        val inverse=1f/determinant
                        Vec3((ac.x*du1-ab.x*du2)*inverse,(ac.y*du1-ab.y*du2)*inverse,(ac.z*du1-ab.z*du2)*inverse)
                    } else Vec3(0f,1f,0f)
                    triangle.forEachIndexed { corner, sourceIndex ->
                        val normal=mesh.normals.getOrNull(sourceIndex)?.takeIf {
                            kotlin.math.abs(it.x)+kotlin.math.abs(it.y)+kotlin.math.abs(it.z)>0.0001f
                        } ?: face
                        val importedTangent=mesh.tangents0.getOrNull(sourceIndex)
                        val tangentVector=if(importedTangent!=null) Vec3(importedTangent.x,importedTangent.y,importedTangent.z) else {
                            val dot=normal.x*tangentRaw.x+normal.y*tangentRaw.y+normal.z*tangentRaw.z
                            Vec3(tangentRaw.x-normal.x*dot,tangentRaw.y-normal.y*dot,tangentRaw.z-normal.z*dot)
                        }
                        val tangentLength=kotlin.math.sqrt(tangentVector.x*tangentVector.x+tangentVector.y*tangentVector.y+tangentVector.z*tangentVector.z).coerceAtLeast(0.00001f)
                        val tx=tangentVector.x/tangentLength; val ty=tangentVector.y/tangentLength; val tz=tangentVector.z/tangentLength
                        val crossNB=Vec3(normal.y*tz-normal.z*ty,normal.z*tx-normal.x*tz,normal.x*ty-normal.y*tx)
                        val handedness=importedTangent?.w ?: if(crossNB.x*bitangentRaw.x+crossNB.y*bitangentRaw.y+crossNB.z*bitangentRaw.z<0f) -1f else 1f
                        val outIndex=i+corner
                        normals[outIndex*3]=normal.x
                        normals[outIndex*3+1]=normal.y
                        normals[outIndex*3+2]=normal.z
                        tangents[outIndex*4]=tx
                        tangents[outIndex*4+1]=ty
                        tangents[outIndex*4+2]=tz
                        tangents[outIndex*4+3]=handedness
                    }
                }
            }
            val texture = node.material.baseColorTextureUri ?: project.textureUris[node.id]
            RenderItem(node.id,node.name,vertices,sourceVertexIndices,wire,normals,tangents,texCoords,texture,
                node.material.normalTextureUri,node.material.normalScale,node.material.metallicRoughnessTextureUri,
                node.material.occlusionTextureUri,node.material.emissiveTextureUri,
                transform.position.copy(),transform.rotationEuler.copy(),
                transform.scale.copy(),node.material.baseColor.copy(),node.material.emissiveColor.copy(),
                node.material.roughness,node.material.metallic,node.material.baseColorAlpha,
                node.material.alphaMode,node.material.alphaCutoff,node.id==selectedId,
                project.effects.wireframe,project.effects.outlines)
        }
        if (::viewportInfo.isInitialized) viewportInfo.text = (selectedNode()?.name ?: "Sin selección") + "  ·  " + project.scene.nodes.size + " objetos"
    }

    private fun openDocument() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("model/gltf+json","model/gltf-binary","application/json","text/plain","application/octet-stream"))
        }
        startActivityForResult(intent,100)
    }
    private fun openTexturePicker(slot: String = "BASE_COLOR") {
        textureTarget=selectedId
        textureSlotTarget=slot
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="image/*" }
        startActivityForResult(intent,104)
    }
    private fun saveGme() { saveKind="gme"; createDocument("application/json",project.name.replace(" ","_")+".gme",101) }
    private fun exportDocument(kind:String) {
        val node=selectedNode() ?: run { toast("Selecciona una malla para exportar"); return }
        saveKind=kind
        val mime=when(kind) { "obj", "stl", "ply" -> "text/plain"; "gltf" -> "model/gltf+json"; else -> "model/gltf-binary" }
        createDocument(mime,node.name.replace(" ","_")+"."+kind,102)
    }
    private fun openSceneObjFolder() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent,108)
    }

    private fun exportCompleteObjFolder(treeUri: Uri) {
        try {
            exportFolderUri=treeUri
            val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            try { contentResolver.takePersistableUriPermission(treeUri,flags) } catch (_:Exception) {}
            val folder=DocumentsContract.buildDocumentUriUsingTree(treeUri,DocumentsContract.getTreeDocumentId(treeUri))
            val bundle=ObjSceneExporter.encode(project) { node -> worldTransform(node) }
            fun writeFile(name:String,mime:String,bytes:ByteArray) {
                val child=DocumentsContract.createDocument(contentResolver,folder,mime,name)
                    ?: throw IllegalStateException("No se pudo crear " + name)
                contentResolver.openOutputStream(child)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("No se pudo escribir " + name)
            }
            writeFile("GemaScene.obj","text/plain",bundle.objText.toByteArray(Charsets.UTF_8))
            writeFile("GemaScene.mtl","text/plain",bundle.mtlText.toByteArray(Charsets.UTF_8))
            bundle.textures.forEach { (name,bytes) ->
                val mime=when(name.substringAfterLast('.').lowercase()) {
                    "jpg","jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }
                writeFile(name,mime,bytes)
            }
            toast("Escena OBJ exportada con MTL y " + bundle.textures.size + " texturas.")
        } catch (error:Exception) {
            toast("Error exportando OBJ: " + error.message)
        }
    }

    private fun exportCompleteScene(kind: String) {
        saveKind = if (kind == "glb") "scene_glb" else "scene_gltf"
        val mime = if (kind == "glb") "model/gltf-binary" else "model/gltf+json"
        createDocument(mime, project.name.replace(" ","_") + if (kind == "glb") ".glb" else ".gltf", 107)
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
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer la imagen.")
                    val mime = contentResolver.getType(uri) ?: "image/png"
                    val dataUri = ObjMtlImporter.dataUri(bytes, mime)
                    recordUndo()
                    textureTarget?.let { target ->
                        val material=project.scene.nodes.firstOrNull { it.id == target }?.material
                        when(textureSlotTarget) {
                            "BASE_COLOR" -> {
                                project.textureUris[target] = dataUri
                                material?.baseColorTextureUri=dataUri
                            }
                            "NORMAL" -> material?.normalTextureUri=dataUri
                            "METALLIC_ROUGHNESS" -> material?.metallicRoughnessTextureUri=dataUri
                            "OCCLUSION" -> material?.occlusionTextureUri=dataUri
                            "EMISSIVE" -> material?.emissiveTextureUri=dataUri
                        }
                    }
                    toast("Mapa " + textureSlotTarget + " importado y aplicado.")
                    pushSceneToRenderer()
                    openEditor("Materials")
                } catch (error: Exception) { toast("Error cargando textura: " + error.message) }
            }
            105 -> save2DCanvas(uri)
            106 -> loadExternalAssetFolder(uri)
            107 -> exportCompleteSceneTo(uri)
            108 -> exportCompleteObjFolder(uri)
        }
    }
    private fun openAssetsFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, 106)
    }

    private fun resolveExternalAsset(path: String): ByteArray? {
        val normalized = path.replace('\\\\', '/').removePrefix("./").trimStart('/')
        return externalAssets[normalized]
            ?: externalAssets[normalized.lowercase()]
            ?: externalAssets[normalized.substringAfterLast('/')]
            ?: externalAssets[normalized.substringAfterLast('/').lowercase()]
    }

    private fun loadExternalAssetFolder(treeUri: Uri) {
        try {
            assetsTreeUri = treeUri
            externalAssets.clear()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            try { contentResolver.takePersistableUriPermission(treeUri, flags) } catch (_: Exception) {}
            data class Folder(val documentId: String, val path: String)
            val pending = ArrayDeque<Folder>()
            pending.add(Folder(DocumentsContract.getTreeDocumentId(treeUri), ""))
            var loaded = 0
            var totalBytes = 0L
            val maxSingleAsset = 64L * 1024L * 1024L
            val maxFolderBytes = 256L * 1024L * 1024L
            val allowedExtensions = setOf("bin","mtl","png","jpg","jpeg","webp")
            while (pending.isNotEmpty() && loaded < 2500 && totalBytes < maxFolderBytes) {
                val folder = pending.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId)
                contentResolver.query(childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext() && loaded < 2500) {
                        val childId = cursor.getString(idColumn)
                        val childName = cursor.getString(nameColumn) ?: continue
                        val mime = cursor.getString(typeColumn) ?: "application/octet-stream"
                        val relative = if (folder.path.isEmpty()) childName else folder.path + "/" + childName
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(Folder(childId, relative))
                        } else {
                            val extension = childName.substringAfterLast('.', "").lowercase()
                            val declaredSize = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else -1L
                            if (extension in allowedExtensions && declaredSize <= maxSingleAsset &&
                                (declaredSize < 0L || totalBytes + declaredSize <= maxFolderBytes)) {
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                val bytes = contentResolver.openInputStream(childUri)?.use { it.readBytes() }
                                if (bytes != null && bytes.size.toLong() <= maxSingleAsset &&
                                    totalBytes + bytes.size <= maxFolderBytes) {
                                    externalAssets[relative] = bytes
                                    externalAssets[relative.lowercase()] = bytes
                                    externalAssets[childName] = bytes
                                    externalAssets[childName.lowercase()] = bytes
                                    loaded++
                                    totalBytes += bytes.size
                                }
                            }
                        }
                    }
                }
            }
            toast("Carpeta vinculada: " + loaded + " recursos encontrados")
        } catch (error: Exception) {
            toast("No se pudo leer la carpeta: " + error.message)
        }
    }

    private fun importFromUri(uri:Uri) {
        try {
            val name=displayName(uri)
            val bytes=contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el archivo.")
            if (name.substringAfterLast('.',"asset").equals("gme", true)) {
                recordUndo()
                project=ProjectCodec.decode(String(bytes,Charsets.UTF_8))
                selectedId=project.scene.nodes.firstOrNull()?.id
                toast("Proyecto GME cargado"); openEditor("Scene")
                return
            }
            val imported = AssetImporter.import(name, bytes, ::resolveExternalAsset)
            require(imported.parts.isNotEmpty()) { "El archivo no contiene mallas importables." }
            recordUndo()
            val nodeIdsBySource = mutableMapOf<Int,String>()
            val addedNodes = mutableListOf<Pair<ImportedPart,SceneNode>>()
            imported.parts.forEach { part ->
                val id = UUID.randomUUID().toString()
                val node = SceneNode(id, part.name, part.transform, part.mesh, part.material)
                project.scene.nodes.add(node)
                project.primitiveKinds[id] = "IMPORTED"
                if (part.nodeIndex >= 0 && !nodeIdsBySource.containsKey(part.nodeIndex)) nodeIdsBySource[part.nodeIndex] = id
                addedNodes.add(part to node)
                selectedId = id
            }
            val hierarchyNodes = mutableListOf<Pair<ImportedHierarchyNode,SceneNode>>()
            imported.hierarchyNodes.forEach { hierarchy ->
                if (!nodeIdsBySource.containsKey(hierarchy.sourceNodeIndex)) {
                    val id=UUID.randomUUID().toString()
                    val empty=SceneNode(
                        id,
                        hierarchy.name,
                        hierarchy.transform.copy(
                            position=hierarchy.transform.position.copy(),
                            rotationEuler=hierarchy.transform.rotationEuler.copy(),
                            scale=hierarchy.transform.scale.copy()
                        )
                    )
                    project.scene.nodes.add(empty)
                    project.primitiveKinds[id]="EMPTY"
                    nodeIdsBySource[hierarchy.sourceNodeIndex]=id
                    hierarchyNodes.add(hierarchy to empty)
                    selectedId=id
                }
            }
            project.rigs.addAll(imported.rigs)
            addedNodes.forEach { (part,node) ->
                if (part.rigIndex in imported.rigs.indices) {
                    val rig = imported.rigs[part.rigIndex]
                    if (rig.boundMeshId == null) rig.boundMeshId = node.id
                    if (node.id !in rig.boundMeshIds) rig.boundMeshIds.add(node.id)
                }
            }
            project.scene.cameras.addAll(imported.cameras)
            project.lights.addAll(imported.lights)
            imported.rigs.forEach { rig ->
                val boneProxyIds = mutableListOf<String>()
                rig.bones.forEach { bone ->
                    val existingId=nodeIdsBySource[bone.sourceNodeIndex]
                    val existing=existingId?.let { sourceId -> project.scene.nodes.firstOrNull { it.id==sourceId } }
                    val reusable=existing != null && existing.mesh == null
                    val id=if(reusable) existing!!.id else UUID.randomUUID().toString()
                    val parentId=boneProxyIds.getOrNull(bone.parentIndex)
                    val transform=bone.restTransform.copy(
                        position=bone.restTransform.position.copy(),
                        rotationEuler=bone.restTransform.rotationEuler.copy(),
                        scale=bone.restTransform.scale.copy()
                    )
                    val proxy=if(reusable) {
                        existing!!.name=rig.name + " / " + bone.name
                        existing.transform=transform
                        existing.mesh=boneSegmentMesh(bone.tail)
                        existing.material=MaterialData("Bone",Vec3(0.98f,0.55f,0.22f))
                        existing.parentId=parentId
                        existing
                    } else {
                        SceneNode(id,rig.name + " / " + bone.name,transform,
                            boneSegmentMesh(bone.tail),MaterialData("Bone",Vec3(0.98f,0.55f,0.22f)),parentId=parentId)
                            .also { project.scene.nodes.add(it) }
                    }
                    project.primitiveKinds[id]="BONE_PROXY"
                    boneProxyIds.add(id)
                    bone.proxyNodeId=id
                    if(bone.sourceNodeIndex>=0) nodeIdsBySource[bone.sourceNodeIndex]=id
                }
            }
            addedNodes.forEach { (part,node) ->
                if (part.parentNodeIndex >= 0) {
                    val parentNode = nodeIdsBySource[part.parentNodeIndex]
                    if (parentNode != null && parentNode != node.id) node.parentId = parentNode
                }
            }
            hierarchyNodes.forEach { (hierarchy,node) ->
                if (hierarchy.parentNodeIndex >= 0) {
                    val parentNode=nodeIdsBySource[hierarchy.parentNodeIndex]
                    if (parentNode != null && parentNode != node.id) node.parentId=parentNode
                }
            }
            imported.animations.forEach { importedClip ->
                val mapped = AnimationClip(importedClip.name, importedClip.duration)
                importedClip.tracks.forEach { sourceTrack ->
                    val sourceIndex = sourceTrack.nodeId.substringAfter("gltfNode:", "").toIntOrNull()
                    val targetId = sourceIndex?.let { nodeIdsBySource[it] }
                    if (targetId != null) mapped.tracks.add(AnimationTrack(targetId, sourceTrack.keys.toMutableList()))
                }
                if (mapped.tracks.isNotEmpty()) project.scene.animations.add(mapped)
            }
            pushSceneToRenderer()
            toast("Importados " + imported.parts.size + " mallas, " + hierarchyNodes.size + " nodos y " + imported.rigs.size + " armatures")
            openEditor(if (imported.rigs.isNotEmpty()) "Rig" else "Scene")
        } catch(e:Exception) {
            AlertDialog.Builder(this).setTitle("No se pudo importar").setMessage(e.message ?: "Formato no compatible.")
                .setPositiveButton("Entendido",null).show()
        }
    }
    private fun exportCompleteSceneTo(uri: Uri) {
        try {
            val bytes = GltfSceneExporter.encode(project, saveKind == "scene_glb")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No se pudo escribir el proyecto exportado.")
            toast("Escena completa exportada correctamente")
        } catch (error: Exception) {
            toast("Error exportando escena: " + error.message)
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
                "ply" -> PlyCodec.encode(mesh).toByteArray(Charsets.UTF_8)
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
    override fun onPause() {
        saveAutosave()
        super.onPause()
        if(::glView.isInitialized) glView.onPause()
        stopTasks()
    }
    override fun onResume() { super.onResume(); if(::glView.isInitialized) glView.onResume() }
}

private class GemaRenderer : GLSurfaceView.Renderer {
    @Volatile var items:List<RenderItem> = emptyList()
    @Volatile var lights:List<EditorLight> = emptyList()
    @Volatile var effects:RenderEffects = RenderEffects()
    private var program=0
    private val projection=FloatArray(16)
    private val view=FloatArray(16)
    private val model=FloatArray(16)
    private val viewModel=FloatArray(16)
    private val mvp=FloatArray(16)
    @Volatile private var yaw=38f
    @Volatile private var pitch=22f
    @Volatile private var distance=8f
    @Volatile private var fieldOfView=45f
    @Volatile private var nearClip=0.1f
    @Volatile private var farClip=150f
    @Volatile private var target=Vec3()
    private var grid:FloatBuffer?=null
    private data class Cache(val hash:Int,val buffer:FloatBuffer,val count:Int)
    private val meshes=HashMap<String,Cache>()
    private val wireMeshes=HashMap<String,Cache>()
    private val normalMeshes=HashMap<String,Cache>()
    private val tangentMeshes=HashMap<String,Cache>()
    private val textures=HashMap<String,Int>()
    private var viewportWidth=1f
    private var viewportHeight=1f
    private var shaderError="Shader inválido"

    fun orbit(dx:Float,dy:Float) { yaw+=dx*0.45f; pitch=(pitch+dy*0.35f).coerceIn(-80f,80f) }
    fun zoom(factor:Float) { distance=(distance/factor.coerceIn(0.5f,2f)).coerceIn(2.2f,25f) }
    fun setCameraTarget(v:Vec3) { target=v.copy() }
    fun setCameraSettings(fov:Float,near:Float,far:Float) {
        fieldOfView=fov.coerceIn(15f,110f)
        nearClip=near.coerceAtLeast(0.001f)
        farClip=far.coerceAtLeast(nearClip+1f)
    }
    fun setViewPreset(preset: String) {
        when (preset) {
            "Front" -> { yaw=0f; pitch=0f; distance=8f }
            "Side" -> { yaw=90f; pitch=0f; distance=8f }
            "Top" -> { yaw=0f; pitch=82f; distance=8f }
            else -> { yaw=38f; pitch=22f; distance=8f }
        }
    }
    fun pan(dx:Float,dy:Float) {
        val yawRad=Math.toRadians(yaw.toDouble())
        val pitchRad=Math.toRadians(pitch.toDouble())
        val right=Vec3(kotlin.math.cos(yawRad).toFloat(),0f,-kotlin.math.sin(yawRad).toFloat())
        val up=Vec3(-kotlin.math.sin(pitchRad).toFloat()*kotlin.math.sin(yawRad).toFloat(),
            kotlin.math.cos(pitchRad).toFloat(),
            -kotlin.math.sin(pitchRad).toFloat()*kotlin.math.cos(yawRad).toFloat())
        val scale=distance/viewportHeight.coerceAtLeast(1f)*1.6f
        target=target + right*(-dx*scale) + up*(dy*scale)
    }

    fun projectPoint(transform: Transform, width: Float, height: Float): Pair<Float,Float>? {
        if (width <= 0f || height <= 0f) return null
        val yawRad=Math.toRadians(yaw.toDouble())
        val pitchRad=Math.toRadians(pitch.toDouble())
        val cx=target.x+distance*kotlin.math.cos(pitchRad).toFloat()*kotlin.math.sin(yawRad).toFloat()
        val cy=target.y+distance*kotlin.math.sin(pitchRad).toFloat()
        val cz=target.z+distance*kotlin.math.cos(pitchRad).toFloat()*kotlin.math.cos(yawRad).toFloat()
        var fx=target.x-cx; var fy=target.y-cy; var fz=target.z-cz
        val fl=kotlin.math.sqrt(fx*fx+fy*fy+fz*fz).coerceAtLeast(0.0001f)
        fx/=fl; fy/=fl; fz/=fl
        var rx=fz; var rz=-fx
        val rl=kotlin.math.sqrt(rx*rx+rz*rz).coerceAtLeast(0.0001f)
        rx/=rl; rz/=rl
        val ux= -rz*fy
        val uy= rz*fx-rx*fz
        val uz= rx*fy
        val px=transform.position.x-cx; val py=transform.position.y-cy; val pz=transform.position.z-cz
        val depth=px*fx+py*fy+pz*fz
        if (depth <= 0.1f) return null
        val tangent=kotlin.math.tan(Math.toRadians(22.5)).toFloat()
        val aspect=width/height
        val nx=(px*rx+py*0f+pz*rz)/(depth*tangent*aspect)
        val ny=(px*ux+py*uy+pz*uz)/(depth*tangent)
        return Pair((nx+1f)*0.5f*width,(1f-ny)*0.5f*height)
    }

    fun projectAxes(transform: Transform, width: Float, height: Float, local: Boolean): List<GizmoAxis>? {
        val start=projectPoint(transform,width,height) ?: return null
        val basis = listOf(Vec3(1f,0f,0f),Vec3(0f,1f,0f),Vec3(0f,0f,1f))
        val rotation=FloatArray(16)
        Matrix.setIdentityM(rotation,0)
        if (local) {
            Matrix.rotateM(rotation,0,transform.rotationEuler.x,1f,0f,0f)
            Matrix.rotateM(rotation,0,transform.rotationEuler.y,0f,1f,0f)
            Matrix.rotateM(rotation,0,transform.rotationEuler.z,0f,0f,1f)
        }
        return basis.map { axis ->
            val endpoint = if (local) {
                val out=FloatArray(4)
                Matrix.multiplyMV(out,0,rotation,0,floatArrayOf(axis.x,axis.y,axis.z,0f),0)
                Vec3(out[0],out[1],out[2])
            } else axis
            val end=projectPoint(Transform(transform.position+endpoint*1.25f,
                transform.rotationEuler,transform.scale),width,height) ?: return null
            GizmoAxis(start.first,start.second,end.first,end.second)
        }
    }

    fun pickFace(x:Float,y:Float,width:Float,height:Float):Int? {
        val selected=items.firstOrNull { it.selected } ?: return null
        if(width<=0f || height<=0f) return null
        var nearest:Int?=null
        var best=48f*48f
        for(face in 0 until selected.vertices.size/9) {
            val points=mutableListOf<Pair<Float,Float>>()
            for(corner in 0..2) {
                val offset=(face*3+corner)*3
                val local=Vec3(selected.vertices[offset],selected.vertices[offset+1],selected.vertices[offset+2])
                val world=transformPointForPick(local,Transform(selected.position,selected.rotation,selected.scale))
                val screen=projectPoint(Transform(position=world),width,height) ?: continue
                points.add(screen)
            }
            if(points.size!=3) continue
            val centerX=(points[0].first+points[1].first+points[2].first)/3f
            val centerY=(points[0].second+points[1].second+points[2].second)/3f
            val dx=centerX-x; val dy=centerY-y
            val distanceSquared=dx*dx+dy*dy
            if(distanceSquared<best) {
                best=distanceSquared
                nearest=face
            }
        }
        return nearest
    }

    fun pickVertex(x:Float,y:Float,width:Float,height:Float):Int? {
        val selected=items.firstOrNull { it.selected } ?: return null
        if(width<=0f || height<=0f) return null
        var nearest:Int?=null
        var best=26f*26f
        val visited=hashSetOf<Int>()
        for(i in selected.sourceVertexIndices.indices) {
            val sourceIndex=selected.sourceVertexIndices[i]
            if(!visited.add(sourceIndex)) continue
            val offset=i*3
            if(offset+2>=selected.vertices.size) continue
            val local=Vec3(selected.vertices[offset],selected.vertices[offset+1],selected.vertices[offset+2])
            val transform=Transform(
                position=selected.position,
                rotationEuler=selected.rotation,
                scale=selected.scale
            )
            val world=transformPointForPick(local,transform)
            val screen=projectPoint(Transform(position=world),width,height) ?: continue
            val dx=screen.first-x; val dy=screen.second-y
            val distanceSquared=dx*dx+dy*dy
            if(distanceSquared<best) {
                best=distanceSquared
                nearest=sourceIndex
            }
        }
        return nearest
    }

    private fun transformPointForPick(point:Vec3,transform:Transform):Vec3 {
        val model=FloatArray(16)
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,transform.position.x,transform.position.y,transform.position.z)
        Matrix.rotateM(model,0,transform.rotationEuler.x,1f,0f,0f)
        Matrix.rotateM(model,0,transform.rotationEuler.y,0f,1f,0f)
        Matrix.rotateM(model,0,transform.rotationEuler.z,0f,0f,1f)
        Matrix.scaleM(model,0,transform.scale.x,transform.scale.y,transform.scale.z)
        val output=FloatArray(4)
        Matrix.multiplyMV(output,0,model,0,floatArrayOf(point.x,point.y,point.z,1f),0)
        return Vec3(output[0],output[1],output[2])
    }

    fun pick(x: Float, y: Float, width: Float, height: Float): String? {
        if (width <= 0f || height <= 0f) return null
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val cx = target.x + distance * kotlin.math.cos(pitchRad).toFloat() * kotlin.math.sin(yawRad).toFloat()
        val cy = target.y + distance * kotlin.math.sin(pitchRad).toFloat()
        val cz = target.z + distance * kotlin.math.cos(pitchRad).toFloat() * kotlin.math.cos(yawRad).toFloat()
        var fx = target.x - cx; var fy = target.y - cy; var fz = target.z - cz
        val fl = kotlin.math.sqrt(fx*fx+fy*fy+fz*fz).coerceAtLeast(0.0001f)
        fx /= fl; fy /= fl; fz /= fl
        var rx = fz; var ry = 0f; var rz = -fx
        val rl = kotlin.math.sqrt(rx*rx+rz*rz).coerceAtLeast(0.0001f)
        rx /= rl; rz /= rl
        val ux = ry*fz-rz*fy
        val uy = rz*fx-rx*fz
        val uz = rx*fy-ry*fx
        val aspect = width / height
        val tangent = kotlin.math.tan(Math.toRadians(22.5)).toFloat()
        var bestId: String? = null
        var bestDistance = 52f * 52f
        var nearestDepth = Float.MAX_VALUE
        val drawItems=items.sortedWith(
            compareBy<RenderItem> { if(it.alphaMode=="BLEND") 1 else 0 }
                .thenByDescending { item ->
                    val dx=item.position.x-cx; val dy=item.position.y-cy; val dz=item.position.z-cz
                    dx*dx+dy*dy+dz*dz
                }
        )
        drawItems.forEach { item ->
            val px = item.position.x-cx; val py = item.position.y-cy; val pz = item.position.z-cz
            val depth = px*fx+py*fy+pz*fz
            if (depth <= 0.1f) return@forEach
            val nx = (px*rx+py*ry+pz*rz) / (depth*tangent*aspect)
            val ny = (px*ux+py*uy+pz*uz) / (depth*tangent)
            val sx = (nx+1f)*0.5f*width
            val sy = (1f-ny)*0.5f*height
            val dx=sx-x; val dy=sy-y
            val distanceSquared=dx*dx+dy*dy
            if (distanceSquared < bestDistance || (distanceSquared <= bestDistance && depth < nearestDepth)) {
                bestDistance=distanceSquared
                nearestDepth=depth
                bestId=item.id
            }
        }
        return bestId
    }

    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        GLES20.glClearColor(0.047f,0.064f,0.082f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        program=createProgram(ShaderLibrary.DEFAULT_FRAGMENT)
        grid=makeBuffer(gridVertices())
        textures.clear()
    }
    override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int) {
        viewportWidth=width.toFloat()
        viewportHeight=height.coerceAtLeast(1).toFloat()
        GLES20.glViewport(0,0,width,height.coerceAtLeast(1))
        Matrix.perspectiveM(projection,0,fieldOfView,width.toFloat()/height.coerceAtLeast(1),nearClip,farClip)
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
        Matrix.perspectiveM(projection,0,fieldOfView,viewportWidth/viewportHeight.coerceAtLeast(1f),nearClip,farClip)
        GLES20.glUseProgram(program)
        val position=GLES20.glGetAttribLocation(program,"aPosition")
        val normalLocation=GLES20.glGetAttribLocation(program,"aNormal")
        val tangentLocation=GLES20.glGetAttribLocation(program,"aTangent")
        val modelLocation=GLES20.glGetUniformLocation(program,"uModel")
        val normalMatrixLocation=GLES20.glGetUniformLocation(program,"uNormalMatrix")
        val matrix=GLES20.glGetUniformLocation(program,"uMvp")
        val color=GLES20.glGetUniformLocation(program,"uColor")
        val uvLocation=GLES20.glGetAttribLocation(program,"aTexCoord")
        val textureLocation=GLES20.glGetUniformLocation(program,"uTexture")
        val useTextureLocation=GLES20.glGetUniformLocation(program,"uUseTexture")
        val normalTextureLocation=GLES20.glGetUniformLocation(program,"uNormalTexture")
        val useNormalTextureLocation=GLES20.glGetUniformLocation(program,"uUseNormalTexture")
        val normalScaleLocation=GLES20.glGetUniformLocation(program,"uNormalScale")
        val mrTextureLocation=GLES20.glGetUniformLocation(program,"uMetallicRoughnessTexture")
        val useMrTextureLocation=GLES20.glGetUniformLocation(program,"uUseMetallicRoughnessTexture")
        val occlusionTextureLocation=GLES20.glGetUniformLocation(program,"uOcclusionTexture")
        val useOcclusionTextureLocation=GLES20.glGetUniformLocation(program,"uUseOcclusionTexture")
        val emissiveTextureLocation=GLES20.glGetUniformLocation(program,"uEmissiveTexture")
        val useEmissiveTextureLocation=GLES20.glGetUniformLocation(program,"uUseEmissiveTexture")
        val roughnessLocation=GLES20.glGetUniformLocation(program,"uRoughness")
        val metallicLocation=GLES20.glGetUniformLocation(program,"uMetallic")
        val emissiveLocation=GLES20.glGetUniformLocation(program,"uEmissive")
        val alphaModeLocation=GLES20.glGetUniformLocation(program,"uAlphaMode")
        val alphaCutoffLocation=GLES20.glGetUniformLocation(program,"uAlphaCutoff")
        val cameraLocation=GLES20.glGetUniformLocation(program,"uCameraPosition")
        val fogEnabledLocation=GLES20.glGetUniformLocation(program,"uFogEnabled")
        val fogDensityLocation=GLES20.glGetUniformLocation(program,"uFogDensity")
        val fogColorLocation=GLES20.glGetUniformLocation(program,"uFogColor")
        val vignetteEnabledLocation=GLES20.glGetUniformLocation(program,"uVignetteEnabled")
        val vignetteStrengthLocation=GLES20.glGetUniformLocation(program,"uVignetteStrength")
        val viewportLocation=GLES20.glGetUniformLocation(program,"uViewport")
        val lightCountLocation=GLES20.glGetUniformLocation(program,"uLightCount")
        val lightPositionLocation=GLES20.glGetUniformLocation(program,"uLightPosition[0]")
        val lightColorLocation=GLES20.glGetUniformLocation(program,"uLightColor[0]")
        val lightIntensityLocation=GLES20.glGetUniformLocation(program,"uLightIntensity[0]")
        val lightTypeLocation=GLES20.glGetUniformLocation(program,"uLightType[0]")
        Matrix.setIdentityM(model,0)
        Matrix.multiplyMM(viewModel,0,view,0,model,0)
        Matrix.multiplyMM(mvp,0,projection,0,viewModel,0)
        GLES20.glUniformMatrix4fv(matrix,1,false,mvp,0)
        if (modelLocation >= 0) GLES20.glUniformMatrix4fv(modelLocation,1,false,model,0)
        GLES20.glUniform4f(color,0.17f,0.23f,0.29f,1f)
        if (useTextureLocation >= 0) GLES20.glUniform1f(useTextureLocation,0f)
        if (useNormalTextureLocation >= 0) GLES20.glUniform1f(useNormalTextureLocation,0f)
        if (useMrTextureLocation >= 0) GLES20.glUniform1f(useMrTextureLocation,0f)
        if (useOcclusionTextureLocation >= 0) GLES20.glUniform1f(useOcclusionTextureLocation,0f)
        if (useEmissiveTextureLocation >= 0) GLES20.glUniform1f(useEmissiveTextureLocation,0f)
        if (textureLocation >= 0) GLES20.glUniform1i(textureLocation,0)
        if (normalTextureLocation >= 0) GLES20.glUniform1i(normalTextureLocation,1)
        if (mrTextureLocation >= 0) GLES20.glUniform1i(mrTextureLocation,2)
        if (occlusionTextureLocation >= 0) GLES20.glUniform1i(occlusionTextureLocation,3)
        if (emissiveTextureLocation >= 0) GLES20.glUniform1i(emissiveTextureLocation,4)
        if (normalScaleLocation >= 0) GLES20.glUniform1f(normalScaleLocation,1f)
        if (roughnessLocation >= 0) GLES20.glUniform1f(roughnessLocation,1f)
        if (metallicLocation >= 0) GLES20.glUniform1f(metallicLocation,0f)
        if (emissiveLocation >= 0) GLES20.glUniform3f(emissiveLocation,0f,0f,0f)
        if (alphaModeLocation >= 0) GLES20.glUniform1f(alphaModeLocation,0f)
        if (alphaCutoffLocation >= 0) GLES20.glUniform1f(alphaCutoffLocation,0.5f)
        if (normalLocation >= 0) GLES20.glVertexAttrib3f(normalLocation,0f,1f,0f)
        if (tangentLocation >= 0) GLES20.glVertexAttrib4f(tangentLocation,1f,0f,0f,1f)
        if (cameraLocation >= 0) GLES20.glUniform3f(cameraLocation,cx,cy,cz)
        if (fogEnabledLocation >= 0) GLES20.glUniform1f(fogEnabledLocation,if(effects.fog) 1f else 0f)
        if (fogDensityLocation >= 0) GLES20.glUniform1f(fogDensityLocation,0.055f)
        if (fogColorLocation >= 0) GLES20.glUniform3f(fogColorLocation,0.12f,0.17f,0.22f)
        if (vignetteEnabledLocation >= 0) GLES20.glUniform1f(vignetteEnabledLocation,if(effects.vignette) 1f else 0f)
        if (vignetteStrengthLocation >= 0) GLES20.glUniform1f(vignetteStrengthLocation,0.62f)
        if (viewportLocation >= 0) GLES20.glUniform2f(viewportLocation,viewportWidth,viewportHeight)
        val renderLights = lights.take(4).ifEmpty {
            listOf(EditorLight("Default Sun","DIRECTIONAL",Vec3(-0.45f,0.8f,0.55f),Vec3(1f,1f,1f),1f))
        }
        if (lightCountLocation >= 0) GLES20.glUniform1i(lightCountLocation,renderLights.size)
        val lightPositions=FloatArray(renderLights.size*3)
        val lightColors=FloatArray(renderLights.size*3)
        val lightIntensities=FloatArray(renderLights.size)
        val lightTypes=FloatArray(renderLights.size)
        renderLights.forEachIndexed { index,light ->
            lightPositions[index*3]=light.position.x
            lightPositions[index*3+1]=light.position.y
            lightPositions[index*3+2]=light.position.z
            lightColors[index*3]=light.color.x
            lightColors[index*3+1]=light.color.y
            lightColors[index*3+2]=light.color.z
            lightIntensities[index]=light.intensity.coerceAtLeast(0f)
            lightTypes[index]=if(light.kind=="DIRECTIONAL") 0f else 1f
        }
        if(lightPositionLocation>=0) GLES20.glUniform3fv(lightPositionLocation,renderLights.size,lightPositions,0)
        if(lightColorLocation>=0) GLES20.glUniform3fv(lightColorLocation,renderLights.size,lightColors,0)
        if(lightIntensityLocation>=0) GLES20.glUniform1fv(lightIntensityLocation,renderLights.size,lightIntensities,0)
        if(lightTypeLocation>=0) GLES20.glUniform1fv(lightTypeLocation,renderLights.size,lightTypes,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (uvLocation >= 0) GLES20.glVertexAttrib2f(uvLocation,0f,0f)
        grid?.let {
            it.position(0); GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,it)
            GLES20.glDrawArrays(GLES20.GL_LINES,0,it.capacity()/3)
            GLES20.glDisableVertexAttribArray(position)
        }
        val drawItems = items.sortedWith(
            compareBy<RenderItem> { it.alphaMode == "BLEND" }
                .thenByDescending { item ->
                    val dx=item.position.x-cx
                    val dy=item.position.y-cy
                    val dz=item.position.z-cz
                    dx*dx+dy*dy+dz*dz
                }
        )
        drawItems.forEach { item ->
            if(item.vertices.isEmpty()) return@forEach
            val hash=item.vertices.contentHashCode()
            var cache=meshes[item.id]
            if(cache==null || cache.hash!=hash) {
                cache=Cache(hash,makeBuffer(item.vertices),item.vertices.size/3)
                meshes[item.id]=cache
            }
            val normalHash=item.normals.contentHashCode()
            var normalCache=normalMeshes[item.id]
            if(normalCache==null || normalCache.hash!=normalHash) {
                normalCache=Cache(normalHash,makeBuffer(item.normals),item.normals.size/3)
                normalMeshes[item.id]=normalCache
            }
            val tangentHash=item.tangents.contentHashCode()
            var tangentCache=tangentMeshes[item.id]
            if(tangentCache==null || tangentCache.hash!=tangentHash) {
                tangentCache=Cache(tangentHash,makeBuffer(item.tangents),item.tangents.size/4)
                tangentMeshes[item.id]=tangentCache
            }
            val wireHash=item.wireVertices.contentHashCode()
            var wireCache=wireMeshes[item.id]
            if(wireCache==null || wireCache.hash!=wireHash) {
                wireCache=Cache(wireHash,makeBuffer(item.wireVertices),item.wireVertices.size/3)
                wireMeshes[item.id]=wireCache
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
            if (modelLocation >= 0) GLES20.glUniformMatrix4fv(modelLocation,1,false,model,0)
            if (normalMatrixLocation >= 0) {
                val inverseModel=FloatArray(16)
                val normalMatrix=FloatArray(16)
                if (Matrix.invertM(inverseModel,0,model,0)) {
                    Matrix.transposeM(normalMatrix,0,inverseModel,0)
                    GLES20.glUniformMatrix4fv(normalMatrixLocation,1,false,normalMatrix,0)
                } else {
                    Matrix.setIdentityM(normalMatrix,0)
                    GLES20.glUniformMatrix4fv(normalMatrixLocation,1,false,normalMatrix,0)
                }
            }
            val tint=if(item.selected) 1.16f else 1f
            if (!item.wireframe) {
                val baseTextureId=textureFor(item.textureUri)
                val normalTextureId=textureFor(item.normalTextureUri)
                val mrTextureId=textureFor(item.metallicRoughnessTextureUri)
                val occlusionTextureId=textureFor(item.occlusionTextureUri)
                val emissiveTextureId=textureFor(item.emissiveTextureUri)
                GLES20.glUniform4f(color,(item.color.x*tint).coerceIn(0f,1f),
                    (item.color.y*tint).coerceIn(0f,1f),(item.color.z*tint).coerceIn(0f,1f),item.alpha.coerceIn(0f,1f))
                if (roughnessLocation >= 0) GLES20.glUniform1f(roughnessLocation,item.roughness.coerceIn(0.04f,1f))
                if (metallicLocation >= 0) GLES20.glUniform1f(metallicLocation,item.metallic.coerceIn(0f,1f))
                if (emissiveLocation >= 0) GLES20.glUniform3f(emissiveLocation,item.emissive.x,item.emissive.y,item.emissive.z)
                if (alphaModeLocation >= 0) GLES20.glUniform1f(alphaModeLocation,if(item.alphaMode=="MASK") 1f else 0f)
                if (alphaCutoffLocation >= 0) GLES20.glUniform1f(alphaCutoffLocation,item.alphaCutoff)
                fun bindTexture(unit:Int,textureId:Int,sampler:Int,use:Int) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textureId)
                    if(sampler>=0) GLES20.glUniform1i(sampler,unit)
                    if(use>=0) GLES20.glUniform1f(use,if(textureId!=0) 1f else 0f)
                }
                bindTexture(0,baseTextureId,textureLocation,useTextureLocation)
                bindTexture(1,normalTextureId,normalTextureLocation,useNormalTextureLocation)
                bindTexture(2,mrTextureId,mrTextureLocation,useMrTextureLocation)
                bindTexture(3,occlusionTextureId,occlusionTextureLocation,useOcclusionTextureLocation)
                bindTexture(4,emissiveTextureId,emissiveTextureLocation,useEmissiveTextureLocation)
                if(normalScaleLocation>=0) GLES20.glUniform1f(normalScaleLocation,item.normalScale)
                if(item.alphaMode=="BLEND") {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glDepthMask(false)
                } else {
                    GLES20.glDisable(GLES20.GL_BLEND)
                    GLES20.glDepthMask(true)
                }
                cache!!.buffer.position(0)
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,cache.buffer)
                if (uvLocation >= 0) {
                    val uvBuffer = makeBuffer(item.texCoords)
                    GLES20.glEnableVertexAttribArray(uvLocation)
                    GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,0,uvBuffer)
                }
                if (normalLocation >= 0) {
                    normalCache!!.buffer.position(0)
                    GLES20.glEnableVertexAttribArray(normalLocation)
                    GLES20.glVertexAttribPointer(normalLocation,3,GLES20.GL_FLOAT,false,0,normalCache.buffer)
                }
                if (tangentLocation >= 0) {
                    tangentCache!!.buffer.position(0)
                    GLES20.glEnableVertexAttribArray(tangentLocation)
                    GLES20.glVertexAttribPointer(tangentLocation,4,GLES20.GL_FLOAT,false,0,tangentCache.buffer)
                }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,cache.count)
                if (tangentLocation >= 0) GLES20.glDisableVertexAttribArray(tangentLocation)
                if (normalLocation >= 0) GLES20.glDisableVertexAttribArray(normalLocation)
                if (uvLocation >= 0) GLES20.glDisableVertexAttribArray(uvLocation)
                GLES20.glDisableVertexAttribArray(position)
            }
            if (item.wireframe || (item.selected && item.outlines)) {
                GLES20.glUniform4f(color,if (item.selected) 0.22f else 0.38f,
                    if (item.selected) 0.82f else 0.48f,if (item.selected) 1f else 0.58f,1f)
                wireCache!!.buffer.position(0)
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position,3,GLES20.GL_FLOAT,false,0,wireCache.buffer)
                GLES20.glDrawArrays(GLES20.GL_LINES,0,wireCache.count)
                GLES20.glDisableVertexAttribArray(position)
            }
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }
    private fun textureFor(uri: String?): Int {
        if (uri.isNullOrBlank() || !uri.startsWith("data:") || !uri.contains("base64,")) return 0
        textures[uri]?.let { return it }
        return try {
            val payload = uri.substringAfter("base64,")
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            val sample = maxOf(1,
                maxOf(bounds.outWidth,bounds.outHeight).let { dimension ->
                    var factor=1
                    while(dimension/factor>2048) factor*=2
                    factor
                })
            val options=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: return 0
            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            val texture = names[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            bitmap.recycle()
            textures[uri] = texture
            texture
        } catch (_: Exception) {
            0
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
            attribute vec3 aNormal;
            attribute vec4 aTangent;
            attribute vec2 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform mat4 uNormalMatrix;
            varying vec2 vTexCoord;
            varying vec3 vNormal;
            varying vec3 vTangent;
            varying float vTangentHandedness;
            varying vec3 vWorldPosition;
            void main() {
                vec4 worldPosition = uModel * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vNormal = normalize(mat3(uNormalMatrix) * aNormal);
                vTangent = normalize(mat3(uModel) * aTangent.xyz);
                vTangentHandedness = aTangent.w;
                vWorldPosition = worldPosition.xyz;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
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
