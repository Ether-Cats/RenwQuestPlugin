import assert from "node:assert/strict";
import test from "node:test";
import { createAiService } from "../src/ai.js";
import { createApp } from "../src/app.js";

function completion(content, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() {
      return { choices: [{ message: { content } }] };
    }
  };
}

function enabledService(content) {
  return createAiService({
    enabled: true,
    baseUrl: "https://ai.example.test/v1",
    apiKey: "test-key",
    model: "test-model",
    rateLimitPerMinute: 6
  }, async () => completion(JSON.stringify(content)));
}

test("AI task drafts are normalized, bounded, and forced to the requested type", async () => {
  const service = enabledService({
    id: "mine_iron",
    name: "&f铁匠学徒",
    description: "挖掘铁矿石",
    type: "WEEKLY",
    experience: 60,
    priority: 5,
    objectives: [{ type: "block_break", target: "iron_ore", amount: 32 }],
    rewards: ["money:30", "item:iron_ingot:4"]
  });

  const result = await service.generateTask({
    prompt: "做一个挖铁矿石的每日任务",
    taskType: "DAILY",
    rateKey: "admin"
  });

  assert.equal(result.draft.type, "DAILY");
  assert.deepEqual(result.draft.objectives, [{ type: "BLOCK_BREAK", target: "IRON_ORE", amount: 32 }]);
  assert.deepEqual(result.draft.rewards, ["money:30", "item:IRON_INGOT:4"]);
  assert.equal(result.suggestedPath, "plugins/siyuan/quests/daily/mine_iron.yml");
  assert.match(result.yaml, /type: DAILY/);
});

test("AI drafts reject privileged task rewards and menu actions", async () => {
  const taskService = enabledService({
    id: "unsafe",
    name: "Unsafe",
    description: "Unsafe",
    type: "DAILY",
    experience: 1,
    priority: 1,
    objectives: [{ type: "BLOCK_BREAK", target: "STONE", amount: 1 }],
    rewards: ["command:op {player}"]
  });
  await assert.rejects(
    taskService.generateTask({ prompt: "unsafe", rateKey: "admin" }),
    /只允许 money、exp 和 item/
  );

  const menuService = enabledService({
    title: "&cUnsafe",
    size: 9,
    items: [{ slot: 0, material: "BARRIER", leftActions: ["console:op %player%"] }]
  });
  await assert.rejects(
    menuService.generateMenu({ prompt: "unsafe", rateKey: "admin" }),
    /不能包含 console: 或 op:/
  );
});

test("AI routes require the management key and audit only metadata", async (context) => {
  const calls = [];
  const audits = [];
  const aiService = {
    status: () => ({ enabled: true, model: "test-model", maxPromptChars: 2000 }),
    async generateTask(input) {
      calls.push(input);
      return {
        draft: { type: "DAILY", objectives: [{}], rewards: ["money:30"] },
        yaml: "id: mine_iron\n",
        suggestedPath: "plugins/siyuan/quests/daily/mine_iron.yml",
        warnings: []
      };
    },
    async generateMenu() {
      throw new Error("not used");
    }
  };
  const database = {
    health: async () => new Date("2026-01-01T00:00:00Z"),
    pool: {
      async query(sql, values = []) {
        if (sql.includes("INSERT INTO web_audit_log")) audits.push(values);
        return { rows: [], rowCount: 1 };
      }
    }
  };
  const app = createApp({
    apiKey: "a".repeat(32),
    corsOrigins: ["http://localhost"],
    trustProxy: 1
  }, database, { aiService });
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;

  assert.equal((await fetch(`${base}/api/ai/status`)).status, 401);
  const status = await fetch(`${base}/api/ai/status`, { headers: { "X-API-Key": "a".repeat(32) } });
  assert.deepEqual(await status.json(), { enabled: true, model: "test-model", maxPromptChars: 2000 });

  const response = await fetch(`${base}/api/ai/task-draft`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-API-Key": "a".repeat(32), "X-siyuan-Actor": "test-admin" },
    body: JSON.stringify({ prompt: "挖铁矿石", taskType: "DAILY" })
  });
  assert.equal(response.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].taskType, "DAILY");
  assert.equal(audits.length, 1);
  assert.equal(audits[0][0], "AI_TASK_DRAFT");
  assert.equal(audits[0][1], "test-admin");
  assert.deepEqual(audits[0][2], { type: "DAILY", objectiveCount: 1, rewardCount: 1 });
});
