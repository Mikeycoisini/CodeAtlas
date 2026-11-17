import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
  build: {
    outDir: "dist",
    // 本机 Windows 上 esbuild 的 chunk 转译临时文件删除会被拒绝；改用 terser 压缩并跳过该步骤。
    target: "esnext",
    minify: "terser",
  },
});
