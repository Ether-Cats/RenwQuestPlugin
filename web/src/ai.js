import YAML from "yaml";
import { normalizeMenu } from "./menu-codec.js";

const TASK_TYPES = new Set(["DAILY", "WEEKLY", "SEASONAL", "STORY", "CHALLENGE"]);
const OBJECTIVE_TYPES = new Set([
  "BLOCK_BREAK", "ENTITY_KILL", "ITEM_CRAFT", "ITEM_CONSUME", "PLAYER_JUMP", "DAMAGE_DEALT", "DAMAGE_TAKEN"
]);
const TASK_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;
const TARGET_PATTERN = /^(ANY|[A-Z0-9_]{1,64})$/;
const FORBIDDEN_MENU_ACTION = /^(console|op):/i;

export class AiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function text(value, field, maxLength) {
  const normalized = String(value ?? "").replace(/[\r\n]+/g, " ").trim();
  if (!normalized) throw new AiError(422, `AI 返回的 ${field} 为空`);
  if (normalized.length > maxLength) throw new AiError(422, `AI 返回的 ${field} 超过 ${maxLength} 字符`);
  return normalized;
}

function integer(value, field, min, max, fallback) {
  if (value == null && fallback != null) return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new AiError(422, `AI 返回的 ${field} 必须是 ${min} 到 ${max} 的整数`);
  }
  return parsed;
}

function decimal(value, field, min, max) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) {
    throw new AiError(422, `AI 返回的 ${field} 必须是 ${min} 到 ${max} 的数字`);
  }
  return parsed;
}

function requiredArray(value, field, maxLength) {
  if (!Array.isArray(value) || !value.length || value.length > maxLength) {
    throw new AiError(422, `AI 返回的 ${field} 必须是 1 到 ${maxLength} 项的数组`);
  }
  return value;
}

function jsonObject(content) {
  const raw = String(content || "").trim();
  const candidates = [raw];
  const fenced = raw.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fenced) candidates.push(fenced[1]);
  const first = raw.indexOf("{");
  const last = raw.lastIndexOf("}");
  if (first >= 0 && last > first) candidates.push(raw.slice(first, last + 1));
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
    } catch {
      // Try the next form. Providers sometimes wrap an otherwise valid object in a code fence.
    }
  }
  throw new AiError(422, "AI 没有返回可用的 JSON 草稿，请换一种描述后重试");
}

function taskType(value, fallback) {
  const type = String(value || fallback || "").trim().toUpperCase();
  if (!TASK_TYPES.has(type)) throw new AiError(422, "AI 返回了不支持的任务类型");
  return type;
}

function target(value) {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (!TARGET_PATTERN.test(normalized)) {
    throw new AiError(422, "AI 返回的任务目标格式无效");
  }
  return normalized;
}

function reward(value) {
  const raw = String(value ?? "").trim();
  const split = raw.indexOf(":");
  if (split < 1) throw new AiError(422, "AI 返回的奖励格式无效");
  const kind = raw.slice(0, split).toLowerCase();
  const payload = raw.slice(split + 1).trim();
  if (kind === "money") return `money:${decimal(payload, "金币奖励", 0.01, 1_000_000)}`;
  if (kind === "exp") return `exp:${integer(payload, "经验奖励", 1, 1_000_000)}`;
  if (kind === "item") {
    const [material, amount, ...rest] = payload.split(":");
    if (rest.length || !TARGET_PATTERN.test(String(material || "").toUpperCase())) {
      throw new AiError(422, "AI 返回的物品奖励格式无效");
    }
    return `item:${String(material).toUpperCase()}:${integer(amount, "物品数量", 1, 4096)}`;
  }
  throw new AiError(422, "AI 草稿只允许 money、exp 和 item 奖励；高权限奖励需由管理员手动添加");
}

function normalizeTask(source, requestedType) {
  const raw = source.task && typeof source.task === "object" ? source.task : source;
  const id = text(raw.id, "任务 ID", 64).toLowerCase();
  if (!TASK_ID_PATTERN.test(id)) {
    throw new AiError(422, "AI 返回的任务 ID 只能包含小写字母、数字、下划线和连字符");
  }
  const type = taskType(requestedType, raw.type);
  const objectives = requiredArray(raw.objectives, "objectives", 5).map((objective) => {
    if (!objective || typeof objective !== "object") throw new AiError(422, "AI 返回了无效任务目标");
    const objectiveType = String(objective.type || "").trim().toUpperCase();
    if (!OBJECTIVE_TYPES.has(objectiveType)) throw new AiError(422, "AI 返回了不支持的任务事件类型");
    return {
      type: objectiveType,
      target: target(objective.target),
      amount: integer(objective.amount, "目标数量", 1, 1_000_000)
    };
  });
  const rewards = raw.rewards == null ? [] : (Array.isArray(raw.rewards) ? raw.rewards : [raw.rewards]);
  if (rewards.length > 8) throw new AiError(422, "AI 返回的奖励不能超过 8 项");
  return {
    id,
    name: text(raw.name, "任务名称", 128),
    description: text(raw.description, "任务描述", 256),
    type,
    experience: integer(raw.experience, "通行证经验", 0, 1_000_000, 0),
    priority: integer(raw.priority, "排序优先级", 0, 100_000, 10),
    objectives,
    rewards: rewards.map(reward)
  };
}

function allActions(document) {
  return [
    ...document.openActions,
    ...document.closeActions,
    ...document.items.flatMap((item) => [...item.leftActions, ...item.rightActions, ...item.allActions])
  ];
}

function normalizeAiMenu(source) {
  const raw = source.document && typeof source.document === "object" ? source.document : source;
  let document;
  try {
    document = normalizeMenu(raw);
  } catch (error) {
    throw new AiError(422, `AI 返回的菜单草稿无效: ${error.message}`);
  }
  if (allActions(document).some((action) => FORBIDDEN_MENU_ACTION.test(action))) {
    throw new AiError(422, "AI 草稿不能包含 console: 或 op: 动作，请由管理员在审阅后手动添加");
  }
  return document;
}

function limitPrompt(prompt, maxLength) {
  const normalized = String(prompt ?? "").trim();
  if (!normalized) throw new AiError(400, "请填写生成需求");
  if (normalized.length > maxLength) throw new AiError(400, `生成需求不能超过 ${maxLength} 字符`);
  return normalized;
}

function messageContent(payload) {
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content === "string" && content.trim()) return content;
  throw new AiError(502, "AI 提供商没有返回可用内容");
}

function taskInstruction(requestedType) {
  return [
    "你是 siyuan Minecraft 插件的任务配置助手。",
    "只返回一个 JSON 对象，不要 Markdown、解释或代码围栏。",
    "JSON 字段必须是 id、name、description、type、experience、priority、objectives、rewards。",
    "type 只能为 DAILY、WEEKLY、SEASONAL、STORY、CHALLENGE。",
    "objectives 是 1 到 5 项，每项包含 type、target、amount；type 只能为 BLOCK_BREAK、ENTITY_KILL、ITEM_CRAFT、ITEM_CONSUME、PLAYER_JUMP、DAMAGE_DEALT、DAMAGE_TAKEN。",
    "target 使用 Paper Material 或实体类型大写名，PLAYER_JUMP 使用 ANY。",
    "rewards 只允许 money:数量、exp:数量、item:MATERIAL:数量；不要生成 command、permission、title 或任何控制台动作。",
    "id 只能用小写字母、数字、下划线、连字符，最长 64 字符。",
    requestedType ? `用户已指定类型，type 必须为 ${requestedType}。` : "根据用户描述选择最合适的 type。"
  ].join(" ");
}

function menuInstruction() {
  return [
    "你是 siyuan Minecraft 菜单草稿助手。",
    "只返回一个 JSON 对象，不要 Markdown、解释或代码围栏。",
    "对象字段为 title、size、permission、openActions、closeActions、items。",
    "size 只能是 9 到 54 的 9 倍数；items 是菜单物品数组，物品字段为 slot、material、amount、name、lore、leftActions、rightActions、allActions、glow、skullOwner。",
    "动作只允许 command:、tell:、message:、chat:、menu:、sound: 或 close。",
    "绝不能生成 console:、op:、权限管理、发放物品、扣发货币或任何服务器控制台命令。",
    "这是未保存草稿，管理员会人工审阅后才可以保存或发布。"
  ].join(" ");
}

export function createAiService(options = {}, fetchImpl = globalThis.fetch) {
  const config = {
    enabled: Boolean(options.enabled),
    baseUrl: options.baseUrl || "https://api.openai.com/v1",
    apiKey: options.apiKey || "",
    model: options.model || "",
    timeoutMs: Number(options.timeoutMs || 20_000),
    maxPromptChars: Number(options.maxPromptChars || 2_000),
    maxResponseChars: Number(options.maxResponseChars || 24_000),
    maxTokens: Number(options.maxTokens || 1_200),
    rateLimitPerMinute: Number(options.rateLimitPerMinute || 6)
  };
  const requests = new Map();

  function status() {
    return {
      enabled: config.enabled,
      model: config.enabled ? config.model : null,
      maxPromptChars: config.maxPromptChars
    };
  }

  function assertEnabled() {
    if (!config.enabled) throw new AiError(503, "AI 功能未启用");
  }

  function consumeRateLimit(key) {
    const now = Date.now();
    const cutoff = now - 60_000;
    const previous = (requests.get(key) || []).filter((at) => at > cutoff);
    if (previous.length >= config.rateLimitPerMinute) {
      throw new AiError(429, "AI 请求过于频繁，请稍后再试");
    }
    previous.push(now);
    requests.set(key, previous);
  }

  async function complete(system, prompt, rateKey) {
    assertEnabled();
    consumeRateLimit(rateKey || "anonymous");
    const endpoint = `${config.baseUrl.replace(/\/+$/, "")}/chat/completions`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.timeoutMs);
    try {
      const response = await fetchImpl(endpoint, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${config.apiKey}`,
          "Content-Type": "application/json",
          "Accept": "application/json"
        },
        body: JSON.stringify({
          model: config.model,
          temperature: 0.2,
          max_tokens: config.maxTokens,
          messages: [
            { role: "system", content: system },
            { role: "user", content: prompt }
          ]
        }),
        signal: controller.signal
      });
      if (!response.ok) {
        throw new AiError(response.status === 429 ? 429 : 502,
          response.status === 429 ? "AI 提供商限流，请稍后再试" : "AI 提供商暂时不可用");
      }
      const content = messageContent(await response.json());
      if (content.length > config.maxResponseChars) throw new AiError(422, "AI 返回内容过长，请缩小需求范围");
      return content;
    } catch (error) {
      if (error instanceof AiError) throw error;
      if (error.name === "AbortError") throw new AiError(504, "AI 请求超时，请稍后再试");
      throw new AiError(502, "AI 提供商连接失败");
    } finally {
      clearTimeout(timeout);
    }
  }

  return {
    status,
    async generateTask({ prompt, taskType: requestedType, rateKey }) {
      const safePrompt = limitPrompt(prompt, config.maxPromptChars);
      const normalizedType = requestedType == null || requestedType === "" ? null : taskType(requestedType);
      const draft = normalizeTask(jsonObject(await complete(taskInstruction(normalizedType), safePrompt, rateKey)), normalizedType);
      return {
        draft,
        yaml: YAML.stringify(draft, { lineWidth: 0 }),
        suggestedPath: `plugins/siyuan/quests/${draft.type.toLowerCase()}/${draft.id}.yml`,
        warnings: draft.rewards.some((entry) => entry.startsWith("money:"))
          ? ["金币奖励仍会受 siyuan 的奖励倍率和每日额度限制。"] : []
      };
    },
    async generateMenu({ prompt, rateKey }) {
      const safePrompt = limitPrompt(prompt, config.maxPromptChars);
      const document = normalizeAiMenu(jsonObject(await complete(menuInstruction(), safePrompt, rateKey)));
      return { document, changeNote: "AI 菜单草稿，待管理员审阅" };
    }
  };
}
