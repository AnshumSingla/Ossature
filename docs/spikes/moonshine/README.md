# Moonshine browser spike (task A4.1)

Throwaway harness behind the numbers in `docs/architecture.md` ("Local STT spike: Moonshine").
Not part of the app.

1. `mkdir audio`, then generate the test clips (Windows only, uses the built-in SAPI voice; replace with real
   recordings for the A4.12 validation - same file names `audio/p00.wav`...):
   `powershell -ExecutionPolicy Bypass -File gen.ps1`
2. Serve this folder: `python serve.py 5199 plain` (or `python serve.py 5198 iso` for
   cross-origin isolation headers).
3. Open `http://127.0.0.1:5199/index.html` in a browser, then in the console:
   `await runSpike('onnx-community/moonshine-tiny-ONNX', 'q8')`
   It returns load time, speed, word error rate, jargon recall and the transcript per clip.

The page loads `@huggingface/transformers@3.8.0` from jsDelivr and the model from Hugging
Face on first run (~28 MB for tiny, ~63 MB for base), so it needs internet.

## Model bake-off (2026-10-12)

`index2.html` + `gen2.ps1` + `clips2.json` compare models on 40 clips (20 jargon-heavy phrases x 2
Windows voices). `mkdir audio2`, run `gen2.ps1`, serve the folder as above, open
`index2.html` and call e.g.
`await run('onnx-community/whisper-tiny.en', 'q8')` (or `'webgpu'` as the third argument with
`{encoder_model:'fp32',decoder_model_merged:'q4'}` as the dtype). Results are in
`docs/architecture.md` ("STT model bake-off").
