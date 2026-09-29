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
    // Development server is local-only; production uses the Nginx build.
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 开发环境把 /api 转发到 Spring Boot
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
