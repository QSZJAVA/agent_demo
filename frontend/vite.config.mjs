import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig({
  plugins: [vue()],
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
    // 同时监听 IPv4/IPv6，避免访问 127.0.0.1 时连接被拒
    host: true,
    port: 5173,
    proxy: {
      // 开发环境把 /api 转发到 Spring Boot
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
