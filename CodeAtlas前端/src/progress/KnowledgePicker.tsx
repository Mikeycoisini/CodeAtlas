import { useEffect, useRef, useState } from "react";
import { errorText, get, type Progress, type StudyPage } from "../study/studyApi";

export default function KnowledgePicker({ onSelect, onClose }: { onSelect: (knowledge: Progress) => Promise<void> | void; onClose: () => void }) {
  const [query, setQuery] = useState(""); const [offset, setOffset] = useState(0); const [page, setPage] = useState<StudyPage<Progress> | null>(null);
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const controller = new AbortController();
    get<typeof page>(`/progress/knowledge?query=${encodeURIComponent(query)}&limit=20&offset=${offset}`, controller.signal).then(setPage).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => controller.abort();
  }, [query, offset]);
  const select = async (knowledge: Progress) => {
    setBusy(true); setError("");
    try { await onSelect(knowledge); if (alive.current) onClose(); }
    catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  return <div className="modal-backdrop"><section className="modal study-modal" role="dialog" aria-modal="true" aria-label="关联知识"><div className="study-heading"><h2>关联知识</h2><button disabled={busy} onClick={onClose}>关闭</button></div>
    {error && <p role="alert" className="study-error">{error}</p>}<label>搜索关联知识<input aria-label="搜索关联知识" value={query} disabled={busy} onChange={event => { setQuery(event.target.value); setOffset(0); }}/></label>
    {page?.items.map(knowledge => <button key={knowledge.id} disabled={busy} className="study-choice" onClick={() => select(knowledge)}><strong>{knowledge.title}</strong><small>版本 {knowledge.revision}</small></button>)}
    {page?.total === 0 && <p>暂无匹配知识，可先到知识库创建。</p>}<div className="button-row"><button disabled={busy || !offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={busy || !page || offset + 20 >= page.total} onClick={() => setOffset(previous => previous + 20)}>下一页</button></div>
  </section></div>;
}
