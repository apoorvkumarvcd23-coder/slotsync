"""Create an ignored local database secret without overwriting an existing demo."""
import os
from pathlib import Path
import secrets

directory = Path(__file__).resolve().parents[1] / ".secrets"
directory.mkdir(mode=0o700, exist_ok=True)
if os.name != "nt":
    directory.chmod(0o700)
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

if os.name != "nt":
    # Compose file secrets are bind mounts: their uid/mode overrides are not
    # applied. Allow the non-root container UID to read the mounted file, while
    # the 0700 parent directory prevents other host users from traversing to it.
    path.chmod(0o444)
