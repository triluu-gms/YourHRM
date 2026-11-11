/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
import { devBff } from './dev/bff-plugin';

// Admin web + ESS web share one codebase (ADR-0013). In production the SPA sits behind hrm-bff
// (ADR-0014): cookies only, never a token in JavaScript. The dev server emulates that BFF
// (dev/bff-plugin.ts) so the SPA code is identical in both environments.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [
      react(),
      devBff({
        apiTarget: env.YHRM_API_URL ?? 'http://localhost:8080',
        keycloak: {
          issuer: env.YHRM_KEYCLOAK_ISSUER ?? 'http://localhost:8180/realms/yourhrm',
          clientId: env.YHRM_KEYCLOAK_CLIENT_ID ?? 'yourhrm-web',
          clientSecret: env.YHRM_KEYCLOAK_CLIENT_SECRET,
        },
        staticToken: env.YHRM_DEV_TOKEN,
      }),
    ],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: { port: 5173, strictPort: true },
    build: {
      sourcemap: true,
      rollupOptions: {
        output: {
          manualChunks(id: string) {
            if (!id.includes('node_modules')) return undefined;
            if (/[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return 'react';
            if (/[\\/](antd|@ant-design|rc-[a-z-]+)[\\/]/.test(id)) return 'antd';
            if (id.includes('@tanstack')) return 'tanstack';
            return undefined;
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
      testTimeout: 15_000,
    },
  };
});
