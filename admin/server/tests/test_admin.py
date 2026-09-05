"""End-to-end API tests for the Arise Admin Control Plane."""
import os
import tempfile
import time

import pyotp
import pytest
from fastapi.testclient import TestClient

os.environ["ARISE_DB"] = os.path.join(tempfile.mkdtemp(), "test_admin.db")
os.environ["ARISE_ADMIN_EMAIL"] = "boss@arise.test"
os.environ["ARISE_ADMIN_PASSWORD"] = "CorrectHorseBattery99"
os.environ["ARISE_ADMIN_SECRET"] = "x" * 40
os.environ["ARISE_CLIENT_SECRET"] = "client-secret-test"

from server.app import app  # noqa: E402

@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c

_boss_secret = None  # set after TOTP is enabled so later tests can complete MFA

def login(client, email="boss@arise.test", pw="CorrectHorseBattery99"):
    r = client.post("/v1/auth/login", json={"email": email, "password": pw})
    assert r.status_code == 200, r.text
    body = r.json()
    if body.get("challenge") == "totp":
        assert _boss_secret, "need totp secret to complete login"
        code = pyotp.TOTP(_boss_secret).now()
        r2 = client.post("/v1/auth/totp", headers={"X-Login-Email": email}, json={"code": code})
        assert r2.status_code == 200
        return r2.json()["token"]
    return body["token"]

def h(token):
    return {"Authorization": f"Bearer {token}"}

CS = {"X-Arise-Secret": "client-secret-test"}

def test_health(client):
    r = client.get("/healthz")
    assert r.status_code == 200 and r.json()["ok"]

def test_login_bad_password(client):
    r = client.post("/v1/auth/login", json={"email": "boss@arise.test", "password": "wrong"})
    assert r.status_code == 401

def test_login_and_me(client):
    tok = login(client)
    me = client.get("/v1/admins/me", headers=h(tok))
    assert me.status_code == 200
    assert me.json()["role"] == "super_admin"

def test_release_lifecycle_and_permissions(client):
    boss = login(client)
    # viewer can read but not publish
    r = client.post("/v1/admins", headers=h(boss), json={
        "email": "viewer@arise.test", "password": "viewerpass123", "role": "analytics_viewer"})
    assert r.status_code == 200
    vtok = login(client, "viewer@arise.test", "viewerpass123")
    denied = client.post("/v1/releases", headers=h(vtok), json={
        "version": "9.9.9", "stage": "stable", "rollout_percent": 100})
    assert denied.status_code == 403
    # super admin publishes
    ok = client.post("/v1/releases", headers=h(boss), json={
        "version": "0.1.0", "min_version": "0.1.0", "stage": "beta",
        "rollout_percent": 25, "notes": "first public beta", "apk_url": "https://cdn.example/arise-0.1.0.apk",
        "apk_sha256": "aa" * 32})
    assert ok.status_code == 200
    # app-side config exposes flags & min version
    cfg = client.get("/v1/app/config?app=com.arise.assistant&version=0.0.9", headers=CS)
    assert cfg.status_code == 200
    data = cfg.json()
    assert data["config"]["force_update"] is True
    assert data["config"]["min_version"] == "0.1.0"
    # client secret required
    assert client.get("/v1/app/config").status_code == 401

def test_feature_flags(client):
    boss = login(client)
    client.put("/v1/flags/cloud_ai", headers=h(boss), json={"enabled": True, "rollout_percent": 100})
    flags = client.get("/v1/flags", headers=h(boss)).json()["flags"]
    assert any(f["name"] == "cloud_ai" and f["enabled"] for f in flags)
    cfg = client.get("/v1/app/config", headers=CS).json()
    assert "cloud_ai" in cfg["config"]["feature_flags"]

def test_announcements_and_ai(client):
    boss = login(client)
    client.post("/v1/announcements", headers=h(boss),
                json={"title": "Welcome", "body": "v0.1 beta is live", "severity": "info", "active": True})
    ann = client.get("/v1/app/config", headers=CS).json()["announcement"]
    assert ann and ann["title"] == "Welcome"
    client.post("/v1/ai-profiles", headers=h(boss),
                json={"name": "default", "endpoint": "https://api.example.com/v1/chat/completions",
                      "fast_model": "fast-x", "power_model": "power-y"})
    assert len(client.get("/v1/ai-profiles", headers=h(boss)).json()["profiles"]) == 1

def test_metrics_crash_and_analytics(client):
    boss = login(client)
    client.post("/v1/app/metrics", headers=CS, json={
        "device_hash": "h1", "app_version": "0.1.0",
        "events": {"wake_latency_ms": 420, "stt_latency_ms": 1800}})
    client.post("/v1/app/crash", headers=CS, json={
        "device_hash": "h2", "app_version": "0.1.0", "android_sdk": 34, "model": "Pixel", "error_class": "X"})
    s = client.get("/v1/analytics/summary", headers=h(boss)).json()
    assert s["metrics"]["wake_latency_ms"] >= 420
    assert s["crashes"]["reports"] >= 1
    assert len(client.get("/v1/analytics/crashes", headers=h(boss)).json()["crashes"]) >= 1

def test_subs(client):
    boss = login(client)
    client.post("/v1/subs", headers=h(boss), json={"device_id": "dev-1", "plan": "support", "status": "active"})
    subs = client.get("/v1/subs", headers=h(boss)).json()["subscriptions"]
    assert any(s["device_id"] == "dev-1" for s in subs)

def test_totp_flow(client):
    global _boss_secret
    boss = login(client)
    secret_row = client.get("/v1/auth/setup-totp", headers=h(boss)).json()
    secret = secret_row["secret"]
    code = pyotp.TOTP(secret).now()
    assert client.post("/v1/auth/enable-totp", headers=h(boss), json={"code": code}).status_code == 200
    _boss_secret = secret
    # now password alone returns a totp challenge
    r = client.post("/v1/auth/login", json={"email": "boss@arise.test", "password": "CorrectHorseBattery99"})
    assert r.status_code == 200 and r.json()["challenge"] == "totp"
    code2 = pyotp.TOTP(secret).now()
    r2 = client.post("/v1/auth/totp", headers={"X-Login-Email": "boss@arise.test"}, json={"code": code2})
    assert r2.status_code == 200 and "token" in r2.json()

def test_audit_entries_exist(client):
    # create a fresh admin so login is plain password (no MFA challenge)
    client.post("/v1/admins", headers=h(login(client)), json={
        "email": "auditor@arise.test", "password": "auditorpass123", "role": "support_admin"})
    atok = login(client, "auditor@arise.test", "auditorpass123")
    entries = client.get("/v1/audit?limit=50", headers=h(atok)).json()["entries"]
    actions = {e["action"] for e in entries}
    assert "admin_created" in actions
    assert "release_created" in actions
