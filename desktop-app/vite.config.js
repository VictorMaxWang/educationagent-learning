import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react()],
  // 本地 educationagent 工作区与应用共享 React 实例，避免画布组件出现 hook 错误。
  resolve: { dedupe: ['react', 'react-dom'] },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    // 网页与 Tauri 使用同一 API；浏览器经代理，避免手机误连自己的 localhost。
    proxy: Object.fromEntries(['/api', '/chat', '/actuator'].map((prefix) => [prefix, {
      target: process.env.CLASSROOM_BACKEND_URL || 'http://127.0.0.1:8888',
      changeOrigin: true,
    }])),
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
  },
}));
