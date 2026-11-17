# CodeAtlas AI V0.4

Python/FastAPI 本机 AI 适配服务，使用 httpx 对接可配置的 chat/completions JSON 接口。内部 API 需要 X-CodeAtlas-Service-Token。服务默认绑定 127.0.0.1:8001。

## 安装

在 CodeAtlasAI 目录运行以下命令，需要 Python 3.11 及以上。

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt --index-url https://pypi.org/simple
```

若本机 ensurepip 无法启动，可用 `python -m venv --without-pip .venv`，再运行 `python -m pip --python .venv/Scripts/python.exe install -r requirements.txt --index-url https://pypi.org/simple`。

## 配置与运行

### PyCharm 直接启动

解释器选择本工程 `.venv/Scripts/python.exe`，打开 `run.py` 点击运行即可。首次启动自动创建本机 `.env`，在其中填写 `CODEATLAS_LLM_API_KEY=你的模型密钥`，保存后重启。默认模型为 deepseek-flash，默认地址为 https://api.deepseek.com，可在同一文件中填写 `CODEATLAS_LLM_MODEL` 与 `CODEATLAS_LLM_BASE_URL` 修改。

Java 与 Python 在标准项目目录启动时自动共享根目录 `.runtime/ai-service.token` 中随机生成的内部令牌。缺少模型密钥时健康检查为 DEGRADED，手工功能继续可用。`.env` 和 `.runtime` 已排除 Git 提交。Python 也可直接运行 `python run.py`。

已有显式内部令牌配置会优先使用，Java 和 Python 应保持一致。新克隆项目可使用上述自动配置。下面保留的环境变量方式适用于已有部署和配置覆盖。

参考 .env.example 在本机设置环境变量。服务通过环境变量读取配置，指定 CODEATLAS_LLM_ENV_FILE 才读取配置文件。白名单文件字段为 LLM_PROVIDER、LLM_MODEL、LLM_BASE_URL、LLM_API_KEY、DEEPSEEK_API_KEY、LLM_TEMPERATURE。优先级依次为 CODEATLAS_ 前缀环境变量、普通 LLM_ 环境变量、白名单文件、默认值。

```powershell
$env:CODEATLAS_AI_SERVICE_TOKEN = '<与 Java 使用相同的本机令牌>'
$env:CODEATLAS_LLM_ENV_FILE = 'D:/HealthAi-server/.env'
$env:CODEATLAS_LLM_MODEL = 'deepseek-flash'
.venv/Scripts/python.exe -m uvicorn codeatlas_ai.app:app --host 127.0.0.1 --port 8001 --no-access-log
```

默认 DeepSeek 官方接口，JSON 模式，关闭 thinking，temperature 为 0，输出上限 4096 tokens。支持 CODEATLAS_LLM_BASE_URL、CODEATLAS_LLM_API_KEY、CODEATLAS_LLM_TIMEOUT 和 CODEATLAS_LLM_MAX_TOKENS。网络读取超时默认 60 秒，范围大于 0 至 300。请求最多 1 MiB，提供商响应最多 2 MiB。HTTPS 或显式本机 HTTP 地址可用于适配，禁用重定向和代理环境继承。

GET /health 返回就绪状态。GET /internal/provider 返回供应商公开配置和指纹。POST /internal/generate 校验服务令牌、上下文摘要、配置指纹和有效期后生成，成功返回 result 与 usage。凭据和供应商原始错误正文不会进入 API 错误消息。

## 验证

```powershell
.venv/Scripts/python.exe -m unittest discover -s tests -v
```

确定性浏览器测试可启动 `.venv/Scripts/python.exe tests/fake_provider.py`，其端口为 8002。另一个终端启动 AI 服务前设置 CODEATLAS_LLM_PROVIDER=fixture、CODEATLAS_LLM_MODEL=fixture-model、CODEATLAS_LLM_BASE_URL=http://127.0.0.1:8002、CODEATLAS_LLM_API_KEY=fixture-key，并配置内部令牌。浏览器测试会重置此测试服务的调用计数，只用于本机验收。


V0.3 新增 TRAINING_QUESTIONS 和 TRAINING_FEEDBACK 用途，缺省仍为 EXPLANATION。请求 operation 与 contextJson 内用途、摘要、配置指纹均需匹配。出题和反馈采用独立严格 JSON 结构，反馈标记 AI_ASSISTED。预览/授权由 Java 服务管理。配置指纹协议为2。


V0.4新增LEARNING_GUIDE，支持用户明确选择的知识/学习摘要与可选源码。无源码时引用必须为空，步骤必须是冻结六个身份的准确排列。协议指纹3，旧未执行预览需要重新准备。复习算法在Java内运行，AI服务继续承担可选模型建议。
