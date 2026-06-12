# Plan de pruebas — Sesión 2026-06-12

> **Para Benjamin cuando vuelva del trabajo.** Esta sesión ha
> implementado 6 slices autónomos + cerró el bloque TPB (4 sprints).
> Todos los commits están en `develop`. Backend probado contra
> arranque limpio (todas las migraciones aplican OK).

Para arrancar la prueba:

```bash
cd C:\Proyectos\git\benjagest-migration
git checkout develop && git pull --ff-only
mvn -pl backend-java spring-boot:run
# en otra terminal:
mvn -pl ui javafx:run
```

Las V96–V103 se aplican en el primer arranque. Verás 8 líneas
"Successfully applied 1 migration to schema benjagest".

---

## 1. TPB-1 — Acuerdo previo facturación por tercero

**Flujo cliente VINCULADO**:
1. Login asesoría (admin@benjagest.local, PIN 2406).
2. Módulo Clientes → seleccionar Marcos Construcciones SL (o
   cualquier cliente vinculado) → "Abrir".
3. Pestaña nueva **"Acuerdo facturación"** (icono firma).
4. Pulsar "Proponer acuerdo". Marcar "Facturas emitidas (ventas)" +
   confirmar.
5. **Cerrar sesión y entrar como el cliente vinculado** (su PIN).
6. En el home aparece **banner amarillo** "Tu asesoría te ha propuesto
   un acuerdo de facturación".
7. Pulsar "Leer y firmar" → modal con términos + checkbox + PIN.
8. Tras firmar: el acuerdo pasa a **ACTIVE**. PDF descargable desde
   la pestaña.

**Flujo cliente NO VINCULADO**:
1. En la pantalla del cliente (managed_client sin invitación aceptada).
2. Pestaña "Acuerdo facturación" → "Proponer acuerdo".
3. Botón **"Descargar PDF para firma"** → guarda PDF en disco.
4. Cliente lo imprime, firma a mano, escanea.
5. Asesoría: "Subir PDF firmado" → selecciona el escaneado → ACTIVE.

**Lo que comprueba**: RD 1619/2012 art. 5 cumplido.

---

## 2. TPB-2 — Serie de facturación expedida por tercero

**Tras activar TPB-1**, la asesoría debe poder emitir facturas.

1. Con la asesoría "actuando como" el cliente → módulo Facturación.
2. Crear nueva factura → validar.
3. **Verificar**: el número debe llevar prefijo **TPB-{NIF de
   asesoría}-{año}-{0001}**, no el prefijo normal del cliente.
4. El cliente puede seguir emitiendo facturas con su serie propia
   (independiente).

Lo que comprueba: RD 1619/2012 art. 6.1.b serie distinta por tercero.

---

## 3. TPB-3 — Aceptación factura-a-factura

**Tras emitir la primera factura TPB**:

1. La factura queda en estado **PENDING_CLIENT_APPROVAL** (NO
   VALIDATED). NO se ha generado PDF ni se ha enviado a VeriFactu.
2. Sale de la sesión y entra como el **cliente vinculado**.
3. En el home aparece **banner naranja** "N facturas pendientes de tu
   aprobación".
4. Pulsar "Revisar" → tabla con las facturas.
5. **Aprobar** una: el botón Aceptar manda a VALIDATED, dispara
   VeriFactu + PDF.
6. **Rechazar** otra con motivo: vuelve a DRAFT con el motivo
   registrado. La asesoría puede editarla y reenviar.

Lo que comprueba: control del titular fiscal sobre el material emitido.

---

## 4. TPB-4 — Marca AEAT "emitida por tercero"

**Verificar en BD tras aprobar TPB-3**:

```sql
SELECT id, hash_current, issued_by_third_party,
       third_party_nif, third_party_name
  FROM verifactu_registry
 WHERE issued_by_third_party = TRUE;
```

- `issued_by_third_party = 1`
- `third_party_nif` = NIF de la asesoría
- `third_party_name` = nombre legal de la asesoría
- El `hash_current` incluye en su cálculo el bloque
  `&EmitidaPorTercero=T&TerceroNif=...&TerceroNombre=...`

Lo que comprueba: cumplimiento Orden HAC/1177/2024 (registro alta
con tercero expedidor).

---

## 5. GEO-FICHAR (RD 8/2019)

**Setup**: Configura un work_center con lat/lng en BD (módulo Personal
→ Centros de trabajo, botón "Buscar coordenadas (OSM)") y asígnalo a
un empleado.

**Verifica los 4 niveles de policy**:

```bash
# 1) none: no comprueba, cualquier coord pasa
curl -X POST .../api/timeclock/punch -d '{
  "employeeId":"...", "eventType":"IN", "lat":0.0, "lng":0.0}'

# 2) info: registra geo_warning_meters pero NO devuelve warning
# 3) soft: si fuera del radio, PunchResult lleva GeoWarning
# 4) strict: fuera del radio → 422 Unprocessable Entity
```

Lo que comprueba: RD 8/2019 art. 35 + uso de geo_policy ya
configurada.

---

## 6. BACKUP-LOCAL

**Disparar manual**:

```bash
curl -X POST -H "Authorization: Bearer ..." \
  http://localhost:8080/api/system/backup/run
```

**Verifica**:
- Se creó `{userHome}/BENJAGEST-backup/{2026-06-12}.zip`.
- Contiene `benjagest-{date}.sql` con `DROP TABLE` + `CREATE TABLE` +
  `INSERT INTO` por cada tabla (salta `flyway_schema_history`).
- Contiene carpeta `facturas/` con todos los PDFs almacenados.

**Listar**: `GET /api/system/backup/list` devuelve los .zip ordenados
por fecha desc.

**Cron**: el siguiente lunes a las 03:00 se ejecuta solo. La
rotación borra los más viejos cuando hay >12 archivos.

---

## 7. CAL-FISCAL — calendario AEAT

**Verifica los seeds**:

```bash
curl -H "Authorization: Bearer ..." \
  "http://localhost:8080/api/fiscal/tax-calendar/upcoming?days=180"
```

Deberías ver 8–10 vencimientos próximos: modelos 303, 130, 111
trimestrales + 190/347/390/200 anuales.

**Marcar uno como presentado**:

```bash
curl -X POST -H "Authorization: Bearer ..." \
  "http://localhost:8080/api/fiscal/tax-calendar/{id}/mark-submitted?notes=Presentado%20OK"
```

Cuando el evento es genérico (company_id NULL), el service clona la
fila como SUBMITTED para tu empresa actual sin afectar a las demás.

---

## 8. MULTI-ALLOCATION pagos

**Tener** 2 facturas VALIDATED PENDING (p.ej. F-2026-0001 por 200€
y F-2026-0002 por 150€). El cliente paga 350€ saldando ambas.

```bash
curl -X POST -H "Authorization: Bearer ..." \
  -H "Content-Type: application/json" \
  http://localhost:8080/api/billing/payments/multi-allocation -d '{
    "paymentDate": "2026-06-12",
    "totalAmount": 350.00,
    "paymentMethod": "TRANSFERENCIA",
    "reference": "TR2026061200001",
    "allocations": [
      {"invoiceId":"<id1>","amount":200.00},
      {"invoiceId":"<id2>","amount":150.00}
    ]
  }'
```

**Verifica**: ambas facturas pasan a PAID + ambos
sales_invoice_payments tienen el mismo `payment_id` UUID. Si la suma
no coincide → 400. Si una factura excede pendiente → 409.

---

## 9. REC-BANCARIA — sugerencias

**Tener** algunos `bank_movements` con `status='UNRECONCILED'` y
`linked_invoice_id=NULL` + facturas pendientes.

```bash
curl -H "Authorization: Bearer ..." \
  http://localhost:8080/api/accounting/bank-reconciliation/suggestions
```

Devuelve, por cada movimiento, candidatos ordenados por score que
cumplen los 3 criterios: ±1€ + ≤7 días + Levenshtein<3 sobre el
nombre del cliente.

---

## 10. BOE-RSS

**Disparar manual** para verificar conexión con BOE:

```bash
curl -X POST -H "Authorization: Bearer ..." \
  "http://localhost:8080/api/boe-alerts/run-now?date=2026-06-12"
```

Devuelve `{date, hits, newInserts, error}`.

**Listar últimos**:
```bash
curl -H "Authorization: Bearer ..." \
  "http://localhost:8080/api/boe-alerts?days=30"
```

Si hay hits con keywords fiscales/laborales, **deberían aparecer en
la bandeja de notificaciones del asesor** (campana del header en modo
ADVISORY) como tipo `BOE_FISCAL_LABOR`.

El cron diario corre a las 06:00.

---

## 11. Iconos Comunicación + fix selector clientes

- Módulo Comunicación → tabs **Mensajes** y **Documentos** ahora
  tienen iconos visibles (gris oscuro #1e293b).
- Selector de cliente en modo asesoría: solo aparecen clientes con
  `fullyLinked()` (que aceptaron invitación). Las shadow companies
  ya no salen porque no podrían leer al otro lado.

---

# 📋 Resumen rápido de qué se commiteó

| # | Commit | Slice | V | Estado |
|---|---|---|---|---|
| 1 | a4769d4 + 3f446d3 | TPB-1 acuerdo | V96 | Probable |
| 2 | b587e00 | TPB-2 serie | V97 | Probable |
| 3 | dd96178 + 0400d41 | TPB-3 aprobación | V98 | Probable |
| 4 | 781555f | TPB-4 marca AEAT | V99 | Probable |
| 5 | 414e87f | Fix iconos Comm | — | Listo |
| 6 | cbe39b4 | GEO-FICHAR | V100 | Probable |
| 7 | d47c1e4 | BACKUP-LOCAL | — | Probable |
| 8 | 692cbb1 | CAL-FISCAL | V101 | Probable |
| 9 | face345 | MULTI-ALLOCATION | V102 | Probable |
| 10 | 655b2af | REC-BANCARIA | — | Probable |
| 11 | b7c4e9a | BOE-RSS | V103 | Probable |

**Backend listo y arranca limpio.** UI específica de TPB ya hecha;
para los slices 6–11 los endpoints están operativos pero falta UI
dedicada (se consumen via Postman para probar el backend mientras
tanto).

Cuando apruebes cada uno, lo paso de "Probable" a "✅" en backlog.md.
