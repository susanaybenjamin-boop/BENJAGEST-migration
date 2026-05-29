# Chuleta personal de programación — Benjamin

> Apuntes para imprimir y repasar. Material vivo: se va ampliando con cada lección. Cada concepto está explicado en lenguaje de obra/albañilería para fijarlo mejor.
>
> **Lecciones recogidas hasta ahora:** 1, 2, 3 y 4.
>
> **Mantra del aprendiz:** *No tengo prisa. Cada concepto, cuando lo entienda, será mío para siempre.*

---

## Mapa completo del aprendizaje

| # | Lección | Estado |
|---|---|---|
| 1 | ¿Qué es un programa? | ✅ Hecha |
| 2 | Variables y tipos de datos | ✅ Hecha |
| 3 | Funciones (bloques reutilizables) | ✅ Hecha |
| 4 | Decisiones (`if`) y bucles (`for`) | ✅ Hecha |
| 5 | Listas y mapas | ⏳ Siguiente |
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
NIF: B12345678              → String (NO int)
CIF: A87654321              → String
Número factura: F2026/0123  → String
Código postal: 28080        → puede ser String si vas a respetar ceros a la izquierda
Matrícula: 1234ABC          → String
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

# Lección 3: Funciones (bloques reutilizables)

## Idea clave

Una **función** es **un bloque de pasos con nombre, que recibe datos, hace algo con ellos y devuelve un resultado**.

Es la herramienta más importante del programador. Casi todo el código que verás en BENJAGEST es:
- O bien una **definición de función** (la técnica explicada paso a paso).
- O bien una **llamada a función** (usar una técnica que ya está definida).

## Analogía de obra

El maestro de obras tiene una libreta de técnicas dominadas. Cada técnica tiene:

```
┌─────────────────────────────────────────────────────┐
│ TÉCNICA: preparar_mortero                           │  ← nombre
│                                                     │
│ NECESITO QUE ME DEN:                                │  ← parámetros (lo que entra)
│   - cantidad de sacos de cemento                    │
│   - cantidad de litros de agua                      │
│                                                     │
│ PASOS:                                              │  ← cuerpo
│   1. Echo los sacos en el cubo.                     │
│   2. Añado el agua.                                 │
│   3. Mezclo hasta que esté homogéneo.               │
│                                                     │
│ TE DEVUELVO:                                        │  ← valor de retorno
│   - un cubo con mortero listo                       │
└─────────────────────────────────────────────────────┘
```

Cuando estés en obra y necesites mortero, **no vuelves a explicar todos los pasos**. Solo dices: *"preparar_mortero(3 sacos, 5 litros)"*.

## Los 4 componentes de toda función

| Componente | Pregunta que responde | Ejemplo (albañilería) |
|---|---|---|
| **Nombre** | ¿Cómo se llama esta técnica? | `preparar_mortero` |
| **Parámetros** | ¿Qué necesito que me den para hacerla? | sacos, litros |
| **Cuerpo** | ¿Qué pasos sigo por dentro? | echar, añadir, mezclar |
| **Valor de retorno** | ¿Qué te entrego cuando termino? | cubo con mortero |

## Cómo se ve esto en Java

Función real que calcula el IVA de una factura:

```java
public BigDecimal calcularIva(BigDecimal base, BigDecimal porcentaje) {
    BigDecimal iva = base.multiply(porcentaje).divide(new BigDecimal("100"));
    return iva;
}
```

Lectura componente por componente:

```
public BigDecimal calcularIva(BigDecimal base, BigDecimal porcentaje) {
   ↑       ↑           ↑              ↑                       ↑
   │       │           │              │                       │
   │       │           │              └── parámetro 2: porcentaje (BigDecimal)
   │       │           └── parámetro 1: base imponible (BigDecimal)
   │       └── nombre: calcularIva
   │
   └── lo que devuelve: BigDecimal (dinero → BigDecimal, regla 3)
```

```java
    BigDecimal iva = base.multiply(porcentaje).divide(new BigDecimal("100"));
                     └── cuerpo: hace la cuenta del IVA (base × porcentaje / 100)
    return iva;
                     └── valor de retorno: el IVA calculado
}
```

> ⚠️ **Detalle importante con BigDecimal**: no puedes usar `*` y `/` directamente. Tienes que usar `.multiply(...)` y `.divide(...)`. Es por la precisión del dinero. Lo verás constantemente en código de facturación.
>
> La palabra `public` significa "esta técnica está abierta para que cualquiera la use". La veremos a fondo en la Lección 6.

## Llamar a la función (usarla)

Una vez que la función existe, calcular IVA en cualquier sitio es **una línea**:

```java
BigDecimal ivaFactura = calcularIva(new BigDecimal("125.00"), new BigDecimal("21"));
```

Léelo así:
- *"Llama a `calcularIva` dándole base=125 y porcentaje=21."*
- *"Lo que devuelva, guárdalo en una caja llamada `ivaFactura`."*

En este caso, `ivaFactura` valdrá `26.25`.

## Funciones que llaman a otras funciones (orquestación)

Una función puede usar otras funciones por dentro. El parte de "guardar factura" se convierte en esta función orquestadora:

```java
public Factura crearFactura(DatosFactura datos) {
    validar(datos);
    BigDecimal base = calcularBaseImponible(datos.lineas);
    BigDecimal iva = calcularIva(base, datos.porcentajeIva);
    BigDecimal total = base.add(iva);

    Factura factura = construirFactura(datos, base, iva, total);
    asignarNumero(factura);
    aplicarVerifactu(factura);
    guardarEnBaseDeDatos(factura);
    encolarEnvioAEAT(factura);
    encolarEnvioEmail(factura);
    registrarAuditoria(factura);

    return factura;
}
```

**14 líneas**, pero por dentro son los 200+ pasos. Si mañana cambia el cálculo del IVA, solo tocas `calcularIva`. **El cambio se aplica en todos los sitios que la usan**.

## Funciones que NO devuelven nada (`void`)

A veces una función hace algo pero no te devuelve un dato. Se marcan con `void` (vacío):

```java
public void guardarEnBaseDeDatos(Factura factura) {
    // pasos para guardar
    // no hay return
}
```

Léelo: *"esta función no devuelve nada, solo hace su trabajo y se calla"*.

Analogía: hay técnicas en obra que te devuelven algo (la mezcla, el ladrillo cortado) y otras que solo **dejan algo hecho** (limpiar la zona, regar el hormigón). Las segundas son las `void`.

## Cómo nombrar bien una función

> **Las funciones se nombran con un VERBO en infinitivo seguido de lo que hacen.**

✅ `calcularIva`, `guardarFactura`, `enviarEmail`, `validarNif`, `obtenerCliente`
❌ `iva`, `factura`, `email`, `nif`, `cliente`

**Variables = cosas (sustantivos). Funciones = acciones (verbos).** Si abres un archivo y todo está bien nombrado, **lees el código casi como castellano**.

## Lo que se lleva de la Lección 3

1. **Una función es una técnica con nombre.** 4 componentes: nombre, parámetros, cuerpo, valor de retorno.
2. **Se escriben una vez y se llaman muchas veces** (DRY en acción).
3. **Funciones llaman a otras funciones** formando capas (orquestación).
4. **Las funciones se nombran con VERBO** + lo que hacen.
5. **BigDecimal usa `.multiply()` y `.divide()`**, no `*` ni `/`.

---

# Lección 4: Decisiones (`if`) y bucles (`for`)

Con estas dos herramientas, un programa puede **decidir** y **repetir**. Sin ellas, solo sabe ir en línea recta.

## 🅰️ Parte 1: las decisiones (`if`)

### Idea clave

Un programa muchas veces tiene que decidir entre dos caminos: *"si pasa esto, hago A; si no, hago B"*.

### Analogía de obra

En obra siempre hay reglas tipo "si... entonces...":

> *"Si llueve, no hormigonamos hoy."*
> *"Si el cliente paga al contado, descuento del 3%."*
> *"Si la temperatura es inferior a 5 ºC, no aplicamos pintura exterior."*

Estructura:

```
SI <una condición se cumple>
   HACER <esto>
SI NO
   HACER <esto otro>
```

### Las condiciones se evalúan a `true` o `false`

Recuerda: el tipo `boolean` solo guarda `true` o `false`. Una condición de un `if` **siempre se evalúa a uno de esos dos valores**.

### Operadores de comparación

| Signo | Significa | Ejemplo |
|---|---|---|
| `==` | es igual a | `edad == 18` |
| `!=` | NO es igual a | `nif != null` |
| `>` | mayor que | `precio > 1000` |
| `<` | menor que | `cantidad < 0` |
| `>=` | mayor o igual que | `iva >= 21` |
| `<=` | menor o igual que | `descuento <= 50` |

> ⚠️ **Cuidado importante**: el "igual" para comparar es `==` (doble). Un solo `=` significa "asignar valor". Es el error más típico del principiante en cualquier lenguaje.

### Operadores lógicos

| Signo | Significa | Ejemplo en cristiano |
|---|---|---|
| `&&` | Y (las dos cosas) | "lluvia Y viento" |
| `\|\|` | O (al menos una) | "lluvia O nieve" |
| `!` | NO (negar) | "NO llueve" |

### Sintaxis del `if` en Java

Básico:

```java
if (condición) {
    // pasos si la condición es verdadera
}
```

Con alternativa:

```java
if (condición) {
    // pasos si es verdadera
} else {
    // pasos si es falsa
}
```

Varias alternativas en cadena:

```java
if (condición1) {
    // pasos si se cumple condición1
} else if (condición2) {
    // pasos si no se cumple condición1 pero sí condición2
} else {
    // pasos si no se cumple ninguna
}
```

Las llaves `{ }` agrupan los pasos de cada caso, como párrafos.

### Ejemplo real (BENJAGEST)

Aplicar descuento solo a VIPs o facturas grandes:

```java
public BigDecimal aplicarDescuento(BigDecimal base, boolean esClienteVip) {
    BigDecimal umbral = new BigDecimal("1000");

    if (esClienteVip || base.compareTo(umbral) > 0) {
        BigDecimal descuento = base.multiply(new BigDecimal("0.05"));
        return base.subtract(descuento);
    } else {
        return base;
    }
}
```

> 📌 **Con BigDecimal**: como no se usa `>` directo, se llama `.compareTo(...)` y se mira si el resultado es `> 0`. Truco mental: `a.compareTo(b) > 0` = "a es mayor que b".

## 🅱️ Parte 2: los bucles (`for`)

### Idea clave

A veces un programa tiene que **hacer lo mismo varias veces** sobre una lista de elementos.

### Analogía de obra

> *"**Para cada** ventana del piso, pongo premarco."*
> *"**Para cada** factura del trimestre, calculo el IVA."*
> *"**Para cada** empleado, genero su nómina."*

La frase *"para cada"* es exactamente un bucle.

### El bucle más legible: `for-each`

```java
for (TipoDelElemento elemento : coleccion) {
    // pasos que se hacen UNA VEZ POR CADA ELEMENTO
}
```

Tradúcelo: *"para cada `elemento` dentro de `coleccion`, haz estos pasos."*

### Ejemplo real (BENJAGEST)

Sumar todas las líneas de una factura:

```java
public BigDecimal calcularBaseImponible(List<LineaFactura> lineas) {
    BigDecimal total = new BigDecimal("0");

    for (LineaFactura linea : lineas) {
        total = total.add(linea.getImporte());
    }

    return total;
}
```

Si la factura tiene 3 líneas (50, 75, 100):
- Vuelta 1: total = 0 + 50 = 50
- Vuelta 2: total = 50 + 75 = 125
- Vuelta 3: total = 125 + 100 = 225
- Devuelve 225.

**Sin bucle**, no podrías procesar facturas con número variable de líneas. Con bucle, **funciona con 1 línea o con 1000**.

### El bucle clásico con contador (lo verás también)

```java
for (int i = 0; i < 10; i++) {
    // se ejecuta 10 veces (i va de 0 a 9)
}
```

Lectura:
- `int i = 0` → contador `i` empieza en 0.
- `i < 10` → mientras `i` sea menor que 10, sigo.
- `i++` → al final de cada vuelta, sumo 1 a `i`.

**Para casi todo lo que vas a hacer, prefiere `for-each`** (más legible). El for clásico solo cuando de verdad necesites contar.

### Hay también `while`

Existe otro tipo de bucle que repite *"mientras se cumpla una condición"*. No te lo enseño hoy a fondo, basta saber que existe.

### Truco crítico: el `return` dentro del bucle

Si dentro de un bucle haces `return`, **el bucle se corta y la función termina inmediatamente**, sin revisar el resto de elementos.

Analogía: entras a una nave llena de palets buscando uno roto. **No tienes que revisar los 200** — en cuanto ves el primero roto, sales y avisas. Eso es `return` dentro de bucle. Es eficiente.

```java
public boolean tieneFacturasImpagadas(List<Factura> facturas) {
    for (Factura factura : facturas) {
        if (!factura.isPagada()) {
            return true;       // ← encuentra una impagada y se va
        }
    }
    return false;              // ← solo si terminó el bucle sin encontrar ninguna
}
```

## Resumen visual de operadores

```
┌──────────────────────────────────────────────────────────┐
│  Comparar:    ==  !=  >  <  >=  <=                      │
│  Combinar:    &&  (Y)   ||  (O)   !  (NO)               │
│  ⚠️  El "=" simple es ASIGNAR, no comparar               │
└──────────────────────────────────────────────────────────┘
```

## Lo que se lleva de la Lección 4

1. **`if` = decidir.** Si se cumple algo, hago A; si no, B.
2. **`==` compara, `=` asigna**. Es el error más típico.
3. **`!` significa NO**. Léelo en voz alta cada vez que lo veas — si te lo saltas, lees el código al revés.
4. **`for-each` = "para cada"**. Repetir lo mismo sobre una colección.
5. **`return` dentro de bucle** sale inmediatamente de la función. Útil para "buscar el primero que cumple".
6. Con variables + funciones + `if` + `for` ya tienes el 80% de lo que hace cualquier programa.

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
| **Parámetro** | Lo que la función necesita que le den |
| **Argumento** | El valor concreto que pasas cuando llamas a una función |
| **Valor de retorno** | Lo que la función te devuelve al terminar |
| **`void`** | Función que no devuelve nada |
| **Llamar** a una función | Usarla: escribir su nombre con paréntesis y argumentos |
| **Orquestador** | Función que solo llama a otras funciones más pequeñas |
| **DRY** | "No te repitas". Si escribes lo mismo dos veces, hazlo función |
| **camelCase** | Convención de nombres con mayúsculas tipo `fechaFactura` |
| **Condición** | Una pregunta que el programa evalúa a `true` o `false` |
| **`if` / `else` / `else if`** | Estructura de decisión: "si... entonces... si no..." |
| **`==`** | Comparar igualdad (NO es lo mismo que `=`) |
| **`!=`** | "Distinto de" |
| **`&&` / `\|\|` / `!`** | Y / O / NO (operadores lógicos) |
| **Bucle** | Repetir una acción varias veces |
| **`for-each`** | Bucle "para cada elemento de una colección" |
| **Visible / Invisible** | Lo que ve el usuario vs lo que hace el programa por debajo |
| **Punto y coma `;`** | Fin de frase en Java |
| **`String`** | Tipo de variable que guarda texto |
| **`int`** | Tipo de variable que guarda números enteros |
| **`BigDecimal`** | Tipo de variable para guardar dinero con precisión |
| **`LocalDate`** | Tipo de variable que guarda una fecha |
| **`boolean`** | Tipo de variable que guarda `true` o `false` |
| **`new BigDecimal(x)`** | Convertir un número (`int`, etc.) en BigDecimal para poder operarlo con otros BigDecimal |
| **`.multiply()` / `.divide()` / `.add()` / `.subtract()`** | Operaciones de BigDecimal (no se usan `*` `/` `+` `-` directos) |
| **`.compareTo()`** | Comparar dos BigDecimal (`a.compareTo(b) > 0` = a mayor que b) |

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

**Aprendizaje:** lo que escribí es **el guion del usuario** (caso de uso). El programa hace por debajo unas 200 instrucciones invisibles. Mi rol natural = describir lo que el usuario hace y espera. El programador (Pablo) traduce eso al guion invisible.

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

## Ejercicio 3 — Lección 3: identificar los 4 componentes de una función

Función analizada:

```java
public BigDecimal calcularImporteLinea(int cantidad, BigDecimal precioUnitario) {
    BigDecimal cantidadComoDecimal = new BigDecimal(cantidad);
    BigDecimal importe = cantidadComoDecimal.multiply(precioUnitario);
    return importe;
}
```

| Componente | Mi respuesta | Correcto |
|---|---|---|
| Nombre | `calcularImporteLinea` | ✅ |
| Parámetros | 2: `cantidad` (entero) y `precioUnitario` (dinero) | ✅ |
| Tipo que devuelve | `BigDecimal` (dinero) | ✅ (lo identifiqué al revisar) |
| Qué hace | Multiplica cantidad por precio | ✅ parcial |

**Lo que me faltó entender:** la primera línea (`new BigDecimal(cantidad)`) **convierte el `int` a `BigDecimal`** para poder multiplicarlo con `precioUnitario`. Java no deja multiplicar tipos distintos directamente, por eso esa conversión "aparentemente tonta" es necesaria. Lo veré muchas veces en código de facturación.

**Resultado: 4/4 con un asterisco.** Bien.

## Ejercicio 4 — Lección 4: traducir una función con `if` dentro de un `for`

Función analizada:

```java
public boolean tieneFacturasImpagadas(List<Factura> facturas) {
    for (Factura factura : facturas) {
        if (!factura.isPagada()) {
            return true;
        }
    }
    return false;
}
```

**Lo que escribí:** "para cada factura, si la factura está pagada, devuelve verdadero; si no, falso".

**Lo que se me escapó:** **el `!` cambia el sentido**. La condición real es `!factura.isPagada()` = "si NO está pagada". Mi lectura era la inversa.

**Lectura correcta:**

```
Recorro la lista. Si encuentro UNA factura sin pagar, contesto SÍ
y me voy (return dentro del bucle). Si llego al final sin encontrar
ninguna, contesto NO (return de fuera).
```

**Aprendizaje crítico:** **cuando veas un `!`, léelo en voz alta como "NO".** Si te lo saltas mentalmente, interpretas el código al revés. Y un truco más: **si el nombre de la función y tu traducción se contradicen, casi seguro te has saltado un `!` o confundido un `==` con un `!=`**.

**Resultado: 6,5/10. Ejercicio más difícil, aprendido en sangre.** Ya no se me olvida.

---

# Cómo seguir entrenando solo

Cuando tengas un rato y quieras practicar lo aprendido, abre uno de estos archivos del proyecto y léelos despacio:

```
backend-java/src/main/java/com/benjagest/backend/customer/CustomerResponse.java
backend-java/src/main/java/com/benjagest/backend/customer/CustomerController.java
backend-java/src/main/java/com/benjagest/backend/customer/CustomerService.java
backend-java/src/main/java/com/benjagest/backend/workspace/PinLoginRequest.java
backend-java/src/main/java/com/benjagest/backend/workspace/DashboardItem.java
```

Para cada archivo, intenta identificar:

- **Variables**: nombre, tipo, qué guarda.
- **Funciones**: nombre, parámetros, qué devuelve, qué hace por dentro.
- **Si hay algún `if`**: ¿qué condición evalúa? ¿hay un `!` que se pueda escapar?
- **Si hay algún `for`**: ¿sobre qué colección itera?

No tienes que entender el resto del archivo. Es como ir a una obra ajena y reconocer materiales y técnicas — todavía no sabes construirla, pero ya distingues lo que hay.

---

# Resumen ultra-condensado (para tener todo en una página)

**Lección 1 — Programa**
- Es un parte de obra para una máquina que no rellena huecos.
- Por cada acción visible, hay docenas de pasos invisibles.
- DRY: si lo haces dos veces, hazlo función.

**Lección 2 — Variables y tipos**
- Variable = caja con etiqueta (nombre) y contenido.
- Cada caja es de un tipo. Java es estricto.
- **3 reglas de oro**: tiene letras → `String`; es fecha → `LocalDate`; es dinero → `BigDecimal`.
- camelCase para nombres.

**Lección 3 — Funciones**
- 4 componentes: nombre, parámetros, cuerpo, valor de retorno.
- Una vez definida, se llama con `nombre(argumentos)`.
- `void` = no devuelve nada.
- Funciones llaman a otras (orquestación).
- Verbos para nombrar (`calcularIva`, no `iva`).

**Lección 4 — Decisiones y bucles**
- `if (condición) { ... } else { ... }` para decidir.
- Comparar con `==`, no `=`.
- `&&` = Y, `||` = O, `!` = NO. Léelos siempre.
- `for (Tipo x : coleccion) { ... }` para repetir.
- `return` dentro de bucle = salir inmediatamente.

---

*Última actualización: 2026-05-27. Lecciones recogidas: 1, 2, 3 y 4. Siguiente: Lección 5 (Listas y mapas).*
