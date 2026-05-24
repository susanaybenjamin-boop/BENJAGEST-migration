# Arranque de desarrollo

## Requisitos

- JDK 21
- Maven
- Git
- IntelliJ IDEA
- MariaDB/MySQL
- Node.js solo si se mantiene o migra el frontend React/Next
- Docker Desktop mas adelante

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

Endpoint de prueba:

```text
http://localhost:8080/api/health
```

## Arrancar UI JavaFX

```powershell
$env:BENJAGEST_API_BASE_URL="http://localhost:8080/api"
mvn -pl ui javafx:run
```

## IntelliJ

Abre la carpeta `BENJAGEST` como proyecto Maven. IntelliJ deberia detectar el `pom.xml` raiz y los modulos `backend-java` y `ui`.
