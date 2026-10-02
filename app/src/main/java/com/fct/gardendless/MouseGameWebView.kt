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


/**
 * 把触摸手势转换为鼠标事件，供游戏使用。
 *
 * 事件注入：左键的按下/移动/抬起与滚轮都使用 native MotionEvent（SOURCE_MOUSE）；
 * 右键不在此类处理，由页面侧的 touchPatch.js 派发 JS MouseEvent。
 *
 * 手势映射：
 * - 单指拖动：左键按下 → 移动 → 抬起；
 * - 双指滑动：以两指中点作为光标位置，纵向位移映射为滚轮；
 * - 双指轻点（位移未超过 moveThreshold）：由 touchPatch.js 处理为右键。
 */
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
        // 真实鼠标事件不做转换，直接交给 GeckoView
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
                    // 进入双指手势，取消左键拖拽
                    isDragging = false
                    hasMovedEnough = false
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy

                    // 双指按下瞬间，先在两指中点补发一次移动（与 touchPatch.js 的 mousemove 对应），
                    // 使后续滚轮以按下点为基准
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
                // 仅最后一根手指离开时收尾
                if (action == MotionEvent.ACTION_UP) {
                    isTouching = false
                    mainHandler.removeCallbacksAndMessages(null)

                    if (maxTouches == 1 && isDragging) {
                        injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_UP, MotionEvent.BUTTON_PRIMARY)
                    }
                    else if (maxTouches == 2 && !hasMovedEnough) {
                        // 双指轻点应产生右键，但此处刻意不处理：右键由 touchPatch.js 在页面侧派发
                        // （它监听 touchend 后向 GameCanvas 发出 mousemove + 右键 down/up）。
                        // 若在此重复注入会产生两次右键，故 injectRightClickAt 未被调用。
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


    /**
     * 右键注入的备选实现，当前未启用：右键由 touchPatch.js 在页面侧派发。
     * 保留此方法以便需要改回原生路径时直接启用。
     */
    private fun injectRightClickAt(x: Float, y: Float) {
        // 备选：先以一个 ACTION_MOVE 把光标移到目标位置
        // val move = MotionEvent.obtain(
        //     SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
        //     MotionEvent.ACTION_MOVE, x, y, 0
        // )
        // super.dispatchTouchEvent(move)
        // move.recycle()
    }
}