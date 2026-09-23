# GNS Phone Bridge

Conecta tu Android a la PC sin escribir ninguna IP: la app del teléfono prende
un servidor FTP y muestra un código QR; la app de Windows abre la cámara,
escanea el QR y se conecta sola.

## Estructura

- `android/` — app Android (Kotlin) que sirve los archivos del teléfono por FTP
  y muestra el QR de conexión.
- `windows/` — app de escritorio (WPF/.NET 8) que escanea el QR con la webcam
  y abre un explorador simple de los archivos del teléfono.

## Android — servidor

1. Abre `android/` en Android Studio (o compílalo con `gradlew assembleDebug`
   desde ese directorio; el APK queda en
   `app/build/outputs/apk/debug/app-debug.apk`).
2. Instala el APK en el teléfono y ábrelo.
3. La primera vez, toca "Permitir acceso a todos los archivos" (permiso
   especial de Android 11+ para poder servir todo el almacenamiento, no solo
   la carpeta de la app).
4. Toca "Iniciar servidor". Aparece un código QR — eso ya codifica IP, puerto,
   usuario y una contraseña aleatoria que cambia cada vez que arrancas el
   servidor.
5. El teléfono y la PC deben estar en la misma red Wi-Fi.

El servidor solo entiende modo pasivo (PASV), que es el que usan todos los
clientes FTP modernos (incluida la app de Windows de este proyecto).

## Windows — cliente

1. Requiere una cámara web conectada a la PC.
2. Compila y corre con:
   ```
   cd windows/GNSPhoneBridge.Client
   dotnet run
   ```
   O abre `windows/GNSPhoneBridge.sln` en Visual Studio.
3. Al abrir, la app enciende la cámara automáticamente. Apunta al QR de la
   app del teléfono.
4. En cuanto lo detecta, se conecta sola y muestra la lista de archivos del
   teléfono. Doble clic en una carpeta para entrar, doble clic en un archivo
   para descargarlo (te deja elegir dónde guardarlo, por defecto Documentos).
5. "Escanear otro teléfono" desconecta y vuelve a la cámara.

## Notas

- La contraseña del FTP es aleatoria y solo vive mientras el servidor esté
  encendido — no hay usuario/clave fijos que alguien pueda memorizar.
- Si el teléfono entra en ahorro de batería agresivo, Android puede matar el
  servicio en segundo plano; si la conexión se corta sola, revisa que la app
  tenga excepción de optimización de batería.
- El cliente de Windows solo permite navegar y descargar (no sube archivos ni
  sincroniza automáticamente) — así fue el alcance que se pidió.
