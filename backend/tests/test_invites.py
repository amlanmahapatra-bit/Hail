"""Invite-only beta: new devices need a code; anyone can ask for one."""

from app.store import Store


def _register(client, code=None):
    body = {"platform": "android", "capabilities": {"can_check_fares": True, "can_book": True}}
    if code is not None:
        body["invite_code"] = code
    return client.post("/v1/devices", json=body)


def test_open_when_invites_are_off(client):
    assert _register(client).status_code == 201


def test_code_required_when_invites_are_on(make_client, clock):
    client = make_client(require_invite=True)
    r = _register(client)
    assert r.status_code == 403 and r.json()["detail"] == "invite_required"
    assert _register(client, "HAIL-NOPE22").json()["detail"] == "invalid_invite"


def test_one_use_code_works_once_case_insensitive(make_client, clock, settings):
    client = make_client(require_invite=True)
    code = Store(settings.database_url).create_invite(clock(), note="test")
    assert code.startswith("HAIL-")
    assert _register(client, code.lower()).status_code == 201
    assert _register(client, code).json()["detail"] == "invalid_invite"


def test_multi_use_code(make_client, clock, settings):
    client = make_client(require_invite=True)
    code = Store(settings.database_url).create_invite(clock(), max_uses=2)
    assert _register(client, code).status_code == 201
    assert _register(client, code).status_code == 201
    assert _register(client, code).status_code == 403


def test_invite_request_and_approval(client, clock, settings):
    r = client.post("/v1/invite-requests", json={"contact": "9876543210", "city": "Bangalore"})
    assert r.status_code == 201 and r.json() == {"status": "received"}
    # the same contact again doesn't create a second request
    client.post("/v1/invite-requests", json={"contact": "9876543210"})
    store = Store(settings.database_url)
    pending = store.list_invite_requests("pending")
    assert len(pending) == 1 and pending[0].city == "Bangalore"
    code = store.approve_invite_request(pending[0].id, clock())
    assert code and store.list_invite_requests("invited")[0].invite_code == code
    assert store.approve_invite_request(pending[0].id, clock()) == code  # idempotent


def test_invite_request_validation_and_daily_cap(make_client):
    client = make_client(invite_requests_per_day=1)
    assert client.post("/v1/invite-requests", json={"contact": "x"}).status_code == 422
    assert (
        client.post("/v1/invite-requests", json={"contact": "a@b.co", "extra": 1}).status_code
        == 422
    )
    assert client.post("/v1/invite-requests", json={"contact": "a@b.co"}).status_code == 201
    assert client.post("/v1/invite-requests", json={"contact": "c@d.co"}).status_code == 429
