# LumaCam

Cámara para Android (Kotlin, CameraX, OpenGL ES, Jetpack Compose) pensada para el Honor X7c:
looks en tiempo real (Natural, Cálido, Cine, Vívido, Nocturno) en vista previa y video, foto
multi-frame con alineación y reducción de ruido, y **modo POSES** que sugiere poses según el
ángulo de la cámara y dispara solo cuando la pose coincide.

## Descargar e instalar

<img src="docs/qr-descarga.png" alt="QR para descargar LumaCam" width="260">

Escanea el QR con un teléfono Android (abre <https://chiquidg1234-hue.github.io/Camare-improving/>)
y sigue los 3 pasos:

1. Si se abre una ventana con una **✕** arriba (la del lector de QR), toca **⋮ → Abrir en Chrome**.
   En esa ventana Chrome deja los `.apk` en «Descargando… 100%» sin terminar.
2. **Descargar LumaCam** → cuando Chrome pregunte, **Descargar**.
3. **Abrir → Instalar** (permite *Instalar apps desconocidas* si lo pide).

Ya instalada, LumaCam **se actualiza sola** (aviso «Actualizar» dentro de la app) y el botón
**compartir** permite **enviar la app** a otro teléfono por Quick Share, Bluetooth o WhatsApp.

Cada cambio en `main` hace que GitHub Actions compile el APK, actualice la Release **lumacam**
(`LumaCam.apk` + `version.json`) y la página de descarga. Enlace directo del APK:
<https://github.com/chiquidg1234-hue/Camare-improving/releases/latest/download/LumaCam.apk>

## Más información

- **Uso, pruebas y limitaciones:** [NOTAS.md](NOTAS.md).
- **Estado del trabajo:** [PROGRESO.md](PROGRESO.md).
- **Compilar:** `./gradlew assembleRelease` (necesita Android SDK; GitHub Actions lo hace solo).
- **Pruebas de la lógica sin SDK:** `./gradlew -p core test`.
