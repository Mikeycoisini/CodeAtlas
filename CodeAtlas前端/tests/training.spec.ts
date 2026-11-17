import { test, expect, type APIRequestContext } from "@playwright/test";
import { artifact } from "./artifacts";
import fs from "node:fs";
import path from "node:path";

test.describe.configure({ mode: "serial" });

async function backend(request: APIRequestContext, method: string, route: string, data?: unknown) {
  const token = (await (await request.get("/api/session")).json()).token;
  const response = await request.fetch(`/api${route}`, { method, data, headers: { "Content-Type": "application/json", "X-CodeAtlas-Token": token } });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
async function waitTask(request: APIRequestContext, task: { id: string; status: string; snapshotId?: string }) {
  for (let index = 0; index < 200; index++) {
    if (!["QUEUED", "RUNNING"].includes(task.status)) { expect(task.status).toBe("SUCCEEDED"); return task; }
    await new Promise(resolve => setTimeout(resolve, 50)); task = await backend(request, "GET", `/tasks/${task.id}`);
  }
  throw new Error("Task timeout");
}
async function fixture(request: APIRequestContext) {
  const root = fs.mkdtempSync(path.resolve("../CodeAtlas后端/target/training-browser-"));
  fs.writeFileSync(path.join(root, "Example.java"), "class Example { int run() { return 1; } }");
  const imported = await waitTask(request, await backend(request, "POST", "/imports", { path: root }));
  const snapshot = imported.snapshotId!;
  await waitTask(request, await backend(request, "POST", `/snapshots/${snapshot}/index`, { rebuild: false }));
  const owner = (await backend(request, "GET", "/projects")).find((item: { sourcePath: string }) => item.sourcePath === root);
  return { root, snapshot, projectId: owner.id };
}

test("source task, saved draft, reveal, submission and self-assessment survive refresh", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=explore&projectId=${sample.projectId}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M run\(\)/ }).click();
  await page.getByRole("button", { name: "创建训练任务", exact: true }).click();
  await expect(page.getByRole("button", { name: "训练", exact: true })).toBeVisible();
  await page.getByLabel("任务标题", { exact: true }).fill("手工返回值训练");
  await page.getByLabel("学习目标", { exact: true }).fill("理解固定方法的返回值");
  await page.getByLabel("验收条件", { exact: true }).fill("能根据源码解释返回值");
  await page.getByLabel("第1题题干", { exact: true }).fill("run 返回 1 吗？");
  await page.getByLabel("第1题题型", { exact: true }).selectOption("TRUE_FALSE");
  await page.getByLabel("第1题参考答案", { exact: true }).selectOption("true");
  await page.getByLabel("第1题参考解释", { exact: true }).fill("return 1 返回整数 1");
  await page.getByLabel("第1题提示", { exact: true }).fill("查看 return 语句");
  await page.getByRole("button", { name: "添加题目", exact: true }).click();
  await page.getByLabel("第2题题干", { exact: true }).fill("解释 run 的行为");
  await page.getByLabel("第2题参考答案", { exact: true }).fill("返回整数 1");
  await page.getByLabel("第2题参考解释", { exact: true }).fill("根据 return 语句说明");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page).toHaveURL(/trainingTaskId=/);
  await page.reload();
  await expect(page.getByLabel("第2题题干", { exact: true })).toHaveValue("解释 run 的行为");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("版本 2", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "发布任务", exact: true }).click();
  await page.getByRole("button", { name: "开始作答", exact: true }).click();
  await expect(page).toHaveURL(/trainingAttemptId=/);
  const attemptUrl = page.url();
  await expect(page.getByLabel("第2题回答", { exact: true })).toHaveAttribute("maxlength", "4000");
  await page.getByLabel("第1题回答", { exact: true }).selectOption("true");
  await page.getByLabel("第2题回答", { exact: true }).fill("这个方法直接返回整数 1");
  await page.getByRole("button", { name: "保存回答", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已保存");
  await page.reload();
  await expect(page.getByLabel("第2题回答", { exact: true })).toHaveValue("这个方法直接返回整数 1");
  const first = page.locator(".training-question").nth(0);
  await first.getByRole("button", { name: "查看提示 1", exact: true }).click();
  await expect(first).toContainText("查看 return 语句");
  await page.getByRole("button", { name: "提交回答", exact: true }).click();
  await expect(first).toContainText("回答正确");
  await expect(first).toContainText("提交前查看过提示");
  const second = page.locator(".training-question").nth(1);
  await second.getByLabel("第2题自评", { exact: true }).selectOption("PARTIAL");
  await second.getByLabel("第2题复盘", { exact: true }).fill("继续练习调用链");
  await second.getByRole("button", { name: "保存自评", exact: true }).click();
  await expect(second).toContainText("用户自评");
  await page.reload();
  await expect(second).toContainText("继续练习调用链");
  await first.getByRole("button", { name: "查看参考答案", exact: true }).click();
  await expect(first.locator(".training-reference")).toContainText("return 1");
  await page.screenshot({ path: artifact("training-manual-result.png"), fullPage: true });
  await page.getByRole("button", { name: "重新作答", exact: true }).click();
  await expect(page).not.toHaveURL(attemptUrl);
  await page.getByLabel("第2题回答", { exact: true }).fill("保留这段草稿");
  await page.getByRole("button", { name: "保存回答", exact: true }).click();
  await page.getByRole("button", { name: "跳过本次", exact: true }).click();
  await expect(page.locator(".training-attempt-status")).toContainText("已跳过");
  await expect(page.getByLabel("第2题回答", { exact: true })).toHaveValue("保留这段草稿");
  await page.goto(attemptUrl);
  await expect(first).toContainText("回答正确");
});

test("late task creation cannot redirect a different workspace", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=explore&projectId=${sample.projectId}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M run\(\)/ }).click();
  await page.getByRole("button", { name: "创建训练任务", exact: true }).click();
  await page.getByLabel("任务标题", { exact: true }).fill("延迟保存训练");
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let received!: () => void;
  const reached = new Promise<void>(resolve => { received = resolve; });
  await page.route("**/api/training/tasks", async route => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch(); received(); await gate;
    await route.fulfill({ response });
  });
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await reached;
  await page.getByRole("button", { name: "文档", exact: true }).click();
  release();
  await expect(page).toHaveURL(/view=documents/);
  await expect(page.getByRole("heading", { name: "文档", exact: true })).toBeVisible();
});

test("optional lists can be cleared and archived editors can load the latest revision", async ({ page, request }) => {
  const sample = await fixture(request);
  await page.goto(`/?view=explore&projectId=${sample.projectId}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M run\(\)/ }).click();
  await page.getByRole("button", { name: "创建训练任务", exact: true }).click();
  await page.getByLabel("任务标题", { exact: true }).fill("清空可选列表");
  await page.getByLabel("学习目标", { exact: true }).fill("阅读源码");
  await page.getByLabel("验收条件", { exact: true }).fill("解释返回值");
  await page.getByLabel("第1题题干", { exact: true }).fill("run 做了什么？");
  await page.getByLabel("第1题参考答案", { exact: true }).fill("返回1");
  await page.getByLabel("第1题参考解释", { exact: true }).fill("return1");
  await page.getByLabel("标签", { exact: true }).fill("Java");
  await page.getByLabel("第1题提示", { exact: true }).fill("提示");
  await page.getByRole("button", { name: "发布任务", exact: true }).click();
  await expect(page.getByRole("button", { name: "开始作答", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "归档任务", exact: true }).click();
  await expect(page.getByRole("button", { name: "开始作答", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "编辑任务", exact: true }).click();
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("版本 3", { exact: true })).toBeVisible();
  const taskId = new URL(page.url()).searchParams.get("trainingTaskId")!;
  const editor = await backend(request, "GET", `/training/tasks/${taskId}/editor`);
  const { title, goal, constraints, acceptanceCriteria, status, tags, sourceIds, questions, generationId, revision } = editor;
  await backend(request, "PUT", `/training/tasks/${taskId}`, { title: "其他窗口更新", goal, constraints, acceptanceCriteria, status, tags, sourceIds, questions, generationId, revision });
  await page.getByLabel("任务标题", { exact: true }).fill(`${title}本地修改`);
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.locator(".training-editor [role=alert]")).toContainText("内容已更新");
  await expect(page.getByLabel("任务标题", { exact: true })).toHaveValue(`${title}本地修改`);
  await page.getByRole("button", { name: "放弃本地修改并加载最新版本", exact: true }).click();
  await expect(page.getByLabel("任务标题", { exact: true })).toHaveValue("其他窗口更新");
  await page.getByLabel("标签", { exact: true }).fill("");
  await page.getByLabel("第1题提示", { exact: true }).fill("");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("版本 5", { exact: true })).toBeVisible();
  await page.getByLabel("第1题提示", { exact: true }).fill("一条提示\n");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByText("版本 6", { exact: true })).toBeVisible();
});

test("AI questions require consent, append editable candidates and preserve rule results after feedback", async ({ page, request }) => {
  const sample = await fixture(request);
  await request.post("http://127.0.0.1:8002/reset", { data: { mode: "NORMAL" } });
  await page.goto(`/?view=explore&projectId=${sample.projectId}&snapshotId=${sample.snapshot}`);
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /^M run\(\)/ }).click();
  await page.getByRole("button", { name: "创建训练任务", exact: true }).click();
  await page.getByLabel("任务标题", { exact: true }).fill("保留手工标题");
  await page.getByLabel("学习目标", { exact: true }).fill("理解 run 的固定返回值");
  await page.getByLabel("验收条件", { exact: true }).fill("解释 return 语句");
  await page.getByLabel("第1题题干", { exact: true }).fill("手工题也保留");
  await page.getByLabel("第1题参考答案", { exact: true }).fill("返回 1");
  await page.getByLabel("第1题参考解释", { exact: true }).fill("根据方法源码");
  await page.getByRole("button", { name: "AI出题", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "AI出题", exact: true });
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await expect(dialog).toContainText("return 1");
  await dialog.getByRole("button", { name: "拒绝发送", exact: true }).click();
  expect((await (await request.get("http://127.0.0.1:8002/calls")).json()).calls).toBe(0);
  await dialog.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await dialog.getByRole("checkbox", { name: "我同意向上述供应商" }).check();
  await dialog.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(dialog.getByRole("button", { name: "追加到任务", exact: true })).toBeEnabled();
  const restoredId = new URL(page.url()).searchParams.get("trainingAiGenerationId")!;
  let releaseRestore!: () => void;
  const restoreGate = new Promise<void>(resolve => { releaseRestore = resolve; });
  let reachedRestore!: () => void;
  const restoring = new Promise<void>(resolve => { reachedRestore = resolve; });
  await page.route(`**/api/ai/generations/${restoredId}`, async route => {
    const response = await route.fetch(); reachedRestore(); await restoreGate; await route.fulfill({ response });
  });
  await page.reload();
  await restoring;
  try { await expect(dialog.getByRole("button", { name: "预览外发范围", exact: true })).toHaveCount(0); }
  finally { releaseRestore(); }
  await expect(dialog.getByRole("button", { name: "追加到任务", exact: true })).toBeEnabled();
  await dialog.getByRole("button", { name: "追加到任务", exact: true }).click();
  await expect(page.getByLabel("任务标题", { exact: true })).toHaveValue("保留手工标题");
  await expect(page.getByLabel("第1题题干", { exact: true })).toHaveValue("手工题也保留");
  await expect(page.getByLabel("第4题题干", { exact: true })).not.toBeEmpty();
  await page.getByRole("button", { name: "发布任务", exact: true }).click();
  await page.getByRole("button", { name: "开始作答", exact: true }).click();
  await page.getByLabel("第1题回答", { exact: true }).fill("返回 1");
  await page.getByLabel("第2题回答", { exact: true }).selectOption("B");
  await page.getByLabel("第3题回答", { exact: true }).selectOption("true");
  await page.getByLabel("第4题回答", { exact: true }).fill("返回整数 1");
  await page.getByRole("button", { name: "提交回答", exact: true }).click();
  const second = page.locator(".training-question").nth(1);
  await expect(second).toContainText("回答错误");
  await second.getByRole("button", { name: "AI辅助反馈", exact: true }).click();
  const feedback = page.getByRole("dialog", { name: "AI辅助反馈", exact: true });
  await feedback.getByRole("button", { name: "预览外发范围", exact: true }).click();
  await feedback.getByText("查看完整发送文本、问题与历史", { exact: true }).click();
  await expect(feedback.locator(".payload-details")).toContainText('"value": "B"');
  await expect(feedback.locator(".payload-details")).toContainText("submissionDigest");
  await feedback.getByRole("checkbox").check();
  await feedback.getByRole("button", { name: "授权并生成", exact: true }).click();
  await expect(feedback).toContainText("AI_ASSISTED");
  await page.reload();
  await expect(feedback).toContainText("AI_ASSISTED");
  await feedback.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(second).toContainText("回答错误");
  await expect(second).toContainText("AI辅助建议");
  await page.screenshot({ path: artifact("training-ai-result.png"), fullPage: true });
});
