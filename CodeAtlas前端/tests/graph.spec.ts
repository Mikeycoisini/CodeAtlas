import { test, expect, type APIRequestContext } from "@playwright/test";
import { artifact } from "./artifacts";
import fs from "node:fs";
import path from "node:path";
import { backend } from "./study-api";

async function completed(request: APIRequestContext, task: { id: string }) {
  await expect.poll(async () => (await backend(request, "GET", `/tasks/${task.id}`)).status, { timeout: 30000 }).toMatch(/SUCCEEDED|PARTIAL_SUCCESS/);
  return backend(request, "GET", `/tasks/${task.id}`);
}
async function fixture(request: APIRequestContext, indexNow = true, selfRelations = false) {
  const root = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/graph-browser-"));
  fs.mkdirSync(path.join(root, "sample"));
  const source = selfRelations ? "package sample;\nimport org.springframework.transaction.annotation.Transactional;\nclass A {\n @Transactional void run() { helper(); }\n private void helper() {}\n}\n" : "package sample;\nclass A {\n int run() { return new B().value(); }\n}\n";
  fs.writeFileSync(path.join(root, "sample/A.java"), source);
  fs.writeFileSync(path.join(root, "sample/B.java"), "package sample;\nclass B {\n int value() { return 1; }\n}\n");
  const imported = await completed(request, await backend(request, "POST", "/imports", { path: root }));
  const indexed = indexNow ? await completed(request, await backend(request, "POST", `/snapshots/${imported.snapshotId}/index`, { rebuild: false })) : { generationId: "" };
  const project = (await backend(request, "GET", "/projects")).find((item: { sourcePath: string }) => item.sourcePath === root);
  return { snapshot: imported.snapshotId, generation: indexed.generationId, project: project.id };
}

test("package and type graph navigate fixed source and restore the original graph", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=explore&projectId=${sample.project}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "关系图", exact: true }).click();
  await expect(page.locator(".graph-summary")).toContainText("节点");
  await page.getByRole("button", { name: "打开节点 sample", exact: true }).click();
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("TYPE");
  await page.getByRole("button", { name: "打开节点 A", exact: true }).click();
  await expect(page.locator(".code-heading")).toContainText("A.java");
  await expect(page).toHaveURL(new RegExp(`generationId=${sample.generation}`));
  await page.goBack();
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("TYPE");
  await page.reload();
  await expect(page.locator(".react-flow__node")).toHaveCount(2);
  await page.getByRole("button", { name: "查看成员 A", exact: true }).click();
  await page.getByRole("button", { name: "以 run() 为调用中心", exact: true }).click();
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("CALL");
  await expect(page.locator(".graph-node-list")).toContainText("value()");
  await page.locator(".graph-edge-list article").filter({ hasText: "静态调用 · 已确认" }).getByRole("button", { name: /查看关系证据/ }).click();
  await expect(page.locator(".graph-evidence")).toContainText("编译期声明目标");
  await page.getByRole("button", { name: /定位调用证据/ }).first().click();
  await expect(page.locator(".code-heading")).toContainText("A.java");
  await expect(page).toHaveURL(/line=3/);
  await page.goBack();
  await page.screenshot({ path: artifact("graph-call.png"), fullPage: true });
});

test("graph search ignores late responses and rebuild keeps the pinned historical generation", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=graph&graphView=TYPE&projectId=${sample.project}&snapshotId=${sample.snapshot}&generationId=${sample.generation}`);
  await expect(page.locator(".graph-summary")).toContainText(sample.generation);
  let release!: () => void; const gate = new Promise<void>(resolve => { release = resolve; });
  let entered!: () => void; const reached = new Promise<void>(resolve => { entered = resolve; });
  await page.route(`**/api/snapshots/${sample.snapshot}/graph?*`, async route => {
    if (new URL(route.request().url()).searchParams.get("query") !== "sample.B") return route.continue();
    const response = await route.fetch(); entered(); await gate; await route.fulfill({ response });
  });
  await page.getByLabel("搜索图节点", { exact: true }).fill("sample.B"); await reached;
  await page.getByLabel("搜索图节点", { exact: true }).fill("sample.A");
  await expect(page.locator(".graph-node-list article")).toHaveCount(1);
  release();
  await expect(page.getByRole("button", { name: "打开节点 A", exact: true })).toBeVisible();
  const rebuilt = await completed(request, await backend(request, "POST", `/snapshots/${sample.snapshot}/index`, { rebuild: true }));
  expect(rebuilt.generationId).not.toBe(sample.generation);
  await page.reload();
  await expect(page.locator(".graph-summary")).toContainText(sample.generation);
  await page.getByRole("button", { name: "打开节点 A", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`generationId=${sample.generation}`));
  await expect(page.locator(".code-heading")).toContainText("A.java");
});

test("late layout cannot replace a new graph and graph controls work in dark narrow mode", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.addInitScript(() => {
    const OriginalWorker = window.Worker;
    (window as any).__deferredLayouts = 0;
    window.Worker = class extends OriginalWorker {
      constructor(url: string | URL, options?: WorkerOptions) {
        super(url, options);
        if (!String(url).includes("layout.worker")) return;
        let callback: ((event: MessageEvent) => void) | null = null;
        Object.defineProperty(this, "onmessage", { get: () => callback, set: value => { callback = value; } });
        this.addEventListener("message", event => {
          (window as any).__deferredLayouts++;
          setTimeout(() => callback?.(event), 800);
        });
      }
    };
  });
  await page.goto(`/?view=graph&projectId=${sample.project}&snapshotId=${sample.snapshot}&generationId=${sample.generation}`);
  await expect.poll(() => page.evaluate(() => (window as any).__deferredLayouts)).toBeGreaterThan(0);
  await page.getByLabel("图形视图", { exact: true }).selectOption("TYPE");
  await expect(page.locator(".react-flow__node")).toHaveCount(3);
  await page.getByRole("button", { name: "切换深色主题", exact: true }).click();
  await page.getByLabel("布局方向", { exact: true }).selectOption("TB");
  await expect(page.locator(".react-flow__node")).toHaveCount(3);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await expect.poll(() => page.locator(".graph-controls fieldset").first().locator("label").first().evaluate(element => element.getBoundingClientRect().height)).toBeLessThan(32);
  await page.getByLabel("搜索图节点", { exact: true }).focus(); await page.keyboard.type("sample.A");
  await expect(page.locator(".graph-node-list article")).toHaveCount(1);
  await page.getByRole("button", { name: "打开节点 A", exact: true }).focus();
  await page.keyboard.press("Enter"); await expect(page.locator(".code-heading")).toContainText("A.java");
  await page.goBack();
  await page.screenshot({ path: artifact("graph-mobile-dark.png"), fullPage: true });
});

test("changing project clears graph-specific scope before loading the next snapshot", async ({ page, request }) => {
  const first = await fixture(request); const second = await fixture(request);
  await page.goto(`/?view=graph&projectId=${first.project}&snapshotId=${first.snapshot}&generationId=${first.generation}`);
  await page.getByRole("button", { name: "打开节点 sample", exact: true }).click();
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("TYPE");
  await page.getByLabel("项目", { exact: true }).selectOption(second.project);
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("PACKAGE");
  await expect(page.locator(".graph-summary")).toContainText(second.generation);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("graph rebuild preserves the old generation until the user explicitly adopts the new one", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=graph&projectId=${sample.project}&snapshotId=${sample.snapshot}&generationId=${sample.generation}`);
  await page.getByRole("button", { name: "打开节点 sample", exact: true }).click();
  await page.getByRole("button", { name: "重建索引", exact: true }).click();
  await expect(page.getByRole("button", { name: "查看最新索引", exact: true })).toBeVisible({ timeout: 15000 });
  await expect(page.locator(".graph-summary")).toContainText(sample.generation);
  await page.getByRole("button", { name: "查看最新索引", exact: true }).click();
  await expect(page.getByLabel("图形视图", { exact: true })).toHaveValue("PACKAGE");
  await expect(page.locator(".graph-summary")).not.toContainText(sample.generation);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("first index automatically populates an already open empty graph and pins its generation", async ({ page, request }) => {
  const sample = await fixture(request, false);
  await page.goto(`/?view=graph&projectId=${sample.project}&snapshotId=${sample.snapshot}`);
  await expect(page.locator(".graph-summary")).toContainText("尚无已发布索引");
  await page.getByRole("button", { name: "开始索引", exact: true }).click();
  await expect(page.getByRole("button", { name: "打开节点 sample", exact: true })).toBeVisible({ timeout: 15000 });
  await expect.poll(() => new URL(page.url()).searchParams.get("generationId")).toMatch(/^[0-9a-f-]{36}$/);
  await page.reload(); await expect(page.getByRole("button", { name: "打开节点 sample", exact: true })).toBeVisible();
});

test("parallel self relations keep separate paths and open their exact evidence in both directions", async ({ page, request }) => {
  const sample = await fixture(request, true, true);
  const graph = await backend(request, "GET", `/snapshots/${sample.snapshot}/graph?view=TYPE&generationId=${sample.generation}`);
  const loops = graph.edges.filter((edge: { source: string; target: string }) => edge.source === edge.target);
  expect(loops).toHaveLength(2);
  await page.goto(`/?view=graph&graphView=TYPE&projectId=${sample.project}&snapshotId=${sample.snapshot}&generationId=${sample.generation}`);
  for (const direction of ["LR", "TB"]) {
    await page.getByLabel("布局方向", { exact: true }).selectOption(direction);
    const paths: string[] = [];
    for (const loop of loops) {
      const interaction = page.locator(`.react-flow__edge[data-id="${loop.id}"] .react-flow__edge-interaction`);
      await expect(interaction).toHaveCount(1);
      paths.push((await interaction.getAttribute("d"))!);
      await expect.poll(() => interaction.evaluate((element: SVGPathElement) => {
        const point = element.getPointAtLength(element.getTotalLength() * 0.27);
        const screen = new DOMPoint(point.x, point.y).matrixTransform(element.getScreenCTM()!);
        const canvas = document.querySelector(".graph-canvas")!.getBoundingClientRect();
        return screen.x > canvas.left && screen.x < canvas.right && screen.y > canvas.top && screen.y < canvas.bottom;
      })).toBeTruthy();
      const point = await interaction.evaluate((element: SVGPathElement) => {
        const location = element.getPointAtLength(element.getTotalLength() * 0.27);
        const screen = new DOMPoint(location.x, location.y).matrixTransform(element.getScreenCTM()!);
        return { x: screen.x, y: screen.y };
      });
      await page.mouse.click(point.x, point.y);
      await expect(page.locator(".graph-evidence")).toContainText(loop.kind === "TRANSACTION_BOUNDARY" ? "SPRING_TRANSACTIONAL_DECLARATION_V1" : "编译期声明目标");
    }
    expect(new Set(paths).size).toBe(2);
    await page.screenshot({ path: artifact(`graph-self-${direction}.png`), fullPage: true });
  }
});
