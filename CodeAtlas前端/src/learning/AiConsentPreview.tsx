import type { PreparedContext } from "./learningApi";

export default function AiConsentPreview({ context, agreed, busy, onAgree, onDeny, onEdit, onGenerate }: {
  context: PreparedContext; agreed: boolean; busy: boolean; onAgree: (value: boolean) => void;
  onDeny: () => void; onEdit: () => void; onGenerate: () => void;
}) {
  return <section className="consent-preview"><h3>确认外发范围</h3>
    <p>{context.provider.provider} · {context.provider.model} · {context.provider.baseUrl}</p>
    <p>{context.characterCount.toLocaleString()} 字符 · {context.byteCount.toLocaleString()} 字节 · 最多 {context.provider.maxTokens} 输出 tokens</p>
    <p>预览有效期至 {new Date(context.expiresAt).toLocaleTimeString("zh-CN")}。模型调用可能产生费用。</p>
    {context.sources.map(source => <div className="preview-source" key={source.sourceId ?? source.id}><strong>{source.filePath} · L{source.startLine}–L{source.endLine}</strong><small>快照 {source.snapshotId} · SHA-256 {source.sha256.slice(0, 12)}</small><pre>{source.content}</pre></div>)}
    <details className="payload-details"><summary>查看完整发送文本、问题与历史</summary><pre>{JSON.stringify(JSON.parse(context.contextJson), null, 2)}</pre></details>
    <label className="authorization"><input type="checkbox" checked={agreed} onChange={event => onAgree(event.target.checked)}/>我同意向上述供应商发送本次预览内容，用于所选用途。</label>
    <div className="button-row"><button onClick={onDeny} disabled={busy}>拒绝发送</button><button onClick={onEdit} disabled={busy}>修改问题</button><button className="primary" onClick={onGenerate} disabled={!agreed || busy}>授权并生成</button></div>
  </section>;
}
