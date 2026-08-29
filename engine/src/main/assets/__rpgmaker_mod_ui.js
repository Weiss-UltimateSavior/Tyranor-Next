(function () {
  "use strict";
  if (window.TyranorModUI || !window.TyranorMod) return;
  var mod = window.TyranorMod;
  var root = null;
  var launcher = null;
  var dragState = null; // { startX, startY, startLeft, startTop, moved }
  var justDragged = false; // pointerup 设 true，click 消费后复位
  var userDragged = false; // 用户手动拖拽过，positionLauncher 不再覆盖
  var LAUNCHER_POS_KEY = "tyranor_mod_fab_pos";
  var activeTab = "quick";
  var context = { actorId: 0, itemKind: "item", systemKind: "switch" };
  var tabs = [
    ["quick", "快捷"], ["items", "物品"], ["actors", "角色"], ["system", "系统"],
    ["map", "地图"], ["battle", "战斗"], ["save", "存档"], ["keys", "键盘"], ["about", "说明"]
  ];
  var flags = [
    ["godMode", "无敌"], ["oneHit", "一击必杀"], ["alwaysCrit", "必定暴击"],
    ["noclip", "穿墙"], ["eventSpeed", "事件加速"], ["msgSkip", "对话快进"]
  ];
  function escapeHtml(value) { return String(value == null ? "" : value).replace(/[&<>'"]/g, function (c) { return ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"})[c]; }); }
  function inputValue(id) { var node = root && root.querySelector("#" + id); return node ? node.value : ""; }
  function checked(id) { var node = root && root.querySelector("#" + id); return !!(node && node.checked); }
  function toast(message, error) {
    if (!root) return;
    var old = root.querySelector(".tm-toast"); if (old) old.remove();
    var node = document.createElement("div"); node.className = "tm-toast"; node.textContent = message || (error ? "操作失败" : "完成");
    if (error) node.style.background = "#8c1d18";
    root.appendChild(node); setTimeout(function () { if (node.parentNode) node.remove(); }, 2200);
  }
  function run(operation, success) {
    try {
      return Promise.resolve(operation()).then(function (value) { if (success) success(value); return value; }).catch(function (error) { console.error(error); toast(error.message || String(error), true); });
    } catch (error) { console.error(error); toast(error.message || String(error), true); return Promise.resolve(); }
  }
  function card(title, body) { return '<section class="tm-card"><h3>' + escapeHtml(title) + '</h3>' + body + '</section>'; }
  function row(label, control) { return '<div class="tm-row"><label>' + escapeHtml(label) + '</label>' + control + '</div>'; }
  function button(text, action, extra, data) { return '<button class="tm-btn ' + (extra || "") + '" data-action="' + action + '" ' + (data || "") + '>' + escapeHtml(text) + '</button>'; }
  function textInput(id, value, type, attrs) { return '<input class="tm-input" id="' + id + '" type="' + (type || "text") + '" value="' + escapeHtml(value == null ? "" : value) + '" ' + (attrs || "") + '>'; }
  function render() {
    if (!root) return;
    var content = root.querySelector(".tm-content");
    root.querySelectorAll(".tm-tab").forEach(function (node) { node.classList.toggle("active", node.dataset.tab === activeTab); });
    if (!mod.isReady()) { content.innerHTML = '<div class="tm-note">请先进入游戏存档或开始新游戏，修改器正在等待 RPG Maker 运行时就绪。</div>'; return; }
    if (activeTab === "quick") renderQuick(content);
    else if (activeTab === "items") renderItems(content);
    else if (activeTab === "actors") renderActors(content);
    else if (activeTab === "system") renderSystem(content);
    else if (activeTab === "map") renderMap(content);
    else if (activeTab === "battle") renderBattle(content);
    else if (activeTab === "save") renderSave(content);
    else if (activeTab === "keys") renderKeys(content);
    else renderAbout(content);
  }
  function renderQuick(content) {
    var state = mod.getState();
    var toggles = flags.map(function (entry) {
      return row(entry[1], '<input class="tm-switch" type="checkbox" data-action="flag" data-flag="' + entry[0] + '" ' + (state[entry[0]] ? "checked" : "") + '>');
    }).join("");
    var gold = row("当前金币", '<span class="tm-badge">' + mod.getGold() + '</span>') + '<div class="tm-row">' + textInput("tm-gold", mod.getGold(), "number", 'min="0"') + button("设置", "set-gold", "primary") + button("拉满", "max-gold") + '</div>';
    var party = '<div class="tm-row">' + button("全体恢复", "recover-party", "primary") + button("刷新页面", "render") + '</div>';
    content.innerHTML = '<div class="tm-grid">' + card("运行时开关", toggles) + card("金币", gold) + card("队伍", party) + '</div>';
  }
  function renderItems(content) {
    var kinds = '<select class="tm-select" id="tm-item-kind" data-action="item-kind"><option value="item">物品</option><option value="weapon">武器</option><option value="armor">护甲</option></select>';
    var tools = '<div class="tm-row">' + kinds + textInput("tm-item-query", "", "search", 'placeholder="搜索名称"') + button("搜索", "render-items", "primary") + '</div>';
    content.innerHTML = card("物品数据库", tools + '<div id="tm-item-list" class="tm-list"><div class="tm-note">加载中…</div></div>');
    root.querySelector("#tm-item-kind").value = context.itemKind;
    loadItems();
  }
  function loadItems() {
    context.itemKind = inputValue("tm-item-kind") || context.itemKind;
    var result = mod.listDatabase(context.itemKind, inputValue("tm-item-query"), 0, 200);
    var list = root.querySelector("#tm-item-list");
    list.innerHTML = result.values.length ? result.values.map(function (item) {
      return '<div class="tm-item"><span class="tm-name" title="' + escapeHtml(item.description) + '">#' + item.id + ' ' + escapeHtml(item.name) + '</span>' +
        textInput("tm-count-" + item.id, item.count, "number", 'min="0" style="width:72px"') + button("应用", "set-item", "", 'data-id="' + item.id + '"') + '</div>';
    }).join("") : '<div class="tm-note">没有匹配项目</div>';
  }
  function actorSelect(actors) {
    return '<select class="tm-select" id="tm-actor" data-action="actor-select">' + actors.map(function (actor) { return '<option value="' + actor.id + '">' + escapeHtml(actor.name) + '</option>'; }).join("") + '</select>';
  }
  function renderActors(content) {
    var actors = mod.listActors();
    if (!actors.length) { content.innerHTML = '<div class="tm-note">当前队伍没有角色</div>'; return; }
    if (!context.actorId || !actors.some(function (a) { return a.id === context.actorId; })) context.actorId = actors[0].id;
    content.innerHTML = '<div class="tm-row">' + actorSelect(actors) + button("恢复", "recover-actor", "primary") + '</div><div id="tm-actor-body"></div>';
    root.querySelector("#tm-actor").value = String(context.actorId); renderActorBody();
  }
  function renderActorBody() {
    var actor = mod.listActors().filter(function (a) { return a.id === context.actorId; })[0];
    if (!actor) return;
    var stats = ["level","hp","mp","mhp","mmp","atk","def","mat","mdf","agi","luk"].map(function (key) {
      return '<div class="tm-item"><span class="tm-name">' + key.toUpperCase() + '</span>' + textInput("tm-stat-" + key, actor[key], "number", 'style="width:94px"') + button("设置", "set-stat", "", 'data-key="' + key + '"') + '</div>';
    }).join("");
    var skillIds = mod.actorSkills(actor.id).map(function (s) { return '#' + s.id + ' ' + escapeHtml(s.name); }).join('、') || '无';
    var stateIds = mod.actorStates(actor.id).map(function (s) { return '#' + s.id + ' ' + escapeHtml(s.name); }).join('、') || '无';
    var equips = mod.actorEquips(actor.id).map(function (e) { return '槽位' + e.slot + '：' + escapeHtml(e.name); }).join('<br>') || '无';
    var skillControls = '<div class="tm-row">' + textInput("tm-skill-id", "", "number", 'placeholder="技能 ID"') + button("学习", "learn-skill", "primary") + button("遗忘", "forget-skill") + '</div><small>' + skillIds + '</small>';
    var stateControls = '<div class="tm-row">' + textInput("tm-state-id", "", "number", 'placeholder="状态 ID"') + button("附加", "add-state", "primary") + button("移除", "remove-state") + '</div><small>' + stateIds + '</small>';
    var equipControls = '<div class="tm-row">' + textInput("tm-equip-slot", "0", "number", 'placeholder="槽位"') + '<select id="tm-equip-kind" class="tm-select"><option value="weapon">武器</option><option value="armor">护甲</option></select>' + textInput("tm-equip-id", "0", "number", 'placeholder="装备 ID，0=卸下"') + button("更换", "change-equip", "primary") + '</div><small>' + equips + '</small>';
    root.querySelector("#tm-actor-body").innerHTML = '<div class="tm-grid">' + card("属性", '<div class="tm-list">' + stats + '</div>') + card("技能", skillControls) + card("状态", stateControls) + card("装备", equipControls) + '</div>';
  }
  function renderSystem(content) {
    var kind = '<select class="tm-select" id="tm-system-kind" data-action="system-kind"><option value="switch">开关</option><option value="variable">变量</option></select>';
    content.innerHTML = card("开关与变量", '<div class="tm-row">' + kind + textInput("tm-system-query", "", "search", 'placeholder="名称或 ID"') + button("搜索", "render-system", "primary") + '</div><div id="tm-system-list" class="tm-list"></div>');
    root.querySelector("#tm-system-kind").value = context.systemKind; loadSystem();
  }
  function loadSystem() {
    context.systemKind = inputValue("tm-system-kind") || context.systemKind;
    var result = context.systemKind === "switch" ? mod.listSwitches(inputValue("tm-system-query"), 0, 200) : mod.listVariables(inputValue("tm-system-query"), 0, 200);
    root.querySelector("#tm-system-list").innerHTML = result.values.map(function (item) {
      var control = context.systemKind === "switch" ? '<input class="tm-switch" id="tm-system-' + item.id + '" type="checkbox" ' + (item.value ? "checked" : "") + '>' : textInput("tm-system-" + item.id, typeof item.value === "object" ? JSON.stringify(item.value) : item.value, "text");
      return '<div class="tm-item"><span class="tm-name">#' + item.id + ' ' + escapeHtml(item.name) + '</span>' + control + button("设置", "set-system", "", 'data-id="' + item.id + '"') + '</div>';
    }).join("") || '<div class="tm-note">没有匹配项目</div>';
  }
  function renderMap(content) {
    var pos = mod.currentPosition();
    var current = pos ? '地图 #' + pos.mapId + '，坐标 (' + pos.x + ', ' + pos.y + ')' : '当前不在地图';
    var form = row("当前位置", '<span class="tm-badge">' + current + '</span>') + '<div class="tm-row">' + textInput("tm-map-id", pos && pos.mapId, "number", 'placeholder="地图 ID"') + textInput("tm-map-x", pos && pos.x, "number", 'placeholder="X"') + textInput("tm-map-y", pos && pos.y, "number", 'placeholder="Y"') + button("传送", "teleport", "primary") + '</div>';
    var maps = mod.listMaps("", 0, 200).values.map(function (map) { return '<div class="tm-item"><span class="tm-name">#' + map.id + ' ' + escapeHtml(map.name) + '</span>' + button("选择", "pick-map", "", 'data-id="' + map.id + '"') + '</div>'; }).join("");
    content.innerHTML = '<div class="tm-grid">' + card("传送", form) + card("地图列表", '<div class="tm-list">' + maps + '</div>') + '</div>';
  }
  function renderBattle(content) {
    var snapshot = mod.battleSnapshot();
    if (!snapshot.inBattle) { content.innerHTML = '<div class="tm-note">当前不在战斗中</div>'; return; }
    var enemies = snapshot.enemies.map(function (enemy) { return '<div class="tm-item"><span class="tm-name">' + escapeHtml(enemy.name) + ' HP ' + enemy.hp + '/' + enemy.mhp + '</span>' + textInput("tm-enemy-" + enemy.index, enemy.hp, "number", 'style="width:86px"') + button("设置", "set-enemy", "", 'data-index="' + enemy.index + '"') + '</div>'; }).join("");
    var actors = snapshot.actors.map(function (actor) { return '<div class="tm-item"><span class="tm-name">' + escapeHtml(actor.name) + '</span><span class="tm-badge">HP ' + actor.hp + '/' + actor.mhp + '</span></div>'; }).join("");
    var actions = '<div class="tm-row">' + button("消灭全部敌人", "kill-enemies", "danger") + button("强制胜利", "victory", "primary") + button("强制逃跑", "escape") + '</div>';
    content.innerHTML = '<div class="tm-grid">' + card("战斗操作", actions) + card("敌方", '<div class="tm-list">' + enemies + '</div>') + card("我方", '<div class="tm-list">' + actors + '</div>') + '</div>';
  }
  function renderSave(content) {
    content.innerHTML = card("存档管理", '<div id="tm-save-list" class="tm-list"><div class="tm-note">读取中…</div></div>');
    run(function () { return mod.listSaveSlots(); }, function (slots) {
      root.querySelector("#tm-save-list").innerHTML = slots.map(function (slot) {
        return '<div class="tm-item"><span class="tm-name">存档 ' + slot.id + (slot.exists ? ' · ' + escapeHtml(slot.playtime || slot.title || '已有数据') : ' · 空') + '</span>' + button("保存", "save", "primary", 'data-id="' + slot.id + '"') + (slot.exists ? button("读取", "load", "", 'data-id="' + slot.id + '"') + button("删除", "delete-save", "danger", 'data-id="' + slot.id + '"') : '') + '</div>';
      }).join("");
    });
  }
  function renderAbout(content) {
    content.innerHTML = card("Tyranor 修改器", '<p>版本 ' + escapeHtml(mod.version) + '</p><p>修改器只操作当前 RPG Maker MV/MZ 游戏的运行时对象。部分深度定制插件可能覆盖标准引擎 API；遇到异常时可在单游戏设置中关闭修改器。</p><p>事件加速和对话快进可能跳过演出或等待，建议先保存游戏。</p>');
  }
  function renderKeys(content) {
    var hasPad = !!(window.__touchPad);
    content.innerHTML = card("触屏手柄", hasPad
      ? '<p>自定义屏幕上虚拟键的位置、大小与显隐。进入映射后：</p><ul style="margin:6px 0 10px;padding-left:18px;font-size:12px"><li>拖拽按钮调整位置</li><li>缩放进度条调整大小（1%-200%）</li><li>“透明”隐藏按钮</li><li>十字键微调位置</li></ul><div class="tm-row">' + button("进入键盘映射", "enter-keys", "primary") + button("恢复默认", "reset-keys") + '</div>'
      : '<p>当前未加载触屏手柄。</p>');
    if (!hasPad) return;
    var names;
    try { names = window.__touchPad.listPresets(); } catch (e) { names = []; }
    if (names && names.length) {
      var rows = names.map(function (name) {
        // escapeHtml 已覆盖引号转义，data-name 属性可直接使用
        var esc = escapeHtml(name);
        return '<div class="tm-row" style="justify-content:space-between">' +
          '<label style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin:0">' + escapeHtml(name) + '</label>' +
          '<span style="display:flex;gap:6px">' +
          button("预览", "preview-preset", "", 'data-name="' + esc + '"') +
          button("载入", "load-preset", "", 'data-name="' + esc + '"') +
          '</span></div>';
      }).join("");
      content.innerHTML += card("预设（" + names.length + "/10）", rows);
    } else {
      content.innerHTML += card("预设", '<p>暂无预设。在键盘映射面板中可保存当前布局为预设。</p>');
    }
  }
  function handleAction(action, node) {
    if (action === "close") return close();
    if (action === "render") return render();
    if (action === "flag") return run(function () { mod.setFlag(node.dataset.flag, node.checked); }, function () { toast("设置已保存"); });
    if (action === "set-gold") return run(function () { mod.setGold(inputValue("tm-gold")); }, function () { toast("金币已修改"); render(); });
    if (action === "max-gold") return run(function () { mod.setGold(99999999); }, function () { toast("金币已拉满"); render(); });
    if (action === "recover-party") return run(mod.recoverParty, function () { toast("队伍已恢复"); render(); });
    if (action === "item-kind" || action === "render-items") return loadItems();
    if (action === "set-item") return run(function () { mod.setItemCount(context.itemKind, node.dataset.id, inputValue("tm-count-" + node.dataset.id)); }, function () { toast("数量已修改"); loadItems(); });
    if (action === "actor-select") { context.actorId = Number(node.value); return renderActorBody(); }
    if (action === "recover-actor") return run(function () { mod.recoverActor(context.actorId); }, function () { toast("角色已恢复"); renderActorBody(); });
    if (action === "set-stat") return run(function () { mod.setActorStat(context.actorId, node.dataset.key, inputValue("tm-stat-" + node.dataset.key)); }, function () { toast("属性已修改"); renderActorBody(); });
    if (action === "learn-skill" || action === "forget-skill") return run(function () { return action === "learn-skill" ? mod.learnSkill(context.actorId, inputValue("tm-skill-id")) : mod.forgetSkill(context.actorId, inputValue("tm-skill-id")); }, renderActorBody);
    if (action === "add-state" || action === "remove-state") return run(function () { return action === "add-state" ? mod.addState(context.actorId, inputValue("tm-state-id")) : mod.removeState(context.actorId, inputValue("tm-state-id")); }, renderActorBody);
    if (action === "change-equip") return run(function () { mod.changeEquip(context.actorId, inputValue("tm-equip-slot"), inputValue("tm-equip-kind"), inputValue("tm-equip-id")); }, function () { toast("装备已更换"); renderActorBody(); });
    if (action === "system-kind" || action === "render-system") return loadSystem();
    if (action === "set-system") return run(function () { var id = node.dataset.id; if (context.systemKind === "switch") mod.setSwitch(id, checked("tm-system-" + id)); else { var raw = inputValue("tm-system-" + id); var value; try { value = JSON.parse(raw); } catch (_) { value = raw; } mod.setVariable(id, value); } }, function () { toast("系统值已修改"); loadSystem(); });
    if (action === "pick-map") { root.querySelector("#tm-map-id").value = node.dataset.id; return; }
    if (action === "teleport") return run(function () { return mod.teleport(inputValue("tm-map-id"), inputValue("tm-map-x"), inputValue("tm-map-y"), 2, 0); }, function () { toast("已预约传送"); close(); });
    if (action === "set-enemy") return run(function () { mod.setEnemyHp(node.dataset.index, inputValue("tm-enemy-" + node.dataset.index)); }, render);
    if (action === "kill-enemies") return run(mod.killAllEnemies, render);
    if (action === "victory") return run(mod.forceVictory, close);
    if (action === "escape") return run(mod.forceEscape, close);
    if (action === "save") return run(function () { return mod.saveGame(node.dataset.id); }, function () { toast("保存完成"); renderSave(root.querySelector(".tm-content")); });
    if (action === "load") return run(function () { return mod.loadGame(node.dataset.id); }, function () { toast("读取完成"); close(); });
    if (action === "delete-save") return run(function () { return mod.deleteSave(node.dataset.id); }, function () { toast("存档已删除"); renderSave(root.querySelector(".tm-content")); });
    if (action === "enter-keys") {
      if (!window.__touchPad) { toast("未加载触屏手柄", true); return; }
      close();
      setTimeout(function () { try { window.__touchPad.enterEdit(); } catch (e) { toast(String(e && e.message || e), true); } }, 50);
      return;
    }
    if (action === "reset-keys") {
      if (!window.__touchPad) { toast("未加载触屏手柄", true); return; }
      try {
        window.__touchPad.resetToDefaults();
        toast("已恢复默认");
      } catch (e) { toast(String(e && e.message || e), true); }
      return;
    }
    if (action === "preview-preset") {
      if (!window.__touchPad) { toast("未加载触屏手柄", true); return; }
      var pname = node.dataset.name;
      close();
      setTimeout(function () {
        try {
          var res = window.__touchPad.previewPreset(pname);
          if (res && res.ok === false) toast(res.msg, true);
        } catch (e) { toast(String(e && e.message || e), true); }
      }, 50);
      return;
    }
    if (action === "load-preset") {
      if (!window.__touchPad) { toast("未加载触屏手柄", true); return; }
      var lname = node.dataset.name;
      try {
        var lres = window.__touchPad.loadPreset(lname);
        if (lres && lres.ok) toast(lres.msg);
        else toast(lres && lres.msg || "载入失败", true);
        renderKeys(root.querySelector(".tm-content"));
      } catch (e) { toast(String(e && e.message || e), true); }
      return;
    }
  }
  function block(event) {
    if (!root) return;
    if (event.target && (event.target.closest && event.target.closest("#tyranor-mod-root"))) event.stopPropagation();
  }
  function open() {
    if (root) return;
    root = document.createElement("div"); root.id = "tyranor-mod-root";
    root.innerHTML = '<div class="tm-panel" role="dialog" aria-modal="true"><div class="tm-head"><strong>RPG Maker 修改器</strong><span class="tm-badge">MV / MZ</span><button class="tm-close" data-action="close" aria-label="关闭">×</button></div><div class="tm-tabs">' + tabs.map(function (tab) { return '<button class="tm-tab" data-tab="' + tab[0] + '">' + tab[1] + '</button>'; }).join("") + '</div><main class="tm-content"></main></div>';
    document.documentElement.appendChild(root);
    root.addEventListener("click", function (event) {
      event.stopPropagation();
      var tab = event.target.closest("[data-tab]"); if (tab) { activeTab = tab.dataset.tab; render(); return; }
      var action = event.target.closest("[data-action]"); if (action) handleAction(action.dataset.action, action);
    });
    root.addEventListener("change", function (event) { var action = event.target.closest("[data-action]"); if (action) handleAction(action.dataset.action, action); event.stopPropagation(); });
    ["touchstart","touchmove","touchend","pointerdown","pointerup","mousedown","mouseup","keydown","keyup","wheel"].forEach(function (name) { root.addEventListener(name, block); });
    render();
  }
  function close() { if (!root) return false; root.remove(); root = null; return true; }
  function toggle() { if (root) close(); else open(); }
  function isOpen() { return !!root; }
  function installLauncher() {
    if (launcher || !document.body) return;
    launcher = document.createElement("button"); launcher.id = "tyranor-mod-launcher"; launcher.innerHTML = '<span class="tm-launcher-icon" aria-hidden="true"></span>'; launcher.setAttribute("aria-label", "打开 RPG Maker 修改器");
    // 点击：拖拽后不触发（pointerup → click 时序问题）
    launcher.addEventListener("click", function (event) {
      event.preventDefault(); event.stopPropagation();
      if (justDragged) { justDragged = false; return; }
      toggle();
    });
    // 阻止 touch/pointer 冒泡到 WebView
    ["touchstart","touchend","pointerdown","pointerup"].forEach(function (name) { launcher.addEventListener(name, function (event) { event.stopPropagation(); }); });
    // 拖拽支持：pointerdown 开始追踪，移动超过 8px 视为拖拽
    launcher.addEventListener("pointerdown", function (event) {
      event.preventDefault();
      var rect = launcher.getBoundingClientRect();
      dragState = { startX: event.clientX, startY: event.clientY, startLeft: rect.left, startTop: rect.top, moved: false };
      launcher.setPointerCapture(event.pointerId);
    });
    launcher.addEventListener("pointermove", function (event) {
      if (!dragState) return;
      var dx = event.clientX - dragState.startX;
      var dy = event.clientY - dragState.startY;
      if (!dragState.moved && Math.abs(dx) + Math.abs(dy) < 8) return;
      dragState.moved = true;
      launcher.style.left = Math.max(0, Math.min(window.innerWidth - launcher.offsetWidth, dragState.startLeft + dx)) + "px";
      launcher.style.top = Math.max(0, Math.min(window.innerHeight - launcher.offsetHeight, dragState.startTop + dy)) + "px";
      launcher.style.right = "auto";
    });
    var endDrag = function (event) {
      if (dragState && dragState.moved) {
        justDragged = true;
        userDragged = true;
        setTimeout(function () { justDragged = false; }, 100);
        launcher.style.left = launcher.offsetLeft + "px";
        launcher.style.top = launcher.offsetTop + "px";
        try { localStorage.setItem(LAUNCHER_POS_KEY, launcher.offsetLeft + "," + launcher.offsetTop); } catch (e) {}
      }
      dragState = null;
    };
    launcher.addEventListener("pointerup", endDrag);
    launcher.addEventListener("pointercancel", function () { dragState = null; });
    document.body.appendChild(launcher);
    // 恢复上次拖拽位置
    try {
      var saved = localStorage.getItem(LAUNCHER_POS_KEY);
      if (saved) {
        var parts = saved.split(",");
        var sx = parseInt(parts[0], 10), sy = parseInt(parts[1], 10);
        if (sx >= 0 && sy >= 0 && sx < window.innerWidth && sy < window.innerHeight) {
          launcher.style.left = sx + "px";
          launcher.style.top = sy + "px";
          launcher.style.right = "auto";
          userDragged = true;
        }
      }
    } catch (e) {}
    positionLauncher();
    // 触屏手柄（__touch_pad.js）重排后避让其动作键列；无手柄时回退右侧
    window.addEventListener("tyranorpadlayout", positionLauncher);
    window.addEventListener("resize", positionLauncher);
  }
  function positionLauncher() {
    if (!launcher || userDragged) return;
    var pad = window.__touchPadMetrics;
    var w = launcher.offsetWidth || 46;
    if (pad && pad.actionLeft > 0 && window.innerWidth > pad.actionLeft + w + 20) {
      launcher.style.right = "auto";
      launcher.style.left = Math.max(8, Math.round(pad.actionLeft - w - 12)) + "px";
    } else {
      launcher.style.left = "";
      launcher.style.right = "10px";
    }
  }
  window.TyranorModUI = { open: open, close: close, toggle: toggle, isOpen: isOpen };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", installLauncher); else installLauncher();
})();
