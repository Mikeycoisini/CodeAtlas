import { test, expect } from "@playwright/test";
import path from "node:path";
import fs from "node:fs";
import { randomUUID } from "node:crypto";

const base = process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:18080";

test("practice workspace submits, records a user report and opens AI feedback", async ({ page, request }) => {
  const session = await request.get(`${base}/api/session`);
  const token = (await session.json()).token as string;
  const headers = { "X-CodeAtlas-Token": token, "Origin": base, "Content-Type": "application/json" };
  const api = async (method: string, route: string, data?: unknown) => {
    const response = await request.fetch(`${base}${route}`, { method, headers, data });
    if (!response.ok()) throw new Error(`${method} ${route} ${response.status()} ${await response.text()}`);
    return response.json();
  };
  const waitTask = async (id: string) => {
    for (let attempt = 0; attempt < 800; attempt++) {
      const task = await api("GET", `/api/tasks/${id}`);
      if (!["QUEUED", "RUNNING"].includes(task.status)) return task;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    throw new Error("task timeout");
  };
  const source = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/browser-fixture-"));
  fs.cpSync(path.resolve("../CodeAtlas后端/test-fixtures/spring-sample"), source, { recursive: true });
  const imported = await api("POST", "/api/imports", { path: source });
  const snapshot = (await waitTask(imported.id)).snapshotId as string;
  const indexed = await api("POST", `/api/snapshots/${snapshot}/index`, { rebuild: false });
  await waitTask(indexed.id);
  const symbols = await api("GET", `/api/snapshots/${snapshot}/symbols?query=pay&kind=METHOD&limit=100`);
  const symbol = symbols.items.find((item: any) => item.qualifiedName === "sample.PaymentService.pay" && item.signature === "pay(String)");
  if (!symbol) throw new Error("PaymentService.pay(String) not found");
  const sourceRecord = await api("POST", "/api/sources", { snapshotId: snapshot, generationId: symbols.generationId, symbolId: symbol.id });
  const questionId = randomUUID();
  const task = await api("POST", "/api/training/tasks", {
    title: `代码练习 ${Date.now()}`,
    goal: "修改 pay 方法并验证行为",
    acceptanceCriteria: "修改后可说明变更并记录外部测试结果",
    status: "ACTIVE",
    sourceIds: [sourceRecord.id],
    questions: [{
      id: questionId, format: "TRUE_FALSE", category: "CODE_READING",
      prompt: "pay 方法是否接受订单号？", options: [], correctAnswer: true,
      referenceExplanation: "是，参数为订单号。", hints: [],
    }],
    idempotencyKey: randomUUID(),
  });

  await page.goto(`${base}/?view=training&trainingTaskId=${task.id}`);
  await expect(page.getByRole("heading", { name: "代码练习", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "创建独立练习副本" }).click();
  const copy = page.locator("p").filter({ hasText: "副本目录：" }).locator("code");
  await expect(copy).not.toHaveText("");
  const copyPath = (await copy.textContent())?.trim() ?? "";
  fs.appendFileSync(path.join(copyPath, "src/main/java/sample/PaymentService.java"), "\n// practice edit\n");
  await page.getByRole("button", { name: "保存当前代码提交" }).click();
  await expect(page.getByRole("heading", { name: /提交 1/ })).toBeVisible();
  await page.getByLabel("验证方式").fill("本地 IDE 测试");
  await page.getByLabel("运行环境").fill("JDK 24 / Windows");
  await page.getByLabel("日志或报告").fill("添加注释后外部测试通过");
  await page.getByRole("button", { name: "记录用户测试结果" }).click();
  await expect(page.locator(".training-result")).toContainText("PASSED");
  await expect(page.locator(".training-result")).toContainText("USER_REPORTED");
  await expect(page.getByRole("button", { name: "关联该报告到知识" })).toBeVisible();
  await page.getByRole("button", { name: "请求 AI 反馈" }).click();
  await expect(page.getByRole("dialog", { name: "代码修改 AI 反馈" })).toBeVisible();
  await expect(page.getByRole("button", { name: "预览外发范围" })).toBeVisible();
  await page.getByRole("dialog", { name: "代码修改 AI 反馈" }).getByRole("button", { name: "关闭" }).click();
});

test("practice keeps immutable submissions, all report types and knowledge evidence", async ({ page, request }) => {
  test.setTimeout(300000);
  const session = await request.get(`${base}/api/session`);
  const token = (await session.json()).token as string;
  const headers = { "X-CodeAtlas-Token": token, "Origin": base, "Content-Type": "application/json" };
  const api = async (method: string, route: string, data?: unknown) => {
    const response = await request.fetch(`${base}${route}`, { method, headers, data });
    if (!response.ok()) throw new Error(`${method} ${route} ${response.status()} ${await response.text()}`);
    return response.json();
  };
  const waitTask = async (id: string) => {
    for (let attempt = 0; attempt < 400; attempt++) {
      const task = await api("GET", `/api/tasks/${id}`);
      if (!["QUEUED", "RUNNING"].includes(task.status)) return task;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("task timeout");
  };
  const source = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/browser-fixture-"));
  fs.cpSync(path.resolve("../CodeAtlas后端/test-fixtures/spring-sample"), source, { recursive: true });
  const imported = await api("POST", "/api/imports", { path: source });
  const snapshot = (await waitTask(imported.id)).snapshotId as string;
  const indexed = await api("POST", `/api/snapshots/${snapshot}/index`, { rebuild: false });
  await waitTask(indexed.id);
  const symbols = await api("GET", `/api/snapshots/${snapshot}/symbols?query=pay&kind=METHOD&limit=100`);
  const symbol = symbols.items.find((item: any) => item.qualifiedName === "sample.PaymentService.pay" && item.signature === "pay(String)");
  if (!symbol) throw new Error("PaymentService.pay(String) not found");
  const sourceRecord = await api("POST", "/api/sources", { snapshotId: snapshot, generationId: symbols.generationId, symbolId: symbol.id });
  const task = await api("POST", "/api/training/tasks", {
    title: `代码练习历史 ${Date.now()}`,
    goal: "完成两次独立修改并记录外部测试结果",
    acceptanceCriteria: "两次提交均可在历史中核对，外部报告可关联到知识",
    status: "ACTIVE",
    sourceIds: [sourceRecord.id],
    questions: [{
      id: randomUUID(), format: "TRUE_FALSE", category: "CODE_READING",
      prompt: "pay 方法是否接受订单号？", options: [], correctAnswer: true,
      referenceExplanation: "是，参数为订单号。", hints: [],
    }],
    idempotencyKey: randomUUID(),
  });
  const target = "src/main/java/sample/PaymentService.java";
  await page.goto(`${base}/?view=training&trainingTaskId=${task.id}`);
  await page.getByRole("button", { name: "创建独立练习副本" }).click();
  const copy = page.locator("p").filter({ hasText: "副本目录：" }).locator("code");
  await expect(copy).not.toHaveText("");
  const copyPath = (await copy.textContent())?.trim() ?? "";
  fs.appendFileSync(path.join(copyPath, target), "\n// practice edit v1\n");
  await page.getByRole("button", { name: "保存当前代码提交" }).click();
  await expect(page.getByRole("heading", { name: /提交 1/ })).toBeVisible();
  fs.appendFileSync(path.join(copyPath, target), "// practice edit v2\n");
  await page.getByRole("button", { name: "保存当前代码提交" }).click();
  await expect(page.getByRole("heading", { name: /提交 2/ })).toBeVisible();

  const workspaces = await api("GET", `/api/practice/workspaces?taskId=${task.id}`);
  const workspace = workspaces[0];
  const firstId = workspace.submissions[0].id as string;
  const secondId = workspace.submissions[1].id as string;
  const first = await api("GET", `/api/practice/submissions/${firstId}`);
  const second = await api("GET", `/api/practice/submissions/${secondId}`);
  expect(first.changes[0].diff).toContain("practice edit v1");
  expect(first.changes[0].diff).not.toContain("practice edit v2");
  expect(second.changes[0].diff).toContain("practice edit v2");

  await page.getByRole("button", { name: /提交 1 ·/ }).click();
  await expect(page.locator("h3").filter({ hasText: "提交 1" })).toBeVisible();
  await page.locator(".training-question details summary").filter({ hasText: target }).first().click();
  await expect(page.locator("pre").filter({ hasText: "practice edit v1" })).toBeVisible();

  const report = async (status: string, key = randomUUID()) =>
    api("POST", `/api/practice/submissions/${secondId}/test-results`,
      { status, method: "真实 mvn test", environment: "JDK 24 / Windows", report: `${status} 报告正文`, idempotencyKey: key });
  const passed = await report("PASSED");
  await report("FAILED");
  await report("ERROR");
  const timeoutKey = randomUUID();
  const timeoutFirst = await report("TIMEOUT", timeoutKey);
  const timeoutRetry = await report("TIMEOUT", timeoutKey);
  expect(timeoutRetry.id).toBe(timeoutFirst.id);
  const reported = await api("GET", `/api/practice/submissions/${secondId}`);
  expect(reported.testResults).toHaveLength(4);
  for (const result of reported.testResults) expect(result.contentDigest).toBe(reported.contentDigest);

  const knowledge = await api("POST", "/api/knowledge", {
    title: `实践证据 ${Date.now()}`, body: "从真实提交与报告核对掌握", tags: [], sourceIds: [], idempotencyKey: randomUUID(),
  });
  await api("POST", "/api/progress/evidence", { knowledgeId: knowledge.id, sourceType: "PRACTICE_SUBMISSION", sourceId: secondId, idempotencyKey: randomUUID() });
  await api("POST", "/api/progress/evidence", { knowledgeId: knowledge.id, sourceType: "PRACTICE_TEST_RESULT", sourceId: passed.id, idempotencyKey: randomUUID() });

  await page.goto(`${base}/?view=progress&progressKnowledgeId=${knowledge.id}`);
  await expect(page.getByText(/用户报告通过的实践测试\s*1\s*次/)).toBeVisible();
  await expect(page.locator(".evidence-item").filter({ hasText: "用户报告 · 测试通过" })).toBeVisible();
  await page.locator(".evidence-item details").filter({ hasText: "报告内容" }).locator("summary").click();
  await expect(page.locator(".evidence-item pre").filter({ hasText: "PASSED 报告正文" })).toBeVisible();
  await expect(page.getByRole("button", { name: "查看代码实践" })).toBeVisible();

  const preview = await api("POST", `/api/practice/submissions/${secondId}/ai-context`, { question: "这次修改满足验收吗？", depth: "STANDARD" });
  expect(preview.operation).toBe("PRACTICE_FEEDBACK");
  const denied = await api("POST", "/api/ai/consents", { contextId: preview.id, decision: "DENY" });
  const deniedGeneration = await request.fetch(`${base}/api/ai/generations`, {
    method: "POST", headers, data: { consentId: denied.id, idempotencyKey: randomUUID() },
  });
  expect(deniedGeneration.status()).toBe(403);
  const allowedPreview = await api("POST", `/api/practice/submissions/${secondId}/ai-context`, { question: "这次修改满足验收吗？", depth: "STANDARD" });
  const allowed = await api("POST", "/api/ai/consents", { contextId: allowedPreview.id, decision: "ALLOW" });
  const generated = await api("POST", "/api/ai/generations", { consentId: allowed.id, idempotencyKey: randomUUID() });
  let done = generated;
  for (let attempt = 0; attempt < 100 && ["QUEUED", "RUNNING"].includes(done.status); attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    done = await api("GET", `/api/ai/generations/${generated.id}`);
  }
  expect(done.status).toBe("SUCCEEDED");
  expect(done.result.source).toBe("AI_ASSISTED");
  expect(done.result.suggestedVerdict).toBe("PARTIAL");
  expect(done.result.citations[0].verificationStatus).toBe("VERIFIED");

  await page.goto(`${base}/?view=training&trainingTaskId=${task.id}`);
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "关闭并清理副本" }).click();
  await expect
    .poll(async () => (await api("GET", `/api/practice/workspaces/${workspace.id}`)).status)
    .toBe("CLOSED");
  const archived = await api("GET", `/api/practice/submissions/${firstId}`);
  expect(archived.changes[0].diff).toContain("practice edit v1");
});
