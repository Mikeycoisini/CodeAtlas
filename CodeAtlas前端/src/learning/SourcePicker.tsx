import { useEffect, useState } from "react";
import { api, type Project, type Snapshot, type Symbol, type Page } from "../api";
import { mutate, type EvidenceSource } from "./learningApi";

export default function SourcePicker({ onSelect, onClose }: { onSelect: (source: EvidenceSource) => void; onClose: () => void }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [project, setProject] = useState("");
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [snapshot, setSnapshot] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<Page<Symbol> | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const report = (error: unknown) => { if (!(error instanceof DOMException && error.name === "AbortError")) setError(error instanceof Error ? error.message : String(error)); };
  useEffect(() => { const controller = new AbortController(); api<Project[]>("/projects", { signal: controller.signal }).then(setProjects).catch(report); return () => controller.abort(); }, []);
  useEffect(() => {
    setSnapshots([]); setSnapshot(""); setPage(null);
    if (!project) return;
    const controller = new AbortController();
    api<Snapshot[]>(`/projects/${project}/snapshots`, { signal: controller.signal }).then(setSnapshots).catch(report);
    return () => controller.abort();
  }, [project]);
  useEffect(() => {
    setPage(null);
    if (!snapshot) return;
    const controller = new AbortController();
    const timer = setTimeout(() => api<Page<Symbol>>(`/snapshots/${snapshot}/symbols?kind=METHOD&query=${encodeURIComponent(query)}&limit=50`,
      { signal: controller.signal }).then(setPage).catch(report), 200);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [snapshot, query]);
  const select = async (symbol: Symbol) => {
    if (!page?.generationId) return;
    setBusy(true); setError("");
    try {
      const source = await mutate<EvidenceSource>("/sources", { snapshotId: snapshot, generationId: page.generationId, symbolId: symbol.id, fileId: symbol.fileId });
      onSelect(source);
    } catch (error) { report(error); }
    finally { setBusy(false); }
  };
  return <div className="modal-backdrop"><section className="modal source-picker" role="dialog" aria-modal="true" aria-labelledby="source-title">
    <div className="modal-title"><h2 id="source-title">关联源码</h2><button onClick={onClose} disabled={busy}>关闭</button></div>
    {error && <p role="alert" className="learning-error">{error}</p>}
    <label>来源项目<select aria-label="来源项目" value={project} onChange={e => setProject(e.target.value)}><option value="">选择项目</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    <label>来源快照<select aria-label="来源快照" value={snapshot} onChange={e => setSnapshot(e.target.value)}><option value="">选择快照</option>{snapshots.map(s => <option key={s.id} value={s.id}>{s.manifestHash.slice(0, 10)} · {new Date(s.createdAt).toLocaleString("zh-CN")}</option>)}</select></label>
    <label>搜索来源方法<input aria-label="搜索来源方法" value={query} onChange={e => setQuery(e.target.value)} placeholder="方法名或签名"/></label>
    <div className="source-search-results">{page?.items.map(symbol => <button key={symbol.id} onClick={() => select(symbol)} disabled={busy}><strong>{symbol.signature}</strong><small>{symbol.qualifiedName} · L{symbol.startLine}</small></button>)}
      {snapshot && !page?.items.length && <p className="hint">没有可选方法，请核对查询或先完成源码索引。</p>}</div>
    {page && page.total > 50 && <p className="hint">显示前 50 个，共 {page.total} 个匹配，请缩小搜索范围。</p>}
  </section></div>;
}
