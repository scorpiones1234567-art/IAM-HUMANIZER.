# I.A.M HUMANIZER (Android)

Aplicación Android ligera, sin IA, sin Demucs y sin conexión a Internet para procesar audio WAV PCM de 16 bits.

## Funciones de esta primera versión
- Seleccionar una canción WAV PCM de 16 bits (mono o estéreo).
- Ajustar presencia/claridad global, calidez global y reducción aproximada de medios.
- Aplicar compresión suave y protección de picos.
- Guardar el resultado como WAV.

## Limitación importante
Esta versión no separa voz e instrumentos. Si la canción ya está mezclada, no es posible subir solo la voz y bajar solo los instrumentos con precisión usando estos controles. Los ajustes son globales y pueden afectar a ambos. Para separación real se necesitaría una herramienta de separación de fuentes, más recursos de procesamiento o un servicio externo.

## Crear APK desde GitHub (teléfono Android)
1. Crea un repositorio nuevo en GitHub llamado `IAM-HUMANIZER`.
2. Sube todos los archivos y carpetas de este proyecto conservando su estructura.
3. Abre la pestaña **Actions** del repositorio.
4. Se ejecutará el flujo **Construir APK Android**.
5. Cuando termine en verde, abre la ejecución y descarga el artefacto `IAM-HUMANIZER-debug-apk`.
6. Descomprime el ZIP descargado y abre `app-debug.apk` en Android. Si Android lo solicita, permite instalar desde esa fuente.

## Construcción local
Requiere Android Studio y JDK 17. Ejecuta `gradle assembleDebug` desde la raíz del proyecto.

## Privacidad
El procesamiento se realiza en el dispositivo. La aplicación no incluye analítica ni envía audio a servidores.
