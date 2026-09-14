"""Health probes must not instantiate or advance a neural circuit."""
import json
from http.server import HTTPServer
from threading import Thread
from urllib.request import urlopen, Request
from urllib.error import HTTPError
from brain_server import Handler, MODEL_ID


def test_health():
    server = HTTPServer(("127.0.0.1", 0), Handler)
    worker = Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with urlopen(f"http://127.0.0.1:{server.server_port}/health", timeout=2) as reply:
            assert reply.status == 200
            assert json.load(reply) == {"protocol": 1, "model": MODEL_ID, "ready": True}
        assert not hasattr(server, "circuit"), "Health must not run or reset neurons"
        def broken(_request):
            raise RuntimeError("isolated simulated circuit failure")
        server.step = broken
        try:
            urlopen(Request(f"http://127.0.0.1:{server.server_port}/step", data=b"{}",
                            headers={"Content-Type": "application/json"}), timeout=2)
            raise AssertionError("Failed circuit accepted")
        except HTTPError as error:
            assert error.code == 503
        try:
            urlopen(f"http://127.0.0.1:{server.server_port}/health", timeout=2)
            raise AssertionError("Failed circuit falsely reports READY")
        except HTTPError as error:
            assert error.code == 503
    finally:
        server.shutdown()
        server.server_close()
        worker.join(2)


if __name__ == "__main__":
    test_health()
    print("HEALTH PASS: identity probe without circuit construction or simulation")
