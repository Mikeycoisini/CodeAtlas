import { useEffect, useRef, useState } from "react";
import { allFiles, api, type Page, type SourceFile, type Symbol } from "../api";
import type { SourceSelection } from "./learningApi";
export default function SourceSelector({ snapshotId, generationId, onSelect }: {
 snapshotId: string; generationId: string; onSelect: (source: SourceSelection, symbol: Symbol | null) => void;
}) {
 const [pinnedGeneration,setPinnedGeneration]=useState(generationId);
 const [files,setFiles]=useState<SourceFile[]>([]); const [file,setFile]=useState("");
 const [symbols,setSymbols]=useState<Symbol[]>([]);const [query,setQuery]=useState("");const [total,setTotal]=useState(0); const [error,setError]=useState("");
 const current=useRef("");const identity=`${snapshotId}|${generationId}|${file}|${query}`;current.current=identity;
 useEffect(()=>{const c=new AbortController();setFile("");setFiles([]); if(snapshotId) allFiles(snapshotId,c.signal).then(setFiles).catch(e=>{if(!c.signal.aborted)setError(e.message);});return()=>c.abort();},[snapshotId]);
 useEffect(()=>{const c=new AbortController();setSymbols([]); if(snapshotId) api<Page<Symbol>>(`/snapshots/${snapshotId}/symbols?generationId=${generationId}&fileId=${file}&query=${encodeURIComponent(query)}&limit=100`,{signal:c.signal}).then(p=>{setSymbols(p.items);setTotal(p.total);setPinnedGeneration(p.generationId||generationId);}).catch(e=>{if(!c.signal.aborted)setError(e.message);});return()=>c.abort();},[snapshotId,generationId,file,query]);
 const choose=(symbol:Symbol|null)=>onSelect({snapshotId,generationId:pinnedGeneration||generationId||undefined,fileId:symbol?.fileId||file,symbolId:symbol?.id},symbol);
 return <section className="source-selector" aria-label="选择源码">
 <label>源码文件<select aria-label="源码文件" value={file} onChange={e=>setFile(e.target.value)}><option value="">全部文件</option>{files.map(f=><option key={f.id} value={f.id}>{f.path}</option>)}</select></label>
 <button disabled={!file} onClick={()=>choose(null)}>使用整个文件</button>
 <label>查找符号<input aria-label="查找符号" value={query} onChange={e=>setQuery(e.target.value)}/></label>
 <select aria-label="源码符号" value="" onChange={e=>{const symbol=symbols.find(s=>s.id===e.target.value);if(symbol)choose(symbol);}}><option value="">选择方法或类型</option>{symbols.map(s=><option key={s.id} value={s.id}>{s.kind} · {s.signature}</option>)}</select>
 {symbols.length<total&&<button onClick={()=>api<Page<Symbol>>(`/snapshots/${snapshotId}/symbols?generationId=${generationId}&fileId=${file}&query=${encodeURIComponent(query)}&limit=100&offset=${symbols.length}`).then(p=>{if(current.current===identity)setSymbols(old=>[...old,...p.items]);}).catch(e=>setError(e.message))}>加载更多符号</button>}
 {error&&<p role="alert">{error}</p>}</section>;
}
