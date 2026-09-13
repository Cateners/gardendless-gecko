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

// 本文件是 WebExtension content script，运行在【隔离的 JS 世界】：
// 能访问 DOM，但与页面 JS 不共享对象 —— 看不到 window.__TAURI_INTERNALS__，
// 包装 prototype 也不会影响页面代码。
//
// 因此真正干活的钩子必须通过 <script> 注入到【页面世界】执行，
// 两侧用 window.postMessage 通信（DOM 事件在两界之间共享）。
//
// 回传原生选用 document.title（原生侧 onTitleChange 接收）而非
// runtime.sendNativeMessage：后者在本机的 Promise 会静默 reject，
// 实测不触发 MessageDelegate。

(function() {
    if (window.__gdHooked) return;
    window.__gdHooked = true;

    function report(type, value) {
        document.title = "[GD] " + type + " " + value;
    }

    // 页面世界 → 本脚本 → 原生
    window.addEventListener("message", function(e) {
        if (e.source !== window) return;
        var d = e.data;
        if (!d || d.__gd !== 1) return;
        report(d.type, d.value);
    });

    // 在【页面世界】执行的钩子。写成具名函数再用 toString() 注入，避免字符串转义问题。
    function pageWorldHook() {
        if (window.__gdPageHooked) return;
        window.__gdPageHooked = true;

        function emit(type, value) {
            window.postMessage({ __gd: 1, type: type, value: value }, "*");
        }

        // 游戏启动时（约 2s）会按自己内部状态同步一次 setFullscreen(false)，
        // 那会把我们从 SharedPreferences 恢复的全屏状态冲掉。
        // 故在启动保护期内忽略这条自动同步的 false；true 一律放行，不影响用户主动操作。
        var bootGuard = true;
        setTimeout(function() { bootGuard = false; }, 6000);

        var setFullscreen = function(v) {
            if (bootGuard && !v) { bootGuard = false; return; }
            bootGuard = false;

            // 同时走两条路，二者幂等：
            // 1) 标准 Fullscreen API —— 让 document.fullscreenElement 变真实，
            //    游戏读取 is_fullscreen 时才能得到正确答案，且原生会收到 onFullScreen；
            // 2) 消息回传 —— requestFullscreen 可能因缺少用户手势上下文被拒绝，
            //    此时由回传兜底保证尺寸一定切换。
            var el = document.documentElement;
            try {
                if (v && !document.fullscreenElement) {
                    var r = el.requestFullscreen || el.webkitRequestFullscreen;
                    if (r) r.call(el);
                } else if (!v && document.fullscreenElement) {
                    var x = document.exitFullscreen || document.webkitExitFullscreen;
                    if (x) x.call(document);
                }
            } catch (e) { /* 忽略，由回传兜底 */ }

            emit("fullscreen", !!v);
        };

        // 标准 Fullscreen API（游戏若改用此路径也能覆盖）
        var ep = Element.prototype;
        var req = ep.requestFullscreen || ep.webkitRequestFullscreen || ep.webkitRequestFullScreen;
        if (req) {
            ep.requestFullscreen = function() {
                setFullscreen(true);
                return req.apply(this, arguments);
            };
        }
        var dp = Document.prototype;
        var exit = dp.exitFullscreen || dp.webkitExitFullscreen || dp.webkitCancelFullScreen;
        if (exit) {
            dp.exitFullscreen = function() {
                setFullscreen(false);
                return exit.apply(this, arguments);
            };
        }

        // 下载命名：游戏用 <a download="名字" href="data:..."> + click() 触发导出，
        // 文件名只存在于 a.download 上，不会传给原生，故在此捕获
        var isDataHref = function(el) {
            return (el && el.getAttribute && (el.getAttribute('href') || '').indexOf('data:') === 0);
        };
        var origClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
            if (this.download && isDataHref(this)) emit("exportName", this.download);
            return origClick.apply(this, arguments);
        };
        document.addEventListener('click', function(e) {
            var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
            if (a && isDataHref(a)) emit("exportName", a.getAttribute('download'));
        }, true);

        // Tauri invoke：游戏实际走这条。polyfill 把 set_fullscreen 实现为空函数，
        // 用 prompt 询问文件名而 WebView/GeckoView 均不响应，两处意图都会丢失。
        var patchTauri = function() {
            var ti = window.__TAURI_INTERNALS__;
            if (!ti || !ti.invoke || ti.__gdHooked) return false;
            ti.__gdHooked = true;
            var orig = ti.invoke;
            ti.invoke = function(cmd, args) {
                if (cmd === 'plugin:window|set_fullscreen') {
                    setFullscreen(!!(args && args.value));
                    return Promise.resolve(null);
                }
                if (cmd === 'plugin:dialog|save') {
                    // 游戏请求一个保存文件名。原生记下它，随后用它作为保存对话框的默认名，
                    // 与游戏自身的命名规则（存档、键位配置等各不相同）保持一致。
                    var raw = args && (args.defaultPath || (args.options && args.options.defaultPath));
                    var name = raw ? String(raw).split(/[\\/]/).pop() : '';
                    if (name) {
                        emit("exportName", name);
                        return Promise.resolve(name);
                    }
                }
                return orig.apply(this, arguments);
            };
            return true;
        };

        // polyfill 在 head 中同步执行，可能晚于本脚本，短暂轮询等待
        if (!patchTauri()) {
            var tries = 0;
            var timer = setInterval(function() {
                if (patchTauri() || ++tries > 100) clearInterval(timer);
            }, 50);
        }
    }

    // 注入到页面世界
    var script = document.createElement("script");
    script.textContent = "(" + pageWorldHook.toString() + ")();";
    (document.head || document.documentElement).appendChild(script);
    script.remove();
})();
