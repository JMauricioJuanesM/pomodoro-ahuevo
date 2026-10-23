"""Pomodoro que en los descansos tapa la pantalla y no te deja seguir trabajando.

Capas:
  core/        lógica pura (horario, consejos, formatos); no toca el sistema ni Tk.
  services/    todo lo que habla con el sistema: GNOME, systemd, sonido, teléfono por ADB, bandeja.
  ui/          ventanas y pantallas de Tk (solo dibujan y llaman a la app).
  controller/  el comportamiento de la app (temporizador, jornada, recordatorios) como mixins
               que se combinan en `app.Pomodoro`.
"""
