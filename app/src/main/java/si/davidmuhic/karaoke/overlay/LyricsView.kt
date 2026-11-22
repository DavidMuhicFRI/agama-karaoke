package si.davidmuhic.karaoke.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import si.davidmuhic.karaoke.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen karaoke display: the current line large and white in the middle,
 * neighbouring lines dimmed, scrolling smoothly as the song progresses.
 */
@SuppressLint("ViewConstructor")
class LyricsView(context: Context) : View(context) {

    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }
    var onDismiss: () -> Unit = {}

    var fontScale = 1f
        set(value) {
            field = value
            relayout()
        }

    private var header = ""
    private var durationMs = 0L
    private var content: Lyrics = Lyrics.NothingPlaying

    private val density = resources.displayMetrics.density
    private val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 18 * density
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        textSize = 13 * density
        textAlign = Paint.Align.CENTER
    }
    private val barTrackPaint = Paint().apply { color = 0x33FFFFFF }
    private val barFillPaint = Paint().apply { color = ACCENT }

    // Laid-out lyric blocks and their vertical offsets inside the scrolling content.
    private var blocks: List<StaticLayout> = emptyList()
    private var tops = IntArray(0)
    private var times = LongArray(0)
    private var contentHeight = 0
    private var gap = 0f

    private var scrollPos = 0f
    private var snapNextFrame = true
    private var lastFrameMs = 0L

    fun setTrack(header: String, durationMs: Long) {
        this.header = header
        this.durationMs = durationMs
        invalidate()
    }

    fun setLyrics(lyrics: Lyrics) {
        content = lyrics
        relayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        val textWidth = width - 2 * horizontalPadding()
        if (textWidth <= 0) return
        lyricPaint.textSize = (min(height * 0.085f, width * 0.055f) * fontScale).coerceAtLeast(14 * density)
        gap = lyricPaint.textSize * 0.55f

        val c = content
        val texts: List<String> = when (c) {
            is Lyrics.Synced -> c.lines.map { if (it.text.isBlank()) "♪" else it.text }
            is Lyrics.Plain -> c.lines.map { it.ifBlank { " " } }
            else -> listOf(statusText(c))
        }
        times = if (c is Lyrics.Synced) LongArray(c.lines.size) { c.lines[it].timeMs } else LongArray(0)
        blocks = texts.map { text ->
            StaticLayout.Builder.obtain(text, 0, text.length, lyricPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        }
        tops = IntArray(blocks.size)
        var y = 0f
        blocks.forEachIndexed { i, block ->
            tops[i] = y.toInt()
            y += block.height + gap
        }
        contentHeight = y.toInt()
        snapNextFrame = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val now = SystemClock.uptimeMillis()
        val dtSec = if (lastFrameMs == 0L) 0f else (now - lastFrameMs) / 1000f
        lastFrameMs = now

        val position = positionProvider()
        val anchorY = height * 0.45f
        val c = content
        var settled = true

        when (c) {
            is Lyrics.Synced -> {
                val current = currentIndex(position + LEAD_MS)
                val target = if (current < 0) -gap else tops[current] + blocks[current].height / 2f
                settled = animateScroll(target, dtSec)
                drawBlocks(canvas, anchorY, current)
            }
            is Lyrics.Plain -> {
                // Unsynced lyrics: scroll through the text in proportion to song progress.
                val progress = if (durationMs > 0) position.toFloat() / durationMs else 0f
                settled = animateScroll(progress.coerceIn(0f, 1f) * contentHeight, dtSec)
                drawBlocks(canvas, anchorY, -1)
            }
            else -> {
                val block = blocks.firstOrNull()
                if (block != null) {
                    lyricPaint.color = 0x99FFFFFF.toInt()
                    canvas.save()
                    canvas.translate(horizontalPadding().toFloat(), (height - block.height) / 2f)
                    block.draw(canvas)
                    canvas.restore()
                }
            }
        }

        drawChrome(canvas, position)

        if (!settled) postInvalidateOnAnimation()
        else if (playingProvider()) postInvalidateDelayed(IDLE_FRAME_MS)
        else lastFrameMs = 0L
    }

    /** Eases the scroll towards [target]; returns true once it has arrived. */
    private fun animateScroll(target: Float, dtSec: Float): Boolean {
        if (snapNextFrame) {
            scrollPos = target
            snapNextFrame = false
            return true
        }
        val t = 1f - exp(-dtSec * SCROLL_STIFFNESS)
        scrollPos += (target - scrollPos) * t
        if (abs(target - scrollPos) < 0.5f) {
            scrollPos = target
            return true
        }
        return false
    }

    private fun drawBlocks(canvas: Canvas, anchorY: Float, current: Int) {
        val padding = horizontalPadding().toFloat()
        for (i in blocks.indices) {
            val block = blocks[i]
            val top = anchorY - scrollPos + tops[i]
            if (top > height || top + block.height < 0) continue

            val isCurrent = i == current
            val distance = if (current < 0) i + 1 else abs(i - current)
            val alpha = if (isCurrent || current == -1 && content is Lyrics.Plain) 255 else max(45, 150 - distance * 28)
            lyricPaint.color = Color.argb(alpha, 255, 255, 255)

            canvas.save()
            canvas.translate(padding, top)
            if (!isCurrent && content is Lyrics.Synced) {
                canvas.scale(INACTIVE_SCALE, INACTIVE_SCALE, block.width / 2f, block.height / 2f)
            }
            block.draw(canvas)
            canvas.restore()
        }
    }

    /** Song title on top, progress bar and hint at the bottom. */
    private fun drawChrome(canvas: Canvas, position: Long) {
        if (header.isNotEmpty()) {
            val maxWidth = width - 2f * horizontalPadding()
            val text = TextUtils.ellipsize(header, headerPaint, maxWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, width / 2f, 36 * density, headerPaint)
        }
        if (durationMs > 0) {
            val barY = height - 34 * density
            val left = horizontalPadding().toFloat()
            val right = width - left
            val progress = (position.toFloat() / durationMs).coerceIn(0f, 1f)
            canvas.drawRect(left, barY, right, barY + 3 * density, barTrackPaint)
            canvas.drawRect(left, barY, left + (right - left) * progress, barY + 3 * density, barFillPaint)
        }
        val source = when (val c = content) {
            is Lyrics.Synced -> c.source
            is Lyrics.Plain -> c.source
            else -> ""
        }
        val hint = if (source.isEmpty()) "tap to close" else "tap to close  ·  lyrics from $source"
        canvas.drawText(hint, width / 2f, height - 12 * density, hintPaint)
    }

    /** Index of the last line whose timestamp has passed, or -1 before the first line. */
    private fun currentIndex(position: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= position) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun statusText(lyrics: Lyrics): String = when (lyrics) {
        Lyrics.NothingPlaying -> "Nothing playing"
        Lyrics.Loading -> "Finding lyrics…"
        Lyrics.NotFound -> "No lyrics found for this song"
        Lyrics.Instrumental -> "♪  Instrumental  ♪"
        is Lyrics.Error -> lyrics.message
        else -> ""
    }

    private fun horizontalPadding(): Int = (width * 0.06f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) onDismiss()
        return true
    }

    /** Call when playback state changes so the animation loop restarts. */
    fun refresh() {
        lastFrameMs = 0L
        invalidate()
    }

    private companion object {
        const val BACKGROUND = 0xF2000000.toInt()
        const val ACCENT = 0xFFFF6B4A.toInt()
        const val LEAD_MS = 250L
        const val IDLE_FRAME_MS = 100L
        const val SCROLL_STIFFNESS = 7f
        const val INACTIVE_SCALE = 0.86f
    }
}
