"""Clasificación de lo que tienes al frente respecto a la actividad del horario (lógica pura).

Veredictos: "work" (sí es lo que toca), "neutral" (no suma ni resta: archivos, ajustes, música…),
"distract" (no es lo que toca). Las reglas cubren lo obvio y salen gratis; lo dudoso (sobre todo
páginas de un navegador) regresa None y lo decide el juez con IA (`services/judge.py`), una sola vez
por título: la respuesta se recuerda.
"""
import re

WORK_APPS = ("code", "codium", "cursor", "jetbrains", "pycharm", "idea", "webstorm", "phpstorm", "goland",
             "clion", "rider", "datagrip", "android-studio", "studio", "gnome-terminal", "terminal", "kgx",
             "console", "ptyxis", "tilix", "kitty", "alacritty", "wezterm", "foot", "konsole", "xterm",
             "libreoffice", "soffice", "evince", "papers", "okular", "xournal", "zed", "sublime", "gedit",
             "texteditor", "text-editor", "kate", "vim", "emacs", "dbeaver", "postman", "insomnia", "figma", "obsidian",
             "zotero", "anki", "drawio", "inkscape", "gimp", "qgis", "claude", "remmina", "zoom", "teams",
             "slack", "meld", "gitg", "pgadmin")
NEUTRAL_APPS = ("nautilus", "files", "gnome-control-center", "settings", "pavucontrol", "spotify",
                "rhythmbox", "calculator", "calendar", "clocks", "tk", "pomodoro", "gnome-shell", "evolution",
                "thunderbird", "keepass", "bitwarden", "1password", "seahorse", "software")
DISTRACT_APPS = ("steam", "lutris", "heroic", "minecraft", "retroarch", "itch", "bottles", "stremio",
                 "popcorn", "hypnotix")
BROWSERS = ("brave", "chrom", "firefox", "opera", "edge", "vivaldi", "epiphany", "librewolf", "zen", "tor")

# En el título de una página: lo que casi siempre es trabajo y lo que casi siempre es distracción.
WORK_WORDS = ("github", "gitlab", "bitbucket", "stack overflow", "stackoverflow", "documentation", "docs",
              "api reference", "jira", "openproject", "infotec", "localhost", "127.0.0.1", "google docs",
              "google sheets", "hojas de cálculo", "documentos de google", "notion", "figma", "mdn", "pypi",
              "npm", "aws", "console", "chatgpt", "claude", "gemini", "overleaf", "colab", "jupyter",
              "kotlin", "python", "android developers", "classroom", "moodle", "coursera", "udemy", "platzi",
              "ticktick", "outlook", "gmail", "calendar", "calendario", "meet", "zoom")
DISTRACT_WORDS = ("netflix", "twitch", "prime video", "disney+", "crunchyroll", "tiktok", "instagram",
                  "facebook", "reddit", "9gag", "pinterest", "x.com", "/ x", "twitter", "tumblr",
                  "mercado libre", "amazon.com.mx", "shein", "temu", "juegos", "poki", "friv", "chess.com",
                  "lichess", "fútbol en vivo", "espn", "marca.com", "memes", "anime", "manga", "webtoon")
# YouTube puede ser un tutorial o puro scroll: lo decide el juez según el título y la actividad.
CALL_WORDS = ("meet", "zoom", "teams", "llamada", "reunión", "daily", "huddle", "jitsi")

BROWSER_SUFFIX = re.compile(r"\s+[-—–]\s+(brave|google chrome|chromium|mozilla firefox|firefox|opera|"
                            r"microsoft​? edge|vivaldi|epiphany|librewolf|zen browser)\s*$", re.I)
COUNTER = re.compile(r"^\(\d+\+?\)\s*|\s*\(\d+\+?\)$")


def _has(text, words):
    return any(w in text for w in words)


def _app_is(app, names):
    """¿Alguna parte del nombre de la app (org.gnome.Console → org, gnome, console) empieza con uno de
    estos? Por partes, para que «tor» no atrape a «editor»."""
    parts = re.split(r"[.\-_ ]+", app)
    return any(p.startswith(n) for p in parts if p for n in names)


def is_browser(app):
    return _app_is((app or "").lower(), BROWSERS)


def norm_title(title):
    """Título sin el nombre del navegador ni contadores de notificaciones, en minúsculas y corto."""
    t = COUNTER.sub("", BROWSER_SUFFIX.sub("", title or "")).strip().lower()
    return re.sub(r"\s+", " ", t)[:140]


def is_call(app, title):
    """¿Una videollamada? Ahí estar quieto (escuchando) cuenta como trabajo."""
    app, t = (app or "").lower(), (title or "").lower()
    return _app_is(app, ("zoom", "teams", "slack", "jitsi")) or (is_browser(app) and _has(t, CALL_WORDS))


def classify(app, title):
    """"work" | "neutral" | "distract" con reglas, o None si hay que preguntarle al juez."""
    app = (app or "").lower()
    t = norm_title(title)
    if not app:
        return None
    if is_browser(app):
        if not t or t in ("new tab", "nueva pestaña", "pestaña nueva"):
            return "neutral"
        if _has(t, DISTRACT_WORDS):
            return "distract"
        if _has(t, WORK_WORDS):
            return "work"
        return None
    if _app_is(app, DISTRACT_APPS):
        return "distract"
    if _app_is(app, WORK_APPS):
        return "work"
    if _app_is(app, NEUTRAL_APPS):
        return "neutral"
    return None


def cache_key(activity_id, app, title):
    """Llave para recordar un veredicto: la página (o la app) en esa actividad."""
    app = (app or "").lower()
    what = norm_title(title) if is_browser(app) else app
    return f"{activity_id or '-'}|{'web' if is_browser(app) else 'app'}|{what}"
