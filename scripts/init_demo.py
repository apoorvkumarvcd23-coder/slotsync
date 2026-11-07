"""Create an ignored local database secret without overwriting an existing demo."""
import os
from pathlib import Path
import secrets

directory = Path(__file__).resolve().parents[1] / ".secrets"
directory.mkdir(mode=0o700, exist_ok=True)
path = directory / "postgres_password"
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    if not path.is_file() or not path.read_text().strip():
        raise SystemExit("Existing secret is invalid; inspect .secrets/postgres_password locally.")
    print("Existing database secret retained.")
else:
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(secrets.token_urlsafe(36))
    print("Created .secrets/postgres_password (ignored by Git).")
