import sys
import os
import socket
import time


def _wait_for_port_free(host, port, timeout=5):
    """Wait until the port is free (previous process released it)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((host, port))
            sock.close()
            return True
        except OSError:
            sock.close()
            time.sleep(0.3)
    return False


def start_server(host, port):
    """Entry point called by Chaquopy from ServerProcessManager.kt."""
    # Inject the app files directory into sys.path so server.py can be imported
    app_dir = os.environ.get("FINTRACK_APP_DIR", "")
    if app_dir and app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    host = str(host)
    port = int(port)

    # Wait for port to be free (previous process may still hold it after SIGKILL)
    _wait_for_port_free(host, port)

    import uvicorn
    from server import app as fastapi_app

    uvicorn.run(fastapi_app, host=host, port=port, log_level="info")
