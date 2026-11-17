import { useState } from "react";
import { mutate, type EvidenceSource, type SourceCheck } from "../learning/learningApi";
import { message, type TrainingSource } from "./trainingApi";

export default function TrainingSources({ sources, onSource }: { sources: TrainingSource[]; onSource: (source: EvidenceSource) => void }) {
  const [checks, setChecks] = useState<Record<string, SourceCheck>>({});
  const [error, setError] = useState("");
  const check = async (source: TrainingSource) => {
    try {
      const result = await mutate<SourceCheck>(`/sources/${source.id}/check-current`, {});
      setChecks(previous => ({ ...previous, [source.id]: result }));
    } catch (error) { setError(message(error)); }
  };
  return <section className="training-sources"><h3>固定源码来源</h3>{error && <p role="alert">{error}</p>}
    {sources.map(source => <details key={source.id}><summary>{source.filePath} · L{source.startLine}–L{source.endLine}{source.snapshotOutdated && " · 已有新快照"}</summary>
      <small>快照 {source.snapshotId} · 代次 {source.generationId} · SHA-256 {source.sha256}</small>
      {source.availability === "UNAVAILABLE" ? <p>{source.availabilityMessage}</p> : <><pre>{source.content}</pre><div className="button-row"><button onClick={() => onSource(source)}>定位源码</button><button onClick={() => check(source)}>检查当前源码</button></div></>}
      {checks[source.id] && <p>{checks[source.id].message}</p>}
    </details>)}
  </section>;
}
