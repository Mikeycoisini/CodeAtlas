# V0.1 本地导入边界

本地服务绑定 127.0.0.1。Host、Origin 和 Sec-Fetch-Site 校验配合运行时会话令牌，保护源码读取和目录导入。启动配置可以限定允许的导入根目录。源码与任务 API 返回 Cache-Control no-store。

导入读取用户明确提交的目录，限定文件数量、大小和总内容，跳过默认忽略目录和密钥文件。拒绝应用数据目录重叠、符号链接、普通 junction 和其他重解析点。复制前后核对文件元信息，并二次扫描内容哈希。静态索引针对固定快照执行。

Git 命令采用固定参数和独立路径参数，关闭 hooks 与 fsmonitor，设置超时并限制输出。检测到外部 clean/process 过滤器或子模块时跳过工作区状态查询，来源为 UNKNOWN，保留原因。

Windows 的路径式文件 API 无法在此实现中原子锁定全部祖先目录。具有同等本机权限的恶意进程可在验证与读取之间替换并恢复父目录，双扫描及属性校验属于变化检测。导入应使用稳定工作区。此边界需要更强文件句柄访问或隔离进程才能进一步收紧。

应用数据目录由应用独占管理，以文件锁限制同目录多进程访问。数据库与数据目录应配对使用。历史快照持续保留，当前版本由使用者管理备份，删除及保留策略属于后续维护能力。

API JSON 请求体实际读取上限为 1 MiB，声明长度与分块读取均检查。服务仅面向本机使用。SOURCE_CHANGED、IMPORT_BOUNDARY、IMPORT_LIMIT、UNSUPPORTED_ENCODING 和 PROCESS_INTERRUPTED 提供可诊断反馈。

## V0.4.2 公开 GitHub 仓库导入

- 仅允许 `https://github.com/{owner}/{repo}` 根地址；拒绝 http、其他主机、URL 凭据、查询参数、片段、非 443 端口、子树地址与 `git@` 地址。
- 首版不接收、不读取、不保存 Git 凭据；私有仓库与 OAuth 不在范围内。
- 浅克隆使用 `--depth 1 --single-branch --recurse-submodules=no`；禁用 hooks、fsmonitor、autocrlf、symlinks、credential helper 与 LFS smudge；独立 HOME/XDG 配置，`GIT_CONFIG_NOSYSTEM=1`，清除可注入的 `GIT_*`/`SSH_*` 环境变量，禁止终端提示。
- 克隆进程有超时与取消轮询，stdout/stderr 截断；成功、失败、取消后删除 staging 目录。
- 远端仓库视为不可信数据：不执行构建、测试、脚本或 hook；`.git`、密钥文件与构建产物按现有快照策略跳过；沿用文件数量、单文件大小、总量限制及重解析点校验。
- 残余风险：GitHub 服务端或网络路径被攻破、超大仓库的下载时间与磁盘峰值、Windows 非法路径、仓库内符号链接（`core.symlinks=false` 仅保留文本）、Git 版本差异导致错误文本映射不稳定。私有仓库、凭据管理与多用户认证需后续设计。

## V0.4.3 关系人工修正

- 修正只写 `relation_corrections`，不执行项目代码、不调用模型、不改写 `relations`、`symbols`、`diagnostics` 或图边聚合。
- 理由最多 2000 字符，来源最多 1000，目标文本最多 1000；拒绝未知字段、非法结论与非法类别。
- 代次必须属于该快照且已发布；关系必须属于该代次。幂等键防止重复写入，同键不同内容返回冲突。
- 修正属于用户意见，不改变 CONFIRMED／INFERRED／UNRESOLVED 证据状态；界面分别显示原始值与 effective 值。
- 本机单用户没有作者身份与审批流；多用户作者、权限与撤回策略需另行设计。
