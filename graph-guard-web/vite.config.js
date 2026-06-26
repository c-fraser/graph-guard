import { defineConfig } from 'vite'
import elmPlugin from 'vite-plugin-elm'
import path from 'path'

export default defineConfig({
  plugins: [elmPlugin()],
  publicDir: path.resolve(__dirname, 'src/public'),
  build: {
    outDir: path.resolve(__dirname, 'build/web'),
    emptyOutDir: true,
    lib: false,
    rollupOptions: {
      input: path.resolve(__dirname, 'src/js/index.js'),
      output: {
        entryFileNames: 'index.js',
        format: 'es',
      },
    },
  },
})
