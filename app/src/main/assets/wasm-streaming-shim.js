// Fix WebAssembly streaming compilation on Android System WebView.
// On some devices WebView's compileStreaming/instantiateStreaming throws
// even when the .wasm is fetched correctly (200 application/wasm), which
// breaks sites that load a wasm video decoder for 4K playback.
// Strategy: try native streaming first (fast path on healthy engines);
// only on failure, fall back to fetch -> arrayBuffer -> compile.
(function () {
    if (window.__wasmStreamingPatched) return;
    if (typeof WebAssembly === 'undefined') return;
    var _cs = WebAssembly.compileStreaming;
    var _is = WebAssembly.instantiateStreaming;
    if (typeof _cs === 'function') {
        WebAssembly.compileStreaming = async function (src) {
            try {
                return await _cs.call(WebAssembly, src);
            } catch (e) {
                var resp = await src;
                return WebAssembly.compile(await resp.arrayBuffer());
            }
        };
    }
    if (typeof _is === 'function') {
        WebAssembly.instantiateStreaming = async function (src, imports) {
            try {
                return await _is.call(WebAssembly, src, imports);
            } catch (e) {
                var resp = await src;
                return WebAssembly.instantiate(await resp.arrayBuffer(), imports);
            }
        };
    }
    window.__wasmStreamingPatched = true;
})();
