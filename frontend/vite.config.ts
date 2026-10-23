import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.BACKEND_PROXY_TARGET || 'http://127.0.0.1:8080';
  const proxy = Object.fromEntries(
    ['/api', '/demo.html', '/tasks', '/timetable-slots', '/preferences', '/recommendations']
      .map((path) => [path, { target: backend, changeOrigin: true }]),
  );
  return {
    plugins: [react()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/frontend-test-setup.ts'],
      clearMocks: true,
      restoreMocks: true,
    },
  };
});
