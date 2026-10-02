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

// 本文件是 WebExtension content script，运行在隔离的 JS 世界：能访问 DOM，但与页面 JS 不共享对象，
// 看不到 window.__TAURI_INTERNALS__，包装 prototype 也不会影响页面代码。
//
// 因此真正生效的钩子必须通过 <script> 注入到页面世界执行，两个世界之间用 window.postMessage 通信
// （DOM 事件是共享的）。
//
// 回传原生使用 document.title（原生侧由 onTitleChange 接收），而非 runtime.sendNativeMessage：
// 后者在本机的 Promise 会静默 reject，实测不触发 MessageDelegate。
//
// 页面世界钩子的实现约束：pageWorldHook 会被 toString() 序列化后注入，不能引用本文件外层作用域的
// 变量，所需常量与辅助函数都必须定义在函数内部。

(function() {
    if (window.__gdHooked) return;
    window.__gdHooked = true;

    // 是否在启动后自动启用 gp-next 的 JS Modding 开关。
    //
    // 该开关默认关闭，且游戏「实验性」页中的对应开关处于锁定状态（pointer-events:none，
    // 回调会把 true 还原为 false），唯一入口是控制台的 window.gpNext.mods.enableJsModding()。
    // GeckoView 侧没有可用的控制台，故由页面世界的钩子代为调用一次。
    // 对应 gardendless-android 中 GameActivity 的 FORCE_JS_MODDING 常量。
    var FORCE_JS_MODDING = true;

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

    // 在页面世界执行的钩子。写成具名函数再用 toString() 注入，避免字符串转义问题。
    function pageWorldHook(forceJsModding) {
        if (window.__gdPageHooked) return;
        window.__gdPageHooked = true;

        var TAG = "[gd-next]";

        function emit(type, value) {
            window.postMessage({ __gd: 1, type: type, value: value }, "*");
        }

        // ── 全屏与导出文件名 ──
        // 以下两项在 GeckoView 下必须由原生接管：
        // - 全屏：polyfill 将 plugin:window|set_fullscreen 实现为空函数，且 WebView 对非 video
        //   元素不回调 onShowCustomView；
        // - 导出文件名：polyfill 使用 prompt() 询问，而 GeckoView 不响应 prompt。

        // 游戏启动约 2 秒后会按自身状态同步一次 setFullscreen(false)，覆盖掉由原生恢复的全屏状态。
        // 因此在启动保护期内忽略这条自动同步的 false；true 一律放行，不影响用户主动操作。
        var bootGuard = true;
        setTimeout(function() { bootGuard = false; }, 6000);

        var setFullscreen = function(v) {
            if (bootGuard && !v) { bootGuard = false; return; }
            bootGuard = false;

            // 同时走两条路径，二者幂等：
            // 1) 标准 Fullscreen API：让 document.fullscreenElement 变为真实值，游戏读取
            //    is_fullscreen 时才能得到正确结果，且原生会收到 onFullScreen；
            // 2) 消息回传：requestFullscreen 可能因缺少用户手势上下文被拒绝，此时由回传兜底
            //    保证尺寸一定切换。
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

        // 标准 Fullscreen API 路径（游戏若改用该路径亦可覆盖）
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

        // 导出文件名：游戏通过 <a download="文件名" href="data:..."> 配合 click() 触发导出，
        // 文件名只存在于 a.download 上，不会传给原生，因此在此捕获
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

        // ── gp-next 数据目录 ──
        // 读取走 <origin>/gp-next/... 虚拟路径（由 Kotlin 侧的 Ktor 静态路由提供）；
        // 元数据与写操作走 /__gdnext/fs/* 端点。GeckoView 没有可用的 JS 桥，只能经 HTTP，
        // 路径统一放在 path 查询参数中，写操作的请求体留给文件内容。

        var FS_API = "/__gdnext/fs/";

        // 游戏传给 plugin:fs 的路径为相对 AppData 的路径（如 gp-next\packs\Foo\pack.json），
        // 也可能拼接了 resolve_directory 的返回值而成为绝对 URL；此处统一收敛为 "gp-next/xxx"。
        function relPath(rawPath) {
            var s = String(rawPath == null ? "" : rawPath).replace(/\\/g, "/").replace(/^\/+/, "");
            var origin = location.origin + "/";
            if (s.indexOf(origin) === 0) s = s.slice(origin.length);
            return s;
        }

        // 按 Tauri 语义，文件不存在时抛错，由游戏侧 catch 成 null / false。
        //
        // read_text_file 同样必须返回字节而非字符串：dist-js 的实现为
        //   `r instanceof ArrayBuffer ? r : Uint8Array.from(r)`，之后才交给 TextDecoder.decode。
        // 若返回字符串，Uint8Array.from 会按字符数组处理（每个字符转为 NaN 再变为 0），
        // 解码结果全为 \0。
        function readBytes(rawPath, label) {
            return fetch("/" + relPath(rawPath), { cache: "no-store" }).then(function(res) {
                if (!res.ok) throw new Error(label + " failed (" + res.status + "): " + relPath(rawPath));
                return res.arrayBuffer();
            });
        }

        function bodyToBytes(payload) {
            if (payload instanceof ArrayBuffer) return new Uint8Array(payload);
            if (payload && payload.buffer instanceof ArrayBuffer) {
                return new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength);
            }
            if (typeof payload === "string") return new TextEncoder().encode(payload);
            return new Uint8Array(0);
        }

        /** 调用元数据端点，返回解析后的 JSON */
        function callFs(action, params) {
            var query = [];
            for (var key in params) {
                if (params[key] != null) {
                    query.push(encodeURIComponent(key) + "=" + encodeURIComponent(params[key]));
                }
            }
            var url = FS_API + action + (query.length ? "?" + query.join("&") : "");
            return fetch(url, { method: "POST" }).then(function(res) {
                if (!res.ok) {
                    throw new Error(action + " failed (" + res.status + "): " + JSON.stringify(params));
                }
                return res.json();
            });
        }

        /** 写入文件；返回值统一为 null，与 Tauri 命令一致 */
        function writeFile(path, bytes) {
            var url = FS_API + "write?path=" + encodeURIComponent(relPath(path));
            return fetch(url, { method: "POST", body: bytes }).then(function(res) {
                if (!res.ok) throw new Error("write failed (" + res.status + "): " + path);
                return res.json();
            }).then(function(ok) {
                if (!ok) throw new Error("write returned false: " + path);
                return null;
            });
        }

        /**
         * 处理 gp-next 相关命令；返回 null 表示不接管，继续交由原 polyfill。
         */
        function handleGpNextCommand(cmd, args, body, options) {
            switch (cmd) {
                case "plugin:path|resolve_directory":
                    // 返回页面 origin，游戏会自行拼上 /gp-next
                    return Promise.resolve(location.origin);

                case "plugin:fs|read_text_file":
                    return readBytes(args.path, "read_text_file");

                case "plugin:fs|read_file":
                    return readBytes(args.path, "read_file");

                // dist-js 会把返回值直接交给 FileInfo 映射（首个字段即 isFile），
                // 因此端点必须返回对象；路径不存在时端点返回 404，此处转成 reject。
                case "plugin:fs|stat":
                case "plugin:fs|lstat":
                    return callFs("stat", { path: relPath(args.path) });

                case "plugin:fs|read_dir":
                    return callFs("readdir", { path: relPath(args.path) });

                case "plugin:fs|exists":
                    return callFs("exists", { path: relPath(args.path) });

                case "plugin:fs|mkdir":
                    return callFs("mkdir", { path: relPath(args.path) }).then(function() { return null; });

                case "plugin:fs|remove":
                    return callFs("remove", { path: relPath(args.path) }).then(function() { return null; });

                case "plugin:fs|rename":
                    return callFs("rename", {
                        from: relPath(args.oldPath),
                        to: relPath(args.newPath)
                    }).then(function() { return null; });

                case "plugin:fs|write_text_file":
                case "plugin:fs|write_file": {
                    // 真实形态为 invoke(cmd, bodyBytes, { headers: { path, options } })。
                    // polyfill 读取的是 args.headers.path，而 args 实际是 body，因此这两个命令在
                    // polyfill 下始终失败。
                    var headers = (options && options.headers) || {};
                    var path = headers.path ? decodeURIComponent(headers.path) : (args && args.path);
                    if (!path) throw new Error(cmd + " requires a path");
                    return writeFile(path, bodyToBytes(body));
                }

                case "plugin:opener|open_path":
                    // 目前仅 patcher 页的「打开补丁文件夹」使用，交由原生打开 gp-next 文档根
                    emit("openDataFolder", "1");
                    return Promise.resolve(null);
            }
            return null;
        }

        // 本脚本接管的命令：全部 plugin:fs|* 以及以下两个精确匹配项
        var HANDLED_EXACT = ["plugin:path|resolve_directory", "plugin:opener|open_path"];

        // Tauri invoke：游戏实际走这条
        var patchTauri = function() {
            var ti = window.__TAURI_INTERNALS__;
            if (!ti || !ti.invoke || ti.__gdHooked) return false;
            ti.__gdHooked = true;
            var orig = ti.invoke;
            ti.invoke = function(cmd, args, options) {
                var name = String(cmd || "");

                if (HANDLED_EXACT.indexOf(name) >= 0 || name.indexOf("plugin:fs|") === 0) {
                    try {
                        var result = handleGpNextCommand(name, args || {}, args, options);
                        if (result) return result;
                        // 未接管的 fs 命令会落入 polyfill 的 default 分支并返回 null，
                        // 通常表现为难以定位的 "Cannot read properties of null"，故在此显式告警。
                        console.warn(TAG, "unhandled command, falling back to polyfill:", name);
                    } catch (e) {
                        console.warn(TAG, name + " failed:", e);
                        return Promise.reject(e);
                    }
                }

                if (name === "plugin:window|set_fullscreen") {
                    setFullscreen(!!(args && args.value));
                    return Promise.resolve(null);
                }

                return orig.apply(this, arguments);
            };
            return true;
        };

        // polyfill 在 head 中同步执行，可能晚于本脚本，故短暂轮询等待
        if (!patchTauri()) {
            var tries = 0;
            var timer = setInterval(function() {
                if (patchTauri() || ++tries > 100) clearInterval(timer);
            }, 50);
        }

        // ── JS Modding 自动启用 ──
        // 关于该开关的约束见本文件顶部 FORCE_JS_MODDING 的说明。
        //
        // 幂等处理：enableJsModding() 会先持久化 experimental.jsModding=true，再重新载入 JS 模组。
        // 若已持久化为开启，则本次启动的初始加载已载入过模组，这里直接返回，避免多一次
        // dispose + reload；若持久化为关闭（含用户在界面中关闭），则重新开启。

        /** 读取 localStorage 中已持久化的 jsModding 开关 */
        function jsModdingPersisted() {
            try {
                var s = JSON.parse(localStorage.getItem("gp-next-settings") || "{}");
                return !!(s && s.experimental && s.experimental.jsModding === true);
            } catch (e) {
                return false;
            }
        }

        /** 尝试启用 JS Modding；返回 false 表示 window.gpNext.mods 尚未挂载 */
        function tryEnableJsModding() {
            var mods = window.gpNext && window.gpNext.mods;
            if (!mods || typeof mods.enableJsModding !== "function") return false;
            if (jsModdingPersisted()) {
                console.log(TAG, "JS Modding already enabled");
                return true;
            }
            Promise.resolve()
                .then(function() { return mods.enableJsModding(); })
                .then(function() { console.log(TAG, "JS Modding enabled"); })
                .catch(function(e) { console.warn(TAG, "enableJsModding failed:", e); });
            return true;
        }

        // window.gpNext.mods 由游戏在 phase-4 挂载（引擎就绪且补丁加载完成后），故轮询等待。
        // 上限 300 秒，覆盖引擎等待超时 30 秒与补丁加载的余量。
        if (forceJsModding === true) {
            if (!tryEnableJsModding()) {
                var modTries = 0;
                var modTimer = setInterval(function() {
                    if (tryEnableJsModding() || ++modTries > 3000) clearInterval(modTimer);
                }, 100);
            }
        }
    }

    // 注入到页面世界
    var script = document.createElement("script");
    script.textContent = "(" + pageWorldHook.toString() + ")(" + FORCE_JS_MODDING + ");";
    (document.head || document.documentElement).appendChild(script);
    script.remove();
})();
