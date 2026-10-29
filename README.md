# Bidaide Notificator

App de macOS que vive en la barra de menú y te avisa de las pull requests que esperan algo de ti.
Todo pasa por la [GitHub CLI](https://cli.github.com): usa la sesión de `gh auth login` y no guarda tokens.

## Qué hace

- **Barra de menú**: icono con el número de PR pendientes. Al pulsarlo, lista por secciones:
  - **Revisar**: te han pedido revisión (`review-requested`).
  - **Responder**: tus PR con cambios pedidos o hilos sin resolver cuyo último comentario no es tuyo.
  - **Comentadas**: notificaciones sin leer de comentarios, menciones y actividad en tus PR.
  - **Asignadas**: PR asignadas a ti.
  - **Mis PR**: tus PR abiertas, con checks y estado de review (se puede ocultar).
  Clic abre la PR; clic derecho para copiar el enlace o marcar la notificación como leída.
- **Notificaciones**: revisión pedida, asignación, comentario, mención, actividad en tus PR y, opcionalmente,
  fusiones/cierres y CI. Muestran autor y texto del comentario; clic abre el comentario. Acción «Marcar como leída».
  La primera consulta no notifica lo que ya estaba sin leer, y nunca avisa de lo que haces tú.
- **Widget** (pequeño, mediano, grande y extragrande): «Todo lo pendiente» o una sección concreta, a elegir
  editando el widget.

## Instalar

Requisitos: macOS 14+ y `gh` con sesión iniciada (`gh auth login`).

```sh
brew install --cask davidups/tap/bidaide-notificator
open -a "Bidaide Notificator"
```

Actualizar: `brew upgrade --cask bidaide-notificator`. Desinstalar: `brew uninstall --cask --zap bidaide-notificator`.

Desde el código (necesita Xcode):

```sh
./scripts/install.sh            # compila en Release, copia a ~/Applications y la abre
```

Después: clic derecho en el escritorio → *Editar widgets* → busca «Pull requests». Para arrancar con el Mac,
activa «Abrir al iniciar sesión» en Ajustes.

## Desarrollo

```sh
xcodegen generate               # genera BidaideNotificator.xcodeproj desde project.yml
open BidaideNotificator.xcodeproj
```

Estructura:

- `App/`: menú, ajustes, `GhCli` (ejecuta `gh`), `GitHubService` (una consulta GraphQL de búsqueda + `/notifications`),
  `AppModel` (sondeo, snapshot, notificaciones), `Notifier`.
- `Widget/`: extensión WidgetKit.
- `Shared/`: modelos y `SnapshotStore`.

El widget está en sandbox y no puede ejecutar `gh`. La app escribe
`~/Library/Application Support/BidaideNotificator/snapshot.json` en cada consulta y recarga el widget, que solo
lee ese fichero (excepción de sandbox de solo lectura). Así funciona con firma ad-hoc, sin equipo de desarrollo;
con un equipo de Apple se podría cambiar a un App Group.

## Publicar una versión

```sh
git tag v0.2.0 && git push origin v0.2.0
```

El workflow `Release` compila, sube `BidaideNotificator-<versión>.zip` a la GitHub Release y actualiza el cask en
[DavidUps/homebrew-tap](https://github.com/DavidUps/homebrew-tap) si existe el secreto `TAP_TOKEN` (token con
permiso de escritura sobre ese repo). Sin él, actualiza `version` y `sha256` a mano con `scripts/package.sh <versión>`.
