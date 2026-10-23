#!/usr/bin/env python3
"""Pomodoro que en los descansos tapa la pantalla y no te deja seguir trabajando.

Durante el descanso:
  - una ventana a pantalla completa cubre cada monitor y no se puede cerrar;
  - (opcional) se desactivan los atajos de GNOME (Super, Alt+Tab, …);
  - (opcional) si logras sacar la ventana del frente, se bloquea la sesión.

Además:
  - horario semanal (como una hoja de cálculo): cada hora tiene una actividad que es
    trabajo, enfocado (como trabajo, pero los descansos se pueden saltar: reuniones),
    descanso programado (bloquea la pantalla, p. ej. la comida) o fuera de trabajo;
  - recordatorios de una vez o recurrentes, con o sin duración y bloqueo;
  - descansos largos "flexibles": bloqueados al inicio y al final, libres a la mitad;
  - jornada: arranca sola al empezar el trabajo del día y, al terminar, se pone
    pesada (trabajo ÷2, descansos ×2) y ofrece apagar la compu;
  - teléfono por ADB: avisos, bloqueo de pantalla al terminar el descanso y vigilancia
    durante el trabajo;
  - widget redondo y pequeño que se queda encima de todo.

`pomodoro.py --auto` es como lo lanza el inicio de sesión / el timer de systemd.
El código vive en el paquete `pomodoro_app/`.
"""
import atexit
import os
import signal
import sys

from pomodoro_app.app import Pomodoro
from pomodoro_app.services.instance import single_instance
from pomodoro_app.services.shortcuts import restore_shortcuts


def main():
    auto = "--auto" in sys.argv
    sock = single_instance(auto)
    if not sock:
        return
    # Si la app murió a media pausa, devolver los atajos de GNOME.
    restore_shortcuts()
    atexit.register(restore_shortcuts)
    signal.signal(signal.SIGTERM, lambda *_: (restore_shortcuts(), os._exit(0)))
    Pomodoro(sock, auto).mainloop()


if __name__ == "__main__":
    main()
