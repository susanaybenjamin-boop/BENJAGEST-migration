# Despliegue LOCAL / on-premise (oficina, LAN)

> **Decisión de producto (Benjamin):** BENJAGEST funciona **en local**, no en
> la nube. Un servidor en la oficina (MariaDB + backend) y la app de escritorio
> JavaFX en cada PC de la asesoría, conectados por la **red local (LAN)**.
>
> Esta guía complementa a [`how-to-start-benjamin.md`](how-to-start-benjamin.md)
> (entorno de desarrollo en una sola máquina). Aquí se describe el montaje
> **oficina = un servidor + varios puestos**.

---

## 1. Topología

```
        OFICINA (LAN)
  ┌──────────────────────────────────────────────┐
  │  SERVIDOR (un PC fijo, p. ej. 192.168.1.10)    │
  │   • MariaDB 11.4  (Docker, puerto 3307)        │
  │   • Backend Java  (Spring Boot, puerto 8080)   │
  │   • Carpeta de datos (PDFs, logos, backups)    │
  └──────────────────────────────────────────────┘
        ▲              ▲               ▲
        │  HTTP 8080   │               │   (LAN)
  ┌─────┴────┐  ┌──────┴───┐    ┌──────┴───┐
  │ Puesto 1 │  │ Puesto 2 │ …  │ Puesto N │   UI JavaFX (escritorio)
  └──────────┘  └──────────┘    └──────────┘
```

- **Una sola asesoría = un tenant raíz** (`companies.parent_company_id IS NULL`).
  Sus clientes son tenants hijos en la **misma** base de datos. El aislamiento
  es por `company_id` (no hay nada de "nube/subdominios"), así que encaja
  perfecto en un despliegue local.
- La UI es **de escritorio (JavaFX + HttpClient)**, no un navegador: por eso
  **no hay problema de CORS** al apuntar a otra IP de la LAN.

---

## 2. Requisitos

**Servidor (el PC fijo de la oficina):**
- Docker Desktop (para MariaDB) — o MariaDB 11.4 instalado nativo.
- JDK 21 (para el backend). Maven si se arranca con `mvn`; no hace falta si se
  usa el `.jar` empaquetado.
- IP fija en la LAN (recomendado) y el **puerto 8080 abierto** en el firewall
  de Windows para la red local.

**Puestos (cada PC de la asesoría):**
- JDK 21 (para `javafx:run`). En el futuro, un `.exe` con `jpackage` evitará
  necesitar Java en cada puesto (ver §7, pendiente).

---

## 3. Arranque del SERVIDOR

Desde la carpeta del proyecto en el servidor:

```powershell
# 1) Base de datos (Docker)
docker compose up -d
docker compose ps          # benjagest-mariadb debe estar "healthy"

# 2) Backend — opción A: con Maven (desarrollo)
$env:BENJAGEST_DB_URL="jdbc:mariadb://localhost:3307/benjagest?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:BENJAGEST_DB_USER="benjagest"
$env:BENJAGEST_DB_PASSWORD="benjagest"
mvn -pl backend-java spring-boot:run

# 2) Backend — opción B: empaquetado (recomendado para oficina)
mvn -pl backend-java -am clean package -DskipTests
java -jar backend-java/target/*.jar      # mismas env vars que arriba
```

El backend aplica las migraciones Flyway al arrancar. Comprobación:
`http://localhost:8080/api/health`.

> **Carpetas de datos** (configurables; por defecto en el `%USERPROFILE%` del
> servidor): `benjagest.invoices.storage-root` (logos) y
> `benjagest.imported-pdfs.root` (PDFs importados). Conviene fijarlas a una
> carpeta concreta del servidor que entre en la copia de seguridad, p. ej.:
> ```powershell
> $env:BENJAGEST_INVOICES_STORAGE_ROOT="D:\BenjagestDatos\facturas"
> $env:BENJAGEST_IMPORTED_PDFS_ROOT="D:\BenjagestDatos\pdfs-importados"
> ```
> (Mapea a las propiedades `benjagest.invoices.storage-root` /
> `benjagest.imported-pdfs.root`.)

---

## 4. Arranque de un PUESTO (cliente)

En cada PC, apuntando a la IP del servidor de la oficina:

```powershell
$env:BENJAGEST_API_BASE_URL="http://192.168.1.10:8080/api"   # IP del servidor
mvn -pl ui javafx:run
```

Si no se define `BENJAGEST_API_BASE_URL`, la UI usa `http://localhost:8080/api`
(solo válido si la UI corre en el propio servidor).

Hay scripts de ayuda en la raíz del repo: **`start-local-server.ps1`** (servidor)
y **`start-ui.ps1-ServerIp 192.168.1.10`** (puesto). Ver §6.

---

## 5. ¿Qué necesita INTERNET y qué funciona sin él?

El **núcleo funciona 100% offline** (facturar, contabilidad, nóminas, IRPF/SS,
finiquitos, PDFs, registro SIF local). Lo que usa internet es **opcional** y
**degrada bien** (si no hay conexión, esa función concreta no va, pero el
programa sigue):

| Función | Internet | Si no hay conexión |
|---|---|---|
| Facturar + registro SIF/Verifactu local (huella + QR) | **No** | Funciona igual |
| Contabilidad, nóminas, IRPF/SS, finiquitos, PDFs | **No** | Funciona igual |
| Envío Verifactu a la AEAT (modalidad VERI*FACTU) | **Sí** | Cola y reintenta (máx. 5); ver §8 |
| Alertas BOE (novedades fiscales/laborales) | Sí (opcional) | Sin alertas, no rompe |
| Email (enviar nóminas/facturas por correo) | Sí (SMTP) | No envía; el PDF se descarga igual |
| Geolocalización al fichar (Nominatim) | Sí (opcional) | Sin coordenadas, no rompe |

---

## 6. Scripts de arranque

- **`start-local-server.ps1`** — en el servidor: levanta Docker (MariaDB),
  espera a que esté `healthy`, fija las variables de BD y arranca el backend
  (con `mvn` o con el `.jar` si existe).
- **`start-ui.ps1 -ServerIp 192.168.1.10`** — en cada puesto: fija
  `BENJAGEST_API_BASE_URL` con la IP del servidor y lanza la UI.

Son ayudas; los comandos manuales de §3 y §4 siguen siendo válidos.

---

## 7. Copias de seguridad (local)

- **Base de datos**: ya existe el backup automático semanal (módulo
  BACKUP-LOCAL, lunes 03:00) configurable en Configuración. Además, recomendado
  un `mysqldump` periódico del contenedor a una unidad externa/NAS de la oficina.
- **Ficheros**: incluir en la copia las carpetas de datos (§3): facturas/logos
  y PDFs importados.
- Regla de oro: la copia debe salir del servidor (disco externo o NAS), no
  quedarse solo en el mismo PC.

---

## 8. Verifactu / SIF en local — estado y modalidad

BENJAGEST cumple el RD 1007/2023 con dos modalidades, **seleccionables** en
Configuración (`companies.verifactu_modality`):

- **NO_VERIFACTU (por defecto, recomendada para asesoría local):** cada factura
  genera **huella SHA-256 encadenada + firma local + QR + registro de eventos
  SIF**, todo **en el propio servidor, sin internet**. El QR permite el cotejo
  posterior por el receptor en la sede AEAT. **Apta para funcionar offline.**
- **VERI*FACTU (envío a la AEAT en tiempo real):** además remite los registros
  a la AEAT. Esto **sí requiere internet** y, para producción real, dos cosas
  pendientes (anotadas en el backlog, bloque DEPLOY-LOCAL / VF-SIGN):
  1. **Certificado** FNMT de representante de persona jurídica registrado en la
     AEAT como Sistema Informático de Facturación.
  2. **Ajuste del XML al XSD oficial** de la AEAT + firma **XAdES-EPES** (hoy el
     cliente AEAT está implementado pero NO probado contra la AEAT, con formato
     interno; ver `AeatVerifactuClient`). El envío ya tiene scheduler con
     reintentos, así que cuando esté ajustado funcionará también en local con
     conexión puntual a internet.

> **Conclusión:** para una asesoría on-premise, lo correcto y suficiente hoy es
> operar en **NO_VERIFACTU** (100% local). El salto a VERI*FACTU es una mejora
> futura que solo necesita el certificado real + el ajuste de XSD/XAdES, y se
> puede hacer en local con una salida a internet puntual.

---

## 9. Puesto autocontenido con MariaDB EMBEBIDA (DEPLOY-PKG)

Para el instalable "todo es un puesto" **no hace falta Docker ni MariaDB
externa**: el backend puede arrancar una **MariaDB embebida** (MariaDB4j) con
sus datos en `~/.benjagest/mariadb-data`. Se activa con la propiedad
`benjagest.db.embedded=true` (en desarrollo NO se activa, así se sigue usando la
MariaDB del 3307 de `application.yml`).

### Probarlo ya (test del stack embebido)

```powershell
.\run-embedded.ps1            # reusa el fat jar; lo construye si falta
.\run-embedded.ps1 -Rebuild   # fuerza reconstruir el fat jar
```

Hace 4 pasos: (1) construye el **fat jar** del backend si falta, (2) lo arranca
con `-Dbenjagest.db.embedded=true` (MariaDB embebida en 13307 + API en 8080),
(3) espera a que la API responda, (4) lanza la UI apuntando a `localhost:8080`.
Al cerrar la UI, para backend y MariaDB embebida. Los datos **persisten** entre
arranques (la primera vez instala la BD y migra; las siguientes reutilizan los
datos).

> El **fat jar** se genera con `mvn -pl backend-java package -DskipTests`
> (239 MB, incluye el binario de MariaDB) y es **autocontenido**: arranca con
> `java -Dbenjagest.db.embedded=true -jar backend-java-0.1.0-SNAPSHOT.jar`, sin
> Maven ni BD externa.

## 10. Pendiente para un "producto instalable" (no bloqueante hoy)

- **`jpackage`** para generar un `.exe`/`.msi` que empaquete UI + backend
  embebido + JRE (necesita **WiX 3.x** instalado para `.msi`/`.exe`; el formato
  `app-image` no lo necesita y ya es testeable).
- Empaquetar el backend como **servicio de Windows** (arranque automático).
- Bundle de **Tesseract** (binario + tessdata) para el OCR del bloque MIG.

Estas tareas son de **empaquetado/operación**, no de arquitectura: el código ya
es local-ready (sin acoplamiento a la nube) y el backend ya es autocontenido con
BD embebida. Están anotadas en el backlog (bloque **DEPLOY-PKG**).
