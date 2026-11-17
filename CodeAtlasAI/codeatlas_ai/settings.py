import hashlib
import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit
from .local_config import read_env, service_token


@dataclass(frozen=True)
class Settings:
    provider: str = "deepseek"
    model: str = "deepseek-flash"
    base_url: str = "https://api.deepseek.com"
    api_key: str = field(default="", repr=False)
    service_token: str = field(default="", repr=False)
    temperature: float = 0
    timeout: float = 60
    max_tokens: int = 4096

    @classmethod
    def load(cls, environ=None, directory=None):
        env = dict(os.environ if environ is None else environ)
        local = environ is None or directory is not None
        project = Path(directory) if directory is not None else Path(__file__).resolve().parents[1]
        if local:
            local_file = project / ".env"
            if not local_file.exists():
                try:
                    with local_file.open("x", encoding="utf-8") as output:
                        output.write("# 本机模型配置，填写密钥后重启服务\nCODEATLAS_LLM_API_KEY=\n")
                except FileExistsError:
                    pass
            names = {"CODEATLAS_AI_SERVICE_TOKEN", "CODEATLAS_LLM_ENV_FILE", "DEEPSEEK_API_KEY"}
            for name in ("PROVIDER", "MODEL", "BASE_URL", "API_KEY", "TEMPERATURE", "TIMEOUT", "MAX_TOKENS"):
                names.update({"LLM_" + name, "CODEATLAS_LLM_" + name})
            env = {**read_env(local_file, names), **env}
        values = {}
        path = env.get("CODEATLAS_LLM_ENV_FILE")
        allowed = {"LLM_PROVIDER", "LLM_MODEL", "LLM_BASE_URL", "LLM_API_KEY", "DEEPSEEK_API_KEY", "LLM_TEMPERATURE"}
        if path:
            for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
                if line.lstrip().startswith("#") or "=" not in line:
                    continue
                name, value = line.split("=", 1)
                if name.strip() in allowed:
                    values[name.strip()] = value.strip().strip("\"'")
        def value(name, fallback):
            return env.get("CODEATLAS_" + name) or env.get(name) or values.get(name) or fallback
        return cls(provider=value("LLM_PROVIDER", "deepseek"), model=value("LLM_MODEL", "deepseek-flash"),
                   base_url=value("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/"),
                   api_key=value("LLM_API_KEY", env.get("DEEPSEEK_API_KEY") or values.get("DEEPSEEK_API_KEY", "")),
                   service_token=env.get("CODEATLAS_AI_SERVICE_TOKEN") or (service_token(project.parent) if local else ""),
                   temperature=float(value("LLM_TEMPERATURE", "0")),
                   timeout=float(env.get("CODEATLAS_LLM_TIMEOUT", "60")),
                   max_tokens=min(4096, max(128, int(env.get("CODEATLAS_LLM_MAX_TOKENS", "4096")))))

    def __post_init__(self):
        url = urlsplit(self.base_url)
        if url.username or url.password or url.query or url.fragment or not url.hostname:
            raise ValueError("Invalid provider URL")
        if url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}):
            raise ValueError("Provider URL requires HTTPS or explicit loopback")
        if not 0 < self.timeout <= 300 or not 128 <= self.max_tokens <= 4096 or not 0 <= self.temperature <= 2:
            raise ValueError("Invalid provider limits")

    @property
    def fingerprint(self):
        config = {"provider": self.provider, "model": self.model, "baseUrl": self.base_url,
                  "temperature": self.temperature, "maxTokens": self.max_tokens,
                  "credentialHash": hashlib.sha256(self.api_key.encode()).hexdigest(), "protocol": 3}
        return hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()

    def public(self):
        return {"provider": self.provider, "model": self.model, "baseUrl": self.base_url,
                "fingerprint": self.fingerprint, "configured": bool(self.api_key),
                "maxTokens": self.max_tokens, "timeoutSeconds": self.timeout}
