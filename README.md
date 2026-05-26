# BENJAGEST Migration

Workspace de migracion de BENJAGEST hacia una arquitectura Java 21 con Maven, backend separado y UI JavaFX.

## Modulos

- `backend-java`: API Java/Spring Boot preparada para MariaDB/MySQL.
- `ui`: cliente de escritorio JavaFX.
- `docs`: arquitectura, arranque y estrategia de migracion de datos.

## Estado

Este repositorio contiene solo la migracion nueva. El proyecto original queda separado y se usa como referencia tecnica durante la migracion.

## Desarrollo

```powershell
docker compose up -d
mvn clean verify
mvn -pl backend-java spring-boot:run
mvn -pl ui javafx:run
```

Si Maven falla con `PKIX path building failed`, revisa la confianza de certificados del JDK/Maven antes de continuar.

## Prueba funcional

La migracion incluye datos demo para consumir dashboard, clientes, facturacion, compras, laboral, fiscal, agenda, avisos y usuarios. Flyway los carga en `V3__seed_demo_and_pin_access.sql` al arrancar el backend contra una base limpia.

La UI arranca por defecto en modo `Asesoria` cuando el PIN pertenece a la empresa interna demo. Desde la cabecera se puede cambiar entre:

- `Asesoria`: cartera, fiscalidad, laboral, facturacion de clientes, informes y agenda de asesoria.
- `Empresario`: operacion de empresa propia, clientes, facturacion, compras, laboral, fiscal, informes y agenda.

PIN demo para la UI:

- `1234`: Marcos Encargado.
- `5678`: Nerea Oficial.
- `2468`: Iker Peon.

Arranque recomendado:

```powershell
docker compose up -d
mvn -pl backend-java spring-boot:run
mvn -pl ui javafx:run
```

## Guia de arranque

Para preparar un equipo nuevo con Docker, MariaDB, DBeaver, backend y UI, sigue:

```text
docs/how-to-start-benjamin.md
```

## Clonar el proyecto

Cada colaborador podra bajarlo asi:

```powershell
git clone https://github.com/pcs001es/benjagest-migration.git
cd benjagest-migration
mvn clean verify
```

Despues se abre la carpeta `benjagest-migration` desde VS Code o IntelliJ IDEA como proyecto Maven.

## Trabajo con ramas

La rama estable sera `main` y la rama central de trabajo sera `develop`. Cada cambio deberia hacerse en una rama propia creada desde `develop`:

```powershell
git checkout develop
git pull origin develop
git checkout -b feature/nombre-del-cambio
```

Despues de trabajar:

```powershell
git status
git add .
git commit -m "Descripcion clara del cambio"
git push -u origin feature/nombre-del-cambio
```

En GitHub se abre una Pull Request desde esa rama hacia `develop`. La rama se mezcla cuando el cambio este revisado y probado.
