import path from "node:path";

// E2E 证据截图默认写入 test-results；隔离沙箱可用 PW_ARTIFACTS 指到可写目录。
export function artifact(name: string) {
  return path.join(process.env.PW_ARTIFACTS ?? "test-results", name);
}
