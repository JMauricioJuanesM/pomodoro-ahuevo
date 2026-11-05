// Vigía de pantalla del Pomodoro (GNOME Shell 45+).
//
// En Wayland ninguna app puede capturar la pantalla sin pedir permiso cada vez; una extensión sí, porque
// corre dentro de GNOME Shell. Cada INTERVAL_S, si la ventana al frente es de riesgo, la captura, la
// reduce a SIDE px y la deja en $XDG_RUNTIME_DIR/pomodoro-vigia/shot.jpg con meta.json (app y título).
// El Pomodoro (services/screenwatch.py) la manda a revisar al servidor, la borra y, si hay que actuar,
// escribe en `action` qué hacer con la ventana al frente: `minimize`, `close-tab` (Ctrl+W en un navegador; en
// otra app, cerrar la ventana), `close-app` (todas sus ventanas; si no se cierran, se mata) o `close` (viejo).
//
// Mientras el Pomodoro tiene una pantalla completa (descanso…), toca `overlay` cada pocos segundos: aquí no se
// deja abrir la vista de actividades (la tecla de la lupita, Super…), desde donde se podía arrastrar la
// pantalla del descanso a otro monitor y dejar uno libre.
//
// Además, cada FOCUS_S deja focus.json con la ventana al frente (de cualquier app) y los ms sin tocar
// teclado ni mouse: el sensor de presencia del Pomodoro (services/presence.py) lo usa para saber si
// estás trabajando de verdad.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GdkPixbuf from 'gi://GdkPixbuf';
import Shell from 'gi://Shell';
import Clutter from 'gi://Clutter';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

Gio._promisify(Shell.Screenshot.prototype, 'screenshot_stage_to_content');
Gio._promisify(Shell.Screenshot, 'composite_to_stream');

const INTERVAL_S = 6;
const FOCUS_S = 2;
const SIDE = 320;
const TEXT_SIDE = 1800;     // para leer el texto (OCR) hace falta más resolución
const VERSION = 2;          // viaja en focus.json: el Pomodoro sabe qué acciones entiende esta extensión
const OVERLAY_FRESH_S = 6;  // `overlay` más viejo que esto: el Pomodoro ya no tiene pantalla completa
const BROWSER = /brave|chrom|firefox|opera|edge|vivaldi|epiphany|tor/i;
const DEFAULT_RISK = 'brave|chrom|firefox|opera|edge|vivaldi|epiphany|tor|telegram|discord|eog|loupe|shotwell|gthumb|vlc|mpv|totem|celluloid';

export default class VigiaExtension extends Extension {
    enable() {
        this._dir = GLib.build_filenamev([GLib.get_user_runtime_dir(), 'pomodoro-vigia']);
        GLib.mkdir_with_parents(this._dir, 0o700);
        const cfg = this._readConfig();
        this._risk = new RegExp(cfg.risk || DEFAULT_RISK, 'i');
        this._debug = Boolean(cfg.debug);
        this._busy = false;
        this._timer = GLib.timeout_add_seconds(GLib.PRIORITY_LOW, INTERVAL_S, () => {
            this._tick();
            return GLib.SOURCE_CONTINUE;
        });
        this._focusTimer = GLib.timeout_add_seconds(GLib.PRIORITY_LOW, FOCUS_S, () => {
            this._writeFocus();
            return GLib.SOURCE_CONTINUE;
        });
        this._actionTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
            this._checkAction();
            this._guardOverlay();
            this._textShot();
            return GLib.SOURCE_CONTINUE;
        });
        // La vista de actividades se cierra en cuanto intenta abrirse si hay pantalla de descanso.
        this._overviewId = Main.overview.connect('showing', () => {
            if (this._overlayActive())
                GLib.idle_add(GLib.PRIORITY_HIGH, () => {
                    Main.overview.hide();
                    return GLib.SOURCE_REMOVE;
                });
        });
    }

    disable() {
        GLib.source_remove(this._timer);
        GLib.source_remove(this._focusTimer);
        GLib.source_remove(this._actionTimer);
        Main.overview.disconnect(this._overviewId);
        this._timer = this._focusTimer = this._actionTimer = this._overviewId = null;
        this._kbd = null;
    }

    _readConfig() {
        try {
            const path = GLib.build_filenamev([GLib.get_user_config_dir(), 'pomodoro-ahuevo', 'vigia.json']);
            const [, bytes] = GLib.file_get_contents(path);
            return JSON.parse(new TextDecoder().decode(bytes));
        } catch {
            return {};
        }
    }

    _focused() {
        let w = global.display.focus_window;
        if (!w && this._debug)   // solo para pruebas (un GNOME anidado no le da el foco a nadie)
            w = global.get_window_actors().map(a => a.meta_window).filter(x => x.get_title() !== null).pop() ?? null;
        if (!w || Main.sessionMode.isLocked || w.minimized)
            return null;
        const cls = `${w.get_wm_class() ?? ''} ${w.get_gtk_application_id() ?? ''}`;
        return this._risk.test(cls) ? w : null;
    }

    _writeFocus() {
        try {
            const w = global.display.focus_window;
            let idle = null;
            try {
                idle = global.backend.get_core_idle_monitor().get_idletime();
            } catch {}
            this._write('focus.json', new TextEncoder().encode(JSON.stringify({
                ts: Date.now() / 1000, v: VERSION, idle_ms: idle, locked: Main.sessionMode.isLocked,
                app: w ? (w.get_wm_class() ?? '') : '', app_id: w ? (w.get_gtk_application_id() ?? '') : '',
                title: w ? (w.get_title() ?? '') : '', fullscreen: Boolean(w?.is_fullscreen()),
                minimized: Boolean(w?.minimized)})));
        } catch (e) {
            logError(e, 'Vigía: no pude escribir focus.json');
        }
    }

    async _tick() {
        if (this._debug) {
            const f = global.display.focus_window;
            this._write('status.json', new TextEncoder().encode(JSON.stringify({
                focus: f ? `${f.get_wm_class()} ${f.get_gtk_application_id()}` : null,
                overview: Main.overview.visible, locked: Main.sessionMode.isLocked})));
        }
        const w = this._focused();
        if ((!w && !this._debug) || this._busy)
            return;
        this._busy = true;
        try {
            const small = await this._capture(w, SIDE);
            const [, jpeg] = small.save_to_bufferv('jpeg', ['quality'], ['70']);
            const meta = JSON.stringify({ts: Date.now() / 1000, app: w?.get_wm_class() ?? 'debug', title: w?.get_title() ?? ''});
            this._write('shot.jpg', jpeg);
            this._write('meta.json', new TextEncoder().encode(meta));
        } catch (e) {
            logError(e, 'Vigía: no pude capturar');
        } finally {
            this._busy = false;
        }
    }

    // Captura de la ventana `w` (sin ventana, solo en pruebas: toda la pantalla), reducida a `side` px de lado
    // como mucho.
    async _capture(w, side) {
        const r = w ? w.get_frame_rect() : {x: 0, y: 0, width: global.stage.width, height: global.stage.height};
        const shooter = new Shell.Screenshot();
        const [content] = await shooter.screenshot_stage_to_content();
        const stream = Gio.MemoryOutputStream.new_resizable();
        const pixbuf = await Shell.Screenshot.composite_to_stream(
            content.get_texture(), r.x, r.y, r.width, r.height, 1, null, 0, 0, 1, stream);
        stream.close(null);
        const k = Math.min(1, side / Math.max(pixbuf.get_width(), pixbuf.get_height()));
        if (k === 1)
            return pixbuf;
        return pixbuf.scale_simple(Math.max(1, Math.round(pixbuf.get_width() * k)),
            Math.max(1, Math.round(pixbuf.get_height() * k)), GdkPixbuf.InterpType.BILINEAR);
    }

    // El Pomodoro pide leer el texto de la ventana al frente (services/textwatch.py): captura grande en PNG,
    // que el Pomodoro pasa por OCR y borra.
    async _textShot() {
        const req = Gio.File.new_for_path(GLib.build_filenamev([this._dir, 'textreq']));
        if (!req.query_exists(null))
            return;
        try {
            req.delete(null);
        } catch {
            return;
        }
        const w = global.display.focus_window;
        if (!w || Main.sessionMode.isLocked || w.minimized)
            return;
        try {
            const big = await this._capture(w, TEXT_SIDE);
            const [, png] = big.save_to_bufferv('png', [], []);
            this._write('text.png', png);
        } catch (e) {
            logError(e, 'Vigía: no pude capturar para leer el texto');
        }
    }

    _write(name, bytes) {
        const tmp = GLib.build_filenamev([this._dir, `${name}.tmp`]);
        GLib.file_set_contents(tmp, bytes);
        Gio.File.new_for_path(tmp).move(Gio.File.new_for_path(GLib.build_filenamev([this._dir, name])),
            Gio.FileCopyFlags.OVERWRITE, null, null);
    }

    _checkAction() {
        const file = Gio.File.new_for_path(GLib.build_filenamev([this._dir, 'action']));
        if (!file.query_exists(null))
            return;
        let action = '';
        try {
            action = new TextDecoder().decode(file.load_contents(null)[1]).trim();
            file.delete(null);
        } catch {
            return;
        }
        // Cualquier ventana al frente (no solo las de riesgo), aunque la sesión se esté bloqueando.
        const w = global.display.focus_window;
        if (!w)
            return;
        if (action === 'minimize') {
            if (w.can_minimize())
                w.minimize();
        } else if (action === 'close-tab') {
            if (BROWSER.test(`${w.get_wm_class() ?? ''} ${w.get_gtk_application_id() ?? ''}`))
                this._closeTab(w);
            else
                w.delete(global.get_current_time());
        } else if (action === 'close-app' || action === 'close') {
            this._closeApp(w);
        }
    }

    _closeTab(w) {
        w.activate(global.get_current_time());
        GLib.timeout_add(GLib.PRIORITY_DEFAULT, 150, () => {
            try {
                if (!this._kbd)
                    this._kbd = Clutter.get_default_backend().get_default_seat()
                        .create_virtual_device(Clutter.InputDeviceType.KEYBOARD_DEVICE);
                const t = GLib.get_monotonic_time();
                this._kbd.notify_keyval(t, Clutter.KEY_Control_L, Clutter.KeyState.PRESSED);
                this._kbd.notify_keyval(t + 10, Clutter.KEY_w, Clutter.KeyState.PRESSED);
                this._kbd.notify_keyval(t + 20, Clutter.KEY_w, Clutter.KeyState.RELEASED);
                this._kbd.notify_keyval(t + 30, Clutter.KEY_Control_L, Clutter.KeyState.RELEASED);
            } catch (e) {
                logError(e, 'Vigía: no pude cerrar la pestaña; cierro la ventana');
                w.delete(global.get_current_time());
            }
            return GLib.SOURCE_REMOVE;
        });
    }

    _closeApp(w) {
        const cls = w.get_wm_class();
        const same = global.get_window_actors().map(a => a.meta_window)
            .filter(x => x === w || (cls && x.get_wm_class() === cls));
        for (const x of same)
            x.delete(global.get_current_time());
        // Si alguna pregunta «¿cerrar todas las pestañas?» o no hace caso, se mata.
        GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 4, () => {
            const alive = new Set(global.get_window_actors().map(a => a.meta_window));
            for (const x of same)
                if (alive.has(x))
                    x.kill();
            return GLib.SOURCE_REMOVE;
        });
    }

    _overlayActive() {
        try {
            const info = Gio.File.new_for_path(GLib.build_filenamev([this._dir, 'overlay']))
                .query_info('time::modified', Gio.FileQueryInfoFlags.NONE, null);
            return Date.now() / 1000 - info.get_modification_date_time().to_unix() < OVERLAY_FRESH_S;
        } catch {
            return false;
        }
    }

    _guardOverlay() {
        if (Main.overview.visible && this._overlayActive())
            Main.overview.hide();
    }
}
