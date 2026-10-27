# Pomodoro a huevo

Pomodoro para GNOME que en los descansos **tapa la pantalla** y no te deja seguir trabajando.
Se arma con un horario semanal, recordatorios, un widget redondo y control del teléfono por ADB.

## Tipos de actividad del horario

| Tipo | Qué pasa |
|---|---|
| **Trabajo** | Corre el pomodoro; los descansos bloquean la pantalla. |
| **Enfocado** | Igual que trabajo, pero la pantalla del descanso trae el botón **«Saltar descanso»** (para reuniones). Se puede apagar en *Ajustes → Bloqueo y ventana*. |
| **Descanso programado** | Bloquea la pantalla durante todo el bloque (p. ej. la comida). |
| **Fuera de trabajo** | No corre el pomodoro. |

## Sensor de presencia (trabajo efectivo)

El pomodoro cuenta solo el trabajo de verdad. Al terminar un descanso, al arrancar la jornada o si lo
pausas en horario de trabajo, **queda esperándote** y arranca solo cuando te ve trabajando: una app de
trabajo al frente y usando teclado o mouse unos segundos. Mientras corre, si te vas (sin tocar la compu)
o te distraes (YouTube de ocio, redes, juegos…), se pausa y **ese rato se devuelve al reloj**.

Si tardas en arrancar, la presión sube por escalones (*Ajustes → Sensor de presencia*):

| Retraso | Qué pasa |
|---|---|
| 2 min | Aviso en la compu y en el teléfono. |
| 5 min | Teléfono en **modo estricto**: te saca al instante de lo que distrae y bloquea pronto lo demás; en la compu se minimiza lo que distrae. |
| 8 min | En la compu, pantalla completa **«¿Arrancamos?»** (empezar, o decir en qué trabajas lejos de la compu: lo juzga la IA). El teléfono se bloquea en cuanto lo usas (salvo llamadas, mensajes, ajustes, banco y `phone_essential`). |

En el teléfono, además, cualquier **juego** cuenta como app que distrae, y si vuelves a entrar a una app de
la que te sacó (o al teléfono justo después de un bloqueo), ya no hay tiempo de gracia.

Cómo sabe qué haces:

- **Ventana al frente y tiempo sin tocar la compu**: la extensión «vigía» (`gnome-extension/`) deja
  `focus.json` cada 2 s. Sin ella (p. ej. antes de volver a iniciar sesión tras actualizarla) solo se sabe
  si estás en la compu, por D-Bus.
- **Reglas** (`core/focus.py`): editores, terminales, PDF… son trabajo; Netflix, redes, juegos, distracción.
- **IA** (`services/judge.py`) para lo dudoso, sobre todo páginas del navegador: `claude -p` con Haiku, sin
  herramientas, ~700 tokens por consulta, varias ventanas por llamada, cada respuesta se recuerda en
  `focus_cache.json` y hay tope por hora y por día. Si se equivoca, toca la línea del sensor en la ventana
  del Pomodoro («¿sí es trabajo? toca aquí») y se recuerda para esa actividad. Una actividad del horario
  puede llevar `"intent"` (p. ej. `"proyecto: app Android en Kotlin"`) para que la IA juzgue mejor.

Las estadísticas del día (efectivo, retraso, distraído, fuera) salen en la ventana y al final de la jornada,
y viajan al servidor con la señal de vida (`devices.desktop.info.focus`) para el bot.

## Uso

```bash
python3 pomodoro.py          # abre la ventana (o muestra la que ya corre)
python3 pomodoro.py --auto   # como lo lanza el inicio de sesión / el timer de systemd
```

Requiere Python 3 con Tk, y para el icono de la barra PyGObject + `libayatana-appindicator3`
del sistema. El teléfono es opcional (`adb` en el PATH).

## Teléfono

Hay dos formas de controlar el teléfono, y conviven:

- **ADB desde la compu** (sin instalar nada): avisos, bloqueo al terminar el descanso, vigilancia de
  apps que distraen y No molestar. Solo funciona con la compu prendida.
- **App Android** (`android/`): lleva el pomodoro aunque la compu esté apagada, con el mismo horario,
  recordatorios y reglas. Se sincroniza con la compu a través del servidor del VPS (`server/`).

Mientras la compu está prendida, ella manda y el teléfono la refleja; lo que hagas en el teléfono
(iniciar, pausar, descanso, saltar) se aplica en la compu al instante. Con la compu apagada, el teléfono
lleva la jornada solo. Si la app está viva, ADB deja de repetir sus avisos; si MIUI la mata, la compu
la revive por ADB y le da sus permisos (accesibilidad, No molestar, batería).

```bash
./android/build.sh        # compila en el VPS e instala por ADB
./server/deploy.sh        # sube el servidor de sincronización
```

La sincronización se activa con `~/.config/pomodoro-ahuevo/sync.json`:
`{"url": "https://pomodoro.mauriciojuanes.com", "token": "…"}` (el mismo token que
`/opt/pomodoro-sync/.env` en el VPS). Sin ese archivo la app funciona igual, sin sincronizar.

## Datos personales

Nada personal vive en el repositorio:

- `~/.config/pomodoro-ahuevo/config.json`: todo tu estado (horario actual, estadísticas, teléfono…).
- `~/.config/pomodoro-ahuevo/defaults.json`: tu horario "original", el que restaura el botón del
  editor y el que se usa la primera vez. Si no existe se usa `defaults.example.json`.

## Estructura

Arquitectura por capas; la app (`app.Pomodoro`) combina mixins de controlador y de vista.

```
pomodoro.py                  punto de entrada (instancia única, restaurar atajos al salir)
defaults.example.json        horario de ejemplo
pomodoro_app/
  paths.py, constants.py     rutas y constantes compartidas
  config.py                  valores por defecto, carga y guardado
  core/                      lógica pura: horario, consejos, formatos de tiempo
  services/                  sistema: GNOME, systemd, sonido, teléfono (ADB), sincronización, icono de la barra
  controller/                comportamiento: temporizador, jornada, recordatorios
  ui/                        ventanas Tk: principal, pantallas completas, widget, editor, ajustes
  app.py                     estado y unión de todo
server/                      sincronización (FastAPI en Docker, detrás de nginx en el VPS)
android/                     app del teléfono (Kotlin, sin dependencias)
```
