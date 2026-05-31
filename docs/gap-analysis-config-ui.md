# Análisis Profundo: Configuración de Usuario e Interfaces (CONTENDO vs BENJAGEST)

> Análisis específico de la configuración de usuario, preferencias y comparativa de interfaces entre la versión original en Next.js (CONTENDO GESTIONES) y la migración a JavaFX (BENJAGEST).
> Fecha: 2026-05-31

A raíz de la revisión del `gap-analysis-contendo.md`, se ha detectado que faltaba profundizar en la capa de **Configuración de Usuario**, **Preferencias del Sistema** y las **Diferencias de UI/UX**. A continuación se detalla lo que todavía no se ha contemplado en el modelo de BENJAGEST.

---

## 1. Configuración de Usuario (Nivel Personal)

En CONTENDO, la configuración no es solo "de empresa". El usuario (administrador o empleado) tiene su propia configuración accesible mediante el modal `AdminSelfConfigModal.tsx`.

### Funciones no añadidas
- **Asignación de Jornada Laboral:** El usuario puede seleccionar su `plantilla_id` (horario) directamente desde su perfil.
- **Acceso Móvil PWA:** Generación de enlaces/QR de invitación para vincular el dispositivo móvil personal del usuario.
- **Email Personal (OAuth2):** Conexión con Gmail mediante OAuth2 a nivel de usuario para el envío de invitaciones y notificaciones, de forma independiente al SMTP general de la empresa.

### Tablas no contempladas (o incompletamente mapeadas)
- `users_180`: Contiene preferencias de usuario, `avatar_url`, modo IA (`ai_enabled`).
- `empleado_plantillas_180`: Tabla de asignación de calendarios de jornada al empleado.
- `employee_devices_180` / `qr_sessions_180`: Para la gestión de sesiones y vinculación de dispositivos PWA.

---

## 2. Comparativa de Interfaces (CONTENDO Next.js vs BENJAGEST JavaFX)

La interfaz de CONTENDO es extremadamente rica en micro-funcionalidades que no están previstas (de momento) en el cliente Desktop JavaFX.

### Faltas en la UI de BENJAGEST
1. **Dashboard Widgets Personalizables:** 
   - *CONTENDO:* Los usuarios pueden activar, desactivar y reordenar widgets del dashboard. Además, mantienen configuraciones de layout diferentes para Escritorio y Móvil.
   - *BENJAGEST:* El dashboard actual (`DashboardController`) es fijo y no personalizable por usuario.
2. **Command Palette (Cmd+K / Ctrl+K):**
   - *CONTENDO:* Un buscador global rápido para saltar entre pantallas y funciones.
   - *BENJAGEST:* No diseñado aún en JavaFX.
3. **Lock Screen (Salvapantallas y PIN):**
   - *CONTENDO:* Configuración granular (`pin_timeout_minutes`, `screensaver_style`: "clock", "logo", "minimal"). Bloquea la UI tras inactividad.
   - *BENJAGEST:* Se acordó usar PIN solo en el Login inicial; falta la protección por inactividad.
4. **BottomNav y Modo PWA:**
   - *CONTENDO:* Layout dinámico que cambia a barra de navegación inferior (BottomNav) si detecta PWA en móvil.
   - *BENJAGEST:* Siendo una aplicación de escritorio, esta parte del código y caso de uso queda huérfana. ¿Cómo accederán los clientes desde el móvil?
5. **AI Copilot Flotante:**
   - *CONTENDO:* Componente `<AICopilot />` global accesible desde cualquier pantalla.

---

## 3. Configuración de Empresa y Facturación (Ampliación)

Revisando los modales de configuración profunda de CONTENDO, hay elementos del dominio de facturación y sistema que tampoco figuran en la migración:

### Funciones y Tablas no añadidas
- **Backup Local Silencioso (`backup_local_path`):** 
  - CONTENDO utiliza la *File System Access API* del navegador para pedir permisos de una carpeta local y realizar backups automáticos transparentes. **Duda para Pablo:** ¿Se replicará esto usando el sistema de ficheros local de JavaFX?
- **Gestor de Tipos de IVA (`iva_180`):** 
  - La configuración permite añadir o eliminar tipos de IVA personalizados. BENJAGEST no tiene tabla de catálogos de impuestos planificada.
- **Titulares y Socios (`titulares_empresa_180`):**
  - Módulo específico en la configuración de la empresa para declarar quiénes son los administradores/socios. Es vital para módulos fiscales, pero no está en `domain-model.md`.
- **Configuración Fina de VeriFactu:**
  - `configuracionsistema_180` almacena el modo (TEST/PROD), correlativo inicial, contraseñas de certificados digitales para firma en cliente, y datos de obligaciones del fabricante.
- **Gestión visual del Certificado Digital:**
  - Modal con carga local del `.p12`, inyección de contraseña (`passModalOpen`), y desencriptado para obtener el `subject` y fechas de validez.

---

## Conclusión y Recomendación

La migración actual ha abstraído la configuración a un único objeto global (`company_settings`), lo cual es una simplificación excesiva frente a CONTENDO.

**Faltas accionables para el Slice C3 (Configuración) y posteriores:**
1. Separar la configuración en **Preferencias de Usuario** (tabla `user_settings` o `user_accounts` expandida) y **Configuración de Empresa**.
2. Diseñar el almacenamiento local seguro de certificados `.p12` en el cliente JavaFX (quizás en un keystore de Java).
3. Añadir entidad `taxes` o enumeración configurable en BD para los tipos de IVA.
4. Definir si el **Dashboard Personalizable** es obligatorio para el MVP o puede ser estático temporalmente.
