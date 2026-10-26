// Vigía de pantalla del Pomodoro (GNOME Shell 45+).
//
// En Wayland ninguna app puede capturar la pantalla sin pedir permiso cada vez; una extensión sí, porque
// corre dentro de GNOME Shell. Cada INTERVAL_S, si la ventana al frente es de riesgo, la captura, la
// reduce a SIDE px y la deja en $XDG_RUNTIME_DIR/pomodoro-vigia/shot.jpg con meta.json (app y título).
// El Pomodoro (services/screenwatch.py) la manda a revisar al servidor, la borra y, si hay que actuar,
// escribe `close` en action: aquí se cierra esa ventana.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GdkPixbuf from 'gi://GdkPixbuf';
import Shell from 'gi://Shell';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

Gio._promisify(Shell.Screenshot.prototype, 'screenshot_stage_to_content');
Gio._promisify(Shell.Screenshot, 'composite_to_stream');

const INTERVAL_S = 6;
const SIDE = 320;
const DEFAULT_RISK = 'brave|chrom|firefox|opera|edge|vivaldi|epiphany|tor|telegram|discord|eog|loupe|shotwell|gthumb|vlc|mpv|totem|celluloid';

export default class VigiaExtension extends Extension {
    enable() {
        this._dir = GLib.build_filenamev([GLib.get_user_runtime_dir(), 'pomodoro-vigia']);
        GLib.mkdir_with_parents(this._dir, 0o700);
        this._risk = new RegExp(this._readRisk(), 'i');
        this._busy = false;
        this._timer = GLib.timeout_add_seconds(GLib.PRIORITY_LOW, INTERVAL_S, () => {
            this._tick();
            return GLib.SOURCE_CONTINUE;
        });
        this._actionTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
            this._checkAction();
            return GLib.SOURCE_CONTINUE;
        });
    }

    disable() {
        GLib.source_remove(this._timer);
        GLib.source_remove(this._actionTimer);
        this._timer = this._actionTimer = null;
    }

    _readRisk() {
        try {
            const path = GLib.build_filenamev([GLib.get_user_config_dir(), 'pomodoro-ahuevo', 'vigia.json']);
            const [, bytes] = GLib.file_get_contents(path);
            return JSON.parse(new TextDecoder().decode(bytes)).risk || DEFAULT_RISK;
        } catch {
            return DEFAULT_RISK;
        }
    }

    _focused() {
        const w = global.display.focus_window;
        if (!w || Main.sessionMode.isLocked || w.minimized)
            return null;
        const cls = `${w.get_wm_class() ?? ''} ${w.get_gtk_application_id() ?? ''}`;
        return this._risk.test(cls) ? w : null;
    }

    async _tick() {
        const w = this._focused();
        if (!w || this._busy)
            return;
        this._busy = true;
        try {
            const r = w.get_frame_rect();
            const shooter = new Shell.Screenshot();
            const [content] = await shooter.screenshot_stage_to_content();
            const stream = Gio.MemoryOutputStream.new_resizable();
            const pixbuf = await Shell.Screenshot.composite_to_stream(
                content.get_texture(), r.x, r.y, r.width, r.height, 1, null, 0, 0, 1, stream);
            stream.close(null);
            const k = SIDE / Math.max(pixbuf.get_width(), pixbuf.get_height());
            const small = pixbuf.scale_simple(Math.max(1, Math.round(pixbuf.get_width() * k)),
                Math.max(1, Math.round(pixbuf.get_height() * k)), GdkPixbuf.InterpType.BILINEAR);
            const [, jpeg] = small.save_to_bufferv('jpeg', ['quality'], ['70']);
            const meta = JSON.stringify({ts: Date.now() / 1000, app: w.get_wm_class(), title: w.get_title()});
            this._write('shot.jpg', jpeg);
            this._write('meta.json', new TextEncoder().encode(meta));
        } catch (e) {
            logError(e, 'Vigía: no pude capturar');
        } finally {
            this._busy = false;
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
        if (action === 'close') {
            const w = global.display.focus_window;
            if (w && this._risk.test(`${w.get_wm_class() ?? ''} ${w.get_gtk_application_id() ?? ''}`))
                w.delete(global.get_current_time());
        }
    }
}
