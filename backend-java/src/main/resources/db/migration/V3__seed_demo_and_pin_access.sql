ALTER TABLE employees
    ADD COLUMN pin_hash VARCHAR(64) NULL AFTER phone;

CREATE UNIQUE INDEX uk_employees_company_pin ON employees (company_id, pin_hash);

INSERT INTO companies (id, legal_name, trade_name, tax_identifier, company_type, email, phone)
VALUES
('11111111-1111-1111-1111-111111111111', 'Benjamin Gestiones Integrales SL', 'BENJAGEST Demo', 'B09990001', 'INTERNAL', 'admin@benjagest.local', '910000001'),
('22222222-2222-2222-2222-222222222222', 'Obras Norte 180 SL', 'Obras Norte', 'B09990002', 'CLIENT', 'administracion@obrasnorte.local', '910000002')
ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);

INSERT INTO user_accounts (id, email, display_name, global_role, active)
VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'admin@benjagest.local', 'Administrador Demo', 'ADMIN', TRUE),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'marcos@benjagest.local', 'Marcos Encargado', 'EMPLOYEE', TRUE)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO company_memberships (id, company_id, user_id, role_name)
VALUES
('10101010-1010-1010-1010-101010101010', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'OWNER'),
('20202020-2020-2020-2020-202020202020', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'EMPLOYEE')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

INSERT INTO company_settings (company_id, enabled_modules, dashboard_widgets, mobile_modules, security_settings, ai_tokens)
VALUES
('11111111-1111-1111-1111-111111111111',
 JSON_ARRAY('customers','billing','purchases','labor','tax','reports','settings'),
 JSON_ARRAY('revenue','pending','work','alerts'),
 JSON_ARRAY('pin','time_clock','work_reports'),
 JSON_OBJECT('pinLogin', true, 'sessionMinutes', 480),
 25000)
ON DUPLICATE KEY UPDATE enabled_modules = VALUES(enabled_modules);

INSERT INTO customers (id, company_id, legal_name, trade_name, tax_identifier, customer_type, notes, active)
VALUES
('30000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Construcciones Alba SL', 'Alba Construcciones', 'B12000001', 'COMPANY', 'Cliente de obra residencial.', TRUE),
('30000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Reformas Centro CB', 'Reformas Centro', 'E12000002', 'COMPANY', 'Trabajos recurrentes de mantenimiento.', TRUE),
('30000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'Ayuntamiento de Valdeprado', 'Valdeprado', 'P1200003A', 'PUBLIC_ENTITY', 'Licitaciones y certificaciones.', TRUE)
ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name), company_id = VALUES(company_id);

INSERT INTO customer_contacts (id, customer_id, full_name, role_name, email, phone, primary_contact)
VALUES
('31000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Laura Marin', 'Administracion', 'laura@alba.local', '620000001', TRUE),
('31000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'Sergio Cano', 'Gerencia', 'sergio@reformas.local', '620000002', TRUE),
('31000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', 'Marta Ruiz', 'Intervencion', 'marta@valdeprado.local', '620000003', TRUE)
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name);

INSERT INTO customer_addresses (id, customer_id, address_type, line1, city, province, postal_code)
VALUES
('32000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'MAIN', 'Calle Mayor 12', 'Madrid', 'Madrid', '28013'),
('32000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'MAIN', 'Avenida Industria 8', 'Getafe', 'Madrid', '28901')
ON DUPLICATE KEY UPDATE city = VALUES(city);

INSERT INTO customer_billing_profiles (id, customer_id, fiscal_name, tax_identifier, fiscal_type, billing_email, default_vat_percent, default_retention_percent, payment_method)
VALUES
('33000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'Construcciones Alba SL', 'B12000001', 'GENERAL', 'facturas@alba.local', 21.00, 0.00, 'TRANSFER'),
('33000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'Reformas Centro CB', 'E12000002', 'GENERAL', 'facturas@reformas.local', 21.00, 0.00, 'TRANSFER')
ON DUPLICATE KEY UPDATE fiscal_name = VALUES(fiscal_name);

INSERT INTO issuers (id, company_id, legal_name, tax_identifier, address_line, city, province, postal_code, email, phone, iban)
VALUES
('40000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Benjamin Gestiones Integrales SL', 'B09990001', 'Calle Oficina 1', 'Madrid', 'Madrid', '28001', 'facturacion@benjagest.local', '910000001', 'ES9121000418450200051332')
ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);

INSERT INTO catalog_items (id, company_id, item_type, name, description, category, unit_price, default_vat_percent)
VALUES
('41000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'SERVICE', 'Jornada oficial 1a', 'Mano de obra oficial de primera', 'Obra', 32.50, 21.00),
('41000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'SERVICE', 'Administracion mensual', 'Gestion documental y fiscal mensual', 'Gestion', 180.00, 21.00),
('41000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'PRODUCT', 'Material auxiliar', 'Consumibles de obra', 'Materiales', 75.00, 21.00)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO invoice_series (id, company_id, code, invoice_kind, numbering_type, format_template, next_number, current_year)
VALUES
('42000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'F2026', 'STANDARD', 'BY_YEAR', 'F-{YYYY}-{0000}', 4, 2026)
ON DUPLICATE KEY UPDATE next_number = VALUES(next_number);

INSERT INTO sales_invoices (id, company_id, issuer_id, customer_id, series_id, invoice_number, invoice_date, due_date, status, payment_status, subtotal, vat_total, retention_total, total, paid_amount)
VALUES
('43000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '42000000-0000-0000-0000-000000000001', 'F-2026-0001', '2026-05-02', '2026-06-01', 'VALIDATED', 'PAID', 1250.00, 262.50, 0.00, 1512.50, 1512.50),
('43000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', '42000000-0000-0000-0000-000000000001', 'F-2026-0002', '2026-05-12', '2026-06-11', 'VALIDATED', 'PENDING', 720.00, 151.20, 0.00, 871.20, 0.00),
('43000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000003', '42000000-0000-0000-0000-000000000001', 'F-2026-0003', '2026-05-18', '2026-06-17', 'DRAFT', 'PENDING', 540.00, 113.40, 0.00, 653.40, 0.00)
ON DUPLICATE KEY UPDATE total = VALUES(total), payment_status = VALUES(payment_status);

INSERT INTO sales_invoice_lines (id, invoice_id, catalog_item_id, description, quantity, unit_price, vat_percent, line_subtotal, line_vat, line_total)
VALUES
('44000000-0000-0000-0000-000000000001', '43000000-0000-0000-0000-000000000001', '41000000-0000-0000-0000-000000000001', 'Trabajos de albañileria mayo', 38.4615, 32.50, 21.00, 1250.00, 262.50, 1512.50),
('44000000-0000-0000-0000-000000000002', '43000000-0000-0000-0000-000000000002', '41000000-0000-0000-0000-000000000002', 'Gestion mensual y partes', 4.0000, 180.00, 21.00, 720.00, 151.20, 871.20),
('44000000-0000-0000-0000-000000000003', '43000000-0000-0000-0000-000000000003', '41000000-0000-0000-0000-000000000003', 'Material auxiliar certificado', 7.2000, 75.00, 21.00, 540.00, 113.40, 653.40)
ON DUPLICATE KEY UPDATE line_total = VALUES(line_total);

INSERT INTO sales_invoice_payments (id, invoice_id, payment_date, amount, payment_method, reference)
VALUES
('45000000-0000-0000-0000-000000000001', '43000000-0000-0000-0000-000000000001', '2026-05-20', 1512.50, 'TRANSFER', 'TR-2026-0001')
ON DUPLICATE KEY UPDATE amount = VALUES(amount);

INSERT INTO suppliers (id, company_id, legal_name, tax_identifier, email, phone, city, province)
VALUES
('50000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Materiales Sur SL', 'B28000001', 'pedidos@materialessur.local', '930000001', 'Madrid', 'Madrid'),
('50000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Alquileres Maquinaria SA', 'A28000002', 'admin@alquileres.local', '930000002', 'Alcala de Henares', 'Madrid')
ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);

INSERT INTO purchase_invoices (id, company_id, supplier_id, supplier_name, invoice_number, invoice_date, category, subtotal, vat_total, total, payment_status)
VALUES
('51000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '50000000-0000-0000-0000-000000000001', 'Materiales Sur SL', 'MS-2611', '2026-05-07', 'Materiales', 340.00, 71.40, 411.40, 'PAID'),
('51000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '50000000-0000-0000-0000-000000000002', 'Alquileres Maquinaria SA', 'AM-870', '2026-05-16', 'Maquinaria', 620.00, 130.20, 750.20, 'PENDING')
ON DUPLICATE KEY UPDATE total = VALUES(total);

INSERT INTO purchase_invoice_lines (id, purchase_invoice_id, description, quantity, unit_price, vat_percent, line_subtotal, line_vat, line_total)
VALUES
('52000000-0000-0000-0000-000000000001', '51000000-0000-0000-0000-000000000001', 'Cemento y pequeno material', 1.0000, 340.00, 21.00, 340.00, 71.40, 411.40),
('52000000-0000-0000-0000-000000000002', '51000000-0000-0000-0000-000000000002', 'Alquiler plataforma elevadora', 1.0000, 620.00, 21.00, 620.00, 130.20, 750.20)
ON DUPLICATE KEY UPDATE line_total = VALUES(line_total);

INSERT INTO employees (id, company_id, user_id, default_customer_id, full_name, tax_identifier, email, phone, pin_hash, work_type, max_shift_minutes)
VALUES
('60000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '30000000-0000-0000-0000-000000000001', 'Marcos Encargado', '12345678Z', 'marcos@benjagest.local', '640000001', '03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4', 'FULL_TIME', 600),
('60000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', NULL, '30000000-0000-0000-0000-000000000002', 'Nerea Oficial', '87654321X', 'nerea@benjagest.local', '640000002', '888df25ae3577245b6c7a43487e343116a9b7245bfa6c1a3c9a6049303846b36', 'FULL_TIME', 600),
('60000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', NULL, '30000000-0000-0000-0000-000000000003', 'Iker Peon', '11223344A', 'iker@benjagest.local', '640000003', '1a10d82733c768fb6eb6e2fe4b71a6caecd73d09b39e059a5431b3302bfa204f', 'PART_TIME', 360)
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), pin_hash = VALUES(pin_hash);

INSERT INTO employment_contracts (id, company_id, employee_id, contract_type, start_date, weekly_hours, gross_salary, status)
VALUES
('61000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000001', 'INDEFINIDO', '2024-01-15', 40.00, 26000.00, 'ACTIVE'),
('61000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000002', 'INDEFINIDO', '2024-09-01', 40.00, 23500.00, 'ACTIVE')
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO work_items (id, company_id, customer_id, name, description, billing_type, default_minutes, monetary_value)
VALUES
('62000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '30000000-0000-0000-0000-000000000001', 'Albanileria vivienda A', 'Partes de obra residencial', 'HOURLY', 480, 260.00),
('62000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '30000000-0000-0000-0000-000000000002', 'Mantenimiento local', 'Revision y reparaciones menores', 'HOURLY', 240, 120.00)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO work_logs (id, company_id, employee_id, customer_id, work_item_id, work_date, minutes, description, value_amount, payment_status)
VALUES
('63000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '62000000-0000-0000-0000-000000000001', '2026-05-21', 480, 'Levantamiento de tabiques', 260.00, 'PENDING'),
('63000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', '62000000-0000-0000-0000-000000000002', '2026-05-22', 300, 'Reparacion de humedades', 165.00, 'PENDING')
ON DUPLICATE KEY UPDATE minutes = VALUES(minutes);

INSERT INTO time_clock_events (id, company_id, employee_id, customer_id, event_type, event_time, origin, status)
VALUES
('64000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'IN', '2026-05-25 08:02:00', 'PIN_KIOSK', 'VALID'),
('64000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'OUT', '2026-05-25 17:04:00', 'PIN_KIOSK', 'VALID')
ON DUPLICATE KEY UPDATE event_time = VALUES(event_time);

INSERT INTO absences (id, company_id, employee_id, absence_type, start_date, end_date, reason, status)
VALUES
('65000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000003', 'VACATION', '2026-06-03', '2026-06-07', 'Vacaciones solicitadas', 'REQUESTED')
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO payrolls (id, company_id, employee_id, period_year, period_month, gross_amount, employee_social_security, employer_social_security, irpf_amount, net_amount, status)
VALUES
('66000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '60000000-0000-0000-0000-000000000001', 2026, 5, 2166.67, 138.67, 660.00, 260.00, 1768.00, 'APPROVED')
ON DUPLICATE KEY UPDATE net_amount = VALUES(net_amount);

INSERT INTO tax_models (id, code, name, tax_area, periodicity)
VALUES
('70000000-0000-0000-0000-000000000001', '303', 'IVA autoliquidacion', 'IVA', 'QUARTERLY'),
('70000000-0000-0000-0000-000000000002', '111', 'Retenciones e ingresos a cuenta', 'IRPF', 'QUARTERLY')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO tax_filings (id, company_id, tax_model_id, period_year, period_code, status, amount_due, submitted_at)
VALUES
('70000000-0000-0000-0000-000000000101', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000001', 2026, '2T', 'DRAFT', 420.90, NULL),
('70000000-0000-0000-0000-000000000102', '11111111-1111-1111-1111-111111111111', '70000000-0000-0000-0000-000000000002', 2026, '2T', 'PENDING', 260.00, NULL)
ON DUPLICATE KEY UPDATE amount_due = VALUES(amount_due);

INSERT INTO digital_certificates (id, company_id, alias, certificate_type, subject_name, subject_tax_identifier, valid_from, valid_to)
VALUES
('71000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Certificado representante demo', 'REPRESENTATIVE', 'Benjamin Gestiones Integrales SL', 'B09990001', '2025-01-01 00:00:00', '2027-01-01 00:00:00')
ON DUPLICATE KEY UPDATE alias = VALUES(alias);

INSERT INTO notifications (id, company_id, user_id, notification_type, title, body, severity, status, due_at)
VALUES
('80000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'TAX', 'Modelo 303 pendiente', 'Revisar IVA del segundo trimestre.', 'WARN', 'UNREAD', '2026-07-15 09:00:00'),
('80000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'BILLING', 'Factura pendiente de cobro', 'F-2026-0002 sigue pendiente.', 'INFO', 'UNREAD', '2026-06-11 09:00:00')
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO calendar_events (id, company_id, event_date, starts_at, ends_at, title, description, event_type)
VALUES
('81000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '2026-05-26', '2026-05-26 09:00:00', '2026-05-26 10:00:00', 'Revision de partes', 'Validar horas de obra Alba', 'LABOR'),
('81000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '2026-06-01', '2026-06-01 12:00:00', '2026-06-01 13:00:00', 'Vencimiento factura F-2026-0001', 'Comprobar conciliacion bancaria', 'BILLING')
ON DUPLICATE KEY UPDATE title = VALUES(title);

INSERT INTO knowledge_entries (id, company_id, title, category, content)
VALUES
('90000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Checklist VeriFactu', 'Facturacion', 'Validar serie, hash, QR y evento antes de enviar.'),
('90000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'Cierre mensual', 'Gestion', 'Revisar cobros, gastos, fichajes, nominas y modelos fiscales.')
ON DUPLICATE KEY UPDATE content = VALUES(content);
