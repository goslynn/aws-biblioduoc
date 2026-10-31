import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * Compilacion del sitio. Sale a dist/, que aws/pipeline/publish-web.sh sube
 * tal cual al bucket.
 *
 * base: '/' porque el sitio vive en la RAIZ del bucket y se abre como
 * https://<bucket>.s3.<region>.amazonaws.com/index.html: los bundles quedan en
 * /assets/ del mismo bucket.
 *
 * No hay "server" configurado a proposito: el proyecto no tiene entorno de
 * desarrollo (localhost no esta en las listas blancas de Cognito ni en el
 * CORS). Se comprueba en el sitio publicado.
 */
export default defineConfig({
  base: '/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
