/**
 * 设置类页面（医院信息 / 仪器状态）共用的页内编辑组件。
 *
 * 抽成独立模块的原因：两个页面的输入框必须具备完全一致的行为，
 * 而这些行为都踩过同一批坑，集中在这一处实现可以避免后续只改一处：
 *   1) 幂等——页面每 1.5s 会定时回填一次，若每次都重建 input，
 *      用户正在输入的内容会被回退、输入法也会被回收；
 *   2) 不打断 IME——聚焦后延后一拍再全选，避免抢占 focus 导致键盘被收起；
 *   3) 与原生状态同步——原生值变化时刷新显示，
 *      但绝不覆盖用户正在输入、尚未保存的内容。
 */
(function () {
  function clean(value) {
    if (value === undefined || value === null) {
      return "";
    }
    value = String(value).replace(/\s+/g, " ").trim();
    if (!value || value === "-" || value === "--" || value === "null" || value === "undefined") {
      return "";
    }
    return value;
  }

  function stopEvent(event) {
    if (event && event.stopPropagation) {
      event.stopPropagation();
    }
  }

  /**
   * 读取原生下发的全量状态；原生桥不可用或数据损坏时返回 null，
 * 由调用方决定是保留页面原始文案还是继续渲染。
   */
  function readState() {
    if (!window.IcuNative || typeof window.IcuNative.firstPhaseState !== "function") {
      return null;
    }
    try {
      return JSON.parse(window.IcuNative.firstPhaseState() || "{}") || {};
    } catch (error) {
      return null;
    }
  }

  /**
   * 把容器改造成可输入的文本框。
   *
   * 幂等：已存在的 input 只在"用户没有改过这个框"时才同步原生新值，
   * 否则定时回填会把用户刚输入的内容冲掉。
   */
  function setField(selector, value, placeholder) {
    var nodes = document.querySelectorAll(selector);
    var text = clean(value);
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      if (!node) {
        continue;
      }
      // 这些框改为页内直接编辑，不再触发原生的弹窗式编辑
      node.removeAttribute("data-native-action");
      node.removeAttribute("data-icu-action");

      var existing = node.querySelector("input[data-inline-field='1']");
      if (existing) {
        var lastNative = existing.__icuNativeValue;
        if (lastNative !== text && existing.value === lastNative) {
          existing.value = text;
        }
        existing.__icuNativeValue = text;
        existing.placeholder = text ? "" : (placeholder || "");
        existing.setAttribute("title", existing.value || text || placeholder || "");
        continue;
      }

      // 容器里可能写死了占位文字（如仪器状态页的 "EM-150Vet-20"），
      // 不清空会和 input 同时显示，看起来像字体错乱。
      node.innerHTML = "";

      var input = document.createElement("input");
      input.type = "text";
      input.setAttribute("data-inline-field", "1");
      input.setAttribute("autocomplete", "off");
      input.setAttribute("spellcheck", "false");
      input.setAttribute("enterkeyhint", "done");
      input.value = text;
      input.__icuNativeValue = text;
      input.placeholder = text ? "" : (placeholder || "");
      input.style.boxSizing = "border-box";
      input.style.display = "block";
      input.style.width = "100%";
      input.style.height = "100%";
      input.style.lineHeight = "36px";
      input.style.padding = "0 16px";
      input.style.fontSize = "18px";
      input.style.color = text ? "rgba(87, 87, 87, 1)" : "rgba(141, 141, 141, 1)";
      input.style.background = "transparent";
      input.style.border = "none";
      input.style.outline = "none";
      input.style.textAlign = "left";
      input.style.overflow = "hidden";
      input.style.whiteSpace = "nowrap";
      input.style.textOverflow = "ellipsis";
      input.setAttribute("title", text || placeholder || "");
      // 聚焦后全选，便于直接覆盖输入；延后一拍避免抢占 focus 导致键盘被回收
      input.addEventListener("focus", function () {
        window.setTimeout(function () {
          try {
            var len = (input.value || "").length;
            if (typeof input.setSelectionRange === "function") {
              input.setSelectionRange(0, len);
            } else if (typeof input.select === "function") {
              input.select();
            }
          } catch (e) {
          }
        }, 0);
      });
      // 阻止冒泡，避免被 lanhu-bridge.js 的 fastHandle 误判为可点击项
      input.addEventListener("click", stopEvent);
      ["mousedown", "touchstart", "touchend"].forEach(function (evt) {
        input.addEventListener(evt, stopEvent);
      });
      node.appendChild(input);
    }
  }

  /**
   * 给容器挂上原生动作标记，交给 lanhu-bridge.js 统一点击分发。
   */
  function bindAction(selector, action, title) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].setAttribute("data-native-action", action);
      nodes[i].style.cursor = "pointer";
      nodes[i].style.touchAction = "manipulation";
      if (title) {
        nodes[i].setAttribute("title", title);
      }
    }
  }

  window.IcuSettingsField = {
    clean: clean,
    readState: readState,
    setField: setField,
    bindAction: bindAction
  };
})();
