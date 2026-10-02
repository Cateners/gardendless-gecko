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

// 本文件是 WebExtension content script，负责页面侧的手势处理：
//
// - 双指轻点映射为右键。该事件必须以 JS MouseEvent 派发到 GameCanvas，故放在页面侧；
// - 单指左键与滚轮由 native 侧的 MouseGameWebView 注入，本文件中对应代码已停用（保留以便切回）；
// - 是否产生右键只由本文件的判定决定：native 侧的右键分支为空实现，不会重复注入。

(function() {
    const target = document.getElementById("GameCanvas");
    if (!target) return;

    let maxTouches = 0;
    let isDragging = false;
    let lastWheelY = 0;
    let touchStartCenter = null;
    let delayTime = 24;

    // 用于区分"滚动"与"轻点"的状态
    let hasMovedEnough = false;
    const moveThreshold = 10; // 位移超过该阈值即视为滚动，不再产生右键

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

    // 在捕获阶段拦截并阻止默认行为，避免页面自身再处理同一手势
    document.addEventListener("touchstart", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();
        const count = e.touches.length;
        if (count > maxTouches) maxTouches = count;

        if (count === 1) {
            // 单指左键由 native 侧注入，故此处停用（保留以便切回）
            // emitMouseEvent("mousemove", e.touches[0], 0);
            // setTimeout(() => {
            //     emitMouseEvent("mousedown", e.touches[0], 0);
            // }, delayTime)
            // isDragging = true;
        } else if (count === 2) {
            touchStartCenter = getCenter(e.touches[0], e.touches[1]);
            lastWheelY = touchStartCenter.clientY;
            // 新手势开始，重置移动判定
            hasMovedEnough = false;

            // 双指按下处不再自行补发 mousemove：native 侧已在两指中点发过一次
            // emitMouseEvent("mousemove", touchStartCenter, 0);
            if (isDragging) {
                isDragging = false;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchmove", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (e.touches.length === 1 && isDragging) {
            // 单指移动同样由 native 侧注入，故此处停用（保留以便切回）
            // setTimeout(() => {
            //     emitMouseEvent("mousemove", e.touches[0], 0);
            // }, delayTime)
        } else if (e.touches.length === 2) {
            const currentCenter = getCenter(e.touches[0], e.touches[1]);
            const deltaYTotal = Math.abs(currentCenter.clientY - touchStartCenter.clientY);

            // 位移超过阈值即认为用户在滚动，据此取消右键
            if (hasMovedEnough || deltaYTotal > moveThreshold) {
                hasMovedEnough = true;
                const deltaY = lastWheelY - currentCenter.clientY;
                // 滚轮由 native 侧注入，故此处停用（deltaY 仅用于推进 lastWheelY）
                // emitWheelEvent(touchStartCenter, deltaY * 2);
                lastWheelY = currentCenter.clientY;
            }
        }
    }, { capture: true, passive: false });

    document.addEventListener("touchend", (e) => {
        e.preventDefault();
        e.stopImmediatePropagation();

        if (maxTouches === 1) {
            // 单指左键抬起由 native 侧注入，故此处停用（保留以便切回）
            // setTimeout(() => {
            //     emitMouseEvent("mouseup", e.changedTouches[0], 0);
            // }, delayTime);
        }
        // 未发生滚动时，双指轻点映射为右键
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
            // 重置手势状态
            hasMovedEnough = false;
        }
    }, { capture: true, passive: false });

})();