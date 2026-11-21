# SUPERV — Qué vigila la app, qué falla en silencio y dónde poner la vigilancia

> **Estado: ANÁLISIS — ✅ PRIMER BLOQUE IMPLEMENTADO el mismo día.** Benjamin eligió los cuatro puntos de esfuerzo bajo de §5.2 (backup, contratos, BOE y actualizador) y están hechos: tablas `job_runs` + `job_supervision` (V184), avisos en la transición a fallo, por ausencia y de recuperación, y la pantalla Configuración → Estado del sistema. **Decisiones 2, 3 y 4 de §6 resueltas así**: avisos a la campana (en estos cuatro casos la app está corriendo, así que llega); 2 días sin éxito para las diarias y 10 para el backup semanal; y el arreglo de certificados **no** se llevó a los otros clientes HTTP — sigue pendiente, AEAT el primero. **Hallazgo extra al implementar**: el actualizador devolvía lo mismo para "estás al día" que para "no se pudo preguntar", así que con la red caída te decía que tenías la última versión. Arreglado.
>
> Escrito el 2026-11-21 a
> petición de Benjamin: *"lo vamos a montar, pero no antes de analizar la app
> y ver en qué puntos lo vamos a poner a que supervise"*.
>
> Origen: el 2026-11-20 se descubrió que los avisos del BOE llevaban **34 días
> sin llegar** (del 18-oct al 20-nov). No fallaba la lógica: fallaba la
> conexión, el error se escribía en un fichero de log que nadie mira, y el
> servicio seguía como si nada. Benjamin se quejaba del ruido de unos avisos
> que **ya no se producían**.
>
> La pregunta de este documento no es *"¿cómo arreglamos el BOE?"* (eso ya está
> hecho, v0.1.76) sino **"¿qué más puede estar roto ahora mismo sin que lo
> sepamos?"**.

---

## 1. Resumen en tres líneas

1. La app tiene **7 tareas automáticas**. Solo **una** avisa cuando falla.
2. Llama a **5 servicios externos**. Solo **uno** tiene el arreglo de
   certificados de ayer; los otros seis clientes HTTP siguen expuestos al mismo
   fallo.
3. Ya existe en el proyecto **todo lo necesario** para arreglarlo: la campana de
   avisos, el patrón de "avisar solo al pasar a fallo" y una tabla de histórico
   de ejecuciones. No hay que inventar nada, hay que generalizar lo que ya hay.

---

## 2. Las 7 tareas automáticas

| Tarea | Cuándo corre | Qué pasa hoy si falla | Riesgo |
|---|---|---|---|
| **Gastos/ventas recurrentes**<br>`RecurringTaskScheduler` | 06:10 + al arrancar | **AVISA a la campana**, solo en la transición a fallo. Guarda cada ejecución en `recurring_task_runs`. No avanza la fecha si falló. | ✅ **Cubierto.** Es el modelo a copiar. |
| **Alertas BOE**<br>`BoeAlertService` | 06:00 | `log.warn` y sigue. | 🔴 **Probado en producción: 34 días caído.** Ya arreglado el *motivo*, no el *silencio*. |
| **Avisos de contratos**<br>`ContractAlertService` | 06:00 | El único `catch` se traga el fallo **sin una sola línea de log** (comentario: *"no romper por noti fallida"*). Si falla el scan entero, solo sale en el log interno de Spring. | 🔴 **El peor.** Son avisos de periodo de prueba y fin de contrato: fallar en silencio tiene coste legal. |
| **Copia de seguridad**<br>`BackupService` | Lunes 03:00 + al arrancar si han pasado >7 días | `log.warn("fallo en backup semanal")`. | 🔴 **Te quedas sin copia y no te enteras.** Lo descubres el día que la necesitas. |
| **Reintento de firma / envío AEAT**<br>`SignatureRetryScheduler` | cada 10 min | `log.warn` por factura. | 🟡 Facturas sin sellar acumulándose. Hoy menos grave porque el envío a AEAT aún no está en marcha. |
| **Resumen SIF**<br>`SifEventScheduler` | cada 6 h | `log.warn` por empresa. | 🟡 Es trazabilidad SIF: importa de cara a una inspección. |
| **Latido de tiempo real**<br>`RealtimeService` | cada 25 s | — | ⚪ No es de negocio. Se deja fuera. |

> **Lectura**: cuatro tareas de negocio pueden estar rotas ahora mismo y la
> única forma de saberlo es abrir un `.log` de 5 MB en `C:\ProgramData`.

---

## 3. Los 5 servicios externos (y el agujero que dejó ayer)

| Servicio | Para qué | ¿Tiene el arreglo de certificados? |
|---|---|---|
| `www.boe.es` | Alertas BOE | ✅ Sí (`WindowsAwareTrust`, v0.1.76) |
| `agenciatributaria.gob.es` · `aeat.es` (4 hosts) | VeriFactu / envío AEAT | ❌ **No** |
| `googleapis.com` · `oauth2` · `gmail` | Drive, Calendar, correo | ❌ No |
| `nominatim.openstreetmap.org` | Mapa de centros de trabajo | ❌ No |
| `api.github.com` | **El actualizador de la app** | ❌ No |

**Siete clases construyen su propio `HttpClient` y solo una usa el almacén de
Windows.** Las demás siguen con el almacén de Java, que es exactamente el que
no conocía a la FNMT.

Dos de estos merecen atención especial:

- **AEAT** es una sede del Estado español, igual que el BOE. Es el candidato
  más probable a dar el mismo problema de certificado. Hoy no se nota porque el
  envío real a AEAT todavía no está activo — pero el día que se active, esto
  muerde.
- **`api.github.com` es el actualizador**. Si deja de responder, la app deja de
  enterarse de que hay versiones nuevas... y no te lo dice. Es el mismo fallo
  silencioso del BOE, aplicado a cómo recibes los arreglos.

---

## 4. Lo que YA existe y hay que reutilizar (no reinventar)

| Pieza | Dónde | Qué aporta |
|---|---|---|
| Campana de avisos | `advisory_notifications` / `business_notifications` | El canal. Ya elige tabla según sea asesoría o empresa. |
| `AdvisoryNotificationService.emit()` | advisory/notifications | Recibe la empresa **por parámetro**: ya es seguro desde un cron. |
| "Avisar solo en la transición a fallo" | `RecurringTaskService` | Evita un aviso cada día mientras algo siga roto. Es la pieza que hace esto usable. |
| `recurring_task_runs` | V-recurrentes | El modelo de histórico: cuándo corrió, con qué estado, cuánto tardó, qué mensaje. |

---

## 5. Propuesta: dónde poner la vigilancia

### 5.1. El mecanismo (una pieza, no un agente por tarea)

Una tabla genérica `job_runs` (nombre de tarea, inicio, fin, estado, mensaje,
empresa si aplica) y un envoltorio que cada tarea llama al empezar y al
terminar. Con eso:

- **Historial**: se ve cuándo corrió cada cosa por última vez y cómo acabó.
- **Aviso en la transición a fallo**: igual que los recurrentes. Un aviso cuando
  pasa a fallar, otro cuando se recupera. Nunca uno diario.
- **Aviso por ausencia**: lo que habría cazado el caso del BOE desde el día 2 —
  *"esta tarea lleva N días sin terminar bien"*. Esta es la clave: el BOE no es
  que diera error visible, es que **dejó de dar resultados** y nadie lo medía.

### 5.2. Puntos de supervisión propuestos, por prioridad

| # | Punto | Por qué | Esfuerzo |
|---|---|---|---|
| 1 | **Copia de seguridad** | Es lo único irrecuperable. Si falla y no lo sabes, el daño es total. | Bajo |
| 2 | **Avisos de contratos** | Hoy se traga el fallo sin log. Periodos de prueba y fines de contrato tienen plazos legales. | Bajo |
| 3 | **Alertas BOE** | Ya sabemos que puede pasar un mes caído. | Bajo |
| 4 | **Actualizador (GitHub)** | Si no te enteras de las versiones, no te llegan los arreglos. | Bajo |
| 5 | **Firma / envío AEAT** | Cuando el envío real se active, esto pasa a ser crítico. | Medio |
| 6 | **Resumen SIF** | Trazabilidad de cara a inspección. | Bajo |

### 5.3. La pantalla

En **Configuración → una pestaña nueva "Estado del sistema"**: una fila por
tarea con *última ejecución*, *resultado*, *último éxito* y, cuando algo lleve
sin funcionar, una línea en rojo explicándolo en castellano. Más un botón
**"Comprobar ahora"** que pruebe las conexiones externas (BOE, AEAT, Google,
GitHub) y diga cuál responde.

Esa pantalla, existiendo el 18 de octubre, habría enseñado *"Alertas BOE:
último éxito hace 2 días"* y esto se habría cazado esa misma semana.

### 5.4. Lo que NO se va a hacer, y por qué

**Un agente que detecte un certificado nuevo y lo acepte automáticamente.**
Fue la idea inicial de Benjamin y se descartó con argumento, no por pereza:

- Aceptar automáticamente el certificado que te presenten **es justo lo que
  HTTPS existe para impedir**. Convierte un fallo visible en un agujero
  invisible: cualquiera que se interponga en la conexión (un wifi ajeno, un
  antivirus mal configurado, un atacante en la red) podría ponerle su propio
  certificado y la app lo daría por bueno.
- Además es trabajo duplicado: Microsoft ya mantiene el almacén de raíces con
  auditorías y revocaciones, y lo actualiza por Windows Update. El arreglo de
  la v0.1.76 **se engancha a ese trabajo** en vez de rehacerlo peor.

El agente correcto no toca la seguridad: **vigila y avisa**.

---

## 6. Decisiones pendientes de Benjamin

1. **¿Qué puntos entran en el primer bloque?** Propuesta: los cuatro primeros
   de 5.2 (backup, contratos, BOE, actualizador), que son todos de esfuerzo
   bajo y cubren lo irrecuperable y lo legal.
2. **¿Avisos a la campana, o también por correo?** La campana solo la ves si
   abres la app; si el fallo es que la app no arranca, no te enteras. El correo
   cubre ese hueco pero mete más piezas.
3. **¿Cuántos días sin éxito disparan el aviso de ausencia?** Propuesta: 2 días
   para las diarias y 10 para el backup semanal.
4. **¿Se lleva el arreglo de certificados a los otros seis clientes HTTP**, o
   solo a AEAT (el que comparte el riesgo real)?

---

## 7. Lo que este análisis NO ha mirado

- La **PWA del empleado** y el portal: solo se auditó el backend.
- Los fallos de la **UI** (una pantalla que no carga ya se ve al momento; el
  problema de este documento son las cosas que pasan sin nadie delante).
- `RealtimeService`, por no ser de negocio.
