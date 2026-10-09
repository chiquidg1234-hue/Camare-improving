// Desenfoque gaussiano separable de 9 muestras (una dirección por pasada).
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uStep;        // paso entre muestras (en coordenadas de textura)
uniform float uWeights[5]; // pesos para desplazamientos 0..4 (normalizados)

varying vec2 vTexCoord;

void main() {
    vec4 s = texture2D(uTex, vTexCoord) * uWeights[0];
    for (int i = 1; i < 5; i++) {
        vec2 o = uStep * float(i);
        s += (texture2D(uTex, vTexCoord + o) + texture2D(uTex, vTexCoord - o)) * uWeights[i];
    }
    gl_FragColor = vec4(s.rgb, 1.0);
}
