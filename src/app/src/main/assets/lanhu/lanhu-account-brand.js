/**
 * 顶栏账号与机构 LOGO 的公共轻量渲染。
 * 设置页不加载 first-phase，也在这里读取同一份原生状态。
 */
(function () {
  var started = false;
  var thumbnailCache = {};

  function clean(value) {
    if (value === undefined || value === null) {
      return "";
    }
    return String(value).replace(/\s+/g, " ").trim();
  }

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

  function writeText(node, value) {
    if (node && node.textContent !== value) {
      node.textContent = value;
    }
  }

  function renderAccount(state) {
    var nodes = document.querySelectorAll("[data-account-live='name']");
    if (!nodes.length) {
      nodes = document.querySelectorAll(".text_4");
    }
    var loggedIn = true;
    if (window.IcuNative && typeof window.IcuNative.isLoggedIn === "function") {
      try {
        loggedIn = window.IcuNative.isLoggedIn() !== false;
      } catch (error) {
      }
    }
    var name = loggedIn ? clean(state.accountName) || "--" : "未登录";
    for (var i = 0; i < nodes.length; i += 1) {
      writeText(nodes[i], name);
    }
  }

  /** 设置页左侧菜单的选中项也导出成 .text-wrapper_1 > .text_5（文字是"医院信息"
   *  而不是"LOGO"）。早期版本把它当成 LOGO 占位框，每 1.5s 把菜单文字改写成
   *  logoName/LOGO，表现为"点医院信息变成标识符"与"点仪器状态文字反复跳动"。 */
  function isSettingsNavPill(slot) {
    // 只检查菜单容器的「直接子元素」文本。整页 textContent 会把内嵌 <script> 里的
    // 路由表（含医院信息/仪器状态/管理员设置）也算进来，主页面会被误判成设置页，
    // 顶栏 LOGO 槽因此被排除，上传的 LOGO 图片就永远不显示。
    var scope = slot && slot.parentElement;
    if (!scope || !scope.children) {
      return false;
    }
    var hits = 0;
    for (var i = 0; i < scope.children.length; i += 1) {
      var child = scope.children[i];
      if (!child || child.tagName === "SCRIPT" || child.tagName === "STYLE") {
        continue;
      }
      var text = clean(child.textContent || "").replace(/\s+/g, "");
      if (text === "医院信息" || text === "仪器状态" || text === "管理员设置"
          || text === "连接状态" || text === "补偿设置" || text === "其他设置"
          || text === "关于") {
        hits += 1;
      }
    }
    return hits >= 3;
  }

  function logoSlots() {
    var result = [];
    var marked = document.querySelectorAll("[data-org-logo-slot]");
    var i;
    for (i = 0; i < marked.length; i += 1) {
      result.push(marked[i]);
    }
    var labels = document.querySelectorAll(".text-wrapper_1 > .text_5");
    for (i = 0; i < labels.length; i += 1) {
      var label = labels[i];
      var text = clean(label.textContent || "");
      var markedText = label.hasAttribute && label.hasAttribute("data-org-logo-text");
      // 只认真正的 LOGO 占位文字；设置页导航标签一律跳过，绝不改写菜单文字。
      if (text !== "LOGO" && text !== "+" && text !== "" && !markedText) {
        continue;
      }
      var slot = label.parentElement;
      if (slot && !isSettingsNavPill(slot) && result.indexOf(slot) < 0) {
        slot.setAttribute("data-org-logo-slot", "common");
        result.push(slot);
      }
    }
    return result;
  }

  function placeholderFor(slot) {
    var text = slot.querySelector("[data-org-logo-text]");
    // 只有确认是 LOGO 槽才允许用 .text_5 兜底，避免误伤同页其它 .text_5 文字。
    if (!text && slot.getAttribute("data-org-logo-slot")) {
      text = slot.querySelector(".text_5");
    }
    if (!slot.__icuLogoPlaceholder && text) {
      slot.__icuLogoPlaceholder = text.textContent || "";
    }
    return text;
  }

  function restorePlaceholder(slot, organization) {
    var text = placeholderFor(slot);
    slot.style.backgroundImage = "";
    slot.style.backgroundSize = "";
    slot.style.backgroundPosition = "";
    slot.style.backgroundRepeat = "";
    if (text) {
      text.style.opacity = "1";
      if (slot.getAttribute("data-org-logo-slot") === "common") {
        writeText(text, clean(organization.logoName) || "LOGO");
      } else {
        writeText(text, slot.__icuLogoPlaceholder || "+");
      }
    }
  }

  function thumbnail(uri) {
    if (thumbnailCache[uri]) {
      return thumbnailCache[uri];
    }
    var source = "";
    if (window.IcuNative && typeof window.IcuNative.galleryThumbnail === "function") {
      try {
        source = clean(window.IcuNative.galleryThumbnail(uri));
      } catch (error) {
        source = "";
      }
    }
    // 浏览器预览没有原生桥时仍可直接预览 data/file/http 图片；
    // Android 真机的 content URI 必须走上面的原生缩略图。
    if (!source) {
      // 原生桥存在但缩略图解码失败时，也再给原始 URI 一次机会：
      // 浏览器预览与已拿到 content URI 读权限的 WebView 仍能直接加载。
      return uri;
    }
    thumbnailCache[uri] = source;
    return source;
  }

  function renderLogo(slot, organization) {
    var uri = clean(organization.logoUri);
    var logoName = clean(organization.logoName);
    if (slot.__icuLogoUri === uri && slot.__icuLogoState) {
      // 同一个空 URI 下也可能只修改 LOGO 名称；占位文字仍须随原生状态局部刷新。
      if (slot.__icuLogoState !== "loaded" && slot.__icuLogoName !== logoName) {
        slot.__icuLogoName = logoName;
        restorePlaceholder(slot, organization);
      }
      return;
    }
    slot.__icuLogoName = logoName;
    var token = (slot.__icuLogoToken || 0) + 1;
    slot.__icuLogoToken = token;
    slot.__icuLogoUri = uri;
    slot.__icuLogoState = uri ? "loading" : "empty";
    restorePlaceholder(slot, organization);
    if (!uri) {
      return;
    }
    var source = thumbnail(uri);
    if (!source) {
      slot.__icuLogoState = "error";
      return;
    }
    var image = new Image();
    image.onload = function () {
      if (slot.__icuLogoToken !== token) {
        return;
      }
      var text = placeholderFor(slot);
      slot.style.backgroundImage = 'url("' + source.replace(/(["\\\n\r])/g, "\\$1") + '")';
      slot.style.backgroundSize = "contain";
      slot.style.backgroundPosition = "center";
      slot.style.backgroundRepeat = "no-repeat";
      if (text) {
        text.style.opacity = "0";
      }
      slot.__icuLogoState = "loaded";
    };
    image.onerror = function () {
      if (slot.__icuLogoToken !== token) {
        return;
      }
      slot.__icuLogoState = "error";
      delete thumbnailCache[uri];
      restorePlaceholder(slot, organization);
    };
    image.src = source;
  }

  function render() {
    var state = readState();
    if (!state) {
      return;
    }
    renderAccount(state);
    var organization = state.organization || {};
    var slots = logoSlots();
    for (var i = 0; i < slots.length; i += 1) {
      renderLogo(slots[i], organization);
    }
  }

  function start() {
    if (started) {
      return;
    }
    started = true;
    render();
    window.setInterval(render, 1500);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
  window.addEventListener("icu-native-state", render);
})();
