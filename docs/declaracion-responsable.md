# Declaración responsable del fabricante (RD 1007/2023)

> Bloque **DR** — sesión 2026-07-07. Este documento explica qué es, dónde
> vive en el código y qué hay que revisar en cada release.

## Qué es y por qué es obligatoria

El RD 1007/2023 (art. 13) y la Orden HAC/1177/2024 (art. 15) exigen que todo
Sistema Informático de Facturación (SIF) incorpore una **declaración
responsable** suscrita por su productor/fabricante. Es **autocertificación**:
no la sella ningún organismo — la AEAT no homologa software. La
responsabilidad recae íntegramente en el fabricante (multas de hasta
150.000 € por producto y ejercicio si el software no cumple).

Requisitos de forma (Orden HAC/1177/2024):

1. Debe constar **"por escrito y de modo visible en el propio sistema
   informático, en cada una de sus versiones"**.
2. El cliente y el comercializador deben disponer de ella **en el momento de
   la adquisición** del producto.

No exige web, ni landing, ni registro en la AEAT.

## Cómo lo cumple BENJAGEST

| Requisito | Dónde |
|---|---|
| Visible en el propio sistema | Configuración → **Acerca de** (texto completo, siempre accesible, no requiere módulo billing activo). También en Facturación → Configuración (diálogo previo). |
| En cada versión | La UI pasa `UpdateService.APP_VERSION` al backend — la declaración siempre lleva la versión instalada real. |
| Constancia escrita para el cliente | Botón **Descargar PDF** en Acerca de (el instalado ES el producto adquirido; el PDF sale de él). |

## Dónde vive en el código

- **Contenido**: `backend-java/.../billing/manufacturer/ManufacturerDeclaration.java`
  — record con los datos del productor, del producto y el compromiso.
  `current(String version)` compone la declaración de la versión instalada.
- **Render**: `ManufacturerDeclarationPdfService` — `plainText()` (pantalla) y
  `pdf()` (descarga) salen del **mismo** texto; no pueden divergir.
- **Endpoints**: `ManufacturerDeclarationController` —
  `GET /api/billing/manufacturer-declaration` (JSON), `/text`, `/pdf`.
  Sin `@RequiresModule`; roles incluyen EMPLOYEE (es información del
  producto, no datos de la empresa).
- **UI**: `SettingsScreen.settingsAboutTab()` (sección DR + PDF vía
  `Host.showInternalPdfViewer`) y `BillingDialogsScreen.showManufacturerDeclaration()`.
- **Tests**: `ManufacturerDeclarationPdfServiceTest` — fijan el contenido
  legal mínimo (NIF, versión, citas normativas, compromiso, `%PDF-`).

## Qué revisar en cada release

- **Nada en el caso normal**: la versión es dinámica.
- Si cambia el **alcance funcional del SIF** (p. ej. se activa el envío
  VERI*FACTU real, se añaden rectificativas R2-R5): actualizar
  `productFunctionalities` y `complianceCommitment` en
  `ManufacturerDeclaration.java` + los tests + la fecha de la declaración.
- Si cambia la **forma jurídica** del productor (autónomo → S.L.):
  actualizar nombre/NIF/dirección + tests + fecha.

## Estado del compromiso (honesto, a 2026-07-07)

La declaración afirma cumplimiento **en la modalidad NO VERI*FACTU**
(registro local: huella encadenada, eventos, firma, inalterabilidad,
conservación). La modalidad de remisión VERI*FACTU se declara explícitamente
como **hoja de ruta, no operativa** — no se promete lo que no hay
(`AeatVerifactuClient` tiene el envío real pendiente, slice VF3-FINAL).
