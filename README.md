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
