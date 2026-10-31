"""Start a local gg-retro server (https://github.com/webs1821/gg-retro) for the protocol E2E tests.

Usage: python3 e2e/start_gg_retro.py <path to gg-retro binary> <work dir>

Registers three synthetic accounts and prints GG_E2E_* variables (also appended to $GITHUB_ENV).
The server keeps running in the background; its log is <work dir>/server.log.
"""
import os
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

PASSWORD = "Synthetic123!"
INVITE = "gg-retro-android-e2e-invitation-0001"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    exe = Path(sys.argv[1]).resolve()
    run = Path(sys.argv[2]).resolve()
    run.mkdir(parents=True, exist_ok=True)
    (run / "password.key").write_bytes(os.urandom(32))
    (run / "invite.txt").write_text(INVITE)
    os.chmod(run / "password.key", 0o600)
    os.chmod(run / "invite.txt", 0o600)
    db = run / "e2e.sqlite"
    port, http_port = free_port(), free_port()
    env = dict(
        os.environ,
        GG_BIND="127.0.0.1",
        GG_GG_PORT=str(port),
        GG_HTTP_PORT=str(http_port),
        GG_DB=str(db),
        GG_HOSTNAME="127.0.0.1",
        GG_ADVERTISED_IP="127.0.0.1",
        GG_ADVERTISED_PORT=str(port),
        GG_PASSWORD_KEY_FILE=str(run / "password.key"),
        GG_REGISTRATION_INVITE_FILE=str(run / "invite.txt"),
    )
    log = (run / "server.log").open("wb")
    proc = subprocess.Popen([str(exe)], cwd=run, env=env, stdout=log, stderr=log, start_new_session=True)
    deadline = time.monotonic() + 30
    while True:
        if proc.poll() is not None:
            raise SystemExit(f"server exited with {proc.returncode}, see {run / 'server.log'}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            if time.monotonic() > deadline:
                raise SystemExit("server did not start")
            time.sleep(0.1)

    def http(path, form=None):
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        with urllib.request.urlopen(f"http://127.0.0.1:{http_port}{path}", data=data, timeout=10) as r:
            return r.read().decode()

    uins = []
    for name in ("alice", "bob", "carol"):
        token = http("/appsvc/regtoken.asp").splitlines()[1]
        with sqlite3.connect(db, timeout=5) as conn:
            code = conn.execute("SELECT captcha_code FROM tokens WHERE token_id=?", (token,)).fetchone()[0]
        reply = http("/appsvc/fmregister3.asp", dict(email=f"{name}@example.invalid", pwd=PASSWORD, invite=INVITE, tokenid=token, tokenval=code))
        if not reply.startswith("reg_success:"):
            raise SystemExit(f"registration failed: {reply}")
        uins.append(reply.split(":", 1)[1].strip())

    lines = [
        "GG_E2E_HOST=127.0.0.1",
        f"GG_E2E_PORT={port}",
        f"GG_E2E_PASSWORD={PASSWORD}",
        f"GG_E2E_UINS={','.join(uins)}",
    ]
    print("\n".join(lines))
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
