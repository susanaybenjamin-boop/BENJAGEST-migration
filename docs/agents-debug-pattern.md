# Pattern: Diagnóstico con agentes paralelos

> Receta para usar agentes Explore en paralelo cuando un bug toca varios
> archivos y la causa raíz no es evidente con una sola lectura.
> Refinado durante el bug **EMP-SCOPE-DEEP** (2026-06-08), donde dos
> agentes independientes convergieron en el mismo path roto sin
> influirse mutuamente.

---

## Cuándo usarlo

Aplica esta receta cuando el bug **cumpla 2 o más** de estos criterios:

- Toca más de **3 archivos** de capas distintas (UI ↔ API ↔ Service ↔ DB).
- Hay **endpoints duplicados** o flujos similares (ej. `/clients` vs `/clients/portfolio`).
- Los síntomas no apuntan directamente a la causa (filtro que no filtra, datos que sí están en BD pero no llegan al UI, role aparentemente correcto que actúa como otro role).
- Has hecho **2+ fixes** que parecían correctos y el bug sigue.
- Has cambiado tu hipótesis de causa raíz al menos una vez.

**NO uses esta receta cuando**:
- El stack trace ya señala el archivo y línea.
- Es un typo, un import faltante, un null check obvio.
- El bug se reproduce y arregla en menos de 5 minutos de lectura.

---

## Cómo lanzarlos

### 1. Define DOS preguntas independientes

Cada agente debe poder llegar a la causa raíz **sin necesidad del otro**.
Si fueran complementarios (uno mira UI, otro mira backend), no habría
convergencia — solo división del trabajo.

Mejor: que ambos puedan mirar el sistema entero pero **desde ángulos
distintos**. Ejemplos:

| Bug | Ángulo 1 | Ángulo 2 |
|---|---|---|
| Filtro de scope no aplica | Rastrea el flujo de `scope.customerIds` desde backend a UI | Rastrea cómo el UI obtiene la lista de clientes y dónde puede saltarse el filtro |
| Hash de cadena roto | Investiga el algoritmo `computeHash` y diferencias INSERT vs SELECT | Investiga conversión de timestamps y zonas horarias en el driver JDBC |
| Wizard no carga datos | Rastrea el endpoint backend que sirve los datos | Rastrea el parser cliente que los lee |

### 2. Cada prompt debe ser **autosuficiente**

El agente no ve el resto de la conversación. Su prompt debe llevar:

- **Repo path absoluto** (ej. `C:\Proyectos\git\benjagest-migration`).
- **Stack tecnológico** (Java 21, Spring Boot, MariaDB, JavaFX).
- **Contexto del bug en 2–3 líneas** (qué ve el usuario, qué debería ver).
- **Lista numerada** de qué archivos/funciones investigar.
- **Formato de salida pedido**: hipótesis ordenadas por probabilidad,
  quote exacto de código, archivo:línea.
- **Restricción explícita**: "NO escribas código. Solo lee y reporta."

### 3. Lánzalos en **paralelo** (mismo turno, dos llamadas a Agent)

No esperes a que el primero termine para lanzar el segundo. El paralelismo
es lo que evita la influencia mutua. Si los lanzas secuenciales:

- El segundo puede sesgarse hacia las conclusiones del primero.
- Pierdes tiempo de pared.
- No detectas si uno se ha quedado atrapado en una hipótesis falsa.

---

## Cómo evaluar los resultados

Cuando ambos agentes terminen, busca **convergencia explícita**:

| Señal | Interpretación |
|---|---|
| Ambos señalan el **mismo archivo y línea** | ≥90 % probabilidad de causa raíz. Aplica fix. |
| Coinciden en **función** pero discrepan en **por qué** | Hay un acoplamiento. Investiga la función y mira si hay un bug Y un side-effect. |
| Discrepan totalmente | Repasa los prompts: probablemente fueron sesgados o el bug está fuera de los ámbitos que les diste. Reformula y relanza. |
| Uno acierta y el otro encuentra problema secundario | Aprovecha — soluciona el bug Y aplica defensa en profundidad en el otro path. |

**Caso ejemplar EMP-SCOPE-DEEP**:
- Agente 1 (rastreando scope) llegó a `AdvisoryService.listPortfolio:174`.
- Agente 2 (rastreando "Mis clientes") llegó a `AdvisoryService.listPortfolio:174`.
- Convergencia perfecta + el agente 2 además identificó la oportunidad
  de defensa en profundidad en UI (`canSeeCustomer` no se invocaba al
  rellenar la tabla).
- Fix: una capa backend + una capa UI. Bug cerrado.

---

## Estructura de prompt recomendada

```
Estoy depurando un bug en BENJAGEST (Java 21 + Spring Boot 3.3.5 +
JavaFX 21 + MariaDB 11.4) donde [DESCRIPCIÓN SINTOMÁTICA EN 1 LÍNEA].

Repo: C:\Proyectos\git\benjagest-migration

Contexto:
- [QUÉ HIZO EL USUARIO]
- [QUÉ ESPERABA]
- [QUÉ VE EN REALIDAD]

Tu misión: [ÁNGULO DE INVESTIGACIÓN].

Investiga ESPECÍFICAMENTE:

1. [Archivo + qué buscar]
2. [Archivo + qué buscar]
3. [Archivo + qué buscar]
...

Reporta:
- Las N líneas/funciones más probables de ser la causa.
- En cada una: quote exacto + por qué podría romper.
- 3 hipótesis ordenadas por probabilidad.
- Tu mejor hipótesis con fix sugerido en 2-3 líneas.

NO escribas código. Solo lee y reporta. Sé conciso pero técnico.
```

---

## Anti-patterns a evitar

- **Lanzar un solo agente** "para que investigue todo". Sin convergencia
  no tienes señal de confianza.
- **Lanzar 4+ agentes** para el mismo bug. El ruido supera al beneficio
  y duplicas información. 2 suele bastar.
- **Pedirles que arreglen** ("si encuentras el bug, arréglalo"). Que
  reporten primero. Tú aplicas el fix sabiendo el contexto completo
  de la conversación.
- **Prompts cortos** ("encuentra el bug del filtro de scope"). Sin
  contexto los agentes derivan a hipótesis genéricas.
- **Mismo prompt a los dos**. Pierdes el sentido del paralelismo: ambos
  llegan por el mismo camino.

---

## Para futuras tareas en BENJAGEST

Esta receta se aplica especialmente bien a:

- **Bugs de scope / permisos / multi-tenant** (donde un usuario ve más
  o menos de lo que debe).
- **Bugs de cadena hash** (verificación SIF/VeriFactu, audit chain).
- **Bugs de cliente↔asesoría** (cuando el `actingForCompanyId` se cuela
  donde no debe, o viceversa).
- **Parser JSON UI** (cuando el regex `parseObjects` falla con anidados —
  ya pasó con convenios, asignaciones, y volverá a pasar).
- **Refactors de endpoints duplicados** (cuando hay dos endpoints
  similares y uno olvida un filtro o validación).

Documenta cada uso exitoso en el commit message con la pista:
**"Diagnóstico hecho con agentes Explore en paralelo. Los dos
coincidieron en [archivo:línea]."**

Eso construye historial: los siguientes mantenedores ven que cuando
hay convergencia entre agentes, la causa raíz es real y el fix es sólido.

---

## Para tareas de implementación nueva (no bugs)

El pattern también aplica a **diseño** de features grandes:

- **Agente Plan** (subagent_type=`Plan`) para diseñar el approach.
- **Agente Explore** para identificar puntos de integración con código
  existente.

Cuando los dos coinciden en la arquitectura, el riesgo de tirar código
que choque con el resto del codebase es bajo.

Caso ejemplar (a futuro): cuando ataquemos **VF-CHAIN-FIX** (cadena
hash SIF rota por TZ), usaremos:
- Agente 1: investigar dónde se construye el `OffsetDateTime` que va al
  hash.
- Agente 2: investigar dónde se reconstruye al verificar.
- Esperamos convergencia en un único método de conversión TZ que esté
  mal.

---

## Idea pendiente: skill en Claude Desktop

Benjamin propuso (2026-06-08) que esto se materialice como una **skill**
de Claude Desktop, de modo que al crear código futuro el agente
principal use automáticamente esta receta para auto-validarse.

Forma sugerida cuando se implemente la skill:

1. Skill `benjagest-debug-with-agents` activa la receta.
2. Al detectar bug con criterios (>3 archivos, >2 fixes fallidos),
   sugiere lanzar 2 Explore en paralelo.
3. Recoge respuestas, compara, reporta convergencia/divergencia.
4. Propone fix solo si hay convergencia ≥ 90 %; si no, pide al usuario
   que elija ángulo de re-investigación.

Esto se vincula a la skill global de Claude Code para que el repo
BENJAGEST tenga supervisión automática de calidad del diagnóstico.
