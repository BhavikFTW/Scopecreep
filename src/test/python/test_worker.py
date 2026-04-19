import io
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../../main/resources/sidecar"))

from starlette.testclient import TestClient
from worker import app

client = TestClient(app)


def test_upload_returns_ok():
    response = client.post(
        "/upload",
        files={
            "schematic": ("schematic.png", io.BytesIO(b"fake schematic"), "image/png"),
            "pcb": ("pcb.png", io.BytesIO(b"fake pcb"), "image/png"),
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "schematic" in body
    assert "pcb" in body


def test_upload_missing_file_returns_422():
    response = client.post(
        "/upload",
        files={
            "schematic": ("schematic.png", io.BytesIO(b"fake"), "image/png"),
        },
    )
    assert response.status_code == 422
