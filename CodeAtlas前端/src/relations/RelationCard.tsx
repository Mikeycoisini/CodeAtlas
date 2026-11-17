import { statusText, type Relation } from "../api";
import RelationCorrectionForm, { correctionVerdicts } from "./RelationCorrection";

function time(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function verdictClass(verdict: string): string {
  return verdict === "REJECTED" ? "unresolved" : "inferred";
}

export default function RelationCard({
  relation,
  snapshotId,
  generationId,
  onEvidence,
  onSaved,
}: {
  relation: Relation;
  snapshotId: string;
  generationId: string;
  onEvidence: () => void;
  onSaved: () => void;
}) {
  const latest = relation.latestCorrection ?? null;
  const effectiveKind = relation.effectiveKind ?? relation.kind;
  const effectiveTarget = relation.effectiveTargetText ?? relation.targetText;
  const changedKind = effectiveKind !== relation.kind;
  const changedTarget = effectiveTarget !== relation.targetText;
  return (
    <div className="relation">
      <div className="relation-meta">
        <span>{effectiveKind}</span>
        <span className={`status ${relation.status.toLowerCase()}`}>
          {statusText[relation.status] ?? relation.status}
        </span>
        {latest && (
          <span className={`status ${verdictClass(latest.verdict)}`}>
            {correctionVerdicts[latest.verdict]}
          </span>
        )}
      </div>
      <strong className={changedTarget ? "relation-effective" : ""}>{effectiveTarget}</strong>
      {changedTarget && <small className="relation-original">原始目标：{relation.targetText}</small>}
      {changedKind && <small className="relation-original">原始类别：{relation.kind}</small>}
      <p>{relation.reason}</p>
      {latest && (
        <div className={`relation-correction verdict-${latest.verdict.toLowerCase()}`}>
          <strong>{correctionVerdicts[latest.verdict]}</strong>
          <p>{latest.reason}</p>
          {latest.source && <small>来源：{latest.source}</small>}
          <small>{time(latest.createdAt)}</small>
          {relation.corrections && relation.corrections.length > 1 && (
            <details>
              <summary>全部 {relation.corrections.length} 条修正记录</summary>
              {relation.corrections.map((correction) => (
                <div key={correction.id}>
                  <small>
                    {time(correction.createdAt)} · {correctionVerdicts[correction.verdict]}
                  </small>
                  <p>{correction.reason}</p>
                  {correction.source && <small>来源：{correction.source}</small>}
                </div>
              ))}
            </details>
          )}
        </div>
      )}
      <div className="button-row">
        <button onClick={onEvidence}>
          查看证据 · L{relation.startLine}
        </button>
        <RelationCorrectionForm
          snapshotId={snapshotId}
          generationId={generationId}
          relation={relation}
          onSaved={onSaved}
        />
      </div>
    </div>
  );
}
