# LumaCam

Cámara para Android (Kotlin, CameraX, OpenGL ES, Jetpack Compose) pensada para el Honor X7c:
looks en tiempo real (Natural, Cálido, Cine, Vívido, Nocturno) en vista previa y video, foto
multi-frame con alineación y reducción de ruido, y **modo POSES** que sugiere poses según el
ángulo de la cámara y dispara solo cuando la pose coincide.

## Descargar e instalar

<img src="docs/qr-descarga.png" alt="QR para descargar LumaCam" width="260">

Escanea el QR con la cámara de un teléfono Android (o abre
<https://github.com/chiquidg1234-hue/Camare-improving/releases/latest/download/LumaCam.apk>),
abre el archivo descargado y toca **Instalar**. Si Android lo pide, permite *Instalar apps
desconocidas* para el navegador. Desde la app, el botón **compartir** muestra este mismo QR para
invitar a otra persona.

Cada vez que se sube un cambio a `main`, GitHub Actions compila el APK y actualiza la Release
**lumacam** (el enlace y el QR no cambian nunca).

## Más información

- **Uso, pruebas y limitaciones:** [NOTAS.md](NOTAS.md).
- **Estado del trabajo:** [PROGRESO.md](PROGRESO.md).
- **Compilar:** `./gradlew assembleRelease` (necesita Android SDK; GitHub Actions lo hace solo).
- **Pruebas de la lógica sin SDK:** `./gradlew -p core test`.
