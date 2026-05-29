# Chuleta personal de programación — Benjamin

> Apuntes para imprimir y repasar. Material vivo: se va ampliando con cada lección. Cada concepto está explicado en lenguaje de obra/albañilería para fijarlo mejor.
>
> **Lecciones recogidas hasta ahora:** 1, 2, 3, 4, 5 y 6.
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
| 5 | Listas y mapas (colecciones) | ✅ Hecha |
| 6 | Objetos y clases | ✅ Hecha |
| 7 | Cómo se monta una app por capas | ⏳ Última |

Al terminar la 7, podrás abrir cualquier archivo de BENJAGEST y entender el **80%** de lo que hace.

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
> La palabra `public` significa "esta técnica está abierta para que cualquiera la use". A fondo en la Lección 6.

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

## Truco potente: los nombres son chivatos del tipo de retorno

| Prefijo del nombre | Tipo que casi siempre devuelve |
|---|---|
| `contar...` | `int` (cuentas cosas enteras) |
| `calcular...` con dinero | `BigDecimal` |
| `es...`, `tiene...`, `puede...` | `boolean` |
| `obtener...`, `buscar...` | el tipo de la cosa que buscas |
| `validar...` | a veces `boolean`, a veces `void` (si lanza excepción) |

Esto te permite **leer código deprisa** sin mirar línea a línea.

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

**Para casi todo lo que vas a hacer, prefiere `for-each`** (más legible).

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
│  Comparar:    ==  !=  >  <  >=  <=                       │
│  Combinar:    &&  (Y)   ||  (O)   !  (NO)                │
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

# Lección 5: Listas y mapas (las colecciones)

## Idea fundamental

Una **colección** es una caja que guarda **muchos elementos**. Las dos más usadas:

- **`List`** — fila ordenada de cosas (puede haber repetidos), acceso por posición.
- **`Map`** — fichero llave → valor, acceso directo por la clave.

## 🅰️ Las listas (`List`)

### La idea

- **Muchas cosas** del mismo tipo metidas en una fila.
- El **orden importa** (hay un primero, un segundo…).
- Puede haber **repetidos**.
- Se puede **añadir, quitar, recorrer**.

### Analogía de obra

Un **palet de ladrillos** o una **caja de tornillos**: están en orden, puedes contar, recorrer uno a uno, hay repetidos, son del mismo tipo.

### ⚠️ Detalle crítico: cuentan desde 0

```
Posición 0 → el primero
Posición 1 → el segundo
Posición 2 → el tercero
Posición 4 → el quinto (no el cuarto)
```

**En programación, "el primero" se dice "el cero"**. Es responsable del 30% de los bugs de principiante.

### Sintaxis

```java
List<String> nombresClientes;         // una lista DE textos
List<BigDecimal> importesFactura;     // una lista DE cantidades de dinero
List<LineaFactura> lineas;            // una lista DE líneas de factura
```

Los `< >` dicen **de qué tipo son los elementos** que contiene la lista.

### Crear una lista vacía

```java
List<String> nombresClientes = new ArrayList<>();
```

`ArrayList` es el tipo concreto de lista más usado. *"Crea una lista vacía nueva, lista para ir metiendo cosas"*.

### Operaciones

| Operación | Para qué |
|---|---|
| `.add(elemento)` | Añadir al final |
| `.get(posición)` | Obtener el de la posición (empieza en 0) |
| `.size()` | Cuántos elementos tiene |
| `.contains(elemento)` | ¿Está dentro? (true/false) |
| `.remove(elemento)` | Quitar el elemento (si está) |
| `.isEmpty()` | ¿Está vacía? |

Ejemplo:

```java
nombresClientes.add("Miguel López");
nombresClientes.add("Construcciones García");

String primero = nombresClientes.get(0);      // "Miguel López"
int cuantos = nombresClientes.size();          // 2
boolean estaMiguel = nombresClientes.contains("Miguel López");  // true
```

### Recorrer una lista

Esto **ya lo sabes** (Lección 4):

```java
for (String nombre : nombresClientes) {
    System.out.println(nombre);
}
```

## 🅱️ Los mapas (`Map`)

### La idea

Un **fichero**: por cada **llave** (clave), te devuelve el **valor** asociado. Como una agenda telefónica.

```
"GENERAL"       → 21
"REDUCIDO"      → 10
"SUPERREDUCIDO" → 4
```

**Acceso instantáneo por la llave**. No recorres todo: pides la clave, recibes el valor.

### Analogía de obra

El **fichero del almacén**: por código del material, te devuelve su ficha (precio, dimensiones, stock).

### Sintaxis

Los mapas tienen **dos tipos**: la clave y el valor.

```java
Map<String, BigDecimal> tiposIva;            // llave texto, valor dinero
Map<Long, Cliente> clientesPorId;            // llave número, valor cliente
Map<String, Integer> stockMaterial;          // llave texto, valor entero
```

Léelo: *"Map DE String A BigDecimal"*.

### Crear un mapa vacío

```java
Map<String, BigDecimal> tiposIva = new HashMap<>();
```

### Operaciones

| Operación | Para qué |
|---|---|
| `.put(clave, valor)` | Meter o actualizar el valor de esa clave |
| `.get(clave)` | Obtener el valor de esa clave |
| `.containsKey(clave)` | ¿Existe esa clave? |
| `.size()` | Cuántas entradas tiene |
| `.remove(clave)` | Quitar una entrada |
| `.isEmpty()` | ¿Está vacío? |

Ejemplo:

```java
tiposIva.put("GENERAL", new BigDecimal("21"));
tiposIva.put("REDUCIDO", new BigDecimal("10"));

BigDecimal pct = tiposIva.get("GENERAL");          // 21
boolean tieneCero = tiposIva.containsKey("CERO");  // false
```

> ⚠️ **Si pides `.get("CLAVE_QUE_NO_EXISTE")` el mapa devuelve `null` (vacío)**. Si después usas ese resultado, el programa explota. Por eso muchas veces se comprueba antes con `.containsKey(...)`.

### Recorrer un mapa

```java
for (String clave : tiposIva.keySet()) {
    BigDecimal porcentaje = tiposIva.get(clave);
    System.out.println(clave + " → " + porcentaje);
}
```

## 🅲 Mención: `Set`

Existe una tercera colección, **`Set`**: como una `List` pero **sin duplicados**. Cuando la veas, ya sabes qué es.

## La gran idea de la Lección 5

> **`List` para orden, `Map` para búsqueda por clave.**
>
> - ¿Te interesa **el orden**? → `List`.
> - ¿Te interesa **encontrar algo por nombre/clave**? → `Map`.

## Resumen visual

```
┌─────────────────────────────────────────────────────────────────┐
│  LIST<Tipo>     →   fila ordenada, índices desde 0              │
│                     .add()  .get(i)  .size()  .contains()        │
│                                                                 │
│  MAP<Clave,Val> →   fichero llave→valor                          │
│                     .put()  .get(k)  .containsKey()  .size()     │
│                                                                 │
│  SET<Tipo>      →   sin duplicados (mención)                     │
└─────────────────────────────────────────────────────────────────┘
```

## Lo que se lleva de la Lección 5

1. Una **colección** es una caja que guarda muchos elementos.
2. **`List`** = fila ordenada, índice desde **0**, hay repetidos.
3. **`Map`** = fichero llave → valor, acceso directo por la clave.
4. La sintaxis `<...>` dice de qué tipo son los elementos.
5. `ArrayList` y `HashMap` son los tipos concretos más usados.
6. ⚠️ `.get(clave)` de Map devuelve `null` si la clave no existe — siempre comprueba con `containsKey()` si dudas.

---

# Lección 6: Objetos y clases

## La idea fundamental

Hasta ahora cada variable guardaba **un dato suelto**. Pero las cosas del mundo real no son datos sueltos — son cosas **con muchos datos juntos y comportamientos asociados**.

Para representar "cosas complejas", Java te deja **crear tus propios tipos**:

- **Clase** = el **plano** (la plantilla que define cómo es algo).
- **Objeto** = una **instancia concreta** construida con ese plano.

## Analogía de obra

```
PLANO "VIVIENDA UNIFAMILIAR TIPO A" (= CLASE)
─────────────────────────────────────────────
Define que la vivienda tiene:
  - dirección (texto)
  - número de habitaciones (entero)
  - metros cuadrados (decimal)
  - propietario (texto)

Y que se le pueden hacer cosas como:
  - cambiar el propietario
  - calcular el precio según m²
```

```
CASAS CONCRETAS CONSTRUIDAS CON ESE PLANO (= OBJETOS)

  Casa #1            Casa #2            Casa #3
  Calle Mayor 5      Plaza Sol 12       Av. Mar 33
  4 hab, 120 m²      3 hab, 80 m²       5 hab, 180 m²
  Miguel López       Ana García         Pedro Pérez
```

**Mismo plano, datos distintos.** En BENJAGEST: `Cliente` es la clase, Miguel López y Construcciones García son **objetos** de esa clase.

## El "aha!" que se te ha aclarado

`BigDecimal`, `LocalDate`, `ArrayList`… **son todas clases que Java te da hechas**. Cuando escribes `new BigDecimal(...)` estás creando un **objeto** de la clase `BigDecimal`. Llevas creando objetos desde la Lección 2 sin saberlo.

> **Cada vez que ves `new TipoConMayuscula(...)`, eso es construir un objeto nuevo de esa clase.**

## Anatomía de una clase

Una clase tiene **3 partes**:

```java
public class Cliente {

    // 1. CAMPOS (los datos)
    private String id;
    private String nombre;
    private String nif;
    private BigDecimal saldo;

    // 2. CONSTRUCTOR (cómo se crea un objeto nuevo)
    public Cliente(String id, String nombre, String nif) {
        this.id = id;
        this.nombre = nombre;
        this.nif = nif;
        this.saldo = BigDecimal.ZERO;
    }

    // 3. MÉTODOS (qué sabe hacer el objeto)
    public String getNombre() {
        return nombre;
    }

    public void pagar(BigDecimal cantidad) {
        this.saldo = this.saldo.subtract(cantidad);
    }
}
```

### Parte 1: los campos (los datos)

```java
private String nombre;
private BigDecimal saldo;
```

Son como variables, pero **viven dentro del objeto**. Cada objeto tendrá los suyos.

### `public` vs `private`: la encapsulación

| Palabra | Significa | En obra |
|---|---|---|
| `private` | Solo se toca **desde dentro** de la clase | La fontanería dentro del tabique. Nadie de fuera la toca. |
| `public` | Cualquiera **desde fuera** lo puede usar | El grifo. La cara visible. Lo que está pensado para que otros usen. |

**Por qué se hace así:** si dejas los datos en `public`, cualquiera puede hacer `cliente.saldo = -999999;` y romperte el sistema. En `private`, solo se modifican a través de métodos `public` que controlan qué se puede hacer. Esto se llama **encapsulación**.

### Parte 2: el constructor

```java
public Cliente(String id, String nombre, String nif) {
    this.id = id;
    this.nombre = nombre;
    this.nif = nif;
    this.saldo = BigDecimal.ZERO;
}
```

Es **una función especial** que se ejecuta cuando creas un objeto con `new`. Tres cosas:

1. **Se llama exactamente igual que la clase** (`Cliente`).
2. **NO tiene tipo de retorno** (ni siquiera `void`).
3. **Recibe los datos iniciales** del objeto.

### El `this`

```java
this.id = id;
```

**`this` quiere decir "este objeto"**, el que se está construyendo. Sin `this.`, Java no sabría a cuál `id` te refieres (al campo o al parámetro).

Tradúcelo: *"el campo `id` de ESTE objeto concreto lo igualo al parámetro `id` que me han dado"*.

### Parte 3: los métodos

```java
public String getNombre() {
    return nombre;
}

public void pagar(BigDecimal cantidad) {
    this.saldo = this.saldo.subtract(cantidad);
}
```

Son **funciones que pertenecen a la clase**. Por convención:

- Empieza por `get...` → **getter** (lee un campo).
- Empieza por `set...` → **setter** (modifica un campo).

## Crear y usar un objeto

```java
Cliente c1 = new Cliente("c001", "Miguel López", "12345678A");
Cliente c2 = new Cliente("c002", "Construcciones García SL", "B87654321");

String nombre = c1.getNombre();           // "Miguel López"
c1.pagar(new BigDecimal("125.50"));        // a Miguel se le baja el saldo
```

Cada `new Cliente(...)` **construye una casa nueva** con el plano. **`c1.pagar(...)` no afecta a `c2`** — cada uno tiene sus propios datos.

## Diferencia con funciones sueltas

| Función suelta (Lección 3) | Método de objeto (Lección 6) |
|---|---|
| `calcularImporteLinea(cantidad, precio)` | `linea.getImporte()` |
| Le das **todos los datos** como parámetros | El objeto **ya tiene los datos dentro** |

Los objetos son útiles porque **agrupan datos relacionados + el comportamiento que opera sobre ellos**.

## Resumen visual

```
┌────────────────────────────────────────────────────────────────┐
│  CLASE = el PLANO (define cómo es algo)                        │
│  OBJETO = una INSTANCIA construida con el plano                │
│                                                                │
│  public class Cliente {                                        │
│    private String nombre;          ← campo (dato)              │
│    public Cliente(String n) {...}  ← constructor               │
│    public String getNombre() {...} ← método (acción)           │
│  }                                                             │
│                                                                │
│  Cliente c = new Cliente("Miguel");  ← creas un objeto         │
│  c.getNombre();                       ← le pides algo          │
│                                                                │
│  private = solo desde dentro (datos protegidos)                │
│  public  = desde cualquier sitio (interfaz pública)            │
│  this    = "este objeto concreto"                              │
└────────────────────────────────────────────────────────────────┘
```

## Lo que se lleva de la Lección 6

1. **Una clase es un plano**, un objeto es **una instancia construida con el plano**.
2. Las clases tienen **3 partes**: campos (datos), constructor (cómo se crea), métodos (qué sabe hacer).
3. **`private`** esconde los datos, **`public`** expone la interfaz. A eso se le llama **encapsulación**.
4. **`new TipoConMayuscula(...)`** crea un objeto nuevo. Lo hacías ya con `BigDecimal`, `LocalDate`, `ArrayList`.
5. **`objeto.metodo()`** llama a un método sobre un objeto concreto.
6. **`this.x`** quiere decir *"el campo `x` de este objeto"*.
7. **Getters y setters** son la convención para leer y escribir datos protegidos.

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
| **Colección** | Caja que guarda muchos elementos |
| **`List<Tipo>`** | Fila ordenada de elementos, índices desde 0 |
| **`ArrayList`** | Tipo concreto de lista más usado |
| **Índice** | La posición en una lista. Empieza en 0. |
| **`Map<K, V>`** | Fichero llave→valor, acceso directo por la clave |
| **`HashMap`** | Tipo concreto de mapa más usado |
| **`Set`** | Colección sin duplicados |
| **`null`** | Vacío, ausencia de dato. Devuelto por `Map.get()` si la clave no existe. |
| **Clase** | Plano/plantilla que define cómo es un tipo |
| **Objeto** / **Instancia** | Una "cosa" concreta construida con el plano de una clase |
| **Campo** / **Atributo** | Dato que tiene cada objeto (declarado dentro de la clase) |
| **Constructor** | Función especial que se ejecuta al crear un objeto con `new` |
| **`this`** | "Este objeto concreto" (el que se está manipulando) |
| **`private`** | Visible solo desde dentro de la clase |
| **`public`** | Visible desde cualquier sitio |
| **Encapsulación** | Esconder los datos (private) y exponer solo el comportamiento (public) |
| **Getter** | Método que empieza por `get...` y devuelve un campo |
| **Setter** | Método que empieza por `set...` y modifica un campo |
| **`new`** | Operador para construir un objeto nuevo de una clase |

---

# Ejercicios resueltos

## Ejercicio 1 — Lección 1: parte de obra para crear una factura

**Lo que escribí (perspectiva del usuario):**

```
1. Enciende la app
2. Click en "Facturas" → "Nueva"
3. Selecciona cliente Miguel
4. Selecciona fecha hoy
5. Escribe concepto, unidades, precio, IVA
6. Click en "Guardar factura"
```

**Aprendizaje:** lo que escribí es **el guion del usuario** (caso de uso). El programa hace por debajo ~200 instrucciones invisibles. Mi rol natural = describir lo que el usuario hace y espera.

## Ejercicio 2 — Lección 2: tipos de los campos de una factura

**9/10 con razonamiento correcto.** Único fallo: dije `String` para la fecha. Debería ser `LocalDate`. Regla aprendida: *si es fecha, `LocalDate`, nunca `String`*.

## Ejercicio 3 — Lección 3: los 4 componentes de `calcularImporteLinea`

**4/4 con asterisco.** Identifiqué nombre, parámetros, tipo de retorno y la operación. Me faltó entender que `new BigDecimal(cantidad)` **convierte el `int` a `BigDecimal`** para poder multiplicar (Java es estricto con los tipos). Lección aprendida: cuando veas esa conversión, no es decoración — es conversión obligatoria.

## Ejercicio 4 — Lección 4: traducir `tieneFacturasImpagadas`

**6,5/10 — lección aprendida en sangre.** Se me escapó el `!` y leí la condición al revés. La regla:

> **Cuando veas un `!`, léelo en voz alta como "NO".** Si te lo saltas, interpretas el código al revés.

Truco extra: **si el nombre de la función y mi traducción se contradicen, casi seguro me he saltado un `!` o confundido `==` con `!=`**.

## Ejercicio 5 — Lección 5: `sumarLineasGrandes` (acumulador con List + if)

Función:

```java
public BigDecimal sumarLineasGrandes(List<LineaFactura> lineas, BigDecimal umbral) {
    BigDecimal total = new BigDecimal("0");
    for (LineaFactura linea : lineas) {
        if (linea.getImporte().compareTo(umbral) > 0) {
            total = total.add(linea.getImporte());
        }
    }
    return total;
}
```

**Acerté:** nombre, tipo de retorno, parámetro `umbral`, que `total` empieza en 0 BigDecimal.

**Lo que se me escapó:**
- Un parámetro (la `List<LineaFactura>`).
- Leí mal `compareTo(umbral) > 0` como "mayor que 0" — en realidad es "**mayor que `umbral`**".
- Me faltó el paso de **acumular** dentro del if (`total = total.add(...)`).

**Lección aprendida:** el patrón **acumulador + for + if** es el más común en código de facturación: *"de todas las cosas, súmame solo las que cumplen una condición"*. Cuando lo reconozca, ya sé qué está pasando.

**Traza con ejemplo:** facturas de 50€, 150€, 30€, 200€ con umbral 100€:

| Vuelta | Importe | ¿>100? | total |
|---|---|---|---|
| 1 | 50 | NO | 0 |
| 2 | 150 | SÍ | 150 |
| 3 | 30 | NO | 150 |
| 4 | 200 | SÍ | 350 |

Devuelve **350€**.

## Ejercicio 6 — Lección 5: `contarFacturasImpagadas` (acumulador con contador)

Función:

```java
public int contarFacturasImpagadas(List<Factura> facturas) {
    int contador = 0;
    for (Factura factura : facturas) {
        if (!factura.isPagada()) {
            contador = contador + 1;
        }
    }
    return contador;
}
```

**¡¡¡5/5!!!** Identifiqué nombre, parámetro `List<Factura>`, contador `int` que empieza en 0, leí bien el `!` ("si NO está pagada"), entendí el incremento (`contador + 1`) y el `return` después del bucle.

**Lección aprendida (estratégica):** los **nombres son chivatos del tipo de retorno**:

| Prefijo | Tipo que casi siempre devuelve |
|---|---|
| `contar...` | `int` |
| `calcular...` con dinero | `BigDecimal` |
| `es...`, `tiene...`, `puede...` | `boolean` |
| `obtener...`, `buscar...` | el tipo de la cosa que buscas |

## Ejercicio 7 — Lección 6: analizar la clase `LineaFactura`

Clase:

```java
public class LineaFactura {
    private String concepto;
    private int cantidad;
    private BigDecimal precioUnitario;

    public LineaFactura(String concepto, int cantidad, BigDecimal precioUnitario) {
        this.concepto = concepto;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
    }

    public BigDecimal getImporte() {
        return precioUnitario.multiply(new BigDecimal(cantidad));
    }

    public String getConcepto() {
        return concepto;
    }
}
```

**4/4 en las preguntas que respondí:**
- Clase: `LineaFactura`.
- 3 campos: `concepto` (String), `cantidad` (int), `precioUnitario` (BigDecimal).
- Constructor: rellena los 3 campos del nuevo objeto.
- Métodos: `getImporte()` multiplica precio × cantidad (convirtiendo `int` a `BigDecimal` primero); `getConcepto()` devuelve el concepto.

**Lo que me alegró mucho:** **recordé yo solo, sin pista**, que `new BigDecimal(cantidad)` es la conversión `int → BigDecimal` del Ejercicio 3. Eso es **conectar conceptos entre lecciones**, no memorizar.

**Pregunta 5 (resuelta acompañado):**

```java
LineaFactura linea = new LineaFactura("Reparación", 5, new BigDecimal("25.00"));
BigDecimal importe = linea.getImporte();
```

Paso a paso:
1. El constructor rellena: `concepto="Reparación"`, `cantidad=5`, `precioUnitario=25.00`.
2. Al llamar `linea.getImporte()`, el método accede a los campos del propio objeto.
3. Convierte `cantidad` (5, int) a `BigDecimal(5)`.
4. Multiplica `25.00 × 5 = 125.00`.
5. Devuelve `125.00`.

**`importe` vale `125.00 €`.**

**Lección estratégica:** los **métodos de un objeto usan los datos del propio objeto**, no necesitan que se los pases como parámetros. Por eso `getImporte()` no recibe nada — usa el `precioUnitario` y `cantidad` que el objeto ya tiene dentro. Diferencia clave con una función suelta:

| Función suelta | Método de objeto |
|---|---|
| `calcularImporteLinea(5, 25.00)` | `linea.getImporte()` |
| Le pasas TODOS los datos | El objeto YA tiene los datos |

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

Para cada archivo, identifica:

- **¿Es una CLASE?** ¿Cómo se llama?
- **Campos** (`private` arriba): nombre, tipo, qué guarda.
- **Constructor** (función con el mismo nombre que la clase): qué parámetros recibe.
- **Métodos**: nombre, parámetros, tipo de retorno, qué hace. ¿Es getter? ¿Setter? ¿Acción?
- **Si hay `if`**: condición, ¿hay un `!`?
- **Si hay `for`**: sobre qué colección itera.

Ya tienes el vocabulario para reconocer todo lo que ves.

---

# Resumen ultra-condensado (para tener todo en una página)

**Lección 1 — Programa**
- Parte de obra para una máquina que no rellena huecos.
- Visible vs invisible.
- DRY: si lo haces dos veces, hazlo función.

**Lección 2 — Variables y tipos**
- Variable = caja con etiqueta (nombre) y contenido.
- Java es estricto con tipos.
- **3 reglas de oro**: tiene letras → `String`; es fecha → `LocalDate`; es dinero → `BigDecimal`.
- camelCase para nombres.

**Lección 3 — Funciones**
- 4 componentes: nombre, parámetros, cuerpo, valor de retorno.
- Una vez definida, se llama con `nombre(argumentos)`.
- `void` = no devuelve nada.
- Verbos para nombrar (`calcularIva`, no `iva`).
- Los nombres son chivatos del tipo: `contar...` → `int`, `es...` → `boolean`.

**Lección 4 — Decisiones y bucles**
- `if (condición) { ... } else { ... }` para decidir.
- Comparar con `==`, no `=`. NUNCA olvides el doble igual.
- `&&` = Y, `||` = O, `!` = NO. **Lee siempre el `!` en voz alta**.
- `for (Tipo x : coleccion) { ... }` para repetir.
- `return` dentro de bucle = salir inmediatamente.

**Lección 5 — Colecciones**
- **`List<Tipo>`**: fila ordenada, índices desde **0**. `.add()`, `.get(i)`, `.size()`.
- **`Map<K, V>`**: fichero llave→valor. `.put()`, `.get(k)`, `.containsKey()`.
- ⚠️ `Map.get()` devuelve `null` si la clave no existe.
- Patrón típico: **acumulador + for + if** ("de todas las cosas, súmame las que cumplen X").

**Lección 6 — Objetos y clases**
- **Clase** = plano. **Objeto** = casa construida con el plano.
- Una clase tiene campos (datos), constructor (cómo se crea), métodos (qué hace).
- **`private` = oculto**, **`public` = expuesto** → encapsulación.
- **`new ClaseConMayuscula(...)`** crea un objeto.
- **`objeto.metodo()`** llama a algo sobre el objeto.
- **`this`** = "este objeto concreto".
- Los métodos del objeto usan los datos del propio objeto, no parámetros.

---

*Última actualización: 2026-05-27. Lecciones recogidas: 1, 2, 3, 4, 5 y 6. Siguiente y última: Lección 7 (cómo se monta una app por capas).*
