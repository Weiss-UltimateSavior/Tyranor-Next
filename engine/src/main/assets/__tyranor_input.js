(function () {
  "use strict";
  /* 输入重映射接收器（Tyrano / RPG Maker MV / MZ 等 Web 系引擎共用）。
   *
   * 由原型按键层（VirtualPadView）与手柄映射（InputRouter）经 evaluateJavascript 调用：
   *   window.__tyranorInput.key(jsKeyCode, 1|0)  —— 合成 keydown/keyup
   *   window.__tyranorInput.mouse(canonical, 1)  —— 鼠标键单击（1000=左 1001=右 1002=中 1003/1004=滚轮）
   *   window.__tyranorInput.cancel()             —— 释放全部按压状态
   *
   * 键盘事件与旧 __touch_pad.js 同型：createEvent('UIEvents') + 伪造 keyCode/which，
   * MV/MZ 的 Input 与 Tyrano 的 KAG 都读 event.keyCode。鼠标事件复用 __tnMouse
   * （__tyranor_mouse.js，含 MZ 需要的 pageX/pageY 修正）。
   */
  if (window.__tyranorInput) return;

  /** 视口中心（鼠标键作用于该点；MZ 菜单 hover / 点击均以画布坐标判定）。 */
  function center() {
    return {
      x: Math.max(1, Math.round((window.innerWidth || 1) / 2)),
      y: Math.max(1, Math.round((window.innerHeight || 1) / 2))
    };
  }

  var held = {};

  function dispatchKey(code, down) {
    var evt = document.createEvent("UIEvents");
    Object.defineProperty(evt, "keyCode", { get: function () { return code; } });
    Object.defineProperty(evt, "which", { get: function () { return code; } });
    evt.initUIEvent(down ? "keydown" : "keyup", true, true, window, 1);
    // 修饰键 metaState：MV/MZ 在部分分支读 ctrlKey/shiftKey；合成事件默认全 false，
    // 需要在实例上补齐（按 keyCode 判断 16/17/18）。
    try {
      if (code === 16) Object.defineProperty(evt, "shiftKey", { get: function () { return down; } });
      if (code === 17) Object.defineProperty(evt, "ctrlKey", { get: function () { return down; } });
      if (code === 18) Object.defineProperty(evt, "altKey", { get: function () { return down; } });
    } catch (e) { /* 个别实现不可覆写时忽略 */ }
    var target = document.body || document.documentElement || document;
    target.dispatchEvent(evt);
    // 部分引擎在 window 上额外监听（keydown 冒泡到 window 即可，无需重复派发）
  }

  function mouse(canonical) {
    var c = center();
    var tn = window.__tnMouse;
    if (!tn) return;
    if (canonical === 1000) { tn.click(c.x, c.y); return; }
    if (canonical === 1001) { tn.rclick(c.x, c.y); return; }
    if (canonical === 1002) { tn.down(c.x, c.y, 1); tn.up(c.x, c.y, 1); return; }
    if (canonical === 1003) { tn.wheel(-120, c.x, c.y); return; }
    if (canonical === 1004) { tn.wheel(120, c.x, c.y); return; }
  }

  window.__tyranorInput = {
    key: function (code, down) {
      if (!code) return;
      var key = String(code);
      if (down) {
        if (held[key]) return; // 重复按下不重复派发
        held[key] = true;
      } else {
        if (!held[key]) return;
        delete held[key];
      }
      dispatchKey(code, !!down);
    },
    mouse: function (canonical, pressed) {
      if (!pressed) return;
      mouse(canonical);
    },
    cancel: function () {
      for (var key in held) {
        if (Object.prototype.hasOwnProperty.call(held, key)) {
          dispatchKey(Number(key), false);
        }
      }
      held = {};
      if (window.__tnMouse && window.__tnMouse.cancel) window.__tnMouse.cancel();
    }
  };
})();
