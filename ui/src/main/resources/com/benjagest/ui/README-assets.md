# Recursos de marca

Para cambiar el icono de la aplicacion, sustituye este archivo por el PNG definitivo:

```text
app-icon.png
```

La aplicacion lo usara automaticamente para la ventana. En ejecucion de desarrollo con `javafx:run`, Windows puede seguir mostrando el icono de Java en la barra de tareas. Para que la barra de tareas y el ejecutable usen siempre el icono final, se configurara tambien el empaquetado con `jpackage` cuando preparemos la distribucion.
