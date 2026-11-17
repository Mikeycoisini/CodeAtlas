import { useEffect, useState } from "react";
import { api, post, type Task } from "../api";
import type { GitHubBinding, GitHubCheck, GitHubCredential } from "./githubApi";
import "./github.css";

export default function GitHubWorkspace({ projectId, theme }: { projectId: string; theme: "light" | "dark" }) {
  const [credentials, setCredentials] = useState<GitHubCredential[]>([]);
  const [binding, setBinding] = useState<GitHubBinding | null>(null);
  const [label, setLabel] = useState("");
  const [token, setToken] = useState("");
  const [rotateId, setRotateId] = useState("");
  const [rotateToken, setRotateToken] = useState("");
  const [credentialId, setCredentialId] = useState("");
  const [url, setUrl] = useState("");
  const [ref, setRef] = useState("");
  const [task, setTask] = useState<Task | null>(null);
  const [check, setCheck] = useState<GitHubCheck | null>(null);
  const [execution, setExecution] = useState<{ enabled: boolean; dockerAvailable: boolean; message: string } | null>(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      const list = await api<GitHubCredential[]>("/github/credentials");
      setCredentials(list);
      if (!credentialId && list.length) setCredentialId(list[0].id);
      if (projectId) {
        const info = await api<GitHubBinding>(`/github/projects/${projectId}/binding`);
        setBinding(info.bound ? info : null);
      } else setBinding(null);
      setExecution(await api<{ enabled: boolean; dockerAvailable: boolean; message: string }>("/executions/status"));
    } catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
  };
  useEffect(() => { setBinding(null); setCheck(null); load(); }, [projectId]);
  useEffect(() => {
    if (!task || !["QUEUED", "RUNNING"].includes(task.status)) return;
    const timer = setInterval(async () => {
      try {
        const next = await api<Task>(`/tasks/${task.id}`);
        setTask(next);
        if (!["QUEUED", "RUNNING"].includes(next.status)) { await load(); }
      } catch { }
    }, 2_000);
    return () => clearInterval(timer);
  }, [task?.id, task?.status]);

  const run = async (work: () => Promise<void>) => {
    setBusy(true); setError(""); setMessage("");
    try { await work(); } catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
    finally { setBusy(false); }
  };
  const createCredential = () => run(async () => {
    const created = await post<GitHubCredential>("/github/credentials", { label, token });
    setMessage(`已保存凭据 ${created.label}，令牌不会再次显示`);
    setLabel(""); setToken(""); await load();
  });
  const revoke = (id: string) => run(async () => { await api(`/github/credentials/${id}`, { method: "DELETE" }); setMessage("凭据已撤销"); await load(); });
  const rotate = (id: string) => run(async () => {
    await post(`/github/credentials/${id}/rotate`, { token: rotateToken });
    setMessage("凭据已轮换"); setRotateId(""); setRotateToken(""); await load();
  });
  const importPrivate = () => run(async () => {
    const created = await post<Task>("/github/imports", { credentialId, url, ref });
    setTask(created); setMessage("私有仓库导入任务已创建；完成后会生成新快照并更新绑定");
  });
  const checkUpdate = () => run(async () => {
    const result = await post<GitHubCheck>(`/github/projects/${projectId}/check`);
    setCheck(result); setMessage(result.changed ? "发现新提交，可同步新快照" : "远端没有新提交");
  });
  const syncNow = () => run(async () => {
    const created = await post<Task>(`/github/projects/${projectId}/sync`);
    setTask(created); setMessage("同步任务已创建；旧快照、知识和训练来源保持不变");
  });

  return <main className={`github-workspace github-${theme}`}>
    <header className="github-header">
      <div><p className="github-eyebrow">PRIVATE GITHUB</p><h1>私有仓库认证与同步</h1><p className="github-muted">令牌只在后端加密保管，任务和日志不返回令牌。公开导入仍在项目探索中使用。</p></div>
      <button className="github-refresh" onClick={load} disabled={busy}>刷新</button>
    </header>
    {error && <p className="github-error" role="alert">{error}</p>}
    {message && <p className="github-notice" role="status">{message}</p>}
    <div className="github-grid">
      <section className="github-panel">
        <h2>访问凭据</h2>
        <p className="github-muted">建议使用最小仓库权限的 fine-grained token。撤销或轮换后，导入任务会立即失效。</p>
        <div className="github-form">
          <label>名称<input value={label} maxLength={120} onChange={event => setLabel(event.target.value)} placeholder="例如：私有后端仓库"/></label>
          <label>Token<input type="password" value={token} autoComplete="off" onChange={event => setToken(event.target.value)} placeholder="只提交给本机后端"/></label>
          <button disabled={busy || !label.trim() || !token.trim()} onClick={createCredential}>保存凭据</button>
        </div>
        <div className="github-list">{credentials.map(credential => <article key={credential.id} className={credential.revokedAt ? "revoked" : ""}>
          <div><strong>{credential.label}</strong><small>{credential.scopes || "未报告 scope"} · {credential.revokedAt ? "已撤销" : "可用"}</small></div>
          <small>创建 {credential.createdAt?.slice(0, 19).replace("T", " ")}{credential.lastUsedAt && ` · 最近使用 ${credential.lastUsedAt.slice(0, 19).replace("T", " ")}`}</small>
          <div className="github-row">
            {!credential.revokedAt && <button onClick={() => { setRotateId(credential.id); setRotateToken(""); }}>轮换</button>}
            {!credential.revokedAt && <button className="danger" onClick={() => revoke(credential.id)} disabled={busy}>撤销</button>}
          </div>
          {rotateId === credential.id && <div className="github-form inline"><input type="password" value={rotateToken} autoComplete="off" onChange={event => setRotateToken(event.target.value)} placeholder="新 token"/><button disabled={busy || !rotateToken.trim()} onClick={() => rotate(credential.id)}>确认轮换</button><button onClick={() => setRotateId("")}>取消</button></div>}
        </article>)}</div>
      </section>
      <section className="github-panel">
        <h2>私有仓库导入</h2>
        <p className="github-muted">只接受 github.com 仓库根地址；不会执行仓库脚本。导入后自动建立项目绑定。</p>
        <div className="github-form">
          <label>凭据<select value={credentialId} onChange={event => setCredentialId(event.target.value)}>{credentials.filter(item => !item.revokedAt).map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
          <label>仓库<input value={url} maxLength={300} onChange={event => setUrl(event.target.value)} placeholder="https://github.com/owner/private-repo"/></label>
          <label>分支／标签<input value={ref} maxLength={200} onChange={event => setRef(event.target.value)} placeholder="留空使用默认分支"/></label>
          <button disabled={busy || !credentialId || !url.trim()} onClick={importPrivate}>导入私有仓库</button>
        </div>
        <h2>同步</h2>
        {projectId ? binding ? <div className="github-binding">
          <p><strong>{binding.repoUrl}</strong><small> · {binding.ref || "默认分支"} · {binding.credentialLabel}</small></p>
          <p className="github-muted">最近提交 {binding.lastCommit?.slice(0, 12) || "未知"} · 检查于 {binding.lastCheckedAt?.slice(0, 19).replace("T", " ") || "尚未检查"}</p>
          {check && <p className={check.changed ? "github-warning" : "github-muted"}>远端 {check.remoteCommit?.slice(0, 12) || "未知"} · 当前快照 {check.snapshotCommit?.slice(0, 12) || "未知"} · {check.changed ? "有新提交" : "无变化"}</p>}
          <div className="github-row"><button onClick={checkUpdate} disabled={busy}>检查更新</button><button onClick={syncNow} disabled={busy}>同步新快照</button></div>
          <p className="github-muted">同步只创建新快照／代次；旧知识、训练和复习继续指向原版本。自动定时同步尚未启用。</p>
        </div> : <p className="github-muted">当前项目没有私有仓库绑定。先在上方导入，或在项目探索中导入公开仓库。</p> : <p className="github-muted">选择一个项目后可检查更新。</p>}
        {task && <article className="github-task"><strong>任务 {task.kind} · {task.status}</strong><small>{task.progress}/{task.total} {task.message ?? ""}</small>{task.errorCode && <small className="github-error">{task.errorCode}</small>}</article>}
      </section>
      <section className="github-panel">
        <h2>自动隔离执行</h2>
        <p className={execution?.enabled && execution.dockerAvailable ? "github-notice" : "github-muted"}>{execution?.message ?? "正在读取执行器状态…"}</p>
        <p className="github-muted">安全准入前仍使用 USER_REPORTED 外部报告。启用需要 Docker、独立镜像、无网络执行和受控工作区。</p>
      </section>
    </div>
  </main>;
}
