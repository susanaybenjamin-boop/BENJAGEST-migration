# Modelo destino de dominio

## Criterio general

El modelo destino usa MariaDB, Flyway y un esquema relacional normalizado. La tabla `customers` creada en `V1` se conserva y se amplia para no romper el modulo inicial de clientes.

Todas las entidades operativas relevantes quedan ligadas a `companies.company_id` mediante `company_id`. En MariaDB no tenemos RLS como en Supabase, por lo que el backend Java debe aplicar siempre el filtro de empresa en repositorios, servicios y endpoints.

## Nucleo

- `companies`: empresa, asesor o cliente gestionado.
- `user_accounts`: usuarios de acceso.
- `company_memberships`: relacion usuario-empresa con rol.
- `company_settings`: configuracion modular, dashboard, seguridad y preferencias.
- `issuers`: datos fiscales/emisor de facturacion.
- `digital_certificates`: certificados de empresa para AEAT/SII/VeriFactu.

## Clientes y catalogo

- `customers`: tercero cliente ya existente, ahora preparado para multiempresa.
- `customer_contacts`: contactos de cliente.
- `customer_addresses`: direcciones de cliente.
- `customer_billing_profiles`: datos fiscales, forma de pago, IBAN, IVA/retencion por defecto.
- `catalog_items`: conceptos facturables, trabajos o servicios.
- `customer_tariffs`: tarifas especificas por cliente y concepto.

## Facturacion y cobros

- `invoice_series`: series y numeracion por empresa.
- `sales_invoices`: factura emitida, borrador, validada, anulada, rectificativa o proforma.
- `sales_invoice_lines`: lineas de factura emitida.
- `sales_invoice_payments`: cobros asociados.
- `recurring_invoices`: reglas de facturacion recurrente.
- `verifactu_records` y `verifactu_events`: cadena, hash, envio y auditoria VeriFactu.
- `sii_configurations` y `sii_submissions`: configuracion y envios SII.

## Compras y gastos

- `suppliers`: proveedores.
- `purchase_invoices`: factura recibida/gasto.
- `purchase_invoice_lines`: lineas de compra.
- `recurring_expenses`: gastos recurrentes.

## Contabilidad

- `accounting_accounts`: plan contable por empresa.
- `fiscal_years`: ejercicios.
- `journal_entries`: cabeceras de asiento.
- `journal_entry_lines`: apuntes debe/haber.
- `fixed_assets`: inmovilizado.
- `year_closings`: cierre y aplicacion de resultado.

## Fiscal

- `tax_models`: catalogo de modelos AEAT.
- `tax_filings`: presentaciones por empresa, periodo y modelo.
- `aeat_consultations`: consultas realizadas a AEAT.
- `aeat_discrepancies`: discrepancias detectadas.

## Laboral, tiempo y RETA

- `employees`: empleados vinculados a empresa y usuario opcional.
- `employment_contracts`: contratos.
- `work_items`: tipos de trabajo.
- `work_logs`: trabajos imputables a cliente/factura.
- `time_clock_events`: fichajes y geolocalizacion.
- `daily_work_reports`: partes diarios.
- `absences`: vacaciones, permisos, bajas y ausencias.
- `payrolls`: nominas.
- `medical_leaves`: bajas laborales.
- `social_security_contributions`: cotizaciones.
- `self_employed_profiles`, `self_employed_estimates`, `self_employed_events`, `self_employed_alerts`: RETA/autonomos.

## Documentos, auditoria e integraciones

- `document_files`: metadatos de documentos y adjuntos.
- `audit_events`: trazabilidad funcional y tecnica.
- `notifications`: notificaciones internas.
- `calendar_integrations`, `calendar_events`, `calendar_sync_logs`: calendario y sincronizacion.
- `knowledge_entries`: base de conocimiento interna.

## Nota sobre cambios de esquema

DBeaver se usa para inspeccionar, consultar y validar. Los cambios definitivos de estructura deben entrar siempre como migraciones Flyway (`V2__...sql`, `V3__...sql`, etc.).
