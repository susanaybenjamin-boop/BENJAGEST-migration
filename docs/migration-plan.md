# Plan de migracion

## Fase 1 - Base tecnica

- Mantener el repositorio raiz `BENJAGEST` como punto principal de trabajo.
- Usar Maven como build principal para Java.
- Mantener `backend-java` y `ui` como modulos separados.
- Abrir `BENJAGEST` desde IntelliJ, no solo un subdirectorio.

## Fase 2 - Backend Java

- Migrar casos de uso del backend Node/Express a servicios Java.
- Exponer endpoints REST bajo `/api`.
- Separar capas de dominio, aplicacion, infraestructura y API.
- Introducir repositorios MariaDB/MySQL solo cuando el modelo de datos destino este cerrado.

Estructura recomendada dentro de `backend-java`:

```text
com.benjagest.backend
  api
  application
  domain
  infrastructure
```

## Fase 3 - UI

- Usar JavaFX como cliente de escritorio principal.
- Consumir el backend por HTTP.
- Decidir despues si React/Next se mantiene como web, se migra o queda solo como referencia.

## Fase 3b - Base de datos

- Inventariar el esquema PostgreSQL/Supabase original.
- Traducir tipos, constraints, indices, funciones y politicas RLS a una estrategia MariaDB/MySQL.
- Definir como se aplicara el aislamiento por `empresa_id` en Java, ya que MariaDB/MySQL no ofrece el mismo RLS.
- Migrar modulo por modulo, empezando por uno pequeno.

## Fase 4 - Empaquetado

- Backend: JAR ejecutable con Spring Boot.
- UI: empaquetado JavaFX con Maven y, mas adelante, instalador con `jpackage`.
- Docker: introducirlo cuando backend y base de datos ya tengan contratos estables.

## Fase 5 - Limpieza de repos

Ahora existen repos Git dentro de `backend` y `app180-frontend`. Antes de borrar o mover nada, decidir una de estas opciones:

- Monorepo real: integrar todo en el Git de la raiz.
- Repos separados: mantener backend legacy y frontend legacy independientes.
- Archivo legacy: moverlos a una carpeta de referencia y dejar de desarrollarlos.
