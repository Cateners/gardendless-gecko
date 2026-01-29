// gardendless-gecko

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

(function() {
    const target = document.getElementById("GameCanvas");
    if (!target) return;

    let maxTouches = 0;
    let isDragging = false;
    let lastWheelY = 0;
    let touchStartCenter = null;
    let delayTime = 24;

    // --- 新增：用于区分滚动和右键的变量 ---
    let hasMovedEnough = false;
    const moveThreshold = 10; // 移动超过10像素才触发滚动

    function emitMouseEvent(type, coords, button = 0) {
        const mouseEvent = new MouseEvent(type, {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: coords.clientX,
            clientY: coords.clientY,
            button: button,
            buttons: button === 0 ? 1 : (button === 2 ? 2 : 0)
        });
        target.dispatchEvent(mouseEvent);
    }

    function emitWheelEvent(coords, deltaY) {
        target.dispatchEvent(new WheelEvent("wheel", {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: coords.clientX,
            clientY: coords.clientY,
            deltaY: deltaY,
            deltaMode: 0
        }));
    }

    function getCenter(t1, t2) {
        return {
            clientX: (t1.clientX + (t2?.clientX || t1.clientX)) / 2,
            clientY: (t1.clientY + (t2?.clientY || t1.clientY)) / 2
        };
    }

    document.addEventListener("touchstart", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
        const count = e.touches.length;
        if (count > maxTouches) maxTouches = count;

        if (count === 1) {
//            emitMouseEvent("mousemove", e.touches[0], 0);
//            setTimeout(() => {
//                emitMouseEvent("mousedown", e.touches[0], 0);
//            }, delayTime)
//            isDragging = true;
        } else if (count === 2) {
            touchStartCenter = getCenter(e.touches[0], e.touches[1]);
            lastWheelY = touchStartCenter.clientY;
            hasMovedEnough = false; // 重置移动判定

            //emitMouseEvent("mousemove", touchStartCenter, 0);
            if (isDragging) {
                isDragging = false;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchmove", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (e.touches.length === 1 && isDragging) {
//            setTimeout(() => {
//                emitMouseEvent("mousemove", e.touches[0], 0);
//            }, delayTime)
        } else if (e.touches.length === 2) {
            const currentCenter = getCenter(e.touches[0], e.touches[1]);
            const deltaYTotal = Math.abs(currentCenter.clientY - touchStartCenter.clientY);

            // 只有当移动距离超过阈值，才触发滚轮
            if (hasMovedEnough || deltaYTotal > moveThreshold) {
                hasMovedEnough = true;
                const deltaY = lastWheelY - currentCenter.clientY;
                //emitWheelEvent(touchStartCenter, deltaY * 2);
                lastWheelY = currentCenter.clientY;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchend", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (maxTouches === 1) {
//            setTimeout(() => {
//                emitMouseEvent("mouseup", e.changedTouches[0], 0);
//            }, delayTime);
        }
        // 只有当没有触发过滚动（hasMovedEnough 为 false）时，才触发右键
        else if (maxTouches === 2 && e.touches.length === 0 && !hasMovedEnough) {
            const finalCenter = touchStartCenter || getCenter(e.changedTouches[0], e.changedTouches[1]);
            emitMouseEvent("mousemove", finalCenter, 0);
            emitMouseEvent("mousedown", finalCenter, 2);
            emitMouseEvent("mouseup", finalCenter, 2);
        }

        if (e.touches.length === 0) {
            maxTouches = 0;
            isDragging = false;
            touchStartCenter = null;
            hasMovedEnough = false; // 重置状态
        }
    }, { capture: true, passive: false });

})();