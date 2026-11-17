import hashlib
import json
import secrets
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header
from fastapi.responses import JSONResponse

from .provider import ProviderError, generate
from .schemas import GenerationRequest
from .settings import Settings


class BodyLimitMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body.extend(message.get("body", b""))
            if len(body) > 1_048_576:
                return await JSONResponse({"code": "REQUEST_LIMIT", "message": "请求内容过大"}, status_code=413)(scope, receive, send)
            if not message.get("more_body", False):
                break
        delivered = False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


def create_app(settings=None, transport=None):
    settings = settings or Settings.load()
    application = FastAPI(title="CodeAtlas AI", version="0.4.0", docs_url=None, redoc_url=None)
    application.add_middleware(BodyLimitMiddleware)

    @application.exception_handler(ProviderError)
    async def provider_error(request, error):
        return JSONResponse({"code": error.code, "message": error.message}, status_code=error.status,
                            headers={"Cache-Control": "no-store"})

    async def authenticate(token: str = Header(default="", alias="X-CodeAtlas-Service-Token")):
        if not settings.service_token:
            raise ProviderError("AI_SERVICE_NOT_CONFIGURED", "请配置本机服务令牌", 503)
        if not secrets.compare_digest(token.encode(), settings.service_token.encode()):
            raise ProviderError("SERVICE_AUTH_REQUIRED", "内部服务令牌无效", 401)

    @application.get("/health")
    async def health():
        return {"status": "UP" if settings.api_key and settings.service_token else "DEGRADED", "version": "0.4.0"}

    @application.get("/internal/provider", dependencies=[Depends(authenticate)])
    async def provider():
        return settings.public()

    @application.post("/internal/generate", dependencies=[Depends(authenticate)])
    async def explain(request: GenerationRequest):
        digest = hashlib.sha256(request.contextJson.encode()).hexdigest()
        if not secrets.compare_digest(request.contextDigest.encode(), digest.encode()):
            raise ProviderError("CONTEXT_DIGEST_MISMATCH", "上下文摘要不匹配", 409)
        if not secrets.compare_digest(request.providerFingerprint.encode(), settings.fingerprint.encode()):
            raise ProviderError("CONSENT_SCOPE_CHANGED", "模型配置已变化，请重新授权", 409)
        if request.expiresAt.tzinfo is None or request.expiresAt <= datetime.now(timezone.utc):
            raise ProviderError("CONSENT_EXPIRED", "授权上下文已过期", 409)
        try:
            context = json.loads(request.contextJson)
        except ValueError:
            raise ProviderError("INVALID_CONTEXT", "上下文结构无效", 400) from None
        if not isinstance(context, dict) or context.get("operation", "EXPLANATION") != request.operation:
            raise ProviderError("CONSENT_SCOPE_CHANGED", "生成用途与授权上下文不匹配", 409)
        return await generate(settings, request.contextJson, transport, request.operation)

    return application


app = create_app()
