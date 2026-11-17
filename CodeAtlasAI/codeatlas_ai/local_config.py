"""Local IDE defaults shared with the Java service."""
import secrets
import time
from pathlib import Path


def service_token(workspace: Path) -> str:
    directory = workspace / ".runtime"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / "ai-service.token"
    try:
        with target.open("x", encoding="ascii") as output:
            output.write(secrets.token_hex(32))
    except FileExistsError:
        pass
    token = ""
    for _ in range(100):
        token = target.read_text(encoding="ascii").strip()
        if len(token) == 64:
            break
        time.sleep(0.02)
    if len(token) != 64 or any(character not in "0123456789abcdef" for character in token):
        raise ValueError("本机内部令牌文件无效，请检查 .runtime/ai-service.token")
    return token


def read_env(path: Path, allowed: set[str]) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.lstrip().startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name.strip() in allowed:
            values[name.strip()] = value.strip().strip("\"'")
    return values
