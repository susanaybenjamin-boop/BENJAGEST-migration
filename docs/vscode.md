# VS Code

VS Code puede usarse como IDE principal para la migracion.

## Extensiones recomendadas

Al abrir el proyecto, VS Code deberia proponer instalar las extensiones definidas en `.vscode/extensions.json`:

- Extension Pack for Java
- Maven for Java
- Spring Boot tools
- XML support
- GitLens

## Abrir el proyecto

```powershell
cd G:\Proyectos\git\Benja\BENJAGEST-migration
code .
```

VS Code debe abrir la carpeta raiz, no `backend-java` ni `ui` por separado.

## Tareas

Desde `Terminal > Run Task...`:

- `Maven: clean verify`
- `Backend: run Spring Boot`
- `UI: run JavaFX`

## Nota sobre IntelliJ

IntelliJ IDEA Ultimate es de pago. IntelliJ IDEA Community es gratuita y sirve para Java/Maven, pero no tiene todas las herramientas de Spring, bases de datos y productividad de Ultimate. VS Code evita esa dependencia de licencia para colaboradores.
