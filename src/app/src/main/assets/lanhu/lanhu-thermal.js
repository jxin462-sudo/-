(function () {
  "use strict";

  // ==========================================================================
  // 红外测温（热成像）前端模块
  // 数据来源：Native 通过 BLE 从红外测温桥接收热成像帧，每帧 32×24 个像素，
  // 每个像素是小端 int16（单位 0.01℃）。本模块只负责解码与渲染。
  // ==========================================================================

  var FRAME_COLS = 32;
  var FRAME_ROWS = 24;
  // 超过这个时间没有收到新帧，就认为红外测温已经停了
  var RECENT_FRAME_MS = 3000;

  var cardCanvas = null;
  var cardContext = null;
  var cardStatus = null;
  var fullscreenCanvas = null;
  var fullscreenContext = null;
  var fullscreenState = null;
  var devicePicker = null;
  var devicePickerTimer = null;
  var deviceListNodes = {};
  var deviceRefreshTimer = null;
  var connectingDeviceId = "";
  var deviceBadge = null;
  var deviceBadgeTimer = 0;
  var requestedDeviceId = "";
  var requestedDeviceName = "";

  var connectionState = "等待红外测温桥";
  var latestTemperatures = null;
  var latestAverage = null;
  var latestMin = null;
  var latestMax = null;
  var lastFrameAt = 0;
  var latestWidth = FRAME_COLS;
  var latestHeight = FRAME_ROWS;
  var displayedMin = null;
  var displayedMax = null;
  var frameCount = 0;

  // ---- inferno 配色查找表（256 项，避免逐像素插值）----
  var COLOR_STOPS = [
    [0, 0, 4], [31, 12, 72], [85, 15, 109], [136, 34, 106],
    [186, 54, 85], [227, 89, 51], [249, 140, 10], [252, 205, 37], [252, 255, 164]
  ];
  var LUT_R = new Uint8Array(256);
  var LUT_G = new Uint8Array(256);
  var LUT_B = new Uint8Array(256);
  (function buildInfernoLut() {
    for (var i = 0; i < 256; i += 1) {
      var scaled = (i / 255) * (COLOR_STOPS.length - 1);
      var index = Math.min(COLOR_STOPS.length - 2, Math.floor(scaled));
      var ratio = scaled - index;
      var start = COLOR_STOPS[index];
      var end = COLOR_STOPS[index + 1];
      LUT_R[i] = (start[0] + (end[0] - start[0]) * ratio) | 0;
      LUT_G[i] = (start[1] + (end[1] - start[1]) * ratio) | 0;
      LUT_B[i] = (start[2] + (end[2] - start[2]) * ratio) | 0;
    }
  })();

  // 32×24 离屏画布：只填 768 个像素，缩放交给浏览器/GPU
  var rawCanvas = null;
  var rawContext = null;
  var rawImageData = null;

  var renderScheduled = false;
  var pendingFrameBase64 = "";
  var pendingFrameCols = 0;
  var pendingFrameRows = 0;

  var decodeBuffer = null;
  var tempBuffer = null;
  var sortScratch = [];

  var logEntries = [];
  var MAX_LOG_ENTRIES = 40;

  // ------------------------------------------------------------------ 工具

  function addLog(message) {
    logEntries.unshift(String(message));
    if (logEntries.length > MAX_LOG_ENTRIES) {
      logEntries.length = MAX_LOG_ENTRIES;
    }
  }

  function toast(message) {
    if (window.IcuToast && typeof window.IcuToast.show === "function") {
      window.IcuToast.show(message);
    }
  }

  /**
   * 画布后备缓冲区与屏幕像素并非 1:1（小卡片还被 .page 整体缩放），
   * 统一换算出"后备缓冲像素 / 屏幕像素"，文字才能按屏幕尺寸绘制。
   */
  function canvasPixelScale(targetCanvas) {
    if (!targetCanvas || !targetCanvas.getBoundingClientRect) {
      return 1;
    }
    var rect = targetCanvas.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var deviceWidth = rect.width * dpr;
    if (!(deviceWidth > 0) || !(targetCanvas.width > 0)) {
      return 1;
    }
    return targetCanvas.width / deviceWidth;
  }

  // ---------------------------------------------------------------- 初始化

  function initialize() {
    if (cardCanvas) {
      return;
    }
    cardCanvas = document.getElementById("icu-thermal-canvas");
    if (!cardCanvas) {
      return;
    }
    cardContext = cardCanvas.getContext("2d", { alpha: false });
    cardStatus = document.getElementById("icu-thermal-status");
    deviceBadge = ensureDeviceBadge();
    readInitialConnectionState();
    drawPlaceholder(cardContext, cardCanvas);
    updateCardStatus();
    startDeviceBadgePolling();

    cardCanvas.addEventListener("click", openFullscreen);
    cardCanvas.addEventListener("touchend", function (event) {
      event.preventDefault();
      openFullscreen();
    }, { passive: false });
  }

  function readInitialConnectionState() {
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalState === "function") {
        connectionState = window.IcuNative.thermalState() || connectionState;
      }
    } catch (error) {
      // Native 桥不可用时保持默认状态，不影响页面其余部分
    }
  }

  // ------------------------------------------------------------ 状态展示

  function cardStatusText() {
    if (latestTemperatures) {
      return "实时热图";
    }
    if (/已连接|实时数据/.test(connectionState)) {
      return "实时热图";
    }
    if (/搜索|扫描/.test(connectionState)) {
      return "搜索中";
    }
    if (/连接中|订阅/.test(connectionState)) {
      return "连接中";
    }
    if (/断开/.test(connectionState)) {
      return "已断开";
    }
    if (/蓝牙|权限/.test(connectionState)) {
      return "蓝牙未就绪";
    }
    return "未连接";
  }

  function updateCardStatus() {
    if (!cardStatus) {
      return;
    }
    var live = !!latestTemperatures;
    cardStatus.textContent = cardStatusText();
    cardStatus.className = live ? "icu-thermal-status is-live" : "icu-thermal-status";
  }

  // 红外测温开始后，卡片顶部显示红外测温桥的设备编号
  function ensureDeviceBadge() {
    if (!cardCanvas || !cardCanvas.parentNode) {
      return null;
    }
    var badge = cardCanvas.parentNode.querySelector(".icu-thermal-device-badge");
    if (!badge) {
      badge = document.createElement("div");
      badge.className = "icu-thermal-device-badge";
      cardCanvas.parentNode.insertBefore(badge, cardCanvas);
    }
    badge.style.display = "none";
    return badge;
  }

  function cleanText(value) {
    if (value === undefined || value === null) {
      return "";
    }
    return String(value).replace(/\s+/g, " ").trim();
  }

  function readThermalDevice() {
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalDevice === "function") {
        return JSON.parse(window.IcuNative.thermalDevice() || "{}") || {};
      }
    } catch (error) {
      // Native 桥不可用时退回网页侧记录的设备编号
    }
    return {};
  }

  function deviceInfo() {
    var device = readThermalDevice();
    return {
      address: cleanText(device.address) || requestedDeviceId,
      name: cleanText(device.name) || requestedDeviceName,
      connected: !!device.connected
    };
  }

  function hasRecentFrame() {
    return !!lastFrameAt && (Date.now() - lastFrameAt) < RECENT_FRAME_MS;
  }

  function updateDeviceBadge() {
    if (!deviceBadge) {
      return;
    }
    var info = deviceInfo();
    if (!info.address || !(info.connected || hasRecentFrame())) {
      deviceBadge.textContent = "";
      deviceBadge.style.display = "none";
      return;
    }
    deviceBadge.style.display = "flex";
    deviceBadge.textContent = "编号 " + info.address;
  }

  function startDeviceBadgePolling() {
    if (deviceBadgeTimer) {
      return;
    }
    deviceBadgeTimer = window.setInterval(function () {
      updateDeviceBadge();
      updateFullscreenState();
    }, 1000);
  }

  // 红外体温取整帧有效像素的平均值，明显无效的读数不参与平均
  function averageTemperature(temperatures) {
    latestMin = null;
    latestMax = null;
    if (!temperatures || !temperatures.length) {
      return null;
    }
    var sum = 0;
    var count = 0;
    var min = Infinity;
    var max = -Infinity;
    for (var i = 0; i < temperatures.length; i += 1) {
      var value = temperatures[i];
      if (!isFinite(value) || value < -50 || value > 200) {
        continue;
      }
      sum += value;
      count += 1;
      if (value < min) {
        min = value;
      }
      if (value > max) {
        max = value;
      }
    }
    if (!count) {
      return null;
    }
    latestMin = min;
    latestMax = max;
    return sum / count;
  }

  function fullscreenStatusText() {
    var text = connectionState || "等待红外测温桥";
    var info = deviceInfo();
    if (info.address) {
      text += "　编号 " + info.address;
    }
    if (latestTemperatures) {
      text += "　" + temperatureSummary();
    }
    return text;
  }

  function temperatureSummary() {
    if (!latestTemperatures || !latestTemperatures.length) {
      return "";
    }
    var min = latestTemperatures[0];
    var max = latestTemperatures[0];
    for (var i = 1; i < latestTemperatures.length; i += 1) {
      if (latestTemperatures[i] < min) {
        min = latestTemperatures[i];
      }
      if (latestTemperatures[i] > max) {
        max = latestTemperatures[i];
      }
    }
    return "最低 " + min.toFixed(1) + "℃　最高 " + max.toFixed(1) + "℃";
  }

  function updateFullscreenState() {
    if (fullscreenState) {
      fullscreenState.textContent = fullscreenStatusText();
    }
  }

  // -------------------------------------------------------------- 帧处理

  function receiveFrame(base64, sourceCols, sourceRows) {
    if (!base64 || sourceCols !== FRAME_COLS || sourceRows !== FRAME_ROWS) {
      return;
    }
    frameCount += 1;
    pendingFrameBase64 = base64;
    pendingFrameCols = sourceCols;
    pendingFrameRows = sourceRows;
    if (renderScheduled) {
      // 已有渲染排队，本次只覆盖待渲染数据（自动丢中间帧）
      return;
    }
    renderScheduled = true;
    var raf = window.requestAnimationFrame || function (callback) { return window.setTimeout(callback, 16); };
    raf(performScheduledRender);
  }

  function performScheduledRender() {
    renderScheduled = false;
    if (!pendingFrameBase64) {
      return;
    }
    if (document.hidden) {
      return;
    }
    var temperatures = decodeFrame(pendingFrameBase64, pendingFrameCols, pendingFrameRows);
    pendingFrameBase64 = "";
    if (!temperatures) {
      return;
    }
    latestTemperatures = temperatures;
    latestAverage = averageTemperature(temperatures);
    lastFrameAt = Date.now();
    latestWidth = pendingFrameCols;
    latestHeight = pendingFrameRows;
    if (cardContext && cardCanvas) {
      renderHeatmap(cardContext, cardCanvas, temperatures, latestWidth, latestHeight);
    }
    if (fullscreenContext && fullscreenCanvas) {
      renderHeatmap(fullscreenContext, fullscreenCanvas, temperatures, latestWidth, latestHeight);
    }
    updateCardStatus();
    updateFullscreenState();
  }

  function decodeFrame(base64, sourceCols, sourceRows) {
    var bytes;
    try {
      var binary = window.atob(base64);
      if (binary.length !== sourceCols * sourceRows * 2) {
        return null;
      }
      if (!decodeBuffer || decodeBuffer.length !== binary.length) {
        decodeBuffer = new Uint8Array(binary.length);
      }
      for (var i = 0; i < binary.length; i += 1) {
        decodeBuffer[i] = binary.charCodeAt(i);
      }
      bytes = decodeBuffer;
    } catch (error) {
      return null;
    }
    var pixelCount = sourceCols * sourceRows;
    if (!tempBuffer || tempBuffer.length !== pixelCount) {
      tempBuffer = new Float32Array(pixelCount);
    }
    for (var p = 0; p < pixelCount; p += 1) {
      var raw = bytes[p * 2] | (bytes[p * 2 + 1] << 8);
      tempBuffer[p] = (raw & 0x8000 ? raw - 0x10000 : raw) / 100;
    }
    return tempBuffer;
  }

  // -------------------------------------------------------------- 热图渲染

  function renderHeatmap(targetContext, targetCanvas, temperatures, sourceCols, sourceRows) {
    var pixelCount = temperatures.length;
    if (!sortScratch.length || sortScratch.length !== pixelCount) {
      sortScratch = new Array(pixelCount);
    }
    for (var s = 0; s < pixelCount; s += 1) {
      sortScratch[s] = temperatures[s];
    }
    sortScratch.sort(function (a, b) { return a - b; });
    var targetMin = sortScratch[Math.floor((pixelCount - 1) * 0.03)];
    var targetMax = sortScratch[Math.floor((pixelCount - 1) * 0.97)];
    if (targetMax - targetMin < 2.0) {
      targetMax = targetMin + 2.0;
    }
    // 平滑色标范围，避免帧间闪烁
    displayedMin = displayedMin === null ? targetMin : displayedMin * 0.82 + targetMin * 0.18;
    displayedMax = displayedMax === null ? targetMax : displayedMax * 0.82 + targetMax * 0.18;
    if (displayedMax - displayedMin < 2.0) {
      displayedMax = displayedMin + 2.0;
    }
    var range = displayedMax - displayedMin;

    var hottest = temperatures[0];
    var hottestIndex = 0;
    for (var i = 1; i < pixelCount; i += 1) {
      if (temperatures[i] > hottest) {
        hottest = temperatures[i];
        hottestIndex = i;
      }
    }

    if (!rawCanvas) {
      rawCanvas = document.createElement("canvas");
    }
    if (rawCanvas.width !== sourceCols || rawCanvas.height !== sourceRows) {
      rawCanvas.width = sourceCols;
      rawCanvas.height = sourceRows;
      rawContext = rawCanvas.getContext("2d", { alpha: false });
      rawImageData = rawContext.createImageData(sourceCols, sourceRows);
    }
    var r = [0];
    var g = [0];
    var b = [0];
    var data = rawImageData.data;
    for (var py = 0; py < sourceRows; py += 1) {
      var rowOffset = py * sourceCols * 4;
      for (var px = 0; px < sourceCols; px += 1) {
        var ratio = (temperatures[py * sourceCols + px] - displayedMin) / range;
        var index = ratio < 0 ? 0 : ratio > 1 ? 255 : (ratio * 255) | 0;
        r[0] = LUT_R[index];
        g[0] = LUT_G[index];
        b[0] = LUT_B[index];
        var offset = rowOffset + px * 4;
        data[offset] = r[0];
        data[offset + 1] = g[0];
        data[offset + 2] = b[0];
        data[offset + 3] = 255;
      }
    }
    rawContext.putImageData(rawImageData, 0, 0);

    var width = targetCanvas.width;
    var height = targetCanvas.height;
    var scale = canvasPixelScale(targetCanvas);
    var margin = Math.round(Math.min(width, height) * 0.035);
    var imageHeight = height - margin * 2;
    var imageWidth = Math.round(imageHeight * sourceCols / sourceRows);
    if (imageWidth > width - margin * 2) {
      imageWidth = width - margin * 2;
      imageHeight = Math.round(imageWidth * sourceRows / sourceCols);
    }
    var imageX = Math.round((width - imageWidth) / 2);
    var imageY = Math.round((height - imageHeight) / 2);
    targetContext.fillStyle = "#17002a";
    targetContext.fillRect(0, 0, width, height);
    targetContext.imageSmoothingEnabled = true;
    targetContext.imageSmoothingQuality = "high";
    targetContext.drawImage(rawCanvas, imageX, imageY, imageWidth, imageHeight);
    targetContext.strokeStyle = "#00e792";
    targetContext.lineWidth = Math.max(1, Math.round(2 * scale));
    targetContext.strokeRect(imageX, imageY, imageWidth, imageHeight);

    // 最热点十字
    var hotX = imageX + ((hottestIndex % sourceCols) + 0.5) * imageWidth / sourceCols;
    var hotY = imageY + (Math.floor(hottestIndex / sourceCols) + 0.5) * imageHeight / sourceRows;
    var crossSize = Math.max(6, Math.round(10 * scale));
    targetContext.strokeStyle = "#ff5d67";
    targetContext.lineWidth = Math.max(1, Math.round(2 * scale));
    targetContext.beginPath();
    targetContext.moveTo(hotX - crossSize, hotY);
    targetContext.lineTo(hotX + crossSize, hotY);
    targetContext.moveTo(hotX, hotY - crossSize);
    targetContext.lineTo(hotX, hotY + crossSize);
    targetContext.stroke();

    // 中心十字
    var centerX = imageX + imageWidth / 2;
    var centerY = imageY + imageHeight / 2;
    var centerIndex = 11 * sourceCols + 15;
    var centerTemperature = centerIndex < pixelCount ? temperatures[centerIndex] : 0;
    targetContext.strokeStyle = "#ffffff";
    targetContext.lineWidth = Math.max(1, Math.round(1.5 * scale));
    targetContext.beginPath();
    targetContext.moveTo(centerX - crossSize, centerY);
    targetContext.lineTo(centerX + crossSize, centerY);
    targetContext.moveTo(centerX, centerY - crossSize);
    targetContext.lineTo(centerX, centerY + crossSize);
    targetContext.stroke();

    // 全屏视图才画温度数值：卡片太小，文字会挤掉热图本身
    if (targetCanvas === fullscreenCanvas) {
      drawTemperatureLabel(targetContext, "最高 " + hottest.toFixed(1) + "℃",
        hotX + crossSize + 6, hotY - crossSize - 6, "#ff5d67", scale);
      drawTemperatureLabel(targetContext, centerTemperature.toFixed(1) + "℃",
        centerX + crossSize + 6, centerY - crossSize - 6, "#ffffff", scale);
    }
    targetContext.textAlign = "left";
  }

  function drawTemperatureLabel(targetContext, label, x, y, color, scale) {
    var fontSize = Math.max(10, Math.round(24 * scale));
    targetContext.font = "bold " + fontSize + "px sans-serif";
    var textWidth = targetContext.measureText(label).width;
    var padding = Math.round(fontSize * 0.35);
    x = Math.max(padding, Math.min(targetContext.canvas.width - textWidth - padding * 2, x));
    y = Math.max(fontSize + padding, Math.min(targetContext.canvas.height - padding, y));
    targetContext.fillStyle = "rgba(28, 31, 33, 0.78)";
    targetContext.fillRect(x - padding, y - fontSize - padding, textWidth + padding * 2, fontSize + padding * 1.5);
    targetContext.fillStyle = color;
    targetContext.fillText(label, x, y - padding * 0.15);
  }

  function drawPlaceholder(targetContext, targetCanvas) {
    if (!targetContext || !targetCanvas) {
      return;
    }
    var width = targetCanvas.width;
    var height = targetCanvas.height;
    var scale = canvasPixelScale(targetCanvas);
    targetContext.fillStyle = "#111820";
    targetContext.fillRect(0, 0, width, height);
    targetContext.fillStyle = "#f4f8fc";
    targetContext.font = "bold " + Math.max(12, Math.round(20 * scale)) + "px sans-serif";
    targetContext.textAlign = "center";
    targetContext.fillText("红外热图", Math.round(width / 2), Math.round(height / 2));
    targetContext.textAlign = "left";
    targetContext.strokeStyle = "#33495c";
    targetContext.lineWidth = Math.max(1, Math.round(1 * scale));
    targetContext.strokeRect(Math.round(width * 0.06), Math.round(height * 0.12),
      Math.round(width * 0.88), Math.round(height * 0.76));
  }

  // -------------------------------------------------------------- 全屏视图

  function openFullscreen() {
    if (document.getElementById("icu-thermal-fullscreen")) {
      return;
    }
    var overlay = document.createElement("div");
    overlay.id = "icu-thermal-fullscreen";

    var fullCanvas = document.createElement("canvas");
    var ratio = Math.min(window.devicePixelRatio || 1, 1.5);
    fullCanvas.width = Math.max(1, Math.floor(window.innerWidth * ratio));
    fullCanvas.height = Math.max(1, Math.floor(window.innerHeight * ratio));

    var state = document.createElement("div");
    state.className = "icu-thermal-state";
    state.textContent = fullscreenStatusText();

    var close = document.createElement("button");
    close.type = "button";
    close.className = "icu-thermal-close";
    close.setAttribute("aria-label", "关闭红外热图");
    close.textContent = "×";
    close.addEventListener("click", function () { closeFullscreen(overlay); });
    close.addEventListener("touchend", function (event) {
      event.preventDefault();
      event.stopPropagation();
      closeFullscreen(overlay);
    }, { passive: false });

    var connect = document.createElement("button");
    connect.type = "button";
    connect.className = "icu-thermal-connect";
    connect.textContent = "连接红外";
    connect.addEventListener("click", requestConnection);
    connect.addEventListener("touchend", function (event) {
      event.preventDefault();
      event.stopPropagation();
      requestConnection();
    }, { passive: false });

    overlay.appendChild(fullCanvas);
    overlay.appendChild(state);
    overlay.appendChild(close);
    overlay.appendChild(connect);
    document.body.appendChild(overlay);

    fullscreenCanvas = fullCanvas;
    fullscreenContext = fullCanvas.getContext("2d", { alpha: false });
    fullscreenState = state;

    setNativeFullscreen(true);
    if (latestTemperatures) {
      renderHeatmap(fullscreenContext, fullscreenCanvas, latestTemperatures, latestWidth, latestHeight);
    } else {
      drawPlaceholder(fullscreenContext, fullscreenCanvas);
    }
    updateFullscreenState();
  }

  function closeFullscreen(overlay) {
    closeDevicePicker();
    fullscreenCanvas = null;
    fullscreenContext = null;
    fullscreenState = null;
    if (overlay) {
      overlay.remove();
    }
    setNativeFullscreen(false);
  }

  function setNativeFullscreen(visible) {
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalFullscreen === "function") {
        window.IcuNative.thermalFullscreen(visible);
      }
    } catch (error) {
      // Native 桥不可用时忽略，全屏画面仍可正常使用
    }
  }

  // ------------------------------------------------------------ 设备连接

  function requestConnection() {
    openDevicePicker();
    requestDeviceScan();
  }

  function requestDeviceScan() {
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalAction === "function") {
        window.IcuNative.thermalAction("scan", "");
      } else {
        toast("红外测温功能不可用");
      }
    } catch (error) {
      toast("红外测温功能不可用");
    }
  }

  function readThermalDevices() {
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalDevices === "function") {
        return JSON.parse(window.IcuNative.thermalDevices() || "[]") || [];
      }
    } catch (error) {
      // 解析失败时按"没有设备"处理
    }
    return [];
  }

  function openDevicePicker() {
    if (document.getElementById("icu-thermal-device-picker")) {
      return;
    }
    var picker = document.createElement("div");
    picker.id = "icu-thermal-device-picker";
    picker.innerHTML = "<div class='icu-thermal-picker-head'>选择红外测温桥</div>"
      + "<button type='button' class='icu-thermal-picker-close' aria-label='关闭设备列表'>×</button>"
      + "<div class='icu-thermal-device-list'></div>"
      + "<div class='icu-thermal-device-empty'>正在扫描附近设备...</div>";
    var close = picker.querySelector(".icu-thermal-picker-close");
    close.addEventListener("click", function () { closeDevicePicker(); });
    // 设备项的点击用事件委托绑在列表容器上：列表项会被反复复用、增删，
    // 逐个按钮绑定监听器会漏掉用户手指按住期间被替换掉的那一个。
    var list = picker.querySelector(".icu-thermal-device-list");
    list.addEventListener("click", onDeviceListActivate);
    list.addEventListener("touchend", onDeviceListActivate, { passive: false });
    var host = document.getElementById("icu-thermal-fullscreen") || document.body;
    host.appendChild(picker);
    devicePicker = picker;
    deviceListNodes = {};
    connectingDeviceId = "";
    if (devicePickerTimer) {
      window.clearInterval(devicePickerTimer);
    }
    devicePickerTimer = window.setInterval(scheduleDeviceListRefresh, 900);
    refreshDeviceList();
  }

  function closeDevicePicker() {
    if (devicePickerTimer) {
      window.clearInterval(devicePickerTimer);
      devicePickerTimer = null;
    }
    if (deviceRefreshTimer) {
      window.clearTimeout(deviceRefreshTimer);
      deviceRefreshTimer = null;
    }
    if (devicePicker) {
      devicePicker.remove();
      devicePicker = null;
    }
    deviceListNodes = {};
  }

  function refreshDeviceList() {
    if (!devicePicker) {
      return;
    }
    var devices = readThermalDevices();
    var list = devicePicker.querySelector(".icu-thermal-device-list");
    var empty = devicePicker.querySelector(".icu-thermal-device-empty");
    if (!list) {
      return;
    }
    empty.style.display = devices.length ? "none" : "block";
    var alive = {};
    for (var index = 0; index < devices.length; index += 1) {
      var device = devices[index];
      var deviceId = device.id || "";
      if (!deviceId) {
        continue;
      }
      alive[deviceId] = true;
      var item = deviceListNodes[deviceId];
      if (!item) {
        item = document.createElement("button");
        item.type = "button";
        item.className = "icu-thermal-device";
        item.setAttribute("data-device-id", deviceId);
        item.innerHTML = "<strong></strong><span></span>";
        deviceListNodes[deviceId] = item;
        list.appendChild(item);
      }
      var title = item.firstChild;
      var detail = item.lastChild;
      var nameText = device.name || "红外测温桥";
      if (title.textContent !== nameText) {
        title.textContent = nameText;
      }
      var detailText = deviceId + "  ·  " + (device.rssi || 0) + " dBm";
      if (detail.textContent !== detailText) {
        detail.textContent = detailText;
      }
    }
    for (var key in deviceListNodes) {
      if (alive[key]) {
        continue;
      }
      var stale = deviceListNodes[key];
      if (stale && stale.parentNode) {
        stale.parentNode.removeChild(stale);
      }
      delete deviceListNodes[key];
    }
  }

  // 合并短时间内的多次刷新请求，避免扫描高频回调把网页主线程占满
  function scheduleDeviceListRefresh() {
    if (deviceRefreshTimer) {
      return;
    }
    deviceRefreshTimer = window.setTimeout(function () {
      deviceRefreshTimer = null;
      refreshDeviceList();
    }, 200);
  }

  // 设备项点击（事件委托）：从落点往上找到 .icu-thermal-device 即可，
  // 不依赖具体按钮实例，按钮被复用或替换都不影响命中。
  function onDeviceListActivate(event) {
    var node = event.target;
    while (node && node !== document.body && !(node.classList && node.classList.contains("icu-thermal-device"))) {
      node = node.parentNode;
    }
    if (!node || !node.classList || !node.classList.contains("icu-thermal-device")) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    var deviceId = node.getAttribute("data-device-id") || "";
    if (!deviceId || connectingDeviceId === deviceId) {
      return;
    }
    connectingDeviceId = deviceId;
    connectDevice(deviceId);
    closeDevicePicker();
  }

  function connectDevice(deviceId) {
    if (!deviceId) {
      return;
    }
    var devices = readThermalDevices();
    for (var i = 0; i < devices.length; i += 1) {
      var device = devices[i] || {};
      if (cleanText(device.id) === deviceId) {
        requestedDeviceId = deviceId;
        requestedDeviceName = cleanText(device.name);
        break;
      }
    }
    if (!requestedDeviceId) {
      requestedDeviceId = deviceId;
    }
    updateDeviceBadge();
    try {
      if (window.IcuNative && typeof window.IcuNative.thermalAction === "function") {
        window.IcuNative.thermalAction("connect", deviceId);
      }
    } catch (error) {
      toast("连接红外测温桥失败");
    }
  }

  // ---------------------------------------------------------------- 对外 API

  window.IcuThermal = {
    receiveFrame: receiveFrame,
    getMeasurement: function () {
      var info = deviceInfo();
      return {
        hasData: !!latestTemperatures && latestAverage !== null,
        average: latestAverage,
        min: latestMin,
        max: latestMax,
        address: info.address,
        name: info.name,
        connected: info.connected
      };
    },
    setConnectionState: function (state) {
      connectionState = state || "等待红外测温桥";
      updateCardStatus();
      updateDeviceBadge();
      updateFullscreenState();
      if (!latestTemperatures && cardContext && cardCanvas) {
        drawPlaceholder(cardContext, cardCanvas);
      }
    },
    addLog: addLog,
    getLog: function () { return logEntries.slice(); }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initialize);
  } else {
    initialize();
  }
  window.addEventListener("icu-thermal-devices", scheduleDeviceListRefresh);
})();
