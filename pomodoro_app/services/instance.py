"""Una sola instancia de la app: la segunda le pide a la primera que se muestre."""
import socket

from ..paths import SOCKET_NAME


def single_instance(auto):
    """Regresa el socket si somos la única instancia; si no, avisa a la otra y regresa None."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.bind(SOCKET_NAME)
        s.listen(2)
        return s
    except OSError:
        s.close()
    try:
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        c.connect(SOCKET_NAME)
        c.sendall(b"auto" if auto else b"show")
        c.close()
    except OSError:
        pass
    return None
