

# Local ADB Manager (LAM)

<p align="center">
  <b>Gestor de paquetes, copias de seguridad e instalación en streaming mediante ADB local inalámbrico (127.0.0.1) en Android 11+ sin necesidad de PC, cables ni Shizuku.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2011%20to%2017-brightgreen.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Architecture-Standalone%20(No%20Root)-blue.svg" alt="Standalone" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.0-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material3-orange.svg" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/License-GPLv3-green.svg" alt="License" />
</p>

---

## 📌 ¿Qué es Local ADB Manager?

**Local ADB Manager** es una herramienta *standalone* (100% autónoma en el propio dispositivo) diseñada para instalar, respaldar e inspeccionar aplicaciones en Android utilizando el bucle de red local (`127.0.0.1`) y las capacidades del demonio de depuración inalámbrica (`adbd`).

A diferencia de otras soluciones, **no requiere un ordenador conectado por cable, no necesita permisos de Root y no depende de intermediarios como Shizuku**. Implementa su propio cliente ADB en JVM pura, motor criptográfico TLS (RSA 2048 + X.509) y un pipeline nativo de instalación en streaming mediante `cmd package`.

---

## ✨ Características Principales

### 1. Instalador de Paquetes Universal (Streaming ADB)
* **Soporte multiformato:** Instala paquetes `.apk` tradicionales, bundles divididos (`.apks`, `.xapk`, `.zip`) y contenedores unificados (`.lam`).
* **Instalación silenciosa en streaming:** Transmite los binarios directamente a las sesiones de `PackageInstaller` del sistema mediante `cmd package install-create` y `install-write`, sin saturar la memoria RAM.
* **Selección inteligente de fragmentos (Splits):**
  * Preserva siempre los fragmentos de densidad de pantalla e idioma (imprescindibles para evitar errores de instalación como `INSTALL_FAILED_MISSING_SPLIT`).
  * Selecciona automáticamente la arquitectura nativa óptima (`arm64-v8a`, `x86_64`, etc.) evitando conflictos de bibliotecas duplicadas.

### 2. Detección Inteligente de Versiones y Control de Downgrade
* **Inspección en tiempo real:** Compara el `versionCode` del archivo seleccionado contra la versión instalada en el sistema.
* **Diagnóstico visual:** Identifica si la operación es una *Nueva instalación*, *Actualización*, *Reinstalación* o *Downgrade*.
* **Seguridad y transparencia técnica:**
  * **En aplicaciones Debug (`FLAG_DEBUGGABLE`):** Permite forzar la instalación de versiones anteriores preservando los datos mediante la bandera `-d` tras confirmación del usuario.
  * **En aplicaciones Release (Producción):** Advierte con total transparencia de que el kernel de Android prohíbe degradar versiones de producción por seguridad, evitando errores crípticos y explicando la necesidad de desinstalar previamente.

### 3. Motor de Copias de Seguridad y Formato `.lam`
* **Extracción de binarios:** Extrae APKs monolíticos o empaqueta todos los splits del sistema en bundles `.apks` con un solo toque.
* **Respaldo de Datos Privados (`/data/data/`):** Para aplicaciones marcadas en modo depuración, extrae sus bases de datos SQLite y preferencias privadas mediante `/system/bin/run-as` empaquetadas en flujos `.tar.gz`.
* **Contenedor Unificado `.lam` (*Local ADB Manager*):**
  * Empaqueta en un único archivo comprimido los binarios (`apks/`), los datos privados (`data.tar.gz`) y un manifiesto certificado (`manifest.json`).
  * Incluye la verificación criptográfica **SHA-256** de cada archivo para garantizar que el respaldo no ha sido manipulado ni está corrupto.

### 4. Orquestador de Restauración Completa
* **Restauración selectiva:** Al abrir un paquete `.lam`, el usuario puede elegir entre:
  * *Instalar solo APK / Bundle* (instalación limpia de la app).
  * *Restaurar App y Datos* (instalación de binarios + inyección de bases de datos/preferencias vía `run-as`).
* **Protección contra *Tar-Slip* (Path Traversal):** Valida cada cabecera durante la descompresión para neutralizar ataques con rutas relativas maliciosas (`../`).

### 5. Conexión y Emparejamiento Flexible
* **Descubrimiento mDNS:** Escáner en vivo de puertos efímeros de conexión (`_adb-tls-connect`) y emparejamiento (`_adb-tls-pairing`).
* **Emparejamiento por Pantalla Dividida (*Split Screen*):** Diálogo ultra-compacto optimizado para introducir el puerto y código sin perder el foco ni cerrar la ventana de Ajustes.
* **Emparejamiento por Notificación interactiva:** Posibilidad de escribir el código desde la cortina superior de Android.
* **Conexión Local Directa (Offline / USB):** Acceso rápido para conectar a `127.0.0.1:5555`.

---

## 📦 Especificación del Formato `.lam`

El formato `.lam` es un contenedor estructurado estándar que unifica el ejecutable y el estado de la aplicación:

```text
paquete_completo.lam (ZIP)
├── manifest.json       # Metadatos e integridad criptográfica SHA-256
├── apks/               # Binarios ejecutables de la aplicación
│   ├── base.apk
│   └── split_*.apk     # Fragmentos de arquitectura, idioma y densidad
└── data.tar.gz         # Volcado GZIP de /data/data/ (excluyendo cachés)
```

#### Ejemplo de `manifest.json`:
```json
{
  "formatVersion": 1,
  "appName": "Mi Aplicación",
  "packageName": "com.ejemplo.app",
  "versionName": "1.2.0",
  "versionCode": 42,
  "isSplit": true,
  "hasPrivateData": true,
  "timestamp": 1791052122932,
  "artifacts": [
    {
      "path": "apks/base.apk",
      "sha256": "16c6b7508084551b43597c51ec8a4d146664b312fb4f91b34c7bd05ed203e1d5",
      "sizeBytes": 38849237
    },
    {
      "path": "data.tar.gz",
      "sha256": "5c6d11b9ef3ff738ed45361ecab157bce93e7fc11631a8064493b1ae4b2f7f61",
      "sizeBytes": 1920
    }
  ]
}
```

---

## 🛡️ Seguridad y Limitaciones Técnicas

| Característica | Estado | Detalle Técnico |
| :--- | :---: | :--- |
| **Instalación de APK / APKS / XAPK** | ✅ | Funciona para cualquier aplicación compatible. |
| **Backup de Binarios (APK)** | ✅ | Compatible con cualquier app instalada por el usuario. |
| **Backup y Restauración de Datos** | ⚠️ | **Exclusivo para apps en modo depuración (`FLAG_DEBUGGABLE`)**. Por diseño de seguridad de Android (SELinux), ninguna herramienta sin Root puede extraer datos privados de apps de producción de terceros. |
| **Downgrade en apps Debug** | ✅ | Permitido mediante la bandera `-d` tras confirmación modal. |
| **Downgrade en apps Release** | ❌ | Bloqueado a nivel de kernel por Android. Requiere desinstalación manual previa. |
| **Protección de Credenciales ADB** | 🔒 | Manifiesto con `android:allowBackup="false"` para impedir la fuga de claves RSA locales a la nube de Google. |

---

## 🚀 Requisitos y Compatibilidad

* **Sistema Operativo:** Android 11 (API 30) hasta **Android 17+**.
* **Dispositivos verificados:** Google Pixel 8 Pro (Android 17 nativo) y Emuladores AVD (x86_64).
* **Conectividad:** Dispositivo conectado a una red Wi-Fi con la opción *Depuración inalámbrica* activada en *Opciones para desarrolladores*.

---

## 🛠️ Compilación y Desarrollo

El proyecto está configurado para compilarse en entornos Linux modernos mediante la terminal:

```bash
# Clonar el repositorio
git clone https://github.com/patxetes/LocalAdbManager.git
cd LocalAdbManager

# Compilar e instalar en un dispositivo conectado
./gradlew installDebug

# Lanzar la actividad principal
adb shell am start -n com.localadb.manager/.MainActivity
```

### Stack Tecnológico
* **Lenguaje:** Kotlin 2.0.0 (código fuente comentado en inglés).
* **Build System:** Gradle 8.9 con Kotlin DSL (`build.gradle.kts`).
* **UI Toolkit:** Jetpack Compose (BOM 2024.09.00) + Material 3.
* **Cliente ADB:** `com.github.MuntashirAkon:libadb-android:3.1.1`.
* **Criptografía:** Conscrypt Android 2.5.3 + BouncyCastle 1.78.1 (RSA/X.509).

---

## Créditos e Inspiración Código Libre

Este proyecto se apoya e inspira en el trabajo de excelentes iniciativas de la comunidad de código abierto de Android:

* **[anyapk](https://github.com/sam1am/anyapk) (por sam1am):** Proyecto de referencia para el concepto de instalación local sin PC ni Shizuku mediante sesiones directas de `PackageInstaller` y descubrimiento mDNS sobre ADB inalámbrico.
* **[libadb-android](https://github.com/MuntashirAkon/libadb-android) (por Muntashir Akon):** Cliente ADB puro en Java/Kotlin que hace posible la comunicación con el socket local `adbd` sin requerir binarios nativos de C++ externos.
* **[LADB](https://github.com/tylernieu/LADB) (por tylernieu):** Pionero en la popularización del emparejamiento local en un solo dispositivo mediante interfaz loopback.
* **[Bouncy Castle](https://www.bouncycastle.org/):** Proveedor criptográfico para la generación y gestión de claves RSA y certificados X.509.
* **[Conscrypt](https://github.com/google/conscrypt):** Motor TLS de alto rendimiento basado en BoringSSL desarrollado por Google.

---

## 📄 Licencia

Este proyecto está bajo la licencia **GNU General Public License v3.0 (GPLv3)**. Consulta el archivo `LICENSE` para más información.
