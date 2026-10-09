// Vértices de un rectángulo a pantalla completa.
// vTexCoord: coordenada en el espacio de la textura de la cámara (ya transformada).
// vQuad: coordenada 0..1 en el espacio de salida (para la viñeta).
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
varying vec2 vQuad;

void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
    vQuad = aTexCoord.xy;
}
