import { expect, type APIRequestContext } from "@playwright/test";

export async function backend(request: APIRequestContext, method: string, route: string, data?: unknown) {
  const token = (await (await request.get("/api/session")).json()).token;
  const response = await request.fetch(`/api${route}`, { method, data, headers: { "Content-Type": "application/json", "X-CodeAtlas-Token": token } });
  expect(response.ok(), await response.text()).toBeTruthy(); return response.json();
}
export async function studyCard(request: APIRequestContext) {
  const title = `返回值知识-${crypto.randomUUID().slice(0, 8)}`;
  const knowledge = await backend(request, "POST", "/knowledge", { title, body: "根据 return 语句理解返回值", sourceIds: [], tags: [], idempotencyKey: crypto.randomUUID() });
  const card = await backend(request, "POST", "/cards", { question: `run 返回什么？${title}`, answer: "返回整数 1", explanation: "根据 return 1", knowledgeId: knowledge.id, sourceIds: [], tags: ["Java"], idempotencyKey: crypto.randomUUID() });
  return { knowledge, card };
}
