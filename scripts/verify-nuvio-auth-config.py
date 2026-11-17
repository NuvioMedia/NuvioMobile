#!/usr/bin/env python3
"""Check generated Nuvio Cloud login config before a lengthy iOS IPA build."""
import re
from pathlib import Path
from urllib.parse import urlsplit

root = Path(__file__).resolve().parent.parent
path = root / "composeApp/build/generated/runtime-config/kotlin/com/nuvio/app/core/network/SupabaseConfig.kt"
if not path.is_file():
    raise SystemExit("Missing generated SupabaseConfig.kt (run :composeApp:generateRuntimeConfigs).")

source = path.read_text(encoding="utf-8")
def setting(name: str) -> str:
    match = re.search(r'\bconst val ' + re.escape(name) + r'\s*=\s*"([^"]*)"', source)
    if match is None:
        raise SystemExit(f"Generated Nuvio auth setting {name} is absent.")
    return match.group(1).strip()

url_text = setting("URL")
client_key = setting("ANON_KEY")
url = urlsplit(url_text)
if url.scheme != "https" or not url.hostname or url.username or url.password:
    raise SystemExit("Nuvio login backend needs a valid public HTTPS URL.")
if not client_key or len(client_key) < 20 or client_key.lower().startswith(("sb_secret_", "service_role")):
    raise SystemExit("Nuvio login needs a valid publishable client key, not a server secret.")
print(f"Nuvio login backend verified: {url.hostname}; client key present (value hidden).")
