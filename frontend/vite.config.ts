import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 后端地址。默认 8081。
 * 改动后端端口时用环境变量覆盖即可，避免代理指向旧端口：
 *   $env:BACKEND_PORT=8082; npm run dev
 */
const BACKEND_PORT = process.env.BACKEND_PORT ?? '8081'
const BACKEND_HOST = process.env.BACKEND_HOST ?? 'localhost'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 注意：这里刻意不用 Vite 默认的 5173。
    // 本机 Windows 保留了动态端口段 5121-5220，5173 落在其中，
    // 任何进程绑定都会得到 EACCES: permission denied。5273 不在任何保留段内。
    // 查看本机保留段：netsh int ipv4 show excludedportrange protocol=tcp
    port: 5273,
    // 端口被占用时直接报错，而不是悄悄换端口让人找不到地址
    strictPort: true,
    // 默认只监听回环地址（更安全）。需要局域网访问时：npm run dev -- --host
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
