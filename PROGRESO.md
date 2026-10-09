# PROGRESO — LumaCam

Registro de trabajo de la noche (2026-10-09). Proyecto nuevo y separado de GrabaFondo: vive en
la rama huérfana `ccr-c0c8f514-ds68bb` (sin historial común con el resto del repo).

## Hecho

- [x] Entorno revisado: JDK 21 y Gradle 8.14.3 instalados. **No hay Android SDK** y
      `dl.google.com` (de donde se descargan el SDK y todo el Maven de Google: AGP, AndroidX,
      CameraX, Compose) está bloqueado por la política de red del entorno (403). No se intentó
      rodear el bloqueo. Por eso:
  - La lógica pura vive en un build independiente (`core/`) que sí compila y se prueba aquí:
    `./gradlew -p core test`.
  - El APK se compila en GitHub Actions (`.github/workflows/build.yml`), que sí tiene SDK.
- [x] Rama nueva, separada de GrabaFondo (nada de GrabaFondo se tocó).
- [x] `core` (Kotlin puro, sin Android):
  - Looks: Natural, Cálido, Cine, Vívido, Nocturno (`LookPreset.kt`) con intensidad 0–100 %.
  - Curva de tonos con spline monótona de 16 bits (contraste, sombras, luces, fade, shoulder).
  - Balance de blancos creativo (temperatura/tinte) en luz lineal, conservando luminancia.
  - LUT 3D 33³ generada a partir del "grade" de cada look (split toning, teal & orange,
    desaturación de sombras/luces).
  - Pipeline en CPU para la foto final idéntico al shader (contraste local, nitidez suave,
    WB, curva, saturación/vibrancia, LUT, viñeta), por franjas y resultado exacto.
  - Multi-frame: estimación de ruido, elección del frame más nítido, descarte de frames
    movidos, alineación en pirámide + ajuste fino, **modelo afín por bloques** (corrige la
    pequeña rotación de la mano), fusión robusta anti-fantasmas, en paralelo.
  - Planificador de captura según resolución y memoria (p. ej. 50 MP → 1 frame).
  - Planificador de zoom 0.6x (lógica pedida: 0.6x sólo si hay ultra gran angular real).
  - 57 pruebas unitarias (todas pasan, también en CI).
- [x] App Android (Kotlin + CameraX 1.4.1 + Compose):
  - `LookSurfaceProcessor`: OpenGL ES (CameraEffect de CameraX) que aplica el look a la vista
    previa y al video; pirámide de luminancia desenfocada para el contraste local.
  - Foto: resolución máxima (estándar o "máxima" con modos lentos), ráfaga + fusión,
    JPEG calidad 85–100 (95 por defecto) con EXIF copiado, en `Imágenes/LumaCam`.
  - Video: 1080p/4K, estabilización (de vista previa+video o sólo video, lo que ofrezca el
    teléfono), look aplicado, audio, en `Películas/LumaCam`.
  - Controles: compensación EV, AE-L, AWB-L, enfoque/medición por toque, zoom con pellizco y
    botones, flash, HDR (extensiones de CameraX o modo de escena de Camera2 si existen),
    exposición larga para Nocturno, mantener pulsado = ver original, volumen = disparador.
  - Diagnóstico: pantalla con todas las cámaras de Camera2, rangos de zoom, cámaras físicas,
    HDR/EIS/OIS, resoluciones, FPS, con botón Copiar, y botón "Medir rendimiento" (fusión de
    4×12 MP + look + JPEG con imágenes sintéticas) para ajustar tiempos al teléfono real.
  - Cuadrícula de tercios (se puede quitar en Ajustes).
- [x] Verificación del shader: `tools/shader-check/check.mjs` ejecuta los shaders reales de la
      app en WebGL (Chromium sin pantalla) y los compara con la CPU: los 5 looks coinciden con
      diferencia máxima de 1/255; con contraste local, media 0.36/255.
- [x] CI: core test + assembleDebug + testDebugUnitTest + lintDebug.

## Pendiente / no verificable sin el teléfono

- Probar en el Honor X7c real (ver lista de pruebas manuales en NOTAS.md).
- Rendimiento real del shader en la Adreno 613 (el contador de FPS de la vista previa lo dirá).
- Si MagicOS expone extensiones de CameraX (HDR/Noche) — la app lo detecta sola.
- Si la estabilización de video está disponible para apps de terceros en este modelo.
- Tiempo real de la fusión multi-frame a 12 MP (estimado: 2–5 s con 4–8 frames).

## Problemas encontrados

- `dl.google.com` bloqueado (403) → sin Android SDK local; compilación vía GitHub Actions.
- Maven Central devolvió 429 (demasiadas peticiones) una vez; al reintentar funcionó.
- El desenfoque en float daba resultados distintos por franjas (orden de sumas): se pasó a
  enteros en punto fijo y ahora es exacto.
