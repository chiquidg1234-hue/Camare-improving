// Reduce la entrada a 1/4 de resolución guardando sólo la luminancia (4 muestras bilineales
// = promedio de un bloque de 4x4 píxeles).
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
uniform vec2 uTexel; // texel de la entrada

varying vec2 vTexCoord;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec3 c = texture2D(uTex, vTexCoord + uTexel * vec2(-1.0, -1.0)).rgb
           + texture2D(uTex, vTexCoord + uTexel * vec2( 1.0, -1.0)).rgb
           + texture2D(uTex, vTexCoord + uTexel * vec2(-1.0,  1.0)).rgb
           + texture2D(uTex, vTexCoord + uTexel * vec2( 1.0,  1.0)).rgb;
    float y = dot(c * 0.25, LUMA);
    gl_FragColor = vec4(y, y, y, 1.0);
}
