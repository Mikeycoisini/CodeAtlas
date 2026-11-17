import { test, expect } from "@playwright/test";
import { artifact } from "./artifacts";
import path from "node:path";
import fs from "node:fs";

test("imports a fixed snapshot, searches overloads and follows relation evidence", async ({
  page,
}) => {
  const problems: string[] = [];
  const source = fs.mkdtempSync(
    path.resolve("../CodeAtlas后端/target/browser-fixture-"),
  );
  fs.cpSync(
    path.resolve("../CodeAtlas后端/test-fixtures/spring-sample"),
    source,
    { recursive: true },
  );
  page.on("pageerror", (error) => problems.push(error.stack ?? error.message));
  await page.goto("/");
  await page.getByRole("button", { name: "导入项目", exact: true }).click();
  await page.getByLabel("项目目录").fill(source);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "创建快照" }).click();
  await expect(page.getByLabel("源码快照", { exact: true })).not.toHaveValue(
    "",
    { timeout: 60000 },
  );
  await expect(page.locator("footer")).toContainText(source, { timeout: 60000 });
  // 导入成功后前端自动开始索引，等待其终态即可。
  await expect(page.getByText("索引部分成功", { exact: true })).toBeVisible({
    timeout: 60000,
  });
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByLabel("搜索符号").fill("pay(String)");
  await page
    .getByRole("button", {
      name: /^M pay\(String\) sample\.PaymentService\.pay/,
    })
    .click();
  await expect(page.locator(".code-heading")).toContainText(
    "PaymentService.java",
  );
  await expect(page.locator(".annotation")).toContainText("@Transactional");
  await expect(page.locator(".relation")).toContainText(["audit"]);
  await expect(page.locator(".relation")).toContainText(["audit"]);
  await page
    .locator(".relation")
    .filter({ hasText: "audit" })
    .getByRole("button", { name: /查看证据/ })
    .click();
  await expect(page).toHaveURL(/line=8/);
  await page
    .getByRole("button", {
      name: /^M pay\(String\) sample\.BackupPayment\.pay/,
    })
    .click();
  await expect(page.locator(".code-heading")).toContainText(
    "BackupPayment.java",
  );
  await page.goBack();
  await expect(page.locator(".code-heading")).toContainText(
    "PaymentService.java",
  );
  await page.reload();
  await expect(page.locator(".code-heading")).toContainText(
    "PaymentService.java",
  );
  await expect(page.locator(".annotation")).toContainText("@Transactional");
  await expect(page.locator(".relation")).toContainText(["audit"]);
  await expect(page.locator('.error[role="alert"]')).toHaveCount(0);
  await page.screenshot({ path: artifact("explorer.png"), fullPage: true });
  expect(problems).toEqual([]);
});

test("late symbol pagination cannot replace a new search", async ({ page }) => {
  const source = fs.mkdtempSync(
    path.resolve("../CodeAtlas后端/target/browser-fixture-"),
  );
  fs.writeFileSync(
    path.join(source, "Many.java"),
    `class Many { ${Array.from({ length: 105 }, (_, n) => `void alpha${n}() {}`).join("\n")} void beta() {} }`,
  );
  await page.goto("/");
  await page.getByRole("button", { name: "导入项目", exact: true }).click();
  await page.getByLabel("项目目录").fill(source);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "创建快照" }).click();
  await expect(page.getByLabel("源码快照", { exact: true })).not.toHaveValue(
    "",
    { timeout: 60000 },
  );
  await expect(page.locator("footer")).toContainText(source, { timeout: 60000 });
  await expect(page.getByText("索引已完成", { exact: true })).toBeVisible({
    timeout: 60000,
  });
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByLabel("搜索符号").fill("alpha");
  await expect(page.locator("button.symbol")).toHaveCount(100);
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  let captured!: () => void;
  const intercepted = new Promise<void>((resolve) => {
    captured = resolve;
  });
  await page.route("**/symbols?*offset=100*", async (route) => {
    const response = await route.fetch();
    captured();
    await released;
    await route.fulfill({ response }).catch(() => {});
  });
  await page.getByRole("button", { name: /加载更多/ }).click();
  await intercepted;
  await page.getByLabel("搜索符号").fill("beta");
  await expect(page.locator("button.symbol")).toHaveCount(1);
  release();
  await expect(page.locator("button.symbol")).toHaveText(/beta/);
  await page.reload();
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByLabel("搜索符号").fill("beta");
  await page.getByRole("button", { name: /^M beta\(\)/ }).click();
  await page.reload();
  await expect(page.locator(".evidence h3")).toHaveText("beta()");
  await expect(page.locator('.error[role="alert"]')).toHaveCount(0);
});
