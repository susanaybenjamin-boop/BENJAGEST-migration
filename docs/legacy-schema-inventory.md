# Inventario del esquema legacy

## Origen revisado

El proyecto original esta en `G:\Proyectos\git\Benja\BENJAGEST`. La informacion de partida sale de:

- `backend/migrations/*.sql`
- `backend/migrations/schema_dump.json`
- documentacion tecnica de facturacion en la raiz del proyecto original

El inventario detecta unas 65 tablas historicas. No todas deben copiarse literalmente: varias son detalles de Supabase/PostgreSQL, RLS, hotfixes o tablas tecnicas que conviene absorber en un modelo nuevo mas claro.

## Modulos legacy principales

| Modulo | Tablas legacy representativas | Destino propuesto |
| --- | --- | --- |
| Empresas y usuarios | `empresa_180`, `users_180`, `asesorias_180`, `empresa_config_180` | `companies`, `user_accounts`, `company_memberships`, `company_settings` |
| Clientes | `clients_180`, `client_fiscal_data_180`, `cliente_seq_180` | `customers`, `customer_contacts`, `customer_addresses`, `customer_billing_profiles` |
| Facturacion | `factura_180`, `lineafactura_180`, `concepto_180`, `factura_recurrente_180` | `sales_invoices`, `sales_invoice_lines`, `catalog_items`, `recurring_invoices` |
| VeriFactu/SII | `registroverifactu_180`, `eventos_sistema_verifactu_180`, `sii_config_180`, `sii_envios_180` | `verifactu_records`, `verifactu_events`, `sii_configurations`, `sii_submissions` |
| Compras y gastos | `purchases_180`, `gastos_recurrentes_180` | `suppliers`, `purchase_invoices`, `purchase_invoice_lines`, `recurring_expenses` |
| Contabilidad | `asientos_180`, `historial_cambios_asientos_180`, `cierre_ejercicio_180`, `inmovilizado_180` | `accounting_accounts`, `fiscal_years`, `journal_entries`, `journal_entry_lines`, `fixed_assets`, `year_closings` |
| Fiscal | `fiscal_models_180`, `modelos_anuales_180`, `renta_irpf_180`, `impuesto_sociedades_180`, `aeat_consultas_180` | `tax_models`, `tax_filings`, `aeat_consultations`, `aeat_discrepancies` |
| Laboral | `employees_180`, `contratos_180`, `nominas_180`, `bajas_laborales_180`, `cotizaciones_ss_180` | `employees`, `employment_contracts`, `payrolls`, `medical_leaves`, `social_security_contributions` |
| Tiempo y trabajos | `fichajes_180`, `work_logs_180`, `work_items_180`, `partes_dia_180`, `ausencias_180` | `time_clock_events`, `work_items`, `work_logs`, `daily_work_reports`, `absences` |
| RETA | `reta_autonomo_perfil_180`, `reta_estimaciones_180`, `reta_eventos_180`, `reta_alertas_180` | `self_employed_profiles`, `self_employed_estimates`, `self_employed_events`, `self_employed_alerts` |
| Documentos e integraciones | `certificados_digitales_180`, `notificaciones_180`, `calendar_*`, `conocimiento_180` | `digital_certificates`, `document_files`, `notifications`, `calendar_integrations`, `knowledge_entries` |

## Decisiones de traduccion

- `uuid` de PostgreSQL se mapea a `CHAR(36)` y se genera desde Java o desde procesos de importacion.
- `jsonb` se mapea a `JSON` en MariaDB cuando no compensa normalizar todavia.
- `timestamp with time zone` se mapea inicialmente a `TIMESTAMP` y se documenta que la aplicacion debe guardar instantes en UTC.
- Las politicas RLS de Supabase pasan a filtros obligatorios por `company_id` en la capa Java.
- Las tablas nuevas usan nombres en ingles y sin sufijo `_180`, para separar el modelo destino del legacy.
- Las tablas de auditoria, documentos, certificados e integraciones se mantienen porque son necesarias para trazabilidad y cumplimiento.

## Orden de migracion de datos recomendado

1. Empresas, usuarios y membresias.
2. Clientes y perfiles fiscales.
3. Catalogo, facturas emitidas, lineas y cobros.
4. Compras/gastos y proveedores.
5. Contabilidad generada desde facturas/compras.
6. Laboral, fichajes y partes.
7. Fiscal, AEAT, VeriFactu y SII.
8. Documentos, certificados, notificaciones e integraciones.
