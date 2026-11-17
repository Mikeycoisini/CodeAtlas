import { test, expect } from "@playwright/test";
import { artifact } from "./artifacts";

test("new architecture, analysis, AI and GitHub workspaces render together", async ({ page }) => {
  const problems: string[] = [];
  page.on("pageerror", error => problems.push(error.stack ?? error.message));
  const node = (id: string, kind: string, name: string) => ({
    id, kind, name, responsibility: name + " responsibility", technology: "Spring",
    parentId: kind === "COMPONENT" ? "module-1" : null, status: "CONFIRMED",
    evidenceIds: [], memberCount: 0,
  });
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const snapshot = { id: "snap1", projectId: "p1", manifestHash: "abc", sourceKind: "LOCAL",
      commitHash: null, createdAt: "2026-11-17T00:00:00Z", publishedGeneration: "gen1", latestTask: null };
    let body: unknown;
    if (path === "/api/session") body = { token: "test" };
    else if (path === "/api/projects") body = [{ id: "p1", name: "Demo Project", sourcePath: "D:/demo" }];
    else if (path === "/api/projects/p1/snapshots") body = [snapshot];
    else if (path === "/api/ai/status") body = { reachable: false, configured: false, capabilities: [], message: "AI 服务未连接" };
    else if (path === "/api/github/credentials") body = [];
    else if (path === "/api/github/projects/p1/binding") body = { bound: false, projectId: "p1" };
    else if (path === "/api/executions/status") body = { enabled: false, dockerAvailable: false, message: "安全准入未完成" };
    else if (path.startsWith("/api/snapshots/snap1/architecture")) body = {
      schemaVersion: "codeatlas-architecture-v1", projectId: "p1", snapshotId: "snap1", generationId: "gen1",
      projectionId: "projection-1", digest: "digest-1", analyzerVersion: "architecture-v1",
      perspective: "SYSTEM", focusId: "", depth: 2, limit: 80,
      nodes: [node("system", "SYSTEM", "Demo"), node("app", "APPLICATION", "Demo App"),
        node("module-1", "MODULE", "Demo Module"), node("component-1", "COMPONENT", "Demo Service")],
      edges: [{ id: "edge-1", source: "system", target: "app", kind: "CONTAINS", status: "CONFIRMED", reason: "", evidenceIds: [] }],
      groups: [{ id: "module-1", kind: "MODULE", name: "Demo Module", parentId: null, nodeIds: ["component-1"] }],
      evidence: [], diagnostics: [],
      coverage: { files: 3, javaFiles: 2, configFiles: 1, modules: 1, components: 1, externalServices: 0, dataStores: 0, unclassifiedTypes: 0, truncated: false },
      totalNodeCount: 4, totalEdgeCount: 1, omittedEdgeCount: 0, truncated: false,
    };
    else if (path === "/api/snapshots/snap1/files") body = { items: [], total: 0, generationId: "gen1", limit: 1000, offset: 0 };
    else if (path.includes("/api/snapshots/snap1/symbols")) body = { items: [], total: 0, generationId: "gen1", limit: 100, offset: 0 };
    else body = { items: [], total: 0 };
    await route.fulfill({ json: body });
  });
  await page.goto("/?view=architecture&projectId=p1&snapshotId=snap1&generationId=gen1");
  await expect(page.getByRole("heading", { name: "架构总览" })).toBeVisible();
  await expect(page.locator(".arch-node")).toHaveCount(4);
  await page.screenshot({ path: artifact("new-architecture.png"), fullPage: true });

  await page.getByRole("button", { name: "代码分析", exact: true }).click();
  await expect(page.getByRole("heading", { name: "代码分析" })).toBeVisible();
  await page.getByRole("button", { name: "AI 助手", exact: true }).click();
  await expect(page.getByRole("heading", { name: "AI 助手" })).toBeVisible();
  await page.getByRole("button", { name: "GitHub", exact: true }).click();
  await expect(page.getByRole("heading", { name: "私有仓库认证与同步" })).toBeVisible();
  await expect(page.locator(".github-error")).toHaveCount(0);
  await page.screenshot({ path: artifact("new-workspaces.png"), fullPage: true });
  expect(problems).toEqual([]);
});
