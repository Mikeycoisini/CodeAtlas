import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { artifact } from "./artifacts";
import fs from "node:fs";
import path from "node:path";

test.describe.configure({ mode: "serial" });

async function backend(request: APIRequestContext, method: string, route: string, data?: unknown) {
  const session = await (await request.get("/api/session")).json();
  const response = await request.fetch(`/api${route}`, { method, data, headers: { "X-CodeAtlas-Token": session.token, "Content-Type": "application/json" } });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
async function waitTask(request: APIRequestContext, id: string) {
  for (let i = 0; i < 150; i++) {
    const task = await backend(request, "GET", `/tasks/${id}`);
    if (!["QUEUED", "RUNNING"].includes(task.status)) return task;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Import/index timeout");
}
async function project(request: APIRequestContext, name: string) {
  const source = fs.mkdtempSync(path.resolve(`../CodeAtlas后端/target/${name}-`));
  fs.writeFileSync(path.join(source, "Example.java"), "package sample;\nclass Example { String run() { return \"old-value\"; } }\n");
  const imported = await backend(request, "POST", "/imports", { path: source });
  const snapshot = (await waitTask(request, imported.id)).snapshotId;
  const task = await backend(request, "POST", `/snapshots/${snapshot}/index`, { rebuild: false });
  expect((await waitTask(request, task.id)).status).toBe("SUCCEEDED");
  return { source, snapshot, name: path.basename(source) };
}
async function chooseSource(page: Page, projectName: string) {
  await page.getByRole("button", { name: "选择来源", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "关联源码" });
  await dialog.getByLabel("来源项目", { exact: true }).selectOption({ label: projectName });
  await expect(dialog.getByLabel("来源快照", { exact: true }).locator("option")).toHaveCount(2);
  await dialog.getByLabel("来源快照", { exact: true }).selectOption({ index: 1 });
  await dialog.getByRole("button", { name: /^run\(\)/ }).click();
  await expect(dialog).toHaveCount(0);
}

test("explicit current-source check detects changes without importing and preserves history", async ({ page, request }) => {
  const sample = await project(request, "review-current");
  const symbols = await backend(request, "GET", `/snapshots/${sample.snapshot}/symbols?kind=METHOD`);
  const source = await backend(request, "POST", "/sources", { snapshotId: sample.snapshot, generationId: symbols.generationId, symbolId: symbols.items[0].id });
  const knowledge = await backend(request, "POST", "/knowledge", { title: "Current source check", body: "Historical notes", sourceIds: [source.id], idempotencyKey: crypto.randomUUID() });
  await page.goto(`/?view=knowledge&recordId=${knowledge.id}`);
  const row = page.locator(".record-source");
  await expect(row).toContainText("未检查当前源码");
  await row.getByRole("button", { name: "检查当前源码", exact: true }).click();
  await expect(row).toContainText("当前文件与历史快照一致");
  fs.writeFileSync(path.join(sample.source, "Example.java"), 'package sample; class Example { String run() { return "new-value"; } }');
  expect((await backend(request, "GET", `/sources/${source.id}`)).snapshotOutdated).toBe(false);
  await row.getByRole("button", { name: "检查当前源码", exact: true }).click();
  await expect(row).toContainText("当前源码已变化");
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("Historical notes");
  await page.screenshot({ path: artifact("review-source-changed.png"), fullPage: true });
  await page.reload();
  await expect(row).toContainText("未检查当前源码");
  fs.unlinkSync(path.join(sample.source, "Example.java"));
  await row.getByRole("button", { name: "检查当前源码", exact: true }).click();
  await expect(row).toContainText("当前源码文件或目录已缺失");
  await row.getByRole("button", { name: "定位源码", exact: true }).click();
  await expect(page.getByRole("code")).toContainText("old-value");
});

test("transaction candidate displays inferred rule and its annotation evidence", async ({ page, request }) => {
  const root = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/review-inferred-"));
  fs.writeFileSync(path.join(root, "Tx.java"), "import org.springframework.transaction.annotation.Transactional;\nclass Tx {\n  @Transactional public void pay() {}\n}\n");
  const imported = await backend(request, "POST", "/imports", { path: root });
  const snapshot = (await waitTask(request, imported.id)).snapshotId;
  const indexed = await backend(request, "POST", `/snapshots/${snapshot}/index`, { rebuild: false });
  expect((await waitTask(request, indexed.id)).status).toBe("SUCCEEDED");
  const owner = (await backend(request, "GET", "/projects")).find((item: { sourcePath: string }) => item.sourcePath === root);
  await page.goto(`/?view=explore&projectId=${owner.id}&snapshotId=${snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M pay\(\)/ }).click();
  const relation = page.locator(".relation").filter({ hasText: "TRANSACTION_BOUNDARY" });
  await expect(relation.locator(".status")).toHaveText("推断");
  await expect(relation).toContainText("SPRING_TRANSACTIONAL_DECLARATION_V1");
  await relation.getByRole("button", { name: /查看证据 · L3/ }).click();
  await expect(page.getByRole("code")).toContainText("@Transactional");
});

test("late source-check response stays with its original document", async ({ page, request }) => {
  const sample = await project(request, "review-late-check");
  const symbols = await backend(request, "GET", `/snapshots/${sample.snapshot}/symbols?kind=METHOD`);
  const source = await backend(request, "POST", "/sources", { snapshotId: sample.snapshot, symbolId: symbols.items[0].id });
  const create = (title: string) => backend(request, "POST", "/documents", { title, body: title, sourceIds: [source.id], idempotencyKey: crypto.randomUUID() });
  const first = await create(`A check ${Date.now()}`);
  const second = await create(`B check ${Date.now()}`);
  let release!: () => void;
  let entered!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const checking = new Promise<void>(resolve => { entered = resolve; });
  await page.goto(`/?view=documents&recordId=${second.id}`);
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue(second.body);
  await page.getByRole("button").filter({ has: page.locator("strong", { hasText: first.title }) }).click();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue(first.body);
  await page.route(`**/api/sources/${source.id}/check-current`, async route => {
    const response = await route.fetch(); entered(); await held; await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "检查当前源码", exact: true }).click();
  await checking;
  await page.goBack();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue(second.body);
  release();
  await expect(page.getByRole("button", { name: "检查当前源码", exact: true })).toBeEnabled();
  await expect(page.locator(".record-source")).toContainText("未检查当前源码");
});

test("manual knowledge links two projects, creates a card and preserves historical source", async ({ page, request }) => {
  const first = await project(request, "learning-one");
  const second = await project(request, "learning-two");
  await page.goto("/?view=knowledge");
  await page.getByRole("button", { name: "新建知识", exact: true }).click();
  await page.getByLabel("标题", { exact: true }).fill("跨项目事务知识");
  await page.getByLabel("正文", { exact: true }).fill("人工整理的事务原理与两个项目案例。");
  await chooseSource(page, first.name);
  await chooseSource(page, second.name);
  await expect(page.locator(".record-source")).toHaveCount(2);
  await page.getByRole("button", { name: "保存知识", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  await page.reload();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("人工整理的事务原理与两个项目案例。");
  await expect(page.locator(".record-source")).toHaveCount(2);
  await page.getByRole("button", { name: "从知识创建卡片", exact: true }).click();
  await expect(page.getByLabel("答案", { exact: true })).toHaveValue("人工整理的事务原理与两个项目案例。");
  await page.getByLabel("问题", { exact: true }).fill("如何识别事务边界？");
  await page.getByLabel("个人笔记", { exact: true }).fill("我的学习记录");
  await page.getByRole("button", { name: "保存卡片", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  await page.reload();
  await expect(page.getByLabel("个人笔记", { exact: true })).toHaveValue("我的学习记录");
  fs.writeFileSync(path.join(first.source, "Example.java"), "package sample;\nclass Example { String run() { return \"new-value\"; } }\n");
  const imported = await backend(request, "POST", "/imports", { path: first.source });
  expect((await waitTask(request, imported.id)).snapshotId).not.toBe(first.snapshot);
  await page.reload();
  await expect(page.locator(".old-snapshot")).toContainText(["存在较新快照"]);
  await page.locator(".record-source").filter({ hasText: first.snapshot.slice(0, 8) }).getByRole("button", { name: "定位源码" }).click();
  await expect(page).toHaveURL(new RegExp(`snapshotId=${first.snapshot}`));
  await expect(page.getByRole("code")).toContainText("old-value");
  await page.screenshot({ path: artifact("learning-card.png"), fullPage: true });
});

test("refuses outbound then authorizes explanation, validates citations and preserves edited notes", async ({ page, request }) => {
  await request.post("http://127.0.0.1:8002/reset", { data: {} });
  const sample = await project(request, "learning-ai");
  const projects = await backend(request, "GET", "/projects");
  const owner = projects.find((p: { sourcePath: string }) => p.sourcePath === sample.source);
  await page.goto(`/?view=explore&projectId=${owner.id}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M run\(\)/ }).click();
  await page.getByRole("button", { name: "解释源码", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "源码解释" });
  await dialog.getByLabel("我的问题", { exact: true }).fill("解释该方法的业务含义");
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await expect(dialog.getByRole("heading", { name: "确认外发范围" })).toBeVisible();
  await dialog.getByRole("button", { name: "拒绝发送", exact: true }).click();
  await expect(dialog.getByRole("status")).toContainText("已拒绝发送");
  expect((await (await request.get("http://127.0.0.1:8002/calls")).json()).calls).toBe(0);
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await dialog.getByRole("checkbox").check();
  await dialog.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(dialog.locator(".generation-status strong")).toHaveText("已完成");
  await expect(dialog.locator(".citation-status")).toContainText(["引用已校验"]);
  expect((await (await request.get("http://127.0.0.1:8002/calls")).json()).calls).toBe(1);
  await page.reload();
  await expect(dialog.locator(".generation-status strong")).toHaveText("已完成");
  await dialog.getByRole("button", { name: "继续追问", exact: true }).click();
  await dialog.getByLabel("我的问题", { exact: true }).fill("补充解释返回值");
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await expect(dialog.locator(".payload-details")).toContainText("解释该方法的业务含义");
  await dialog.getByRole("checkbox").check();
  await dialog.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(dialog.locator(".generation-status strong")).toHaveText("已完成");
  expect((await (await request.get("http://127.0.0.1:8002/calls")).json()).calls).toBe(2);
  await dialog.getByLabel("保存正文", { exact: true }).fill("人工编辑后的解释与笔记。");
  await dialog.getByRole("button", { name: "保存为文档", exact: true }).click();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("人工编辑后的解释与笔记。");
  await page.reload();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("人工编辑后的解释与笔记。");
  const originalDocument = page.url();
  await page.getByRole("button", { name: "基于来源生成说明", exact: true }).click();
  await dialog.getByLabel("我的问题", { exact: true }).fill("重新生成独立候选");
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await dialog.getByRole("checkbox").check();
  await dialog.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(dialog.locator(".generation-status strong")).toHaveText("已完成");
  await page.reload();
  await expect(dialog.locator(".generation-status strong")).toHaveText("已完成");
  await dialog.getByLabel("保存正文", { exact: true }).fill("独立的新候选内容。");
  await dialog.getByRole("button", { name: "保存为文档", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("独立的新候选内容。");
  await page.goto(originalDocument);
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("人工编辑后的解释与笔记。");
  await page.getByRole("button", { name: "提炼知识", exact: true }).click();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("人工编辑后的解释与笔记。");
  await page.getByRole("button", { name: "保存知识", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  await page.getByRole("button", { name: "从知识创建卡片", exact: true }).click();
  await expect(page.getByLabel("答案", { exact: true })).toHaveValue("人工编辑后的解释与笔记。");
  await page.getByRole("button", { name: "保存卡片", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  await page.screenshot({ path: artifact("learning-workspace.png"), fullPage: true });
});

test("late save cannot replace another document after browser back", async ({ page, request }) => {
  const first = await backend(request, "POST", "/documents", { title: "A document " + Date.now(), body: "A original", idempotencyKey: crypto.randomUUID() });
  const second = await backend(request, "POST", "/documents", { title: "B document " + Date.now(), body: "B original", idempotencyKey: crypto.randomUUID() });
  let release!: () => void;
  let entered!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const saving = new Promise<void>(resolve => { entered = resolve; });
  await page.goto(`/?view=documents&recordId=${second.id}`);
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("B original");
  await page.getByRole("button").filter({ has: page.locator("strong", { hasText: first.title }) }).click();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("A original");
  await page.route(`**/api/documents/${first.id}`, async route => {
    if (route.request().method() !== "PUT") return route.continue();
    const response = await route.fetch(); entered(); await held; await route.fulfill({ response });
  });
  await page.getByLabel("正文", { exact: true }).fill("A edited");
  await page.getByRole("button", { name: "保存文档", exact: true }).click();
  await saving;
  await page.goBack();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("B original");
  release();
  await expect(page.getByRole("button", { name: "保存文档", exact: true })).toBeEnabled();
  await expect(page.getByLabel("正文", { exact: true })).toHaveValue("B original");
  await page.getByLabel("正文", { exact: true }).fill("B edited");
  await page.getByRole("button", { name: "保存文档", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  expect((await backend(request, "GET", `/documents/${second.id}`)).body).toBe("B edited");
});
