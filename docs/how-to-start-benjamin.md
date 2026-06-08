# Guia de arranque para Benjamin

Esta guia deja el entorno listo para probar BENJAGEST Migration en Windows con Docker, MariaDB, DBeaver, backend Java y UI JavaFX.

## 1. Instalar herramientas

Instala o confirma estas herramientas:

- Git
- JDK 21
- Maven
- Docker Desktop
- DBeaver Community
- IntelliJ IDEA o VS Code

Comprobaciones desde PowerShell o Git Bash:

```powershell
git --version
java -version
mvn -version
docker version
```

Si DBeaver no esta instalado, en PowerShell:

```powershell
winget install --id DBeaver.DBeaver.Community --exact --source winget --accept-package-agreements --accept-source-agreements
```

## 2. Preparar Docker Desktop y WSL

Abre Docker Desktop. Si aparece el aviso `WSL needs updating`, ejecuta:

```powershell
wsl --update
wsl --shutdown
```

Despues cierra Docker Desktop completamente y vuelve a abrirlo.

Docker esta listo cuando:

- Docker Desktop muestra `Engine running`.
- Este comando devuelve seccion `Server`:

```powershell
docker version
```

## 3. Clonar el repositorio

```powershell
git clone https://github.com/pcs001es/benjagest-migration.git
cd benjagest-migration
git checkout develop
git pull origin develop
```

La rama de trabajo compartida es `develop`. Para cambios propios:

```powershell
git checkout -b feature/nombre-del-cambio
```

## 4. Crear la base de datos local

Con Docker Desktop abierto, desde la carpeta del proyecto:

```powershell
docker compose up -d
docker compose ps
```

Debe aparecer el contenedor `benjagest-mariadb` como `healthy`.

Datos locales:

```text
Contenedor: benjagest-mariadb
Host desde Windows: localhost
Puerto: 3307
Base de datos: benjagest
Usuario: benjagest
Contrasena: benjagest
Root password: root
```

Comprobar desde consola:

```powershell
docker compose exec -T mariadb mariadb -u benjagest -pbenjagest benjagest -e "SELECT DATABASE() AS database_name;"
```

## 5. Aplicar el esquema con Flyway

El esquema no se crea manualmente en DBeaver. Lo aplica el backend al arrancar mediante Flyway.

Para aplicar migraciones sin dejar el servidor web arrancado:

```powershell
mvn -pl backend-java spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
```

Para comprobar las migraciones:

```powershell
docker compose exec -T mariadb mariadb -u benjagest -pbenjagest benjagest -e "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Debe mostrar al menos:

```text
1  create core schema
2  create domain model
```

## 6. Conectar DBeaver a la base de datos

Abre DBeaver y crea una conexion nueva:

```text
Tipo: MariaDB
Server Host: localhost
Port: 3306
Database: benjagest
Username: benjagest
Password: benjagest
```

Si DBeaver pide descargar el driver de MariaDB, acepta.

Notas:

- Desde DBeaver se usa `localhost`, no `benjagest-mariadb`, porque DBeaver corre en Windows.
- Si aparece un aviso sobre `SELECT command denied ... mysql.user`, se puede ignorar. El usuario `benjagest` no es administrador, pero si tiene permisos sobre la base `benjagest`.
- DBeaver se usa para consultar, revisar tablas y validar datos. Los cambios definitivos de estructura deben hacerse con migraciones Flyway en `backend-java/src/main/resources/db/migration`.

Consultas utiles:

```sql
SELECT * FROM flyway_schema_history;
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_name;
SELECT * FROM customers;
```

## 7. Compilar y probar

```powershell
mvn clean verify
```

Si Maven falla con `PKIX path building failed`, no es un error del codigo. Suele ser un problema de certificados del JDK, proxy, antivirus HTTPS o Maven Central.

## 8. Arrancar backend

```powershell
$env:BENJAGEST_DB_URL="jdbc:mariadb://localhost:3307/benjagest"
$env:BENJAGEST_DB_USER="benjagest"
$env:BENJAGEST_DB_PASSWORD="benjagest"
mvn -pl backend-java spring-boot:run
```

Comprobaciones:

```text
http://localhost:8080/api/health
http://localhost:8080/api/customers
```

## 9. Arrancar UI JavaFX

En otra terminal:

```powershell
$env:BENJAGEST_API_BASE_URL="http://localhost:8080/api"
mvn -pl ui javafx:run
```

Prueba funcional inicial:

1. Abrir la UI.
2. Ir a Clientes.
3. Crear un cliente nuevo.
4. Consultarlo en DBeaver en la tabla `customers`.

## 10. Problemas frecuentes

### Docker no arranca

```powershell
wsl --update
wsl --shutdown
```

Despues reinicia Docker Desktop.

### Puerto 3306 ocupado

Cambia el puerto local en `.env`:

```text
BENJAGEST_DB_PORT=3307
```

Despues:

```powershell
docker compose up -d
```

Y usa `localhost:3307` en DBeaver y `jdbc:mariadb://localhost:3307/benjagest` en el backend.

### Reiniciar MariaDB local

```powershell
docker compose restart mariadb
```

### Ver logs de MariaDB

```powershell
docker compose logs -f mariadb
```

### Borrar la base local y empezar de cero

Esto elimina los datos locales del contenedor:

```powershell
docker compose down -v
docker compose up -d
```

Despues vuelve a ejecutar el backend para que Flyway recree el esquema.

## 11. Forma de trabajo recomendada

- `develop` es la rama central.
- Cada cambio se hace en una rama `feature/...`.
- La base local vive en Docker.
- DBeaver es para consultar y validar.
- Los cambios de esquema van siempre por Flyway.
- Antes de subir cambios:

```powershell
mvn clean verify
git status
git add .
git commit -m "Descripcion clara"
git push -u origin feature/nombre-del-cambio
```
