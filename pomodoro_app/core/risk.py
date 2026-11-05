"""Contenido de riesgo en texto (título de la ventana o lo que se lee en ella): lógica pura y barata.

Regresa "explicit", "suggestive", None (nada) o "unsure" (una sola pista suave: que decida la IA).
"""
import re

EXPLICIT = re.compile(
    r"\b(porn\w*|xxx+|xvideos|xnxx|pornhub|xhamster|redtube|youporn|spankbang|eporner|onlyfans|fansly|"
    r"hentai|nhentai|rule ?34|r34|nsfw|nudes|desnud[oa]s?|encuerad[oa]s?|cogiendo|follando|"
    r"chaturbate|stripchat|camsoda|bongacams|cam ?girls?|webcam sex|milfs?|pornstar|nopor|"
    r"fapello|erome|leaked (pics|nudes|video)|sex ?tape|sexting|blowjob|cum\w*shot|boobs|tetas)\b", re.I)
SUGGESTIVE = re.compile(
    r"(\bsexy\b|\bnude\b|\bnaked\b|\bescorts\b|escort service|\bsexo\b|\bsex\b|\berótic\w*|\berotic\w*|lencer[ií]a|"
    r"lingerie|bikini|\bhot girls?\b|chicas hot|"
    r"thirst trap|seduct\w*|sedu[cz]\w*|\b18\s?\+|\+\s?18\b|ai girlfriend|virtual girlfriend|novia virtual|"
    r"\bai companion|romantic ai|candy\.ai|character\.ai|janitor ?ai|crushon|spicychat|replika|kupid|"
    r"\bchat with [^|]{2,50}\b(ai|bot)\b|enjoy ai chat|ai chat free|\broleplay\b|\brol (er[oó]tico|picante)|"
    r"\bkinky?\b|\bfetish\w*|\bfetiche\w*)", re.I)


def _hits(rx, text):
    return {m.group(0).lower() for m in rx.finditer(text or "")}


def title_risk(title):
    """Del título basta una palabra: es lo que buscaste o la página que abriste."""
    if _hits(EXPLICIT, title):
        return "explicit"
    if _hits(SUGGESTIVE, title):
        return "suggestive"
    return None


def text_risk(text):
    """Del texto de la página hacen falta varias pistas (una palabra suelta puede ser una noticia)."""
    ex, sug = _hits(EXPLICIT, text), _hits(SUGGESTIVE, text)
    if len(ex) >= 2:
        return "explicit"
    if ex or len(sug) >= 2:
        return "suggestive"
    return "unsure" if sug else None
