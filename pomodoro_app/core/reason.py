"""¿La razón para saltarse el pomodoro dice algo? Si cualquier tecleo pasa, deja de servir como freno."""
import re

VOWELS = "aeiouáéíóúüy"
MIN_WORDS = 5


def nonsense(reason):
    """True si parece tecleo al azar («hehdjdgshshs…»): menos de MIN_WORDS palabras distintas con vocales y sin
    sílabas repetidas («shshsh», «aaaa»), pocas vocales o mucho texto que no son palabras. Mismo criterio que
    `nonsense` en TimerScreen.kt del teléfono."""
    words = re.findall(r"[^\W\d_]+", reason.lower())
    real = [w for w in words if any(c in VOWELS for c in w) and len(w) <= 20 and not re.search(r"(.{1,3})\1\1", w)]
    letters = sum(map(len, words))
    vowels = sum(c in VOWELS for w in words for c in w)
    return len(set(real)) < MIN_WORDS or vowels < 0.3 * letters or sum(map(len, real)) < 0.8 * letters
