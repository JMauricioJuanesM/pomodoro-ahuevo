"""Recorte circular de una ventana con la extensión SHAPE de X11 (para el widget)."""
import ctypes
import ctypes.util
import math


class _XPoint(ctypes.Structure):
    _fields_ = [("x", ctypes.c_short), ("y", ctypes.c_short)]


def make_round(win, size):
    """Recorta la ventana en forma de círculo con la extensión SHAPE de X11."""
    try:
        x11 = ctypes.CDLL(ctypes.util.find_library("X11"))
        xext = ctypes.CDLL(ctypes.util.find_library("Xext"))
    except (OSError, TypeError):
        return
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XPolygonRegion.restype = ctypes.c_void_p
    x11.XPolygonRegion.argtypes = [ctypes.POINTER(_XPoint), ctypes.c_int, ctypes.c_int]
    x11.XDestroyRegion.argtypes = [ctypes.c_void_p]
    x11.XFlush.argtypes = [ctypes.c_void_p]
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    xext.XShapeCombineRegion.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
                                         ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
    dpy = x11.XOpenDisplay(None)
    if not dpy:
        return
    n, r = 180, size / 2
    pts = (_XPoint * n)(*[_XPoint(round(r + r * math.cos(2 * math.pi * i / n)),
                                  round(r + r * math.sin(2 * math.pi * i / n))) for i in range(n)])
    region = x11.XPolygonRegion(pts, n, 0)
    xext.XShapeCombineRegion(dpy, int(win.wm_frame(), 16), 0, 0, 0, region, 0)  # ShapeBounding, ShapeSet
    x11.XDestroyRegion(region)
    x11.XFlush(dpy)
    x11.XCloseDisplay(dpy)
