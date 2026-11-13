"""Founder-only ride summaries: hidden without the token, no places or text with it."""

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_admin_rides_hidden_without_token(client):
    assert client.get("/v1/admin/rides").status_code == 404


def test_admin_rides_needs_the_right_token(tmp_path):
    s = Settings(
        _env_file=None,
        database_url=f"sqlite:///{tmp_path}/a.db",
        hail_llm="fake",
        admin_token="t0ken-for-tests",
    )
    c = TestClient(create_app(settings=s))
    assert c.get("/v1/admin/rides", headers={"x-admin-token": "wrong"}).status_code == 404
    r = c.get("/v1/admin/rides", headers={"x-admin-token": "t0ken-for-tests"})
    assert r.status_code == 200 and r.json() == []
