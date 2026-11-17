import { useEffect, useRef, useState } from "react";
import type { EvidenceSource } from "../learning/learningApi";
import SourcePicker from "../learning/SourcePicker";
import KnowledgePicker from "../progress/KnowledgePicker";
import useStudyLocation from "../study/useStudyLocation";
import { errorText, get, guideNames, studyUrl, timeText, write, type Guide, type GuideStep, type StudyPage } from "../study/studyApi";
import GuideAiPanel from "./GuideAiPanel";
import "../study/study.css";

const blank = (): Guide => ({ id: "", revision: 0, title: "", goal: "", status: "ACTIVE", steps: [], sourceIds: [], knowledgeIds: [], sources: [], knowledge: [], doneCount: 0, skippedCount: 0 });
const body = (guide: Guide) => ({ title: guide.title, goal: guide.goal, revision: guide.revision, steps: guide.steps, sourceIds: guide.sourceIds, knowledgeIds: guide.knowledgeIds });

function GuideEditor({ id, draftId, onChanged, onSource }: { id: string; draftId: string; onChanged: () => void; onSource: (source: EvidenceSource) => void }) {
  const [guide, setGuide] = useState<Guide | null>(null); const [dirty, setDirty] = useState(false); const [busy, setBusy] = useState(false);
  const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [picker, setPicker] = useState<"source" | "knowledge" | null>(null);
  const [aiOpen, setAiOpen] = useState(!!new URLSearchParams(location.search).get("guideAiGenerationId"));
  const [events, setEvents] = useState<StudyPage<{ id: string; revision: number; kind: string; createdAtMs: number; payload: unknown }> | null>(null);
  const [historyOffset, setHistoryOffset] = useState(0);
  const alive = useRef(true); const keys = useRef<Record<string, string>>({}); const storageKey = `codeatlas-guide-${id || draftId}`;
  const key = (name: string) => keys.current[name] ?? (keys.current[name] = crypto.randomUUID());
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    const load = async () => {
      try { const cached = JSON.parse(sessionStorage.getItem(storageKey) ?? "null"); if (cached?.dirty && cached.guide) { setGuide(cached.guide); setDirty(true); return; } } catch { /* Restore the server version when a browser draft is unreadable. */ }
      const item = id ? await get<Guide>(`/guides/${id}`, controller.signal) : blank();
      if (!controller.signal.aborted) setGuide(item);
    };
    load().catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id]);
  useEffect(() => { if (dirty && guide) { try { sessionStorage.setItem(storageKey, JSON.stringify({ dirty, guide })); } catch { setNotice("浏览器会话空间不足，请保存到服务器。"); } } }, [guide, dirty, storageKey]);
  const edit = (values: Partial<Guide>) => { setDirty(true); setGuide(previous => previous && ({ ...previous, ...values })); };
  const editStep = (id: string, values: Partial<GuideStep>) => { setDirty(true); setGuide(previous => previous && ({ ...previous, steps: previous.steps.map(step => step.id === id ? { ...step, ...values } : step) })); };
  const move = (index: number, delta: number) => { if (!guide) return; const steps = [...guide.steps]; [steps[index], steps[index + delta]] = [steps[index + delta], steps[index]]; edit({ steps }); };
  const accept = (saved: Guide) => { setGuide(saved); setDirty(false); sessionStorage.removeItem(storageKey); setEvents(null); onChanged(); };
  const save = async () => {
    if (!guide) throw new Error("请先加载引导");
    const saved = guide.id ? await write<Guide>(`/guides/${guide.id}`, body(guide), "PUT")
      : await write<Guide>("/guides", { title: guide.title, goal: guide.goal, sourceIds: guide.sourceIds, knowledgeIds: guide.knowledgeIds, idempotencyKey: key("create") });
    if (alive.current) {
      accept(saved); setNotice("已保存引导");
      if (!guide.id) studyUrl("guides", { guideId: saved.id });
    }
    return saved;
  };
  const run = async (work: () => Promise<void>) => {
    setBusy(true); setError(""); setNotice("");
    try { await work(); } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  const action = (action: string) => run(async () => {
    if (!guide) return;
    const current = dirty ? await save() : guide;
    const next = await write<Guide>(`/guides/${current.id}/actions`, { revision: current.revision, action, idempotencyKey: key(`${action}-${current.revision}`) });
    if (alive.current) accept(next);
  });
  const reload = () => run(async () => { if (id) { const latest = await get<Guide>(`/guides/${id}`); if (alive.current) accept(latest); } });
  const closeAi = () => { setAiOpen(false); const params = new URLSearchParams(location.search); params.delete("guideAiGenerationId"); history.replaceState(null, "", `?${params}`); };
  const openAi = () => run(async () => { if (dirty) await save(); if (alive.current) setAiOpen(true); });
  const showHistory = (offset = 0) => run(async () => { if (!guide?.id) return; const result = await get<typeof events>(`/guides/${guide.id}/history?limit=20&offset=${offset}`); if (alive.current) { setEvents(result); setHistoryOffset(offset); } });
  return <section className="study-detail">{error && <p role="alert" className="study-error">{error}</p>}{notice && <p role="status" className="study-notice">{notice}</p>}{guide ? <>
    <div className="study-heading"><h2>{guide.id ? guide.title : "新建学习引导"}</h2><span className="guide-state">{guideNames[guide.status]} · 版本 {guide.revision}{dirty && " · 未保存"}</span></div>
    {guide.id && <button disabled={busy} onClick={reload}>放弃本地修改并加载最新版本</button>}
    <fieldset disabled={busy || guide.status === "COMPLETED"}><label>引导标题<input aria-label="引导标题" value={guide.title} maxLength={200} onChange={event => edit({ title: event.target.value })}/></label><label>引导目标<textarea aria-label="引导目标" value={guide.goal} maxLength={4000} onChange={event => edit({ goal: event.target.value })}/></label>
      <div className="button-row"><button disabled={guide.sourceIds.length >= 6} onClick={() => setPicker("source")}>关联源码</button><button disabled={guide.knowledgeIds.length >= 10} onClick={() => setPicker("knowledge")}>关联知识</button></div>
      {guide.sources.map(source => <div className="button-row" key={source.id}><button onClick={() => onSource(source)}>{source.filePath} · L{source.startLine}–L{source.endLine}</button><button onClick={() => edit({ sources: guide.sources.filter(item => item.id !== source.id), sourceIds: guide.sourceIds.filter(id => id !== source.id) })}>移除源码</button></div>)}
      {guide.knowledge.map(knowledge => <div className="button-row" key={knowledge.id}><span>{knowledge.title}</span><button onClick={() => edit({ knowledge: guide.knowledge.filter(item => item.id !== knowledge.id), knowledgeIds: guide.knowledgeIds.filter(id => id !== knowledge.id) })}>移除知识</button></div>)}
      {guide.steps.map((step, index) => <article className="guide-step" data-step-id={step.id} key={step.id}><div className="study-heading"><h3>{step.title}</h3><div className="button-row"><button disabled={index === 0} onClick={() => move(index, -1)}>上移</button><button disabled={index === guide.steps.length - 1} onClick={() => move(index, 1)}>下移</button></div></div>
        <label>步骤标题<input aria-label="步骤标题" maxLength={200} value={step.title} onChange={event => editStep(step.id, { title: event.target.value })}/></label><label>步骤状态<select aria-label="步骤状态" value={step.status} onChange={event => editStep(step.id, { status: event.target.value })}><option value="TODO">待进行</option><option value="DONE">已完成</option><option value="SKIPPED">已跳过</option></select></label><label>步骤笔记<textarea aria-label="步骤笔记" value={step.notes} maxLength={4000} onChange={event => editStep(step.id, { notes: event.target.value })}/></label>
        {step.advice && <div className="review-reference"><strong>AI辅助建议</strong><p>{step.reason}</p><p>{step.advice}</p></div>}
        <div className="button-row"><button onClick={() => studyUrl(step.id === "SOURCE" ? "explore" : step.id === "REFLECT" ? "reviews" : step.id === "PRINCIPLE" ? "knowledge" : step.id === "CODE" || step.id === "VERIFY" ? "training" : "documents")}>打开相关功能</button></div>
        {["CODE", "VERIFY"].includes(step.id) && <p className="study-muted">可在自己的外部环境中实践，并在步骤笔记记录过程与来源。</p>}
      </article>)}
      <button className="primary" onClick={() => run(async () => { await save(); })}>保存引导</button>
    </fieldset>
    {guide.id && <><p>已完成 {guide.doneCount} · 已跳过 {guide.skippedCount} · 共 6 步</p><div className="button-row">
      {guide.status === "ACTIVE" && <><button disabled={busy} onClick={() => action("PAUSE")}>暂停引导</button><button disabled={busy} onClick={() => action("COMPLETE")}>结束引导</button></>}
      {["ACTIVE", "PAUSED"].includes(guide.status) && <button disabled={busy} onClick={() => action("EXIT")}>退出引导</button>}
      {["PAUSED", "EXITED"].includes(guide.status) && <button disabled={busy} onClick={() => action("RESUME")}>恢复引导</button>}
      {guide.status === "COMPLETED" && <button disabled={busy} onClick={() => action("REOPEN")}>重新打开引导</button>}
      <button disabled={busy || guide.status === "COMPLETED"} onClick={openAi}>AI调整建议</button><button disabled={busy} onClick={() => showHistory()}>查看引导历史</button>
    </div></>}
    {guide.suggestionSummary && <section className="review-reference"><strong>已采纳的AI建议</strong><p>{guide.suggestionSummary}</p><small>生成记录 {guide.suggestionGenerationId}</small></section>}
    {events?.items.map(event => <details className="review-history-item" key={event.id}><summary>{timeText(event.createdAtMs)} · 版本 {event.revision} · {event.kind}</summary><pre>{JSON.stringify(event.payload, null, 2)}</pre></details>)}
    {events && <div className="button-row"><button disabled={busy || !historyOffset} onClick={() => showHistory(Math.max(0, historyOffset - 20))}>较新操作</button><button disabled={busy || historyOffset + 20 >= events.total} onClick={() => showHistory(historyOffset + 20)}>较早操作</button></div>}
    {picker === "source" && <SourcePicker onClose={() => setPicker(null)} onSelect={source => { if (!guide.sourceIds.includes(source.id)) edit({ sourceIds: [...guide.sourceIds, source.id], sources: [...guide.sources, { ...source, content: "" }] }); setPicker(null); }}/>}
    {picker === "knowledge" && <KnowledgePicker onClose={() => setPicker(null)} onSelect={knowledge => { if (!guide.knowledgeIds.includes(knowledge.id)) edit({ knowledgeIds: [...guide.knowledgeIds, knowledge.id], knowledge: [...guide.knowledge, { id: knowledge.id, title: knowledge.title, revision: knowledge.revision }] }); }}/>} 
    {aiOpen && <GuideAiPanel guide={guide} restoredId={new URLSearchParams(location.search).get("guideAiGenerationId") ?? ""} onClose={closeAi} onApplied={saved => { accept(saved); closeAi(); setNotice("已采纳步骤建议，完成与跳过记录已保留"); }}/>}
  </> : <p>正在加载引导…</p>}</section>;
}

export default function GuideWorkspace({ onSource }: { onSource: (source: EvidenceSource) => void }) {
  const params = useStudyLocation(); const id = params.get("guideId") ?? ""; const draftId = params.get("guideDraftId") ?? "";
  const [page, setPage] = useState<StudyPage<Guide> | null>(null); const [query, setQuery] = useState(""); const [offset, setOffset] = useState(0); const [revision, setRevision] = useState(0); const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  useEffect(() => { const controller = new AbortController(); get<typeof page>(`/guides?query=${encodeURIComponent(query)}&status=${status}&limit=20&offset=${offset}`, controller.signal).then(setPage).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); }); return () => controller.abort(); }, [query, status, offset, revision, params.toString()]);
  return <main className="study-workspace"><aside className="study-list"><div className="study-heading"><h1>学习引导</h1><button onClick={() => studyUrl("guides", { guideDraftId: crypto.randomUUID() })}>新建引导</button></div><label>搜索引导<input aria-label="搜索引导" value={query} onChange={event => { setQuery(event.target.value); setOffset(0); }}/></label>
    <label>引导状态<select aria-label="引导状态" value={status} onChange={event => { setStatus(event.target.value); setOffset(0); }}><option value="">全部</option>{Object.entries(guideNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    {error && <p className="study-error">{error}</p>}{page?.items.map(item => <button key={item.id} className={`study-list-item ${id === item.id ? "selected" : ""}`} onClick={() => studyUrl("guides", { guideId: item.id })}><strong>{item.title}</strong><small>{guideNames[item.status]} · 完成 {item.doneCount}/6 · 跳过 {item.skippedCount}</small></button>)}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={!page || offset + 20 >= page.total} onClick={() => setOffset(previous => previous + 20)}>下一页</button></div>
  </aside><div className="study-content">{id || draftId ? <GuideEditor key={id || draftId} id={id} draftId={draftId} onChanged={() => setRevision(previous => previous + 1)} onSource={onSource}/> : <section className="study-detail"><h2>按需开启学习引导</h2><p>填写目标，按自己的顺序学习。步骤可跳过，整个引导可暂停、退出和恢复。</p></section>}</div></main>;
}
