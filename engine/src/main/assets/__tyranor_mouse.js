(function () {
  "use strict";
  /* 虚拟鼠标合成事件 API（RPG Maker MV/MZ）。
   * 坐标为 CSS 像素（clientX/clientY 空间）；事件从命中元素冒泡到
   * window，被 rmmz_core 的 TouchInput 捕获。MZ 不校验 isTrusted。
   * button：0=左键，2=右键（RMMZ 右键=取消/呼出菜单）。 */
  if (window.__tnMouse) return;

  /**
   * Finds the element at the specified viewport coordinates.
   * @param {number} x - The horizontal viewport coordinate.
   * @param {number} y - The vertical viewport coordinate.
   * @return {Element|null} The element at the coordinates, or `null` if hit testing fails.
   */
  function hitElement(x, y) {
    var cx = Math.min(Math.max(x, 0), (window.innerWidth || 1) - 1);
    var cy = Math.min(Math.max(y, 0), (window.innerHeight || 1) - 1);
    try { return document.elementFromPoint(cx, cy); } catch (e) { return null; }
  }

  /**
   * Dispatches a bubbling, cancelable event at the element located at the given client coordinates.
   * @param {Function} constructor - Event constructor used to create the event.
   * @param {Object} init - Event type and initialization data, including `clientX` and `clientY`.
   */
  function fire(constructor, init) {
    var el = hitElement(init.clientX, init.clientY) ||
      document.body || document.documentElement;
    if (!el) return;
    var ev = new constructor(init.type, init);
    // 关键：MZ 的 TouchInput 读 event.pageX/pageY（rmmz_core _onLeftButtonDown
    // 等），而 pageX/pageY 不在 MouseEventInit 标准字段里，合成事件上恒为
    // undefined → Graphics.pageToCanvasX(undefined)=NaN → isInsideCanvas 恒
    // false → 点击/悬停全部失效。这里在实例上手动补齐（遮蔽原型 getter）。
    // MZ 页面无滚动，page 坐标 == client 坐标。
    try {
      Object.defineProperty(ev, "pageX", { value: init.clientX || 0 });
      Object.defineProperty(ev, "pageY", { value: init.clientY || 0 });
    } catch (e) { /* 极端实现下忽略 */ }
    el.dispatchEvent(ev);
  }

  /**
   * Dispatches a mouse event at the specified coordinates and button state.
   * @param {string} type - The mouse event type to dispatch.
   * @param {number} x - The horizontal client and screen coordinate.
   * @param {number} y - The vertical client and screen coordinate.
   * @param {number} button - The button identifier, where `2` represents the right button.
   */
  function mouseEvent(type, x, y, button) {
    fire(MouseEvent, {
      type: type, clientX: x, clientY: y,
      screenX: x, screenY: y,
      button: button || 0,
      buttons: type === "mouseup" ? 0 : (button || 0) === 2 ? 2 : 1,
      bubbles: true, cancelable: true
    });
  }

  window.__tnMouse = {
    /** 光标移动（驱动 RMMZ 菜单 hover 高亮） */
    move: function (x, y) { mouseEvent("mousemove", x, y, 0); },
    down: function (x, y, button) { mouseEvent("mousedown", x, y, button); },
    up: function (x, y, button) { mouseEvent("mouseup", x, y, button); },
    /** 左键单击 = down + (按住 ~50ms) + up + click。
     *  不能同步 down+up：本作的 Window_Message.isTriggered 走
     *  TouchInput.isRepeated() = isPressed() && (triggered || 长按重复)，
     *  同帧复位 _mousePressed 会让下一帧 isPressed()=false，对话永不推进
     *  （真实鼠标靠人手按住数帧才生效）。 */
    click: function (x, y) {
      var self = this;
      this.down(x, y, 0);
      setTimeout(function () {
        self.up(x, y, 0);
        fire(MouseEvent, { type: "click", clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true });
      }, 50);
    },
    /** 右键单击（取消/返回）：mousedown/up button=2 + contextmenu */
    rclick: function (x, y) {
      this.down(x, y, 2);
      this.up(x, y, 2);
      fire(MouseEvent, {
        type: "contextmenu", clientX: x, clientY: y,
        button: 2, buttons: 0, bubbles: true, cancelable: true
      });
    },
    /** 滚轮：deltaMode 固定 0（像素），作用于光标处窗口。
     *  注意：合成（untrusted）wheel 事件不会触发浏览器原生滚动，
     *  MZ 的列表由 JS 接管滚动不受影响；但 DOM 滚动容器（如修改器
     *  面板 .tm-content）需要这里手动补滚，向上找最近可滚祖先。 */
    wheel: function (deltaY, x, y) {
      fire(WheelEvent, {
        type: "wheel", deltaX: 0, deltaY: deltaY, deltaZ: 0, deltaMode: 0,
        clientX: x, clientY: y, bubbles: true, cancelable: true
      });
      var el = hitElement(x, y);
      var depth = 0;
      while (el && el !== document.body && el !== document.documentElement && depth++ < 8) {
        if (el.scrollHeight > el.clientHeight + 1) {
          var oy = "";
          try { oy = getComputedStyle(el).overflowY; } catch (e) { oy = ""; }
          if (oy === "auto" || oy === "scroll" || oy === "overlay") {
            el.scrollTop += deltaY;
            return;
          }
        }
        el = el.parentElement;
      }
    }
  };
})();
