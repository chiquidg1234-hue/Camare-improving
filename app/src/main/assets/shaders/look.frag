// Pipeline del look (vista previa y video). Debe hacer exactamente las mismas cuentas que
// core/.../look/LookProcessor.kt (CPU, foto final). El código Kotlin antepone
// "#define EXTERNAL_OES" para la textura de la cámara; sin él usa sampler2D (pruebas).
#ifdef EXTERNAL_OES
#extension GL_OES_EGL_image_external : require
#endif

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#ifdef EXTERNAL_OES
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform sampler2D uBlur;      // luminancia desenfocada, en el espacio de la textura de entrada
uniform sampler2D uCurve;     // 256x1: R = byte alto, G = byte bajo (16 bits)
uniform sampler2D uLut;       // atlas (N*N) x N de la LUT 3D
uniform vec2 uTexel;          // 1/ancho, 1/alto de la entrada
uniform vec3 uGains;          // balance de blancos en luz lineal
uniform float uWb;            // 1.0 si hay que aplicar balance de blancos
uniform float uSaturation;    // 1.0 = neutro
uniform float uVibrance;
uniform float uLocalContrast; // ya multiplicado por LC_GAIN
uniform float uSharpness;     // ya multiplicado por SH_GAIN
uniform float uLutSize;
uniform float uLutMix;
uniform float uVignette;
uniform float uBypass;        // 1.0 = mostrar original (comparar antes/después)

varying vec2 vTexCoord;
varying vec2 vQuad;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float SHARPEN_THRESHOLD = 1.0 / 255.0;

float curve1(float x) {
    float u = (clamp(x, 0.0, 1.0) * 255.0 + 0.5) / 256.0;
    vec2 hl = texture2D(uCurve, vec2(u, 0.5)).rg;
    return (hl.r * 65280.0 + hl.g * 255.0) / 65535.0;
}

vec3 applyLut(vec3 c) {
    float n = uLutSize;
    float n1 = n - 1.0;
    float b = c.b * n1;
    float b0 = min(floor(b), n1 - 1.0);
    float t = b - b0;
    vec2 base = vec2((c.r * n1 + 0.5) / (n * n), (c.g * n1 + 0.5) / n);
    vec2 uv0 = base + vec2(b0 / n, 0.0);
    vec2 uv1 = uv0 + vec2(1.0 / n, 0.0);
    return mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, t);
}

void main() {
    vec3 c0 = texture2D(uTex, vTexCoord).rgb;
    if (uBypass > 0.5) {
        gl_FragColor = vec4(c0, 1.0);
        return;
    }
    float y = dot(c0, LUMA);
    float delta = 0.0;

    // 1) Contraste local (claridad) y nitidez suave sobre la luminancia de la entrada.
    if (uLocalContrast > 0.0) {
        float bl = texture2D(uBlur, vTexCoord).r;
        float w = 4.0 * y * (1.0 - y);
        delta += clamp(uLocalContrast * (y - bl) * w, -0.25, 0.25);
    }
    if (uSharpness > 0.0) {
        float yl = dot(texture2D(uTex, vTexCoord - vec2(uTexel.x, 0.0)).rgb, LUMA);
        float yr = dot(texture2D(uTex, vTexCoord + vec2(uTexel.x, 0.0)).rgb, LUMA);
        float yu = dot(texture2D(uTex, vTexCoord - vec2(0.0, uTexel.y)).rgb, LUMA);
        float yd = dot(texture2D(uTex, vTexCoord + vec2(0.0, uTexel.y)).rgb, LUMA);
        float d = y - 0.25 * (yl + yr + yu + yd);
        float ad = abs(d) - SHARPEN_THRESHOLD;
        if (ad > 0.0) {
            delta += clamp(uSharpness * sign(d) * ad, -0.2, 0.2);
        }
    }
    vec3 c = clamp(c0 + delta, 0.0, 1.0);

    // 2) Balance de blancos en luz lineal.
    if (uWb > 0.5) {
        c = clamp(pow(pow(c, vec3(2.2)) * uGains, vec3(1.0 / 2.2)), 0.0, 1.0);
    }

    // 3) Curva de tonos por canal.
    c = vec3(curve1(c.r), curve1(c.g), curve1(c.b));

    // 4) Saturación y vibrancia.
    if (uSaturation != 1.0 || uVibrance != 0.0) {
        float l = dot(c, LUMA);
        float s = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
        float k = uSaturation * (1.0 + uVibrance * (1.0 - s));
        c = clamp(vec3(l) + (c - vec3(l)) * k, 0.0, 1.0);
    }

    // 5) LUT 3D del look, mezclada por intensidad.
    if (uLutMix > 0.0) {
        c = mix(c, applyLut(c), uLutMix);
    }

    // 6) Viñeta.
    if (uVignette > 0.0) {
        float d = length(vQuad - vec2(0.5)) * 1.4142135;
        c *= 1.0 - uVignette * 0.6 * smoothstep(0.3, 1.0, d);
    }

    gl_FragColor = vec4(c, 1.0);
}
