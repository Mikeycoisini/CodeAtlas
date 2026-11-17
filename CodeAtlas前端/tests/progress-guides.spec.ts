import { test, expect } from "@playwright/test";
import { artifact } from "./artifacts";
import { backend, studyCard } from "./study-api";
import fs from "node:fs";
import path from "node:path";

test.describe.configure({ mode: "serial" });

test("guide steps can reorder, skip, exit, resume and receive explicitly accepted AI advice", async ({ page, request }) => {
  await request.post("http://127.0.0.1:8002/reset", { data: { mode: "NORMAL" } });
  const guide = await backend(request, "POST", "/guides", { title: `事务学习引导-${crypto.randomUUID().slice(0, 8)}`, goal: "从源码理解事务并复盘", sourceIds: [], knowledgeIds: [], idempotencyKey: crypto.randomUUID() });
  await page.goto(`/?view=guides&guideId=${guide.id}`);
  const business = page.locator('.guide-step[data-step-id="BUSINESS"]');
  await business.getByLabel("步骤状态", { exact: true }).selectOption("SKIPPED");
  await business.getByLabel("步骤笔记", { exact: true }).fill("稍后再做这一项");
  await business.getByRole("button", { name: "下移", exact: true }).click();
  await page.getByRole("button", { name: "保存引导", exact: true }).click();
  await expect(page.locator(".study-notice")).toContainText("已保存");
  await page.getByRole("button", { name: "暂停引导", exact: true }).click();
  await page.getByRole("button", { name: "退出引导", exact: true }).click();
  await expect(page.locator(".guide-state")).toContainText("已退出");
  await page.getByRole("button", { name: "项目探索", exact: true }).click();
  await page.getByRole("button", { name: "引导", exact: true }).click();
  await page.getByRole("button", { name: guide.title, exact: false }).click();
  await page.getByRole("button", { name: "恢复引导", exact: true }).click();
  await expect(business.getByLabel("步骤笔记", { exact: true })).toHaveValue("稍后再做这一项");
  await page.getByRole("button", { name: "AI调整建议", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "AI学习引导", exact: true });
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await dialog.getByRole("button", { name: "拒绝发送", exact: true }).click();
  expect((await (await request.get("http://127.0.0.1:8002/calls")).json()).calls).toBe(0);
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await dialog.getByRole("checkbox", { name: "我同意向上述供应商" }).check();
  await dialog.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(dialog.getByRole("button", { name: "采纳步骤建议", exact: true })).toBeEnabled();
  const restoredId = new URL(page.url()).searchParams.get("guideAiGenerationId")!;
  let releaseRestore!: () => void; const restoreGate = new Promise<void>(resolve => { releaseRestore = resolve; });
  let reachedRestore!: () => void; const restoring = new Promise<void>(resolve => { reachedRestore = resolve; });
  await page.route(`**/api/ai/generations/${restoredId}`, async route => { const response = await route.fetch(); reachedRestore(); await restoreGate; await route.fulfill({ response }); });
  await page.reload(); await restoring;
  try { await expect(dialog.getByRole("button", { name: "预览外发范围", exact: true })).toHaveCount(0); }
  finally { releaseRestore(); }
  await expect(dialog.getByRole("button", { name: "采纳步骤建议", exact: true })).toBeEnabled();
  await dialog.getByRole("button", { name: "采纳步骤建议", exact: true }).click();
  await expect(page.locator(".guide-step").first()).toHaveAttribute("data-step-id", "REFLECT");
  await expect(business.getByLabel("步骤状态", { exact: true })).toHaveValue("SKIPPED");
  await expect(business.getByLabel("步骤笔记", { exact: true })).toHaveValue("稍后再做这一项");
  await page.screenshot({ path: artifact("v04-guide-applied.png"), fullPage: true });
});

test("personal reflections keep provenance and survive refresh without becoming automatic mastery", async ({ page, request }) => {
  const { knowledge } = await studyCard(request);
  await page.goto(`/?view=progress&progressKnowledgeId=${knowledge.id}`);
  await page.getByLabel("个人记录评分", { exact: true }).selectOption("NEEDS_REVIEW");
  await page.getByLabel("个人复盘", { exact: true }).fill("需要再对照事务边界");
  await page.getByRole("button", { name: "保存个人记录", exact: true }).click();
  await expect(page.locator(".progress-assessment")).toContainText("建议复盘");
  await page.reload();
  await expect(page.locator(".evidence-item")).toContainText("需要再对照事务边界");
  await expect(page.locator(".evidence-item")).toContainText("用户自报");
});

test("knowledge progress shows related review due times and opens the card history", async ({ page, request }) => {
  const { knowledge, card } = await studyCard(request);
  await backend(request, "POST", `/reviews/cards/${card.id}/enroll`, { idempotencyKey: crypto.randomUUID() });
  await page.goto(`/?view=progress&progressKnowledgeId=${knowledge.id}`);
  await expect(page.locator(".progress-reviews")).toContainText(card.question);
  await expect(page.locator(".progress-reviews")).toContainText("到期时间");
  await page.locator(".progress-reviews").getByRole("button", { name: card.question, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`reviewCardId=${card.id}`));
  await expect(page.locator(".review-card-state")).toBeVisible();
});

test("repeated personal records are new facts while an uncertain retry stays idempotent", async ({ page, request }) => {
  const { knowledge } = await studyCard(request);
  await page.goto(`/?view=progress&progressKnowledgeId=${knowledge.id}`);
  for (const [index, rating] of ["NEEDS_REVIEW", "UNDERSTOOD", "NEEDS_REVIEW"].entries()) {
    await page.getByLabel("个人记录评分", { exact: true }).selectOption(rating);
    await page.getByRole("button", { name: "保存个人记录", exact: true }).click();
    await expect(page.locator(".evidence-item")).toHaveCount(index + 1);
  }
  await expect(page.locator(".progress-assessment")).toContainText("建议复盘");
  let failed = false;
  const keys: string[] = [];
  await page.route("**/api/progress/evidence", async route => {
    keys.push(route.request().postDataJSON().idempotencyKey);
    const response = await route.fetch();
    if (!failed) { failed = true; await route.abort("failed"); } else await route.fulfill({ response });
  });
  await page.getByLabel("个人复盘", { exact: true }).fill("网络失败后重试");
  await page.getByRole("button", { name: "保存个人记录", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "保存个人记录", exact: true }).click();
  await expect(page.locator(".evidence-item")).toHaveCount(4);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
});

test("progress links focus the exact question in a multi-question attempt", async ({ page, request }) => {
  const { knowledge } = await studyCard(request);
  const root = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/question-link-"));
  fs.writeFileSync(path.join(root, "Example.java"), "class Example { int run() { return 1; } }");
  const imported = await backend(request, "POST", "/imports", { path: root });
  await expect.poll(async () => (await backend(request, "GET", `/tasks/${imported.id}`)).status).toBe("SUCCEEDED");
  const snapshotId = (await backend(request, "GET", `/tasks/${imported.id}`)).snapshotId;
  const indexing = await backend(request, "POST", `/snapshots/${snapshotId}/index`, { rebuild: false });
  await expect.poll(async () => (await backend(request, "GET", `/tasks/${indexing.id}`)).status).toBe("SUCCEEDED");
  const symbols = await backend(request, "GET", `/snapshots/${snapshotId}/symbols?kind=METHOD`);
  const source = await backend(request, "POST", "/sources", { snapshotId, generationId: symbols.generationId, symbolId: symbols.items[0].id });
  const questions = Array.from({ length: 5 }, (_, index) => ({ id: crypto.randomUUID(), category: "CODE_READING", format: "TRUE_FALSE", prompt: `定位第${index + 1}题`, options: [], correctAnswer: true, referenceExplanation: "return 1", hints: [] }));
  const task = await backend(request, "POST", "/training/tasks", { title: "多题定位", goal: "精确定位", acceptanceCriteria: "回答", status: "ACTIVE", sourceIds: [source.id], questions, idempotencyKey: crypto.randomUUID() });
  const attempt = await backend(request, "POST", `/training/tasks/${task.id}/attempts`, { idempotencyKey: crypto.randomUUID() });
  await backend(request, "POST", `/training/attempts/${attempt.id}/submit`, { revision: attempt.revision, answers: questions.map(question => ({ questionId: question.id, value: false })), idempotencyKey: crypto.randomUUID() });
  await backend(request, "POST", "/progress/evidence", { knowledgeId: knowledge.id, sourceType: "TRAINING_RESULT", sourceId: attempt.id, questionId: questions[4].id, idempotencyKey: crypto.randomUUID() });
  await page.goto(`/?view=progress&progressKnowledgeId=${knowledge.id}`);
  await page.getByRole("button", { name: "查看原始作答", exact: true }).click();
  const selected = page.locator('.training-question[data-selected="true"]');
  await expect(selected).toContainText("定位第5题");
  await expect(selected).toBeInViewport();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.reload(); await expect(selected).toBeInViewport();
  await page.goBack(); await expect(page.locator(".evidence-item")).toHaveCount(1);
  await page.goForward(); await expect(selected).toBeInViewport();
});

test("all-skipped guide can finish and reopen with its history intact", async ({ page }) => {
  await page.goto("/?view=guides");
  await page.getByRole("button", { name: "新建引导", exact: true }).click();
  await page.getByLabel("引导标题", { exact: true }).fill(`跳过引导-${crypto.randomUUID().slice(0, 8)}`);
  await page.getByLabel("引导目标", { exact: true }).fill("按自己的目标学习");
  await page.getByRole("button", { name: "保存引导", exact: true }).click();
  await expect(page.locator(".guide-step")).toHaveCount(6);
  for (let index = 0; index < 6; index++) await page.locator(".guide-step").nth(index).getByLabel("步骤状态", { exact: true }).selectOption("SKIPPED");
  await page.getByRole("button", { name: "结束引导", exact: true }).click();
  await expect(page.locator(".guide-state")).toContainText("已结束");
  await expect(page.locator(".study-detail")).toContainText("已完成 0 · 已跳过 6");
  await page.reload();
  await page.getByRole("button", { name: "重新打开引导", exact: true }).click();
  await expect(page.locator(".guide-state")).toContainText("进行中");
  await page.getByRole("button", { name: "查看引导历史", exact: true }).click();
  await expect(page.locator(".review-history-item")).toHaveCount(4);
});
