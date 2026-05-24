# Arquitectura inicial BENJAGEST

## Objetivo

Separar BENJAGEST en modulos claros dentro del mismo directorio de trabajo:

- `backend-java`: API Java 21 con Maven, preparada para exponer servicios REST.
- `ui`: aplicacion de escritorio JavaFX, comunicada con el backend por HTTP.
- `app180-frontend`: frontend React/Next existente, conservado como referencia mientras decidimos si se migra, se mantiene o se sustituye.
- `backend`: backend Node/Express existente, conservado como referencia de negocio y migracion.

## Comunicacion

La UI JavaFX no necesita proxy para hablar con el backend: puede llamar directamente a `http://localhost:8080/api`.

Si se mantiene React/Next, ahi si tiene sentido usar proxy o rewrites en desarrollo:

- React/Next dev server: `/api/*` -> `http://localhost:8080/api/*`.
- Produccion local o Docker: un reverse proxy puede publicar UI y API bajo el mismo host.

## Persistencia

La referencia deseada para la nueva aplicacion sera MariaDB/MySQL. Aun asi, el proyecto original usa PostgreSQL/Supabase, asi que la migracion de datos requiere una fase especifica de conversion de esquema y reglas de seguridad.

El backend Java ya deja configuradas variables de entorno para la conexion destino:

- `BENJAGEST_DB_URL`
- `BENJAGEST_DB_USER`
- `BENJAGEST_DB_PASSWORD`

Ver tambien `docs/database-migration.md`.

## Control de versiones

La raiz `BENJAGEST` ya es un repositorio Git. Tambien existen repos Git dentro de `backend` y `app180-frontend`; conviene decidir en una fase posterior si se integran como codigo legacy en el monorepo o si se mantienen como repos separados.
