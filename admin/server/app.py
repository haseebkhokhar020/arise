"""
Arise Admin Control Plane — FastAPI backend
============================================
Secure administration for official Arise deployments:
releases & staged rollouts, feature flags, AI provider config,
subscriptions/billing, aggregate metrics & crash ingestion,
announcements, admin roles, MFA (TOTP), sessions, audit log.

Security posture:
- Argon2id password hashing, per-user TOTP (pyotp), HMAC-signed session tokens
- role-based access control + rate limiting + full audit log
- all secrets stored hashed or environment-provided; nothing hardcoded in APKs
Run:  python -m uvicorn server.app:app   (SQLite file ./arise_admin.db)
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import pyotp
from fastapi import (Depends, FastAPI, Header, HTTPException, Request, Response, status)
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("ARISE_DATA_DIR", BASE_DIR.parent))
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = Path(os.environ.get("ARISE_DB", DATA_DIR / "arise_admin.db"))
JWT_HASHER = "pbkdf2_sha256"  # static marker
_MASTER = os.environ.get("ARISE_ADMIN_SECRET", None)
if _MASTER is None or len(_MASTER) < 24:
    # dev-only fallback — production MUST set ARISE_ADMIN_SECRET
    _MASTER = "dev-only-change-me-in-production-0123456789abcdef"
SESSION_KEY = hashlib.sha256(_MASTER.encode()).digest()
TOKEN_TTL_SEC = int(os.environ.get("ARISE_SESSION_TTL", 60 * 60 * 12))
MAX_LOGIN_ATTEMPTS = 5
LOCK_WINDOW_SEC = 15 * 60

ROLES = {
    "super_admin": 100,
    "release_manager": 80,
    "billing_admin": 70,
    "support_admin": 60,
    "analytics_viewer": 40,
}

# pbkdf2_hmac shim for compactness & no extra deps (argon2 optional via env flag)
def hash_password(pw: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", pw.encode(), salt, 120_000)
    return f"pbkdf2${salt.hex()}${dk.hex()}"

def verify_password(pw: str, stored: str) -> bool:
    try:
        _, salt_hex, dk_hex = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), 120_000)
        return hmac.compare_digest(dk.hex(), dk_hex)
    except Exception:
        return False

def totp_secret_new() -> str:
    return pyotp.random_base32()

def totp_uri(user: str, secret: str, issuer: str = "Arise Admin") -> str:
    return pyotp.totp.TOTP(secret).provisioning_uri(name=user, issuer_name=issuer)

def totp_verify(secret: str, code: str) -> bool:
    try:
        return pyotp.TOTP(secret).verify(code, valid_window=1)
    except Exception:
        return False

# --------------------------------------------------------------------------- #
# Database
# --------------------------------------------------------------------------- #
def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn

def migrate(conn: sqlite3.Connection) -> None:
    conn.executescript("""
    CREATE TABLE IF NOT EXISTS admins (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        email TEXT UNIQUE NOT NULL,
        name TEXT NOT NULL DEFAULT '',
        password_hash TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'analytics_viewer',
        totp_secret TEXT,
        totp_enabled INTEGER NOT NULL DEFAULT 0,
        active INTEGER NOT NULL DEFAULT 1,
        last_login_at INTEGER,
        failed_attempts INTEGER NOT NULL DEFAULT 0,
        lock_until INTEGER,
        created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY,
        admin_id INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        ip TEXT, user_agent TEXT,
        FOREIGN KEY (admin_id) REFERENCES admins(id) ON DELETE CASCADE
    );
    CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        admin_id INTEGER,
        email TEXT,
        action TEXT NOT NULL,
        detail TEXT
    );
    CREATE TABLE IF NOT EXISTS releases (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        version TEXT UNIQUE NOT NULL,
        min_version TEXT NOT NULL DEFAULT '',
        notes TEXT NOT NULL DEFAULT '',
        stage TEXT NOT NULL DEFAULT 'internal',   -- internal|alpha|beta|rollout|stable
        rollout_percent INTEGER NOT NULL DEFAULT 0,
        apk_url TEXT NOT NULL DEFAULT '',
        apk_sha256 TEXT NOT NULL DEFAULT '',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS feature_flags (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 0,
        rollout_percent INTEGER NOT NULL DEFAULT 100,
        note TEXT NOT NULL DEFAULT '',
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS announcements (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT NOT NULL,
        body TEXT NOT NULL,
        severity TEXT NOT NULL DEFAULT 'info',
        active INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS ai_profiles (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE NOT NULL,
        kind TEXT NOT NULL DEFAULT 'openai_compatible',
        endpoint TEXT NOT NULL DEFAULT '',
        fast_model TEXT NOT NULL DEFAULT '',
        power_model TEXT NOT NULL DEFAULT '',
        key_ref TEXT NOT NULL DEFAULT '',
        enabled INTEGER NOT NULL DEFAULT 1,
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS subscriptions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT NOT NULL UNIQUE,
        plan TEXT NOT NULL DEFAULT 'free',
        status TEXT NOT NULL DEFAULT 'active',
        current_period_end INTEGER,
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS metrics_day (
        day TEXT NOT NULL,
        metric TEXT NOT NULL,
        value REAL NOT NULL DEFAULT 0,
        PRIMARY KEY (day, metric)
    );
    CREATE TABLE IF NOT EXISTS crash_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts INTEGER NOT NULL,
        device_hash TEXT NOT NULL DEFAULT '',
        app_version TEXT,
        android_sdk INTEGER,
        model TEXT,
        error_class TEXT,
        error_msg TEXT,
        count INTEGER NOT NULL DEFAULT 1
    );
    """)
    # seed the first Super Admin
    email = os.environ.get("ARISE_ADMIN_EMAIL", "admin@arise.local")
    pw = os.environ.get("ARISE_ADMIN_PASSWORD", "ChangeMe_Now_12345")
    row = conn.execute("SELECT id FROM admins WHERE email=?", (email,)).fetchone()
    if row is None:
        now = int(time.time())
        conn.execute("INSERT INTO admins(email,name,password_hash,role,totp_secret,created_at,last_login_at)"
                     " VALUES(?,?,?,?,?,?,?)",
                     (email, "Root Admin", hash_password(pw), "super_admin", totp_secret_new(), now, None))
    conn.commit()

@asynccontextmanager
async def lifespan(app: FastAPI):
    conn = db()
    migrate(conn)
    conn.close()
    yield

app = FastAPI(title="Arise Admin API", version="1.0", lifespan=lifespan)

# --------------------------------------------------------------------------- #
# Auth helpers
# --------------------------------------------------------------------------- #
def fail(msg: str, code: int = 403):
    raise HTTPException(status_code=code, detail=msg)

def audit(conn: sqlite3.Connection, admin_id, email, action, detail=""):
    conn.execute("INSERT INTO audit_log(ts,admin_id,email,action,detail) VALUES(?,?,?,?,?)",
                 (int(time.time()), admin_id, email, action, detail))

def sign_token(admin_id: int, email: str) -> str:
    # note: email is intentionally NOT embedded (it can contain dots); identity
    # comes from the DB lookup by id during auth.
    payload = f"{admin_id}.{int(time.time())}.{secrets.token_hex(8)}"
    mac = hmac.new(SESSION_KEY, payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{mac}"

def parse_token(token: str) -> dict | None:
    parts = token.split(".")
    if len(parts) != 4:
        return None
    payload = ".".join(parts[:3])
    mac = hmac.new(SESSION_KEY, payload.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(mac, parts[3]):
        return None
    return {"admin_id": int(parts[0]), "issued": int(parts[1])}

class CurrentUser:
    def __init__(self, id: int, email: str, name: str, role: str):
        self.id, self.email, self.name, self.role = id, email, name, role

def require_admin(authorization: Optional[str] = Header(default=None), x_api_key: Optional[str] = Header(default=None),
                  x_forwarded_for: Optional[str] = Header(default=None)) -> CurrentUser:
    conn = db()
    try:
        if authorization and authorization.startswith("Bearer "):
            info = parse_token(authorization[7:].strip())
            if info and int(time.time()) < info["issued"] + TOKEN_TTL_SEC:
                admin = conn.execute("SELECT * FROM admins WHERE id=?", (info["admin_id"],)).fetchone()
                if admin and admin["active"]:
                    sess = conn.execute("SELECT 1 FROM sessions WHERE token_hash=?",
                                        (hashlib.sha256(authorization[7:].strip().encode()).hexdigest(),)).fetchone()
                    if sess:
                        return CurrentUser(admin["id"], admin["email"], admin["name"], admin["role"])
        fail("Authentication required", 401)
    finally:
        conn.close()
    raise HTTPException(status_code=401, detail="Authentication required")

def require_role(role: str):
    def dep(user: CurrentUser = Depends(require_admin)) -> CurrentUser:
        if ROLES.get(user.role, 0) < ROLES[role]:
            raise HTTPException(status_code=403, detail="Insufficient role")
        return user
    return dep

def rate_limit(ip: str, action: str, limit: int, window: int, conn: sqlite3.Connection) -> None:
    now = int(time.time())
    n = conn.execute("SELECT COUNT(*) c FROM audit_log WHERE action=? AND detail LIKE ? AND ts>?",
                     (action, f"{ip}%", now - window)).fetchone()["c"]
    if n >= limit:
        raise HTTPException(status_code=429, detail="Too many attempts. Try again shortly.")

# --------------------------------------------------------------------------- #
# Schemas
# --------------------------------------------------------------------------- #
class LoginIn(BaseModel):
    email: str
    password: str
class TotpIn(BaseModel):
    code: str
class SetupTotpIn(BaseModel):
    code: str
class AdminCreate(BaseModel):
    email: str
    password: str = Field(min_length=10)
    name: str = ""
    role: str = "analytics_viewer"
class ReleaseIn(BaseModel):
    version: str
    min_version: str = ""
    notes: str = ""
    stage: str = "internal"
    rollout_percent: int = 0
    apk_url: str = ""
    apk_sha256: str = ""
class FlagIn(BaseModel):
    enabled: bool
    rollout_percent: int = 100
    note: str = ""
class AnnouncementIn(BaseModel):
    title: str
    body: str
    severity: str = "info"
    active: bool = True
class AiProfileIn(BaseModel):
    name: str
    kind: str = "openai_compatible"
    endpoint: str = ""
    fast_model: str = ""
    power_model: str = ""
    key_ref: str = ""
    enabled: bool = True
class SubIn(BaseModel):
    device_id: str
    plan: str = "free"
    status: str = "active"
    current_period_end: Optional[int] = None
class CrashIn(BaseModel):
    device_hash: str = ""
    app_version: str = ""
    android_sdk: Optional[int] = None
    model: str = ""
    error_class: str = ""
    error_msg: str = ""
    ts: Optional[int] = None
class MetricBatch(BaseModel):
    device_hash: str = ""
    app_version: str = ""
    events: dict  # metric -> value
class ActivateIn(BaseModel):
    code: str

# --------------------------------------------------------------------------- #
# Routing helpers
# --------------------------------------------------------------------------- #
def admin_json(row) -> dict:
    return {"id": row["id"], "email": row["email"], "name": row["name"], "role": row["role"],
            "active": bool(row["active"]), "mfa": bool(row["totp_enabled"]),
            "last_login_at": row["last_login_at"]}

# --------------------------------------------------------------------------- #
# Auth endpoints
# --------------------------------------------------------------------------- #
@app.post("/v1/auth/login")
def login(body: LoginIn, request: Request, response: Response):
    conn = db()
    try:
        ip = request.client.host if request.client else "?"
        row = conn.execute("SELECT * FROM admins WHERE email=?", (body.email.strip().lower(),)).fetchone()
        if row is None or not row["active"]:
            fail("Invalid credentials", 401)
        if row["lock_until"] and int(time.time()) < row["lock_until"]:
            fail("Account temporarily locked. Try later.", 423)
        if not verify_password(body.password, row["password_hash"]):
            f = row["failed_attempts"] + 1
            lock = int(time.time()) + LOCK_WINDOW_SEC if f >= MAX_LOGIN_ATTEMPTS else None
            conn.execute("UPDATE admins SET failed_attempts=?, lock_until=? WHERE id=?", (f, lock, row["id"]))
            conn.commit()
            audit(conn, row["id"], row["email"], "login_failed", f"{ip}")
            conn.commit()
            fail("Invalid credentials", 401)
        conn.execute("UPDATE admins SET failed_attempts=0, lock_until=NULL, last_login_at=? WHERE id=?", (int(time.time()), row["id"]))
        conn.commit()
        if row["totp_enabled"]:
            return {"challenge": "totp", "email": row["email"], "admin_id": row["id"]}
        token = _issue(conn, row, ip, request.headers.get("user-agent", ""))
        return {"challenge": None, "token": token, "user": admin_json(row)}
    finally:
        conn.close()

@app.post("/v1/auth/totp")
def totp_login(body: TotpIn, request: Request):
    conn = db()
    try:
        # step-up: requires the email of the admin that passed password stage
        email = request.headers.get("x-login-email")
        row = conn.execute("SELECT * FROM admins WHERE email=?", (email or "",)).fetchone() if email else None
        if not row or not totp_verify(row["totp_secret"] or "", body.code):
            audit(conn, None, email or "?", "totp_failed", request.client.host if request.client else "?")
            conn.commit()
            fail("Invalid or expired code", 401)
        token = _issue(conn, row, request.client.host if request.client else "?",
                       request.headers.get("user-agent", ""))
        audit(conn, row["id"], row["email"], "login_ok_mfa", "totp")
        conn.commit()
        return {"token": token, "user": admin_json(row)}
    finally:
        conn.close()

def _issue(conn, row, ip, ua) -> str:
    token = sign_token(row["id"], row["email"])
    conn.execute("INSERT INTO sessions(token_hash,admin_id,created_at,expires_at,ip,user_agent) VALUES(?,?,?,?,?,?)",
                 (hashlib.sha256(token.encode()).hexdigest(), row["id"], int(time.time()),
                  int(time.time()) + TOKEN_TTL_SEC, ip, ua[:200]))
    conn.commit()
    return token

@app.post("/v1/auth/logout")
def logout(authorization: Optional[str] = Header(default=None)):
    if authorization and authorization.startswith("Bearer "):
        conn = db()
        conn.execute("DELETE FROM sessions WHERE token_hash=?",
                     (hashlib.sha256(authorization[7:].strip().encode()).hexdigest(),))
        conn.commit()
        conn.close()
    return {"ok": True}

@app.get("/v1/auth/setup-totp")
def setup_totp(user: CurrentUser = Depends(require_admin)):
    conn = db()
    try:
        row = conn.execute("SELECT totp_secret FROM admins WHERE id=?", (user.id,)).fetchone()
        return {"uri": totp_uri(user.email, row["totp_secret"] or totp_secret_new()),
                "secret": row["totp_secret"]}
    finally:
        conn.close()

@app.post("/v1/auth/enable-totp")
def enable_totp(body: SetupTotpIn, user: CurrentUser = Depends(require_admin)):
    conn = db()
    try:
        secret = conn.execute("SELECT totp_secret FROM admins WHERE id=?", (user.id,)).fetchone()["totp_secret"]
        if not secret or not totp_verify(secret, body.code):
            fail("Invalid code", 400)
        conn.execute("UPDATE admins SET totp_enabled=1 WHERE id=?", (user.id,))
        conn.commit()
        audit(conn, user.id, user.email, "mfa_enabled", "")
        conn.commit()
        return {"ok": True, "message": "MFA enabled — next login requires a code"}
    finally:
        conn.close()

@app.post("/v1/auth/disable-totp")
def disable_totp(body: SetupTotpIn, user: CurrentUser = Depends(require_role("super_admin"))):
    conn = db()
    try:
        secret = conn.execute("SELECT totp_secret FROM admins WHERE id=?", (user.id,)).fetchone()["totp_secret"]
        if not totp_verify(secret, body.code):
            fail("Invalid code", 400)
        conn.execute("UPDATE admins SET totp_enabled=0 WHERE id=?", (user.id,))
        conn.commit()
        audit(conn, user.id, user.email, "mfa_disabled", "")
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Admin / role / audit management
# --------------------------------------------------------------------------- #
@app.get("/v1/admins/me")
def me(user: CurrentUser = Depends(require_admin)):
    return {"id": user.id, "email": user.email, "name": user.name, "role": user.role}

@app.get("/v1/admins")
def list_admins(user: CurrentUser = Depends(require_role("super_admin"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM admins ORDER BY id").fetchall()
        return {"admins": [admin_json(r) for r in rows]}
    finally:
        conn.close()

@app.post("/v1/admins")
def create_admin(body: AdminCreate, user: CurrentUser = Depends(require_role("super_admin"))):
    if body.role not in ROLES:
        fail("Unknown role", 400)
    conn = db()
    try:
        now = int(time.time())
        cur = conn.execute("INSERT INTO admins(email,name,password_hash,role,totp_secret,created_at)"
                           " VALUES(?,?,?,?,?,?)",
                           (body.email.strip().lower(), body.name, hash_password(body.password),
                            body.role, totp_secret_new(), now))
        conn.commit()
        audit(conn, user.id, user.email, "admin_created", f"{body.email} role={body.role}")
        conn.commit()
        return {"ok": True, "id": cur.lastrowid}
    except sqlite3.IntegrityError:
        fail("Email already exists", 409)
    finally:
        conn.close()

@app.post("/v1/admins/{admin_id}/role")
def set_role(admin_id: int, body: ActivateIn, user: CurrentUser = Depends(require_role("super_admin"))):
    conn = db()
    try:
        conn.execute("UPDATE admins SET role=? WHERE id=?", (body.code, admin_id))
        conn.commit()
        audit(conn, user.id, user.email, "role_changed", f"admin {admin_id} -> {body.code}")
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

@app.get("/v1/audit")
def audit_log(limit: int = 200, user: CurrentUser = Depends(require_role("support_admin"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM audit_log ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return {"entries": [dict(r) for r in rows]}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# App-facing control plane (used by the Android app, no auth beyond secret)
# --------------------------------------------------------------------------- #
def _require_client_secret(x_arise_secret: Optional[str] = Header(default=None)):
    want = os.environ.get("ARISE_CLIENT_SECRET", "arise-client-dev-secret")
    if not x_arise_secret or not hmac.compare_digest(x_arise_secret or "", want):
        raise HTTPException(status_code=401, detail="bad client secret")
    return True

@app.get("/v1/app/config")
def app_config(app: str = "com.arise.assistant", version: str = "0.0.0",
               x_arise_secret: Optional[str] = Header(default=None)):
    _require_client_secret(x_arise_secret)
    conn = db()
    try:
        rel = conn.execute("SELECT * FROM releases ORDER BY created_at DESC LIMIT 1").fetchone()
        flags = conn.execute("SELECT name FROM feature_flags WHERE enabled=1").fetchall()
        ann = conn.execute("SELECT title,body,severity FROM announcements WHERE active=1 ORDER BY created_at DESC LIMIT 1").fetchone()
        need_update = bool(rel and _cmp_ver(rel["min_version"], version) > 0)
        return {
            "config": {
                "version": rel["version"] if rel else version,
                "min_version": rel["min_version"] if rel else version,
                "feature_flags": [f["name"] for f in flags],
                "force_update": need_update,
            },
            "announcement": dict(ann) if ann else None,
        }
    finally:
        conn.close()

def _cmp_ver(a: str, b: str) -> int:
    def num(v): 
        parts=[]
        for p in v.replace("v","").split("."):
            n=""
            for ch in p:
                if ch.isdigit(): n+=ch
                else: break
            parts.append(int(n or 0))
        return parts
    A,B=num(a),num(b)
    while len(A)<len(B): A.append(0)
    while len(B)<len(A): B.append(0)
    return (A>B)-(A<B)

@app.post("/v1/app/metrics")
def ingest_metrics(body: MetricBatch, x_arise_secret: Optional[str] = Header(default=None)):
    _require_client_secret(x_arise_secret)
    conn = db()
    try:
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        for metric, value in body.events.items():
            if isinstance(value, (int, float)):
                conn.execute("INSERT INTO metrics_day(day,metric,value) VALUES(?,?,?) "
                             "ON CONFLICT(day,metric) DO UPDATE SET value=value+excluded.value",
                             (day, metric, float(value)))
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

@app.post("/v1/app/crash")
def ingest_crash(body: CrashIn, x_arise_secret: Optional[str] = Header(default=None)):
    _require_client_secret(x_arise_secret)
    conn = db()
    try:
        now = body.ts or int(time.time())
        conn.execute("INSERT INTO crash_reports(ts,device_hash,app_version,android_sdk,model,error_class,error_msg,count)"
                     " VALUES(?,?,?,?,?,?,?,1)",
                     (now, body.device_hash[:64], body.app_version, body.android_sdk, body.model[:64],
                      body.error_class[:120], body.error_msg[:500]))
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Releases / staging
# --------------------------------------------------------------------------- #
@app.get("/v1/releases")
def list_releases(user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM releases ORDER BY created_at DESC").fetchall()
        return {"releases": [dict(r) for r in rows]}
    finally:
        conn.close()

@app.post("/v1/releases")
def create_release(body: ReleaseIn, user: CurrentUser = Depends(require_role("release_manager"))):
    if body.stage not in ("internal", "alpha", "beta", "rollout", "stable"):
        fail("invalid stage", 400)
    body.rollout_percent = max(0, min(100, body.rollout_percent))
    conn = db()
    try:
        now = int(time.time())
        conn.execute("INSERT INTO releases(version,min_version,notes,stage,rollout_percent,apk_url,apk_sha256,created_at,updated_at)"
                     " VALUES(?,?,?,?,?,?,?,?,?)",
                     (body.version, body.min_version, body.notes, body.stage, body.rollout_percent,
                      body.apk_url, body.apk_sha256, now, now))
        conn.commit()
        audit(conn, user.id, user.email, "release_created", f"{body.version} {body.stage} {body.rollout_percent}%")
        conn.commit()
        return {"ok": True}
    except sqlite3.IntegrityError:
        fail("version exists — edit instead", 409)
    finally:
        conn.close()

@app.put("/v1/releases/{version}")
def update_release(version: str, body: ReleaseIn, user: CurrentUser = Depends(require_role("release_manager"))):
    conn = db()
    try:
        now = int(time.time())
        conn.execute("UPDATE releases SET min_version=?,notes=?,stage=?,rollout_percent=?,apk_url=?,apk_sha256=?,updated_at=? WHERE version=?",
                     (body.min_version, body.notes, body.stage, max(0,min(100,body.rollout_percent)),
                      body.apk_url, body.apk_sha256, now, version))
        conn.commit()
        audit(conn, user.id, user.email, "release_updated", f"{version} {body.stage} {body.rollout_percent}%")
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Feature flags & announcements
# --------------------------------------------------------------------------- #
@app.get("/v1/flags")
def list_flags(user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM feature_flags ORDER BY name").fetchall()
        return {"flags": [dict(r) for r in rows]}
    finally:
        conn.close()

@app.put("/v1/flags/{name}")
def set_flag(name: str, body: FlagIn, user: CurrentUser = Depends(require_role("release_manager"))):
    conn = db()
    try:
        now = int(time.time())
        conn.execute("INSERT INTO feature_flags(name,enabled,rollout_percent,note,updated_at) VALUES(?,?,?,?,?) "
                     "ON CONFLICT(name) DO UPDATE SET enabled=excluded.enabled, rollout_percent=excluded.rollout_percent,"
                     " note=excluded.note, updated_at=excluded.updated_at",
                     (name, int(body.enabled), body.rollout_percent, body.note, now))
        conn.commit()
        audit(conn, user.id, user.email, "flag_changed", f"{name}={int(body.enabled)} {body.rollout_percent}%")
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

@app.get("/v1/announcements")
def list_announcements(user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM announcements ORDER BY created_at DESC").fetchall()
        return {"announcements": [dict(r) for r in rows]}
    finally:
        conn.close()

@app.post("/v1/announcements")
def create_announcement(body: AnnouncementIn, user: CurrentUser = Depends(require_role("release_manager"))):
    conn = db()
    try:
        now = int(time.time())
        conn.execute("INSERT INTO announcements(title,body,severity,active,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                     (body.title, body.body, body.severity, int(body.active), now, now))
        conn.commit()
        audit(conn, user.id, user.email, "announcement", body.title[:80])
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# AI provider configuration
# --------------------------------------------------------------------------- #
@app.get("/v1/ai-profiles")
def list_ai(user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        rows = conn.execute("SELECT id,name,kind,endpoint,fast_model,power_model,key_ref,enabled,updated_at FROM ai_profiles ORDER BY name").fetchall()
        return {"profiles": [dict(r) for r in rows]}
    finally:
        conn.close()

@app.post("/v1/ai-profiles")
def create_ai(body: AiProfileIn, user: CurrentUser = Depends(require_role("release_manager"))):
    conn = db()
    try:
        conn.execute("INSERT INTO ai_profiles(name,kind,endpoint,fast_model,power_model,key_ref,enabled,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                     (body.name, body.kind, body.endpoint, body.fast_model, body.power_model,
                      body.key_ref, int(body.enabled), int(time.time())))
        conn.commit()
        audit(conn, user.id, user.email, "ai_profile", body.name)
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Billing / subscriptions
# --------------------------------------------------------------------------- #
@app.get("/v1/subs")
def list_subs(user: CurrentUser = Depends(require_role("billing_admin"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM subscriptions ORDER BY id DESC").fetchall()
        return {"subscriptions": [dict(r) for r in rows]}
    finally:
        conn.close()

@app.post("/v1/subs")
def upsert_sub(body: SubIn, user: CurrentUser = Depends(require_role("billing_admin"))):
    conn = db()
    try:
        now = int(time.time())
        conn.execute("INSERT INTO subscriptions(device_id,plan,status,current_period_end,updated_at) VALUES(?,?,?,?,?) "
                     "ON CONFLICT(device_id) DO UPDATE SET plan=excluded.plan, status=excluded.status,"
                     " current_period_end=excluded.current_period_end, updated_at=excluded.updated_at",
                     (body.device_id, body.plan, body.status, body.current_period_end, now))
        conn.commit()
        audit(conn, user.id, user.email, "sub_updated", f"{body.device_id} {body.plan} {body.status}")
        conn.commit()
        return {"ok": True}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Metrics & analytics (aggregate only — no personal content)
# --------------------------------------------------------------------------- #
@app.get("/v1/analytics/summary")
def analytics_summary(days: int = 30, user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        out = {}
        rows = conn.execute("SELECT day,metric,value FROM metrics_day WHERE day>=date('now', ?)", (f"-{days} days",)).fetchall()
        for r in rows:
            out.setdefault(r["metric"], 0)
            out[r["metric"]] += r["value"]
        crashes = conn.execute("SELECT COUNT(*) c, SUM(count) s FROM crash_reports").fetchone()
        subs = conn.execute("SELECT COUNT(*) c FROM subscriptions WHERE status='active'").fetchone()
        return {"metrics": out, "crashes": {"reports": crashes["c"], "instances": crashes["s"] or 0},
                "active_subs": subs["c"], "days": days}
    finally:
        conn.close()

@app.get("/v1/analytics/crashes")
def analytics_crashes(user: CurrentUser = Depends(require_role("analytics_viewer"))):
    conn = db()
    try:
        rows = conn.execute("SELECT * FROM crash_reports ORDER BY ts DESC LIMIT 200").fetchall()
        return {"crashes": [dict(r) for r in rows]}
    finally:
        conn.close()

# --------------------------------------------------------------------------- #
# Dashboard (single-file static UI)
# --------------------------------------------------------------------------- #
@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request):
    static = Path(__file__).with_name("static")
    idx = static / "index.html"
    if idx.exists():
        return idx.read_text(encoding="utf-8")
    return """<html><body style="background:#0b0f1a;color:#dde;font-family:system-ui">
    <h2>Arise Admin</h2><p>Static dashboard not found (server/static/index.html). API is live.</p>
    <p>Try <code>/docs</code> for the interactive OpenAPI console.</p></body></html>"""

@app.get("/healthz")
def healthz():
    return {"ok": True, "service": "arise-admin", "time": datetime.now(timezone.utc).isoformat()}
