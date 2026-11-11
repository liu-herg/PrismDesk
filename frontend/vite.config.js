import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 打包结果直接放进 Spring Boot 的静态资源目录，由后端在 http://127.0.0.1:8080/ 托管，
// 前后端同源，不需要额外的容器和跨域配置。
// 开发模式（npm run dev）下，接口请求代理到本机 8080。
const backend = process.env.PRISMDESK_API || 'http://127.0.0.1:8080'
const apiPaths = ['/auth', '/chat', '/conversations', '/notifications', '/tickets', '/search', '/knowledge', '/health', '/monitor', '/admin']

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: Object.fromEntries(apiPaths.map((p) => [p, { target: backend, changeOrigin: true }]))
  }
})
