# Bloque CONTRATO-MODALIDADES — catálogo de modalidades de contrato por ley (no-code)

> Diseño a partir de la petición de Benjamin (2026-06-25): *"todo esto tiene que
> estar por ley expuesto a los asesores, no quiero que nos dejemos otros tipos de
> contrato, y que el porcentaje varíe entre otros tipos"*. Estado: **propuesta para
> validar con Benjamin** (es su dominio legal + toca el motor de cálculo, §11.2).

## 1. El problema detectado (sesión 2026-06-25)

BENJAGEST aplica el **mismo tipo de desempleo** (1,55% trab. / 5,50% emp.) a TODOS
los contratos. La realidad legal: el desempleo **varía según la modalidad**. En el
PDF real (trabajador temporal de construcción) el desempleo del trabajador es
**1,60%**, no 1,55% → para temporales BENJAGEST infra-cotiza.

Además `employment_contracts.contract_type` es un **varchar libre** ("Indefinido")
sin catálogo: no hay forma fiable de saber la modalidad ni de exponerla al asesor.

## 2. Catálogo legal de modalidades vigentes 2026 (verificado)

Tras la **reforma laboral 2022 (RDL 32/2021)** — el contrato "de obra y servicio"
**desapareció** (efectivo 31/03/2022). Quedan:

| Código | Modalidad | Familia | Desempleo **trab.** | Desempleo **emp.** | Notas legales |
|---|---|---|---|---|---|
| `INDEFINIDO_ORDINARIO` | Indefinido ordinario | INDEFINIDO | 1,55 % | 5,50 % | contrato por defecto |
| `FIJO_DISCONTINUO` | Fijo-discontinuo | INDEFINIDO | 1,55 % | 5,50 % | actividad intermitente/estacional |
| `INDEFINIDO_ADSCRITO_OBRA` | Indefinido adscrito a obra (fijo de obra) | INDEFINIDO | 1,55 % | 5,50 % | **construcción**; sustituye al antiguo "obra o servicio" |
| `TEMPORAL_PRODUCCION` | Temporal por circunstancias de la producción | TEMPORAL | 1,60 % | 6,70 % | máx. 6 m (12 por convenio) |
| `TEMPORAL_SUSTITUCION` | Temporal por sustitución | TEMPORAL | 1,60 % | 6,70 % | antiguo interinidad |
| `FORMATIVO_ALTERNANCIA` | Formativo en alternancia | FORMATIVO | 1,55 % | 5,50 % | **cotización especial** (cuota reducida) |
| `FORMATIVO_PRACTICA` | Formativo para práctica profesional | FORMATIVO | 1,55 % | 5,50 % | titulación reciente |

> **Nota construcción (tu caso):** "hasta terminación de obra" hoy es
> `INDEFINIDO_ADSCRITO_OBRA` (**indefinido**, 1,55 %). Tu gestoría lo registró como
> **temporal** (1,60 %), probablemente `TEMPORAL_PRODUCCION`. A confirmar contigo
> cuál es el correcto para tu convenio. **El catálogo debe permitir las dos** y que
> el asesor elija con conocimiento de causa.

> **Formativos:** además del desempleo, tienen reglas de cotización propias
> (la alternancia cotiza por **cuota fija/reducida**, no por base). Eso es un sub-bloque
> aparte del motor; de momento el catálogo los marca y se afina luego.

## 3. Diseño no-code

> **DECISIÓN DE IMPLEMENTACIÓN (2026-06-25):** NO se crea un catálogo nuevo.
> El catálogo legal de modalidades **ya existe**: `sepe_contract_types` (V74),
> con TODOS los códigos SEPE oficiales y su `family`. Crear otra tabla lo
> duplicaría. En su lugar **extendemos** esa tabla con el único dato que le
> faltaba para la nómina: el esquema de desempleo por código.

### 3.1. Extender `sepe_contract_types` con el esquema de desempleo (V144)
```
ALTER TABLE sepe_contract_types
    ADD COLUMN unemployment_scheme VARCHAR(20) DEFAULT 'INDEFINIDO';  -- INDEFINIDO | TEMPORAL
```
El **MATIZ** clave (Orden PJC/297/2026): el esquema NO se deriva de la familia.
La familia TEMPORAL contiene la **sustitución/interinidad** (411/511), que
cotiza desempleo al esquema **INDEFINIDO** (7,05 %), igual que los formativos
(421/521) y prácticas (401/501). Solo cotizan TEMPORAL (8,30 %): producción
(300/410/510/420), inserción (405/505) y Fondos Europeos (406/506). Por eso se
marca **código a código** (UPDATE en V144). Editable no-code: si la ley cambia,
se ajusta la columna del código afectado.

### 3.2. Tipos de desempleo por año (ampliar `ss_contribution_rates`, V143)
La tabla ya tiene `ee_unemployment`/`er_unemployment` (= indefinido). Añadir:
```
ee_unemployment_temporal  DEFAULT 1.60
er_unemployment_temporal  DEFAULT 6.70
```
(year-dependiente, no-code, como el resto.)

### 3.3. Motor (`PayslipService`)
Al calcular el desempleo: leer el `sepe_contract_code` del contrato →
`ContractCatalogService.isTemporalUnemployment(code)` (consulta
`sepe_contract_types.unemployment_scheme`) → si TEMPORAL usa el par temporal del
año (`ss_contribution_rates.ee/er_unemployment_temporal`), si no el indefinido.
Default DEFENSIVO indefinido: código nulo/desconocido → como antes.

### 3.4. UI
- **Formulario de contrato**: el wizard YA tiene `familyCombo` + `sepeCombo` que
  guardan `sepe_contract_code` (verificado: contratos existentes con code=100).
  El esquema de desempleo queda determinado por ese código → no requiere campo nuevo.
- **Pantalla de catálogo** (config asesoría): ver/editar `sepe_contract_types` y
  su `unemployment_scheme`, con la referencia legal — "expuesto a los asesores".

## 4. Slices

- **CM-1** ✅ — V143: columnas `ee/er_unemployment_temporal` en `ss_contribution_rates`.
- **CM-2** ✅ — V144: columna `unemployment_scheme` en `sepe_contract_types` + UPDATE por código.
- **CM-3** ✅ — `PayslipService`: desempleo según el código SEPE del contrato.
- **CM-4** ✅ — (ya cubierto) el wizard guarda `sepe_contract_code`; no hay campo nuevo.
- **CM-5** — (pendiente, opcional) pantalla de catálogo para ver/editar el esquema por código.
- **CM-6** — (futuro) cotización especial de formativos (cuota fija alternancia).

## 5. Decisiones / validación de Benjamin

1. **¿El catálogo de la sección 2 está completo y correcto?** (eres el experto —
   confirma/corrige modalidades y esquemas de desempleo).
2. **Tu caso construcción**: ¿lo registramos como `INDEFINIDO_ADSCRITO_OBRA`
   (indefinido, lo correcto post-2022) o como `TEMPORAL_PRODUCCION` (como hizo la
   gestoría)? El catálogo permite ambos.
3. **¿Construimos el bloque ahora?** CM-1/CM-2/CM-4/CM-5 son aditivos/no-code (puedo
   en autónomo); **CM-3 (motor) lo hago contigo delante** salvo que autorices.

## 6. Fuentes
- Reforma laboral / modalidades vigentes 2026.
- Tipos de cotización desempleo 2026: Orden PJC/297/2026 (BOE, efectos 01/01/2026):
  indefinido 7,05 % (5,50 + 1,55) · temporal 8,30 % (6,70 + 1,60) · formativos 7,05 %.
