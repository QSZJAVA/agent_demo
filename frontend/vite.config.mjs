/** Vite 构建和本机代理配置；Vue 与 Element UI 共用同一响应式运行时，环境变量只提供接口地址。 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // Vue 2.7 的 import/require 条件入口不同；统一到 ESM，避免 Element UI store 与页面各持有一份依赖收集器。
    // 两份运行时会让生产表格无法感知后续注册的列与数据，不能用逐页刷新布局掩盖该状态断连。
    alias: [{ find: /^vue$/, replacement: 'vue/dist/vue.runtime.esm.js' }]
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'axios']
        }
      }
    }
  },
  server: {
    // Development server is local-only; production uses the Nginx build.
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 开发环境把 /api 转发到 Spring Boot
      '/api': {
        target: process.env.AGENT_API_URL || 'http://127.0.0.1:8080',
        // Preserve the browser-facing Host so Spring recognizes proxied
        // requests as same-origin (Origin still contains the Vite address).
        changeOrigin: false
      }
    }
  }
})
