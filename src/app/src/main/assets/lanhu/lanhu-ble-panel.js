(function () {
  var openTarget = "";
  var renderTimer = 0;
  var panel = null;
  var panelMode = "hidden";
  var overlayOpen = false;
  // 上一次渲染出来的 HTML 和面板节点：内容没变就别重建 DOM。
  var lastHtml = "";
  var lastPanelNode = null;
  // 每个下拉列表记住的滚动位置，键是 data-target。
  var dropdownScrollMap = {};

  var TEXT = {
    hostName: "\u4e3b\u673a\u540d\u79f0",
    wifi: "wifi\u94fe\u63a5",
    hostBle: "\u4e3b\u673a\u84dd\u7259",
    monitorBle: "\u76d1\u62a4\u84dd\u7259",
    noData: "\u6682\u65e0\u6570\u636e",
    scanning: "\u626b\u63cf\u4e2d",
    connected: "\u5df2\u8fde\u63a5",
    disconnected: "\u672a\u8fde\u63a5",
    hostState: "\u4e3b\u673a\u72b6\u6001",
    monitorState: "\u76d1\u62a4\u72b6\u6001",
    wifiState: "WiFi\u72b6\u6001",
    save: "\u4fdd\u5b58",
    cancel: "\u53d6\u6d88",
    rssi: "\u4fe1\u53f7",
    secure: "\u52a0\u5bc6",
    openSettings: "\u6253\u5f00\u7cfb\u7edf\u8bbe\u7f6e"
  };

  function nativeReady() {
    return !!(window.IcuNative && typeof window.IcuNative.bleState === "function");
  }

  function readState() {
    if (!nativeReady()) {
      return {};
    }
    try {
      return JSON.parse(window.IcuNative.bleState() || "{}") || {};
    } catch (error) {
      return {};
    }
  }

  function callNative(target, action, value) {
    if (nativeReady() && typeof window.IcuNative.bleAction === "function") {
      try {
        window.IcuNative.bleAction(target, action, value || "");
      } catch (error) {
      }
    }
    scheduleRender(180);
  }

  function scheduleRender(delay) {
    window.clearTimeout(renderTimer);
    renderTimer = window.setTimeout(render, delay || 0);
  }

  function clean(value) {
    if (value === undefined || value === null) {
      return "";
    }
    value = String(value).replace(/\s+/g, " ").trim();
    if (!value || value === "null" || value === "undefined" || value === "<unknown ssid>") {
      return "";
    }
    return value;
  }

  function display(value) {
    return clean(value) || "--";
  }

  // 扫描、连接、服务发现和自动重连等待都显示为开，并允许再次点击直接取消。
  function linkActive(link) {
    return !!(link && (link.active === true || link.connected === true || link.scanning === true));
  }

  function escapeHtml(value) {
    return display(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function escapeAttr(value) {
    return clean(value)
      .replace(/&/g, "&amp;")
      .replace(/"/g, "&quot;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function deviceTitle(device) {
    if (!device) {
      return "";
    }
    return clean(device.name) || clean(device.deviceName) || clean(device.id) || clean(device.deviceId);
  }

  function connectedTitle(state) {
    if (!state) {
      return "";
    }
    var name = clean(state.deviceName);
    if (name) {
      return name;
    }
    // 设备没有广播名称时，名称位给出可读的名字，物理地址放在括号里备查。
    var id = clean(state.deviceId);
    return id ? "未命名设备（" + id + "）" : "未命名设备";
  }

  function hostNameValue(state) {
    var host = state.host || {};
    if (!host.connected) {
      return "";
    }
    return connectedTitle(host);
  }

  function wifiOptions(wifi) {
    var list = [];
    var seen = {};
    var networks = (wifi && wifi.networks) || [];
    for (var i = 0; i < networks.length; i += 1) {
      var item = networks[i] || {};
      var ssid = clean(item.ssid || item.name);
      if (!ssid || seen[ssid]) {
        continue;
      }
      seen[ssid] = true;
      var metaParts = [];
      if (typeof item.level === "number") {
        metaParts.push(TEXT.rssi + " " + item.level);
      }
      if (item.secure) {
        metaParts.push(TEXT.secure);
      }
      list.push({
        name: ssid,
        value: ssid,
        meta: metaParts.join(" / ")
      });
    }
    return list;
  }

  function bleOptions(items) {
    var list = [];
    var seen = {};
    items = items || [];
    for (var i = 0; i < items.length; i += 1) {
      var item = items[i] || {};
      var id = clean(item.id || item.deviceId);
      var name = deviceTitle(item);
      if (!id || seen[id]) {
        continue;
      }
      seen[id] = true;
      list.push({
        name: name || id,
        value: id,
        meta: (typeof item.rssi === "number" ? TEXT.rssi + " " + item.rssi : "")
      });
    }
    return list;
  }

  function optionsHtml(target, options) {
    if (!options.length) {
      return '<div class="conn-option empty"><span class="conn-option-name">--</span><span class="conn-option-meta">' + TEXT.noData + '</span></div>';
    }
    return options.map(function (item) {
      return [
        '<div class="conn-option" data-target="', escapeAttr(target), '" data-value="', escapeAttr(item.value), '">',
        '<span class="conn-option-name">', escapeHtml(item.name), '</span>',
        '<span class="conn-option-meta">', escapeHtml(item.meta), '</span>',
        '</div>'
      ].join("");
    }).join("");
  }

  function rowHtml(label, target, value, options, isOn, clickable, showToggle) {
    var selectClass = "conn-select" + (openTarget === target ? " open" : "");
    var valueClass = "conn-value" + (clickable ? " clickable" : " disabled");
    var toggleHtml = showToggle === false ? '<div class="conn-toggle-placeholder"></div>' : '<div class="conn-toggle ' + (isOn ? "on" : "") + '" data-toggle="' + escapeAttr(target) + '"></div>';
    return [
      '<div class="conn-row">',
      '<div class="conn-label">', label, '</div>',
      '<div class="', selectClass, '" data-target="', escapeAttr(target), '">',
      '<div class="', valueClass, '" data-open="', escapeAttr(target), '">', escapeHtml(value), '</div>',
      '<div class="conn-dropdown">', optionsHtml(target, options), '</div>',
      '</div>',
      toggleHtml,
      '</div>'
    ].join("");
  }

  /**
   * 连接状态页原本有一行灰色汇总提示（主机状态/监护状态/WiFi/诊断版本）。
   * 按需求整段删除，不再渲染任何可见文本；但数据通路异常和诊断版本仍要能排障，
   * 因此只写进面板的不可见 data-* 属性，供探针或 adb 读取。
   */
  function diagnosticHint(state) {
    var host = state.host || {};
    var parts = [];
    var dataError = clean(host.dataError);
    if (dataError) {
      parts.push("数据通路异常: " + display(dataError));
    } else if (clean(host.dataState) === "timeout" || host.ready === false) {
      parts.push("数据回包超时，数值为最后真实缓存");
    }
    if (clean(host.dataDiagVersion)) {
      parts.push("诊断: " + display(host.dataDiagVersion));
    }
    return parts.join(" · ");
  }

  function isConnectionPage() {
    var path = window.location.pathname || "";
    return path.indexOf("lanhu_84lianjiezhuangtai") >= 0;
  }

  function ensurePanel() {
    if (panel && panel.parentNode) {
      return panel;
    }
    panel = document.getElementById("icu-ble-panel");
    if (!panel) {
      panel = document.createElement("div");
      panel.id = "icu-ble-panel";
      var page = document.querySelector(".page");
      (page || document.body).appendChild(panel);
    }
    return panel;
  }

  function applyPanelMode() {
    var node = ensurePanel();
    node.classList.remove("icu-ble-visible", "icu-ble-inline", "icu-ble-overlay");
    if (panelMode === "inline") {
      node.classList.add("icu-ble-visible", "icu-ble-inline");
    } else if (panelMode === "overlay") {
      node.classList.add("icu-ble-visible", "icu-ble-overlay");
    }
  }

  function openOverlay() {
    var node = ensurePanel();
    openTarget = "";
    if (!window.IcuOverlay || typeof window.IcuOverlay.open !== "function") {
      panelMode = "inline";
      applyPanelMode();
      render();
      return;
    }
    overlayOpen = true;
    panelMode = "overlay";
    applyPanelMode();
    window.IcuOverlay.open({
      title: "蓝牙与网络连接",
      className: "icu-overlay-ble",
      host: node,
      onClose: function () {
        overlayOpen = false;
        panelMode = isConnectionPage() ? "inline" : "hidden";
        applyPanelMode();
      }
    });
    render();
  }

  function closeOverlay() {
    if (overlayOpen && window.IcuOverlay && typeof window.IcuOverlay.close === "function") {
      window.IcuOverlay.close();
      return;
    }
    openTarget = "";
    render();
  }

  function render() {
    panel = ensurePanel();
    if (!panel) {
      return;
    }
    captureDropdownScroll();
    var state = readState();
    var host = state.host || {};
    var monitor = state.monitor || {};
    var wifi = state.wifi || {};
    var html = "";
    html += rowHtml(TEXT.hostName, "hostName", hostNameValue(state), [], false, false, false);
    html += rowHtml(TEXT.wifi, "wifi", clean(wifi.current), wifiOptions(wifi), clean(wifi.current) !== "" || wifi.enabled === true, true, true);
    html += rowHtml(TEXT.hostBle, "host", connectedTitle(host), bleOptions(host.devices), linkActive(host), true, true);
    html += rowHtml(TEXT.monitorBle, "monitor", connectedTitle(monitor), bleOptions(monitor.devices), linkActive(monitor), true, true);
    // 灰色汇总提示行已移除：诊断结论只留在不可见属性里，页面不再显示。
    var connHint = diagnosticHint(state);
    if (connHint) {
      panel.setAttribute("data-icu-conn-hint", connHint);
    } else {
      panel.removeAttribute("data-icu-conn-hint");
    }
    html += '<div class="conn-actions"><button class="conn-btn" data-command="save">' + TEXT.save + '</button><button class="conn-btn primary" data-command="cancel">' + TEXT.cancel + '</button></div>';
    if (panel !== lastPanelNode || html !== lastHtml) {
      panel.innerHTML = html;
      lastPanelNode = panel;
      lastHtml = html;
    }
    restoreDropdownScroll();
  }

  /**
   * 记下每个下拉列表当前的滚动位置。
   *
   * <p>附近设备一多，下拉框一次只露 5 条，剩下的要往下滚。
   * 面板每隔 1.2 秒就会重建一次 DOM，滚动位置会被重置回 0，
   * 刚找到的设备又跑回最前面，所以重建前先把位置记下来。</p>
   */
  function captureDropdownScroll() {
    if (!panel) {
      return;
    }
    var lists = panel.querySelectorAll(".conn-dropdown");
    for (var i = 0; i < lists.length; i += 1) {
      var select = lists[i].parentNode;
      var key = select ? select.getAttribute("data-target") : "";
      if (!key) {
        continue;
      }
      // 隐藏的列表读出来的 scrollTop 恒为 0，会把记住的位置冲掉，
      // 只有真正可见的时候才更新记录。
      if (lists[i].getClientRects().length > 0) {
        dropdownScrollMap[key] = lists[i].scrollTop;
      }
    }
  }

  /** 把重建 DOM 之前的滚动位置还原回去。 */
  function restoreDropdownScroll() {
    if (!panel) {
      return;
    }
    var lists = panel.querySelectorAll(".conn-dropdown");
    for (var i = 0; i < lists.length; i += 1) {
      var select = lists[i].parentNode;
      var key = select ? select.getAttribute("data-target") : "";
      if (!key || !Object.prototype.hasOwnProperty.call(dropdownScrollMap, key)) {
        continue;
      }
      lists[i].scrollTop = dropdownScrollMap[key];
    }
  }

  function openSelect(target) {
    if (target === "hostName") {
      openTarget = "";
      render();
      return;
    }
    openTarget = openTarget === target ? "" : target;
    if (openTarget === "wifi") {
      callNative("wifi", "scan", "");
    } else if (openTarget === "host" || openTarget === "monitor") {
      callNative(openTarget, "scan", "");
    } else {
      render();
    }
  }

  function onSelect(target, value) {
    openTarget = "";
    if (target === "wifi") {
      callNative("wifi", "select", value);
      return;
    }
    if (target === "host" || target === "monitor") {
      callNative(target, "connect", value);
    }
  }

  function onToggle(target) {
    var state = readState();
    if (target === "wifi") {
      openTarget = "wifi";
      if (state.wifi && state.wifi.enabled === false) {
        callNative("wifi", "settings", "");
      } else {
        callNative("wifi", "scan", "");
      }
      return;
    }
    if (target === "host") {
      if (linkActive(state.host)) {
        openTarget = "";
        callNative("host", "disconnect", "");
      } else {
        openSelect("host");
      }
      return;
    }
    if (target === "monitor") {
      if (linkActive(state.monitor)) {
        openTarget = "";
        callNative("monitor", "disconnect", "");
      } else {
        openSelect("monitor");
      }
    }
  }

  document.addEventListener("click", function (event) {
    var option = event.target.closest ? event.target.closest(".conn-option") : null;
    if (option && !option.className.match(/\bempty\b/)) {
      onSelect(option.getAttribute("data-target"), option.getAttribute("data-value"));
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    var toggle = event.target.closest ? event.target.closest("[data-toggle]") : null;
    if (toggle) {
      onToggle(toggle.getAttribute("data-toggle"));
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    var opener = event.target.closest ? event.target.closest("[data-open]") : null;
    if (opener) {
      openSelect(opener.getAttribute("data-open"));
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    var command = event.target.closest ? event.target.closest("[data-command]") : null;
    if (command) {
      if (command.getAttribute("data-command") === "save") {
        callNative("host", "readAll", "");
      } else {
        closeOverlay();
      }
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (openTarget && !(event.target.closest && event.target.closest("#icu-ble-panel"))) {
      openTarget = "";
      render();
    }
  }, true);

  function startPanel() {
    panelMode = isConnectionPage() ? "inline" : "hidden";
    applyPanelMode();
    render();
    window.setInterval(render, 1200);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startPanel);
  } else {
    startPanel();
  }

  window.IcuBlePanel = {
    open: openOverlay,
    close: closeOverlay,
    isOpen: function () {
      return overlayOpen;
    }
  };

  window.addEventListener("icu-native-state", function () {
    scheduleRender(0);
  });
})();
