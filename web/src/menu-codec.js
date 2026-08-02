import YAML from "yaml";

const KEY_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;

function stringList(value) {
  if (value == null) return [];
  return (Array.isArray(value) ? value : [value]).map(String).map((entry) => entry.trim()).filter(Boolean);
}

function integer(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function normalizeAction(value) {
  let action = String(value || "").trim();
  const lower = action.toLowerCase();
  if (!action) return "";
  if (lower.startsWith("[player]")) {
    const command = action.slice(8).trim();
    const commandLower = command.toLowerCase();
    if (commandLower.startsWith("msg:")) return `tell:${command.slice(4).trim()}`;
    if (commandLower.startsWith("tell:")) return `tell:${command.slice(5).trim()}`;
    if (commandLower.startsWith("cmd:")) return `command:${command.slice(4).trim()}`;
    return `command:${command}`;
  }
  if (lower.startsWith("[console]")) return `console:${action.slice(9).trim()}`;
  if (lower.startsWith("[message]")) return `message:${action.slice(9).trim()}`;
  if (lower.startsWith("[sound]")) return `sound:${action.slice(7).trim()}`;
  if (lower.startsWith("[open]")) return `menu:${action.slice(6).trim()}`;
  if (lower === "[close]" || action === "关闭") return "close";

  const aliases = [
    ["控制台命令:", "console:"], ["控制台:", "console:"], ["玩家命令:", "command:"],
    ["命令:", "command:"], ["消息:", "message:"], ["提示:", "message:"],
    ["聊天:", "chat:"], ["打开菜单:", "menu:"], ["菜单:", "menu:"], ["声音:", "sound:"]
  ];
  for (const [prefix, replacement] of aliases) {
    if (action.startsWith(prefix)) {
      action = `${replacement}${action.slice(prefix.length).trim()}`;
      break;
    }
  }
  const separator = action.indexOf(":");
  if (separator < 0) return action.toLowerCase() === "close" ? "close" : action;
  return `${action.slice(0, separator).trim().toLowerCase()}:${action.slice(separator + 1).trim()}`;
}

function actionList(value) {
  return stringList(value).map(normalizeAction).filter(Boolean);
}

function normalizeItem(source = {}, fallbackSlot = -1) {
  return {
    slot: integer(source.slot, fallbackSlot),
    material: String(source.material || "STONE").toUpperCase(),
    amount: Math.max(1, Math.min(64, integer(source.amount, 1))),
    name: String(source.name ?? source.display_name ?? "&f物品"),
    lore: stringList(source.lore),
    leftActions: actionList(source.leftActions ?? source.left_click_commands),
    rightActions: actionList(source.rightActions ?? source.right_click_commands),
    allActions: actionList(source.allActions ?? source.click_commands),
    glow: Boolean(source.glow),
    skullOwner: String(source.skullOwner ?? source.skull_owner ?? "").trim().slice(0, 64)
  };
}

export function normalizeMenu(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new Error("菜单文档必须是对象");
  const sizeInput = integer(input.size ?? input.menu_size, 54);
  const size = Math.max(9, Math.min(54, Math.ceil(sizeInput / 9) * 9));
  let rawItems;
  if (Array.isArray(input.items)) {
    rawItems = input.items;
  } else if (input.items && typeof input.items === "object") {
    rawItems = Object.entries(input.items).map(([key, value]) => ({ key, ...value }));
  } else {
    rawItems = Object.entries(input)
      .filter(([, value]) => value && typeof value === "object" && "slot" in value)
      .map(([key, value]) => ({ key, ...value }));
  }
  const occupied = new Set();
  const items = [];
  for (const raw of rawItems) {
    const item = normalizeItem(raw);
    if (item.slot < 0 || item.slot >= size || occupied.has(item.slot)) continue;
    occupied.add(item.slot);
    items.push(item);
  }
  items.sort((a, b) => a.slot - b.slot);
  return {
    title: String(input.title ?? input.menu_title ?? "&6思渊菜单").slice(0, 128),
    size,
    permission: String(input.permission ?? input.open_permission ?? "").slice(0, 128),
    openActions: actionList(input.openActions ?? input.open_commands),
    closeActions: actionList(input.closeActions ?? input.close_commands),
    items
  };
}

export function parseMenu(source, format = "yaml") {
  if (typeof source !== "string" || source.length > 1_000_000) throw new Error("导入内容无效或超过 1MB");
  const parsed = format.toLowerCase() === "json" ? JSON.parse(source) : YAML.parse(source);
  return normalizeMenu(parsed);
}

function exportActions(actions) {
  return actions.map((action) => {
    if (action.startsWith("command:")) return `[player] ${action.slice(8).trim()}`;
    if (action.startsWith("op:") || action.startsWith("console:")) return `[console] ${action.split(":").slice(1).join(":").trim()}`;
    if (action.startsWith("tell:") || action.startsWith("message:") || action.startsWith("msg:")) return `[message] ${action.split(":").slice(1).join(":").trim()}`;
    if (action === "close") return "[close]";
    if (action.startsWith("menu:")) return `[open] ${action.slice(5).trim()}`;
    if (action.startsWith("sound:")) return `[sound] ${action.slice(6).trim()}`;
    return action;
  });
}

export function exportMenu(document, format = "yaml") {
  const menu = normalizeMenu(document);
  if (format.toLowerCase() === "json") return JSON.stringify(menu, null, 2);
  const output = {
    menu_title: menu.title,
    size: menu.size,
    open_requires_permission: Boolean(menu.permission),
    items: {}
  };
  if (menu.permission) output.open_permission = menu.permission;
  if (menu.openActions.length) output.open_commands = menu.openActions;
  if (menu.closeActions.length) output.close_commands = exportActions(menu.closeActions);
  for (const item of menu.items) {
    output.items[`item_${item.slot}`] = {
      slot: item.slot,
      material: item.material,
      amount: item.amount,
      display_name: item.name,
      lore: item.lore,
      left_click_commands: exportActions(item.leftActions),
      right_click_commands: exportActions(item.rightActions),
      click_commands: exportActions(item.allActions)
    };
    if (item.glow) output.items[`item_${item.slot}`].glow = true;
    if (item.skullOwner) output.items[`item_${item.slot}`].skull_owner = item.skullOwner;
  }
  return YAML.stringify(output, { lineWidth: 0 });
}

export function validateKey(value, label = "key") {
  const normalized = String(value || "").trim().toLowerCase();
  if (!KEY_PATTERN.test(normalized)) {
    const error = new Error(`${label} 只能包含小写字母、数字、下划线和连字符，最长 64 字符`);
    error.status = 400;
    throw error;
  }
  return normalized;
}
