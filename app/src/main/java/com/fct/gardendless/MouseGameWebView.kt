package com.fct.gardendless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs


class MouseGameWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GeckoView(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var lastScrollY = 0f
    private var touchStartCenterX = 0f
    private var touchStartCenterY = 0f
    private var hasMovedEnough = false
    private val moveThreshold = 20f
    private var maxTouches = 0
    private var isTouching = false

    init {
        // 必须禁用长按菜单，否则会干扰右键模拟
        this.isLongClickable = false
        this.setOnLongClickListener { true }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.source == InputDevice.SOURCE_MOUSE) {
            return super.dispatchTouchEvent(event)
        }

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        if (pointerCount > maxTouches) maxTouches = pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pointerCount == 1) {
                    isTouching = true
                    val dx = event.x
                    val dy = event.y
                    if (isTouching && maxTouches == 1) {
                        injectMouseEventAt(dx, dy, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
                        isDragging = true
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    isDragging = false // 取消左键拖拽
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    // 【关键补全】双指按下瞬间的 move
                    // 对应 JS: emitMouseEvent("mousemove", touchStartCenter, 0);
                    injectMouseEventAt(cx, cy, MotionEvent.ACTION_MOVE, 0)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1 && isDragging) {
                    injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val deltaTotal = abs(cy - touchStartCenterY)

                    if (hasMovedEnough || deltaTotal > moveThreshold) {
                        hasMovedEnough = true
                        val scrollDelta = cy - lastScrollY
                        injectScrollEventAt(touchStartCenterX, touchStartCenterY, scrollDelta * 2)
                        lastScrollY = cy
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 当最后一根手指离开
                if (action == MotionEvent.ACTION_UP) {
                    isTouching = false
                    mainHandler.removeCallbacksAndMessages(null)

                    if (maxTouches == 1 && isDragging) {
                        injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY)
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 【关键补全】右键点击逻辑，包含它之前的 move
                        // 对应 JS: emitMouseEvent("mousemove", finalCenter, 0);
                        // 然后再 Down/Up 右键
                        // injectRightClickAt(touchStartCenterX, touchStartCenterY)
                    }

                    // 重置状态
                    maxTouches = 0
                    isDragging = false
                    hasMovedEnough = false
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private fun injectMouseEventAt(x: Float, y: Float, action: Int, buttonState: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), action,
            1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun injectScrollEventAt(x: Float, y: Float, delta: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, delta / 15f)
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }


    private fun injectRightClickAt(x: Float, y: Float) {
//        val upEvent = MotionEvent.obtain(
//            SystemClock.uptimeMillis(),
//            SystemClock.uptimeMillis(),
//            MotionEvent.ACTION_MOVE,
//            x,
//            y,
//            0
//        )
//        super.dispatchTouchEvent(upEvent)
//        upEvent.recycle()
    }
}