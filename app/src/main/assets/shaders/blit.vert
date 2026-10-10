// Copia de una textura normal (lienzo del modo DUAL) a una región; vQuad = 0..1 en la región.
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vQuad;

void main() {
    gl_Position = aPosition;
    vQuad = aTexCoord.xy;
}
