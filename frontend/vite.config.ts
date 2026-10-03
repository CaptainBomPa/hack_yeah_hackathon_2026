import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Gateway (Spring Cloud Gateway) — domyślnie port z docker-compose.yml.
  const gateway = env.GATEWAY_URL ?? 'http://localhost:8000'

  return {
    plugins: [react()],
    server: {
      port: 3000,
      proxy: {
        '/v1': { target: gateway, changeOrigin: true },
        '/api': { target: gateway, changeOrigin: true },
      },
    },
  }
})
