/**
 * 设置类页面（医院信息 / 仪器状态 / 连接状态 / 补偿设置 / 其他设置 / 关于 / 管理员设置）
 * 左侧菜单的统一排版与统一点击热区。
 *
 * 背景：蓝湖导出的这 7 个页面各自带了一份菜单副本，"当前选中项"被写死在各自页面的
 * CSS 里——白底药丸（.text-wrapper_1）套住选中项、选中项文字用深色、其余项用白色，
 * 每个菜单项的外边距也随"药丸套在哪一项"而不同。各页导出的外边距并不一致：医院信息页
 * 第一项的外边距是 9px，仪器状态页是 26px，补偿设置页也差 1px。于是同一条菜单在不同
 * 页面上的条目坐标会漂移，切换页面时列表看起来会"跳一下"。
 *
 * 这里做两件事：
 * 1) 排版统一——把 7 个页面的菜单项按同一组坐标重排，选中态跟随当前页面；
 * 2) 热区统一——每一项的可点范围统一扩展到与白色药丸完全重合的整行，避免出现
 *    "选中项点整行都行、未选中项只能点中文字"的不一致。
 *
 * 只重写外边距与文字颜色，并追加一个透明的 ::after 伪元素；字体、字号、图标与配色
 * 一律沿用设计稿，视觉风格保持不变。
 */
(function () {
  "use strict";

  // 页面 -> 该页应选中的菜单项
  var PAGE_ACTIVE_LABEL = {
    "lanhu_81shezhi": "医院信息",
    "lanhu_82shezhi": "仪器状态",
    "lanhu_84lianjiezhuangtai": "连接状态",
    "lanhu_85buchangshezhi": "补偿设置",
    "lanhu_86qita": "其他设置",
    "lanhu_87guanyu": "关于",
    "lanhu_88guanliyuan": "管理员设置"
  };

  // 菜单项在设计稿中的顺序（报告模版-默认不展示）
  var LABEL_ORDER = ["医院信息", "仪器状态", "报告模版-默认", "连接状态", "补偿设置", "其他设置", "关于", "管理员设置"];
  var HIDDEN_LABEL = "报告模版-默认";

  // 菜单项文字的纵向位置（设计稿坐标，相对菜单容器顶部）与横向缩进
  var ITEM_TOP = {
    "医院信息": 26,
    "仪器状态": 64,
    "连接状态": 101,
    "补偿设置": 137,
    "其他设置": 174,
    "关于": 211,
    "管理员设置": 248
  };
  var ITEM_LEFT = 50;

  // 选中项白底药丸的样式（与设计稿一致）
  var PILL_LEFT = 14;
  var PILL_WIDTH = 336;
  var PILL_HEIGHT = 36;
  var PILL_RADIUS = 5;
  var PILL_TEXT_TOP = 9;

  // 命中区：与白色药丸完全重合（相对菜单文字的偏移），保证每一行可点范围一致
  var HIT_CLASS = "icu-nav-item";
  var HIT_STYLE_ID = "icu-settings-nav-hitarea";
  var HIT_LEFT = PILL_LEFT - ITEM_LEFT;
  var HIT_TOP = -PILL_TEXT_TOP;
  var HIT_WIDTH = PILL_WIDTH;
  var HIT_HEIGHT = PILL_HEIGHT;

  var ACTIVE_COLOR = "rgb(87, 87, 87)";
  var INACTIVE_COLOR = "rgb(255, 255, 255)";

  function compact(value) {
    return (value || "").replace(/\s+/g, "").trim();
  }

  function pageKey() {
    var match = (window.location.pathname || "").match(/src\/views\/(lanhu_[0-9a-z]+)\//);
    return match ? match[1] : "";
  }

  /** 在 root 内查找叶子节点（没有子元素的 span/div）且文本完全等于 label 的第一个节点。 */
  function leafByLabel(root, label) {
    var nodes = root.querySelectorAll("span,div");
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      if (node.children && node.children.length) {
        continue;
      }
      if (compact(node.textContent) === label) {
        return node;
      }
    }
    return null;
  }

  /** 菜单容器就是"报告模版-默认"的父级；逐级上溯直到同时包含全部菜单项为止。 */
  function findNavContainer() {
    var anchor = leafByLabel(document, HIDDEN_LABEL);
    if (!anchor) {
      return null;
    }
    var container = anchor.parentNode;
    while (container && container !== document.body) {
      var matched = true;
      for (var i = 0; i < LABEL_ORDER.length; i += 1) {
        if (!leafByLabel(container, LABEL_ORDER[i])) {
          matched = false;
          break;
        }
      }
      if (matched) {
        return container;
      }
      container = container.parentNode;
    }
    return null;
  }

  function findPill(container) {
    var nodes = container.querySelectorAll("div");
    for (var i = 0; i < nodes.length; i += 1) {
      if (nodes[i].classList && nodes[i].classList.contains("text-wrapper_1")) {
        return nodes[i];
      }
    }
    return null;
  }

  /** 注入命中区样式：伪元素透明无背景，只扩大可点范围，不改变任何可见样式。 */
  function installHitAreaStyle() {
    if (document.getElementById(HIT_STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = HIT_STYLE_ID;
    style.textContent = "." + HIT_CLASS + "{position:relative;}" +
      "." + HIT_CLASS + "::after{content:'';position:absolute;" +
      "left:" + HIT_LEFT + "px;top:" + HIT_TOP + "px;" +
      "width:" + HIT_WIDTH + "px;height:" + HIT_HEIGHT + "px;}";
    (document.head || document.documentElement).appendChild(style);
  }

  /** 给所有可见菜单项挂上命中区样式类。 */
  function applyHitAreas(nodes) {
    installHitAreaStyle();
    for (var i = 0; i < LABEL_ORDER.length; i += 1) {
      var label = LABEL_ORDER[i];
      if (label === HIDDEN_LABEL) {
        continue;
      }
      nodes[label].classList.add(HIT_CLASS);
    }
  }

  /**
   * 按统一坐标重排菜单。
   * 每一项的 margin-top 由"目标纵向位置 - 上一项底部"算出，因此无论药丸套在哪一项上，
   * 所有菜单项都会落在同一组坐标上。
   */
  function normalize() {
    var activeLabel = PAGE_ACTIVE_LABEL[pageKey()];
    if (!activeLabel) {
      return;
    }
    var container = findNavContainer();
    if (!container) {
      return;
    }
    var nodes = {};
    for (var i = 0; i < LABEL_ORDER.length; i += 1) {
      var node = leafByLabel(container, LABEL_ORDER[i]);
      if (!node) {
        // 页面结构与预期不符时保持原样，避免半吊子改动
        return;
      }
      nodes[LABEL_ORDER[i]] = node;
    }

    var hidden = nodes[HIDDEN_LABEL];
    hidden.style.display = "none";
    hidden.style.visibility = "hidden";

    var pill = findPill(container);
    if (!pill) {
      pill = document.createElement("div");
      pill.className = "text-wrapper_1 flex-col";
      container.appendChild(pill);
    }
    var active = nodes[activeLabel];
    if (active.parentNode !== pill) {
      // 药丸没套住选中项：先把它原来套着的项放回原位（否则会留在药丸里重复渲染），
      // 再把药丸挪到选中项原来的位置，最后把选中项装进药丸
      var previous = pill.firstElementChild;
      if (previous && previous !== active) {
        container.insertBefore(previous, pill);
      }
      container.insertBefore(pill, active);
      pill.appendChild(active);
    }

    applyHitAreas(nodes);

    // 把设计稿标签钉在节点上：公共模块周期性回填时一旦改写导航文字，
    // 守卫会立刻恢复，杜绝设置页菜单文字波动。
    for (var labelIndex = 0; labelIndex < LABEL_ORDER.length; labelIndex += 1) {
      var navKey = LABEL_ORDER[labelIndex];
      nodes[navKey].setAttribute("data-icu-nav-label", navKey);
    }
    installNavLabelGuard();

    // 外边距不影响高度，先把各菜单项的高度取出来，供下一项的顶部推算
    var heights = {};
    for (var j = 0; j < LABEL_ORDER.length; j += 1) {
      var label = LABEL_ORDER[j];
      if (label !== HIDDEN_LABEL) {
        heights[label] = nodes[label].offsetHeight || 17;
      }
    }

    var prevBottom = 0;
    for (var k = 0; k < LABEL_ORDER.length; k += 1) {
      var key = LABEL_ORDER[k];
      if (key === HIDDEN_LABEL) {
        continue;
      }
      var span = nodes[key];
      var top = ITEM_TOP[key];
      if (key === activeLabel) {
        pill.style.width = PILL_WIDTH + "px";
        pill.style.height = PILL_HEIGHT + "px";
        pill.style.borderRadius = PILL_RADIUS + "px";
        pill.style.backgroundColor = "rgb(255, 255, 255)";
        pill.style.marginLeft = PILL_LEFT + "px";
        pill.style.marginRight = "0px";
        pill.style.marginBottom = "0px";
        pill.style.marginTop = (top - PILL_TEXT_TOP - prevBottom) + "px";
        span.style.marginTop = PILL_TEXT_TOP + "px";
        span.style.marginLeft = (ITEM_LEFT - PILL_LEFT) + "px";
        span.style.marginRight = "0px";
        span.style.marginBottom = "0px";
        span.style.color = ACTIVE_COLOR;
        prevBottom = top - PILL_TEXT_TOP + PILL_HEIGHT;
      } else {
        span.style.marginTop = (top - prevBottom) + "px";
        span.style.marginLeft = ITEM_LEFT + "px";
        span.style.marginRight = "0px";
        span.style.marginBottom = "0px";
        span.style.color = INACTIVE_COLOR;
        prevBottom = top + heights[key];
      }
    }
  }

  /** 导航文字必须与设计稿标签一致：任何模块改写后都立刻恢复。 */
  function restoreNavLabels() {
    var nodes = document.querySelectorAll("[data-icu-nav-label]");
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      var expected = node.getAttribute("data-icu-nav-label");
      if (expected && compact(node.textContent) !== expected) {
        node.textContent = expected;
      }
    }
  }

  var navGuardInstalled = false;
  function installNavLabelGuard() {
    restoreNavLabels();
    if (navGuardInstalled) {
      return;
    }
    navGuardInstalled = true;
    if (window.MutationObserver && document.body) {
      try {
        var observer = new MutationObserver(function () { restoreNavLabels(); });
        observer.observe(document.body, { childList: true, characterData: true, subtree: true });
      } catch (error) {
      }
    }
    // 兜底：部分改写走 value / innerHTML，MutationObserver 未必覆盖全部场景。
    window.setInterval(restoreNavLabels, 400);
  }

  function run() {
    try {
      normalize();
    } catch (error) {
      if (window.console && window.console.warn) {
        window.console.warn("[settings-nav] 菜单排版失败", error);
      }
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();
