import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Compilacion del sitio. Sale a dist/, que aws/pipeline/publish-web.sh empaqueta
 * para el servidor de Caddy (front/Caddyfile).
 *
 * base: '/' porque el sitio vive en la RAIZ del dominio: los bundles quedan en
 * /assets/, que es lo que el Caddyfile deja cachear.
 *
 * No hay "server" configurado a proposito: el proyecto no tiene entorno de
 * desarrollo (localhost no esta en las listas blancas de Cognito ni en el
 * CORS). Se comprueba en el sitio publicado.
 *
 * assetsInlineLimit: Vite incrusta en el CSS como data: lo que pesa menos de
 * 4 KB, y eso incluye los .woff de latin-ext de Lato. La CSP del Caddyfile los
 * bloquea (no admite data: en fuentes, y no hay por que abrirla): las fuentes
 * siempre salen como archivo. Para lo demas, undefined deja la regla de Vite.
 */
export default defineConfig({
  base: '/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsInlineLimit: (archivo) => (/\.woff2?$/.test(archivo) ? false : undefined),
  },
});
