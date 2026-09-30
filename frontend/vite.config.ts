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

/**
 * 开发服务器监听地址。默认 0.0.0.0（监听所有网卡），
 * 这样局域网内其它设备可以用本机 IPv4 访问，例如 http://192.168.3.86:5273/。
 * 只想本机访问时：$env:DEV_HOST='127.0.0.1'; npm run dev
 */
const DEV_HOST = process.env.DEV_HOST ?? '0.0.0.0'

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
    // 监听所有网卡，局域网可用本机 IPv4 访问；用 DEV_HOST=127.0.0.1 可限制为本机
    host: DEV_HOST,
    proxy: {
      '/api': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true
      }
    }
  },
  /*
    `vite preview` 服务的是**构建产物**（dist/），本地抽查"拆包后的页面还能不能用"就走它。
    它默认没有代理，页面里的 /api 请求会 404，于是连登录都过不去、路由守卫直接把你送回登录页——
    那样抽查就变成了"只证明 HTML 能返回"。这里补上与 dev 完全一致的代理，
    让 preview 也能做端到端抽查（同样用 BACKEND_PORT 指向临时实例）。
  */
  preview: {
    port: 5276,
    strictPort: true,
    proxy: {
      '/api': {
        target: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    /*
      把"重依赖"从业务 chunk 里拆出来（R2 未闭环第 7 条）。

      拆分前：`index` 1,273 kB（gzip 412 kB）、`StatCards` 570 kB（gzip 192 kB）——
      业务代码与 echarts/element-plus 混在一个文件里，改一行业务代码就让整包缓存失效，
      局域网首屏也要一次性拉 1.2 MB。

      **只做分包，不改任何业务行为、不引依赖**：动态 import 仍然是那 17 处路由级懒加载，
      这里只决定"同一个三方库落到哪个文件"。拆成：
        vendor-echarts        echarts + zrender（只有图表页用，首屏不该强制加载）
        vendor-element-plus   Element Plus + 图标
        vendor-vue            vue / vue-router / pinia
      其余三方**刻意不单独成 chunk**（返回 undefined，交给 Rollup 按引用关系落位）：
      早先版本把"其它 node_modules"也打到 `vendor` 里，结果把只有懒加载页才用的
      markdown-it/axios 提到首屏预加载集合中，首屏反而更大——所以这里只拆已知重依赖。
    */
    rollupOptions: {
      output: {
        manualChunks(id: string): string | undefined {
          if (!id.includes('node_modules')) {
            return undefined
          }
          if (id.includes('echarts') || id.includes('zrender')) {
            return 'vendor-echarts'
          }
          if (id.includes('element-plus') || id.includes('@element-plus')) {
            return 'vendor-element-plus'
          }
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')
              || id.includes('@vue/')) {
            return 'vendor-vue'
          }
          return undefined
        }
      }
    }
  }
})
