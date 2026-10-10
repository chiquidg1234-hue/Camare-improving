// Modo DUAL: una cámara dentro de una región del lienzo.
// aTexCoord ya viene calculada en Kotlin (recorte, giro, espejo y matriz del SurfaceTexture).
// vQuad: 0..1 dentro de la región (para la viñeta del look).
attribute vec4 aPosition;
attribute vec4 aTexCoord;
attribute vec2 aQuad;
varying vec2 vTexCoord;
varying vec2 vQuad;

void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord.xy;
    vQuad = aQuad;
}
