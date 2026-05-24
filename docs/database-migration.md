# Migracion de base de datos

## Situacion actual

El proyecto original no esta usando MySQL/MariaDB como base principal. El backend Node actual usa:

- Driver `postgres`.
- Variables `SUPABASE_URL` o `SUPABASE_DB_URL`.
- Migraciones SQL con sintaxis PostgreSQL/Supabase.
- RLS, roles, grants y politicas de seguridad.
- Tipos PostgreSQL como `UUID`, `JSONB`, `TIMESTAMPTZ` y funciones como `gen_random_uuid()`.

Por tanto, la migracion a MariaDB/MySQL no es un cambio directo de driver.

## Objetivo de la migracion

La carpeta `BENJAGEST-migration` prepara MariaDB/MySQL como destino, pero todavia no hay migracion real de datos ni modelo relacional definitivo. El backend Java solo tiene una configuracion inicial de conexion para MariaDB y un endpoint de salud.

## Riesgos principales

- PostgreSQL `JSONB` debe convertirse a `JSON` o a tablas normalizadas.
- PostgreSQL `UUID` puede mapearse a `CHAR(36)`, `BINARY(16)` o generarse desde Java.
- `TIMESTAMPTZ` debe revisarse para no perder zona horaria.
- RLS de Supabase/PostgreSQL no existe igual en MariaDB/MySQL.
- Las politicas multiempresa deben pasar a la capa de aplicacion Java, con filtros obligatorios por `empresa_id`.
- Funciones PL/pgSQL, triggers, indices y constraints deben revisarse uno a uno.

## Estrategia recomendada

1. Inventariar tablas, columnas, indices, constraints, funciones y politicas del origen.
2. Clasificar cada tabla por modulo funcional: facturacion, laboral, fiscal, calendario, usuarios, auditoria, etc.
3. Disenar el modelo destino en MariaDB/MySQL.
4. Migrar primero un modulo pequeno y verificable.
5. Crear pruebas de equivalencia entre datos origen y destino.
6. Solo despues conectar los repositorios Java reales.

## Decision pendiente

Antes de avanzar con persistencia real hay que confirmar si el destino definitivo sera:

- MariaDB/MySQL local, como se propuso inicialmente.
- PostgreSQL local, para reducir el coste de migracion desde Supabase.
- Un paso intermedio: backend Java contra PostgreSQL y migracion a MariaDB mas adelante.
