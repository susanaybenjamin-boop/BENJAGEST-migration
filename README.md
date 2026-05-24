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
mvn clean verify
mvn -pl backend-java spring-boot:run
mvn -pl ui javafx:run
```

Si Maven falla con `PKIX path building failed`, revisa la confianza de certificados del JDK/Maven antes de continuar.

## Clonar el proyecto

Cuando el repositorio exista en GitHub, cada colaborador podra bajarlo asi:

```powershell
git clone https://github.com/TU_USUARIO/benjagest-migration.git
cd benjagest-migration
mvn clean verify
```

Despues se abre la carpeta `benjagest-migration` desde VS Code o IntelliJ IDEA como proyecto Maven.

## Trabajo con ramas

La rama principal sera `main`. Cada cambio deberia hacerse en una rama propia:

```powershell
git checkout main
git pull
git checkout -b feature/nombre-del-cambio
```

Despues de trabajar:

```powershell
git status
git add .
git commit -m "Descripcion clara del cambio"
git push -u origin feature/nombre-del-cambio
```

En GitHub se abre una Pull Request desde esa rama hacia `main`. La rama se mezcla cuando el cambio este revisado y probado.
