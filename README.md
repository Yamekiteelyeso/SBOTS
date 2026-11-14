# SBot 2.2.0 — jugadores simulados para Paper 1.20.1

**Objetivo:** registrar jugadores simulados dentro del `PlayerList` nativo de Paper. No son NPCs mostrados únicamente por paquetes, ni hay una TAB propia. Están representados por objetos NMS `ServerPlayer` y conexiones internas sin un cliente TCP real.

> **Estado:** los cambios se verifican en GitHub Actions; las pruebas dentro de Paper todavía requieren un servidor de pruebas antes de considerarlo apto para producción.

## Qué hace

- `/sbot create <cantidad>`: registra inmediatamente jugadores simulados en la ubicación de quien ejecuta el comando. Si la skin no está en caché, la descarga en segundo plano sin bloquear la conexión.
- Los `ServerPlayer` se registran mediante `PlayerList.placeNewPlayer`; una vez registrados deberían contar para los métodos `Bukkit.getOnlinePlayers()`, `/list` y el contador nativo del **backend Paper**.
- TAB y cuerpo se actualizan con el mecanismo estándar de Paper. No existe TAB personalizada ni inyección visual por paquetes desde SBot.
- `/sbot tp <nombre>`, `/sbot tpall`: teletransporte mediante Bukkit/NMS.
- `/sbot delete <nombre>`, `/sbot delete allbots`: elimina el registro interno y llama a `PlayerList.remove(ServerPlayer)`.
- `/sbot status`: muestra reales, SBot activos, preparados y contador total de Paper.
- `/sbot list`, `/sbot info <nombre>`, `/sbot reload`.
- `restore-on-startup: true`: restaura ubicaciones tras un reinicio (solo cuando Paper vuelve a encenderse).
- Texturas públicas firmadas de Mojang, con caché local.
- UUID sintéticos específicos de SBot, para no usar el UUID premium auténtico, ni datos/inventario de otro usuario.
- Los nombres pertenecen a `premium-name-pool`; los jugadores reales tienen prioridad cuando su nombre entra al servidor.
- `minimum-free-slots` limita el número de bots para reservar cupos humanos. Un bot realmente registrado puede ocupar una plaza del límite del servidor.

## Lo que NO significa «jugador real»

SBot genera **jugadores simulados en el servidor**, no cuentas Microsoft verificadas ni conexiones TCP desde el cliente Minecraft. Algunos complementos de Bukkit que usan `getOnlinePlayers`, entidades `Player`, eventos de ingreso o comandos nativos pueden reconocerlos; otros, especialmente plugins de autenticación, sistemas anticheat, plugins de red/proxy y complementos dependientes de IP/sesiones de cliente, pueden distinguirlos o rechazarlos. No hay compatibilidad universal garantizada.

Si usas Velocity/BungeeCord, la lista pública y el contador de la **proxy** pueden mostrar únicamente sus conexiones reales. La lista de Paper backend no controla necesariamente esos valores. Revisa la configuración de ping passthrough de la proxy.

Si tu hosting apaga el **proceso del servidor** al no haber clientes TCP reales, SBot no puede impedirlo, aunque los `ServerPlayer` consten online. Tampoco puede reactivar un servidor apagado. Una conexión sintética no equivale a un usuario autenticado.

## Instalación / compilación

Objetivo: **Paper 1.20.1**, **Java 17**, **Gradle 8.x**, **paperweight-userdev**.

```bash
gradle clean reobfJar
```

Copia el JAR reobfuscado generado en `build/libs/` (no el `-dev.jar`) a `plugins/`. En Paper 1.20.1 se recomienda comprobar la versión exacta de mappings tras compilar.

## Comandos

| Comando | Acción |
| --- | --- |
| `/sbot create 5` | Prepara cinco jugadores simulados con skins y nombres del pool |
| `/sbot tpall` | Teletransporta todos a tu ubicación |
| `/sbot tp Notch` | Teletransporta un jugador simulado |
| `/sbot delete allbots` | Desregistra todos |
| `/sbot delete Notch` | Desregistra un jugador |
| `/sbot status` | Comprueba cuántos están registrados |
| `/sbot list` | Muestra los nombres |
| `/sbot info Notch` | Información de un jugador |
| `/sbot reload` | Recarga los dos YAML |

Permiso: `sbot.admin`, concedido a OP por defecto. Desde consola puedes usar `list`, `info`, `status`, `delete` y `reload`.

## Archivos

- `config.yml`: límites, reserva de plazas, restauración, skins y `premium-name-pool`.
- `messages.yml`: mensajes de comandos en español personalizables.
- `bots.yml`: guardado de nombre, mundo y posición.
- `skins.yml`: caché de texturas.

Si PlaceholderAPI está instalado: `%sbot_bots%` (registrados), `%sbot_pending%` (preparados), `%sbot_real%`, `%sbot_total%` (de Paper), `%sbot_names%`.

## Pruebas recomendadas

1. Abre un servidor **de pruebas** con Paper 1.20.1 y 20 slots.
2. Instala el plugin y ejecuta `/sbot create 3` siendo OP.
3. Espera a que `/sbot status` indique `3 SBot, 0 cargando`.
4. Comprueba `/list`, `Bukkit.getOnlinePlayers()` desde otro plugin de prueba, y la TAB normal.
5. Sal del servidor, actualiza el contador de la lista multijugador mediante una conexión directa **al backend Paper** y verifica que los tres sigan online.
6. Entra de nuevo, usa `/sbot tpall`, borra un bot, comprueba `/list` y `/sbot status`.
7. Haz `/sbot delete allbots`; verifica que no quedan entradas fantasma y que se puede reconectar con normalidad.
8. Reinicia Paper con `restore-on-startup: true` y confirma la restauración.
9. Repite con plugins de autenticación, TAB, scoreboards y proxy que utilices en tu servidor. No asumir compatibilidad total sin estas pruebas.

## Notas de implementación

- La clase `NmsPlayerService` encapsula el código NMS específico de `v1_20_R1`.
- `FakeConnection` proporciona un canal Netty en memoria, responde a keepalives y descarta paquetes de salida; no se conecta a servidores de Mojang ni inicia sesiones de juego auténticas.
- El método nativo de borrado se usa en lugar de únicamente invocar `kick()`, porque una conexión sintética puede no procesar una desconexión del mismo modo que un cliente real.
- La creación es asíncrona para no bloquear el hilo principal esperando HTTPS; el registro NMS ocurre en el hilo principal del servidor.
- El código utiliza la estructura de NMS 1.20.1; cualquier modificación en otra versión de Paper requiere revisión.

## Correcciones 2.2.0

- Registro **inmediato** de los jugadores; las consultas de skins ya no bloquean la aparición. Las skins obtenidas después de conectar se actualizan con paquetes nativos de información del jugador.
- `FakeConnection` confirma los paquetes keepalive del servidor para evitar el timeout de clientes inexistentes.
- El hilo principal revisa cada 2 segundos la presencia de los jugadores en `Bukkit.getOnlinePlayers()`, evita inactividad y restaura los que hayan salido inesperadamente. No interfiere con expulsiones de jugadores humanos.
- Tickets de chunks activos por defecto mientras existe al menos un bot en cada chunk, para evitar que Paper descargue la zona cuando se marchan los jugadores reales.
- `/sbot status` cuenta bots realmente registrados en Paper, no referencias NMS perdidas.
- `config.yml`: `auto-reconnect-missing-bots`, `prevent-automatic-kicks`, `keep-bot-chunks-loaded`.

**Nota:** En una red Velocity o BungeeCord, estos jugadores siguen siendo simulados en el backend Paper; las proxies pueden mostrar solo conexiones TCP reales. SBot no mantiene activo un proceso de servidor que un hosting apague.


## Correcciones 2.2.0 — aparición y nombres

- `/sbot create <cantidad>`: nombres originales generados aleatoriamente (ej. `Vexrox_4382`). Ya no usa identidades públicas conocidas en TAB, `/list` ni el scoreboard.
- `/sbot createat <cantidad> <mundo> <x> <y> <z>`: variante ejecutable desde consola para pruebas o administración; las coordenadas introducidas son el centro de la formación.
- `/sbot info <nombre>` ahora muestra la ubicación real del jugador registrado, no solamente las coordenadas guardadas en `bots.yml`.
- Corrección de posición NMS: después de `placeNewPlayer` el plugin ajusta la posición real. Reintenta en el siguiente tick y el watchdog la comprueba periódicamente.
- Los nombres ya guardados que pertenecían a la lista anterior de famosos se sustituyen por identidades generadas durante la restauración.
- `skin-source-pool` únicamente contiene nombres de **origen para descargar texturas**, jamás se muestra como el nombre del bot. Puede quedar vacío para usar skins predeterminadas.
- Los bots siguen siendo jugadores simulados; las cuentas premium que se usan como fuente de skin no se autentican.

La prueba CI incluye ahora una creación con `/sbot createat`, verificación de nombre aleatorio y comprobación de las coordenadas reales de `/sbot info` antes y después de más de un minuto sin clientes conectados.
