(function () {
  var handledAt = 0;
  var handledKey = '';
  var lastActionName = '';
  var lastActionNode = null;
  var lastActionAt = 0;
  var routes = {
    '\u0049\u0043\u0055\u72b6\u6001': '../lanhu_1icuzhuangtaikaobei/index.html',
    '\u52a8\u7269\u6837\u672c': '../lanhu_1icuzhuangtaikaobei/index.html',
    '\u79bb\u5f00': '../lanhu_1icuzhuangtaikaobei/index.html',
    '\u4e3b\u673a\u63a7\u5236': '../lanhu_2zhujikongzhi/index.html',
    '\u5b9e\u65f6\u76d1\u62a4': '../lanhu_3shishijianhu/index.html',
    '\u5b9e\u65f6\u76d1\u63a7': '../lanhu_3shishijianhu/index.html',
    '\u6444\u50cf\u76d1\u63a7': '../lanhu_4jiankong/index.html',
    '\u6cbb\u7597\u8bb0\u5f55': '../lanhu_6zhiliaojilu/index.html',
    '\u4f7f\u7528\u6559\u7a0b': '../lanhu_7jiaocheng/index.html',
    '\u8bbe\u7f6e': '../lanhu_81shezhi/index.html',
    '\u8fde\u63a5\u72b6\u6001': '../lanhu_84lianjiezhuangtai/index.html',
    '\u5b9e\u51b5': '../lanhu_4jiankong/index.html',
    '\u56de\u653e': '../lanhu_5jiankonghuifang/index.html'
  };
  var assetBase = (function () {
    var script = document.currentScript;
    var url = script && script.src ? script.src : '';
    var cut = url.lastIndexOf('/');
    if (cut > 0) {
      return url.substring(0, cut + 1);
    }
    return 'file:///android_asset/lanhu/';
  })();

  function injectStylesheet(name) {
    if (document.querySelector('link[href*="' + name + '"]')) {
      return;
    }
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = assetBase + name;
    document.head.appendChild(link);
  }

  function injectScript(name) {
    if (document.querySelector('script[src*="' + name + '"]')) {
      return;
    }
    var script = document.createElement('script');
    script.src = assetBase + name;
    script.async = false;
    document.head.appendChild(script);
  }

  function installOverlayModules() {
    injectStylesheet('lanhu-overlay.css');
    injectScript('lanhu-overlay.js');
    injectStylesheet('lanhu-ble-panel.css');
    injectScript('lanhu-ble-panel.js');
    injectStylesheet('lanhu-tutorial.css');
    injectScript('lanhu-tutorial.js');
  }

  var SETTINGS_PAGES = ['lanhu_81shezhi', 'lanhu_82shezhi', 'lanhu_85buchangshezhi', 'lanhu_86qita', 'lanhu_87guanyu', 'lanhu_88guanliyuan'];

  function pageKey(path) {
    var match = (path || '').match(/src\/views\/([^/]+)\//);
    return match ? match[1] : '';
  }

  function compact(value) {
    return (value || '').replace(/\s+/g, '').trim();
  }

  function routeKey(route) {
    var match = (route || '').match(/(lanhu_[0-9a-z]+)/);
    return match ? match[1] : '';
  }

  var navTimingSeq = 0;

  function navTimingLog(message) {
    try {
      if (window.IcuNative && typeof window.IcuNative.log === 'function') {
        window.IcuNative.log(message);
      }
    } catch (error) {
      // 原生日志不可用时不影响跳转
    }
  }

  // 整页导航的唯一计时入口：navId 只含时间基数与序号，不含账号、病例或设备信息。
  function timedNavigationUrl(route) {
    navTimingSeq += 1;
    var navId = Date.now().toString(36) + '-' + navTimingSeq;
    var startedAt = Date.now();
    var separator = route.indexOf('?') >= 0 ? '&' : '?';
    navTimingLog('[导航计时] 开始 id=' + navId + ' to=' + routeKey(route)
      + ' at=' + startedAt);
    return route + separator + 'icuNavId=' + encodeURIComponent(navId)
      + '&icuNavAt=' + startedAt
      + '&icuNavTo=' + encodeURIComponent(routeKey(route));
  }

  // 相对路径永远无法与 location.href 直接匹配，必须按页面标识判断是否已在当前页
  function isCurrentRoute(route) {
    var target = routeKey(route);
    if (!target) {
      return false;
    }
    return target === pageKey(window.location.pathname || '');
  }

  function showCurrentPageHint(text) {
    var label = compact(text || '');
    var message = label ? '已在「' + label + '」页面' : '已在当前页面';
    if (window.IcuToast && typeof window.IcuToast.show === 'function') {
      window.IcuToast.show(message);
      return true;
    }
    if (window.IcuNative && typeof window.IcuNative.action === 'function') {
      window.IcuNative.action('current_page_hint', message, window.location.pathname || '');
      return true;
    }
    return false;
  }

  function directText(node) {
    if (!node || !node.childNodes) {
      return '';
    }
    var text = '';
    for (var i = 0; i < node.childNodes.length; i += 1) {
      if (node.childNodes[i].nodeType === 3) {
        text += node.childNodes[i].nodeValue || '';
      }
    }
    return compact(text);
  }

  function actionText(node) {
    if (!node) {
      return '';
    }
    var text = directText(node);
    if (text) {
      return text;
    }
    if (node.getAttribute) {
      text = compact(node.getAttribute('aria-label') || node.getAttribute('title') || node.getAttribute('alt'));
      if (text) {
        return text;
      }
    }
    return compact(node.innerText || node.textContent);
  }

  function pointInRect(point, rect) {
    return !!rect && point.x >= rect.left && point.x <= rect.right && point.y >= rect.top && point.y <= rect.bottom;
  }

  function elementContainsPoint(selector, point) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      if (!nodes[i].getBoundingClientRect) {
        continue;
      }
      var rect = nodes[i].getBoundingClientRect();
      if (rect.width > 1 && rect.height > 1 && pointInRect(point, rect)) {
        return true;
      }
    }
    return false;
  }

  var TOUCH_DRIFT_TOLERANCE = 6;
  var touchDriftReported = null;
  var lastTouchViewportKey = '';
  var touchDebug = {
    browserX: -1,
    browserY: -1,
    nativeX: -1,
    nativeY: -1,
    driftX: 0,
    driftY: 0,
    scale: 0,
    source: '',
    corrected: false
  };
  window.IcuTouchDebug = touchDebug;

  function layoutScale() {
    var value = '1';
    try {
      value = window.getComputedStyle(document.documentElement).getPropertyValue('--lanhu-scale') || '1';
    } catch (error) {
    }
    var scale = parseFloat(value);
    return scale > 0 ? scale : 1;
  }


  // 页面整体按 --lanhu-scale 缩放后，设计像素与视口像素不再是一比一：
  // 设计像素乘缩放才是视口像素，视口像素除缩放才是设计像素。
  // 命中判定必须在同一个坐标系内完成，否则缩放不等于 1 时，判定矩形与点击坐标
  // 会按不同比例拉伸，表现为所有控件的点击范围整体偏移。
  function designToViewport(designPixels) {
    return designPixels * layoutScale();
  }

  function viewportToDesign(viewportPixels) {
    return viewportPixels / layoutScale();
  }

  function elementFromPoint(point) {
    if (!document.elementFromPoint || point.x < 0 || point.y < 0) {
      return null;
    }
    try {
      return document.elementFromPoint(point.x, point.y);
    } catch (error) {
      return null;
    }
  }

  function nativeTouchGeometry() {
    if (!window.IcuNative || typeof window.IcuNative.touchGeometry !== 'function') {
      return null;
    }
    var raw = '';
    try {
      raw = window.IcuNative.touchGeometry();
    } catch (error) {
      return null;
    }
    if (!raw) {
      return null;
    }
    var data = null;
    try {
      data = JSON.parse(raw);
    } catch (error) {
      return null;
    }
    if (!data || !(data.viewWidth > 0)) {
      return null;
    }
    var hasLocal = typeof data.localX === 'number' && data.localX >= 0 &&
      typeof data.localY === 'number' && data.localY >= 0;
    var hasRaw = typeof data.rawX === 'number' && data.rawX >= 0 &&
      typeof data.rawY === 'number' && data.rawY >= 0;
    if (!hasLocal && !hasRaw) {
      return null;
    }
    return data;
  }

  function reportTouchDrift(driftX, driftY, scale, source) {
    if (touchDriftReported &&
      Math.abs(driftX - touchDriftReported.x) <= 2 && Math.abs(driftY - touchDriftReported.y) <= 2) {
      return;
    }
    touchDriftReported = { x: driftX, y: driftY };
    var message = 'icu touch drift dx=' + driftX.toFixed(1) + ' dy=' + driftY.toFixed(1) +
      ' scale=' + (scale > 0 ? scale.toFixed(3) : '?') + ' src=' + (source || '?');
    if (window.console && console.warn) {
      console.warn(message);
    }
    if (window.IcuNative && typeof window.IcuNative.log === 'function') {
      try {
        window.IcuNative.log(message);
      } catch (error) {
      }
    }
  }

  // 渲染器实际使用的"设备像素 : CSS 像素"比例。devicePixelRatio 与视觉视口缩放和画面渲染、
  // 浏览器命中判定完全同源；系统 density 只是近似值，在分数密度、页面缩放、分屏/自由窗口下
  // 二者并不相等，用 density 换算出的落点会整体偏斜且偏差随坐标放大，
  // 表现为画面渲染正常、但所有控件的真实点击范围整体向右下错位。
  function browserRenderScale() {
    var dpr = typeof window.devicePixelRatio === 'number' ? window.devicePixelRatio : 0;
    if (!(dpr > 0)) {
      return 0;
    }
    var zoom = 1;
    if (window.visualViewport && typeof window.visualViewport.scale === 'number' && window.visualViewport.scale > 0) {
      zoom = window.visualViewport.scale;
    }
    // 页面被缩放后 devicePixelRatio 与视觉视口缩放不再是固定倍数，比例无法由 dpr 单独确定，
    // 此时放弃 dpr 比例，改由后续的两次采样确认去判定，避免按错误比例纠偏。
    return Math.abs(zoom - 1) < 0.01 ? dpr : 0;
  }

  function nativeCssScale(geometry, viewportWidth) {
    var render = browserRenderScale();
    if (render > 0) {
      return { scale: render, source: 'dpr' };
    }
    var ratio = (geometry.viewWidth > 0 && viewportWidth > 0) ? geometry.viewWidth / viewportWidth : 0;
    if (ratio > 0) {
      return { scale: ratio, source: 'viewport' };
    }
    var density = typeof geometry.density === 'number' ? geometry.density : 0;
    if (density > 0.5 && density < 8) {
      return { scale: density, source: 'density' };
    }
    return null;
  }

  function nativePointFromGeometry(geometry, viewportWidth, viewportHeight) {
    var scaleInfo = nativeCssScale(geometry, viewportWidth);
    if (!scaleInfo) {
      return null;
    }
    var x = -1;
    var y = -1;
    if (typeof geometry.localX === 'number' && geometry.localX >= 0 &&
      typeof geometry.localY === 'number' && geometry.localY >= 0) {
      x = geometry.localX / scaleInfo.scale;
      y = geometry.localY / scaleInfo.scale;
    } else if (typeof geometry.rawX === 'number' && geometry.rawX >= 0 &&
      typeof geometry.originX === 'number' && typeof geometry.originY === 'number') {
      x = (geometry.rawX - geometry.originX) / scaleInfo.scale;
      y = (geometry.rawY - geometry.originY) / scaleInfo.scale;
    }
    if (!isFinite(x) || !isFinite(y) || x < 0 || y < 0) {
      return null;
    }
    if (x > viewportWidth + TOUCH_DRIFT_TOLERANCE || y > viewportHeight + TOUCH_DRIFT_TOLERANCE) {
      return null;
    }
    return { x: x, y: y, scale: scaleInfo.scale, source: scaleInfo.source };
  }


  // 命中定位一律以浏览器自身上报的坐标为准。
  // 浏览器把 clientX/clientY 换算到视口坐标时已经处理了页面缩放与窗口偏移，
  // 其命中结果与 getBoundingClientRect() 天然同源；若再用原生坐标"纠偏"，
  // 一旦换算比例取错、或读到的是上一次触点的快照，就会把整片点击范围带偏，
  // 表现为所有控件的真实点击范围整体错位。原生几何只用于发现并记录平台级偏移。
  function computeTouchPoint(browserPoint) {
    var point = {
      x: browserPoint.x,
      y: browserPoint.y,
      corrected: false,
      nativeX: -1,
      nativeY: -1,
      driftX: 0,
      driftY: 0,
      scale: 0,
      source: ''
    };
    var geometry = nativeTouchGeometry();
    if (!geometry) {
      return point;
    }
    var viewportWidth = document.documentElement.clientWidth || window.innerWidth || 0;
    var viewportHeight = document.documentElement.clientHeight || window.innerHeight || 0;
    if (!(viewportWidth > 0) || !(viewportHeight > 0)) {
      return point;
    }
    var viewportKey = Math.round(viewportWidth) + 'x' + Math.round(viewportHeight);
    if (lastTouchViewportKey && lastTouchViewportKey !== viewportKey) {
      // 视口尺寸刚发生变化（旋转、分屏、键盘），此刻两套坐标系可能处于过渡态，本轮只记录不比较
      lastTouchViewportKey = viewportKey;
      return point;
    }
    lastTouchViewportKey = viewportKey;
    var nativePoint = nativePointFromGeometry(geometry, viewportWidth, viewportHeight);
    if (!nativePoint) {
      return point;
    }
    point.nativeX = nativePoint.x;
    point.nativeY = nativePoint.y;
    point.scale = nativePoint.scale;
    point.source = nativePoint.source;
    if (browserPoint.x < 0 || browserPoint.y < 0) {
      // 事件没有携带坐标时只能退回到原生坐标
      point.x = nativePoint.x;
      point.y = nativePoint.y;
      point.corrected = true;
      return point;
    }
    point.driftX = nativePoint.x - browserPoint.x;
    point.driftY = nativePoint.y - browserPoint.y;
    var drift = Math.sqrt(point.driftX * point.driftX + point.driftY * point.driftY);
    if (drift > TOUCH_DRIFT_TOLERANCE) {
      reportTouchDrift(point.driftX, point.driftY, nativePoint.scale, nativePoint.source);
    }
    return point;
  }

  function resolveTouchPoint(event) {
    var browserPoint = browserTouchPoint(event);
    var resolution = computeTouchPoint(browserPoint);
    touchDebug.browserX = browserPoint.x;
    touchDebug.browserY = browserPoint.y;
    touchDebug.nativeX = resolution.nativeX;
    touchDebug.nativeY = resolution.nativeY;
    touchDebug.driftX = resolution.driftX;
    touchDebug.driftY = resolution.driftY;
    touchDebug.scale = resolution.scale;
    touchDebug.source = resolution.source;
    touchDebug.corrected = resolution.corrected;
    return { x: resolution.x, y: resolution.y, corrected: resolution.corrected };
  }

  function setAttr(selector, name, value) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].setAttribute(name, value);
      nodes[i].style.cursor = 'pointer';
      nodes[i].style.touchAction = 'manipulation';
    }
  }

  function findUsefulContainer(node) {
    var current = node;
    var steps = 0;
    while (current && current !== document.body && steps < 5) {
      if (!current.getBoundingClientRect) {
        break;
      }
      var rect = current.getBoundingClientRect();
      if (viewportToDesign(rect.width) >= 48 && viewportToDesign(rect.height) >= 24) {
        return current;
      }
      current = current.parentNode;
      steps += 1;
    }
    return node;
  }

  function setTextContainerAction(matchText, action) {
    var nodes = document.querySelectorAll('span,button,div');
    for (var i = 0; i < nodes.length; i += 1) {
      if (nodes[i].tagName !== 'BUTTON' && nodes[i].children && nodes[i].children.length) {
        continue;
      }
      // 隐藏容器里的同名文字不能作为命中依据：找不到可见容器时属性会被标到外层大块上，
      // 摄像区域 .block_7 就是这样被误标成“下载”，整个画面区域的点击都会被劫持。
      if (nodes[i].getClientRects && nodes[i].getClientRects().length === 0) {
        continue;
      }
      // 摄像回放面板自带点击处理，不能被这里的同名文字抢走
      if (nodes[i].closest && nodes[i].closest('.icu-camera-playback-panel')) {
        continue;
      }
      var text = actionText(nodes[i]);
      if (!text || text.length > Math.max(18, matchText.length + 8)) {
        continue;
      }
      if (text === matchText || text.indexOf(matchText) >= 0) {
        var container = findUsefulContainer(nodes[i].parentNode || nodes[i]);
        if (container && container.setAttribute) {
          container.setAttribute('data-native-action', action);
          container.style.cursor = 'pointer';
          container.style.touchAction = 'manipulation';
        }
      }
    }
  }

  // 入口按钮文案由「预览」改成「预览打印」：点进去是 PDF 预览，预览页最下面才是「打印」。
  // 蓝湖导出的页面文件保持原样，这里只在运行时改文案并放宽标签
  // （蓝湖给的是 30px 定宽，只放得下两个字，四个字会被裁掉）。
  function renamePreviewEntry() {
    var spans = document.querySelectorAll('span');
    for (var i = 0; i < spans.length; i += 1) {
      var span = spans[i];
      if (span.children && span.children.length) {
        continue;
      }
      if (compact(span.textContent) !== '预览') {
        continue;
      }
      var wrapper = span.parentNode;
      if (!wrapper || !wrapper.className || String(wrapper.className).indexOf('text-wrapper') !== 0) {
        continue;
      }
      span.textContent = '预览打印';
      span.style.width = '64px';
      span.style.marginLeft = '8px';
    }
  }

  function installWideHitAreas() {
    renamePreviewEntry();
    setTextContainerAction('编辑', 'patient_edit');
    setTextContainerAction('删除', 'patient_delete');
    setTextContainerAction('预览', 'pdf_preview');
    setTextContainerAction('下载', 'pdf_download');
    setAttr('.text_4,.thumbnail_2', 'data-native-action', 'account_menu');

    var path = window.location.pathname || '';
    if (path.indexOf('lanhu_1icuzhuangtaikaobei') >= 0) {
      setAttr('.section_1 > .box_5', 'data-native-action', 'organization_switch');
    } else if (path.indexOf('lanhu_2zhujikongzhi') >= 0) {
      setAttr('.block_2 > .block_4', 'data-native-action', 'organization_switch');
    } else if (path.indexOf('lanhu_3shishijianhu') >= 0) {
      setAttr('.box_3 > .group_5', 'data-native-action', 'organization_switch');
    } else if (path.indexOf('lanhu_4jiankong') >= 0) {
      setAttr('.group_1 > .box_4', 'data-native-action', 'organization_switch');
      setAttr('.text_64,.text_65,.text_66,.text_67,.text_68,.text_69,.text_71,.text_72', 'data-native-action', 'camera_feature');
      setAttr('.image-wrapper_1,.image-wrapper_2,.image-wrapper_3,.image-wrapper_4,.image-wrapper_5,.image-wrapper_6,.image-wrapper_8,.image-wrapper_9', 'data-native-action', 'camera_feature');
    } else if (path.indexOf('lanhu_5jiankonghuifang') >= 0) {
      setAttr('.group_2 > .box_4', 'data-native-action', 'organization_switch');
    } else if (path.indexOf('lanhu_6zhiliaojilu') >= 0) {
      setAttr('.group_1 > .section_2', 'data-native-action', 'organization_switch');
    }

    if (path.indexOf('lanhu_2zhujikongzhi') >= 0) {
      setAttr('.box_8', 'data-native-action', 'control_temp');
      setAttr('.box_9', 'data-native-action', 'control_oxygen');
      setAttr('.box_15', 'data-native-action', 'control_co2');
      setAttr('.group_18', 'data-native-action', 'control_red');
      setAttr('.group_21', 'data-native-action', 'control_blue');
      setAttr('.group_26', 'data-native-action', 'control_uv');
      setAttr('.box_35', 'data-native-action', 'control_nebulizer');
      setAttr('.box_39', 'data-native-action', 'control_anion');
      setAttr('.text_93', 'data-native-action', 'control_red_time');
      setAttr('.text_94', 'data-native-action', 'control_blue_time');
      setAttr('.text_96', 'data-native-action', 'control_uv_time');
      setAttr('.text_99', 'data-native-action', 'control_nebulizer_time');
      setAttr('.text_102', 'data-native-action', 'control_anion_time');
      setAttr('.block_31', 'data-native-action', 'control_cold_light');
      setAttr('.section_42', 'data-native-action', 'control_warm_light');
      setAttr('.group_99', 'data-native-action', 'control_outer');
      setAttr('.group_101', 'data-native-action', 'control_inner');
      // 治疗时长只读展示，暂不开放“设置”入口。
      setAttr('.group_45,.section_41', 'data-native-action', 'host_mode_config_mother');
      setAttr('.section_20,.box_78', 'data-native-action', 'host_mode_config_postop');
      setAttr('.group_55,.box_79', 'data-native-action', 'host_mode_config_cardio');
      setAttr('.group_62,.box_80', 'data-native-action', 'host_mode_config_custom');
    }

    if (path.indexOf('lanhu_3shishijianhu') >= 0) {
      setAttr('.group_16,.image-text_14', 'data-native-action', 'monitor_ble');
      setAttr('.group_18,.image-text_16', 'data-native-action', 'monitor_alarm_sound');
      setAttr('.group_19,.image-text_17', 'data-native-action', 'monitor_limits');
      setAttr('.group_20,.image-text_18', 'data-native-action', 'monitor_leave');
    }
  }

  // 左右舱隔离：当前舱由原生 host.zone 驱动，页面上的舱位按钮和按舱缓存都要跟着换。
  // 切舱是异步的：网页点下按钮时原生那边还没切过来，这段时间用 pendingZone 顶着，
  // 等原生真正切过来（reconcileNativeZone）再把 pending 收掉。
  var pendingZone = '';
  var pendingZoneAt = 0;
  var PENDING_ZONE_TIMEOUT_MS = 3000;

  function normalizeZoneText(value) {
    var zone = String(value === undefined || value === null ? '' : value).toLowerCase();
    return zone === 'left' ? 'left' : 'right';
  }

  function currentZone() {
    return normalizeZoneText(window.__icuCurrentZone);
  }

  /**
   * 用原生上报的舱位校准网页里的当前舱。
   * pendingZone 表示"网页已经请求切舱、原生还没执行"，
   * 这段时间原生报的还是旧舱，不能把网页切回旧舱，
   * 否则刚点完切舱就会把另一舱的数据刷出来。
   */
  function reconcileNativeZone(reportedZone) {
    var zone = String(reportedZone || '').toLowerCase();
    if (zone !== 'left' && zone !== 'right') {
      return;
    }
    if (pendingZone) {
      if (zone === pendingZone) {
        pendingZone = '';
        pendingZoneAt = 0;
      } else if (Date.now() - pendingZoneAt > PENDING_ZONE_TIMEOUT_MS) {
        // 原生没有执行这次切舱，以原生为准，别让网页停在错的舱上
        pendingZone = '';
        pendingZoneAt = 0;
      } else {
        return;
      }
    }
    if (zone !== window.__icuCurrentZone) {
      window.__icuCurrentZone = zone;
      syncZoneSwitchState();
      notifyZoneChanged();
    }
  }

  /** 监护等级按舱保存，左舱和右舱各自独立。 */
  function monitorLevelStorageKey() {
    return 'icu.monitor.level.' + currentZone();
  }

  /** 监护等级待主机回读确认的本地选择。 */
  function pendingMonitorLevelStorageKey() {
    return 'icu.monitor.level.pending.' + currentZone();
  }

  function topNavElement() {
    var pageRoot = document.querySelector('.page') || document.body;
    if (!pageRoot || !pageRoot.querySelector) {
      return null;
    }
    // 顶栏容器在各个页面的类名并不一致（box_1 / block_1 ...），
    // 但账号头像 .label_2 始终是顶栏里最靠右的元素，用它的父节点定位最稳妥。
    var avatar = pageRoot.querySelector('.label_2');
    if (avatar && avatar.parentNode) {
      return avatar.parentNode;
    }
    var account = pageRoot.querySelector('.text_4[data-account-live]');
    if (account && account.parentNode) {
      return account.parentNode;
    }
    return pageRoot.querySelector('.box_1.flex-row') || pageRoot.querySelector('.box_1');
  }

  /** 设置页、登录页没有舱位概念，不显示切换按钮。 */
  function zoneSwitchVisible() {
    var path = window.location.pathname || '';
    if (path.indexOf('/src/views/lanhu_') < 0) {
      return false;
    }
    return path.indexOf('lanhu_8') < 0
      && path.indexOf('lanhu_denglu') < 0;
  }

  function syncZoneSwitchState() {
    var bar = document.getElementById('icu-zone-switch');
    if (!bar) {
      return;
    }
    var zone = currentZone();
    var buttons = bar.querySelectorAll('.icu-zone-button');
    for (var i = 0; i < buttons.length; i += 1) {
      buttons[i].classList.toggle('active', buttons[i].getAttribute('data-native-action') === 'tablet_zone_' + zone);
    }
  }

  window.__icuZoneChanged = function () {
    syncZoneSwitchState();
    // 通知各模块：舱位换了，按舱缓存的数据要换成新舱的
    try {
      window.dispatchEvent(new CustomEvent('icu-zone-changed'));
    } catch (error) {
    }
  };

  /** 通知各模块：舱位变了。 */
  function notifyZoneChanged() {
    if (typeof window.__icuZoneChanged === 'function') {
      window.__icuZoneChanged(window.__icuCurrentZone);
    }
  }

  /** 读取原生当前舱位。 */
  function nativeZone() {
    if (!window.IcuNative || typeof window.IcuNative.bleState !== 'function') {
      return '';
    }
    try {
      var state = JSON.parse(window.IcuNative.bleState() || '{}') || {};
      var zone = state.host ? String(state.host.zone || '') : '';
      return zone === 'left' || zone === 'right' ? zone : '';
    } catch (error) {
      return '';
    }
  }

  /**
   * 实时监护、治疗记录这些页面没有实时数据节点，不会去读原生状态，
   * 这里主动把原生舱位同步到网页，保证按舱保存的数据（监护等级等）落在正确的舱。
   */
  function applyNativeZone() {
    reconcileNativeZone(nativeZone());
  }

  // 供实时监护、治疗记录等模块把原生舱位交给桥接层统一对账
  window.__icuReconcileZone = reconcileNativeZone;

  /**
   * 在顶栏右上角插入左右舱切换按钮。
   * 原来的"控制舱"字样按需求去掉了，只保留左舱 / 右舱两个切换按钮，
   * 监护等级等按舱保存的数据也跟着按钮上的选中态一起切换。
   */
  function installZoneSwitch() {
    var nav = zoneSwitchVisible() ? topNavElement() : null;
    if (nav) {
      var bar = document.getElementById('icu-zone-switch');
      if (!bar) {
        bar = document.createElement('div');
        bar.id = 'icu-zone-switch';
        bar.className = 'icu-zone-switch';
        bar.innerHTML = '<button type="button" class="icu-zone-button" data-native-action="tablet_zone_left">左舱</button>'
          + '<button type="button" class="icu-zone-button" data-native-action="tablet_zone_right">右舱</button>';
        nav.appendChild(bar);
      }
      var avatar = nav.querySelector('.label_2');
      if (avatar && avatar.parentNode === nav) {
        if (bar.parentNode !== nav || bar.nextSibling !== avatar) {
          nav.insertBefore(bar, avatar);
        }
        // 头像原本靠外边距把账号区顶到最右侧，这里把这段外边距让给舱位按钮，
        // 按钮就紧挨在头像左边，账号区也不会被挤出屏幕。
        avatar.style.marginLeft = '0px';
      }
      // 设备时间排在舱位按钮左边（时间在左、按钮在右），
      // 桥接层和时钟模块谁先加载都要保持这个顺序。
      var clock = document.getElementById('icu-device-clock');
      if (clock && clock.parentNode === nav && clock.nextSibling !== bar) {
        nav.insertBefore(clock, bar);
      }
      // 时钟还没插进来时由舱位按钮自己顶到最右侧，时钟到位后交回给时钟，
      // 避免顶栏右侧出现一大片空白。
      if (clock) {
        bar.classList.remove('icu-no-clock');
      } else {
        bar.classList.add('icu-no-clock');
      }
    }
    applyNativeZone();
    syncZoneSwitchState();
  }

  function hideReportTemplateNavigation() {
    var nodes = document.querySelectorAll('span,div');
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      if (node.children && node.children.length) {
        continue;
      }
      var text = compact(node.textContent || '');
      if (text === '报告模版-默认' || text === '报告模板-默认') {
        node.style.display = 'none';
        node.style.visibility = 'hidden';
      }
    }
  }

  function isCurrentTreatmentSelected() {
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.isCurrentSelected === 'function') {
      return window.IcuPatientTemp.isCurrentSelected();
    }
    if (window.IcuNative && typeof window.IcuNative.firstPhaseState === 'function') {
      try {
        var state = JSON.parse(window.IcuNative.firstPhaseState() || '{}') || {};
        return !state.patient || state.patient.currentTreatment !== false;
      } catch (error) {
      }
    }
    return true;
  }

  function selectCurrentTreatment() {
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.selectCurrentTreatment === 'function') {
      window.IcuPatientTemp.selectCurrentTreatment();
    }
    if (window.IcuNative && typeof window.IcuNative.action === 'function') {
      window.IcuNative.action('select_current_treatment', '切回当前治疗', window.location.pathname || '');
    }
  }

  function ensureCurrentTreatmentForRoute(text) {
    if (text !== '主机控制' && text !== '实时监护') {
      return true;
    }
    if (isCurrentTreatmentSelected()) {
      return true;
    }
    selectCurrentTreatment();
    return true;
  }

  function isSmallActionTarget(node, text) {
    if (!text || text.length > 28 || !node.getBoundingClientRect) {
      return false;
    }
    var rect = node.getBoundingClientRect();
    if (!rect || rect.width < 4 || rect.height < 4) {
      return false;
    }
    var designWidth = viewportToDesign(rect.width);
    var designHeight = viewportToDesign(rect.height);
    if (designWidth > 520 || designHeight > 180) {
      return false;
    }
    return designWidth * designHeight <= 65000;
  }

  function hasClassInPath(node, classNames) {
    while (node && node !== document.body) {
      if (node.classList) {
        for (var i = 0; i < classNames.length; i += 1) {
          if (node.classList.contains(classNames[i])) {
            return true;
          }
        }
      }
      node = node.parentNode;
    }
    return false;
  }

  function hostControlActionFromNode(node) {
    var map = [
      { cls: 'box_8', action: 'control_temp' },
      { cls: 'box_9', action: 'control_oxygen' },
      { cls: 'box_15', action: 'control_co2' },
      { cls: 'group_18', action: 'control_red_time' },
      { cls: 'group_21', action: 'control_blue_time' },
      { cls: 'group_26', action: 'control_uv_time' },
      { cls: 'box_35', action: 'control_nebulizer_time' },
      { cls: 'box_39', action: 'control_anion_time' },
      { cls: 'block_31', action: 'control_cold_light' },
      { cls: 'section_42', action: 'control_warm_light' },
      { cls: 'group_99', action: 'control_outer' },
      { cls: 'group_101', action: 'control_inner' }
    ];
    for (var i = 0; i < map.length; i += 1) {
      if (hasClassInPath(node, [map[i].cls])) {
        return map[i].action;
      }
    }
    return '';
  }

  function inlineSearchInputFromNode(node) {
    while (node && node !== document.body) {
      if (node.getAttribute && node.getAttribute('data-icu-search-box') === '1' && node.querySelector) {
        return node.querySelector('.icu-patient-search-input');
      }
      node = node.parentNode;
    }
    return null;
  }

  function insideOverlay(node) {
    while (node && node !== document.body) {
      if (node.classList && (node.classList.contains('icu-patient-modal') || node.classList.contains('icu-overlay') || node.classList.contains('icu-banner'))) {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  // 登录/注册页的表单与按钮由页面自己处理：桥接不能拦截，
  // 否则输入框点不进去，按钮也会被坐标推算劫持成别的动作。
  function insideLoginForm(node) {
    while (node && node !== document.body) {
      // 原版登录页的表单与注册面板
      if (node.id === 'icuLoginForm' || node.id === 'icuRegisterPanel') {
        return true;
      }
      if (node.classList && node.classList.contains('icu-login-card')) {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  // 红外热图区域（状态页卡片、全屏浮层、设备列表）自带点击处理，
  // 不能被下面的坐标推算劫持，否则点热图会触发页面上别的控件。
  function insideThermalSurface(node) {
    while (node && node !== document.body) {
      if (node.id === 'icu-thermal-fullscreen' || node.id === 'icu-thermal-device-picker') {
        return true;
      }
      if (node.classList && node.classList.contains('icu-thermal-card')) {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  function focusInlineSearch(node, event) {
    var input = inlineSearchInputFromNode(node);
    if (!input) {
      return false;
    }
    if (node !== input && typeof input.focus === 'function') {
      input.focus();
      stopEvent(event);
    }
    return true;
  }

  function nativeActionFromText(text, path, node) {
    if (inlineSearchInputFromNode(node)) {
      return '';
    }
    var dataAction = nativeActionFromData(node);
    if (dataAction) return dataAction;
    if (!text) {
      return '';
    }
    var page = pageKey(path);
    if (page === 'lanhu_denglu') {
      if (text === '登录') {
        return 'app_login';
      }
      if (text.indexOf('新用户注册') >= 0) {
        return 'settings_not_available';
      }
    }
    if (SETTINGS_PAGES.indexOf(page) >= 0) {
      if (text === '保存') {
        if (page === 'lanhu_81shezhi') {
          return 'organization_save';
        }
        if (page === 'lanhu_82shezhi') {
          return 'device_profile_save';
        }
        return 'settings_not_available';
      }
      if (text === '取消') {
        return 'settings_cancel';
      }
      if (text === '+' || text.indexOf('升级') >= 0) {
        return page === 'lanhu_81shezhi' ? 'organization_logo_pick' : 'settings_not_available';
      }
      if (page === 'lanhu_81shezhi') {
        if (text.indexOf('医院名称') >= 0 || text.indexOf('医院地址') >= 0 || text.indexOf('医院电话') >= 0) {
          return 'settings_not_available';
        }
        if (text.indexOf('LOGO') >= 0) {
          return 'organization_logo_pick';
        }
      }
      if (page === 'lanhu_88guanliyuan' && (text.indexOf('管理员账户') >= 0 || text.indexOf('密码') >= 0)) {
        return 'settings_not_available';
      }
    }
    if (text === '登录' || text.indexOf('退出登录') >= 0 || text.indexOf('当前账号') >= 0) return 'account_menu';
    if (text === '\u7f16\u8f91' && hasClassInPath(node, ['text_50', 'text-wrapper_18'])) return 'patient_edit';
    if (text === '\u5220\u9664' && hasClassInPath(node, ['text_51', 'text-wrapper_19'])) return 'patient_delete';
    if (text.indexOf('\u9884\u89c8') >= 0 && hasClassInPath(node, ['text_52', 'text-wrapper_20'])) return 'pdf_preview';
    if (text.indexOf('\u4e0b\u8f7d') >= 0 && hasClassInPath(node, ['text_53', 'text-wrapper_21'])) return 'pdf_download';
    if (path.indexOf('lanhu_2zhujikongzhi') >= 0 && text.indexOf('\u8bbe\u7f6e') >= 0) {
      return hostControlActionFromNode(node);
    }
    if (path.indexOf('lanhu_4jiankong') >= 0) {
      if (text.indexOf('\u56de\u653e') >= 0) return 'camera_playback';
      if (text.indexOf('\u5b9e\u51b5') >= 0) return 'camera_live';
      // 配置摄像头按钮、摄像回放面板和未连接引导都浮在画面区域里面，必须先于画面命中规则判定，
      // 否则会被 .block_7 的画面规则劫持成打开全屏预览，点配置就打不开对话框。
      if (hasClassInPath(node, ['icu-camera-config-button', 'icu-camera-playback-panel', 'icu-camera-empty-state'])) {
        if (text.indexOf('\u914d\u7f6e\u6444\u50cf\u5934') >= 0) return 'camera_config';
        if (text.indexOf('\u4e0b\u8f7d') >= 0) return 'camera_snapshot_download';
        if (text.indexOf('\u5220\u9664') >= 0) return 'camera_snapshot_delete';
        if (text.indexOf('\u622a\u56fe') >= 0) return 'camera_snapshot';
      }
      if (hasClassInPath(node, ['block_7', 'image_8'])) return 'camera_preview_tap';
      if (hasClassInPath(node, ['box_18', 'image-text_14', 'label_3', 'text-group_2'])) return 'camera_snapshot';
      // “录制”按钮不再打开全屏预览，改成真正的开始 / 停止录像。
      if (hasClassInPath(node, ['box_19', 'image-text_15', 'image_9', 'text-group_3'])) return 'camera_record';
      if (hasClassInPath(node, ['box_20', 'image-text_16', 'label_4', 'text-group_4'])) return 'camera_open';
      if (hasClassInPath(node, ['image-wrapper_7', 'text_70'])) return 'camera_config';
    }
    if (path.indexOf('lanhu_1icuzhuangtaikaobei') >= 0 && hasClassInPath(node, ['image-wrapper_4', 'image_8'])) {
      return 'camera_preview_tap';
    }
    if (text.indexOf('\u622a\u56fe') >= 0) return 'camera_snapshot';
    if (text.indexOf('\u914d\u7f6e\u6444\u50cf\u5934') >= 0 || text.indexOf('\u89c6\u9891\u53c2\u6570') >= 0) return 'camera_config';
    if (text.indexOf('\u5168\u5c4f') >= 0 || text.indexOf('\u5f55\u5236') >= 0) return 'camera_open';
    if (text === '编辑' && hasClassInPath(node, ['text_50', 'text-wrapper_18'])) return 'patient_edit';
    if (text === '删除' && hasClassInPath(node, ['text_51', 'text-wrapper_19'])) return 'patient_delete';
    if (text.indexOf('预览') >= 0) return 'pdf_preview';
    // 独立的「打印」按钮直接送系统打印；「预览打印」已经被上一条按预览处理。
    if (text === '打印') return 'pdf_print';
    if (text.indexOf('生成PDF') >= 0) return 'pdf_generate';
    if (text.indexOf('下载') >= 0 || text.indexOf('下载最新PDF') >= 0) return 'pdf_download';
    if (text.indexOf('截图') >= 0) return 'camera_snapshot';
    if (text.indexOf('配置摄像头') >= 0 || text.indexOf('视频参数') >= 0) return 'camera_config';
    if (text.indexOf('全屏') >= 0) return 'camera_open';
    if (text.indexOf('录制') >= 0) return 'camera_record';
    // 摄像头里当前版本还没开放的功能要逐项区分，点了要能说清楚是哪一项。
    if (path.indexOf('lanhu_4jiankong') >= 0) {
      if (text.indexOf('对讲') >= 0) return 'camera_feature_talk';
      if (text.indexOf('镜头遮蔽') >= 0) return 'camera_feature_privacy';
      if (text.indexOf('画面翻转') >= 0) return 'camera_feature_flip';
      if (text.indexOf('手动报警') >= 0) return 'camera_feature_alarm';
      if (text.indexOf('原图') >= 0) return 'camera_feature_original';
      if (text.indexOf('巡航') >= 0) return 'camera_feature_cruise';
      if (text.indexOf('图表') >= 0) return 'camera_feature_chart';
      if (text.indexOf('自定义') >= 0 && text.indexOf('自定义模式') < 0) return 'camera_feature_custom';
    }
    if (text.indexOf('新建治疗记录') >= 0) return 'treatment_new';
    if (text.indexOf('结束当前治疗') >= 0) return 'treatment_finish';
    if (text.indexOf('母幼护理模式') >= 0) return 'host_mode_config_mother';
    if (text.indexOf('术后护理模式') >= 0) return 'host_mode_config_postop';
    if (text.indexOf('心肺护理模式') >= 0) return 'host_mode_config_cardio';
    if (text.indexOf('自定义模式') >= 0) return 'host_mode_config_custom';
    if (text.indexOf('主机蓝牙') >= 0) return 'host_ble';
    if (text.indexOf('监护蓝牙') >= 0 || text === '蓝牙' || text.indexOf('监护宝') >= 0) return 'monitor_ble';
    if (text.indexOf('舱内温度') >= 0 || text.indexOf('温度补偿') >= 0) return 'control_temp';
    if (text.indexOf('红外体温') >= 0) return 'sensor_readonly';
    if (text.indexOf('氧浓度') >= 0 || text.indexOf('氧气浓度') >= 0) return 'control_oxygen';
    if (text.indexOf('二氧化碳浓度') >= 0) return 'control_co2';
    if (text.indexOf('红外理疗') >= 0) return 'control_red';
    if (text.indexOf('蓝光理疗') >= 0) return 'control_blue';
    if (text.indexOf('紫外消毒') >= 0) return 'control_uv';
    if (text.indexOf('雾化器') >= 0) return 'control_nebulizer';
    if (text.indexOf('负离子') >= 0) return 'control_anion';
    if (text.indexOf('冷光照明') >= 0) return 'control_cold_light';
    if (text.indexOf('暖光照明') >= 0 || text.indexOf('暖光灯') >= 0) return 'control_warm_light';
    if (text.indexOf('外循环') >= 0) return 'control_outer';
    if (text.indexOf('内循环') >= 0) return 'control_inner';
    if (text.indexOf('治疗时长') >= 0) return '';
    return '';
  }

  function nativeActionFromData(node) {
    while (node && node !== document.body) {
      if (node.getAttribute) {
        var action = node.getAttribute('data-native-action') || node.getAttribute('data-icu-action') || '';
        if (action) {
          return action;
        }
      }
      node = node.parentNode;
    }
    return '';
  }

  function nativeActionFromPoint(point, path) {
    if (point.x < 0 || point.y < 0) {
      return '';
    }
    if (path.indexOf('lanhu_4jiankong') >= 0 && !cameraPanelHit(point) && elementContainsPoint('.block_7,.image_8', point)) {
      return 'camera_preview_tap';
    }
    if (path.indexOf('lanhu_1icuzhuangtaikaobei') >= 0) {
      var icuPriority = [
        { selector: '.icu-switch-fake[data-for=".thumbnail_10"]', action: 'control_nebulizer' },
        { selector: '.icu-switch-fake[data-for=".thumbnail_11"]', action: 'control_warm_light' },
        { selector: '.icu-switch-fake[data-for=".thumbnail_12"]', action: 'control_inner' },
        { selector: '.box_25,.box_28', action: 'monitor_level_1' },
        { selector: '.box_26,.box_29', action: 'monitor_level_2' },
        { selector: '.box_27,.box_30', action: 'monitor_level_3' },
        { selector: '.text-wrapper_24', action: 'monitor_level_cycle' }
      ];
      for (var k = 0; k < icuPriority.length; k += 1) {
        if (elementContainsPoint(icuPriority[k].selector, point)) {
          return icuPriority[k].action;
        }
      }
    }
    if (path.indexOf('lanhu_2zhujikongzhi') >= 0) {
      var priority = [
        { selector: '.text_93', action: 'control_red_time' },
        { selector: '.text_94', action: 'control_blue_time' },
        { selector: '.text_96', action: 'control_uv_time' },
        { selector: '.text_99', action: 'control_nebulizer_time' },
        { selector: '.text_102', action: 'control_anion_time' },
        { selector: '.image_9,.icu-switch-fake[data-for=".image_9"]', action: 'control_red' },
        { selector: '.image_10,.icu-switch-fake[data-for=".image_10"]', action: 'control_blue' },
        { selector: '.image_11,.icu-switch-fake[data-for=".image_11"]', action: 'control_uv' },
        { selector: '.image_12,.icu-switch-fake[data-for=".image_12"]', action: 'control_nebulizer' },
        { selector: '.image_13,.icu-switch-fake[data-for=".image_13"]', action: 'control_anion' },
        { selector: '.image_14,.icu-switch-fake[data-for=".image_14"]', action: 'control_cold_light' },
        { selector: '.image_15,.icu-switch-fake[data-for=".image_15"]', action: 'host_mode_mother' },
        { selector: '.image_16,.icu-switch-fake[data-for=".image_16"]', action: 'control_warm_light' },
        { selector: '.image_17,.icu-switch-fake[data-for=".image_17"]', action: 'host_mode_postop' },
        { selector: '.image_18,.icu-switch-fake[data-for=".image_18"]', action: 'control_outer' },
        { selector: '.image_19,.icu-switch-fake[data-for=".image_19"]', action: 'host_mode_cardio' },
        { selector: '.image_20,.icu-switch-fake[data-for=".image_20"]', action: 'control_inner' },
        { selector: '.image_21,.icu-switch-fake[data-for=".image_21"]', action: 'host_mode_custom' },
        { selector: '.box_8', action: 'control_temp' },
        { selector: '.box_9', action: 'control_oxygen' },
        { selector: '.box_15', action: 'control_co2' },
        { selector: '.group_18', action: 'control_red' },
        { selector: '.group_21', action: 'control_blue' },
        { selector: '.group_26', action: 'control_uv' },
        { selector: '.box_35', action: 'control_nebulizer' },
        { selector: '.box_39', action: 'control_anion' },
        { selector: '.block_31', action: 'control_cold_light' },
        { selector: '.section_42', action: 'control_warm_light' },
        { selector: '.group_99', action: 'control_outer' },
        { selector: '.group_101', action: 'control_inner' },
        { selector: '.group_45,.section_41', action: 'host_mode_mother' },
        { selector: '.section_20,.box_78', action: 'host_mode_postop' },
        { selector: '.group_55,.box_79', action: 'host_mode_cardio' },
        { selector: '.group_62,.box_80', action: 'host_mode_custom' },
        { selector: '.block_16,.section_10', action: 'monitor_level_1' },
        { selector: '.block_17,.section_11', action: 'monitor_level_2' },
        { selector: '.block_18,.section_12', action: 'monitor_level_3' },
        { selector: '.text-wrapper_28', action: 'monitor_level_cycle' }
      ];
      for (var i = 0; i < priority.length; i += 1) {
        if (elementContainsPoint(priority[i].selector, point)) {
          return priority[i].action;
        }
      }
    }
    if (path.indexOf('lanhu_3shishijianhu') >= 0) {
      if (elementContainsPoint('.group_16,.image-text_14', point)) {
        return 'monitor_ble';
      }
      if (elementContainsPoint('.group_18,.image-text_16', point)) {
        return 'monitor_alarm_sound';
      }
      if (elementContainsPoint('.group_19,.image-text_17', point)) {
        return 'monitor_limits';
      }
    }
    var common = [
      { selector: '.text-wrapper_18', action: 'patient_edit' },
      { selector: '.text-wrapper_19', action: 'patient_delete' },
      { selector: '.text-wrapper_20', action: 'pdf_preview' },
      { selector: '.text-wrapper_21', action: 'pdf_download' }
    ];
    for (var j = 0; j < common.length; j += 1) {
      if (elementContainsPoint(common[j].selector, point)) {
        return common[j].action;
      }
    }
    return '';
  }

  function markHandled(key) {
    var now = Date.now();
    if (handledKey === key && now - handledAt < 450) {
      return false;
    }
    handledKey = key;
    handledAt = now;
    return true;
  }

  /**
   * 同一次点按只派发一次动作。
   *
   * <p>触摸和 click 是两个事件，一次点按会先后触发；之前用「动作 + 文案」
   * 做去重键，而开关的文案会随状态在“点击开启/点击关闭”之间变化，两段文案
   * 对不上，去重失效，一次点按被派发两次：开关刚被打开又马上被关掉，
   * 看起来就是“只能打开”。</p>
   * <p>改成按「动作 + 命中的元素」判断：同一个元素上的同一个动作，
   * 450 毫秒内只认第一次。</p>
   */
  function markHandledAction(action, node) {
    var now = Date.now();
    if (lastActionName === action && lastActionNode === node && now - lastActionAt < 450) {
      return false;
    }
    lastActionName = action;
    lastActionNode = node;
    lastActionAt = now;
    return true;
  }

  /** 病例列表当前属于哪个舱：列表按舱存，这个舱位才是数据的归属。 */
  function patientStoreZone() {
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.zone === 'function') {
      var zone = String(window.IcuPatientTemp.zone() || '').toLowerCase();
      if (zone === 'left' || zone === 'right') {
        return zone;
      }
    }
    return currentZone();
  }

  function syncTempPatientForNative(action) {
    if (!window.IcuNative || typeof window.IcuNative.syncTempPatient !== 'function') {
      return;
    }
    if (!window.IcuPatientTemp || typeof window.IcuPatientTemp.currentPatient !== 'function') {
      return;
    }
    try {
      var patient = window.IcuPatientTemp.currentPatient() || {};
      // 舱位要用这份数据真正所属的舱（病例列表按舱存），
      // 用乐观切舱的结果会把上一舱的宠物写进新舱。
      patient.zone = patientStoreZone();
      window.IcuNative.syncTempPatient(JSON.stringify(patient));
    } catch (error) {
    }
  }

  function browserTouchPoint(event) {
    var source = event;
    if (event && event.changedTouches && event.changedTouches.length) {
      source = event.changedTouches[0];
    } else if (event && event.touches && event.touches.length) {
      source = event.touches[0];
    }
    return {
      x: source && typeof source.clientX === 'number' ? source.clientX : -1,
      y: source && typeof source.clientY === 'number' ? source.clientY : -1
    };
  }

  function routeFromTabNode(node) {
    var map = [
      { cls: 'text_54', text: 'ICU状态', route: '../lanhu_1icuzhuangtaikaobei/index.html' },
      { cls: 'text_55', text: '主机控制', route: '../lanhu_2zhujikongzhi/index.html' },
      { cls: 'text_56', text: '实时监护', route: '../lanhu_3shishijianhu/index.html' },
      { cls: 'text_57', text: '摄像监控', route: '../lanhu_4jiankong/index.html' },
      { cls: 'text_58', text: '治疗记录', route: '../lanhu_6zhiliaojilu/index.html' },
      { cls: 'text-wrapper_22', text: '治疗记录', route: '../lanhu_6zhiliaojilu/index.html' },
      { cls: 'text_59', text: '使用教程', route: '../lanhu_7jiaocheng/index.html' }
    ];
    while (node && node !== document.body) {
      if (node.classList) {
        for (var i = 0; i < map.length; i += 1) {
          if (node.classList.contains(map[i].cls)) {
            return map[i];
          }
        }
      }
      node = node.parentNode;
    }
    return null;
  }

  function navCandidates() {
    var nodes = document.querySelectorAll('.text_2,.text_3,.group_1,.text_54,.text_55,.text_56,.text_57,.text_58,.text_59,.text-wrapper_22');
    var items = [];
    for (var i = 0; i < nodes.length; i += 1) {
      var text = actionText(nodes[i]);
      var route = routes[text] || '';
      if (!route || !nodes[i].getBoundingClientRect) {
        continue;
      }
      var rect = nodes[i].getBoundingClientRect();
      if (!rect || rect.width < 1 || rect.height < 1) {
        continue;
      }
      items.push({ text: text, route: route, rect: rect, node: nodes[i] });
    }
    return items;
  }

  function tabBarRouteFromPoint(point) {
    if (point.x < 0 || point.y < 0) {
      return null;
    }
    var labels = [
      { selector: '.text_54', text: 'ICU状态', route: '../lanhu_1icuzhuangtaikaobei/index.html' },
      { selector: '.text_55', text: '主机控制', route: '../lanhu_2zhujikongzhi/index.html' },
      { selector: '.text_56', text: '实时监护', route: '../lanhu_3shishijianhu/index.html' },
      { selector: '.text_57', text: '摄像监控', route: '../lanhu_4jiankong/index.html' },
      { selector: '.text_58', text: '治疗记录', route: '../lanhu_6zhiliaojilu/index.html' },
      { selector: '.text_59', text: '使用教程', route: '../lanhu_7jiaocheng/index.html' }
    ];
    var found = [];
    for (var i = 0; i < labels.length; i += 1) {
      var node = document.querySelector(labels[i].selector);
      if (!node || !node.getBoundingClientRect) {
        continue;
      }
      var rect = node.getBoundingClientRect();
      if (rect.width > 1 && rect.height > 1) {
        found.push({ text: labels[i].text, route: labels[i].route, rect: rect, node: node });
      }
    }
    if (found.length < 2) {
      return null;
    }
    var padX = designToViewport(20);
    var padY = designToViewport(20);
    for (var k = 0; k < found.length; k += 1) {
      var rect = found[k].rect;
      if (point.x >= rect.left - padX && point.x <= rect.right + padX &&
        point.y >= rect.top - padY && point.y <= rect.bottom + padY) {
        return found[k];
      }
    }
    return null;
  }

  function navRouteFromPoint(point) {
    if (point.x < 0 || point.y < 0) {
      return null;
    }
    var items = navCandidates();
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < items.length; i += 1) {
      var rect = items[i].rect;
      var expandX = Math.max(designToViewport(120), rect.width * 1.8);
      var expandY = Math.max(designToViewport(45), rect.height * 2.4);
      if (point.x < rect.left - expandX || point.x > rect.right + expandX ||
        point.y < rect.top - expandY || point.y > rect.bottom + expandY) {
        continue;
      }
      var cx = (rect.left + rect.right) / 2;
      var cy = (rect.top + rect.bottom) / 2;
      var score = Math.abs(point.x - cx) + Math.abs(point.y - cy) * 2;
      if (score < bestScore) {
        bestScore = score;
        best = items[i];
      }
    }
    return best;
  }

  function navFallbackAllowed(point, candidate) {
    // 兜底命中矩形不能劫持"落在具体控件上"的点击，否则会出现点A跳到B的错位
    var node = elementFromPoint(point);
    if (!node || node === document.body || node === document.documentElement) {
      // 点在空白处：没有可以回退的控件，直接放弃，绝不凭空选一个控件
      return false;
    }
    if (candidate && candidate.node && (node === candidate.node || candidate.node.contains(node))) {
      return true;
    }
    var tag = node.tagName;
    var leaf = !node.children || node.children.length === 0;
    if (leaf && (tag === 'SPAN' || tag === 'IMG' || tag === 'BUTTON' || tag === 'INPUT' ||
      tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'A')) {
      return false;
    }
    return true;
  }

  function goBackOrHome() {
    if (window.history && window.history.length > 1) {
      window.history.back();
      return;
    }
    window.location.href = '../lanhu_1icuzhuangtaikaobei/index.html';
  }

  function stopEvent(event) {
    event.preventDefault();
    event.stopPropagation();
    if (event.stopImmediatePropagation) {
      event.stopImmediatePropagation();
    }
  }

  function navigateRoute(text, route, event) {
    if (!route) {
      return false;
    }
    // 旧的 HTML 报告页已经下线，报告一律走原生 PDF 预览，
    // 这里兜住任何残留的报告页跳转，避免打开一个已经不存在的页面。
    if (route.indexOf('lanhu_baogao') >= 0) {
      stopEvent(event);
      runNativeAction('pdf_preview', text || '预览', window.location.pathname || '', event);
      return true;
    }
    if (!ensureCurrentTreatmentForRoute(text)) {
      stopEvent(event);
      return true;
    }
    if (!markHandled('r:' + route)) {
      return true;
    }
    stopEvent(event);
    if (isCurrentRoute(route)) {
      showCurrentPageHint(text);
      return true;
    }
    if (window.location.href.indexOf(route) < 0) {
      window.location.href = timedNavigationUrl(route);
    }
    return true;
  }


  function optimisticModeForAction(action) {
    var map = {
      host_mode_mother: '.image_15',
      host_mode_postop: '.image_17',
      host_mode_cardio: '.image_19',
      host_mode_custom: '.image_21'
    };
    var selector = map[action] || '';
    if (!selector) {
      return false;
    }
    var selectors = ['.image_15', '.image_17', '.image_19', '.image_21'];
    for (var i = 0; i < selectors.length; i += 1) {
      var fake = document.querySelector('.icu-switch-fake[data-for="' + selectors[i] + '"]');
      if (!fake) {
        continue;
      }
      var on = selectors[i] === selector;
      fake.classList.toggle('on', on);
      fake.classList.toggle('off', !on);
      fake.setAttribute('title', on ? '点击切换模式' : '点击开启模式');
    }
    return true;
  }

  function applyMonitorLevel(action) {
    var levels = ['一级', '二级', '三级'];
    var level = '';
    if (action === 'monitor_level_cycle') {
      var current = '';
      try {
        current = window.localStorage ? window.localStorage.getItem(monitorLevelStorageKey()) : '';
      } catch (error) {
        current = '';
      }
      var index = levels.indexOf(current);
      level = levels[(index + 1) % levels.length];
    } else {
      var slot = parseInt(action.substring('monitor_level_'.length), 10);
      if (!isNaN(slot) && slot >= 1 && slot <= levels.length) {
        level = levels[slot - 1];
      }
    }
    if (!level) {
      return false;
    }
    try {
      if (window.localStorage) {
        window.localStorage.setItem(monitorLevelStorageKey(), level);
        // 主机回读前先按本地选择显示，避免界面闪回旧等级
        window.localStorage.setItem(pendingMonitorLevelStorageKey(), level + '|' + Date.now());
      }
    } catch (error) {
    }
    if (typeof window.CustomEvent === 'function') {
      window.dispatchEvent(new CustomEvent('icu-monitor-level', { detail: { level: level } }));
    }
    // 监护等级要真正下发到主机：等级换算成监护等级灯颜色，走蓝牙指令下发
    if (window.IcuNative && typeof window.IcuNative.action === 'function') {
      window.IcuNative.action('monitor_level_set', level, window.location.pathname || '');
    }
    return true;
  }

  function runLocalOverlayAction(action) {
    if (action.indexOf('monitor_level_') === 0) {
      return applyMonitorLevel(action);
    }
    if (action === 'monitor_ble' || action === 'host_ble') {
      if (window.IcuBlePanel && typeof window.IcuBlePanel.open === 'function') {
        window.IcuBlePanel.open();
        return true;
      }
      return false;
    }
    if (action === 'monitor_limits') {
      if (window.IcuMonitorPanel && typeof window.IcuMonitorPanel.openSettings === 'function') {
        window.IcuMonitorPanel.openSettings();
        return true;
      }
      return false;
    }
    if (action === 'monitor_history') {
      if (window.IcuMonitorPanel && typeof window.IcuMonitorPanel.openHistory === 'function') {
        window.IcuMonitorPanel.openHistory();
        return true;
      }
      return false;
    }
    // 使用教程页的六个入口对应内置教程清单里的资料（图文 / 视频 / 文档）
    if (action.indexOf('tutorial_item_') === 0) {
      var tutorialIndex = parseInt(action.substring('tutorial_item_'.length), 10);
      if (!isNaN(tutorialIndex) && window.IcuTutorial && typeof window.IcuTutorial.open === 'function') {
        window.IcuTutorial.open(tutorialIndex);
        return true;
      }
      return false;
    }
    // 教程页的「导入教程文档」：拉起系统文件选择器，复制由原生完成
    if (action === 'tutorial_import') {
      if (window.IcuNative && typeof window.IcuNative.importTutorialDocument === 'function') {
        window.IcuNative.importTutorialDocument();
        return true;
      }
      return false;
    }
    return false;
  }

  function dispatchPdfDownload(text, path) {
    var diagnostic = {
      action: 'pdf_download',
      dispatchedAt: new Date().toISOString(),
      nativeReceiptReceived: false,
      requestId: 0,
      accepted: null,
      receiptError: '',
      bridgeError: ''
    };
    window.__icuPdfDownloadDispatch = diagnostic;
    if (!window.IcuNative || typeof window.IcuNative.action !== 'function') {
      diagnostic.receiptError = 'bridge_unavailable';
      showPrintBridgeError('原生桥不可用，PDF下载未派发');
      return;
    }
    var raw;
    try {
      raw = window.IcuNative.action('pdf_download', text, path);
    } catch (error) {
      diagnostic.receiptError = 'bridge_error';
      diagnostic.bridgeError = String(error && error.message ? error.message : error);
      showPrintBridgeError('PDF下载派发异常，未确认原生已受理');
      return;
    }
    if (raw === undefined || raw === null || raw === '') {
      diagnostic.receiptError = 'missing_receipt';
      showPrintBridgeError('原生未返回有效受理回执，未确认PDF下载已受理');
      return;
    }
    diagnostic.nativeReturnObserved = true;
    var receipt = null;
    try {
      receipt = typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch (error) {
      diagnostic.receiptError = 'invalid_receipt';
    }
    if (!receipt || typeof receipt !== 'object') {
      if (!diagnostic.receiptError) {
        diagnostic.receiptError = 'missing_receipt';
      }
      showPrintBridgeError('原生未返回有效受理回执，未确认PDF下载已受理');
      return;
    }
    diagnostic.receipt = receipt;
    diagnostic.requestId = Number(receipt.requestId || 0);
    diagnostic.accepted = receipt.accepted === true;
    diagnostic.nativeReceiptReceived = diagnostic.requestId > 0;
    if (!diagnostic.nativeReceiptReceived) {
      diagnostic.receiptError = 'invalid_receipt';
      showPrintBridgeError('原生受理回执缺少请求编号，未确认PDF下载已受理');
    }
    // accepted 只表示原生已受理；最终成功必须等 printStatus.stage === 'success'。
  }

  function showPrintBridgeError(message) {
    if (window.IcuToast && typeof window.IcuToast.show === 'function') {
      window.IcuToast.show(message);
    } else if (window.IcuOverlay && typeof window.IcuOverlay.showToast === 'function') {
      window.IcuOverlay.showToast(message);
    }
  }

  function runNativeAction(action, text, path, event) {
    if (!action) {
      return false;
    }
    if (!markHandledAction(action, event && event.target ? event.target : null)) {
      stopEvent(event);
      return true;
    }
    stopEvent(event);
    // 切舱动作立刻更新当前舱，不等原生回包，
    // 否则"切到右舱后马上点开关"会把倒计时写进左舱。
    if (action === 'tablet_zone_left' || action === 'tablet_zone_right') {
      window.__icuCurrentZone = action === 'tablet_zone_right' ? 'right' : 'left';
      // 原生真正切过来之前，原生报的还是旧舱，这段时间别把网页切回去
      pendingZone = window.__icuCurrentZone;
      pendingZoneAt = Date.now();
      syncZoneSwitchState();
      notifyZoneChanged();
    }
    if (action === 'monitor_leave') {
      navigateRoute('离开', '../lanhu_1icuzhuangtaikaobei/index.html', event);
      return true;
    }
    if (action === 'camera_preview_tap' && path.indexOf('lanhu_1icuzhuangtaikaobei') >= 0) {
      // ICU 状态页点摄像头画面：直接跳到“摄像监控”专用页。
      // 原生那边没配地址时只会弹“摄像头配置”框，画面本身点了没反应。
      navigateRoute('摄像监控', '../lanhu_4jiankong/index.html', event);
      return true;
    }
    if (action === 'settings_cancel') {
      stopEvent(event);
      goBackOrHome();
      return true;
    }
    if (action === 'app_login') {
      navigateRoute('登录', '../lanhu_denglu/index.html', event);
      return true;
    }
    if (runLocalOverlayAction(action)) {
      return true;
    }
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.handleAction === 'function' && window.IcuPatientTemp.handleAction(action)) {
      return true;
    }
    if (action === 'camera_playback') {
      if (window.IcuCameraRuntime && typeof window.IcuCameraRuntime.showPlayback === 'function') {
        window.IcuCameraRuntime.showPlayback();
      }
      return true;
    }
    if (action === 'camera_live') {
      if (window.IcuCameraRuntime && typeof window.IcuCameraRuntime.showLive === 'function') {
        window.IcuCameraRuntime.showLive();
      }
      return true;
    }
    syncTempPatientForNative(action);
    if (action === 'pdf_download') {
      dispatchPdfDownload(text, path);
      return true;
    }
    if (window.IcuNative && typeof window.IcuNative.action === 'function') {
      window.IcuNative.action(action, text, path);
    }
    return true;
  }

  function priorityPdfActionFromNode(node) {
    while (node && node !== document.body) {
      var text = actionText(node);
      // 只有写着"预览"的按钮才是 PDF 预览入口；报告页的【影像记录】同样用 .text_52，不能被劫持
      if (hasClassInPath(node, ['text_52', 'text-wrapper_20']) && (text === '预览' || text === '预览打印' || text === '打印')) {
        return { action: 'pdf_preview', text: text || '预览' };
      }
      if (hasClassInPath(node, ['text_53', 'text-wrapper_21'])) {
        return { action: 'pdf_download', text: text || '下载' };
      }
      node = node.parentNode;
    }
    return null;
  }

  function cameraActionFromPoint(point, path) {
    if (point.x < 0 || point.y < 0) {
      return '';
    }
    if (path.indexOf('lanhu_4jiankong') >= 0) {
      if (cameraPanelHit(point)) {
        return '';
      }
      return elementContainsPoint('.block_7,.image_8', point) ? 'camera_preview_tap' : '';
    }
    // ICU 状态页的摄像区是空容器（底图已移除），没有文字可用于匹配，
    // 只能按区域命中，否则点画面区域完全没有反应。
    if (path.indexOf('lanhu_1icuzhuangtaikaobei') >= 0) {
      return regionHit('.image-wrapper_4', point) ? 'camera_preview_tap' : '';
    }
    return '';
  }

  // 判定点是否落在指定区域内，并且该点最上层的元素就属于这个区域，
  // 避免区域内被别的浮层覆盖时误判。
  function regionHit(selector, point) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      var rect = nodes[i].getBoundingClientRect();
      if (!(rect.width > 1 && rect.height > 1)) {
        continue;
      }
      if (!pointInRect(point, rect)) {
        continue;
      }
      var hit = document.elementFromPoint(point.x, point.y);
      if (hit && (hit === nodes[i] || nodes[i].contains(hit))) {
        return true;
      }
    }
    return false;
  }

  // 配置摄像头按钮和回放面板浮在画面上，点它们不能算作点击画面
  function cameraPanelHit(point) {
    return elementContainsPoint('.icu-camera-config-button,.icu-camera-playback-panel', point);
  }

  // 节点级命中：浏览器自身的 event.target 与画面渲染同源，任何坐标推算都必须排在它之后，
  // 否则坐标基准一旦整体偏移，点位判定就会抢先劫持点击（点 A 触发 B、点击无反应）。
  function handleNodeTextAction(node, path, event) {
    while (node && node !== document.body) {
      var text = actionText(node);
      var action = isSmallActionTarget(node, text) ? nativeActionFromText(text, path, node) : '';
      if (action) {
        runNativeAction(action, text, path, event);
        return true;
      }
      var route = routes[text] || '';
      if (route) {
        // 文字命中与页签节点命中、坐标兜底共用同一导航入口，计时参数只生成一次。
        return navigateRoute(text, route, event);
      }
      node = node.parentNode;
    }
    return false;
  }

  function fastHandle(event) {
    var node = event.target;
    var path = window.location.pathname || '';
    var touch = resolveTouchPoint(event);
    if (path.indexOf('lanhu_denglu') >= 0 && insideLoginForm(node)) {
      return;
    }
    if (insideOverlay(node)) {
      return;
    }
    if (insideThermalSurface(node)) {
      return;
    }
    if (focusInlineSearch(node, event)) {
      return;
    }
    var cameraPointAction = cameraActionFromPoint(touch, path);
    if (cameraPointAction && runNativeAction(cameraPointAction, cameraPointAction, path, event)) {
      return;
    }
    var directTabRoute = routeFromTabNode(node);
    if (directTabRoute && navigateRoute(directTabRoute.text, directTabRoute.route, event)) {
      return;
    }
    var priorityAction = priorityPdfActionFromNode(node);
    if (priorityAction && runNativeAction(priorityAction.action, priorityAction.text, path, event)) {
      return;
    }
    var dataAction = nativeActionFromData(node);
    if (dataAction && runNativeAction(dataAction, actionText(node) || dataAction, path, event)) {
      return;
    }
    // 一、节点级命中优先：文字按钮与导航项都由此判定，避免被坐标推算抢先劫持
    if (handleNodeTextAction(node, path, event)) {
      return;
    }
    if (!node || node === document.body || node === document.documentElement) {
      // 二、点到空白处：不再用坐标推算去找控件。空白处本就该"点了没反应"，
      // 一旦推算出控件，就会变成"点 A 触发 B"的错位。
      return;
    }
    // 三、点位级命中：仅在节点级无法识别、且确实点到了某个元素时兜底
    var pointAction = nativeActionFromPoint(touch, path);
    if (pointAction && runNativeAction(pointAction, pointAction, path, event)) {
      return;
    }
    var tabRoute = tabBarRouteFromPoint(touch);
    if (tabRoute && navFallbackAllowed(touch, tabRoute) && navigateRoute(tabRoute.text, tabRoute.route, event)) {
      return;
    }
    var navRoute = navRouteFromPoint(touch);
    if (navRoute && navFallbackAllowed(touch, navRoute) && navigateRoute(navRoute.text, navRoute.route, event)) {
      return;
    }
  }

  installOverlayModules();
  // 设置类页面（医院信息/仪器状态/连接状态/...）的左侧菜单在蓝湖导出时各自写死了一份
  // 选中态与外边距，切页时条目坐标会漂移；交给独立模块按统一坐标重排。
  injectScript('lanhu-settings-nav.js');
  // 顶栏账号和机构 LOGO 共用一份轻量渲染，覆盖不加载 first-phase 的设置页。
  injectScript('lanhu-account-brand.js');
  // 顶栏右上角显示设备当前时间；"控制舱"字样已去掉，左舱 / 右舱切换按钮保留。
  injectScript('lanhu-clock.js');
  // 页签整页加载的点击->正确首帧计时，结果保存在 window.IcuNavTiming（最近一次）。
  injectScript('lanhu-nav-timing.js');
  document.addEventListener('DOMContentLoaded', hideReportTemplateNavigation);
  document.addEventListener('DOMContentLoaded', installWideHitAreas);
  document.addEventListener('DOMContentLoaded', installZoneSwitch);
  window.setTimeout(hideReportTemplateNavigation, 120);
  window.setTimeout(installWideHitAreas, 120);
  window.setTimeout(installWideHitAreas, 600);
  window.setTimeout(installZoneSwitch, 120);
  window.setTimeout(installZoneSwitch, 600);
  document.addEventListener('touchend', fastHandle, { capture: true, passive: false });
  document.addEventListener('click', fastHandle, true);
})();
