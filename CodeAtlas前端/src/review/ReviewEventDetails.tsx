import type { EvidenceSource } from "../learning/learningApi";
import { gradeNames, timeText, type ReviewEvent } from "../study/studyApi";

export default function ReviewEventDetails({ event, zone, onSource, onAssociate }: { event: ReviewEvent; zone: string; onSource: (source: EvidenceSource) => void; onAssociate: () => void }) {
  return <><h4>{event.payload.card.question}</h4><p>当时答案 {event.payload.card.answer}</p><small>卡片版本 {event.payload.card.revision} · {event.payload.card.contentDigest}</small>
    {event.kind === "REVIEW" && <><p>{gradeNames[event.payload.rating!]} · 用户自报 · {timeText(event.occurredAtMs, zone)}</p><p>当时回忆 {event.payload.recalledAnswer || "未填写"}</p><p>{event.payload.reflection}</p><p>下次复习 {timeText(event.payload.dueAtMs!, zone)}</p><button onClick={onAssociate}>关联知识</button></>}
    <div className="button-row">{event.payload.card.sources.map(source => <button key={source.id} onClick={() => onSource(source)}>{source.filePath} · L{source.startLine}–L{source.endLine}</button>)}</div>
    <small>{event.algorithmId} · {event.payload.configVersion} · {event.configHash}</small>
    <details><summary>调度前后状态</summary><pre>{JSON.stringify({ before: event.payload.before, after: event.payload.after, algorithmLog: event.payload.algorithmLog }, null, 2)}</pre></details>
    <details><summary>调度配置</summary><pre>{JSON.stringify(event.payload.configuration, null, 2)}</pre></details>
  </>;
}
