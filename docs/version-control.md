# Control de versiones

## Repositorio

Este proyecto de migracion debe subirse a un repositorio GitHub propio, separado del proyecto original.

Recomendacion inicial:

- Repositorio: `benjagest-migration`
- Rama principal: `main`
- Visibilidad: privada con colaboradores, o publica si se decide compartirla completamente.

## Colaboradores

Cuando empiece la colaboracion, se pueden anadir colaboradores desde GitHub sin cambiar la estructura del proyecto ni los remotos locales.

## Flujo de trabajo

```powershell
git status
git add .
git commit -m "Descripcion del cambio"
git push
```

Antes de cada subida, revisar que no haya archivos `.env`, claves, certificados o datos reales de clientes.

## Clonado para colaboradores

Cuando el repositorio este creado en GitHub:

```powershell
git clone https://github.com/TU_USUARIO/benjagest-migration.git
cd benjagest-migration
mvn clean verify
```

Cada colaborador debe abrir la carpeta clonada desde VS Code o IntelliJ IDEA. No hace falta abrir subcarpetas sueltas; el IDE debe detectar el `pom.xml` raiz.

## Ramas y Pull Requests

La rama estable sera `develop`. Para trabajar:

```powershell
git checkout main
git pull
git checkout -b feature/nombre-del-cambio
```

Para subir la rama:

```powershell
git add .
git commit -m "Descripcion clara del cambio"
git push -u origin feature/nombre-del-cambio
```

En GitHub se crea una Pull Request hacia `main`. Esa Pull Request sera el punto de revision, pruebas y conversacion antes de mezclar.

Nombres recomendados para ramas:

- `feature/...` para funcionalidad nueva.
- `fix/...` para correcciones.
- `docs/...` para documentacion.
- `migration/...` para cambios especificos de migracion.
