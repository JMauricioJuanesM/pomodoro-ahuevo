"""Detección de desnudez en capturas de pantalla (teléfono y compu) con NudeNet, en memoria.

Las imágenes llegan reducidas (~320 px), se analizan y se descartan: no se guardan ni se registran.
Niveles:
  - "explicit": desnudez explícita;
  - "suggestive": contenido sugerente (cubierto pero insinuante);
  - "none".
"""
from nudenet import NudeDetector

EXPLICIT = {"FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "FEMALE_BREAST_EXPOSED",
            "ANUS_EXPOSED", "BUTTOCKS_EXPOSED"}
SUGGESTIVE = {"FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED", "FEMALE_GENITALIA_COVERED", "ANUS_COVERED"}
EXPLICIT_MIN = 0.45
SUGGESTIVE_MIN = 0.55

_detector = None


def _get():
    global _detector
    if _detector is None:
        _detector = NudeDetector()
    return _detector


def classify(jpeg: bytes):
    """{"level": "explicit" | "suggestive" | "none", "labels": [(clase, score)]}"""
    found = [(d["class"], round(d["score"], 2)) for d in _get().detect(jpeg)]
    if any(c in EXPLICIT and s >= EXPLICIT_MIN for c, s in found):
        level = "explicit"
    elif any(c in SUGGESTIVE and s >= SUGGESTIVE_MIN for c, s in found):
        level = "suggestive"
    else:
        level = "none"
    return {"level": level, "labels": [x for x in found if x[0] in EXPLICIT | SUGGESTIVE]}
