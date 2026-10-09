# NOTAS — LumaCam para Honor X7c

App de cámara nativa (Kotlin + CameraX + OpenGL ES + Jetpack Compose) con procesado propio:
looks en tiempo real en la vista previa y el video, y foto multi-frame con el mismo look.

> **Dónde está:** rama `main` del repo
> [`chiquidg1234-hue/Camare-improving`](https://github.com/chiquidg1234-hue/Camare-improving)
> (con todo el historial). En el repo viejo `Camera-` sigue la rama `ccr-c0c8f514-ds68bb` como
> copia; GrabaFondo no se tocó.

---

## 1. Cómo instalar (QR de invitación)

**Página de descarga (la abre el QR):** <https://chiquidg1234-hue.github.io/Camare-improving/>
(GitHub Pages, rama `gh-pages`; el código de la página está en `web/`).

**Enlace directo del APK:**
<https://github.com/chiquidg1234-hue/Camare-improving/releases/latest/download/LumaCam.apk>

1. Escanea el QR (`docs/qr-descarga.png`, también en la Release) con la cámara del teléfono.
2. **Si se abre una ventana con una ✕ arriba a la izquierda** (la que abren la cámara, Google
   Lens o los lectores de QR): toca **⋮ → «Abrir en Chrome»**. Ver el problema más abajo.
3. En Chrome toca **Descargar LumaCam** y, cuando Chrome pregunte, **Descargar / Descargar de
   todos modos**.
4. **Abrir → Instalar**. Si Android lo pide: permite *Instalar apps desconocidas* para Chrome;
   si sale el aviso de app no verificada, *Instalar de todos modos*.
5. Abre **LumaCam** y concede cámara y micrófono.

### Por qué la descarga se quedaba en «Descargando… 37/37 MB»

No es culpa del APK (por eso pasa igual con otras apps): es Chrome para Android.

- Desde 2025-2026 Chrome **no termina ninguna descarga de .apk hasta que el usuario toca
  «Descargar» en un aviso de seguridad**. Llega al 100 % y espera esa respuesta (código de
  Chromium: `ChromeDownloadManagerDelegate::ShouldCompleteDownload` →
  `DangerousDownloadDialogBridge`).
- Al escanear un QR, el enlace se abre en una **pestaña personalizada de Chrome** (la ventana con
  ✕, flecha y ⋮). Si el enlace es directamente el `.apk`, esa ventana se convierte en la pantalla
  «Descargando…», que no sabe mostrar ese aviso: la pantalla sólo entiende «en curso»,
  «completa», «pausada» y «cancelada» (`DownloadInterstitialMediator`). En algunas versiones de
  Chrome el aviso ni siquiera llega a mostrarse. Resultado: 37/37 MB y no pasa nada.
- En la app Chrome normal el aviso sí aparece y la descarga termina.

**Qué se cambió:**
1. El QR ya no apunta al `.apk` sino a la **página de descarga**, que explica el paso «Abrir en
   Chrome» (y en navegadores internos de WhatsApp/Instagram ofrece un botón que abre Chrome).
2. **«Enviar la app»** (botón compartir de la app): manda el APK por Quick Share, Bluetooth o
   WhatsApp. Quien lo recibe lo abre y toca Instalar, sin navegador.
3. **Actualizaciones dentro de la app:** LumaCam lee `version.json` de la Release y, si hay
   versión nueva, muestra «Nueva versión… Actualizar». Descarga el APK ella misma (sin Chrome),
   comprueba el SHA-256 y Android pide confirmar. La primera vez pide permitir «Instalar apps
   desconocidas» para LumaCam.

**Para tu otra app**, lo mismo sirve: QR → página (no al `.apk`), abrir en Chrome, y enviar o
actualizar el APK desde la propia app.

**Si ya tienes una descarga trabada:** cierra la ventana con la ✕, abre Chrome → ⋮ → Descargas,
borra LumaCam.apk si aparece a medias, y vuelve a abrir la página en Chrome.

**Invitar a otra persona:** en la app, botón **compartir** (arriba) → QR de la página, **Enviar
la app directamente** (lo más fácil si están cerca: Quick Share) o **Compartir enlace**.

**Cómo se actualiza:** cada cambio en `main` hace que GitHub Actions compile el APK, reemplace la
Release **lumacam** (`LumaCam.apk` + `version.json` + QR) y actualice la página. El enlace y el
QR no cambian nunca; cada versión nueva se instala encima de la anterior sin perder ajustes
(misma firma y número de versión creciente).

Notas:
- El APK es para teléfonos ARM de 64 bits (arm64-v8a): el Honor X7c y casi todos los Android
  de los últimos años. Teléfonos muy viejos de 32 bits no lo podrán instalar.
- Es un APK firmado con una clave de pruebas (no de Play Store); por eso Android avisa al
  instalar. Para Play Store haría falta una clave propia y una cuenta de desarrollador.

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
- **Alineación con rotación:** además del desplazamiento, corrige el pequeño giro de la mano entre
  frames (modelo afín medido en 48 bloques), para que las esquinas también salgan nítidas.
- **Menos ruido de color:** filtro guiado por la luminancia que quita las manchas de color de las
  fotos con poca luz sin emborronar bordes. Su fuerza depende del look (máxima en Nocturno) y del
  ruido medido en cada foto (de día casi no actúa). Se puede subir en Ajustes.
- Se aplica **el mismo look** que ves en pantalla (misma matemática en CPU, verificada contra el
  shader) y se guarda JPEG calidad 95 (ajustable 85–100) con los datos EXIF de la cámara, en
  *Imágenes/LumaCam*.

**Video:** 1080p (o 4K), estabilización activada si el teléfono la ofrece, look aplicado en la
grabación, audio. Se guarda en *Películas/LumaCam*.

**Controles:** compensación de exposición (EV), bloqueo AE (AE-L) y AWB (AWB-L), enfoque y
medición por toque, zoom con pellizco y botones, flash, cámara frontal, botones de volumen
como disparador, HDR del fabricante o de Camera2 **sólo si el teléfono lo ofrece** (si no,
la opción no aparece), exposición larga en el look Nocturno, cuadrícula de tercios, sonido de
obturador del sistema y vibración al disparar. La miniatura abre la última foto o video.

**Diagnóstico (botón ⓘ):** lista todas las cámaras que ve Camera2, sus rangos de zoom
(`CONTROL_ZOOM_RATIO_RANGE`), cámaras físicas, resoluciones, HDR/EIS/OIS, FPS y si OpenGL
arrancó. Tiene botón **Copiar**: pégame ese texto mañana y ajusto la app a tu teléfono real.
También tiene **"Medir rendimiento del procesado"**: mide en tu teléfono cuánto tarda la fusión
de 4 fotos de 12 MP, el look y el JPEG (pégame también ese resultado).

**Protecciones** (porque no pude probar en tu teléfono):
- Si la vista previa con efectos OpenGL no recibe imágenes en ~3,5 s, la app la reabre sola sin
  efectos y avisa (la foto sigue llevando el look). También hay un interruptor manual en Ajustes:
  *"Look en la vista previa y el video (GPU)"*.
- Si una combinación (look + estabilización, modo del fabricante, etc.) no es compatible, prueba
  la siguiente en orden y te dice qué se desactivó.
- Si sales de la app mientras procesa una foto, la foto se termina de guardar igual.

### Modo POSES (nuevo)

Pestaña **POSES** (entre FOTO y VIDEO). Es el modo foto con una guía para posar:

- **Ángulo de la cámara:** con el sensor de gravedad del teléfono detecta si la cámara está
  *cenital* (justo encima), en *picado* (desde arriba), *a la altura de los ojos*, en
  *contrapicado* (desde abajo) o *desde el suelo*, y explica qué efecto da ese ángulo (por
  ejemplo, el picado estiliza la cara; el contrapicado hace ver más alto).
- **Poses sugeridas para ese ángulo** (15 en total, al menos 3 por ángulo), con consejos que van
  rotando. Con las flechas ◀ ▶ cambias de pose. Con la cámara frontal salen primero las de
  selfie.
- **Silueta guía** de la pose en el visor y **línea de nivel** (verde cuando el teléfono está
  recto).
- **Detectar mi pose** (ML Kit, el modelo va dentro de la app, no necesita internet): dibuja tu
  esqueleto, da un **% de coincidencia** con la pose elegida y un consejo concreto ("Sube el
  brazo de la derecha", "Más cerca"…). Funciona aunque hagas la pose con el otro lado.
- **Disparo automático:** si mantienes la pose (≥ 80 % de coincidencia) cuenta 3, 2, 1 y hace
  la foto sola (con el look y el multi-frame de siempre). Ideal para selfies o con el teléfono
  apoyado.

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
- [ ] En ⓘ toca "Medir rendimiento del procesado" y apunta los tiempos.
- [ ] Si la vista previa se ve negra o aparece el aviso de que se desactivó el look en la vista
      previa, dímelo (y copia el diagnóstico).

**Cada look** (en foto y en video)
- [ ] Natural: mejora sutil (más detalle y color, sin exagerar).
- [ ] Cálido: tonos dorados, piel más cálida.
- [ ] Cine: sombras verde azuladas, piel anaranjada, negros algo levantados, viñeta.
- [ ] Vívido: colores y contraste fuertes; la piel algo más viva pero no naranja.
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

**Modo POSES**
- [ ] Pestaña POSES: arriba a la derecha sale el ángulo (p. ej. "A la altura de los ojos · 2°").
- [ ] Inclina el teléfono hacia abajo/arriba: cambia a Picado/Contrapicado/Cenital y cambian
      las poses sugeridas.
- [ ] La línea del centro se pone verde con el teléfono recto.
- [ ] Con "Detectar mi pose": aparece tu esqueleto sobre el cuerpo (con la cámara trasera y con
      la frontal) y el % sube cuando imitas la silueta.
- [ ] Con "Disparo automático": al mantener la pose sale la cuenta atrás 3-2-1 y se hace la foto.
- [ ] Si el esqueleto sale desplazado respecto a la persona o con los lados cambiados, dímelo.

**Invitar, descargar y actualizar**
- [ ] Escanea `docs/qr-descarga.png`: se abre la página. Toca ⋮ → «Abrir en Chrome», luego
      Descargar → aparece el aviso de Chrome → Descargar → Abrir → Instalar.
- [ ] Botón compartir → **Enviar la app directamente** → Quick Share a otro teléfono → allí se
      abre e instala.
- [ ] Cuando haya una versión nueva en la Release: al abrir LumaCam sale «Nueva versión…
      Actualizar» arriba del visor → Permitir (la primera vez) → descarga con % → Android pide
      confirmar → la app se reinicia actualizada.

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
- Compila: `./gradlew assembleRelease` en GitHub Actions ✔ (APK firmado de ~37 MB, sólo arm64-v8a, publicado en la Release **lumacam**).
- Pruebas unitarias de la lógica: 85 en `core` (presets, curvas, LUT, balance de blancos,
  pipeline por franjas, zoom 0.6x, planificador de captura, alineación con rotación, fusión,
  anti-fantasmas, ruido de color, reglas de ráfaga, ángulo de cámara, poses, disparo automático) + 1 en la app. Todas pasan ✔.
- Lint de Android: **0 errores** ✔. Quedan advertencias intencionadas: versiones más nuevas
  disponibles (se usan las ya validadas: AGP 8.7.3, CameraX 1.4.1, Compose BOM 2024.12.01;
  existen CameraX 1.6.x y AGP 9.x para actualizar con el teléfono a mano), targetSdk 35 y la
  pantalla fija en vertical.
- Los shaders reales de la app se compilaron y ejecutaron en WebGL (Chromium) y se compararon
  con el procesado de la foto: los 5 looks coinciden con diferencia máxima de 1/255 ✔
  (`node tools/shader-check/check.mjs`).
- Revisé los 5 looks a ojo aplicándolos con el pipeline real de la foto a fotos de muestra
  (retrato, gato, café, moto, cohete al anochecer) y recortes al 100 %: sin halos ni artefactos;
  ajusté Vívido para no saturar tanto la piel (`LUMACAM_SAMPLES=… ./gradlew -p core test --tests '*LookSheetTool*'`).

**No lo pude verificar (necesita el teléfono):**
- El modo POSES en el teléfono: precisión del sensor de ángulo, que el esqueleto de ML Kit
  coincida con la persona en pantalla (cámara trasera y frontal) y la velocidad de detección.
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
- Resolución por defecto "Estándar" = la **máxima resolución rápida** que da la cámara
  (normalmente 12.5 MP con píxeles agrupados, que es la que mejor rinde con poca luz y permite
  multi-frame). "Máxima" usa la resolución más alta que exista (p. ej. 50 MP) si MagicOS la
  ofrece, pero sin multi-frame. Pediste máxima resolución **y** multi-frame; a 50 MP no caben las
  dos en memoria en este teléfono, así que elegí la combinación que da mejor foto.

---

## 5. Limitaciones del hardware (Honor X7c 5G, Snapdragon 4 Gen 2)

- **Una sola cámara útil atrás** (50 MP + 2 MP de profundidad): **no hay 0.6x real**; el 2x es
  recorte digital. La app no inventa un 0.6x.
- GPU Adreno 613 modesta: el look en 1080p debería ir a 30 fps; en 4K puede perder frames.
- Probablemente **sin estabilización óptica (OIS)**; la electrónica (EIS) puede no estar
  disponible para apps de terceros. La app lo detecta y lo dice.
- Las apps de terceros no reciben el procesado con IA de la cámara de Honor; lo compensamos
  con el multi-frame, la reducción de ruido de color y los looks, pero la cámara de Honor puede
  ganar en algunas escenas (sobre todo HDR fuerte a contraluz si MagicOS no expone HDR).
- La reducción de ruido de color sólo se aplica a la foto (no a la vista previa ni al video).
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

## 7. Repos

- Repo actual: `chiquidg1234-hue/Camare-improving`, rama `main` (todo el historial).
- En `chiquidg1234-hue/Camera-` quedó la rama `ccr-c0c8f514-ds68bb` como copia antigua; se puede
  borrar cuando quieras (GrabaFondo sigue en su rama y etiqueta, sin cambios).
