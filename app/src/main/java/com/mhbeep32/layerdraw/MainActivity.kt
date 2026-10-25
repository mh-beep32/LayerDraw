package com.mhbeep32.layerdraw

import android.app.Activity
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.graphics.*
import android.view.*
import android.widget.*
import android.content.Intent
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {

    data class Layer(var bmp: Bitmap, var x: Float, var y: Float, var w: Float, var h: Float)
    data class Stroke(val path: Path, val color: Int, val width: Float)


    private lateinit var board: Board
    private lateinit var bar: LinearLayout
    private var hidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(245,245,245)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245,245,245))
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(5,5,5,5)
            setBackgroundColor(Color.rgb(250,250,250))
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1,56))

        board = Board()
        root.addView(board, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)

        addButton("＋ صورة") {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(i, 10)
        }
        addButton("✎ قلم") { board.mode = "pen"; board.invalidate() }
        addButton("➤ سهم") { board.mode = "arrow"; board.invalidate() }
        addButton("△ مثلث") { board.mode = "triangle"; board.invalidate() }
        addButton("◻ مستطيل") { board.mode = "rect"; board.invalidate() }
        addButton("⌫ ممحاة") { board.mode = "eraser"; board.invalidate() }
        addButton("🔴 أحمر") { board.ink = Color.RED }
        addButton("⚫ أسود") { board.ink = Color.BLACK }
        addButton("↶ تراجع") { board.undo(); board.invalidate() }
        addButton("مسح الرسم") { board.clearInk(); board.invalidate() }
        addButton("إظهار/إخفاء") {
            hidden = !hidden
            bar.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        val b = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(15,10,15,10)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(2, Color.LTGRAY)
                cornerRadius = 22f
            }
            setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(-2,48)
        lp.setMargins(3,0,3,0)
        bar.addView(b,lp)
    }

    @Deprecated("Legacy image picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 10 && resultCode == Activity.RESULT_OK) {
            try {
                val uri = data?.data ?: return
                contentResolver.openInputStream(uri)?.use {
                    val bmp = BitmapFactory.decodeStream(it) ?: return
                    board.addLayer(bmp)
                }
            } catch (_: Exception) {
                Toast.makeText(this,"تعذّر فتح الصورة",Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class Board : View(this@MainActivity) {
        var mode = "pen"
        var ink = Color.RED


        private val layers = mutableListOf<Layer>()
        private val strokes = mutableListOf<Stroke>()
        private val shapes = mutableListOf<Pair<String, Pair<PointF,PointF>>>()
        private var current: Path? = null
        private var start = PointF()
        private var last = PointF()
        private var moving: Layer? = null
        private var moveDX = 0f
        private var moveDY = 0f

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setBackgroundColor(Color.WHITE)
        }

        fun addLayer(b: Bitmap) {
            val maxW = width * 0.65f
            val maxH = height * 0.65f
            val scale = minOf(maxW / b.width, maxH / b.height, 1f)
            val w = b.width * scale
            val h = b.height * scale
            layers.add(Layer(b, (width-w)/2, (height-h)/2, w, h))
            invalidate()
        }

        fun undo() {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            else if (shapes.isNotEmpty()) shapes.removeAt(shapes.lastIndex)
            else if (layers.isNotEmpty()) layers.removeAt(layers.lastIndex)
        }

        fun clearInk() {
            strokes.clear()
            shapes.clear()
            current = null
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.WHITE)

            for (layer in layers) {
                val dst = RectF(layer.x,layer.y,layer.x+layer.w,layer.y+layer.h)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                c.drawBitmap(layer.bmp,null,dst,paint)
            }

            for (s in strokes) {
                p.color = s.color
                p.strokeWidth = s.width
                c.drawPath(s.path,p)
            }
            for ((kind,pts) in shapes) drawShape(c,kind,pts.first,pts.second)
            current?.let {
                p.color = if (mode == "eraser") Color.WHITE else ink
                p.strokeWidth = if (mode == "eraser") 32f else 5f
                c.drawPath(it,p)
            }
            if (mode == "pen" || mode == "eraser") return
            if (isDrawing) drawShape(c,mode,start,PointF(last.x,last.y))
        }

        private var isDrawing = false

        private fun drawShape(c: Canvas, kind: String, a: PointF, b: PointF) {
            p.color = ink
            p.strokeWidth = 5f
            p.style = Paint.Style.STROKE
            when(kind) {
                "rect" -> c.drawRect(a.x,a.y,b.x,b.y,p)
                "triangle" -> {
                    val path = Path()
                    path.moveTo((a.x+b.x)/2,a.y)
                    path.lineTo(b.x,b.y)
                    path.lineTo(a.x,b.y)
                    path.close()
                    c.drawPath(path,p)
                }
                "arrow" -> {
                    c.drawLine(a.x,a.y,b.x,b.y,p)
                    val angle = Math.atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble())
                    val len = 22f
                    val path = Path()
                    path.moveTo(b.x,b.y)
                    path.lineTo(
                        b.x-len*kotlin.math.cos(angle-0.5).toFloat(),
                        b.y-len*kotlin.math.sin(angle-0.5).toFloat())
                    path.moveTo(b.x,b.y)
                    path.lineTo(
                        b.x-len*kotlin.math.cos(angle+0.5).toFloat(),
                        b.y-len*kotlin.math.sin(angle+0.5).toFloat())
                    c.drawPath(path,p)
                }
            }
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = PointF(x,y)
                    last = PointF(x,y)
                    moving = layers.asReversed().firstOrNull {
                        x >= it.x && x <= it.x+it.w && y >= it.y && y <= it.y+it.h
                    }
                    if (moving != null) {
                        moveDX = x-moving!!.x
                        moveDY = y-moving!!.y
                    } else if (mode == "pen" || mode == "eraser") {
                        current = Path().apply { moveTo(x,y) }
                    } else {
                        isDrawing = true
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    last = PointF(x,y)
                    if (moving != null) {
                        moving!!.x = x-moveDX
                        moving!!.y = y-moveDY
                    } else {
                        current?.lineTo(x,y)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    last = PointF(x,y)
                    if (moving == null) {
                        if (current != null) {
                            strokes.add(Stroke(current!!,
                                if (mode == "eraser") Color.WHITE else ink,
                                if (mode == "eraser") 32f else 5f))
                            current = null
                        } else if (isDrawing) {
                            shapes.add(mode to (start to PointF(x,y)))
                        }
                    }
                    moving = null
                    isDrawing = false
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}
