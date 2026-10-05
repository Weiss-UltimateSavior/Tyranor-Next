
;/* TyranorNext 补丁：老版 Tyrano 的 tag.text 在 [iscript] 块中逐行递归调用 nextOrder，
   遇到超长脚本块（如 dress.ks 单块 3229 行）会超出 Android WebView 的 JS 栈而抛
   RangeError，导致 [iscript] 整段中断（事件未绑定、素材/状态异常）。
   此处改为在单帧内迭代消费连续的 text 标签，语义与逐行追加一致，但不再加深递归。*/
(function () {
  try {
    var plugin = window.tyrano && window.tyrano.plugin;
    var def = plugin && plugin.kag && plugin.kag.tag && plugin.kag.tag.text;
    if (!def || typeof def.start !== "function" || def.__tyranorIterative) return;
    var origStart = def.start;
    def.start = function (pm) {
      if (this.kag && this.kag.stat && this.kag.stat.is_script === true) {
        var ftag = this.kag.ftag;
        var array = ftag.array_tag;
        if (!array) return origStart.apply(this, arguments);
        // 当前标签的 pm 已由 nextOrder 转换过，直接使用；后续连续 text 逐个转换，与逐行递归一致
        this.kag.stat.buff_script += pm.val + "\n";
        var index = ftag.current_order_index + 1;
        var tag = array[index];
        while (tag && tag.name === "text") {
          tag.pm = ftag.convertEntity(tag.pm);
          this.kag.stat.buff_script += tag.pm.val + "\n";
          index++;
          tag = array[index];
        }
        ftag.current_order_index = index - 1;
        ftag.nextOrder();
        return;
      }
      return origStart.apply(this, arguments);
    };
    def.__tyranorIterative = true;
  } catch (e) {}
})();
