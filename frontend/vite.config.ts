import { createReadStream, existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv, type Plugin } from 'vite'
import { securityHeaders, toNetlifyHeaders, toNginxHeaders } from './security-headers.mjs'

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
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, here, 'VITE_')
  const headers = securityHeaders({
    // The page that will actually be served (index.html in the project root); throws if it has an
    // inline script other than the theme bootstrap, since CSP would block it.
    html: readFileSync(join(here, 'index.html'), 'utf-8'),
    apiBase: env.VITE_API_BASE_URL ?? 'http://localhost:8080',
    wsUrl: env.VITE_WS_URL ?? 'ws://localhost:8080/ws/interview',
    modelHost: env.VITE_STT_MODEL_HOST ?? '',
  })

  // Emits the headers for the host that serves dist/ (see security-headers.mjs).
  const emitSecurityHeaders: Plugin = {
    name: 'emit-security-headers',
    apply: 'build',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: '_headers', source: toNetlifyHeaders(headers) })
      this.emitFile({ type: 'asset', fileName: 'nginx-security-headers.conf', source: toNginxHeaders(headers) })
    },
  }

  return {
    plugins: [react(), serveOnnxRuntime(), emitSecurityHeaders],
    worker: { format: 'es' },
    // `vite preview` serves the built site; sending the production headers here means the policy
    // is exercised locally. (The dev server deliberately does not: HMR needs inline scripts.)
    preview: { headers },
  }
})
