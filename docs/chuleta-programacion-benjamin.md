# Chuleta personal de programación — Benjamin

> Apuntes para imprimir y repasar. Material vivo: se va ampliando con cada lección. Cada concepto está explicado en lenguaje de obra/albañilería para fijarlo mejor.
>
> **Lecciones recogidas hasta ahora:** 1, 2, 3, 4, 5, 6 y 7. **¡BASE COMPLETA!** 🎓
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
| 7 | Cómo se monta una app por capas | ✅ Hecha |

**🎓 Base completa.** Desde aquí, el aprendizaje sigue leyendo código real con la chuleta a mano. La teoría se ha acabado.

---

# Lección 1: ¿Qué es un programa?

## Idea clave

Un programa es **un parte de obra muy detallado para una máquina**.

La diferencia brutal con un parte de obra para personas:

> Un peón experimentado rellena los huecos del parte con su experiencia.
> **Un ordenador NO rellena huecos.** Si no le dices algo, no lo hace.
> Si te saltas un paso, se rompe. Si das algo por hecho, falla.

## El visible y el invisible

Por cada acción del usuario, el programa hace **decenas de pasos invisibles**.

**El usuario ve:** *clic en "Guardar factura".*

**El programa hace por debajo (21 pasos):** validar cliente, validar fecha, validar líneas, calcular base, calcular IVA, calcular total, pedir número de serie, guardar en BD, calcular hash Verifactu, firmar, registrar en AEAT, generar PDF, mandar email, registrar en auditoría, refrescar UI, etc.

## Reutilización (DRY)

> **Regla "DRY"** (*Don't Repeat Yourself*, "no te repitas"):
> Si te ves escribiendo lo mismo dos veces, **para**. Hazlo función. Reutilízalo.

**Analogía:** un buen albañil tiene rutinas dominadas y las aplica una y otra vez sin reinventar nada. Con el código: igual.

## Lo que se lleva de la Lección 1

1. Programar = escribir el parte invisible.
2. El visible es la punta del iceberg.
3. Cada paso se escribe UNA vez y se reutiliza (DRY).
4. La habilidad clave: **no dar nada por hecho**.

---

# Lección 2: Variables y tipos de datos

## ¿Qué es una variable?

Una caja con etiqueta donde guardas un dato.

```
┌──────────────────────────┐
│ ETIQUETA: sacosCemento   │   ← nombre de la variable
│ TIPO:     número entero  │   ← qué cabe dentro
│ CONTENIDO: 25            │   ← el valor actual
└──────────────────────────┘
```

## Los 7 tipos que más vas a ver en Java

| Tipo Java | Para qué sirve | Ejemplo |
|---|---|---|
| `int` | Números enteros (no muy grandes) | `5`, `1200`, `-3` |
| `long` | Números enteros grandes (ids) | `1234567890` |
| `double` | Decimales (medidas, porcentajes) | `3.14`, `21.5` |
| `BigDecimal` | **Decimales para DINERO** | `125.50` |
| `String` | Texto (con comillas) | `"Miguel López"` |
| `boolean` | `true` o `false` | `true`, `false` |
| `LocalDate` | Una fecha | `2026-05-27` |

## Cómo se declara una variable

Estructura: **TIPO + nombre + = + valor + ;**

```java
int sacosCemento = 25;
String clienteObra = "Miguel López";
boolean facturaPagada = false;
BigDecimal precioFactura = new BigDecimal("125.50");
LocalDate fechaFactura = LocalDate.of(2026, 5, 27);
```

## camelCase

✅ `fechaFactura`, `numeroDeCliente`, `precioUnitarioSinIva`
❌ `fecha factura`, `fecha-factura`, `FechaFactura`, `fecha_factura`

## Las 3 reglas de oro

```
┌──────────────────────────────────────────────────────────┐
│  ¿Parece número pero tiene letras?  →  String            │
│  ¿Es una fecha?                     →  LocalDate         │
│  ¿Es dinero?                        →  BigDecimal        │
└──────────────────────────────────────────────────────────┘
```

**Por qué BigDecimal y no double:** los `double` redondean mal con decimales. `0.1 + 0.2` puede dar `0.30000000000000004`. En facturación eso es inadmisible.

## Lo que se lleva de la Lección 2

1. Variable = caja con etiqueta y contenido.
2. Cada variable es de un tipo, y el tipo dice qué cabe.
3. Java es estricto: hay que declarar el tipo siempre.
4. camelCase para nombres.
5. Las 3 reglas de oro siempre.

---

# Lección 3: Funciones (bloques reutilizables)

## Idea clave

Una función = **un bloque de pasos con nombre, que recibe datos, hace algo y devuelve un resultado**.

## Los 4 componentes de toda función

| Componente | Pregunta que responde |
|---|---|
| **Nombre** | ¿Cómo se llama esta técnica? |
| **Parámetros** | ¿Qué necesito que me den? |
| **Cuerpo** | ¿Qué pasos sigo? |
| **Valor de retorno** | ¿Qué te entrego? |

## Anatomía de una función en Java

```java
public BigDecimal calcularIva(BigDecimal base, BigDecimal porcentaje) {
    BigDecimal iva = base.multiply(porcentaje).divide(new BigDecimal("100"));
    return iva;
}
```

- `public` → "esta técnica está abierta para que cualquiera la use".
- `BigDecimal` → lo que devuelve.
- `calcularIva` → el nombre.
- `(BigDecimal base, BigDecimal porcentaje)` → los parámetros.
- Cuerpo: el cálculo.
- `return` → devolver el resultado.

> ⚠️ **Con BigDecimal**: usa `.multiply()` y `.divide()`, NUNCA `*` ni `/`. Es por la precisión del dinero.

## Llamar a una función

```java
BigDecimal ivaFactura = calcularIva(new BigDecimal("125.00"), new BigDecimal("21"));
// ivaFactura = 26.25
```

## Funciones que NO devuelven nada: `void`

```java
public void guardarEnBaseDeDatos(Factura factura) {
    // pasos para guardar — sin return
}
```

## Funciones llaman a otras funciones (orquestación)

```java
public Factura crearFactura(DatosFactura datos) {
    validar(datos);                                      // llamada
    BigDecimal base = calcularBaseImponible(datos.lineas);
    BigDecimal iva = calcularIva(base, datos.porcentajeIva);
    // ...más llamadas...
    return factura;
}
```

## Nombres = verbos en infinitivo

✅ `calcularIva`, `guardarFactura`, `enviarEmail`, `validarNif`
❌ `iva`, `factura`, `email`, `nif`

**Truco potente: los nombres son chivatos del tipo de retorno**

| Prefijo | Tipo que casi siempre devuelve |
|---|---|
| `contar...` | `int` |
| `calcular...` con dinero | `BigDecimal` |
| `es...`, `tiene...`, `puede...` | `boolean` |
| `obtener...`, `buscar...` | el tipo de la cosa que buscas |

## Lo que se lleva de la Lección 3

1. Función = técnica con nombre, 4 componentes.
2. Se escribe una vez, se llama muchas (DRY).
3. Funciones llaman a otras (orquestación).
4. Nombres = VERBOS en infinitivo.
5. BigDecimal usa `.multiply()`, `.divide()`, `.add()`, `.subtract()`.

---

# Lección 4: Decisiones (`if`) y bucles (`for`)

## 🅰️ Las decisiones (`if`)

### Operadores de comparación

| Signo | Significa |
|---|---|
| `==` | es igual a |
| `!=` | NO es igual a |
| `>` | mayor que |
| `<` | menor que |
| `>=` | mayor o igual que |
| `<=` | menor o igual que |

> ⚠️ **CRÍTICO**: el "igual" para comparar es `==` (doble). Un solo `=` significa "asignar valor".

### Operadores lógicos

| Signo | Significa |
|---|---|
| `&&` | Y (las dos cosas) |
| `\|\|` | O (al menos una) |
| `!` | NO (negar) |

> ⚠️ **CRÍTICO**: cuando veas un `!`, **léelo en voz alta como "NO"**. Si te lo saltas mentalmente, lees el código al revés.

### Sintaxis del `if`

```java
if (condición) {
    // si verdadera
} else if (otraCondicion) {
    // si la primera falla y esta se cumple
} else {
    // si ninguna se cumple
}
```

### Comparar BigDecimal

Con BigDecimal no se usa `>` directo. Se usa `.compareTo()`:

```java
a.compareTo(b) > 0      // a es mayor que b
a.compareTo(b) < 0      // a es menor que b
a.compareTo(b) == 0     // a es igual a b
```

## 🅱️ Los bucles (`for`)

### `for-each` (el más legible)

```java
for (TipoDelElemento elemento : coleccion) {
    // pasos una vez por cada elemento
}
```

Tradúcelo: *"para cada `elemento` dentro de `coleccion`..."*.

### `for` clásico con contador

```java
for (int i = 0; i < 10; i++) {
    // se ejecuta 10 veces (i va de 0 a 9)
}
```

### `return` dentro de bucle

Si dentro de un bucle haces `return`, **el bucle se corta y la función termina**. Útil para "buscar el primero que cumple":

```java
public boolean tieneFacturasImpagadas(List<Factura> facturas) {
    for (Factura factura : facturas) {
        if (!factura.isPagada()) {
            return true;       // ← encuentra y se va
        }
    }
    return false;              // ← solo si terminó sin encontrar
}
```

## Lo que se lleva de la Lección 4

1. `if` = decidir.
2. `==` compara, `=` asigna.
3. `!` significa NO. **Léelo siempre en voz alta.**
4. `for-each` = "para cada".
5. `return` dentro de bucle = salir inmediatamente.

---

# Lección 5: Listas y mapas (colecciones)

## 🅰️ Las listas (`List`)

Fila ordenada de elementos. Índices desde **0** (el primero es el 0, no el 1).

### Sintaxis

```java
List<String> nombres = new ArrayList<>();
List<LineaFactura> lineas;
```

### Operaciones

| Operación | Para qué |
|---|---|
| `.add(elemento)` | Añadir al final |
| `.get(posición)` | Obtener el de esa posición (empieza en 0) |
| `.size()` | Cuántos elementos tiene |
| `.contains(elemento)` | ¿Está dentro? |
| `.remove(elemento)` | Quitar |
| `.isEmpty()` | ¿Está vacía? |

## 🅱️ Los mapas (`Map`)

Fichero llave → valor. Acceso directo por la clave.

### Sintaxis

```java
Map<String, BigDecimal> tiposIva = new HashMap<>();
```

### Operaciones

| Operación | Para qué |
|---|---|
| `.put(clave, valor)` | Meter o actualizar |
| `.get(clave)` | Obtener el valor de esa clave |
| `.containsKey(clave)` | ¿Existe esa clave? |
| `.size()` | Cuántas entradas |
| `.remove(clave)` | Quitar una entrada |

> ⚠️ **CRÍTICO**: `.get("CLAVE_QUE_NO_EXISTE")` devuelve `null` (vacío). Comprueba con `.containsKey()` si tienes duda.

## Lo que se lleva de la Lección 5

1. `List<Tipo>` = fila ordenada, índices desde 0.
2. `Map<K, V>` = fichero llave→valor.
3. Patrón común: **acumulador + for + if** ("de todas las cosas, súmame las que cumplen X").

---

# Lección 6: Objetos y clases

## La idea fundamental

- **Clase** = el **plano** (cómo es una "cosa" tipo).
- **Objeto** = una **instancia construida** con el plano.

## Anatomía de una clase

```java
public class Cliente {

    // 1. CAMPOS (los datos)
    private String id;
    private String nombre;
    private BigDecimal saldo;

    // 2. CONSTRUCTOR (cómo se crea)
    public Cliente(String id, String nombre) {
        this.id = id;
        this.nombre = nombre;
        this.saldo = BigDecimal.ZERO;
    }

    // 3. MÉTODOS (qué sabe hacer)
    public String getNombre() {
        return nombre;
    }

    public void pagar(BigDecimal cantidad) {
        this.saldo = this.saldo.subtract(cantidad);
    }
}
```

## `public` vs `private` (encapsulación)

| Palabra | Significa |
|---|---|
| `private` | Solo desde dentro de la clase (datos protegidos) |
| `public` | Cualquiera puede usarlo (interfaz) |

**Por qué**: los datos se esconden en `private` para que nadie pueda hacer `cliente.saldo = -999999;` desde fuera. Solo se modifican mediante métodos `public` controlados.

## El `this`

`this` = "este objeto concreto". Sirve para distinguir el campo del objeto del parámetro con el mismo nombre.

## Crear y usar un objeto

```java
Cliente c1 = new Cliente("c001", "Miguel López");
String nombre = c1.getNombre();           // "Miguel López"
c1.pagar(new BigDecimal("125.50"));        // baja el saldo de c1
```

## Getters y setters

- `getXxx()` → lee un campo (getter).
- `setXxx(valor)` → modifica un campo (setter).

## Lo que se lleva de la Lección 6

1. **Clase** = plano. **Objeto** = instancia.
2. 3 partes: campos, constructor, métodos.
3. `private` esconde, `public` expone (encapsulación).
4. `new Clase(...)` crea un objeto.
5. `objeto.metodo()` llama a algo sobre el objeto.
6. `this.x` = "el campo x de este objeto".

---

# Lección 7: Cómo se monta una app por capas

## La idea fundamental

Una app bien hecha **NO amontona** todo el código en un archivo gigante. Lo separa en **3 capas**, cada una con una responsabilidad clara.

## La analogía clave — LA TIENDA

```
            EL CLIENTE
                │
                │ "Quiero 200 ladrillos"
                ▼
       ┌────────────────────┐
       │  EL DEPENDIENTE    │  ← está en el mostrador, atiende al cliente
       │   (CONTROLLER)     │
       └────────┬───────────┘
                │ "Oye, el cliente quiere 200 ladrillos"
                ▼
       ┌────────────────────┐
       │  EL ALMACENISTA    │  ← organiza el pedido, mira stock,
       │     (SERVICE)      │     aplica descuentos, valida
       └────────┬───────────┘
                │ "Mozo, tráeme 200 ladrillos del pasillo 3"
                ▼
       ┌────────────────────┐
       │  EL MOZO           │  ← solo va a buscar cosas al almacén
       │   (REPOSITORY)     │     o las guarda
       └────────┬───────────┘
                │
                ▼
         EL ALMACÉN
       (la base de datos)
```

Y la respuesta sube por el mismo camino: mozo → almacenista → dependiente → cliente.

## Las 3 capas y sus responsabilidades

| Capa | Quién | Qué hace |
|---|---|---|
| **Controller** | El dependiente | Recibe peticiones HTTP del exterior y devuelve respuestas. **No sabe nada del almacén ni de las reglas internas.** |
| **Service** | El almacenista | Lleva la **lógica de negocio**: valida, calcula, aplica reglas. **No habla con el cliente ni con la BD directamente.** |
| **Repository** | El mozo | Habla con la **base de datos**: SELECT, INSERT, UPDATE, DELETE. **No piensa, solo lee y escribe.** |

## ¿Por qué se hace así?

1. **Cambias una capa sin tocar las demás.** Si mañana cambian de MariaDB a PostgreSQL, solo cambian los Repositories.
2. **Cada uno hace una cosa muy bien.** Especialistas, no chapuzas.
3. **Es más fácil de mantener entre varios programadores.** Tú trabajas en una capa, Pablo en otra, no os pisáis.

## Las anotaciones `@` (las "etiquetas")

Spring Boot reconoce el papel de cada clase por unas **etiquetas** llamadas anotaciones (precedidas por `@`):

| Anotación | Significa |
|---|---|
| `@RestController` | "Soy un dependiente: recibo HTTP, devuelvo respuestas." |
| `@Service` | "Soy un almacenista: lógica de negocio." |
| `@Repository` | "Soy un mozo: hablo con la base de datos." |
| `@GetMapping` | "Atiendo peticiones GET (leer datos)." |
| `@PostMapping` | "Atiendo peticiones POST (crear)." |
| `@PutMapping` | "Atiendo peticiones PUT (modificar)." |
| `@DeleteMapping` | "Atiendo peticiones DELETE (borrar)." |
| `@RequestMapping("/api/x")` | "Mi mostrador está en la URL `/api/x`." |

> **CRUD = Create, Read, Update, Delete.** Las 4 operaciones básicas que un dependiente puede atender.

## Los archivos de APOYO (además de las 3 capas)

Cuando abres una carpeta como `auth`, verás MÁS archivos. No te asustes — son ayudas:

| En la tienda | En el código | Para qué sirve |
|---|---|---|
| **Catálogo / ficha del producto** | **Entity** o **Model** (`User`, `Customer`, `Factura`) | Define qué ES esa cosa (sus campos), cómo es por dentro. Viaja entre las 3 capas. |
| **Albarán de entrada** (papel que llega de fuera) | **Request** DTO (`LoginRequest`, `CustomerCreateRequest`) | Lo que envía el cliente exterior. |
| **Albarán de salida** (papel que se le da al cliente) | **Response** DTO (`LoginResponse`, `CustomerResponse`) | Lo que la app devuelve al exterior. |
| **Libro de reglas de la tienda** | **Properties** (`JwtProperties`, `BenjagestProperties`) | Configuración cargada al arrancar (claves, URLs, tiempos). |
| **Cuadro eléctrico** | **Configuration** (`SecurityConfig`) | Cómo Spring monta la tienda al arrancar. |

## Patrón típico de una carpeta de módulo

Cuando abras una carpeta de BENJAGEST, casi siempre verás estos archivos:

```
nombremodulo/
  ├── NombreController.java       ← Capa 1 (dependiente)
  ├── NombreService.java          ← Capa 2 (almacenista)
  ├── NombreRepository.java       ← Capa 3 (mozo)
  ├── Nombre.java                  ← Entity (la "cosa")
  ├── NombreCreateRequest.java    ← DTO de entrada
  ├── NombreResponse.java          ← DTO de salida
  └── (a veces) NombreProperties.java o NombreConfig.java
```

**Primer ejercicio práctico:** cuando abras una carpeta, **identifica esos 5-6 archivos típicos**. Eso te da el "plano" del módulo. Solo después miras el código por dentro.

## El flujo completo de una petición

Cuando la UI hace clic en "Ver clientes":

```
1. La UI envía:  GET http://localhost:8080/api/customers

2. CustomerController (@RestController):
     - @GetMapping coincide con la petición
     - Llama a: service.list()

3. CustomerService (@Service):
     - Aplica filtros (empresa actual, permisos)
     - Llama a: repository.findAllByCompanyId(companyId)

4. CustomerRepository (@Repository):
     - SQL: SELECT * FROM customers WHERE company_id = ?
     - Devuelve lista de Customer (entities)

5. Service:
     - Convierte cada Customer → CustomerResponse (DTO)
     - Devuelve al Controller

6. Controller:
     - Devuelve la lista al cliente como JSON

7. UI:
     - Recibe la lista, la pinta en pantalla
```

## Lo que se lleva de la Lección 7

1. **Una app se separa en 3 capas**: Controller (dependiente), Service (almacenista), Repository (mozo).
2. **Cada capa solo hace su trabajo.** El controller no toca la BD. El repository no atiende HTTP.
3. **`@RestController` / `@Service` / `@Repository`** son las "etiquetas" que Spring lee para reconocer cada papel.
4. **CRUD** = Create (`@PostMapping`), Read (`@GetMapping`), Update (`@PutMapping`), Delete (`@DeleteMapping`).
5. **Además de las 3 capas, hay archivos de apoyo**: Entity (la ficha), DTOs (albaranes), Properties (reglas), Config (cuadro eléctrico).
6. **Cuando abras una carpeta**, busca primero los 5-6 archivos típicos. Te da el plano del módulo.

---

# Glosario de palabras que ya conoces

| Palabra | Significado en cristiano |
|---|---|
| **Programa** | Lista de instrucciones precisas para una máquina |
| **Variable** | Caja con etiqueta y contenido |
| **Tipo de dato** | Qué clase de cosa cabe en la caja |
| **Declarar** | Crear la caja con etiqueta y tipo |
| **Asignar** | Meter algo dentro (`= 25`) |
| **Función** / **Método** | Bloque de pasos con nombre, reutilizable |
| **Parámetro** | Lo que la función necesita que le den |
| **Argumento** | El valor concreto que pasas al llamar |
| **Valor de retorno** | Lo que la función te devuelve |
| **`void`** | Función que no devuelve nada |
| **Orquestador** | Función que solo llama a otras funciones |
| **DRY** | "No te repitas". Hazlo función. |
| **camelCase** | `fechaFactura`, mayúsculas tipo joroba de camello |
| **Condición** | Pregunta que se evalúa a `true` o `false` |
| **`if` / `else` / `else if`** | Estructura de decisión |
| **`==`** | Comparar igualdad (NO `=`) |
| **`!=`** | Distinto de |
| **`&&` / `\|\|` / `!`** | Y / O / NO |
| **Bucle** | Repetir una acción varias veces |
| **`for-each`** | "Para cada elemento de una colección" |
| **`String`** | Texto |
| **`int`** | Entero |
| **`BigDecimal`** | Dinero con precisión exacta |
| **`LocalDate`** | Fecha |
| **`boolean`** | `true` o `false` |
| **`.multiply()` / `.divide()` / `.add()` / `.subtract()`** | Operaciones de BigDecimal |
| **`.compareTo()`** | Comparar BigDecimal |
| **Colección** | Caja que guarda muchos elementos |
| **`List<Tipo>`** | Fila ordenada, índices desde 0 |
| **`ArrayList`** | Tipo concreto de lista más usado |
| **`Map<K, V>`** | Fichero llave→valor |
| **`HashMap`** | Tipo concreto de mapa más usado |
| **`Set`** | Colección sin duplicados |
| **`null`** | Vacío, ausencia de dato |
| **Clase** | Plano que define cómo es un tipo |
| **Objeto** / **Instancia** | Cosa concreta construida con el plano |
| **Campo** / **Atributo** | Dato dentro de un objeto |
| **Constructor** | Función especial que crea un objeto con `new` |
| **`this`** | "Este objeto concreto" |
| **`private`** | Solo desde dentro de la clase |
| **`public`** | Desde cualquier sitio |
| **Encapsulación** | Esconder datos (private), exponer métodos (public) |
| **Getter** / **Setter** | Métodos `get...` / `set...` para leer/escribir campos |
| **`new`** | Operador para construir un objeto |
| **Controller** | Capa 1: atiende HTTP (el dependiente) |
| **Service** | Capa 2: lógica de negocio (el almacenista) |
| **Repository** | Capa 3: habla con la BD (el mozo) |
| **Entity** / **Model** | Clase que representa una fila de la BD |
| **DTO** | Clase que viaja entre el exterior y el controller |
| **Request DTO** | Albarán de entrada (lo que envía el cliente) |
| **Response DTO** | Albarán de salida (lo que la app devuelve) |
| **Properties** | Configuración (claves, URLs, tiempos) |
| **Anotación** (`@`) | Etiqueta que Spring lee para reconocer el papel de una clase |
| **`@RestController`** | "Soy un controller HTTP" |
| **`@Service`** | "Soy un service de lógica" |
| **`@Repository`** | "Soy un repository de BD" |
| **`@GetMapping`** | "Atiendo GET (leer)" |
| **`@PostMapping`** | "Atiendo POST (crear)" |
| **`@PutMapping`** | "Atiendo PUT (modificar)" |
| **`@DeleteMapping`** | "Atiendo DELETE (borrar)" |
| **CRUD** | Create, Read, Update, Delete (las 4 operaciones básicas) |
| **JSON** | Formato de texto que se usa para transmitir datos por HTTP |
| **HTTP** | El "idioma" en el que el cliente y el backend se hablan |

---

# Ejercicios resueltos

## Ejercicio 1 — Lección 1

Parte de obra para crear una factura → escribí 10 pasos del USUARIO. Lección: lo mío es el caso de uso, el programa hace ~200 pasos invisibles por debajo.

## Ejercicio 2 — Lección 2

Tipos de los 10 campos de una factura. **9/10**. Único fallo: dije `String` para la fecha (debería ser `LocalDate`). Lección: *fecha → `LocalDate`, nunca `String`*.

## Ejercicio 3 — Lección 3

Los 4 componentes de `calcularImporteLinea`. **4/4 con asterisco.** Lección: `new BigDecimal(cantidad)` convierte `int → BigDecimal` para poder multiplicar (Java es estricto con los tipos).

## Ejercicio 4 — Lección 4

Traducir `tieneFacturasImpagadas`. **6,5/10 — lección aprendida en sangre.** Se me escapó el `!`. La regla:

> **Cuando veas un `!`, léelo en voz alta como "NO".**

## Ejercicio 5 — Lección 5

`sumarLineasGrandes` (acumulador con List + if). Aprendí el patrón **acumulador + for + if**: *"de todas las cosas, súmame las que cumplen una condición"*.

## Ejercicio 6 — Lección 5

`contarFacturasImpagadas`. **¡5/5!** Esta vez leí el `!` correctamente, identifiqué el contador `int`, el incremento, y el `return` después del bucle. Aprendí: *los nombres son chivatos del tipo de retorno* (`contar...` → `int`).

## Ejercicio 7 — Lección 6

Analizar la clase `LineaFactura`. **4/4** + pregunta 5 resuelta acompañado. Lo mejor: **recordé yo solo** la conversión `int → BigDecimal` del Ejercicio 3 sin que me la recordaran. Lección: *los métodos de un objeto usan los datos del propio objeto, no necesitan que se los pases como parámetros*.

## Ejercicio 8 — Lección 7

Síntesis del flujo de las 3 capas. Lo dije con mis palabras:

> *"Llega el cliente al frontend y hace una petición que va al controller donde recibe (get), el controller llama al service, el service al repository, y este a la base de datos, y luego a la inversa."*

**Perfecto.** Lección entendida al nivel necesario. Pregunta extra: en la carpeta `auth` hay más archivos además de las 3 capas — Entity (`User`), DTOs (Request/Response), Properties, Config. Son archivos de apoyo, las 3 capas siguen siendo el esqueleto.

---

# Cómo seguir entrenando solo

Cuando tengas un rato y quieras practicar lo aprendido, abre una carpeta del backend (por ejemplo `customer/` o `auth/`) e identifica:

1. **¿Cuál es el Controller?** (lleva `@RestController`)
2. **¿Cuál es el Service?** (lleva `@Service`)
3. **¿Cuál es el Repository?** (lleva `@Repository`)
4. **¿Cuál es la Entity?** (es el "tipo de cosa", sin `@Rest...` ni `@Service`)
5. **¿Cuáles son los DTOs?** (suelen acabar en `Request` o `Response`)
6. **¿Hay alguna Properties o Config?**

Cuando identifiques esos 5-6 archivos, **ya entiendes el plano del módulo** aunque no entiendas el código línea a línea.

Después, mira **dentro del Controller**: verás métodos con `@GetMapping`, `@PostMapping`, etc. Cada uno corresponde a una operación del CRUD. Cada uno llama al `service`. Y así sigues el hilo.

---

# Resumen ultra-condensado (todo en una página)

**Lección 1 — Programa**
- Parte de obra para una máquina que no rellena huecos.
- Visible vs invisible.
- DRY: si lo haces dos veces, hazlo función.

**Lección 2 — Variables y tipos**
- Variable = caja (nombre + tipo + contenido).
- 3 reglas de oro: letras → `String`; fecha → `LocalDate`; dinero → `BigDecimal`.
- camelCase para nombres.

**Lección 3 — Funciones**
- 4 componentes: nombre, parámetros, cuerpo, valor de retorno.
- `void` = no devuelve nada.
- Verbos para nombrar.
- Nombres son chivatos: `contar...` → `int`, `es...` → `boolean`.

**Lección 4 — Decisiones y bucles**
- `if (condición) { ... } else { ... }`.
- `==` compara, `=` asigna.
- `&&` Y, `||` O, `!` NO. **`!` siempre en voz alta**.
- `for (Tipo x : coleccion) { ... }` para repetir.
- `return` dentro de bucle = salir inmediatamente.

**Lección 5 — Colecciones**
- `List<Tipo>` = fila, índices desde 0. `.add()`, `.get(i)`, `.size()`.
- `Map<K, V>` = fichero llave→valor. `.put()`, `.get(k)`, `.containsKey()`.
- ⚠️ `Map.get()` devuelve `null` si la clave no existe.
- Patrón típico: **acumulador + for + if**.

**Lección 6 — Objetos y clases**
- Clase = plano. Objeto = casa construida con el plano.
- 3 partes: campos, constructor, métodos.
- `private` esconde, `public` expone (encapsulación).
- `new ClaseConMayuscula(...)` crea objeto. `objeto.metodo()` llama.
- `this` = "este objeto concreto".

**Lección 7 — App por capas**
- **Controller** (dependiente) → **Service** (almacenista) → **Repository** (mozo) → **BD**.
- Cada capa solo hace su trabajo. Nadie se salta capas.
- Anotaciones: `@RestController`, `@Service`, `@Repository`.
- HTTP: `@GetMapping` (leer), `@PostMapping` (crear), `@PutMapping` (modificar), `@DeleteMapping` (borrar). **CRUD**.
- Apoyo: Entity (la cosa), DTOs (albaranes), Properties (reglas), Config (cuadro eléctrico).
- Al abrir una carpeta, **busca los 5-6 archivos típicos primero**, el detalle después.

---

*Última actualización: 2026-05-31. **Las 7 lecciones de la base están completas.** Desde aquí: práctica leyendo código real con la chuleta a mano.*
