// Copia con esquinas redondeadas y borde (ventanita del modo DUAL). Con uRadius = 0 y
// uBorder = 0 es una copia normal.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uSize;        // tamaño de la región en píxeles
uniform float uRadius;     // radio de las esquinas en píxeles
uniform float uBorder;     // grosor del borde en píxeles
uniform vec3 uBorderColor;
varying vec2 vQuad;

void main() {
    vec2 p = vQuad * uSize;
    vec2 h = uSize * 0.5;
    vec2 q = abs(p - h) - (h - vec2(uRadius));
    // Distancia con signo al rectángulo redondeado (negativa dentro).
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
    if (d > 0.5) discard;
    float alpha = clamp(0.5 - d, 0.0, 1.0);
    vec3 c = texture2D(uTex, vQuad).rgb;
    if (uBorder > 0.0) {
        c = mix(c, uBorderColor, smoothstep(-uBorder - 1.0, -uBorder, d));
    }
    gl_FragColor = vec4(c, alpha);
}
