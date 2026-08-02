import assert from "node:assert/strict";
import test from "node:test";
import YAML from "yaml";
import { exportMenu, normalizeMenu, parseMenu, validateKey } from "../src/menu-codec.js";

test("normalizes canonical item arrays and menu size", () => {
  const menu = normalizeMenu({ size: 10, items: [{ slot: 3, material: "paper", amount: 99 }] });
  assert.equal(menu.size, 18);
  assert.deepEqual(menu.items[0], {
    slot: 3,
    material: "PAPER",
    amount: 64,
    name: "&f物品",
    lore: [],
    leftActions: [],
    rightActions: [],
    allActions: [],
    glow: false,
    skullOwner: ""
  });
});

test("imports official DeluxeMenus items mapping and actions", () => {
  const menu = parseMenu(`
menu_title: '&6Test'
menu_size: 27
items:
  spawn:
    slot: 13
    material: NETHER_STAR
    display_name: '&eSpawn'
    left_click_commands:
      - '[player] spawn'
      - '[close]'
`);
  assert.equal(menu.items[0].slot, 13);
  assert.deepEqual(menu.items[0].leftActions, ["command:spawn", "close"]);
});

test("normalizes newer GFMenu and DeluxeMenus action aliases", () => {
  const menu = normalizeMenu({
    size: 9,
    items: [{
      slot: 0,
      leftActions: [
        "[player] msg: &aHello",
        "[console] give %player% diamond 1",
        "[sound] ENTITY_PLAYER_LEVELUP-1-1",
        "打开菜单: rewards"
      ]
    }]
  });
  assert.deepEqual(menu.items[0].leftActions, [
    "tell:&aHello",
    "console:give %player% diamond 1",
    "sound:ENTITY_PLAYER_LEVELUP-1-1",
    "menu:rewards"
  ]);
});

test("preserves common-click, close, glow, and skull fields across export", () => {
  const source = {
    size: 9,
    close_commands: ["[message] &7Closed"],
    items: {
      head: {
        slot: 4,
        material: "PLAYER_HEAD",
        glow: true,
        skull_owner: "Notch",
        click_commands: ["[sound] ENTITY_PLAYER_LEVELUP-1-1"]
      }
    }
  };
  const normalized = normalizeMenu(source);
  assert.deepEqual(normalized.closeActions, ["message:&7Closed"]);
  assert.deepEqual(normalized.items[0].allActions, ["sound:ENTITY_PLAYER_LEVELUP-1-1"]);
  assert.equal(normalized.items[0].glow, true);
  assert.equal(normalized.items[0].skullOwner, "Notch");
  const exported = YAML.parse(exportMenu(normalized));
  assert.deepEqual(exported.close_commands, ["[message] &7Closed"]);
  assert.equal(exported.items.item_4.glow, true);
  assert.equal(exported.items.item_4.skull_owner, "Notch");
  assert.deepEqual(exported.items.item_4.click_commands, ["[sound] ENTITY_PLAYER_LEVELUP-1-1"]);
});

test("imports legacy top-level slot sections", () => {
  const menu = normalizeMenu({ menu_size: 9, legacy: { slot: 0, material: "BOOK" } });
  assert.equal(menu.items[0].material, "BOOK");
});

test("exports a DeluxeMenus items mapping", () => {
  const source = { title: "&6Test", size: 9, items: [{ slot: 4, material: "CHEST", amount: 1, name: "Shop", leftActions: ["command:gc shop"] }] };
  const parsed = YAML.parse(exportMenu(source, "yaml"));
  assert.equal(parsed.items.item_4.slot, 4);
  assert.deepEqual(parsed.items.item_4.left_click_commands, ["[player] gc shop"]);
});

test("validates stable deployment keys", () => {
  assert.equal(validateKey("Lobby-1"), "lobby-1");
  assert.throws(() => validateKey("invalid key"));
});
