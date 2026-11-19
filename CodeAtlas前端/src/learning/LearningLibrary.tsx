import { useEffect, useRef, useState } from "react";
import { Plus, Search, ArrowRight, Save, Trash2, Link2, Sparkles } from "lucide-react";
import { api, type Page } from "../api";
import AiPanel from "./AiPanel";
import SourcePicker from "./SourcePicker";
import { listRecords, mutate, names, recordUrl, type LearningKind, type LearningRecord, type EvidenceSource, type SourceSelection, type SourceCheck } from "./learningApi";

type Draft = { title: string; body: string; question: string; answer: string; explanation: string; codeExample: string; notes: string;
  knowledgeId: string; generationId: string; type: string; tags: string; sources: EvidenceSource[]; revision: number };
const blank = (): Draft => ({ title: "", body: "", question: "", answer: "", explanation: "", codeExample: "", notes: "", knowledgeId: "", generationId: "", type: "NOTE", tags: "", sources: [], revision: 0 });
function initialDraft(kind: LearningKind): Partial<Draft> | null {
  try {
    const raw = sessionStorage.getItem(`codeatlas-draft-${kind}`);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" && Array.isArray(parsed.sources) ? parsed : null;
  } catch { return null; }
}
function fromRecord(record: LearningRecord): Draft {
  return { title: record.title ?? "", body: record.body ?? "", question: record.question ?? "", answer: record.answer ?? "",
    explanation: record.explanation ?? "", codeExample: record.codeExample ?? "", notes: record.notes ?? "", knowledgeId: record.knowledgeId ?? "",
    generationId: record.generationId ?? "", type: record.type ?? "NOTE", tags: record.tags.join(", "), sources: record.sources, revision: record.revision };
}

export default function LearningLibrary({ kind, currentSource, onSource, onOpenRecord, onReview }: {
  kind: LearningKind; currentSource?: SourceSelection; onSource: (source: EvidenceSource, line?: number) => void;
  onOpenRecord: (kind: LearningKind, id: string) => void;
  onReview?: (id: string) => void;
}) {
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<Page<LearningRecord> | null>(null);
  const [selectedId, setSelectedId] = useState(new URLSearchParams(location.search).get("recordId") ?? "");
  const [sourceChecks, setSourceChecks] = useState<Record<string, SourceCheck>>({});
  const selectionVersion = useRef(0);
  const select = (id: string) => { selectionVersion.current++; setSelectedId(id); setSourceChecks({}); };
  const [record, setRecord] = useState<LearningRecord | null>(null);
  const [linkedCards, setLinkedCards] = useState<LearningRecord[]>([]);
  const seed = useRef(initialDraft(kind));
  const [draft, setDraft] = useState<Draft | null>(() => seed.current ? { ...blank(), ...seed.current } : null);
  const [creating, setCreating] = useState(!!seed.current);
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [picker, setPicker] = useState(false);
  const [explain, setExplain] = useState(false);
  const createKey = useRef(crypto.randomUUID());
  const enrollmentKeys = useRef<Record<string, string>>({});
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const report = (error: unknown) => { if (!(error instanceof DOMException && error.name === "AbortError") && mounted.current) setError(error instanceof Error ? error.message : String(error)); };
  const openReview = async (enroll: boolean) => {
    if (!record) return;
    const identity = selectionVersion.current; const id = record.id; setBusy(true);
    try {
      const key = enrollmentKeys.current[id] ?? (enrollmentKeys.current[id] = crypto.randomUUID());
      if (enroll) await mutate(`/reviews/cards/${id}/enroll`, { idempotencyKey: key });
      if (mounted.current && selectionVersion.current === identity) onReview?.(id);
    } catch (error) { if (selectionVersion.current === identity) report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => listRecords(kind, query, offset, controller.signal).then(setPage).catch(report), 180);
    return () => { controller.abort(); clearTimeout(timer); };
  }, [kind, query, offset, revision]);
  useEffect(() => {
    if (kind !== "knowledge" || !selectedId) { setLinkedCards([]); return; }
    const controller = new AbortController();
    api<Page<LearningRecord>>("/cards?limit=100&offset=0", { signal: controller.signal })
      .then(items => { if (mounted.current) setLinkedCards(items.items.filter(item => item.knowledgeId === selectedId)); })
      .catch(() => { if (mounted.current) setLinkedCards([]); });
    return () => controller.abort();
  }, [kind, selectedId, revision]);
  useEffect(() => {
    if (!selectedId) { setRecord(null); if (!creating) setDraft(null); return; }
    const controller = new AbortController();
    setLoading(true); setError("");
    api<LearningRecord>(`/${kind}/${selectedId}`, { signal: controller.signal }).then(item => {
      setRecord(item); setDraft(fromRecord(item)); setCreating(false); setDirty(false);
    }).catch(report).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [kind, selectedId]);
  useEffect(() => {
    const restore = () => { setCreating(false); select(new URLSearchParams(location.search).get("recordId") ?? ""); setMessage(""); };
    addEventListener("popstate", restore); return () => removeEventListener("popstate", restore);
  }, []);

  const start = (prefill?: Partial<Draft>) => {
    recordUrl(kind); select(""); setRecord(null); setCreating(true); setDraft({ ...blank(), ...prefill });
    setDirty(false); setMessage(""); setError(""); createKey.current = crypto.randomUUID();
  };
  const edit = (field: keyof Draft, value: string) => { setDraft(previous => previous ? { ...previous, [field]: value } : previous); setDirty(true); setMessage(""); };
  const addSource = (source: EvidenceSource) => {
    setDraft(previous => previous ? { ...previous, sources: [...previous.sources.filter(s => s.id !== source.id), source] } : previous);
    setDirty(true); setPicker(false); setMessage("");
  };
  const attachCurrent = async () => {
    if (!currentSource) return;
    setBusy(true);
    try { const source = await mutate<EvidenceSource>("/sources", currentSource); if (mounted.current) addSource(source); }
    catch (error) { report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  const checkCurrent = async (source: EvidenceSource) => {
    const checkingSelection = selectionVersion.current;
    setBusy(true); setError("");
    try {
      const checked = await mutate<SourceCheck>(`/sources/${source.id}/check-current`, {});
      if (mounted.current && selectionVersion.current === checkingSelection)
        setSourceChecks(previous => ({ ...previous, [source.id]: checked }));
    } catch (error) { if (selectionVersion.current === checkingSelection) report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  const save = async () => {
    if (!draft) return;
    setBusy(true); setError(""); setMessage("");
    const savingSelection = selectionVersion.current;
    const fields = kind === "cards" ? { question: draft.question, answer: draft.answer, explanation: draft.explanation,
      codeExample: draft.codeExample, notes: draft.notes, knowledgeId: draft.knowledgeId }
      : { title: draft.title, body: draft.body, generationId: draft.generationId, type: draft.type };
    const payload = { ...fields, tags: draft.tags.split(/[,，]/).map(tag => tag.trim()).filter(Boolean), sourceIds: draft.sources.map(s => s.id),
      ...(selectedId ? { revision: draft.revision } : { idempotencyKey: createKey.current }) };
    try {
      const item = await mutate<LearningRecord>(`/${kind}${selectedId ? `/${selectedId}` : ""}`, payload, selectedId ? "PUT" : "POST");
      if (!mounted.current || selectionVersion.current !== savingSelection) return;
      setRecord(item); setDraft(fromRecord(item)); setDirty(false); setCreating(false); setMessage("已保存");
      if (selectedId !== item.id) { recordUrl(kind, item.id); select(item.id); }
      setRevision(value => value + 1);
    } catch (error) { report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  const remove = async () => {
    if (!selectedId) return;
    if (!window.confirm(`确认删除这条${names[kind]}？`)) return;
    setBusy(true);
    const deletingSelection = selectionVersion.current;
    try {
      await api(`/${kind}/${selectedId}`, { method: "DELETE" });
      if (!mounted.current || selectionVersion.current !== deletingSelection) return;
      recordUrl(kind); select(""); setDraft(null); setRecord(null); setCreating(false); setMessage("已删除"); setRevision(value => value + 1);
    } catch (error) { report(error); }
    finally { if (mounted.current) setBusy(false); }
  };
  const derive = (destination: "knowledge" | "cards") => {
    if (!draft) return;
    const prefill = destination === "knowledge" ? { title: draft.title, body: draft.body, tags: draft.tags, sources: draft.sources }
      : { question: draft.title, answer: draft.body, knowledgeId: selectedId, tags: draft.tags, sources: draft.sources };
    sessionStorage.setItem(`codeatlas-draft-${destination}`, JSON.stringify(prefill));
    onOpenRecord(destination, "");
  };
  useEffect(() => {
    const key = `codeatlas-draft-${kind}`;
    sessionStorage.removeItem(key);
  }, []);

  const cardMode = kind === "cards";
  return <main className={`learning-workspace ${cardMode ? "cards-mode" : ""}`}>
    {!cardMode && <aside className="learning-list"><div className="learning-list-heading"><h1>{names[kind]}</h1><button className="primary" onClick={() => start()} disabled={busy}><Plus size={14}/>新建{names[kind]}</button></div>
      <div className="search"><Search size={14}/><input aria-label={`搜索${names[kind]}`} placeholder="搜索标题、内容或标签…" value={query} onChange={e => { setQuery(e.target.value); setOffset(0); }}/></div>
      <div className="learning-list-items">{page?.items.map(item => <button disabled={busy} className={`learning-list-item ${selectedId === item.id ? "selected" : ""}`} key={item.id} onClick={() => { recordUrl(kind, item.id); select(item.id); setCreating(false); setMessage(""); }}><strong>{item.title}</strong><p>{(item.body ?? item.answer ?? "").slice(0, 120)}</p><small>{item.tags.join(" · ") || names[kind]} · {new Date(item.updatedAt).toLocaleDateString("zh-CN")}</small></button>)}
        {!page?.items.length && <p className="hint">{query ? "没有匹配资料" : `还没有${names[kind]}，可以直接创建或从源码解释保存。`}</p>}</div>
      <div className="list-pagination"><button disabled={!offset} onClick={() => setOffset(Math.max(0, offset - 30))}>上一页</button><span>共 {page?.total ?? 0} 条</span><button disabled={!page || offset + 30 >= page.total} onClick={() => setOffset(offset + 30)}>下一页</button></div>
    </aside>}
    <section className="learning-editor">
      {error && <p className="learning-error" role="alert">{error}</p>}
      {message && <p className="learning-success" role="status">{message}</p>}
      {!draft ? (cardMode ? <section className="card-wall">
        <div className="card-wall-heading"><div><h2>卡片墙</h2><p>每张卡片同时保留八股文知识、源码片段与代码解析；点击卡片可编辑或加入复习。</p></div>
          <div className="button-row"><div className="search"><Search size={14}/><input aria-label="搜索卡片" placeholder="搜索问题、知识、代码或标签…" value={query} onChange={e => { setQuery(e.target.value); setOffset(0); }}/></div>
            <button className="primary" onClick={() => start()} disabled={busy}><Plus size={14}/>新建卡片</button></div></div>
        {page?.items.length ? <div className="card-grid">{page.items.map(item => <article className="study-card" key={item.id} role="button" tabIndex={0}
          onClick={() => { recordUrl(kind, item.id); select(item.id); setCreating(false); setMessage(""); }}
          onKeyDown={event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); recordUrl(kind, item.id); select(item.id); setCreating(false); setMessage(""); } }}>
          <header><span>{item.tags[0] ?? "八股文"}</span><small>{new Date(item.updatedAt).toLocaleDateString("zh-CN")}</small></header>
          <h3>{item.question}</h3>
          {item.answer && <p className="study-card-answer">{item.answer}</p>}
          {item.codeExample && <pre className="study-card-code">{item.codeExample}</pre>}
          {item.explanation && <div className="study-card-analysis"><span>代码解析</span><p>{item.explanation}</p></div>}
          <footer><span>{item.sources.length} 个源码来源</span><span>{item.knowledgeId ? "已关联知识" : "独立卡片"}</span></footer>
        </article>)}</div> : <p className="hint">{query ? "没有匹配卡片" : "还没有卡片；AI 项目出题保存后会自动生成知识卡片，也可以手工新建。"}</p>}
        <div className="list-pagination"><button disabled={!offset} onClick={() => setOffset(Math.max(0, offset - 30))}>上一页</button><span>共 {page?.total ?? 0} 张</span><button disabled={!page || offset + 30 >= page.total} onClick={() => setOffset(offset + 30)}>下一页</button></div>
      </section> : <div className="code-empty"><h2>选择或新建{names[kind]}</h2><p>学习资料与源码版本一起保存，手工编辑随时可用。</p></div>) : <>
        <div className="learning-editor-heading"><h2>{creating ? `新建${names[kind]}` : names[kind]}<span>{dirty ? "未保存" : record ? `版本 ${record.revision}` : "草稿"}</span></h2><div className="button-row">{cardMode && <button onClick={() => { recordUrl(kind); select(""); setDraft(null); setCreating(false); }}>返回卡片墙</button>}{selectedId && <button onClick={remove} disabled={busy || loading}><Trash2 size={14}/>删除</button>}<button className="primary" onClick={save} disabled={busy || loading}><Save size={14}/>保存{names[kind]}</button></div></div>
        <fieldset disabled={busy || loading} className="learning-fields">
          {kind === "cards" && record && onReview && <div className="button-row"><button disabled={dirty} onClick={() => openReview(true)}>加入复习</button><button onClick={() => openReview(false)}>复习历史</button>{dirty && <small>保存卡片更改后可加入复习。</small>}</div>}
          {kind === "cards" ? <><label>问题<input aria-label="问题" value={draft.question} onChange={e => edit("question", e.target.value)} maxLength={500}/></label><label>答案<textarea aria-label="答案" value={draft.answer} onChange={e => edit("answer", e.target.value)} rows={6} maxLength={16000}/></label><label>解释<textarea aria-label="卡片解释" value={draft.explanation} onChange={e => edit("explanation", e.target.value)} rows={3}/></label><label>代码实例<textarea className="code-textarea" aria-label="代码实例" value={draft.codeExample} onChange={e => edit("codeExample", e.target.value)} rows={4}/></label><label>个人笔记<textarea aria-label="个人笔记" value={draft.notes} onChange={e => edit("notes", e.target.value)} rows={3}/></label>{record?.knowledgeDeleted && <p className="hint">关联知识已删除，这张卡片保留独立内容与源码来源。</p>}</>
            : <><label>标题<input aria-label="标题" value={draft.title} onChange={e => edit("title", e.target.value)} maxLength={200}/></label><label>正文<textarea className="document-body" aria-label="正文" value={draft.body} onChange={e => edit("body", e.target.value)} rows={14} maxLength={kind === "documents" ? 64000 : 32000}/></label></>}
          <label>标签<input aria-label="标签" placeholder="使用逗号分隔，最多 10 个" value={draft.tags} onChange={e => edit("tags", e.target.value)}/></label>
          <section className="record-sources"><div className="record-sources-heading"><h3>源码来源</h3><div className="button-row">{currentSource && <button onClick={attachCurrent} type="button">关联当前源码</button>}<button onClick={() => setPicker(true)} type="button"><Link2 size={13}/>选择来源</button></div></div>
            {draft.sources.map(source => <div className="record-source" key={source.id}><div><strong>{source.symbolName || source.filePath}</strong><small>{source.filePath} · L{source.startLine}–L{source.endLine} · 快照 {source.snapshotId.slice(0, 8)}</small>{source.snapshotOutdated && <span className="old-snapshot">该项目存在较新快照，当前引用保留历史版本。</span>}
              <span className={`current-source-state ${sourceChecks[source.id]?.status.toLowerCase() ?? "unchecked"}`}>
                {sourceChecks[source.id] ? <>{sourceChecks[source.id].message}<small>检查时间 {new Date(sourceChecks[source.id].checkedAt).toLocaleString("zh-CN")}</small></> : "未检查当前源码"}
              </span></div><button type="button" onClick={() => checkCurrent(source)}>检查当前源码</button><button type="button" onClick={() => onSource(source)}>定位源码<ArrowRight size={12}/></button><button type="button" aria-label="移除来源" onClick={() => { setDraft({ ...draft, sources: draft.sources.filter(s => s.id !== source.id) }); setDirty(true); }}>移除</button></div>)}
            {!draft.sources.length && <p className="hint">可关联不同项目的固定快照。</p>}
          </section>
          {kind === "knowledge" && <section className="record-sources linked-cards"><div className="record-sources-heading"><h3>关联卡片 <span>{linkedCards.length}</span></h3></div>
            {linkedCards.map(item => <button type="button" className="linked-card" key={item.id} onClick={() => onOpenRecord("cards", item.id)}><strong>{item.question}</strong><small>{(item.answer ?? "").slice(0, 120)}</small></button>)}
            {!linkedCards.length && <p className="hint">还没有关联卡片；AI 项目出题保存时会自动为每个知识点生成一张卡片。</p>}
          </section>}
        </fieldset>
        <div className="learning-bottom-actions">{kind === "documents" && <button onClick={() => derive("knowledge")} disabled={busy}>提炼知识<ArrowRight size={13}/></button>}{kind === "knowledge" && <button onClick={() => derive("cards")} disabled={busy || !selectedId || dirty}>从知识创建卡片<ArrowRight size={13}/></button>}{kind !== "cards" && !!draft.sources.length && <button onClick={() => setExplain(true)} disabled={busy}><Sparkles size={14}/>基于来源生成说明</button>}</div>
      </>}
    </section>
    {picker && <SourcePicker onSelect={addSource} onClose={() => setPicker(false)}/>}
    {explain && draft && <AiPanel sourceIds={draft.sources.map(s => s.id)} onClose={() => setExplain(false)} onSource={onSource} onOpenRecord={(destination, id) => {
      setExplain(false);
      if (destination === kind) { recordUrl(kind, id); select(id); setCreating(false); setRevision(value => value + 1); }
      else onOpenRecord(destination, id);
    }}/>}
  </main>;
}
