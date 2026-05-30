# Roadmap de migración BENJAGEST

> Documento de seguimiento. Aquí se va marcando lo que ya está hecho
> según se completa, para no perder de vista lo que falta y no
> saltarnos nada.
>
> Documento vivo: lo actualizamos en cada commit que cierre uno de
> los items. Si descubrimos algo nuevo que falta, se añade aquí.
>
> Fuente del alcance: [`gap-analysis-contendo.md`](gap-analysis-contendo.md)
> (apartados 3 y 4) y [`migration-plan.md`](migration-plan.md) de Pablo.

---

## Leyenda

| Símbolo | Significado |
|---|---|
| ✅ | Hecho y verificado |
| 🟡 | Parcial (a la derecha, qué falta) |
| ⏳ | Pendiente |
| ❌ | Fuera de alcance o aplazado (decisión consciente) |
| ❓ | A decidir con Pablo antes de empezar |

## Convenciones para considerar un item "✅"

Un módulo está **completo** cuando tiene:

1. **Schema** — tablas creadas en alguna migración `V*__*.sql`, FKs y seeds.
2. **Backend** — Repository + Service + Controller en Spring (siguiendo el patrón de `customer/`).
3. **UI** — pantalla(s) JavaFX equivalentes.

Si solo uno o dos de los tres están hechos, va con 🟡 y se indica qué falta.

---

## Decisiones previas que hay que cerrar con Pablo

Antes de empezar fases nuevas hay que tener respuesta a estas preguntas
(la mayoría salen del apartado 6 del gap-analysis):

- ❓ ¿Qué representa `companies.parent_company_id` y `company_type='MANAGED_CLIENT'`? ¿Sirven como link asesoría→cliente o se metieron para otra cosa?
- ❓ ¿Modo asesoría / empresario se deriva de las `company_memberships` del usuario, o se elige en login?
- ❓ Métricas concretas de "más ágil que A3": tiempo de cambio de cliente, vista panorámica al login, operaciones en lote.
- ❓ ¿Entra en alcance el módulo construcción (`cons_*`)? ¿Y la IA (MCP)? ¿Y `plans_180` (SaaS)?
- ❓ ¿Portal asesor y portal empleado dentro de la misma UI JavaFX, o aplicaciones separadas?
- ❓ ¿Kiosko de fichaje se mantiene? Si sí, ¿app Android/iOS aparte, o JavaFX en modo kiosko?
- ❓ ¿BENJAGEST es SaaS multi-tenant u on-premise (una instalación por gestoría)?
- ❓ ¿Se migran datos históricos desde Supabase a MariaDB, o se arranca con BD vacía?

---

## Fase 1 — Modelo de dominio base

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `companies`, `user_accounts`, `company_memberships`, `company_settings` | ✅ V2 | 🟡 solo `workspace` PIN | ⏳ | Falta CRUD real de empresa y usuario. |
| `customers` + `customer_billing_profiles` | ✅ V1+V2 | ✅ | ✅ básica | CRUD ya funciona end-to-end. |
| `issuers` (emisores de factura) | ✅ V2 + V6 | ✅ | ✅ | CRUD completo + emisor activo (`is_default`) + indicador en header. Pendiente: validacion duplicado por NIF a nivel UI. |
| `digital_certificates` | ✅ V2 | ⏳ | ⏳ | |
| `document_files` | ✅ V2 | ⏳ | ⏳ | Decidir estrategia de storage (FS vs S3). |
| `audit_events` (genérica) | ✅ V2 | ⏳ | — | Apartado 3.O: confirmar si una sola tabla cubre VeriFactu y RD 8/2019. |
| `notifications` (genérica) | ✅ V2 | ⏳ | ⏳ | |

---

## Fase 2 — Facturación + VeriFactu (CRÍTICA: obligación legal)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `invoice_series` (series de numeración) | ✅ V2 | ⏳ | ⏳ | Faltan proformas, rectificativas, anulaciones. |
| `sales_invoices` + `sales_invoice_lines` + `sales_invoice_payments` | ✅ V2 | ⏳ | ⏳ | |
| `recurring_invoices` | ✅ V2 | ⏳ | ⏳ | |
| `verifactu_records` + `verifactu_events` | ✅ V2 | ⏳ | ⏳ | Falta documentar hash encadenado, firma XML, anulación con vínculo. |
| Envío de facturas por email (`envios_email_180`) | ⏳ | ⏳ | ⏳ | No contemplado todavía. |
| Emisor activo por empresa (`is_default`) | ✅ V6 | ✅ | ✅ | `PUT /api/issuers/{id}/default` + linea persistente "Facturando como..." en el header. |
| Configuración avanzada de emisor (`configuracionsistema_180`) | ⏳ | ⏳ | ⏳ | Plantillas, series por emisor, etc. — no esta hecho. |
| Auditoría específica de facturación | ❓ | — | — | Decidir si genérica `audit_events` cubre o se separa. |
| Almacenamiento documental de facturas | ⏳ | ⏳ | ⏳ | Ruta `facturacion/almacenamiento` en CONTENDO. |
| **Obligaciones de fabricante VeriFactu** (auditoría propia del software) | ⏳ | ⏳ | ⏳ | Leer `VERIFACTU_OBLIGACIONES_FABRICANTE.md` de CONTENDO. |

---

## Fase 3 — SII (Suministro Inmediato de Información)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `sii_configurations` + `sii_submissions` | ✅ V2 | ⏳ | ⏳ | Falta tabla extra "framework" tipo `sii_envios_180`. |

---

## Fase 4 — Compras, gastos y proveedores

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `suppliers`, `purchase_invoices`, `purchase_invoice_lines` | ✅ V2 | ⏳ | ⏳ | |
| `recurring_expenses` | ✅ V2 | ⏳ | ⏳ | |
| Gastos recurrentes silenciados (`gastos_recurrentes_silenciados_180`) | ⏳ | — | — | Mecanismo de silenciar gastos temporalmente, no contemplado. |
| Reconciliación bancaria (`bank_transactions_180`) | ⏳ | ⏳ | ⏳ | No contemplado todavía. |

---

## Fase 5 — Contabilidad

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `accounting_accounts`, `fiscal_years`, `journal_entries`, `journal_entry_lines` | ✅ V2 | ⏳ | ⏳ | |
| `fixed_assets`, `year_closings` | ✅ V2 | ⏳ | ⏳ | |
| **Carga inicial del PGC español (326 cuentas estándar)** | ✅ V4 | — | — | Sembrado para empresa demo. Al alta de empresas reales hay que copiar la plantilla en backend. |
| Historial de cambios de asientos (`historial_cambios_asientos_180`) | ❓ | ⏳ | ⏳ | Decidir si `audit_events` genérica vale o tabla específica. |
| Asientos revisados por usuario | ⏳ | ⏳ | ⏳ | Workflow de revisión contable. |
| Reportes: balance, mayor, P&G, extracto | — | ⏳ | ⏳ | Consultas, no tablas. |
| Cierre de ejercicio con aplicación de resultado | 🟡 V2 | ⏳ | ⏳ | Tabla `year_closings` existe; falta documentar el proceso. |

---

## Fase 6 — Fiscal: modelos AEAT, renta, sociedades (CRÍTICA)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `tax_models`, `tax_filings` (genéricas) | ✅ V2 | ⏳ | ⏳ | Decidir si modelos 100/180/190/347/390 son tipos o subtablas. |
| `aeat_consultations`, `aeat_discrepancies` | ✅ V2 | ⏳ | ⏳ | |
| Reglas fiscales con histórico anual (`fiscal_reglas_180`) | ⏳ | ⏳ | ⏳ | Mecanismo de duplicar en enero ajustando valores Hacienda. |
| Casillas de modelos como patrones regex (`fiscal_casilla_patterns_180`) | ⏳ | ⏳ | ⏳ | Único en CONTENDO. |
| Mapeo de campos AEAT (`aeat_campo_mapeo_180`) | ⏳ | ⏳ | ⏳ | |
| Calendario fiscal (`calendario_fiscal_180`) | ⏳ | ⏳ | ⏳ | Vencimientos fiscales. |
| Epígrafes IAE personalizados | ⏳ | ⏳ | ⏳ | |
| Régimen especial de IVA / prorrata / criterio de caja | ⏳ | ⏳ | ⏳ | Mecanismos fiscales españoles concretos. |
| Inmovilizado: cálculo de amortizaciones | 🟡 V2 | ⏳ | ⏳ | Tabla `fixed_assets` existe; falta cálculo y vinculación con asientos. |

---

## Fase 7 — RETA (autónomos)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `self_employed_profiles` | ✅ V2 | ⏳ | ⏳ | |
| `self_employed_estimates` | ✅ V2 | ⏳ | ⏳ | |
| `self_employed_events` | ✅ V2 | ⏳ | ⏳ | |
| `self_employed_alerts` | ✅ V2 | ⏳ | ⏳ | |
| **`self_employed_contribution_brackets`** (tramos cotización) | ✅ V4 | ⏳ | ⏳ | 15 tramos × 2 ejercicios (2025, 2026). |
| **`self_employed_base_changes`** | ✅ V5 | ⏳ | ⏳ | Histórico de cambios de base de cotización. |
| **`self_employed_preonboarding`** | ✅ V5 | ⏳ | ⏳ | Captura de prospecto antes del alta. |

**Schema cerrado.** Pendiente backend + UI (decisión: lo verá Pablo con Benjamin).

---

## Fase 8 — Empleados, contratos, nóminas, bajas

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `employees` + `employment_contracts` | ✅ V2 | ⏳ | ⏳ | |
| `payrolls` | ✅ V2 | ⏳ | ⏳ | |
| `medical_leaves` (bajas) | ✅ V2 | ⏳ | ⏳ | |
| `social_security_contributions` | ✅ V2 | ⏳ | ⏳ | |
| Entrega de nóminas (`nomina_entregas_180`) | ⏳ | ⏳ | ⏳ | Firma del trabajador, fecha, vía. |
| Incidencias de nómina (`nomina_incidencias_180`) | ⏳ | ⏳ | ⏳ | |
| Centros de trabajo (`centros_trabajo_180`) | ⏳ | ⏳ | ⏳ | Relevante para fichajes y SS. |
| Coste empresa por empleado (reporte) | — | ⏳ | ⏳ | |

---

## Fase 9 — Fichajes (CRÍTICA: obligación legal RD 8/2019)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `time_clock_events` | ✅ V2 | ⏳ | ⏳ | |
| `daily_work_reports` | ✅ V2 | ⏳ | ⏳ | |
| `absences` | ✅ V2 | ⏳ | ⏳ | |
| **Correcciones de fichaje (RD 8/2019, solo por apunte vinculado)** | ⏳ | ⏳ | ⏳ | **Obligación legal.** No se pueden modificar fichajes. |
| **Verificaciones de fichaje (CSV, art. 35.8 RD 8/2019)** | ⏳ | ⏳ | ⏳ | **Obligación legal.** |
| Plantillas de jornada complejas | ⏳ | ⏳ | ⏳ | Días tipo, bloques horarios, excepciones. |
| Asignación de plantillas a empleados | ⏳ | ⏳ | ⏳ | |
| Turnos (`turnos_180`, `turno_bloques_180`) | ⏳ | ⏳ | ⏳ | |
| Plannings (`admin/planings`) | ⏳ | ⏳ | ⏳ | |
| Fichajes sospechosos (detección de patrones) | ⏳ | ⏳ | ⏳ | |
| Sincronización offline (`offline_sync_batches_180`) | ⏳ | ⏳ | ⏳ | Crítico para uso real con kioskos sin red. |

---

## Fase 10 — Calendario, festivos e integración Google

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `calendar_integrations`, `calendar_events`, `calendar_sync_logs` | ✅ V2 | ⏳ | ⏳ | |
| Calendario laboral por empresa (mezcla festivos + cierres) | ⏳ | ⏳ | ⏳ | Festivos vienen de la importación del calendario del cliente. |
| Integración Google Calendar bidireccional | ⏳ | ⏳ | ⏳ | Webhooks + mapeo + log de sync. |
| Importación masiva de calendarios | ⏳ | ⏳ | ⏳ | |

---

## Fase 11 — Asesoría (workflow completo)

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| Link asesoría↔cliente | ❓ | ⏳ | ⏳ | Confirmar primero si `parent_company_id` + `MANAGED_CLIENT` ya lo cubren. |
| Mensajes asesoría↔cliente (`asesoria_mensajes_180`) | ⏳ | ⏳ | ⏳ | |
| Documentos compartidos (`documentos_asesoria_180`) | ⏳ | ⏳ | ⏳ | |
| Notificaciones específicas de asesor (`notificaciones_asesor_180`) | ⏳ | ⏳ | ⏳ | |
| Permisos finos por sub-recurso | ⏳ | ⏳ | ⏳ | Ej: `configuracion:write` sobre un cliente concreto. |
| Invitaciones (`invite_180`) | ⏳ | ⏳ | ⏳ | Alta de usuarios y vinculación cliente↔asesor. |
| **Vista panorámica de asesoría** (cross-client dashboard) | — | ⏳ | ⏳ | Lista de clientes, vencimientos agregados, operaciones en lote. |

---

## Fase 12 — Notificaciones, alertas, integraciones externas

| Item | Schema | Backend | UI | Comentario |
|---|---|---|---|---|
| `notifications` genérica | ✅ V2 | ⏳ | ⏳ | Decidir tipología (legales, deadlines, seguridad, DEHú, asesor). |
| Alertas de seguridad (`security_alerts_180`) | ⏳ | ⏳ | ⏳ | Intentos de login, accesos sospechosos. |
| Credenciales externas cifradas (`credenciales_externas_180`) | ⏳ | ⏳ | ⏳ | DEHú, SS RED, SILTRA. **Valor diferencial.** |
| Log de uso de certificados (`certificados_uso_log_180`) | ⏳ | ⏳ | ⏳ | Trazabilidad obligatoria. |
| Notificaciones DEHú (`notificaciones_dehu_180`) | ⏳ | ⏳ | ⏳ | |
| Estrategia de storage de ficheros | ❓ | — | — | FS vs S3-compat vs BD. |

---

## Fase 13 — Portal empleado

| Item | Status | Comentario |
|---|---|---|
| Decisión arquitectura UI | ❓ | ¿Misma UI JavaFX con cambio de modo según rol, o app separada? |
| Calendario del empleado | ⏳ | |
| Nóminas para descargar | ⏳ | |
| Notificaciones | ⏳ | |
| Lista de trabajos asignados | ⏳ | |

---

## Fase 14 — Kiosko (fichaje en dispositivo compartido)

| Item | Status | Comentario |
|---|---|---|
| Decisión arquitectura | ❓ | ¿JavaFX modo kiosko, o app móvil aparte? |
| `kiosk_devices_180`, `kiosk_empleados_180` | ⏳ | |
| Tokens de activación | ⏳ | |
| Sesiones QR + OTP | ⏳ | |
| Sincronización offline por lotes | ⏳ | |

---

## Fase 15 — Áreas a decidir alcance

| Área | Decisión | Comentario |
|---|---|---|
| Módulo construcción (`cons_*`) | ❓ | ~50 tablas, prácticamente otra aplicación. Tiene IA, presupuestos, mediciones, certificaciones. |
| FERRAPP (proyectos/etiquetas) | ❓ | Sub-app/integración. |
| MCP / IA con quotas | ❓ | Sistema de IA integrado con control de costes. |
| Sugerencias | ❓ | Feedback de usuarios. |
| Planes y suscripciones (SaaS) | ❓ | Depende de la decisión SaaS vs on-premise. |
| Análisis BOE | ❓ | Análisis automático de novedades fiscales. |
| Páginas legales públicas | ❓ | Solo aplica si hay web pública. |
| Onboarding y flujo de alta | ❓ | Registro, activación, verificación. |

---

## Histórico de commits relevantes

| Commit | Fase | Item completado |
|---|---|---|
| `4874855` | 5, 7 | V4: PGC seed (326 cuentas para demo) + tramos RETA (30 filas) + tabla `self_employed_contribution_brackets` |
| `a0340af` | 7 | V5: tablas `self_employed_base_changes` y `self_employed_preonboarding` |
| `a0d0f53` | — | Limpieza: borrado de `BUILD` + `.gitignore` |
| `b9f5be7` | — | Doc: roadmap de migracion con tracking por fase |
| `17b251d` | 1 | Issuers slice 1: CRUD backend (issuer/) + pantalla JavaFX dedicada + chuleta con comandos de arranque |
| (siguiente) | 1, 2 | Issuers slice 2: V6 + flag `is_default` + endpoint `markAsDefault` + columna ★ y header "Facturando como..." |

---

*Última actualización: 2026-05-30 por Benjamin. Actualizar conforme se cierren items.*

*Cambios desde la creación: ítem `issuers` cerrado end-to-end (V6 + emisor activo). Pendiente: configuración avanzada (plantillas, series).*
