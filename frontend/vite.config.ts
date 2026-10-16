import { createReadStream, existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

const here = dirname(fileURLToPath(import.meta.url))
const ortDist = join(here, 'node_modules', 'onnxruntime-web', 'dist')
const ortFile = /^ort-wasm-simd-threaded.*\.(mjs|wasm)$/

// Serves the ONNX runtime's WebAssembly files from our own origin at /ort/ (dev middleware +
// copied into dist/ort at build), instead of letting the library fetch them from a public CDN
// at runtime. Used by the local speech-to-text worker.
function serveOnnxRuntime(): Plugin {
  return {
    name: 'serve-onnx-runtime',
    configureServer(server) {
      server.middlewares.use('/ort/', (req, res, next) => {
        const name = (req.url ?? '').split('?')[0].replace(/^\//, '')
        if (!ortFile.test(name) || !existsSync(join(ortDist, name))) return next()
        res.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript')
        createReadStream(join(ortDist, name)).pipe(res)
      })
    },
    generateBundle() {
      for (const name of readdirSync(ortDist).filter((f) => ortFile.test(f))) {
        this.emitFile({ type: 'asset', fileName: `ort/${name}`, source: readFileSync(join(ortDist, name)) })
      }
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), serveOnnxRuntime()],
  worker: { format: 'es' },
})
