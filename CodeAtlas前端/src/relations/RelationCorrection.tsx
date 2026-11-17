import { useEffect, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { post, type Relation, type RelationCorrection } from "../api";

export const correctionVerdicts: Record<RelationCorrection["verdict"], string> = {
  CORRECTED: "用户修正",
  REJECTED: "用户标记不成立",
  UNCERTAIN: "用户标记存疑",
};

const relationKinds: Record<string, string> = {
  CALL: "静态调用",
  RUNTIME_CALL: "运行时调用",
  EXTENDS: "继承",
  IMPLEMENTS: "接口实现",
  TRANSACTION_BOUNDARY: "事务候选",
};

export default function RelationCorrectionForm({
  snapshotId,
  generationId,
  relation,
  onSaved,
}: {
  snapshotId: string;
  generationId: string;
  relation: Relation;
  onSaved: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [verdict, setVerdict] = useState<RelationCorrection["verdict"]>("CORRECTED");
  const [kind, setKind] = useState("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [source, setSource] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());

  useEffect(() => {
    if (!open) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    addEventListener("keydown", closeOnEscape);
    return () => removeEventListener("keydown", closeOnEscape);
  }, [open]);

  const show = () => {
    setVerdict("CORRECTED");
    setKind("");
    setTarget("");
    setReason("");
    setSource("");
    setError("");
    key.current = crypto.randomUUID();
    setOpen(true);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await post<RelationCorrection>(
        `/snapshots/${snapshotId}/relations/${relation.id}/corrections`,
        {
          generationId,
          verdict,
          correctedKind: verdict === "CORRECTED" && kind ? kind : null,
          correctedTargetText: verdict === "CORRECTED" && target.trim() ? target.trim() : null,
          reason: reason.trim(),
          source: source.trim() || null,
          idempotencyKey: key.current,
        },
      );
      key.current = crypto.randomUUID();
      setOpen(false);
      onSaved();
    } catch (error) {
      setError(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button disabled={!relation.id || !generationId} onClick={show}>
        修正关系
      </button>
      {open &&
        createPortal(
          <div className="modal-backdrop" onClick={() => setOpen(false)}>
            <section
              className="modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="relation-correction-title"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="modal-title">
                <h2 id="relation-correction-title">修正关系</h2>
                <button onClick={() => setOpen(false)} aria-label="关闭修正">
                  <X size={18} />
                </button>
              </div>
              <p>
                原始分析与证据状态保持不变。修正记录会保留结论、理由、来源和时间，并在关系卡片与图谱证据中并存显示。
              </p>
              <form className="correction-form" onSubmit={submit}>
                <label className="path-label">
                  修正结论
                  <select
                    value={verdict}
                    onChange={(event) =>
                      setVerdict(event.target.value as RelationCorrection["verdict"])
                    }
                  >
                    <option value="CORRECTED">修正关系</option>
                    <option value="REJECTED">标记不成立</option>
                    <option value="UNCERTAIN">标记存疑</option>
                  </select>
                </label>
                {verdict === "CORRECTED" && (
                  <>
                    <label className="path-label">
                      修正后的类别
                      <select value={kind} onChange={(event) => setKind(event.target.value)}>
                        <option value="">不修改类别</option>
                        {Object.entries(relationKinds).map(([value, label]) => (
                          <option key={value} value={value}>
                            {label} · {value}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="path-label">
                      修正后的目标
                      <input
                        value={target}
                        maxLength={1000}
                        placeholder={relation.targetText}
                        onChange={(event) => setTarget(event.target.value)}
                      />
                    </label>
                  </>
                )}
                <label className="path-label">
                  理由
                  <textarea
                    required
                    maxLength={2000}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder="说明原关系的问题或修正依据"
                  />
                </label>
                <label className="path-label">
                  来源（可选）
                  <input
                    value={source}
                    maxLength={1000}
                    onChange={(event) => setSource(event.target.value)}
                    placeholder="例如：人工核对 Service.java:L42 或外部文档链接"
                  />
                </label>
                {error && (
                  <p className="learning-error" role="alert">
                    {error}
                  </p>
                )}
                <button
                  className="primary"
                  type="submit"
                  disabled={
                    busy ||
                    !reason.trim() ||
                    (verdict === "CORRECTED" && !kind && !target.trim())
                  }
                >
                  {busy ? "保存中…" : "保存修正"}
                </button>
              </form>
            </section>
          </div>,
          document.body,
        )}
    </>
  );
}
