import { lazy, Suspense, useEffect, useRef, useState } from "react";
import Editor, { loader, type OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import {
  ArrowRight,
  BookOpen,
  Braces,
  ChevronRight,
  FileCode2,
  FileText,
  Lightbulb,
  CreditCard,
  Sparkles,
  Folder,
  GitBranch,
  Github,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Moon,
  Sun,
  X,
} from "lucide-react";
import {
  api,
  post,
  allFiles,
  active,
  statusText,
  type Project,
  type Snapshot,
  type Task,
  type SourceFile,
  type Symbol,
  type Relation,
  type Diagnostic,
  type Content,
  type Page,
} from "./api";

import LearningLibrary from "./learning/LearningLibrary";
import AiPanel from "./learning/AiPanel";
import TrainingWorkspace from "./training/TrainingWorkspace";
import ReviewWorkspace from "./review/ReviewWorkspace";
import ProgressWorkspace from "./progress/ProgressWorkspace";
import GuideWorkspace from "./guides/GuideWorkspace";
import { studyUrl } from "./study/studyApi";
import { trainingUrl } from "./training/trainingApi";
import RelationCard from "./relations/RelationCard";
import { recordUrl, names, type LearningKind, type SourceSelection, type EvidenceSource } from "./learning/learningApi";
const GraphWorkspace = lazy(() => import("./graph/GraphWorkspace"));
const ArchitectureWorkspace = lazy(() => import("./architecture/ArchitectureWorkspace"));
const AnalysisWorkspace = lazy(() => import("./analysis/AnalysisWorkspace"));
const AiAssistantWorkspace = lazy(() => import("./ai/AiAssistantWorkspace"));
const GitHubWorkspace = lazy(() => import("./github/GitHubWorkspace"));

// Use bundled Monaco assets so source browsing works without a third-party CDN.
(self as typeof self & { MonacoEnvironment: unknown }).MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};
loader.config({ monaco });
const initial = new URLSearchParams(location.search);
type View = "explore" | "graph" | "architecture" | "analysis" | "ai" | "github" | "training" | "reviews" | "progress" | "guides" | LearningKind;
const parseView = (value: string | null): View => value === "graph" || value === "architecture" || value === "analysis" || value === "ai" || value === "github" || value === "documents" || value === "knowledge" || value === "cards" || value === "training" || value === "reviews" || value === "progress" || value === "guides" ? value : "explore";
const graphDefaults = { graphView: "PACKAGE", graphPackage: "", graphFocus: "", graphQuery: "", graphOffset: "0", graphType: "", graphEdge: "" };
const PAGE_SIZE = 200;

function FileTree({
  files,
  selected,
  open,
}: {
  files: SourceFile[];
  selected: string;
  open: (file: SourceFile) => void;
}) {
  type Node = { directories: Map<string, Node>; files: SourceFile[] };
  const root: Node = { directories: new Map(), files: [] };
  files.forEach((file) => {
    const parts = file.path.split("/");
    let node = root;
    parts.slice(0, -1).forEach((part) => {
      if (!node.directories.has(part))
        node.directories.set(part, { directories: new Map(), files: [] });
      node = node.directories.get(part)!;
    });
    node.files.push(file);
  });
  const render = (node: Node, path: string) => (
    <>
      {[...node.directories].map(([name, child]) => (
        <details key={name} open>
          <summary>
            <Folder size={14} />
            {name}
          </summary>
          <div className="tree-children">
            {render(child, `${path}${name}/`)}
          </div>
        </details>
      ))}
      {node.files.map((file) => (
        <button
          className={`tree-file ${selected === file.id ? "selected" : ""}`}
          key={file.id}
          title={file.path}
          onClick={() => open(file)}
        >
          <FileCode2 size={14} />
          <span>{file.path.slice(path.length)}</span>
        </button>
      ))}
    </>
  );
  return <div className="file-tree">{render(root, "")}</div>;
}

export default function App() {
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    const stored = localStorage.getItem("codeatlas-theme");
    return stored === "light" || stored === "dark" ? stored : (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("codeatlas-theme", theme);
  }, [theme]);
  const [view, setView] = useState<View>(parseView(initial.get("view")));
  const [aiOpen, setAiOpen] = useState(!!initial.get("aiGenerationId"));
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [projectId, setProjectId] = useState(initial.get("projectId") ?? "");
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);
  const [snapshotId, setSnapshotId] = useState(initial.get("snapshotId") ?? "");
  const [files, setFiles] = useState<SourceFile[]>([]);
  const [symbols, setSymbols] = useState<Page<Symbol> | null>(null);
  const [selectedSymbol, setSelectedSymbol] = useState<Symbol | null>(null);
  const [relations, setRelations] = useState<Relation[]>([]);
  const [relationsTotal, setRelationsTotal] = useState(0);
  const [relationsMore, setRelationsMore] = useState(false);
  const relationsIdentity = useRef("");
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  const [diagnosticsTotal, setDiagnosticsTotal] = useState(0);
  const [diagnosticsMore, setDiagnosticsMore] = useState(false);
  const diagnosticsIdentity = useRef("");
  const [content, setContent] = useState<Content | null>(null);
  const [fileId, setFileId] = useState(initial.get("fileId") ?? "");
  const [line, setLine] = useState(Number(initial.get("line")) || 1);
  const [generation, setGeneration] = useState(
    initial.get("generationId") ?? "",
  );
  const [restoreSymbolId, setRestoreSymbolId] = useState(
    initial.get("symbolId") ?? "",
  );
  const [search, setSearch] = useState("");
  const [fileSearch, setFileSearch] = useState("");
  const [tab, setTab] = useState<"files" | "symbols">("files");
  const [task, setTask] = useState<Task | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  useEffect(() => {
    if (!importOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === "Escape") setImportOpen(false); };
    addEventListener("keydown", closeOnEscape);
    return () => removeEventListener("keydown", closeOnEscape);
  }, [importOpen]);
  const [importPath, setImportPath] = useState("");
  const [importSource, setImportSource] = useState<"LOCAL" | "GITHUB">("LOCAL");
  const [importUrl, setImportUrl] = useState("");
  const [importRef, setImportRef] = useState("");
  const [authorized, setAuthorized] = useState(false);
  const [revision, setRevision] = useState(0);
  const [paging, setPaging] = useState(false);
  const [navWidth, setNavWidth] = useState(255);
  const [evidenceWidth, setEvidenceWidth] = useState(290);
  const explorerRef = useRef<HTMLElement | null>(null);
  const resizePanel = (panel: "nav" | "evidence", x: number) => {
    const bounds = explorerRef.current?.getBoundingClientRect();
    if (!bounds) return;
    if (panel === "nav") setNavWidth(Math.min(420, Math.max(180, x - bounds.left)));
    else setEvidenceWidth(Math.min(440, Math.max(230, bounds.right - x)));
  };
  const pagingController = useRef<AbortController | null>(null);
  const queryIdentity = `${snapshotId}|${search}|${generation}`;
  const currentQuery = useRef(queryIdentity);
  currentQuery.current = queryIdentity;
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const snapshot = snapshots.find((s) => s.id === snapshotId);
  const project = projects.find((p) => p.id === projectId);
  const report = (e: unknown) => {
    if (!(e instanceof DOMException && e.name === "AbortError"))
      setError(e instanceof Error ? e.message : String(e));
  };

  useEffect(() => {
    const controller = new AbortController();
    api<Project[]>("/projects", { signal: controller.signal })
      .then((items) => {
        setProjects(items);
        setProjectId((previous) => previous || items[0]?.id || "");
      })
      .catch(report)
      .finally(() => { if (!controller.signal.aborted) setProjectsLoading(false); });
    return () => controller.abort();
  }, [revision]);

  useEffect(() => {
    if (!projectId) return;
    const controller = new AbortController();
    api<Snapshot[]>(`/projects/${projectId}/snapshots`, {
      signal: controller.signal,
    })
      .then((items) => {
        setSnapshots(items);
        setSnapshotId((previous) =>
          items.some((s) => s.id === previous)
            ? previous
            : (items[0]?.id ?? ""),
        );
      })
      .catch(report);
    return () => controller.abort();
  }, [projectId, revision]);

  useEffect(() => {
    if (!snapshotId) {
      setFiles([]);
      setSymbols(null);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    allFiles(snapshotId, controller.signal)
      .then((items) => {
        setFiles(items);
        setFileId((previous) =>
          items.some((f) => f.id === previous) ? previous : "",
        );
      })
      .catch(report)
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [snapshotId, revision]);

  useEffect(() => {
    setDiagnostics([]);
    setDiagnosticsTotal(0);
    setDiagnosticsMore(false);
    diagnosticsIdentity.current = "";
    if (!snapshot) return;
    // 同一快照的任务状态以轮询结果为准；仅当展示的是其它快照的任务时才切换。
    if (task?.snapshotId !== snapshot.id) setTask(snapshot.latestTask);
    const latest = snapshot.latestTask;
    if (!latest) return;
    const identity = `${latest.id}|${revision}`;
    diagnosticsIdentity.current = identity;
    const controller = new AbortController();
    api<Page<Diagnostic>>(`/tasks/${latest.id}/diagnostics?limit=${PAGE_SIZE}`, {
      signal: controller.signal,
    })
      .then((page) => {
        if (diagnosticsIdentity.current !== identity) return;
        setDiagnostics(page.items);
        setDiagnosticsTotal(page.total);
      })
      .catch((error) => {
        if (!controller.signal.aborted) report(error);
      });
    return () => controller.abort();
  }, [snapshot?.id, snapshot?.latestTask?.id, revision]);

  useEffect(() => {
    if (!snapshotId) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      const params = new URLSearchParams({ query: search, limit: "100" });
      if (generation) params.set("generationId", generation);
      api<Page<Symbol>>(`/snapshots/${snapshotId}/symbols?${params}`, {
        signal: controller.signal,
      })
        .then((page) => {
          setSymbols(page);
        })
        .catch(report);
    }, 200);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [snapshotId, search, generation, revision]);

  useEffect(() => {
    pagingController.current?.abort();
    setPaging(false);
    return () => pagingController.current?.abort();
  }, [queryIdentity]);

  useEffect(() => {
    if (!restoreSymbolId || !snapshotId) return;
    const controller = new AbortController();
    const suffix = generation ? `?generationId=${generation}` : "";
    api<Symbol>(
      `/snapshots/${snapshotId}/symbols/${restoreSymbolId}${suffix}`,
      { signal: controller.signal },
    )
      .then((symbol) => {
        setSelectedSymbol(symbol);
        setFileId(symbol.fileId);
      })
      .catch(report);
    return () => controller.abort();
  }, [restoreSymbolId, snapshotId, generation]);

  useEffect(() => {
    setContent(null);
    if (!snapshotId || !fileId) return;
    const controller = new AbortController();
    api<Content>(`/snapshots/${snapshotId}/files/${fileId}/content`, {
      signal: controller.signal,
    })
      .then(setContent)
      .catch(report);
    return () => controller.abort();
  }, [snapshotId, fileId]);

  useEffect(() => {
    setRelations([]);
    setRelationsTotal(0);
    setRelationsMore(false);
    relationsIdentity.current = "";
    if (!selectedSymbol || !snapshotId || !symbols?.generationId) return;
    const identity = `${snapshotId}|${selectedSymbol.id}|${symbols.generationId}`;
    relationsIdentity.current = identity;
    const controller = new AbortController();
    api<Page<Relation>>(
      `/snapshots/${snapshotId}/symbols/${selectedSymbol.id}/relations?generationId=${symbols.generationId}&limit=${PAGE_SIZE}`,
      { signal: controller.signal },
    )
      .then((page) => {
        if (relationsIdentity.current !== identity) return;
        setRelations(page.items);
        setRelationsTotal(page.total);
      })
      .catch((error) => {
        if (!controller.signal.aborted) report(error);
      });
    return () => controller.abort();
  }, [selectedSymbol, snapshotId, symbols?.generationId, revision]);

  const loadMoreRelations = async () => {
    if (!selectedSymbol || !snapshotId || !symbols?.generationId || relations.length >= relationsTotal) return;
    const identity = relationsIdentity.current;
    setRelationsMore(true);
    try {
      const more = await api<Page<Relation>>(
        `/snapshots/${snapshotId}/symbols/${selectedSymbol.id}/relations?generationId=${symbols.generationId}&limit=${PAGE_SIZE}&offset=${relations.length}`,
      );
      if (relationsIdentity.current !== identity) return;
      setRelations((previous) => [...previous, ...more.items]);
      setRelationsTotal(more.total);
    } catch (error) {
      if (relationsIdentity.current === identity) report(error);
    } finally {
      setRelationsMore(false);
    }
  };

  const loadMoreDiagnostics = async () => {
    const latest = snapshot?.latestTask;
    if (!latest || diagnostics.length >= diagnosticsTotal) return;
    const identity = diagnosticsIdentity.current;
    setDiagnosticsMore(true);
    try {
      const more = await api<Page<Diagnostic>>(
        `/tasks/${latest.id}/diagnostics?limit=${PAGE_SIZE}&offset=${diagnostics.length}`,
      );
      if (diagnosticsIdentity.current !== identity) return;
      setDiagnostics((previous) => [...previous, ...more.items]);
      setDiagnosticsTotal(more.total);
    } catch (error) {
      if (diagnosticsIdentity.current === identity) report(error);
    } finally {
      setDiagnosticsMore(false);
    }
  };

  useEffect(() => {
    if (!active(task)) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const next = await api<Task>(`/tasks/${task!.id}`, {
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        if (!active(next)) {
          let follow = next;
          if (next.kind === "IMPORT" && next.status === "SUCCEEDED") {
            const items = await api<Project[]>("/projects", {
              signal: controller.signal,
            });
            if (controller.signal.aborted) return;
            resetSelection(false);
            setProjects(items);
            setView("explore");
            setProjectId(
              items.find((p) => p.sourcePath === next.sourcePath)?.id ?? "",
            );
            setSnapshotId(next.snapshotId ?? "");
            // 导入只创建快照；成功后自动开始索引，关系图与源码探索无需再手工触发。
            if (next.snapshotId) {
              try {
                follow = await post<Task>(`/snapshots/${next.snapshotId}/index`, { rebuild: false });
              } catch (e) {
                if (!controller.signal.aborted) report(e);
              }
            }
          }
          if (controller.signal.aborted) return;
          setRevision((value) => value + 1);
          setTask(follow);
          return;
        }
        setTask(next);
      } catch (e) {
        if (controller.signal.aborted) return;
        report(e);
      }
      timer = setTimeout(poll, 700);
    };
    timer = setTimeout(poll, 500);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [task?.id]);

  useEffect(() => {
    const restore = () => {
      const params = new URLSearchParams(location.search);
      resetSelection();
      setView(parseView(params.get("view")));
      setAiOpen(!!params.get("aiGenerationId"));
      setProjectId(params.get("projectId") ?? "");
      setSnapshotId(params.get("snapshotId") ?? "");
      setFileId(params.get("fileId") ?? "");
      setLine(Number(params.get("line")) || 1);
      setGeneration(params.get("generationId") ?? "");
      setRestoreSymbolId(params.get("symbolId") ?? "");
    };
    addEventListener("popstate", restore);
    return () => removeEventListener("popstate", restore);
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    params.set("view", view);
    Object.entries({
      projectId,
      snapshotId,
      fileId,
      generationId: generation || symbols?.generationId || "",
      symbolId: selectedSymbol?.id ?? "",
      line: String(line),
    }).forEach(([key, value]) => {
      if (value) params.set(key, value); else params.delete(key);
    });
    history.replaceState(null, "", `?${params}`);
  }, [
    projectId,
    snapshotId,
    fileId,
    selectedSymbol,
    line,
    generation,
    symbols?.generationId,
    view,
  ]);

  useEffect(() => {
    if (content && editor.current) {
      editor.current.revealLineInCenter(line);
      editor.current.setPosition({ lineNumber: line, column: 1 });
    }
  }, [line, content]);
  const mounted: OnMount = (instance) => {
    editor.current = instance;
    instance.revealLineInCenter(line);
    instance.setPosition({ lineNumber: line, column: 1 });
  };
  const resetSelection = (clearTask = true) => {
    setFileId("");
    setContent(null);
    setSelectedSymbol(null);
    setRelations([]);
    setSymbols(null);
    setDiagnostics([]);
    setGeneration("");
    // 导入成功后的自动索引仍需沿用当前轮询控制器；清空任务会触发清理并中止后续状态写入。
    if (clearTask) setTask(null);
    setLine(1);
    setError("");
    setRestoreSymbolId("");
  };
  const navigate = (values: Record<string, string>) => {
    const params = new URLSearchParams(location.search);
    Object.entries(values).forEach(([key, value]) =>
      value ? params.set(key, value) : params.delete(key),
    );
    history.pushState(null, "", `?${params}`);
  };
  const openFile = (file: SourceFile, targetLine = 1) => {
    navigate({ fileId: file.id, symbolId: "", line: String(targetLine) });
    setRestoreSymbolId("");
    setError("");
    setFileId(file.id);
    setLine(targetLine);
  };
  const openSymbol = (symbol: Symbol) => {
    navigate({
      fileId: symbol.fileId,
      symbolId: symbol.id,
      line: String(symbol.startLine),
    });
    setRestoreSymbolId("");
    setSelectedSymbol(symbol);
    setFileId(symbol.fileId);
    setLine(symbol.startLine);
    setError("");
  };
  const loadMore = async () => {
    if (!symbols || paging) return;
    const identity = queryIdentity;
    const controller = new AbortController();
    pagingController.current = controller;
    setPaging(true);
    try {
      const more = await api<Page<Symbol>>(
        `/snapshots/${snapshotId}/symbols?query=${encodeURIComponent(search)}&generationId=${symbols.generationId}&offset=${symbols.items.length}`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted && currentQuery.current === identity)
        setSymbols({ ...more, items: [...symbols.items, ...more.items] });
    } catch (e) {
      report(e);
    } finally {
      if (!controller.signal.aborted && currentQuery.current === identity)
        setPaging(false);
    }
  };
  const runIndex = async (rebuild: boolean) => {
    try {
      setError("");
      if (view !== "graph") setGeneration("");
      setTask(await post<Task>(`/snapshots/${snapshotId}/index`, { rebuild }));
    } catch (e) {
      report(e);
    }
  };
  const importProject = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!authorized) return;
    try {
      setError("");
      if (importSource === "GITHUB") {
        setTask(
          await post<Task>("/imports/github", {
            url: importUrl.trim(),
            ref: importRef.trim() || null,
          }),
        );
      } else {
        setTask(await post<Task>("/imports", { path: importPath.trim() }));
      }
      setImportOpen(false);
    } catch (e) {
      report(e);
    }
  };

  const currentSource: SourceSelection | undefined = selectedSymbol && snapshotId && (generation || symbols?.generationId)
    ? { snapshotId, generationId: generation || symbols!.generationId!, fileId: selectedSymbol.fileId, symbolId: selectedSymbol.id } : undefined;
  const changeView = (next: View) => {
    const params = new URLSearchParams(location.search); params.set("view", next); params.delete("recordId"); params.delete("aiGenerationId");
    for (const key of [...params.keys()]) if (/^(training|review|progress|guide|graph|arch|analysis)/.test(key)) params.delete(key);
    history.pushState(null, "", `?${params}`); setView(next); setAiOpen(false); setError("");
    dispatchEvent(new PopStateEvent("popstate"));
  };
  const createTraining = () => {
    if (!currentSource) return;
    const draftId = crypto.randomUUID();
    sessionStorage.setItem(`codeatlas-training-seed-${draftId}`, JSON.stringify(currentSource));
    trainingUrl({ trainingMode: "edit", trainingDraftId: draftId });
  };
  const openGraph = () => {
    const params = new URLSearchParams(location.search);
    for (const key of [...params.keys()]) if (key.startsWith("graph")) params.delete(key);
    params.set("view", "graph");
    if (selectedSymbol) {
      params.set("graphView", ["METHOD", "CONSTRUCTOR"].includes(selectedSymbol.kind) ? "CALL" : "TYPE");
      if (["METHOD", "CONSTRUCTOR"].includes(selectedSymbol.kind)) params.set("graphFocus", selectedSymbol.id);
    }
    history.pushState(null, "", `?${params}`);
    dispatchEvent(new PopStateEvent("popstate"));
  };
  const openLearning = (kind: LearningKind, id: string) => { recordUrl(kind, id); setView(kind); setAiOpen(false); setError(""); dispatchEvent(new PopStateEvent("popstate")); };
  const goSource = (source: EvidenceSource, targetLine?: number) => {
    navigate({ view: "explore", projectId: source.projectId, snapshotId: source.snapshotId, fileId: source.fileId,
      generationId: source.generationId ?? "", symbolId: source.symbolId ?? "", line: String(targetLine ?? source.startLine), recordId: "", aiGenerationId: "" });
    setView("explore"); setAiOpen(false); setError(""); setProjectId(source.projectId); setSnapshotId(source.snapshotId);
    setGeneration(source.generationId ?? ""); setFileId(source.fileId); setLine(targetLine ?? source.startLine);
    setSelectedSymbol(null); setRestoreSymbolId(source.symbolId ?? "");
  };

  return (
    <div className="app">
      <aside className="rail">
        <a className="brand" href="/" aria-label="CodeAtlas 首页">
          <Layers size={25} />
        </a>
        <nav className="rail-navigation" aria-label="功能导航">
          <span className="rail-caption">工作区</span>
          <button className={view === "explore" ? "rail-active" : ""} aria-label="项目探索" aria-current={view === "explore" ? "page" : undefined} onClick={() => changeView("explore")}><Braces size={17}/><span>项目探索</span></button>
          <button className={view === "graph" ? "rail-active" : ""} aria-label="关系图" aria-current={view === "graph" ? "page" : undefined} onClick={() => changeView("graph")}><GitBranch size={17}/><span>关系图</span></button>
          <button className={view === "architecture" ? "rail-active" : ""} aria-label="架构总览" aria-current={view === "architecture" ? "page" : undefined} onClick={() => changeView("architecture")}><Layers size={17}/><span>架构总览</span></button>
          <button className={view === "analysis" ? "rail-active" : ""} aria-label="代码分析" aria-current={view === "analysis" ? "page" : undefined} onClick={() => changeView("analysis")}><FileCode2 size={17}/><span>代码分析</span></button>
          <button className={view === "ai" ? "rail-active" : ""} aria-label="AI 助手" aria-current={view === "ai" ? "page" : undefined} onClick={() => changeView("ai")}><Sparkles size={17}/><span>AI 助手</span></button>
          <button className={view === "github" ? "rail-active" : ""} aria-label="GitHub" aria-current={view === "github" ? "page" : undefined} onClick={() => changeView("github")}><Github size={17}/><span>GitHub</span></button>
          <span className="rail-caption rail-caption-secondary">知识库</span>
          <button className={view === "documents" ? "rail-active" : ""} aria-label="文档" aria-current={view === "documents" ? "page" : undefined} onClick={() => changeView("documents")}><FileText size={17}/><span>文档</span></button>
          <button className={view === "knowledge" ? "rail-active" : ""} aria-label="知识" aria-current={view === "knowledge" ? "page" : undefined} onClick={() => changeView("knowledge")}><Lightbulb size={17}/><span>知识</span></button>
          <button className={view === "cards" ? "rail-active" : ""} aria-label="卡片" aria-current={view === "cards" ? "page" : undefined} onClick={() => changeView("cards")}><CreditCard size={17}/><span>卡片</span></button>
          <button className={view === "training" ? "rail-active" : ""} aria-label="训练" aria-current={view === "training" ? "page" : undefined} onClick={() => changeView("training")}><BookOpen size={17}/><span>训练</span></button>
          <button className={view === "reviews" ? "rail-active" : ""} aria-label="复习" aria-current={view === "reviews" ? "page" : undefined} onClick={() => changeView("reviews")}><RefreshCw size={17}/><span>复习</span></button>
          <button className={view === "progress" ? "rail-active" : ""} aria-label="进度" aria-current={view === "progress" ? "page" : undefined} onClick={() => changeView("progress")}><Layers size={17}/><span>进度</span></button>
          <button className={view === "guides" ? "rail-active" : ""} aria-label="引导" aria-current={view === "guides" ? "page" : undefined} onClick={() => changeView("guides")}><GitBranch size={17}/><span>引导</span></button>
        </nav>
        <div className="rail-bottom"><button className="theme-toggle" aria-label={theme === "dark" ? "切换浅色主题" : "切换深色主题"} title={theme === "dark" ? "浅色主题" : "深色主题"} onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>{theme === "dark" ? <Sun size={17}/> : <Moon size={17}/>}<span>{theme === "dark" ? "浅色主题" : "深色主题"}</span></button><span className="version">CodeAtlas 0.4.6</span></div>
      </aside>
      <div className="workspace">
        <header>
          <div className="product">
            CodeAtlas<span>{view === "explore" ? "项目探索" : view === "graph" ? "关系图" : view === "architecture" ? "架构总览" : view === "analysis" ? "代码分析" : view === "ai" ? "AI 助手" : view === "github" ? "GitHub" : view === "training" ? "训练" : view === "reviews" ? "复习" : view === "progress" ? "进度" : view === "guides" ? "引导" : names[view]}</span>
          </div>
          <div className="header-actions">
            <span className="local">
              <i />
              本地工作区
            </span>
            <button
              className="primary"
              onClick={() => setImportOpen(true)}
              disabled={active(task)}
            >
              <Plus size={15} />
              导入项目
            </button>
          </div>
        </header>
        {error && (
          <div className="error" role="alert">
            <span>{error}</span>
            <button onClick={() => setError("")} aria-label="关闭错误">
              <X size={15} />
            </button>
          </div>
        )}
        {view === "explore" || view === "graph" ? <>
        <section className="toolbar">
          <label>
            <span>项目</span>
            <select
              aria-label="项目"
              value={projectId}
              onChange={(e) => {
                navigate({
                  projectId: e.target.value,
                  snapshotId: "",
                  fileId: "",
                  symbolId: "",
                  generationId: "",
                  ...(view === "graph" ? graphDefaults : {}),
                });
                resetSelection();
                setSnapshots([]);
                setSnapshotId("");
                setProjectId(e.target.value);
              }}
            >
              <option value="">选择项目</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <ChevronRight size={14} />
          <label className="snapshot-select">
            <span>源码快照</span>
            <select
              aria-label="源码快照"
              value={snapshotId}
              onChange={(e) => {
                navigate({
                  snapshotId: e.target.value,
                  fileId: "",
                  symbolId: "",
                  generationId: "",
                  ...(view === "graph" ? graphDefaults : {}),
                });
                resetSelection();
                setSnapshotId(e.target.value);
              }}
            >
              <option value="">选择快照</option>
              {snapshots.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.manifestHash.slice(0, 10)} ·{" "}
                  {new Date(s.createdAt).toLocaleString("zh-CN")}
                </option>
              ))}
            </select>
          </label>
          {snapshot && (
            <span className="git-state">
              <GitBranch size={14} />
              {snapshot.sourceKind}
              {snapshot.commitHash && ` · ${snapshot.commitHash.slice(0, 7)}`}
            </span>
          )}
          <button
            className="index-button"
            disabled={!snapshotId || active(task)}
            onClick={() => runIndex(!!snapshot?.publishedGeneration)}
          >
            <RefreshCw size={14} />
            {snapshot?.publishedGeneration ? "重建索引" : "开始索引"}
          </button>
          {view === "graph" && snapshot?.publishedGeneration && generation && snapshot.publishedGeneration !== generation &&
            <button onClick={() => { navigate({ ...graphDefaults, generationId: snapshot.publishedGeneration! }); dispatchEvent(new PopStateEvent("popstate")); }}>查看最新索引</button>}
        </section>
        {task && (
          <section
            className={`task-strip ${task.status.toLowerCase()}`}
            aria-live="polite"
          >
            <span>
              {active(task) && <Loader2 className="spin" size={14} />}
              <strong>
                {task.kind === "IMPORT" ? "导入" : "索引"}
                {statusText[task.status]}
              </strong>
              {task.total > 0
                ? `${task.progress} / ${task.total} 个文件`
                : `${task.progress} 个文件`}
            </span>
            <span className="task-message">
              {task.errorCode && `${task.errorCode} · `}
              {task.message}
            </span>
            {active(task) ? (
              <button
                disabled={task.cancelRequested}
                onClick={() =>
                  post<Task>(`/tasks/${task.id}/cancel`)
                    .then(setTask)
                    .catch(report)
                }
              >
                {task.cancelRequested ? "正在取消" : "取消"}
              </button>
            ) : (
              ["FAILED", "CANCELLED", "PARTIAL_SUCCESS"].includes(
                task.status,
              ) && (
                <button
                  onClick={() =>
                    post<Task>(`/tasks/${task.id}/retry`)
                      .then(setTask)
                      .catch(report)
                  }
                >
                  重试
                </button>
              )
            )}
          </section>
        )}
        {view === "graph" ? <Suspense fallback={<p role="status">正在加载关系图…</p>}><GraphWorkspace key={`${projectId}|${snapshotId}`} projectId={projectId} snapshotId={snapshotId} generationId={generation} refreshRevision={revision} onGeneration={setGeneration} onSource={goSource} theme={theme}/></Suspense> : projectsLoading ? <main className="welcome" role="status"><Loader2 className="spin" size={24}/><p>正在加载项目…</p></main> : !projects.length && !active(task) && !error ? (
          <main className="welcome">
            <div className="welcome-icon">
              <BookOpen size={34} />
            </div>
            <h1>从真实源码开始探索</h1>
            <p>导入本地 Java 项目，保存源码快照，查找符号与关系。</p>
            <button className="primary" onClick={() => setImportOpen(true)}>
              导入第一个项目
              <ArrowRight size={16} />
            </button>
            <small>源码保存在本机 · 静态分析 · 历史版本可追溯</small>
          </main>
        ) : (
          <main className="explorer" ref={explorerRef} style={{ gridTemplateColumns: `${navWidth}px 5px minmax(0, 1fr) 5px ${evidenceWidth}px` }}>
            <aside className="navigator">
              <div className="tabs">
                <button
                  className={tab === "files" ? "chosen" : ""}
                  onClick={() => setTab("files")}
                >
                  文件
                </button>
                <button
                  className={tab === "symbols" ? "chosen" : ""}
                  onClick={() => setTab("symbols")}
                >
                  符号
                </button>
              </div>
              <div className="search">
                <Search size={14} />
                <input
                  aria-label={tab === "files" ? "搜索文件路径" : "搜索符号"}
                  placeholder={
                    tab === "files" ? "搜索文件路径…" : "类名、方法名或签名…"
                  }
                  value={tab === "files" ? fileSearch : search}
                  onChange={(e) =>
                    tab === "files"
                      ? setFileSearch(e.target.value)
                      : setSearch(e.target.value)
                  }
                />
              </div>
              <div className="nav-content">
                {loading ? (
                  <p className="hint">正在读取文件…</p>
                ) : tab === "files" ? (
                  <FileTree
                    files={files.filter((f) =>
                      f.path.toLowerCase().includes(fileSearch.toLowerCase()),
                    )}
                    selected={fileId}
                    open={(f) => {
                      setSelectedSymbol(null);
                      openFile(f);
                    }}
                  />
                ) : (
                  <>
                    {symbols?.items.map((symbol) => (
                      <button
                        className={`symbol ${selectedSymbol?.id === symbol.id ? "selected" : ""}`}
                        key={symbol.id}
                        onClick={() => openSymbol(symbol)}
                      >
                        <span
                          className={`symbol-kind ${symbol.kind.toLowerCase()}`}
                        >
                          {symbol.kind === "METHOD"
                            ? "M"
                            : symbol.kind === "INTERFACE"
                              ? "I"
                              : "C"}
                        </span>
                        <span>
                          <strong>{symbol.signature}</strong>
                          <small>
                            {symbol.qualifiedName} · L{symbol.startLine}
                          </small>
                        </span>
                      </button>
                    ))}
                    {!symbols?.items.length && (
                      <p className="hint">
                        {snapshot?.publishedGeneration
                          ? "没有匹配的符号"
                          : "运行索引后可查询符号"}
                      </p>
                    )}
                    {symbols && symbols.items.length < symbols.total && (
                      <button
                        className="load-more"
                        disabled={paging}
                        onClick={loadMore}
                      >
                        {paging
                          ? "正在加载…"
                          : `加载更多 · 共 ${symbols.total} 个`}
                      </button>
                    )}
                  </>
                )}
              </div>
              <div className="nav-footer">
                {files.length} 个文件
                {symbols && ` · ${symbols.total} 个匹配符号`}
              </div>
            </aside>
            <div className="panel-resizer" role="separator" aria-label="调整文件导航宽度" aria-orientation="vertical" tabIndex={0} onKeyDown={e => { if (e.key === "ArrowLeft" || e.key === "ArrowRight") { e.preventDefault(); setNavWidth(v => Math.min(420, Math.max(180, v + (e.key === "ArrowRight" ? 16 : -16)))); } }} onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); }} onPointerMove={e => { if (e.currentTarget.hasPointerCapture(e.pointerId)) resizePanel("nav", e.clientX); }}/>
            <section className="code-panel">
              <div className="code-heading">
                <FileCode2 size={15} />
                <span>{content?.path ?? "源码浏览"}</span>
                {content && <span className="read-only">只读快照</span>}
              </div>
              {content ? (
                <>
                  <Editor
                    height="100%"
                    language={
                      content.path.endsWith(".java") ? "java" : "plaintext"
                    }
                    theme={theme === "dark" ? "vs-dark" : "vs"}
                    path={`${snapshotId}/${fileId}`}
                    value={content.content}
                    onMount={mounted}
                    options={{
                      readOnly: true,
                      occurrencesHighlight: "off",
                      minimap: { enabled: false },
                      fontSize: 13,
                      lineHeight: 22,
                      padding: { top: 16 },
                      scrollBeyondLastLine: false,
                      automaticLayout: true,
                      wordWrap: "off",
                    }}
                  />
                  <div className="code-footer">
                    <span>UTF-8 · {content.lineCount} 行</span>
                    <span>
                      SHA-256 {content.sha256.slice(0, 12)} · L{line}
                    </span>
                  </div>
                </>
              ) : (
                <div className="code-empty">
                  <Braces size={32} />
                  <h2>{snapshotId ? "选择一个文件或符号" : "选择源码快照"}</h2>
                  <p>浏览固定版本的源码及其分析证据</p>
                </div>
              )}
            </section>
            <div className="panel-resizer" role="separator" aria-label="调整源码证据宽度" aria-orientation="vertical" tabIndex={0} onKeyDown={e => { if (e.key === "ArrowLeft" || e.key === "ArrowRight") { e.preventDefault(); setEvidenceWidth(v => Math.min(440, Math.max(230, v + (e.key === "ArrowLeft" ? 16 : -16)))); } }} onPointerDown={e => { e.currentTarget.setPointerCapture(e.pointerId); }} onPointerMove={e => { if (e.currentTarget.hasPointerCapture(e.pointerId)) resizePanel("evidence", e.clientX); }}/>
            <aside className="evidence">
              <h2>源码证据</h2>
              {selectedSymbol ? (
                <>
                  <span className="eyebrow">{selectedSymbol.kind}</span>
                  <h3>{selectedSymbol.signature}</h3>
                  <button className="explain-source-button" onClick={() => setAiOpen(true)} disabled={!currentSource}><Sparkles size={14}/>解释源码</button>
                  <button onClick={createTraining} disabled={!currentSource}>创建训练任务</button>
                  <p className="qualified">{selectedSymbol.qualifiedName}</p>
                  <button
                    className="source-link"
                    onClick={() => openSymbol(selectedSymbol)}
                  >
                    L{selectedSymbol.startLine}–L{selectedSymbol.endLine}
                    <ArrowRight size={13} />
                  </button>
                  {selectedSymbol.annotations.map((a) => (
                    <span className="annotation" key={a}>
                      @{a}
                    </span>
                  ))}
                  <button onClick={openGraph}>查看关系图</button>
                  <h4>
                    局部关系 <span>{relations.length}{relationsTotal > relations.length ? ` / ${relationsTotal}` : ""}</span>
                  </h4>
                  {relations.map((r, i) => (
                    <RelationCard
                      key={r.id ?? i}
                      relation={r}
                      snapshotId={snapshotId}
                      generationId={symbols?.generationId ?? generation}
                      onEvidence={() => {
                        setFileId(r.fileId);
                        setLine(r.startLine);
                      }}
                      onSaved={() => setRevision((value) => value + 1)}
                    />
                  ))}
                  {!relations.length && (
                    <p className="hint">未提取到局部关系</p>
                  )}
                  {relations.length < relationsTotal && (
                    <div>
                      <p className="hint">
                        当前显示 {relations.length} / {relationsTotal} 条；继续加载可完整浏览。
                      </p>
                      <button
                        className="load-more"
                        disabled={relationsMore}
                        onClick={loadMoreRelations}
                      >
                        {relationsMore
                          ? "加载中…"
                          : `加载更多关系（剩余 ${relationsTotal - relations.length} 条）`}
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <p className="hint">
                  在符号列表中选择类或方法，查看声明与局部关系。
                </p>
              )}
              <div className="diagnostic-heading">
                <h4>
                  文件诊断 <span>{diagnostics.length}{diagnosticsTotal > diagnostics.length ? ` / ${diagnosticsTotal}` : ""}</span>
                </h4>
              </div>
              {diagnostics.map((d, i) => (
                <button
                  className="diagnostic"
                  key={i}
                  onClick={() => {
                    setFileId(d.fileId);
                    setLine(d.startLine);
                  }}
                >
                  <strong>
                    {files
                      .find((f) => f.id === d.fileId)
                      ?.path.split("/")
                      .pop() ?? d.fileId}
                  </strong>
                  <span>{d.code}</span>
                  <p>{d.message}</p>
                </button>
              ))}
              {!diagnostics.length && <p className="hint">当前没有文件诊断</p>}
              {diagnostics.length < diagnosticsTotal && (
                <div>
                  <p className="hint">
                    当前显示 {diagnostics.length} / {diagnosticsTotal} 条；继续加载可完整浏览。
                  </p>
                  <button
                    className="load-more"
                    disabled={diagnosticsMore}
                    onClick={loadMoreDiagnostics}
                  >
                    {diagnosticsMore
                      ? "加载中…"
                      : `加载更多诊断（剩余 ${diagnosticsTotal - diagnostics.length} 条）`}
                  </button>
                </div>
              )}
            </aside>
          </main>
        )}
        </> : view === "architecture" ? <Suspense fallback={<p role="status">正在加载架构总览…</p>}><ArchitectureWorkspace key={`${projectId}|${snapshotId}`} snapshotId={snapshotId} generationId={generation} refreshRevision={revision} onSource={goSource} theme={theme}/></Suspense> : view === "analysis" ? <Suspense fallback={<p role="status">正在加载代码分析…</p>}><AnalysisWorkspace projectId={projectId} snapshotId={snapshotId} generationId={generation} selectedSymbol={selectedSymbol} onSource={goSource} theme={theme}/></Suspense> : view === "ai" ? <Suspense fallback={<p role="status">正在加载 AI 助手…</p>}><AiAssistantWorkspace hasSource={!!currentSource} onOpenAi={() => setAiOpen(true)} onNavigate={changeView} theme={theme}/></Suspense> : view === "github" ? <Suspense fallback={<p role="status">正在加载 GitHub…</p>}><GitHubWorkspace projectId={projectId} theme={theme}/></Suspense> : view === "training" ? <TrainingWorkspace onSource={goSource}/> : view === "reviews" ? <ReviewWorkspace onSource={goSource}/> : view === "progress" ? <ProgressWorkspace onSource={goSource}/> : view === "guides" ? <GuideWorkspace onSource={goSource}/> : <LearningLibrary key={view} kind={view} currentSource={currentSource} onSource={goSource} onOpenRecord={openLearning} onReview={id => studyUrl("reviews", { reviewCardId: id })}/>}
        <footer>
          <span>{view === "explore" ? project?.sourcePath ?? "CodeAtlas 本地项目探索" : view === "architecture" ? "架构总览来自固定快照" : "学习资料保存在本机"}</span>
          <span>
            {snapshot ? `快照 ${snapshot.manifestHash.slice(0, 12)}` : "V0.4"}
          </span>
        </footer>
      </div>
      {aiOpen && <AiPanel selection={currentSource} onClose={() => setAiOpen(false)} onSource={goSource} onOpenRecord={openLearning}/>}
      {importOpen && (
        <div className="modal-backdrop" onClick={() => setImportOpen(false)}>
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="import-title"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="modal-title">
              <h2 id="import-title">导入项目</h2>
              <button
                onClick={() => setImportOpen(false)}
                aria-label="关闭导入"
              >
                <X size={18} />
              </button>
            </div>
            <div className="button-row" role="tablist" aria-label="导入来源">
              <button
                type="button"
                role="tab"
                aria-selected={importSource === "LOCAL"}
                className={importSource === "LOCAL" ? "primary" : ""}
                onClick={() => { setImportSource("LOCAL"); setAuthorized(false); }}
              >
                本地目录
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={importSource === "GITHUB"}
                className={importSource === "GITHUB" ? "primary" : ""}
                onClick={() => { setImportSource("GITHUB"); setAuthorized(false); }}
              >
                GitHub 仓库
              </button>
            </div>
            <p>
              {importSource === "GITHUB"
                ? "输入公开 GitHub 仓库的根地址，可选择分支或标签。"
                : "选择 Java / Spring Boot 项目所在的绝对目录。"}
            </p>
            <form onSubmit={importProject}>
              {importSource === "LOCAL" ? (
                <label className="path-label">
                  项目目录
                  <input
                    autoFocus
                    required
                    value={importPath}
                    onChange={(e) => setImportPath(e.target.value)}
                    placeholder="D:\Projects\my-spring-project"
                  />
                </label>
              ) : (
                <>
                  <label className="path-label">
                    仓库地址
                    <input
                      autoFocus
                      required
                      value={importUrl}
                      onChange={(e) => setImportUrl(e.target.value)}
                      placeholder="https://github.com/owner/repo"
                    />
                  </label>
                  <label className="path-label">
                    分支或标签（可选）
                    <input
                      value={importRef}
                      onChange={(e) => setImportRef(e.target.value)}
                      placeholder="main 或 v1.0.0"
                    />
                  </label>
                </>
              )}
              <label className="authorization">
                <input
                  type="checkbox"
                  checked={authorized}
                  onChange={(e) => setAuthorized(e.target.checked)}
                />
                {importSource === "GITHUB"
                  ? "我确认该仓库为公开仓库，并授权本机联网下载源码创建快照。"
                  : "我拥有该目录的读取权限，并授权保存本地源码快照。"}
              </label>
              <div className="import-note">
                {importSource === "GITHUB"
                  ? "首版仅支持 github.com 公开仓库，通过本机 Git 浅克隆。默认跳过 .git、构建产物与密钥文件；上限为 20,000 个文件、单文件 2 MiB、总计 256 MiB。"
                  : "默认跳过 Git 元数据、构建产物与密钥文件。上限为 20,000 个文件、单文件 2 MiB、总计 256 MiB。"}
              </div>
              <button
                className="primary"
                disabled={!authorized || (importSource === "GITHUB" ? !importUrl.trim() : !importPath.trim()) || active(task)}
                type="submit"
              >
                创建快照
                <ArrowRight size={15} />
              </button>
            </form>
          </section>
        </div>
      )}
    </div>
  );
}
