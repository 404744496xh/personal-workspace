import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  base: '/plugins/personal-workspace/assets/',
  plugins: [vue(), tailwindcss()],
  build: {
    outDir: 'build/dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: 'workspace.js',
        chunkFileNames: 'workspace-[name].js',
        assetFileNames: 'workspace.[ext]'
      }
    }
  }
})
