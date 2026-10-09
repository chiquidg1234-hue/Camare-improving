# NOTAS — LumaCam para Honor X7c

App de cámara nativa (Kotlin + CameraX + OpenGL ES + Jetpack Compose) con procesado propio:
looks en tiempo real en la vista previa y el video, y foto multi-frame con el mismo look.

> **Dónde está:** rama `ccr-c0c8f514-ds68bb` del repo `chiquidg1234-hue/Camera-`. Es una rama
> **huérfana**: no comparte historial con GrabaFondo ni toca nada de él (la rama
> `ccr-b0160724-ouuaqg` y la etiqueta `grabafondo` siguen intactas). Para pasarla al repo nuevo
> ver "Mover a un repo nuevo" al final.

---

## 1. Cómo instalar el APK en el teléfono

El APK se compila solo en GitHub Actions con cada cambio (workflow **"LumaCam APK"**).

1. En el navegador (puede ser el del teléfono, con tu sesión de GitHub iniciada) abre
   <https://github.com/chiquidg1234-hue/Camera-/actions> y elige el workflow **LumaCam APK**.
2. Toca la ejecución más reciente de la rama `ccr-c0c8f514-ds68bb` (marca verde ✔).
3. Abajo, en **Artifacts**, descarga **LumaCam-apk** (llega como `.zip`).
4. Abre el `.zip` con el gestor de archivos del Honor y toca `app-debug.apk`.
5. Si MagicOS lo bloquea: Ajustes → Seguridad → *Instalar apps de fuentes desconocidas* (o
   "Instalación de apps externas") y permite al gestor de archivos/navegador. Si aparece el
   aviso de "app no verificada", elige *Instalar de todos modos*.
6. Abre **LumaCam** y concede permisos de cámara y micrófono.

Alternativa con cable: `adb install -r app-debug.apk` (con depuración USB activada).

Cada APK nuevo se instala **encima** del anterior (misma clave de firma fija y número de
versión creciente), sin perder ajustes.

*No publiqué un "Release" con enlace directo (como en GrabaFondo) porque crearía una etiqueta
en este repo y pediste no cambiarlo. En el repo nuevo lo activo y tendrás un enlace fijo.*

---

## 2. Qué hace la app

**Vista previa en vivo con GPU** (OpenGL ES, efecto de CameraX):
curva de tonos (contraste, sombras, luces), contraste local ("claridad"), nitidez suave con
umbral anti-ruido, balance de blancos creativo (temperatura y tinte), saturación y vibrancia.

**Looks** (con deslizador de intensidad 0–100 %): Natural, Cálido, Cine, Vívido, Nocturno.
Cada uno es un conjunto de ajustes + una LUT 3D de 33³ generada al vuelo + viñeta.
Mantén pulsado el visor para ver el **original** y comparar.

**Foto:**
- Resolución "Estándar" (la mayor rápida, normalmente 12.5 MP con píxeles agrupados) o
  "Máxima" (incluye modos lentos, p. ej. 50 MP, si MagicOS los ofrece a otras apps).
- **Multi-frame:** ráfaga de 4 fotos (8 en Nocturno), elige la más nítida, descarta las movidas,
  alinea el resto y las promedia con rechazo de movimiento (sin "fantasmas"). Baja el ruido.
  Durante la ráfaga se bloquean exposición y balance de blancos.
- Se aplica **el mismo look** que ves en pantalla (misma matemática en CPU, verificada contra el
  shader) y se guarda JPEG calidad 95 (ajustable 85–100) con los datos EXIF de la cámara, en
  *Imágenes/LumaCam*.

**Video:** 1080p (o 4K), estabilización activada si el teléfono la ofrece, look aplicado en la
grabación, audio. Se guarda en *Películas/LumaCam*.

**Controles:** compensación de exposición (EV), bloqueo AE (AE-L) y AWB (AWB-L), enfoque y
medición por toque, zoom con pellizco y botones, flash, cámara frontal, botones de volumen
como disparador, HDR del fabricante o de Camera2 **sólo si el teléfono lo ofrece** (si no,
la opción no aparece), exposición larga en el look Nocturno.

**Diagnóstico (botón ⓘ):** lista todas las cámaras que ve Camera2, sus rangos de zoom
(`CONTROL_ZOOM_RATIO_RANGE`), cámaras físicas, resoluciones, HDR/EIS/OIS, FPS y si OpenGL
arrancó. Tiene botón **Copiar**: pégame ese texto mañana y ajusto la app a tu teléfono real.

### Zoom 0.6x

Al arrancar la app lee `CONTROL_ZOOM_RATIO_RANGE` y las cámaras físicas:
- Si la cámara lógica baja de 1.0x → arranca en 0.6x (o en su mínimo si no llega a 0.6x).
- Si hay una cámara ultra gran angular independiente → arranca en ella (botón "0.6x").
- **Si no existe** (lo esperado en el X7c) → arranca en **1.0x**, no simula un 0.6x falso y
  muestra el aviso: *"Este teléfono no ofrece a las apps una cámara ultra gran angular: el zoom
  mínimo real es 1.0x. No se simula un 0.6x falso."*

El Honor X7c 5G (Snapdragon 4 Gen 2) tiene principal de 50 MP + profundidad de 2 MP; la de
profundidad no sirve para fotos, así que **no habrá 0.6x**. El diagnóstico lo confirmará.

---

## 3. Pruebas manuales que tienes que hacer tú

Marca cada una. Si algo falla, copia el **Diagnóstico** y dime qué pasó.

**Arranque**
- [ ] La app abre, pide permisos y se ve la imagen. El contador de abajo a la derecha marca ~30 fps.
- [ ] Sale el aviso de zoom 1.0x (o, si tu teléfono sí tuviera ultra gran angular, arranca en 0.6x).
- [ ] Botón ⓘ: el diagnóstico dice "OpenGL: ES 3.0 OK" (o 2.0) y "look en vista previa: true".

**Cada look** (en foto y en video)
- [ ] Natural: mejora sutil (más detalle y color, sin exagerar).
- [ ] Cálido: tonos dorados, piel más cálida.
- [ ] Cine: sombras verde azuladas, piel anaranjada, negros algo levantados, viñeta.
- [ ] Vívido: colores y contraste fuertes, sin que la piel se vea naranja.
- [ ] Nocturno: sombras levantadas, luces contenidas, menos ruido de color.
- [ ] Deslizador de intensidad de 0 % a 100 %: el cambio es progresivo; al 0 % se ve como el original.
- [ ] Mantener pulsado el visor → aparece "ORIGINAL"; al soltar vuelve el look.
- [ ] La foto guardada se ve igual que la vista previa (mismo look).

**Foto de día**
- [ ] Foto con multi-frame (por defecto 4 frames): se ve "Mantén quieto… 1/4", luego
      "Fusionando…", y en unos segundos "Foto guardada". Anota cuánto tarda.
- [ ] Compara con la cámara de Honor: detalle, ruido, color.
- [ ] Con gente moviéndose: no deben aparecer "fantasmas" ni bordes dobles.
- [ ] Foto vertical y horizontal: ambas quedan bien orientadas en la galería.
- [ ] Ajustes → Resolución "Máxima": mira qué resolución dice y haz una foto (tardará más).

**Foto de noche**
- [ ] Look Nocturno + multi-frame (8 frames), apoyando el teléfono o muy quieto.
- [ ] Compara el ruido con una foto de 1 frame (Ajustes → desactiva Multi-frame).
- [ ] Prueba con y sin "Exposición larga en look Nocturno".
- [ ] Si en Ajustes aparece "Modo del fabricante" → prueba "Noche"/"HDR".

**Video**
- [ ] Graba 30 s a 1080p con Natural y otros 30 s con Cine: el look sale en el video.
- [ ] Ajustes → Video: mira el estado de la estabilización. Graba caminando con y sin ella.
- [ ] El audio se oye; vertical/horizontal quedan bien orientados.
- [ ] Prueba 4K: si el contador de fps baja mucho o el video da saltos, quédate en 1080p.

**Controles**
- [ ] Toca la imagen: aparece el círculo y enfoca/mide ahí.
- [ ] EV en Ajustes: la imagen se aclara/oscurece.
- [ ] AE-L y AWB-L: al apuntar a otra luz la exposición/color no cambian.
- [ ] Zoom: botones 1x y 2x, y pellizco.
- [ ] Flash (si aparece), cámara frontal, botones de volumen como disparador.
- [ ] Toca la miniatura: abre la última foto o video.

---

## 4. Qué está verificado y qué no

**Verificado aquí (sin teléfono):**
- Compila: `./gradlew assembleDebug` en GitHub Actions ✔ (APK de ~10 MB).
- Pruebas unitarias de la lógica: 52 en `core` (presets, curvas, LUT, balance de blancos,
  pipeline por franjas, zoom 0.6x, planificador de captura, alineación, fusión, anti-fantasmas)
  + 1 en la app. Todas pasan ✔.
- Lint de Android sin errores ✔.
- Los shaders reales de la app se compilaron y ejecutaron en WebGL (Chromium) y se compararon
  con el procesado de la foto: los 5 looks coinciden con diferencia máxima de 1/255 ✔
  (`node tools/shader-check/check.mjs`).

**No lo pude verificar (necesita el teléfono):**
- Que la vista previa con el efecto OpenGL funcione en la Adreno 613 y a cuántos fps.
- La orientación correcta de la imagen en la vista previa y en el video con el efecto.
- Qué resoluciones, HDR, extensiones y estabilización expone MagicOS a apps de terceros.
- Tiempos reales de la ráfaga y la fusión (estimo 2–5 s a 12.5 MP con 4–8 frames).
- Consumo de memoria real en modo "Máxima" (la app reduce frames/tamaño si falta memoria).

**Decisiones tomadas sin preguntarte** (cámbialas si quieres):
- Nombre de la app: *LumaCam*, paquete `com.lumacam`.
- Intensidad por defecto del look 80 %, look Natural, multi-frame activado, JPEG 95.
- La pantalla queda fija en vertical; los iconos no giran, pero las fotos/videos sí se guardan
  con la orientación correcta (sensor de orientación).
- Vista previa 4:3 en foto (igual que la foto) y 16:9 en video.
- Con flash o con un modo del fabricante activo se usa 1 frame.
- Multi-frame sólo hasta 25 MP; por encima (modo 50 MP) se usa 1 frame.

---

## 5. Limitaciones del hardware (Honor X7c 5G, Snapdragon 4 Gen 2)

- **Una sola cámara útil atrás** (50 MP + 2 MP de profundidad): **no hay 0.6x real**; el 2x es
  recorte digital. La app no inventa un 0.6x.
- GPU Adreno 613 modesta: el look en 1080p debería ir a 30 fps; en 4K puede perder frames.
- Probablemente **sin estabilización óptica (OIS)**; la electrónica (EIS) puede no estar
  disponible para apps de terceros. La app lo detecta y lo dice.
- Las apps de terceros no reciben el procesado con IA de la cámara de Honor; lo compensamos
  con el multi-frame y los looks, pero la cámara de Honor puede ganar en algunas escenas
  (sobre todo HDR fuerte a contraluz si MagicOS no expone HDR).
- El multi-frame trabaja con los JPEG que entrega la cámara (no RAW): baja el ruido de forma
  visible en sombras y de noche, pero no hace milagros si la foto sale movida.
- El modo de 50 MP real (sin agrupar píxeles) sólo existe si MagicOS lo ofrece a otras apps;
  con poca luz suele verse peor que 12.5 MP.

---

## 6. Para desarrollar

- Lógica pura (sin Android SDK): `./gradlew -p core test`
- App completa (necesita Android SDK, o GitHub Actions): `./gradlew assembleDebug testDebugUnitTest lintDebug`
- Verificación de shaders: `./gradlew -p core test` y luego `node tools/shader-check/check.mjs`
  (necesita el paquete npm `playwright` y Chromium).

Estructura:
- `core/` — looks, curvas, LUT, pipeline CPU, zoom, fusión multi-frame (Kotlin puro + pruebas).
- `app/src/main/assets/shaders/` — shaders GLSL de la vista previa/video.
- `app/src/main/java/com/lumacam/gl/` — EGL, renderer y procesador de superficies de CameraX.
- `app/src/main/java/com/lumacam/camera/` — sesión de CameraX y detección del dispositivo.
- `app/src/main/java/com/lumacam/photo/` — pipeline de la foto final.
- `app/src/main/java/com/lumacam/ui/` — interfaz Compose y ViewModel.

## 7. Mover a un repo nuevo

Todo el proyecto está en esta única rama, sin historial de GrabaFondo. Para pasarlo:

```bash
git clone --branch ccr-c0c8f514-ds68bb --single-branch https://github.com/chiquidg1234-hue/Camera- lumacam
cd lumacam
git remote set-url origin https://github.com/<tu-usuario>/<repo-nuevo>.git
git push -u origin ccr-c0c8f514-ds68bb:main
```

(O dame el repo nuevo y lo hago yo.) Después se puede borrar esta rama del repo viejo.
