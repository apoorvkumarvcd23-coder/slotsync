"""Create an ignored local database secret without overwriting an existing demo."""
import os
import json
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

users = {}
for username in ("requester", "requester2", "approver", "admin"):
    credential = directory / ("seed_" + username + "_password")
    try:
        descriptor = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        if not credential.is_file() or not 24 <= len(credential.read_text().strip()) <= 64:
            raise SystemExit("Invalid existing demo credential file; inspect locally.")
    else:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(secrets.token_urlsafe(24))
    if os.name != "nt":
        credential.chmod(0o444)
    users[username] = credential.read_text().strip()

metadata = directory / "demo-users.json"
try:
    descriptor = os.open(metadata, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    if json.loads(metadata.read_text()) != users:
        raise SystemExit("Demo account metadata differs from retained credential files; inspect locally.")
else:
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(users, output, indent=2)
print("Demo accounts retained/generated. Read .secrets/demo-users.json locally; credentials are never printed.")
