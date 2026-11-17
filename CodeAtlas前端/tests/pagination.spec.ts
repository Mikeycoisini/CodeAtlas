import { test, expect } from "@playwright/test";
import path from "node:path";
import fs from "node:fs";

test("shows the diagnostic total and loads every page", async ({ page }) => {
  test.setTimeout(180000);
  const source = fs.mkdtempSync(
    path.resolve("../CodeAtlas后端/target/browser-fixture-"),
  );
  fs.writeFileSync(path.join(source, "Good.java"), "class Good { void ok() {} }");
  for (let index = 0; index < 201; index++)
    fs.writeFileSync(path.join(source, `Broken${index}.java`), `class Broken${index} {`);
  await page.goto("/");
  await page.getByRole("button", { name: "导入项目", exact: true }).click();
  await page.getByLabel("项目目录").fill(source);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "创建快照" }).click();
  await expect(page.getByLabel("源码快照", { exact: true })).not.toHaveValue("", {
    timeout: 60000,
  });
  await expect(page.locator("footer")).toContainText(source, { timeout: 120000 });
  // 导入成功后前端会自动开始索引，等待自动索引的终态即可。
  await expect(page.getByText("索引部分成功", { exact: true })).toBeVisible({
    timeout: 60000,
  });
  const heading = page.locator("h4").filter({ hasText: "文件诊断" });
  await expect(heading).toContainText("200 / 201");
  await page.getByRole("button", { name: /加载更多诊断/ }).click();
  await expect(heading).toHaveText(/文件诊断\s+201/);
  await expect(page.getByRole("button", { name: /加载更多诊断/ })).toHaveCount(0);
});

test("shows the relation total and loads every page", async ({ page }) => {
  test.setTimeout(180000);
  const source = fs.mkdtempSync(
    path.resolve("../CodeAtlas后端/target/browser-fixture-"),
  );
  const calls = Array.from({ length: 201 }, (_, index) => `call${index}();`).join(" ");
  const methods = Array.from(
    { length: 201 },
    (_, index) => `void call${index}() {}`,
  ).join("\n");
  fs.writeFileSync(
    path.join(source, "Many.java"),
    `class Many {\n${methods}\nvoid all() { ${calls} }\n}`,
  );
  await page.goto("/");
  await page.getByRole("button", { name: "导入项目", exact: true }).click();
  await page.getByLabel("项目目录").fill(source);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "创建快照" }).click();
  await expect(page.getByLabel("源码快照", { exact: true })).not.toHaveValue("", {
    timeout: 60000,
  });
  await expect(page.locator("footer")).toContainText(source, { timeout: 120000 });
  // 导入成功后前端会自动开始索引，等待自动索引的终态即可。
  await expect(page.getByText("索引已完成", { exact: true })).toBeVisible({
    timeout: 60000,
  });
  await page.getByRole("button", { name: "符号", exact: true }).click();
  await page.getByLabel("搜索符号").fill("Many.all");
  await page
    .getByRole("button", { name: /^M all\(\) Many\.all/ })
    .click();
  const heading = page.locator("h4").filter({ hasText: "局部关系" });
  await expect(heading).toContainText("200 /");
  const initialText = (await heading.textContent()) ?? "";
  const total = Number(initialText.match(/\/\s*(\d+)/)?.[1] ?? 0);
  expect(total).toBeGreaterThan(200);
  let loaded = 200;
  for (let index = 0; index < 10; index++) {
    const more = page.getByRole("button", { name: /加载更多关系/ });
    if ((await more.count()) === 0) break;
    await more.click();
    await expect
      .poll(async () => {
        const text = (await heading.textContent()) ?? "";
        return Number(text.match(/局部关系\s+(\d+)/)?.[1] ?? 0);
      })
      .toBeGreaterThan(loaded);
    const text = (await heading.textContent()) ?? "";
    loaded = Number(text.match(/局部关系\s+(\d+)/)?.[1] ?? loaded);
  }
  await expect(heading).toHaveText(new RegExp(`局部关系\\s+${total}`));
  await expect(page.getByRole("button", { name: /加载更多关系/ })).toHaveCount(0);
});
