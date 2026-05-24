# Arranque de desarrollo

## Requisitos

- JDK 21
- Maven
- Git
- IntelliJ IDEA
- Docker Desktop
- MariaDB/MySQL mediante contenedor Docker
- Node.js solo si se mantiene o migra el frontend React/Next

## Arrancar MariaDB local

Con Docker Desktop abierto:

```powershell
docker compose up -d
docker compose ps
```

El contenedor crea la base `benjagest` y el usuario `benjagest`. El esquema no se crea a mano: lo ejecuta Flyway al arrancar el backend.

## Compilar todo

```powershell
mvn clean verify
```

Si Maven falla con `PKIX path building failed`, el JDK no esta confiando en el certificado usado para acceder a Maven Central. No es un error del codigo: hay que revisar certificados del JDK, proxy corporativo, antivirus HTTPS o configuracion de Maven/IntelliJ.

## Arrancar backend Java

```powershell
$env:BENJAGEST_DB_URL="jdbc:mariadb://localhost:3306/benjagest"
$env:BENJAGEST_DB_USER="benjagest"
$env:BENJAGEST_DB_PASSWORD="benjagest"
mvn -pl backend-java spring-boot:run
```

Al arrancar, el backend aplica automaticamente los scripts de:

```text
backend-java/src/main/resources/db/migration
```

Endpoint de prueba:

```text
http://localhost:8080/api/health
http://localhost:8080/api/customers
```

## Arrancar UI JavaFX

```powershell
$env:BENJAGEST_API_BASE_URL="http://localhost:8080/api"
mvn -pl ui javafx:run
```

Desde la UI, en Clientes > Nuevo cliente, se puede crear un cliente y comprobar que queda guardado en MariaDB a traves del backend.

## IntelliJ

Abre la carpeta `BENJAGEST` como proyecto Maven. IntelliJ deberia detectar el `pom.xml` raiz y los modulos `backend-java` y `ui`.
