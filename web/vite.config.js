import { defineConfig } from "vite";

// ONNX Runtime Web (used by @nosmai/moderation-web) ships its own WASM. Excluding
// it from dependency pre-bundling stops Vite from rewriting the worker/wasm paths.
export default defineConfig({
  optimizeDeps: { exclude: ["onnxruntime-web"] },
  server: { open: "/" },
});
