import { test, expect } from "@playwright/test";
import { artifact } from "./artifacts";
import { backend, studyCard } from "./study-api";

test.describe.configure({ mode: "serial" });

test("card recall, two reviews, immutable history and knowledge evidence survive refresh", async ({ page, request }) => {
  const { knowledge, card } = await studyCard(request);
  await page.goto(`/?view=cards&recordId=${card.id}`);
  await page.getByRole("button", { name: "加入复习", exact: true }).click();
  await page.getByRole("button", { name: "开始复习", exact: true }).click();
  await expect(page).toHaveURL(/reviewAttemptId=/);
  await page.getByLabel("回忆内容", { exact: true }).fill("run 直接返回整数一");
  await page.getByRole("button", { name: "保存回忆", exact: true }).click();
  await expect(page.locator(".study-notice")).toContainText("已保存");
  await page.reload();
  await expect(page.getByLabel("回忆内容", { exact: true })).toHaveValue("run 直接返回整数一");
  await page.getByRole("button", { name: "查看答案", exact: true }).click();
  await expect(page.locator(".review-reference")).toContainText("返回整数 1");
  await page.getByRole("button", { name: "记住", exact: true }).click();
  await expect(page.locator(".review-result")).toContainText("用户自报");
  await expect(page.locator(".review-result")).toContainText("下次复习");
  await page.getByRole("button", { name: "再次复习", exact: true }).click();
  await page.getByLabel("回忆内容", { exact: true }).fill("整数 1");
  await page.getByRole("button", { name: "查看答案", exact: true }).click();
  await page.getByRole("button", { name: "简单", exact: true }).click();
  await expect(page.locator(".review-result")).toContainText("下次复习");
  await page.getByRole("button", { name: "查看复习历史", exact: true }).click();
  await expect(page.locator('.review-history-item[data-kind="REVIEW"]')).toHaveCount(2);
  await page.getByLabel("显示时区", { exact: true }).selectOption("UTC");
  await page.reload();
  await expect(page.getByLabel("显示时区", { exact: true })).toHaveValue("UTC");
  await expect(page.locator('.review-history-item[data-kind="REVIEW"]')).toHaveCount(2);
  await page.getByRole("button", { name: "进度", exact: true }).click();
  await page.getByRole("button", { name: knowledge.title, exact: false }).click();
  await expect(page.locator(".progress-detail")).toContainText("已有支持证据");
  await expect(page.locator(".evidence-item")).toHaveCount(2);
  await page.screenshot({ path: artifact("v04-progress-supported.png"), fullPage: true });
  const first = page.locator(".evidence-item").first();
  await first.getByRole("button", { name: "撤回关联", exact: true }).click();
  await first.getByLabel("撤回原因", { exact: true }).fill("这条记录需要重新关联");
  await first.getByRole("button", { name: "确认撤回", exact: true }).click();
  await expect(page.locator(".progress-assessment")).toContainText("练习中");
  await expect(page.locator(".evidence-retracted")).toHaveCount(1);
});

test("changed card blocks old grading and explicit reset starts the new content", async ({ page, request }) => {
  const { card } = await studyCard(request);
  await backend(request, "POST", `/reviews/cards/${card.id}/enroll`, { idempotencyKey: crypto.randomUUID() });
  await page.goto(`/?view=reviews&reviewCardId=${card.id}`);
  await page.getByRole("button", { name: "开始复习", exact: true }).click();
  await page.getByLabel("回忆内容", { exact: true }).fill("原回忆保留");
  await page.getByRole("button", { name: "查看答案", exact: true }).click();
  await backend(request, "PUT", `/cards/${card.id}`, { revision: card.revision, question: card.question, answer: "更新后的参考答案", explanation: card.explanation, knowledgeId: card.knowledgeId, sourceIds: [], tags: [] });
  await page.getByRole("button", { name: "记住", exact: true }).click();
  await expect(page.locator(".study-error")).toContainText("卡片内容已更新");
  await expect(page.getByLabel("回忆内容", { exact: true })).toHaveValue("原回忆保留");
  await page.getByRole("button", { name: "返回卡片", exact: true }).click();
  await page.getByRole("button", { name: "采用新版并重新学习", exact: true }).click();
  await expect(page.locator(".review-card-state")).toContainText("轮次 2");
  await page.getByRole("button", { name: "开始复习", exact: true }).click();
  await page.getByRole("button", { name: "查看答案", exact: true }).click();
  await expect(page.locator(".review-reference")).toContainText("更新后的参考答案");
  await page.getByRole("button", { name: "跳过本次", exact: true }).click();
  await expect(page.locator(".review-attempt-state")).toContainText("已跳过");
});

test("old review event opens by identity beyond the first history page", async ({ page, request }) => {
  const { card } = await studyCard(request);
  await backend(request, "POST", `/reviews/cards/${card.id}/enroll`, { idempotencyKey: crypto.randomUUID() });
  let oldEvent = "";
  for (let index = 0; index < 22; index++) {
    const attempt = await backend(request, "POST", `/reviews/cards/${card.id}/attempts`, { idempotencyKey: crypto.randomUUID() });
    const saved = await backend(request, "PUT", `/reviews/attempts/${attempt.id}`, { revision: attempt.revision, recalledAnswer: index === 0 ? "最早的回忆内容" : `后续回忆${index}` });
    const revealed = await backend(request, "POST", `/reviews/attempts/${attempt.id}/reveal`, { revision: saved.revision, idempotencyKey: crypto.randomUUID() });
    const done = await backend(request, "POST", `/reviews/attempts/${attempt.id}/submit`, { revision: revealed.revision, rating: "GOOD", idempotencyKey: crypto.randomUUID() });
    if (index === 0) oldEvent = done.reviewEvent.id;
  }
  await page.goto(`/?view=reviews&reviewCardId=${card.id}&reviewEventId=${oldEvent}`);
  await expect(page.locator(".review-selected-event")).toContainText("最早的回忆内容");
  await expect(page.locator('.review-history-item[data-kind="REVIEW"]')).toHaveCount(20);
  await page.getByRole("button", { name: "较早记录", exact: true }).click();
  await expect(page.locator('.review-history-item[data-kind="REVIEW"]')).toHaveCount(2);
});

test("late enrollment cannot redirect a newly selected workspace", async ({ page, request }) => {
  const { card } = await studyCard(request);
  await page.goto(`/?view=cards&recordId=${card.id}`);
  let release!: () => void; const gate = new Promise<void>(resolve => { release = resolve; });
  let received!: () => void; const reached = new Promise<void>(resolve => { received = resolve; });
  await page.route(`**/api/reviews/cards/${card.id}/enroll`, async route => { const response = await route.fetch(); received(); await gate; await route.fulfill({ response }); });
  await page.getByRole("button", { name: "加入复习", exact: true }).click(); await reached;
  await page.getByRole("button", { name: "进度", exact: true }).click(); release();
  await expect(page).toHaveURL(/view=progress/);
  await expect(page.getByRole("heading", { name: "学习证据与复盘", exact: true })).toBeVisible();
});
