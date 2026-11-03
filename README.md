# Faro

(Antes «Pomodoro a huevo».) Tu sistema de enfoque y bienestar: pomodoro para GNOME que en los descansos
**tapa la pantalla** (también la del teléfono), con horario semanal, recordatorios, tareas de TickTick, diario
de urgencias y recaídas, frases para reforzar creencias, y un ciclo nocturno que **se mejora solo** con lo que
pasa y lo que le dices. Por dentro los paquetes conservan el nombre viejo (`pomodoro_app`,
`com.mauriciojuanes.pomodoro`) para no perder permisos ni configuración.

## Faro: lo nuevo

| Pieza | Qué hace |
|---|---|
| **Dónde aplica cada actividad** | En el horario, cada actividad dice si aplica en 💻📱 ambos, solo compu, solo teléfono o ninguno (`lock`). Una actividad «solo teléfono» no tapa la compu ni activa el sensor; «solo compu» no vigila el teléfono. |
| **Apps permitidas por actividad** | Cada actividad permite tipos de app en el teléfono (`allow`: banco, mensajes, mapas, trabajo, estudio, lectura, compras, música, redes, video, juegos). Si no escoges, Faro lo deduce: primero la IA nocturna (`policies` del hub, con la `intent` de la actividad), si no, el nombre («👤 trámites» → banco, compras, mapas…). **Banco, mapas y lo esencial nunca se bloquean** ni cuentan como «estar en el teléfono»; las apps de banco se reconocen solas (paquete y nombre), aunque sean nuevas. |
| **WhatsApp con tope** | En el trabajo: chats con personas sí, hasta `wa_budget_min` (5) min por pomodoro; estados, canales, comunidades y grupos no permitidos (`wa_groups_allowed`, `wa_groups_ok`) te sacan desde el primer momento. En actividades de chat (p. ej. «💬») no hay tope. |
| **Telón del teléfono** | Pantalla completa como la de la compu: en los descansos tapa el teléfono (llamadas, banco y WhatsApp sí; `phone_break_lock`, `break_allow`), y también muestra frases al desbloquear, preguntas, tareas vencidas y el SOS de urgencia (respiración guiada; al terminar pregunta, de un toque y opcional, dónde estás, si estás solo y si tomaste alcohol, y eso va al diario para la IA nocturna; si estás solo ofrece llamar a alguien). |
| **Frases y creencias** | «Autolavado de cerebro»: frases al desbloquear (cada `mind_unlock_every_min`), en los descansos (también en la compu) y tras una urgencia o recaída, con 👍/👎. Cada noche la IA las reescribe según tu diario y tus reacciones, y lista las creencias que estás cambiando. |
| **Diario** | Recaídas, urgencias, victorias, notas, ánimo y sugerencias, desde la app (pestaña **Yo**), la web (`/app`) o el bot (`/recai`, `/venci`); lo que detecta la vigilancia entra solo (marcado «auto»). Racha y mapa de 4 semanas. |
| **Tareas de TickTick** | El hub refleja TickTick cada 5 min (pestaña **Tareas** y web). Al vencer una tarea con hora (o a las `tasks_allday_hour` las de todo el día) sale en pantalla completa en compu y teléfono: ✅ hecha, 🎯 la hago ahora (sale en el pomodoro), ⏰ posponer (a la tercera, con razón). |
| **Preguntas** | El sistema te pregunta (qué mejorarías, cómo vas…) en pantalla completa: en el descanso o fuera del trabajo, nunca a media racha de trabajo. Cada día a media tarde, si nadie preguntó nada, pregunta por sugerencias. Las respuestas van al diario. |
| **Telegram sin ruido** | Lo proactivo del bot llega en una línea con enlace al buzón (el texto completo vive en la app); nada durante un pomodoro (se junta en un resumen para el descanso) y como mucho un mensaje cada 45 min. Los enlaces `https://pomodoro.mauriciojuanes.com/app#…` abren la app del teléfono. |
| **Ciclo nocturno** (`server/nightly/`, en el VPS) | 01:30 `reflect`: Claude lee 14 días (diario, alertas, estadísticas, reacciones, respuestas, tareas pospuestas) y reescribe frases, políticas por actividad y categorías de apps, deja preguntas para mañana y un reporte en el buzón. 02:15 `improve.sh`: Claude Code hace 1-2 mejoras de código pequeñas en la rama `auto` (nunca en lo que protege contra recaídas), y solo si pasan las pruebas se compila y publica la app. Cada 15 min `watch`: si la versión nueva falla sus pruebas en el teléfono, republica la anterior. |
| **Actualizaciones sin la compu** | El VPS compila y publica el APK (`/api/release`, `/api/apk`); el teléfono lo descarga, revisa su huella y se lo instala (la accesibilidad acepta el diálogo), fuera de los pomodoros. Si falla, espera 10 min y luego 20; a la tercera falla seguida (aunque sean versiones distintas) lo deja para el día siguiente y te avisa para instalarla desde *Ajustes → Instalar ahora*; si MIUI responde «Permission denied», desde entonces instala con su diálogo. Al arrancar corre sus **pruebas automáticas** (`SelfTest`) y las reporta. Con el teléfono conectado por ADB, la compu lo instala ella misma. Al prender la compu (`services/faro_sync.py`) sube tu código al VPS y trae las mejoras nocturnas si tu copia está limpia. |

Todo esto se configura en *Ajustes → Faro* del teléfono (o en `config.json`) y viaja entre compu y teléfono.

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

## Saltarse el pomodoro (con fricción)

En horario de trabajo (no en horas extra), **descanso ya**, **reiniciar** y **pausar** (en la compu, solo
sin el sensor: con él, pausar únicamente deja el pomodoro esperándote) no son de un toque: piden una razón
de al menos 40 letras que diga algo (el tecleo al azar como «hehdjdgshshs…», sin vocales o sin al menos 5
palabras distintas, no pasa; `core/reason.py`), escribir a mano «Es necesario de verdad, no estoy evitando el trabajo» y esperar
45 s, 90 s, 180 s… según cuántas veces llevas hoy. Hay tope diario (`skips_per_day` en la compu,
`phone_skips_per_day` en el teléfono, 2 cada uno) y el bot se entera con tu razón. En el teléfono las
acciones de la notificación desaparecen en horario de trabajo. Se apaga con `skip_friction: false`.

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

**Vigilancia de contenido en el teléfono** (accesibilidad, `ScreenWatch` + `AdultDb` + `Strikes`): revisa
todas las apps, también las nuevas. Captura cuando cambia la pantalla (otra app, scroll, contenido nuevo; a
lo más una cada 2.5 s) y cada 6 s en apps de video/redes o 15 s en las demás; si la pantalla se ve igual a
la última revisada (huella de 64 bits) no la manda. Sin capturas, compara el nombre/paquete de cada app y la
barra de direcciones de los navegadores con una lista de contenido sexual (ampliable con
`phone_adult_apps` y `phone_adult_words`). Al detectar algo: te saca, bloquea y avisa; la app suma una
advertencia y a la segunda en una semana (`phone_uninstall_strikes`) se desinstala sola (la accesibilidad
pulsa «Aceptar»). Nunca desinstala WhatsApp, apps del sistema, el navegador predeterminado, el banco ni
`phone_never_uninstall`. No revisa videollamadas (audio en modo llamada, pantallas de llamada de
WhatsApp), los mensajes de «ver una vez» ni la cámara. Las apps instaladas que estén en la lista llegan con
una advertencia; si ya tenían, se desinstalan al llegar.

```bash
./android/build.sh        # compila en el VPS e instala por ADB (para probar sin publicar)
./server/deploy.sh        # sube el hub y el ciclo nocturno (/opt/faro) al VPS
ssh contabo /opt/faro/build.sh main "notas"   # compila, prueba y publica: el teléfono se actualiza solo
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
server/                      hub (FastAPI en Docker, detrás de nginx en el VPS) y su web /app
server/nightly/              ciclo nocturno: reflexión con IA, mejora de código, compilación y rollback
android/                     app del teléfono (Kotlin, sin dependencias)
```
