# Chuleta personal de Git — Benjamin

> Esta chuleta está hecha SOLO con los comandos que ya has usado.
> Está organizada por SITUACIÓN, no alfabéticamente. Cuando no recuerdes algo,
> busca por qué necesitas hacer, no por cómo se llama el comando.
>
> **Hermana de esta chuleta:** `docs/chuleta-programacion-benjamin.md` (lecciones de programación).

---

## ⚠️ Aviso importante: dos proyectos, dos reglas distintas

Tú trabajas en dos proyectos a la vez, y el flujo de Git **no es igual** en los dos. Es importantísimo que tengas esto claro antes de hacer cualquier `push`:

| | **CONTENDO GESTIONES** | **BENJAGEST migración** |
|---|---|---|
| Estructura | 3 repos anidados (umbrella + frontend + backend) | Un solo repo multimódulo (Maven) |
| Rama estable | `main` | `main` |
| Rama de trabajo central | `main` (sí, se trabaja directo) | `develop` (nunca tocar `main`) |
| Flujo de cambios | Rama feature → merge local → push a `main` | Rama feature → push → **Pull Request en GitHub** → revisión de Pablo → merge |
| ¿Puedes hacer `git push origin main`? | Sí (despliega Vercel/Render) | **NUNCA** sin acuerdo previo |
| ¿Puedes mergear sin revisión? | Sí (es tu app) | **NO**, salvo urgencia acordada con Pablo |

**Regla mental:** si la ruta de tu PowerShell pone `C:\Proyectos\CONTENDO GESTIONES\...` o `G:\...`, son tus reglas. Si pone `C:\Proyectos\git\benjagest-migration\...`, manda el flujo de Pablo.

---

## Conceptos base (lo que tienes que tener en la cabeza)

| Palabra | Qué es en cristiano |
|---|---|
| **Repo** | Una carpeta con `.git` dentro. Es donde Git guarda el álbum de fotos. |
| **Commit** | Una foto del proyecto en un momento dado, con su descripción. |
| **Rama (branch)** | Un plano paralelo de la casa. Puedes trabajar en uno sin tocar el otro. |
| **`main` / `master`** | La rama "principal". Lo que está en producción suele venir de aquí. |
| **`develop`** (BENJAGEST) | La rama central de trabajo. Las features salen y vuelven a aquí. |
| **HEAD** | "Dónde estás ahora". La foto en la que vives en este momento. |
| **HEAD~1** | La foto justo anterior a la actual. HEAD~5 = 5 fotos atrás. |
| **Staging area** ("la mesa") | Lo que has seleccionado para que entre en la próxima foto. |
| **Remote / origin** | El álbum en la nube (GitHub). `origin` es el apodo habitual. |
| **Pull Request (PR)** | Petición en GitHub para fusionar tu rama. Punto de revisión. |
| **Worktree** | Una carpeta de trabajo extra del mismo repo (avanzado, raro). |

---

## SITUACIÓN 1 — "Quiero ver qué hay" (todo SIN RIESGO)

Estos comandos solo leen. Es imposible romper nada.

```powershell
git status               # ¿qué tengo cambiado ahora mismo?
git log --oneline -10    # mis últimas 10 fotos (mensaje + hash)
git show <hash>          # ver una foto entera (qué cambió)
git show --stat <hash>   # ver solo qué archivos cambiaron, sin las líneas
git diff                 # comparar mis cambios sin commitear con la foto actual
git branch               # ¿qué ramas tengo y en cuál estoy? (* = aquí estás)
git branch -a            # ramas locales + las de GitHub que conoces
git remote -v            # ¿a qué GitHub está conectado este repo?
git ls-files             # lista todos los archivos que Git rastrea
git check-ignore -v <archivo>   # ¿por qué Git ignora este archivo?
```

**Truco**: si no sabes qué está pasando, **siempre empieza por `git status`**. Te dice todo: en qué rama estás, qué cambios tienes pendientes, y qué te falta por hacer.

---

## SITUACIÓN 2 — "Voy a hacer un cambio" (el ciclo básico)

⚠️ **Este es el flujo correcto para BENJAGEST**. Para CONTENDO ver la nota al final de esta sección.

```powershell
# 1. Asegúrate de partir limpio y al día desde develop
git status                       # debe decir "clean"
git checkout develop             # te aseguras de estar en develop
git pull origin develop          # bajar lo último de GitHub

# 2. Crear una rama para tu cambio (NUNCA trabajar en develop ni main directamente)
git checkout -b tipo/descripcion-breve
# ejemplos:
#   git checkout -b feature/modulo-cierre-ejercicio
#   git checkout -b fix/calculo-irpf-profesionales
#   git checkout -b docs/notas-modelo-datos
#   git checkout -b chore/actualizar-dependencias

# 3. Trabajas, editas archivos como siempre, pruebas en local...

# 4. Ver lo que has cambiado
git status                       # qué archivos tocaste
git diff                         # qué líneas cambiaste

# 5. Pon en la mesa lo que quieres fotografiar
git add archivo1.java archivo2.java
# o "todo lo que he cambiado":
git add .                        # ⚠️ cuidado, mete TODO

# 6. Hacer la foto con un mensaje real
git commit -m "fix: corregido cálculo IRPF para profesionales al 7%"

# 7. Subir TU RAMA a GitHub (no a develop ni a main)
git push -u origin tipo/descripcion-breve

# 8. ABRIR PULL REQUEST EN GITHUB
#    - Entras a https://github.com/pcs001es/benjagest-migration
#    - GitHub te muestra "Compare & pull request" → púlsalo
#    - Base: develop  |  Compare: tu rama
#    - Asignas a Pablo como reviewer
#    - Pulsas "Create pull request"

# 9. Esperas la revisión de Pablo.
#    Si te pide cambios, los haces en la misma rama, commiteas
#    y haces git push otra vez. La PR se actualiza sola.

# 10. Cuando Pablo aprueba, ÉL mergea la PR desde GitHub.
#     Tú NO mergeas localmente. Tú NO haces push a develop ni a main.
```

### Una vez la PR está mergeada por Pablo

```powershell
git checkout develop             # vuelves a develop
git pull origin develop          # bajas el merge que hizo Pablo
git branch -d tipo/descripcion-breve   # borras tu rama local (ya no se necesita)
git push origin --delete tipo/descripcion-breve   # opcional: borras la rama en GitHub
```

### Prefijos para los mensajes de commit (BENJAGEST sigue Conventional Commits)

- `feat:` — funcionalidad nueva
- `fix:` — arreglo de bug
- `chore:` — mantenimiento, configuración, limpieza
- `docs:` — documentación
- `refactor:` — reorganizar código sin cambiar comportamiento
- `test:` — añadir o cambiar tests
- `migration:` — cambios de modelo de datos / migraciones SQL

### ⚠️ En CONTENDO el flujo es distinto

En CONTENDO sí merges localmente a `main` y haces `git push origin main`, porque es tu app y cada push despliega en Vercel/Render. Esa parte la tienes en tu cabeza, no la apliques en BENJAGEST.

---

## SITUACIÓN 3 — "Quiero cambiar de rama" o "moverme por el historial"

```powershell
git branch                       # ver dónde estás (* = aquí)
git checkout develop             # saltar a develop (rama central de BENJAGEST)
git checkout main                # saltar a main (rama estable)
git checkout otra-rama           # saltar a otra rama existente
git checkout -b nueva-rama       # crear rama nueva Y saltar a ella
```

**Importante**: al saltar de rama, **los archivos en tu Explorador de Windows cambian solos**. No te asustes. Cada rama tiene su propia versión de los archivos.

---

## SITUACIÓN 4 — "Subir o bajar a/de GitHub"

```powershell
# Subir
git push -u origin nombre-rama   # subir Y emparejar (primera vez de una rama)
git push origin nombre-rama      # subir cambios siguientes de esa rama

# Bajar
git pull origin develop          # bajar lo último de develop a mi PC
git fetch                        # solo descargar sin aplicar (para inspeccionar)
git fetch --prune                # descarga + limpia ramas de seguimiento borradas

# Borrar una rama EN GitHub (solo después de mergear la PR)
git push origin --delete nombre-rama
```

**En BENJAGEST nunca haces `git push origin main` ni `git push origin develop`.** Eso lo hace Pablo cuando mergea las PRs.

---

## SITUACIÓN 5 — "Mover, renombrar o borrar archivos"

```powershell
git mv archivo-viejo.java carpeta-nueva/    # mover/renombrar (Git lo detecta como rename)
git rm archivo.java                          # borrar archivo trackeado y meter en la mesa
```

`git mv` y `git rm` ya hacen el `git add` solos. No tienes que stagearlos después.

---

## SITUACIÓN 6 — "He metido la pata, quiero deshacer"

⚠️ Cuidado con estos. Algunos son destructivos.

```powershell
git restore archivo.java         # tira mis cambios sin commitear en ese archivo
git restore --staged archivo.java # saca el archivo de la mesa (sin perder cambios)

# Modificar el último commit (añadir algo olvidado o cambiar mensaje)
git add lo-que-faltaba
git commit --amend --no-edit     # mismo mensaje, foto rehecha
git commit --amend -m "nuevo mensaje"  # cambiar mensaje

# ⚠️ DESTRUCTIVO: borrar último commit Y los archivos
git reset --hard HEAD~1          # vuelve al estado anterior, borra todo

# Nota: nunca uses --amend o reset --hard si el commit YA está pusheado a GitHub.
# Esos solo son seguros en local.

# Para deshacer algo YA pusheado, usa git revert (no destructivo):
git revert <hash>                # crea un commit nuevo que deshace el otro
```

---

## SITUACIÓN 7 — "Configuración y limpieza avanzada" (rara vez)

```powershell
git remote remove nombre-remote          # quitar un remote configurado
git worktree list                         # ver worktrees registrados
git worktree prune -v                     # limpiar worktrees fantasma
git worktree remove --force <path>        # ⚠️ quitar un worktree con su carpeta
git branch -D nombre-rama                 # ⚠️ borrar rama sin merge (D mayúscula = a la fuerza)
```

---

## PowerShell útil (los que has usado)

```powershell
cd "carpeta con espacios"        # entrar en una carpeta (comillas si tiene espacios)
cd ..                            # subir un nivel
ls                               # listar archivos
ls -la                           # listar incluso ocultos y con detalles
New-Item nombre.txt              # crear archivo vacío
Remove-Item archivo.java         # borrar archivo
Remove-Item -Recurse -Force carpeta/   # borrar carpeta entera y su contenido
notepad $PROFILE                 # editar tu perfil de PowerShell
```

### Sugerencias de PSReadLine (ya activas)

- Mientras escribes, te aparecen sugerencias en gris claro del historial.
- **Flecha derecha (→)** = aceptar sugerencia entera.
- **Ctrl + R** = buscar en historial.
- **F2** = cambiar entre vista inline y vista lista.

---

## Mapa visual del flujo (BENJAGEST)

```
   [tu disco duro]
        |
        | git add
        v
   [staging area / la mesa]
        |
        | git commit
        v
   [historial local en tu PC, en TU rama feature]
        |
        | git push -u origin tu-rama
        v
   [tu rama en GitHub]
        |
        | Pull Request → revisión Pablo → merge
        v
   [develop en GitHub]
        |
        | (más adelante: Pablo decide pasar develop → main)
        v
   [main en GitHub = versión estable]
```

Y al revés (bajar lo que ha mergeado Pablo):

```
   [develop en GitHub]
        |
        | git checkout develop && git pull origin develop
        v
   [tu develop local actualizado]
```

---

## Reglas de oro

1. **Antes de cada commit: lee `git status`.** Mira las dos columnas (en mesa / fuera de la mesa). Si te falta meter algo, falta `git add`.
2. **Antes de cada push: comprueba `git log --oneline -3` y `git status`.** Asegúrate de que vas a subir lo que crees.
3. **En BENJAGEST: nunca trabajas directo en `main` ni en `develop`.** Siempre rama feature → PR.
4. **En BENJAGEST: nunca mergeas tú la PR.** Eso lo hace Pablo cuando lo aprueba.
5. **Mensajes de commit con verbo y prefijo Conventional Commits**, no "fix" a secas. Tu yo de mañana te lo agradecerá.
6. **`--force` y `reset --hard` son destructivos**: úsalos solo cuando entiendes exactamente qué te están protegiendo y has decidido conscientemente saltártelo. Si el commit ya está pusheado, **usa `git revert`**.
7. **Identifica en qué proyecto estás antes de tocar Git.** Mira la ruta del PowerShell:
   - `C:\Proyectos\git\benjagest-migration\` → flujo de Pablo (PR obligatoria, no push a main/develop).
   - `C:\Proyectos\CONTENDO GESTIONES\` → tu app, tus reglas (merge y push a main directos).
8. **En BENJAGEST `git push origin develop` y `git push origin main` están vetados** salvo acuerdo expreso con Pablo. La rama que sí puedes pushear es siempre la tuya (`feature/*`, `fix/*`, `docs/*`, etc.).

---

## SITUACIÓN 8 — "Cambiar de cuenta GitHub / gestionar credenciales"

Tienes dos cuentas (`benjaminreciolopez` y `susanaybenjamin-boop`) y Git Credential Manager guarda **una sola** credencial para `github.com` por defecto. Cuando intentas pushear a un repo cuya cuenta no coincide con la guardada, GitHub responde con `403 Permission denied`.

### Saber con qué credencial está autenticado este repo

```powershell
# 1. Identidad del autor (lo que se firma en los commits)
git config --get user.name
git config --get user.email

# 2. URL del remote — pista del propietario del repo
git remote -v

# 3. ¿Qué credencial tiene cacheada Windows para github.com?
cmdkey /list | Select-String 'git:' -CaseSensitive:$false
# Te muestra algo como:
#   Destino: LegacyGeneric:target=git:https://github.com
#   Usuario: <nombre-de-cuenta-cacheada>
```

El campo "Usuario" de `cmdkey` te dice **con qué cuenta vas a pushear**. Si no coincide con el dueño del repo, hay que cambiarla.

### Limpiar la credencial cacheada (forzar nuevo login)

```powershell
# Borra la credencial global de github.com. El próximo `git push` te
# abrirá el navegador para que inicies sesión otra vez.
cmdkey /delete:"git:https://github.com"
```

### Guardar una credencial nueva

No hace falta un comando explícito. **Después de borrar**, el siguiente `git push` lanzará el flujo OAuth de GitHub:

1. Se abre una ventana del navegador.
2. Inicias sesión con la cuenta correcta (`benjaminreciolopez` o `susanaybenjamin-boop`).
3. Autorizas a "Git Credential Manager".
4. Vuelves a PowerShell, el push continúa, y la credencial nueva queda guardada automáticamente en Windows Credential Manager.

### Evitar tener que borrar y re-loguear cada vez (recomendado)

```powershell
# Hacer que GCM guarde una credencial DISTINTA por cada URL completa
# de repo, no una global para todo github.com.
git config --global credential.useHttpPath true
```

Tras activar esta flag, cada repo se acuerda de **su** cuenta sin pisarse con otros. Ya nunca tienes que borrar manualmente al cambiar de proyecto.

### Cambiar la identidad de autor (firma de los commits)

Solo para el repo actual:

```powershell
git config user.name "Benjamin"
git config user.email "benjaminreciolopez@hotmail.es"
```

Para todos los repos (global):

```powershell
git config --global user.name "Benjamin"
git config --global user.email "benjaminreciolopez@hotmail.es"
```

Esto **no autentica nada**; solo cambia el nombre que aparece como autor en `git log`. La autenticación va por separado (la credencial cacheada).

### Síntomas y diagnóstico rápido

| Síntoma | Causa probable | Solución |
|---|---|---|
| `remote: Permission to <repo> denied to <otra-cuenta>` | GCM tiene cacheada la cuenta equivocada | `cmdkey /delete:"git:https://github.com"` y volver a pushear |
| `git config user.email` muestra un email, pero los commits aparecen con otro autor en GitHub | Email no asociado a tu cuenta GitHub | Configura el email que sí está en tu cuenta de GitHub |
| Push parece funcionar pero el deploy no se dispara (solo CONTENDO) | Otra causa (rama equivocada, auto-deploy off), no de credenciales | Mira `git branch` y la rama de deploy de Vercel/Render |
| Push falla con `Internal Server Error` / `502` | **No es tuyo, es GitHub**. Comprueba https://www.githubstatus.com | Espera 15-30 min y reintenta |

---

## Comandos que aún NO te he enseñado pero podrían venirte bien

- **`git stash`** — guardar temporalmente cambios sin commitear, para hacer otra cosa primero (cambiar de rama, hacer un pull, etc.).
- **`git tag v1.0`** — etiquetar una versión.
- **`git cherry-pick <hash>`** — coger un commit suelto de otra rama y aplicarlo en la actual.
- **`git rebase`** — reorganizar la historia de tu rama antes de pedir PR (avanzado, mejor pedir ayuda la primera vez).

Cuando los necesites, pídelos.

---

*Última actualización: 2026-05-27. Adaptada al flujo de BENJAGEST (PR-based) sin perder la utilidad de CONTENDO.*
