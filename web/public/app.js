const state = {
  csrfToken: sessionStorage.getItem("siyuan.csrfToken") || "",
  username: sessionStorage.getItem("siyuan.username") || "",
  servers: [],
  server: null,
  menus: [],
  menu: null,
  draft: null,
  selectedSlot: null,
  dirty: false,
  dragSlot: null,
  entityMode: null,
  aiEnabled: false,
  aiDraft: null
};

const elements = Object.fromEntries([...document.querySelectorAll("[id]")].map((element) => [element.id, element]));
let toastTimer;

function toast(message, error = false) {
  elements.toast.textContent = message;
  elements.toast.className = `toast visible${error ? " error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { elements.toast.className = "toast"; }, 3500);
}

async function api(path, options = {}) {
  const method = String(options.method || "GET").toUpperCase();
  const headers = { ...options.headers };
  if (!new Set(["GET", "HEAD", "OPTIONS"]).has(method) && state.csrfToken) {
    headers["X-siyuan-CSRF"] = state.csrfToken;
  }
  if (options.body && !(options.body instanceof FormData)) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...options, method, headers, credentials: "same-origin" });
  if (response.status === 204) return null;
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      clearSessionState();
      if (path !== "/api/auth/login") openCredentials();
    }
    const error = new Error(body.error || `请求失败 (${response.status})`);
    error.status = response.status;
    error.details = body.details;
    throw error;
  }
  return body;
}

function clearSessionState() {
  state.csrfToken = "";
  state.username = "";
  sessionStorage.removeItem("siyuan.csrfToken");
  sessionStorage.removeItem("siyuan.username");
}

function openCredentials() {
  elements.usernameInput.value = state.username;
  elements.passwordInput.value = "";
  elements.credentialError.textContent = "";
  if (!elements.credentialDialog.open) elements.credentialDialog.showModal();
}

function setDirty(dirty = true) {
  state.dirty = dirty;
  elements.saveState.textContent = !state.menu ? "未载入菜单" : dirty ? "有未保存修改" : `已保存 v${state.menu.current_version}`;
}

function requireDiscard() {
  return !state.dirty || window.confirm("当前修改尚未保存，确定放弃吗？");
}

async function loadServers(selectId) {
  state.servers = await api("/api/servers");
  renderServers();
  const target = state.servers.find((server) => server.id === selectId) || state.server && state.servers.find((server) => server.id === state.server.id);
  if (target) await selectServer(target);
}

async function loadAiStatus() {
  const status = await api("/api/ai/status");
  state.aiEnabled = Boolean(status.enabled);
  elements.aiButton.classList.toggle("hidden", !state.aiEnabled);
}

function renderServers() {
  elements.serverList.replaceChildren(...state.servers.map((server) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `entity-button${state.server?.id === server.id ? " active" : ""}`;
    const name = document.createElement("span");
    name.textContent = server.display_name;
    const count = document.createElement("span");
    count.className = "entity-count";
    count.textContent = server.menu_count;
    button.append(name, count);
    button.addEventListener("click", () => { if (requireDiscard()) selectServer(server).catch(showError); });
    return button;
  }));
}

async function selectServer(server) {
  state.server = server;
  state.menu = null;
  state.draft = null;
  state.selectedSlot = null;
  state.menus = await api(`/api/servers/${server.id}/menus`);
  renderServers();
  renderMenus();
  elements.editor.classList.add("hidden");
  elements.emptyState.classList.remove("hidden");
  setDirty(false);
}

function renderMenus() {
  elements.menuList.replaceChildren(...state.menus.map((menu) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `entity-button${state.menu?.id === menu.id ? " active" : ""}`;
    const name = document.createElement("span");
    name.textContent = menu.display_name;
    const version = document.createElement("span");
    version.className = "entity-count";
    version.textContent = `v${menu.current_version}`;
    button.append(name, version);
    button.addEventListener("click", () => { if (requireDiscard()) loadMenu(menu.id).catch(showError); });
    return button;
  }));
}

async function loadMenu(menuId, version) {
  const suffix = version ? `?version=${version}` : "";
  state.menu = await api(`/api/menus/${menuId}${suffix}`);
  state.draft = structuredClone(state.menu.document);
  normalizeDraft();
  state.selectedSlot = null;
  elements.emptyState.classList.add("hidden");
  elements.editor.classList.remove("hidden");
  renderMenus();
  renderEditor();
  setDirty(false);
}

function renderEditor() {
  const menu = state.menu;
  const draft = state.draft;
  elements.serverLabel.textContent = `${menu.server_name} / ${menu.menu_key}`;
  elements.menuTitle.value = draft.title;
  elements.rowCount.value = String(draft.size / 9);
  elements.menuPermission.value = draft.permission || "";
  elements.versionSelect.replaceChildren(...menu.versions.map((version) => {
    const option = document.createElement("option");
    option.value = version.version;
    option.textContent = `v${version.version} · ${version.change_note || "无说明"}`;
    option.selected = Number(menu.selectedVersion) === version.version;
    return option;
  }));
  elements.publishedBadge.textContent = menu.published_version ? `线上 v${menu.published_version}` : "未发布";
  renderGrid();
  renderInspector();
}

function findItem(slot) {
  return state.draft.items.find((item) => item.slot === slot);
}

function renderGrid() {
  const rows = state.draft.size / 9;
  elements.menuGrid.style.gridTemplateRows = `repeat(${rows}, minmax(52px, 1fr))`;
  elements.menuGrid.style.aspectRatio = `9 / ${rows}`;
  const slots = [];
  for (let slot = 0; slot < state.draft.size; slot += 1) {
    const item = findItem(slot);
    const button = document.createElement("button");
    button.type = "button";
    button.className = `menu-slot${state.selectedSlot === slot ? " selected" : ""}`;
    button.dataset.slot = String(slot);
    button.draggable = Boolean(item);
    const index = document.createElement("span");
    index.className = "slot-index";
    index.textContent = String(slot);
    button.append(index);
    if (item) {
      const material = document.createElement("span");
      material.className = "slot-material";
      material.textContent = item.material;
      const amount = document.createElement("span");
      amount.className = "slot-amount";
      amount.textContent = item.amount > 1 ? item.amount : "";
      button.append(material, amount);
    }
    button.addEventListener("click", () => selectSlot(slot));
    button.addEventListener("dragstart", () => { state.dragSlot = slot; });
    button.addEventListener("dragover", (event) => { event.preventDefault(); button.classList.add("drag-over"); });
    button.addEventListener("dragleave", () => button.classList.remove("drag-over"));
    button.addEventListener("drop", (event) => {
      event.preventDefault();
      button.classList.remove("drag-over");
      moveItem(state.dragSlot, slot);
    });
    slots.push(button);
  }
  elements.menuGrid.replaceChildren(...slots);
}

function selectSlot(slot) {
  state.selectedSlot = slot;
  if (!findItem(slot)) {
    state.draft.items.push({
      slot, material: "STONE", amount: 1, name: "&f新物品", lore: [], leftActions: [], rightActions: [],
      allActions: [], glow: false, skullOwner: ""
    });
    state.draft.items.sort((a, b) => a.slot - b.slot);
    setDirty();
  }
  renderGrid();
  renderInspector();
}

function moveItem(from, to) {
  if (from == null || from === to) return;
  const first = findItem(from);
  if (!first) return;
  const second = findItem(to);
  first.slot = to;
  if (second) second.slot = from;
  state.selectedSlot = to;
  state.draft.items.sort((a, b) => a.slot - b.slot);
  setDirty();
  renderGrid();
  renderInspector();
}

function renderInspector() {
  const item = state.selectedSlot == null ? null : findItem(state.selectedSlot);
  elements.slotEmpty.classList.toggle("hidden", Boolean(item));
  elements.itemForm.classList.toggle("hidden", !item);
  if (!item) return;
  elements.slotNumber.textContent = state.selectedSlot;
  elements.itemMaterial.value = item.material;
  elements.itemAmount.value = item.amount;
  elements.itemName.value = item.name;
  elements.itemLore.value = item.lore.join("\n");
  elements.leftActions.value = item.leftActions.join("\n");
  elements.rightActions.value = item.rightActions.join("\n");
  elements.allActions.value = (item.allActions || []).join("\n");
  elements.itemGlow.checked = Boolean(item.glow);
  elements.itemSkullOwner.value = item.skullOwner || "";
}

function syncInspector() {
  const item = findItem(state.selectedSlot);
  if (!item) return;
  item.material = (elements.itemMaterial.value.trim() || "STONE").toUpperCase();
  item.amount = Math.max(1, Math.min(64, Number.parseInt(elements.itemAmount.value, 10) || 1));
  item.name = elements.itemName.value;
  item.lore = lines(elements.itemLore.value);
  item.leftActions = lines(elements.leftActions.value);
  item.rightActions = lines(elements.rightActions.value);
  item.allActions = lines(elements.allActions.value);
  item.glow = elements.itemGlow.checked;
  item.skullOwner = elements.itemSkullOwner.value.trim();
  setDirty();
  renderGrid();
}

function lines(value) {
  return value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

function normalizeDraft() {
  state.draft.openActions ||= [];
  state.draft.closeActions ||= [];
  state.draft.items ||= [];
  for (const item of state.draft.items) {
    item.lore ||= [];
    item.leftActions ||= [];
    item.rightActions ||= [];
    item.allActions ||= [];
    item.glow = Boolean(item.glow);
    item.skullOwner ||= "";
  }
}

async function saveMenu() {
  if (!state.menu || !state.dirty) return;
  const result = await api(`/api/menus/${state.menu.id}`, {
    method: "PUT",
    body: JSON.stringify({ baseVersion: state.menu.current_version, document: state.draft, changeNote: "Web 可视化编辑" })
  });
  toast(`已保存版本 v${result.version}`);
  await loadMenu(state.menu.id);
  state.menus = await api(`/api/servers/${state.server.id}/menus`);
  renderMenus();
}

async function publishMenu() {
  if (!state.menu) return;
  if (state.dirty) await saveMenu();
  const result = await api(`/api/menus/${state.menu.id}/publish`, {
    method: "POST",
    body: JSON.stringify({ version: state.menu.selectedVersion })
  });
  toast(`已发布 v${result.publishedVersion}`);
  await loadMenu(state.menu.id);
}

async function exportCurrent(format = "yaml") {
  if (!state.menu) return;
  const response = await fetch(`/api/menus/${state.menu.id}/export?format=${format}&version=${state.menu.selectedVersion}`, {
    credentials: "same-origin"
  });
  if (!response.ok) {
    if (response.status === 401) openCredentials();
    return showError(new Error("导出失败"));
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `${state.menu.menu_key}-v${state.menu.selectedVersion}.${format === "json" ? "json" : "yml"}`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function showEntityDialog(mode) {
  if (mode === "menu" && !state.server) return toast("请先选择服务器", true);
  state.entityMode = mode;
  elements.entityDialogTitle.textContent = mode === "server" ? "添加服务器" : "新建菜单";
  elements.entityKey.value = "";
  elements.entityName.value = "";
  elements.entityDialog.showModal();
}

function showError(error) {
  console.error(error);
  toast(error.message || "操作失败", true);
}

function updateAiDialog() {
  const task = elements.aiKind.value === "task";
  elements.aiTaskTypeField.classList.toggle("hidden", !task);
  elements.aiPrompt.placeholder = task
    ? "例如：做一个每天挖 32 个铁矿石的任务，给 60 通行证经验和 30 金币"
    : "例如：做一个大厅导航菜单，包含出生点、全球商店、传送点和任务入口";
  state.aiDraft = null;
  elements.aiResultPanel.classList.add("hidden");
  elements.aiCopyButton.classList.add("hidden");
  elements.aiApplyButton.classList.add("hidden");
}

function openAiDialog() {
  updateAiDialog();
  elements.aiPrompt.value = "";
  if (!elements.aiDialog.open) elements.aiDialog.showModal();
}

async function generateAiDraft() {
  const task = elements.aiKind.value === "task";
  if (!task && !state.menu) throw new Error("请先选择一个菜单，再生成菜单草稿");
  const result = await api(task ? "/api/ai/task-draft" : "/api/ai/menu-draft", {
    method: "POST",
    body: JSON.stringify({
      prompt: elements.aiPrompt.value,
      taskType: task ? elements.aiTaskType.value : undefined
    })
  });
  state.aiDraft = { kind: task ? "task" : "menu", result };
  elements.aiSuggestedPath.value = task ? result.suggestedPath : `${state.menu.menu_key}（未保存草稿）`;
  elements.aiResult.value = task ? result.yaml : JSON.stringify(result.document, null, 2);
  elements.aiResultNote.textContent = task
    ? (result.warnings || []).join(" ")
    : "载入后仍需手动保存版本并发布。";
  elements.aiResultPanel.classList.remove("hidden");
  elements.aiCopyButton.classList.toggle("hidden", !task);
  elements.aiApplyButton.classList.toggle("hidden", task);
}

async function copyAiYaml() {
  if (state.aiDraft?.kind !== "task") return;
  const value = state.aiDraft.result.yaml;
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    elements.aiResult.focus();
    elements.aiResult.select();
    document.execCommand("copy");
  }
  toast("任务 YAML 已复制");
}

function applyAiMenuDraft() {
  if (state.aiDraft?.kind !== "menu" || !state.menu) return;
  if (!window.confirm("将替换当前未保存的菜单内容，继续吗？")) return;
  state.draft = structuredClone(state.aiDraft.result.document);
  state.selectedSlot = null;
  normalizeDraft();
  renderEditor();
  setDirty();
  elements.aiDialog.close();
  toast("AI 菜单草稿已载入，尚未保存或发布");
}

elements.credentialForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: elements.usernameInput.value, password: elements.passwordInput.value })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "登录失败");
    state.username = result.user.username;
    state.csrfToken = result.csrfToken;
    sessionStorage.setItem("siyuan.username", state.username);
    sessionStorage.setItem("siyuan.csrfToken", state.csrfToken);
    elements.credentialDialog.close();
    await loadServers();
    await loadAiStatus().catch(() => {
      state.aiEnabled = false;
      elements.aiButton.classList.add("hidden");
    });
  } catch (error) {
    elements.credentialError.textContent = error.message;
  }
});

async function logout() {
  if (!requireDiscard()) return;
  try {
    await api("/api/auth/logout", { method: "POST" });
  } finally {
    clearSessionState();
    window.location.reload();
  }
}

elements.entityForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (event.submitter?.value === "cancel") return elements.entityDialog.close();
  try {
    if (state.entityMode === "server") {
      const server = await api("/api/servers", { method: "POST", body: JSON.stringify({ slug: elements.entityKey.value, displayName: elements.entityName.value }) });
      elements.entityDialog.close();
      window.prompt("同步令牌仅显示一次，请配置到对应游戏服：", server.syncToken);
      await loadServers(server.id);
    } else {
      const menu = await api(`/api/servers/${state.server.id}/menus`, { method: "POST", body: JSON.stringify({ menuKey: elements.entityKey.value, displayName: elements.entityName.value }) });
      elements.entityDialog.close();
      state.menus = await api(`/api/servers/${state.server.id}/menus`);
      await loadMenu(menu.id);
    }
  } catch (error) { showError(error); }
});

elements.importForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (event.submitter?.value === "cancel") return elements.importDialog.close();
  try {
    const result = await api("/api/import", { method: "POST", body: JSON.stringify({ source: elements.importSource.value, format: elements.importFormat.value }) });
    state.draft = result.document;
    state.selectedSlot = null;
    setDirty();
    renderEditor();
    elements.importDialog.close();
  } catch (error) { showError(error); }
});

elements.aiForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (event.submitter?.value === "cancel") return elements.aiDialog.close();
  try {
    await generateAiDraft();
  } catch (error) { showError(error); }
});

elements.addServerButton.addEventListener("click", () => showEntityDialog("server"));
elements.addMenuButton.addEventListener("click", () => showEntityDialog("menu"));
elements.aiButton.addEventListener("click", openAiDialog);
elements.logoutButton.addEventListener("click", () => logout().catch(showError));
elements.aiKind.addEventListener("change", updateAiDialog);
elements.aiCopyButton.addEventListener("click", () => copyAiYaml().catch(showError));
elements.aiApplyButton.addEventListener("click", applyAiMenuDraft);
elements.saveButton.addEventListener("click", () => saveMenu().catch(showError));
elements.publishButton.addEventListener("click", () => publishMenu().catch(showError));
elements.importButton.addEventListener("click", () => {
  if (!state.menu) return toast("请先选择菜单", true);
  elements.importSource.value = "";
  elements.importDialog.showModal();
});
elements.exportButton.addEventListener("click", () => exportCurrent("yaml"));
elements.versionSelect.addEventListener("change", () => {
  if (requireDiscard()) loadMenu(state.menu.id, elements.versionSelect.value).catch(showError);
  else elements.versionSelect.value = state.menu.selectedVersion;
});
elements.menuTitle.addEventListener("input", () => { state.draft.title = elements.menuTitle.value; setDirty(); });
elements.menuPermission.addEventListener("input", () => { state.draft.permission = elements.menuPermission.value; setDirty(); });
elements.rowCount.addEventListener("change", () => {
  const size = Number(elements.rowCount.value) * 9;
  if (state.draft.items.some((item) => item.slot >= size) && !window.confirm("缩小菜单会移除超出范围的物品，继续吗？")) {
    elements.rowCount.value = String(state.draft.size / 9);
    return;
  }
  state.draft.size = size;
  state.draft.items = state.draft.items.filter((item) => item.slot < size);
  if (state.selectedSlot >= size) state.selectedSlot = null;
  setDirty();
  renderGrid();
  renderInspector();
});
elements.itemForm.addEventListener("input", syncInspector);
elements.itemForm.addEventListener("submit", (event) => event.preventDefault());
elements.deleteItemButton.addEventListener("click", () => {
  state.draft.items = state.draft.items.filter((item) => item.slot !== state.selectedSlot);
  state.selectedSlot = null;
  setDirty();
  renderGrid();
  renderInspector();
});
window.addEventListener("beforeunload", (event) => { if (state.dirty) event.preventDefault(); });

async function initialize() {
  const response = await fetch("/api/session", { credentials: "same-origin" });
  const session = response.ok ? await response.json() : null;
  if (!session || session.authMethod !== "session" || !state.csrfToken) {
    openCredentials();
    return;
  }
  state.username = session.user?.username || state.username;
  sessionStorage.setItem("siyuan.username", state.username);
  try {
    await loadServers();
    await loadAiStatus();
  } catch (error) {
    state.aiEnabled = false;
    elements.aiButton.classList.add("hidden");
    openCredentials();
    showError(error);
  }
}

initialize();
