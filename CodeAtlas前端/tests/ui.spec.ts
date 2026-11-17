import { test, expect } from "@playwright/test";
import { artifact } from "./artifacts";
import path from "node:path";

test("主题、导航和窄屏工作台保持可用", async ({ page }) => {
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    if (url.hostname !== "localhost") { await route.fulfill({ body: "", contentType: "text/css" }); return; }
    const filePath = path.resolve("dist", url.pathname === "/" ? "index.html" : `.${url.pathname}`);
    await route.fulfill({ path: filePath });
  });
  const file = { id: "f1", path: "src/main/java/demo/Service.java", byteSize: 35, sha256: "1234567890abcdef" };
  const symbol = { id: "s1", fileId: "f1", kind: "METHOD", name: "run", qualifiedName: "demo.Service.run", signature: "run()", startLine: 2, endLine: 2, startColumn: 3, endColumn: 30, annotations: [] };
  const snapshot = { id: "snap1", projectId: "p1", manifestHash: "1234567890abcdef", sourceKind: "LOCAL", commitHash: null, createdAt: "2026-11-14T00:00:00Z", publishedGeneration: "gen1", latestTask: null };
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const body = path === "/api/session" ? { token: "test" }
      : path === "/api/projects" ? [{ id: "p1", name: "Demo Project", sourcePath: "D:/demo" }]
      : path === "/api/projects/p1/snapshots" ? [snapshot]
      : path.includes("/files/f1/content") ? { fileId: "f1", snapshotId: "snap1", content: "class Service {\n  void run() {}\n}", sha256: file.sha256, path: file.path, lineCount: 3 }
      : path.includes("/files") ? { items: [file], total: 1, generationId: null, limit: 1000, offset: 0 }
      : path.includes("/symbols/s1/relations") ? { items: [], total: 0, generationId: "gen1", limit: 1000, offset: 0 }
      : path.includes("/symbols") ? { items: [symbol], total: 1, generationId: "gen1", limit: 100, offset: 0 }
      : path === "/api/documents" || path === "/api/knowledge" || path === "/api/cards" ? { items: [], total: 0, generationId: null, limit: 30, offset: 0 }
      : { items: [], total: 0 };
    await route.fulfill({ json: body });
  });
  await page.goto("http://localhost/?view=explore");
  await expect(page.getByLabel("项目", { exact: true })).toHaveValue("p1");
  await page.getByRole("button", { name: "切换深色主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByRole("button", { name: /run\(\)/ }).click();
  await expect(page.locator(".code-heading")).toContainText("Service.java");
  await page.screenshot({ path: artifact("ui-desktop-dark.png") });
  await page.getByRole("separator", { name: "调整文件导航宽度" }).focus();
  await page.keyboard.press("ArrowRight");
  await expect.poll(() => page.locator(".navigator").evaluate(element => element.clientWidth)).toBeGreaterThan(255);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: artifact("ui-mobile-dark.png"), fullPage: true });
  for (const label of ["文档", "知识", "卡片"]) {
    await page.getByRole("button", { name: label, exact: true }).first().click();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
    await page.screenshot({ path: artifact(`ui-mobile-${label}.png`), fullPage: true });
  }
  await page.getByRole("button", { name: "新建卡片" }).click();
  await expect(page.getByLabel("问题", { exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: artifact("ui-mobile-card-editor.png"), fullPage: true });
  await page.getByRole("button", { name: "切换浅色主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.screenshot({ path: artifact("ui-mobile-light.png"), fullPage: true });
  await page.getByRole("button", { name: "导入项目", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "导入项目" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "导入项目" })).toHaveCount(0);
});
