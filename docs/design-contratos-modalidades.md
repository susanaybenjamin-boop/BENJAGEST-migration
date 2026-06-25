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

### 3.1. Tabla `contract_modality_catalog` (V143, editable por el asesor)
```
code (PK)            -- INDEFINIDO_ORDINARIO, TEMPORAL_PRODUCCION, ...
label                -- "Indefinido ordinario"
family               -- INDEFINIDO | TEMPORAL | FORMATIVO
unemployment_scheme  -- INDEFINIDO | TEMPORAL  (qué tipo de desempleo aplica)
special_cotization   -- BOOLEAN (formativos: cotización especial pendiente)
legal_reference      -- "RDL 32/2021; Orden PJC/297/2026"
active               -- BOOLEAN
display_order
```
Seed con las 7 filas de arriba. El asesor puede activar/desactivar o añadir.

### 3.2. Tipos de desempleo por año (ampliar `ss_contribution_rates`, V144)
La tabla ya tiene `ee_unemployment`/`er_unemployment` (= indefinido). Añadir:
```
ee_unemployment_temporal  DEFAULT 1.60
er_unemployment_temporal  DEFAULT 6.70
```
(year-dependiente, no-code, como el resto.)

### 3.3. Motor (`PayslipService`)
Al calcular el desempleo: leer la modalidad del contrato → su `unemployment_scheme`
en el catálogo → usar el par de tipos (indefinido o temporal) del año. Hoy usa
siempre el indefinido; el cambio es escoger el par según el esquema.

### 3.4. UI
- **Formulario de contrato**: el campo "tipo" pasa a ser un **desplegable del
  catálogo** (no texto libre). Migrar el valor actual "Indefinido" → `INDEFINIDO_ORDINARIO`.
- **Pantalla de catálogo** (config asesoría): ver/editar las modalidades y sus
  esquemas, con la referencia legal — "expuesto a los asesores" como pides.

## 4. Slices

- **CM-1** — V143 `contract_modality_catalog` + seed (7 filas).
- **CM-2** — V144 tipos desempleo temporal en `ss_contribution_rates`.
- **CM-3** — `PayslipService`: desempleo según esquema de la modalidad. *(motor → con Benjamin)*.
- **CM-4** — Formulario de contrato: desplegable de modalidad + migración del valor actual.
- **CM-5** — Pantalla de catálogo de modalidades (config asesoría) con referencia legal.
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
