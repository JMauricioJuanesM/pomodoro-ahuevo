"""Ayudas de color y dibujo compartidas por las ventanas."""


def text_color(bg):
    """Negro o blanco, lo que se lea mejor sobre `bg`."""
    try:
        r, g, b = (int(bg[i:i + 2], 16) for i in (1, 3, 5))
    except (ValueError, TypeError):
        return "white"
    return "#111111" if (0.299 * r + 0.587 * g + 0.114 * b) > 150 else "white"


def on_dark(color):
    """Aclara los colores muy oscuros para que se vean sobre el fondo negro."""
    try:
        r, g, b = (int(color[i:i + 2], 16) for i in (1, 3, 5))
    except (ValueError, TypeError):
        return color
    if 0.299 * r + 0.587 * g + 0.114 * b >= 90:
        return color
    r, g, b = (int(x + (255 - x) * 0.45) for x in (r, g, b))
    return f"#{r:02x}{g:02x}{b:02x}"


def round_rect_points(x1, y1, x2, y2, r):
    return [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2, x2 - r, y2, x1 + r, y2,
            x1, y2, x1, y2 - r, x1, y1 + r, x1, y1]


def round_rect(canvas, x1, y1, x2, y2, r, **kw):
    return canvas.create_polygon(round_rect_points(x1, y1, x2, y2, r), smooth=True, **kw)

