import { useEffect, useRef, useState } from "react";
import type { EvidenceSource } from "../learning/learningApi";
import useStudyLocation from "../study/useStudyLocation";
import { errorText, get, gradeNames, studyUrl, timeText, write, type ReviewAttempt, type ReviewEvent, type ReviewState, type StudyPage } from "../study/studyApi";
import ReviewAttemptView from "./ReviewAttemptView";
import ReviewEventDetails from "./ReviewEventDetails";
import KnowledgePicker from "../progress/KnowledgePicker";
import EvidencePicker from "../progress/EvidencePicker";
import "../study/study.css";

function CardDetail({ id, zone, eventId, onRefresh, onSource }: { id: string; zone: string; eventId: string; onRefresh: () => void; onSource: (source: EvidenceSource) => void }) {
  const [state, setState] = useState<ReviewState | null>(null); const [events, setEvents] = useState<StudyPage<ReviewEvent> | null>(null);
  const [offset, setOffset] = useState(0); const [revision, setRevision] = useState(0); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const alive = useRef(true); const keys = useRef<Record<string, string>>({});
  const [selectedEvent, setSelectedEvent] = useState<ReviewEvent | null>(null); const [association, setAssociation] = useState<ReviewEvent | null>(null);
  useEffect(() => {
    const controller = new AbortController(); setSelectedEvent(null);
    if (eventId) get<ReviewEvent>(`/reviews/events/${eventId}`, controller.signal).then(item => {
      if (item.cardId !== id) throw new Error("该历史事件属于其他卡片");
      if (!controller.signal.aborted) setSelectedEvent(item);
    }).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => controller.abort();
  }, [id, eventId]);
  useEffect(() => {
    alive.current = true; const controller = new AbortController();
    get<ReviewState>(`/reviews/cards/${id}/state`, controller.signal).then(setState).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    get<StudyPage<ReviewEvent>>(`/reviews/cards/${id}/history?limit=20&offset=${offset}`, controller.signal).then(setEvents).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => { alive.current = false; controller.abort(); };
  }, [id, revision, offset]);
  const act = async (operation: string) => {
    setBusy(true); setError("");
    const name = `${operation}-${state?.epoch}-${state?.revision}`; const key = keys.current[name] ?? (keys.current[name] = crypto.randomUUID());
    try {
      if (operation === "start") {
        const next = await write<ReviewAttempt>(`/reviews/cards/${id}/attempts`, { idempotencyKey: key });
        if (alive.current) studyUrl("reviews", { reviewCardId: id, reviewAttemptId: next.id });
      } else {
        const next = await write<ReviewState>(`/reviews/cards/${id}/${operation}`, operation === "enroll" ? { idempotencyKey: key } : { revision: state!.revision, epoch: state!.epoch, idempotencyKey: key });
        if (alive.current) { setState(next); setRevision(previous => previous + 1); onRefresh(); }
      }
    } catch (error) { if (alive.current) setError(errorText(error)); }
    finally { if (alive.current) setBusy(false); }
  };
  return <section className="study-detail"><h2>{state?.card.question ?? "卡片复习"}</h2>{error && <p role="alert" className="study-error">{error}</p>}
    {state && <p className="review-card-state">轮次 {state.epoch} · {state.status === "CONTENT_CHANGED" ? "卡片内容已更新" : state.status === "PAUSED" ? "已暂停" : state.status === "DELETED" ? "卡片已删除" : "复习中"} · 下次复习 {timeText(state.dueAtMs, zone)}</p>}
    <div className="button-row">{!state ? <button disabled={busy} onClick={() => act("enroll")}>加入复习</button> : <>
      <button className="primary" disabled={busy || state.status !== "ACTIVE"} onClick={() => act("start")}>开始复习</button>
      {state.status !== "DELETED" && <><button disabled={busy} onClick={() => act(state.suspended ? "resume" : "suspend")}>{state.suspended ? "恢复复习" : "暂停复习"}</button><button disabled={busy} onClick={() => act("reset")}>采用新版并重新学习</button></>}
      <button onClick={() => studyUrl("cards", { recordId: id })}>编辑卡片</button></>}</div>
    {selectedEvent && <section className="review-reference review-selected-event"><h3>选定的历史事件</h3><ReviewEventDetails event={selectedEvent} zone={zone} onSource={onSource} onAssociate={() => setAssociation(selectedEvent)}/></section>}
    <h3>复习历史</h3>{events?.items.map(event => <details className="review-history-item" data-kind={event.kind} key={event.id}><summary>{event.kind === "REVIEW" ? `${gradeNames[event.payload.rating!]} · 用户自报` : ({ ENROLL: "加入复习", RESET: "重新学习", SUSPEND: "暂停复习", RESUME: "恢复复习" }[event.kind] ?? event.kind)} · {timeText(event.occurredAtMs, zone)} · 轮次 {event.epoch}</summary>
      <ReviewEventDetails event={event} zone={zone} onSource={onSource} onAssociate={() => setAssociation(event)}/>
    </details>)}{events?.total === 0 && <p className="study-empty">尚无复习事件。</p>}
    <div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>较新记录</button><button disabled={!events || offset + 20 >= events.total} onClick={() => setOffset(previous => previous + 20)}>较早记录</button></div>
    {association && <EvidencePicker sourceType="REVIEW_RATING" sourceId={association.id} onClose={() => setAssociation(null)} onAssociated={() => { if (alive.current) setRevision(previous => previous + 1); }}/>}
  </section>;
}

export default function ReviewWorkspace({ onSource }: { onSource: (source: EvidenceSource) => void }) {
  const params = useStudyLocation(); const cardId = params.get("reviewCardId") ?? ""; const attemptId = params.get("reviewAttemptId") ?? "";
  const [page, setPage] = useState<StudyPage<{ cardId: string; title: string; dueAtMs: number }> | null>(null);
  const [dueOnly, setDueOnly] = useState(true); const [query, setQuery] = useState(""); const [offset, setOffset] = useState(0); const [revision, setRevision] = useState(0);
  const [error, setError] = useState(""); const [zone, setZone] = useState(localStorage.getItem("codeatlas-review-zone") || "Asia/Shanghai");
  const [tag, setTag] = useState(""); const [knowledge, setKnowledge] = useState<{ id: string; title: string } | null>(null); const [picker, setPicker] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    get<typeof page>(`/reviews/cards?dueOnly=${dueOnly}&query=${encodeURIComponent(query)}&knowledgeId=${knowledge?.id ?? ""}&tag=${encodeURIComponent(tag)}&limit=20&offset=${offset}`, controller.signal).then(setPage).catch(error => { if (!controller.signal.aborted) setError(errorText(error)); });
    return () => controller.abort();
  }, [dueOnly, query, knowledge?.id, tag, offset, revision, params.toString()]);
  return <main className="study-workspace"><aside className="study-list"><h1>复习</h1><label>搜索复习卡片<input aria-label="搜索复习卡片" value={query} onChange={event => { setQuery(event.target.value); setOffset(0); }}/></label>
    <label className="authorization"><input type="checkbox" checked={dueOnly} onChange={event => { setDueOnly(event.target.checked); setOffset(0); }}/>只看到期</label>
    <label>标签筛选<input aria-label="标签筛选" value={tag} maxLength={32} onChange={event => { setTag(event.target.value); setOffset(0); }}/></label><button onClick={() => setPicker(true)}>按知识筛选</button>{knowledge && <p>{knowledge.title} <button onClick={() => { setKnowledge(null); setOffset(0); }}>清除知识筛选</button></p>}
    <label>显示时区<select aria-label="显示时区" value={zone} onChange={event => { setZone(event.target.value); localStorage.setItem("codeatlas-review-zone", event.target.value); }}><option value="Asia/Shanghai">上海</option><option value="UTC">UTC</option><option value="America/New_York">纽约</option></select></label>
    {error && <p className="study-error">{error}</p>}{page?.items.map(item => <button key={item.cardId} className={`study-list-item ${cardId === item.cardId ? "selected" : ""}`} onClick={() => studyUrl("reviews", { reviewCardId: item.cardId })}><strong>{item.title}</strong><small>{timeText(item.dueAtMs, zone)}</small></button>)}
    {page?.total === 0 && <p className="study-empty">当前没有匹配卡片，可从卡片详情加入复习。</p>}<div className="button-row"><button disabled={!offset} onClick={() => setOffset(previous => Math.max(0, previous - 20))}>上一页</button><button disabled={!page || offset + 20 >= page.total} onClick={() => setOffset(previous => previous + 20)}>下一页</button></div>
  </aside><div className="study-content">{attemptId ? <ReviewAttemptView key={attemptId} id={attemptId} zone={zone} onSource={onSource}/> : cardId ? <CardDetail key={cardId} id={cardId} eventId={params.get("reviewEventId") ?? ""} zone={zone} onSource={onSource} onRefresh={() => setRevision(previous => previous + 1)}/> : <section className="study-detail"><h2>主动回忆与间隔复习</h2><p>选择一张卡片开始回忆，评分后查看下一次安排和逐次历史。</p></section>}</div>
    {picker && <KnowledgePicker onClose={() => setPicker(false)} onSelect={item => { setKnowledge({ id: item.id, title: item.title }); setOffset(0); }}/>}
  </main>;
}
