# Interactive Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. User approved direct completion, execute inline and request one final independent review.

**Goal** Deliver0.4.1 package/type/local-call interactive graphs with exact snapshot source navigation.

**Architecture** Java read-only graph projection of published immutable index generations. React Flow renders bounded subgraphs, Dagre calculates layout in an isolated worker. Reuse existing App identities, source navigation and themes.

**Tech Stack** Java21/SpringBoot/JDBC/MySQL, React19/TypeScript/Vite, @xyflow/react12.12.1 and @dagrejs/dagre2.0.0 candidates.

**Spec** D:/CodeAtlas/CodeAtlas后端/docs/superpowers/specs/2026-11-15-interactive-graph-design.md, user approved direct development.

## Global Constraints

PACKAGE/TYPE induced scope and query, isolated nodes retained under edge filters. CALL center always retained, offset neighbors, limit includes center. Default80/max200 nodes,300/max1000 edges. Fixed published snapshot/generation, old payloads supported. Unknown targets preserve original states/reasons. No database migration required. No Git metadata, file/hash delivery. Diagram components lazy loaded.

## Review Focus

1. Nested/local/duplicate types map only by file, exact range and qualified identity, ambiguity retains unknown ownership. Task2 fixtures.
2. Filtered/page boundaries count original records separately from grouped edges, CALL center survives every page/search. Task2 HTTP cases.
3. Rebuild and browser back never switch generation, graph/source identities guard late fetch/layout/evidence. Task3 browser barriers.
4. Unknown or inferred edges never become confirmed nodes, text remains escaped. Task2 and3 injection/state cases.
5. Cycles, self-loops, multiple status edges, failed layout and narrow keyboard access preserve equivalent lists. Task1/3 tests.

## File Map

Backend graph/GraphIndex.java derives owners/packages/nodes, GraphQueryService.java scopes/aggregates/pages, GraphController.java HTTP. Test GraphIntegrationTest.java fixtures/HTTP. Store adds streaming index access only if required. Existing analyzer/table semantics retained.

Frontend graph/graphApi.ts DTO/location, graph/layout.ts pure Dagre adapter, graph/layout.worker.ts worker, graph/GraphCanvas.tsx ReactFlow, graph/GraphWorkspace.tsx scope/evidence/member controls, graph/graph.css. App imports lazy workspace and shared source callbacks. tests/graph.spec.ts.

Docs contracts/v0.4.1-graphs.md, OpenAPI, ADR003, scope-v0.4.1, references, README and reports/v0.4.1-acceptance.md. Launcher/pom/package version0.4.1, AI service version unchanged0.4.0.

### Task1 Fixed dependency/layout compatibility

- [x] Save product hash baseline, record user approval in spec.
- [x] Install fixed frontend dependencies with scripts disabled, inspect published declarations/licenses/tree. If Dagre declarations absent, select compatible published version within approved route and document evidence.
- [x] Write pure layout regression, observe missing implementation failure. Implement layout(nodes,edges,direction) using independent graph, deterministic stable sorting and cloned dimensions. Preserve self-loops/multiple edges, finite positions. Measure50/500/1000 node fixtures and build ReactFlow smoke.
- [x] Record PoC/version/license evidence, use isolated worker for product layout.

### Task2 Read-only graph projection and HTTP

Consumes AtlasStore.generation(snapshot,requested), published index JSON and files. Produces query(snapshot,Query), evidence(snapshot,edgeId,Query,limit,offset), members(snapshot,typeId,generation,limit,offset).

- [x] Add GraphIntegrationTest covering package/type/call IDs, aggregate evidence, old generations, unknown targets and exact source references, see RED missing HTTP.
- [x] Implement GraphIndex owner containment/outermost package inference, explicit unknown groups, source refs and placeholders. Stream generation payloads into compact projection.
- [x] Implement graph query filters, stable node/group IDs, current page/edge caps, total/boundary counts, default no-index result and published generation validation. CALL direct relation SQL/neighbor projection, always retain center.
- [x] Add members and paginated edge evidence, strict query validation, partial diagnostics. Verify RED→GREEN plus nested/duplicate/default/ambiguous ownership, filters/center/page and cross-generation/escaping regressions.

### Task3 React graph workspaces/navigation

Consumes GraphPage(nodes,edges,totalNodeCount,totalEdgeCount,boundary counts), source refs, member/evidence endpoints. Produces GraphWorkspace(projectId,snapshotId,generationId,onGeneration,onSource,onError,theme) and source entry URL view=graph.

- [x] Add browser first-flow missing-entry RED.
- [x] Add graph URL hook and lazy GraphWorkspace, share project/snapshot toolbar, graph entry and selected-symbol entry. Pin returned generation before subsequent requests, source push preserves graph history.
- [x] Add graph canvas, worker layout identity gate, zoom/fit/direction/center controls, per-identity session viewport/positions, state-styled edges with accessible labels. Add equivalent lists and layout failure recovery.
- [x] Add package drilldown, type member methods→CALL, direct neighbor recenter, evidence pagination and exact source jumps. Query/status/kind/self-loop/limits/page controls persist URL and reset paging appropriately.
- [x] Run browser graph/source/back/reload, package boundaries, edge source metadata, stale fetch/layout and rebuilt old-generation cases, keyboard/text escaping/deep-light/narrow. Build and run existing browser compatibility suite.

### Task4 Contracts and final review

- [x] Update contracts/OpenAPI, scope/ADR003/reuse licenses, version0.4.1 and launcher. Document precise totals/filter semantics.
- [x] Run complete H2/MySQL/Python/build/browser suites, read results and capture summaries. Dedicated MySQL3307 only, preserve user's3306 and8080.
- [x] Dispatch one fresh whole-implementation reviewer per executing-plans, fix Important findings with RED→GREEN and full regressions, no repeat review dispatch.

### Task5 Release and persistence acceptance

- [x] Preserve0.4.0 sanitized baseline JAR, run0.4.1 on existing dedicated V4 upgrade database and compare prior reviews/progress/training/guides. Verify graph source identities after restart/reindex.
- [x] Package, sanitize env config and exact current dist, verify licenses/zip/hash. Run final JAR/MySQL whole browser suite, UI/layout checks, archive evidence and report measured timings/bundle costs.
- [x] Mark ledger/checklist, stop live-verified owned test services, verify completion against approved scope. Final disclose actual limits and decisions.

## Ledger

2026-11-15 User approved detailed design and direct completion. Existing explicit authorization covers inline execution without another plan approval. No feature implementation agents. Final independent review remains required. Current configuration source contains local credentials, never print it, use safe distribution template.

Ruling: Continue authorized file delivery without Git worktree — repository has no Git metadata — rollback depends on artifact/data backups. Preflight Task1 layout DTO feeds Task3 graphNodes/edges; Task2 GraphPage feeds Task3 exact source/ID/total semantics; Task4/5 exercise fixed generation and old V4 data. No unresolved interface conflict.

Ruling: Dagre2.0.0 published package omits declared index.d.ts — fixed3.1.3 at a73acbfebe6fda008e1a3d80f3738170699c3d6b provides declarations, same approved library route — graphlib dependency changes to4.0.6 and is included in dependency/license verification. Layout tests RED missing module then GREEN2/2, measured50/500/1000 at17/105/220ms. Graph HTTP initial3 RED missing routes then GREEN3/3.
Ruling: Start baseline already contains V5 evidence ordering and V6 practice workspaces — preserve user's baseline and exercise migration through6 — 0.4.1 bundle includes those pre-existing migrations even though graph adds none. Local runtime uses resolved Surefire classpath because offline dependency plugin lacks its own dependencies, direct Unicode arguments avoid Windows argument-file encoding mismatch.

Task1 complete: fixed dependencies3.1.3/12.12.1, 22 licenses collected, pure layout3/3 including readable layer clearance. Task2 complete: GraphIntegration5/5, CALL evidence limit1 had RED400 then independent pagination GREEN. Task3 initial graph5/5, snapshot/project scope and explicit generation-adoption RED→GREEN; screenshot label overlap and narrow checkbox wrapping corrected. Full H2 total67,63passed4skip, MySQL67/67, Python10/10, first fullbrowser26/26; final28 and package running. One final implementation reviewer dispatched. Source baseline V5/V6 preserved. OpenAPI687refs validated.

Final independent review complete: Important first-index empty graph did not refresh after App task completion, Minor parallel self-loops shared Math.abs(fan) radius. Both covered by browser RED and pure-geometry RED. Fix passes App refreshRevision into graph request effect, preserving pinned generation on rebuild. Self-loop rank sets unique radius/labels, fitBounds includes loop extents. Added 2/3-loop pure tests and real SVG stroke click tests in both orientations. First full28 had27pass1failure because a test selected first randomly ordered edge but assumed CONFIRMED; test now selects its required static-confirmed evidence explicitly. Latest pure5/5 GREEN, browser review fixes running. No rereview dispatch.

Final review fix pass GREEN: pure geometry/layout5/5, targeted browser3/3 including first index and actual SVG self-loop clicks LR/TB. Vertical loop tangent now leaves bottom and enters top externally, layout reserves loop/label area so neighboring nodes are clear. All review findings addressed. Auto approval of a full-MySQL retry timed out without executing, one permitted retry launched normally. Final archive/report pending.


Final acceptance 2026-11-15 complete. JavaMySQL67/67, H2 total67 with63passed/4MySQL-onlyskipped, Python10/10, pure layout+geometry5/5, final JAR/MySQL browsers30/30, global theme/narrow1/1. Six migration records success=1,10 V4 resources compared across upgrade/restart, old source and old-generation graph restored after rebuild. One Important and one Minor review finding fixed, no deferred findings. Current dist/env config/22 licenses/ZIP verified. Final JAR af2177532d31cbfded36d62db0c194255ab1713fd6937f6b5a23253f41738874,33147211bytes. Live-verified owned Java2/Python4/MySQL2 processes closed, ports18080/18083/18001/8002/3307 verified absent. Files delivered in existing user-authorized directories with hash baseline, no branch action applicable. Report docs/reports/v0.4.1-acceptance.md and evidence/v041-graphs-2026-11-15 archived.

