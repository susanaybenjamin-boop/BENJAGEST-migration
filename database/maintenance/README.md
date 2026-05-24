# Scripts de mantenimiento de base de datos

Estos scripts son para desarrollo local. La creacion del esquema se gestiona con Flyway desde el backend, en:

```text
backend-java/src/main/resources/db/migration
```

## Comprobar estado de la base

Con Docker:

```bash
docker compose exec mariadb mariadb -u benjagest -pbenjagest benjagest < database/maintenance/check_database.sql
```

## Copia de seguridad local

```bash
docker compose exec mariadb mariadb-dump -u benjagest -pbenjagest benjagest > backup-benjagest.sql
```

## Restaurar una copia local

```bash
docker compose exec -T mariadb mariadb -u benjagest -pbenjagest benjagest < backup-benjagest.sql
```

No subas copias de seguridad, volcados de datos ni informacion real de clientes al repositorio.
