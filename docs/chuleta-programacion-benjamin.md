# Chuleta personal de programación — Benjamin

> Apuntes para imprimir y repasar. Material vivo: se va ampliando con cada lección. Cada concepto está explicado en lenguaje de obra/albañilería para fijarlo mejor.
>
> **Lecciones recogidas hasta ahora:** 1 (¿qué es un programa?) y 2 (variables y tipos).
>
> **Mantra del aprendiz:** *No tengo prisa. Cada concepto, cuando lo entienda, será mío para siempre.*

---

## Mapa completo del aprendizaje

| # | Lección | Estado |
|---|---|---|
| 1 | ¿Qué es un programa? | ✅ Hecha |
| 2 | Variables y tipos de datos | ✅ Hecha |
| 3 | Funciones (bloques reutilizables) | ⏳ Siguiente |
| 4 | Decisiones (if) y bucles (for) | Pendiente |
| 5 | Listas y mapas | Pendiente |
| 6 | Objetos y clases | Pendiente |
| 7 | Cómo se monta una app por capas | Pendiente |

Al terminar las 7, podrás abrir cualquier archivo de BENJAGEST y entender el **80%** de lo que hace.

---

# Lección 1: ¿Qué es un programa?

## Idea clave

Un programa es **un parte de obra muy detallado para una máquina**.

La diferencia brutal con un parte de obra para personas:

> Un peón experimentado rellena los huecos del parte con su experiencia.
> **Un ordenador NO rellena huecos.** Si no le dices algo, no lo hace.
> Si te saltas un paso, se rompe. Si das algo por hecho, falla.

## Parte para una persona vs parte para una máquina

**Para un peón:**
```
Levanta el tabique del baño.
```

**Para una máquina (programa):**
```
1. Ve al almacén.
2. Coge 50 ladrillos huecos de 7 cm.
3. Coge un saco de cemento cola.
4. Coge un cubo.
5. Llévalo todo al baño.
6. En el cubo, mezcla 2 partes de cemento con 1 de agua.
   Remueve hasta homogéneo.
7. Pon la primera fila de ladrillos pegándolos con la mezcla.
8. Para cada fila siguiente, hasta llegar al techo:
     - Coloca los ladrillos a tresbolillo.
     - Echa mezcla entre filas.
     - Comprueba con nivel.
9. Cuando llegues al techo, recoge y limpia.
```

## El visible y el invisible

Por cada acción del usuario, el programa hace **decenas de pasos invisibles**.

**El usuario ve:** *clic en "Guardar factura".*

**El programa hace por debajo (21 pasos):**
1. Validar que hay cliente seleccionado.
2. Validar que la fecha es válida.
3. Validar que hay al menos un concepto.
4. Validar que el IVA está dentro de tipos válidos.
5. Calcular importe de cada línea (cantidad × precio).
6. Sumar líneas → base imponible.
7. Calcular IVA (base × porcentaje / 100).
8. Calcular total (base + IVA).
9. Pedir a la BD el siguiente número de factura de la serie.
10. Crear el objeto factura en memoria con todos los datos.
11. Guardar el objeto en la base de datos.
12. Calcular el hash de Verifactu (encadenado al anterior).
13. Firmar con el certificado digital de la empresa.
14. Guardar el registro Verifactu en su tabla.
15. Encolar el envío a AEAT (offline-first).
16. Generar el PDF de la factura.
17. Encolar el envío del PDF por email si procede.
18. Registrar la acción en auditoría.
19. Cerrar la ventana de creación.
20. Refrescar el listado de facturas.
21. Mostrar mensaje de confirmación.

## Reutilización (DRY)

Esos 21 pasos **no se escriben cada vez**. Cada paso se escribe **una sola vez** como una "función con nombre", y luego se llama desde donde haga falta.

> **Regla "DRY"** (*Don't Repeat Yourself*, "no te repitas"):
> Si te ves escribiendo lo mismo dos veces, **para**. Hazlo función. Reutilízalo.

**Analogía:** un buen albañil tiene rutinas dominadas (cómo levantar tabique, cómo echar solera) y las aplica una y otra vez sin reinventar nada. Un mal albañil cada tabique lo hace como si fuera el primero. Con el código: igual.

## Lo que se lleva de la Lección 1

1. Programar = escribir el parte invisible.
2. El visible (clic, formulario) es la punta del iceberg.
3. Cada paso se escribe UNA vez y se reutiliza (DRY).
4. La habilidad clave del programador: **no dar nada por hecho**.

---

# Lección 2: Variables y tipos de datos

## ¿Qué es una variable?

Una variable es **una caja con etiqueta donde guardas un dato para usarlo después**.

```
┌──────────────────────────┐
│ ETIQUETA: sacosCemento   │   ← nombre de la variable
│ TIPO:     número entero  │   ← qué cabe dentro
│ CONTENIDO: 25            │   ← el valor actual
└──────────────────────────┘
```

Analogía: estanterías de un almacén etiquetadas. Sin la etiqueta no sabes qué hay; sin saber qué tipo de material aguanta la estantería, metes lo que no toca y se rompe.

## El tipo: qué CABE en la caja

Cada variable es de **un tipo concreto**. Java es **estricto** con esto (mucho más que TypeScript): si declaras `int sacosCemento`, no puedes guardarle un texto "veinticinco".

**Por qué importa:** evita disparates como sumar `"Miguel López" + 25`. El programa te avisa **antes** de que explote.

## Los 7 tipos que más vas a ver en Java

| Tipo Java | Para qué sirve | Ejemplo |
|---|---|---|
| `int` | Números enteros (sin decimales, no muy grandes) | `5`, `1200`, `-3` |
| `long` | Números enteros grandes (ids, millones) | `1234567890` |
| `double` | Decimales en general (medidas, porcentajes) | `3.14`, `21.5` |
| `BigDecimal` | **Decimales para DINERO** (precisión exacta) | `125.50` |
| `String` | Texto (letras, palabras, frases) — con comillas | `"Miguel López"` |
| `boolean` | Verdadero o falso, solo dos valores | `true`, `false` |
| `LocalDate` | Una fecha (día/mes/año) | `2026-05-27` |

## Cómo se declara una variable en Java

Estructura: **TIPO + nombre + = + valor + ;**

```java
int sacosCemento = 25;
String clienteObra = "Miguel López";
boolean facturaPagada = false;
BigDecimal precioFactura = new BigDecimal("125.50");
LocalDate fechaFactura = LocalDate.of(2026, 5, 27);
```

El punto y coma `;` al final = "fin de frase", como el punto en español.

## Convención de nombres: camelCase

Los nombres de variables se escriben **sin espacios, sin acentos, sin guiones**, y cada palabra nueva empieza con mayúscula (menos la primera).

✅ `fechaFactura`, `numeroDeCliente`, `precioUnitarioSinIva`
❌ `fecha factura`, `fecha-factura`, `FechaFactura`, `fecha_factura`

Se llama **camelCase** porque las mayúsculas hacen jorobas como las de un camello.

## Las 3 reglas de oro

### Regla 1 — Parece número pero lleva letras → `String`

```
NIF: B12345678       → String (NO int)
CIF: A87654321       → String
Número factura: F2026/0123 → String
Código postal: 28080 → puede ser String si vas a respetar ceros a la izquierda
Matrícula: 1234ABC   → String
```

**Por qué:** si lo guardas como número entero, las letras y los símbolos (`/`, `-`) no caben. La caja `int` los rechaza.

### Regla 2 — Es una fecha → `LocalDate`, NUNCA `String`

```
fechaFactura: 27/05/2026  → LocalDate
```

**Por qué no String:** si guardas la fecha como texto, el programa no puede:
- Restar fechas ("¿hace cuántos días?").
- Ordenar facturas cronológicamente (las ordenaría alfabéticamente — desastre).
- Entender que `"27-5-26"` y `"27/05/2026"` son la misma fecha.

**Regla mental:** si ves `String fecha = ...` en código, casi siempre es un bug a punto de pasar.

### Regla 3 — Es dinero → `BigDecimal`, NUNCA `double`

```
precioUnitario: 25.00 €      → BigDecimal
totalFactura:   151.25 €     → BigDecimal
baseImponible:  125.00 €     → BigDecimal
```

**Por qué no double:** los `double` guardan decimales de forma **aproximada** internamente. `0.1 + 0.2` puede dar `0.30000000000000004`. Una factura de 100 € te puede dar 99,9999 €. **La factura está mal, Hacienda se enfada, tú pierdes credibilidad**.

`BigDecimal` guarda decimales con precisión exacta. Cuesta un poco más de teclear, pero es lo correcto.

**Apunte clave:** cuando veas `BigDecimal` en el código de BENJAGEST, ya sabes — *"esto es dinero, requiere precisión"*.

## Resumen visual de las 3 reglas

```
┌──────────────────────────────────────────────────────────┐
│  ¿Parece número pero tiene letras?  →  String            │
│  ¿Es una fecha?                     →  LocalDate         │
│  ¿Es dinero?                        →  BigDecimal        │
└──────────────────────────────────────────────────────────┘
```

## Lo que se lleva de la Lección 2

1. Una variable es una caja con etiqueta y contenido.
2. Cada variable es de un tipo, y el tipo dice qué cabe.
3. Java es estricto: hay que declarar el tipo siempre.
4. camelCase para nombres (sin espacios ni acentos).
5. **Las 3 reglas de oro** se aplican siempre, sin excepciones.

---

# Glosario de palabras que ya conoces

| Palabra | Significado en cristiano |
|---|---|
| **Programa** | Lista de instrucciones precisas para una máquina |
| **Variable** | Caja con etiqueta y contenido |
| **Tipo de dato** | Qué clase de cosa cabe en la caja (texto, número, fecha…) |
| **Declarar** una variable | Crear la caja con su etiqueta y tipo |
| **Asignar** un valor | Meter algo dentro de la caja (`= 25`) |
| **Función** / **Método** | Bloque de pasos con nombre, reutilizable |
| **DRY** | "No te repitas". Si escribes lo mismo dos veces, hazlo función |
| **camelCase** | Convención de nombres con mayúsculas tipo `fechaFactura` |
| **Visible / Invisible** | Lo que ve el usuario vs lo que hace el programa por debajo |
| **Punto y coma `;`** | Fin de frase en Java |
| **`String`** | Tipo de variable que guarda texto |
| **`int`** | Tipo de variable que guarda números enteros |
| **`BigDecimal`** | Tipo de variable para guardar dinero con precisión |
| **`LocalDate`** | Tipo de variable que guarda una fecha |
| **`boolean`** | Tipo de variable que guarda `true` o `false` |

---

# Ejercicios resueltos

## Ejercicio 1 — Lección 1: escribir un parte de obra para crear una factura

**Lo que escribí (perspectiva del usuario):**
```
1. Enciende la app
2. Click en "Facturas"
3. Click en "Nueva"
4. Selecciona cliente Miguel
5. Selecciona fecha hoy
6. Escribe concepto "ejemplo"
7. Escribe unidades 5
8. Escribe precio 25
9. Selecciona IVA 21
10. Click en "Guardar factura"
```

**Aprendizaje:** lo que escribí es **el guion del usuario** (caso de uso). El programa hace por debajo unas 200 instrucciones invisibles. Mi rol natural = describir lo que el usuario hace y espera. El programador (Pablo) traduce eso al guion invisible del programa.

## Ejercicio 2 — Lección 2: identificar tipos de los campos de una factura

| Campo | Tipo que asigné | Correcto | Notas |
|---|---|---|---|
| Cliente: "Construcciones García SL" | `String` | ✅ | Texto puro |
| NIF: "B12345678" | `String` | ✅ | Lleva letra, no es número |
| Fecha: 27/05/2026 | `String` ❌ | Debería ser `LocalDate` | Las fechas NUNCA como texto |
| Número: "F2026/0123" | `String` | ✅ | Lleva letras y barra |
| Concepto: "Reparación de tabique" | `String` | ✅ | Texto |
| Cantidad: 5 | `int` | ✅ | Entero |
| Precio unitario: 25,00 € | `BigDecimal` | ✅ | Dinero |
| IVA aplicado: 21 | `int` | ✅ (matiz) | Acepta entero; `BigDecimal` si quieres soportar 4,5% |
| Total factura: 151,25 € | `BigDecimal` | ✅ | Dinero |
| Pagada: No | `boolean` | ✅ | Sí/No |

**Resultado: 9/10 con razonamiento correcto.** La que se me escapó fue la fecha (error típico, le pasa a todo el mundo la primera vez). Aprendí la regla: *si es fecha, `LocalDate`, nunca `String`*.

---

# Cómo seguir entrenando solo

Cuando tengas un rato y quieras practicar lo aprendido, abre uno de estos archivos del proyecto y lee las **líneas que declaran variables**:

```
backend-java/src/main/java/com/benjagest/backend/customer/CustomerResponse.java
backend-java/src/main/java/com/benjagest/backend/workspace/PinLoginRequest.java
backend-java/src/main/java/com/benjagest/backend/workspace/DashboardItem.java
```

Para cada variable que veas, intenta identificar:
- **Su tipo** (`String`, `int`, `boolean`, etc.).
- **Su nombre** y si sigue camelCase.
- **Qué guarda** según el contexto del archivo.

No tienes que entender el resto del archivo. Solo las variables. Es como ir a una obra ajena y reconocer los materiales — todavía no sabes construirla, pero ya distingues qué cosas hay.

---

*Última actualización: 2026-05-27. Lecciones recogidas: 1 y 2. Siguiente: Lección 3 (Funciones).*
