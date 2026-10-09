// Ejecuta los shaders reales de la app (app/src/main/assets/shaders) en WebGL con Chromium
// sin pantalla y compara la salida con la del pipeline en CPU (core/LookProcessor).
//
// Uso (desde la raíz del repo):
//   ./gradlew -p core test --tests '*ShaderParityCasesTest*'
//   node tools/shader-check/check.mjs
// Requiere el paquete npm "playwright" y un Chromium (PLAYWRIGHT_BROWSERS_PATH).
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  const globalRoot = process.env.NODE_GLOBAL_ROOT || '/opt/node22/lib/node_modules';
  ({ chromium } = require(join(globalRoot, 'playwright')));
}

const shaders = {
  vert: readFileSync(join(root, 'app/src/main/assets/shaders/look.vert'), 'utf8'),
  look: readFileSync(join(root, 'app/src/main/assets/shaders/look.frag'), 'utf8'),
  down: readFileSync(join(root, 'app/src/main/assets/shaders/luma_down.frag'), 'utf8'),
  blur: readFileSync(join(root, 'app/src/main/assets/shaders/blur.frag'), 'utf8'),
};
const data = JSON.parse(readFileSync(join(root, 'core/build/shader-check/cases.json'), 'utf8'));

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
page.on('console', (m) => console.log('[página]', m.text()));
await page.setContent('<canvas id="c"></canvas>');

const results = await page.evaluate(({ shaders, data }) => {
  const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  const W = data.width, H = data.height;
  const canvas = document.getElementById('c');
  canvas.width = W; canvas.height = H;
  const gl = canvas.getContext('webgl', { antialias: false, preserveDrawingBuffer: true, premultipliedAlpha: false });
  if (!gl) return { error: 'Sin WebGL' };

  function compile(type, src, name) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(name + ': ' + gl.getShaderInfoLog(s));
    return s;
  }
  function program(fragSrc, name) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, shaders.vert, 'look.vert'));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragSrc, name));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(name + ' link: ' + gl.getProgramInfoLog(p));
    return p;
  }
  // Sin "#define EXTERNAL_OES": los shaders usan sampler2D, igual que en la app salvo el tipo.
  const progLook = program(shaders.look, 'look.frag');
  const progDown = program(shaders.down, 'luma_down.frag');
  const progBlur = program(shaders.blur, 'blur.frag');

  const pos = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, pos);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const tex = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, tex);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
  const identity = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);

  function draw(p) {
    gl.useProgram(p);
    const aPos = gl.getAttribLocation(p, 'aPosition');
    const aTex = gl.getAttribLocation(p, 'aTexCoord');
    gl.bindBuffer(gl.ARRAY_BUFFER, pos);
    gl.enableVertexAttribArray(aPos);
    gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, tex);
    gl.enableVertexAttribArray(aTex);
    gl.vertexAttribPointer(aTex, 2, gl.FLOAT, false, 0, 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
  function texture(w, h, bytes) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
    return t;
  }
  function fbo(t) {
    const f = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    return f;
  }
  const u = (p, n) => gl.getUniformLocation(p, n);

  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
  const inputTex = texture(W, H, b64(data.input));
  const bw = Math.max(1, Math.floor(W / 4)), bh = Math.max(1, Math.floor(H / 4));
  const blurA = texture(bw, bh, null), blurB = texture(bw, bh, null);
  const fboA = fbo(blurA), fboB = fbo(blurB);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);

  const out = [];
  for (const c of data.cases) {
    const curveTex = texture(256, 1, b64(c.curve));
    const lutTex = texture(c.lutSize * c.lutSize, c.lutSize, b64(c.lut));

    // Pirámide de luminancia desenfocada (igual que LookRenderer.prepareFrame).
    if (c.localContrast > 0) {
      const sigmaQ = Math.max(0.35, c.sigmaFrac * Math.min(W, H) / 4);
      const spacing = Math.max(1, sigmaQ / 2);
      const wts = []; let sum = 0;
      for (let k = 0; k <= 4; k++) { const d = k * spacing; wts[k] = Math.exp(-(d * d) / (2 * sigmaQ * sigmaQ)); sum += k === 0 ? wts[k] : 2 * wts[k]; }
      for (let k = 0; k <= 4; k++) wts[k] /= sum;
      gl.bindFramebuffer(gl.FRAMEBUFFER, fboA); gl.viewport(0, 0, bw, bh);
      gl.useProgram(progDown);
      gl.uniformMatrix4fv(u(progDown, 'uTexMatrix'), false, identity);
      gl.uniform2f(u(progDown, 'uTexel'), 1 / W, 1 / H);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, inputTex);
      gl.uniform1i(u(progDown, 'uTex'), 0);
      draw(progDown);
      gl.useProgram(progBlur);
      gl.uniformMatrix4fv(u(progBlur, 'uTexMatrix'), false, identity);
      gl.uniform1fv(u(progBlur, 'uWeights'), new Float32Array(wts));
      gl.uniform1i(u(progBlur, 'uTex'), 0);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fboB); gl.bindTexture(gl.TEXTURE_2D, blurA);
      gl.uniform2f(u(progBlur, 'uStep'), spacing / bw, 0); draw(progBlur);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fboA); gl.bindTexture(gl.TEXTURE_2D, blurB);
      gl.uniform2f(u(progBlur, 'uStep'), 0, spacing / bh); draw(progBlur);
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    const p = progLook;
    gl.useProgram(p);
    gl.uniformMatrix4fv(u(p, 'uTexMatrix'), false, identity);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, inputTex); gl.uniform1i(u(p, 'uTex'), 0);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, blurA); gl.uniform1i(u(p, 'uBlur'), 1);
    gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, curveTex); gl.uniform1i(u(p, 'uCurve'), 2);
    gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D, lutTex); gl.uniform1i(u(p, 'uLut'), 3);
    gl.uniform2f(u(p, 'uTexel'), 1 / W, 1 / H);
    gl.uniform3f(u(p, 'uGains'), c.gains[0], c.gains[1], c.gains[2]);
    gl.uniform1f(u(p, 'uWb'), c.wb);
    gl.uniform1f(u(p, 'uSaturation'), c.saturation);
    gl.uniform1f(u(p, 'uVibrance'), c.vibrance);
    gl.uniform1f(u(p, 'uLocalContrast'), c.localContrast);
    gl.uniform1f(u(p, 'uSharpness'), c.sharpness);
    gl.uniform1f(u(p, 'uLutSize'), c.lutSize);
    gl.uniform1f(u(p, 'uLutMix'), c.lutMix);
    gl.uniform1f(u(p, 'uVignette'), c.vignette);
    gl.uniform1f(u(p, 'uBypass'), 0);
    draw(p);
    gl.activeTexture(gl.TEXTURE0);
    const px = new Uint8Array(W * H * 4);
    gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    const err = gl.getError();

    const exp = b64(c.expected);
    let max = 0, sum = 0, over2 = 0, n = 0;
    for (let i = 0; i < px.length; i += 4) {
      for (let k = 0; k < 3; k++) {
        const d = Math.abs(px[i + k] - exp[i + k]);
        if (d > max) max = d;
        if (d > 2) over2++;
        sum += d; n++;
      }
    }
    out.push({ name: c.name, exact: c.exactLocalContrast, max, mean: sum / n, over2: over2 / n, glError: err });
  }
  return { renderer: gl.getParameter(gl.VERSION), out };
}, { shaders, data });

await browser.close();
if (results.error) { console.error(results.error); process.exit(2); }
console.log('WebGL:', results.renderer);
let ok = true;
for (const r of results.out) {
  // Exacto (sin contraste local): mismas cuentas → diferencia de redondeo (<= 2/255).
  // Con contraste local el desenfoque de GPU es una pirámide distinta: tolerancia amplia.
  const pass = r.glError === 0 && (r.exact ? r.max <= 2 : (r.mean < 2.5 && r.max <= 40));
  if (!pass) ok = false;
  console.log(`${pass ? 'OK ' : 'MAL'} ${r.name.padEnd(16)} máx ${String(r.max).padStart(3)}  media ${r.mean.toFixed(3)}  >2: ${(r.over2 * 100).toFixed(2)}%`);
}
process.exit(ok ? 0 : 1);
