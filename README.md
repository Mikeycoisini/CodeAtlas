# CodeAtlas

CodeAtlas 是本地源码探索与学习工具。仓库包含 Spring Boot 后端、React 前端和可选的 Python AI 服务。

## 工程目录

- `CodeAtlas后端/`：后端、API 契约、迁移脚本和测试样本
- `CodeAtlas前端/`：React 应用
- `CodeAtlasAI/`：可选的 AI 服务

请分别阅读各工程的 README 了解依赖与启动步骤。后端运行前须配置本机数据库；`CodeAtlas后端/src/main/resources/application.yml` 从 `CODEATLAS_DB_USER` 和 `CODEATLAS_DB_PASSWORD` 环境变量读取凭据。

在 Windows 上，若 `npm run dev` 报 `node_modules/.vite/deps_temp_*: Access is denied`，请检查杀毒软件或 Windows 受控文件夹访问是否拦截了 `CodeAtlas前端/node_modules/@esbuild/win32-x64/esbuild.exe` 的写入。该错误发生在 Vite 的依赖预构建阶段，清理缓存或改缓存位置未必能解决。