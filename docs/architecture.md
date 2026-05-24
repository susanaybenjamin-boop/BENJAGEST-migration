# Arquitectura inicial BENJAGEST

## Objetivo

Separar BENJAGEST en modulos claros dentro del mismo directorio de trabajo:

- `backend-java`: API Java 21 con Maven, preparada para exponer servicios REST.
- `ui`: aplicacion de escritorio JavaFX, comunicada con el backend por HTTP.
- `docs`: documentacion de arquitectura, base de datos y forma de trabajo.
- `database`: scripts de apoyo para mantenimiento local.

## Comunicacion

La UI JavaFX no necesita proxy para hablar con el backend: puede llamar directamente a `http://localhost:8080/api`.

Si se mantiene React/Next, ahi si tiene sentido usar proxy o rewrites en desarrollo:

- React/Next dev server: `/api/*` -> `http://localhost:8080/api/*`.
- Produccion local o Docker: un reverse proxy puede publicar UI y API bajo el mismo host.

## Persistencia

La persistencia local de desarrollo usa MariaDB en Docker. El esquema se versiona con Flyway desde el backend Java.

El backend Java usa estas variables de entorno para conectarse:

- `BENJAGEST_DB_URL`
- `BENJAGEST_DB_USER`
- `BENJAGEST_DB_PASSWORD`

Ver tambien `docs/database-model.md` y `docs/database-migration.md`.

## Control de versiones

La rama estable es `main` y la rama central de trabajo es `develop`. Cada cambio debe hacerse en una rama propia.
