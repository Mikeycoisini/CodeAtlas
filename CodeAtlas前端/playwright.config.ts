import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  timeout: 60000,
  // 后端任务队列为单工作线程，浏览器用例必须串行，避免并行导入互相排队。
  workers: 1,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:5171",
    channel: "chrome",
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
  },
});
