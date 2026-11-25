(function () {
  var timer = 0;
  var MAX_THERAPY_PRESET_MINUTES = 1440;
  var MONITOR_LEVELS = ['一级', '二级', '三级'];
  var MONITOR_LEVEL_STORAGE_KEY = 'icu.monitor.level';
  // 刚点过监护等级后，本地选择要保留这么久，等主机回读确认后再交回设备状态
  var MONITOR_LEVEL_PENDING_MS = 8000;
  // 主机回报的监护等级按舱缓存：主机某一轮没回等级时不要把页面上的等级清空，
  // 但只能回退本舱上一次的值，绝不能串到另一舱。
  var lastHostMonitorLevel = {};
  var THERAPY_CONTROL_KEYS = ['redTherapy', 'blueTherapy', 'nebulizer', 'uv', 'anion'];
  var CO2_VALUE_PATHS = ['host.co2', 'host.controls.co2'];
  var ICU_NUMBER_PATHS = ['host.temp', 'host.oxygen', 'host.humidity', 'host.treatmentTime'];
  var ICU_SWITCHES = [
    { image: '.thumbnail_10', wrapper: '.image-wrapper_1', action: 'control_nebulizer', onPath: 'host.controls.nebulizerOn' },
    { image: '.thumbnail_11', wrapper: '.image-wrapper_2', action: 'control_warm_light', onPath: 'host.controls.warmLightOn' },
    { image: '.thumbnail_12', wrapper: '.image-wrapper_3', action: 'control_inner', onPath: 'host.controls.innerCycleOn' }
  ];
  var therapySessions = {};
  // 左右舱隔离：一个主机同时管左右两个舱，跨 render 的缓存（监护等级、倒计时）
  // 必须按舱命名，否则切舱后会沿用另一舱的数据。
  var DEFAULT_ZONE = 'right';

  function normalizeZone(value) {
    var text = clean(value).toLowerCase();
    if (text === 'left' || text === 'right') {
      return text;
    }
    return DEFAULT_ZONE;
  }

  function currentZone() {
    return normalizeZone(window.__icuCurrentZone);
  }

  function setCurrentZone(zone) {
    var normalized = normalizeZone(zone);
    if (typeof window.__icuReconcileZone === 'function') {
      // 舱位统一交给桥接层对账：网页已经点了切舱、原生还没切过来时，
      // 这里不能把网页的舱位改回旧舱。
      window.__icuReconcileZone(normalized);
      return;
    }
    if (normalized === window.__icuCurrentZone) {
      return;
    }
    window.__icuCurrentZone = normalized;
    if (typeof window.__icuZoneChanged === 'function') {
      window.__icuZoneChanged(normalized);
    }
  }

  function nativeReady() {
    return !!(window.IcuNative && typeof window.IcuNative.bleState === "function");
  }

  function readState() {
    if (!nativeReady()) {
      return {};
    }
    try {
      var state = JSON.parse(window.IcuNative.bleState() || "{}") || {};
      var phase = readFirstPhaseState();
      // bleState 是环境参数、开关和倒计时的唯一实时来源；
      // 历史治疗记录由治疗记录页单独渲染，不能覆盖 ICU 状态页和主机控制页的实时值。
      if (phase && phase.hostModes) {
        state.hostModes = phase.hostModes;
      }
      // 只有原生明确给出舱位才更新当前舱；state 为空时不能把舱位打回默认舱。
      var reportedZone = state.host ? clean(state.host.zone) : '';
      if (reportedZone) {
        setCurrentZone(reportedZone);
      }
      return state;
    } catch (error) {
      return {};
    }
  }

  function readFirstPhaseState() {
    if (!window.IcuNative || typeof window.IcuNative.firstPhaseState !== "function") {
      return {};
    }
    try {
      return JSON.parse(window.IcuNative.firstPhaseState() || "{}") || {};
    } catch (error) {
      return {};
    }
  }

  function readPatient() {
    if (!window.IcuNative || typeof window.IcuNative.patientState !== "function") {
      return {};
    }
    try {
      return JSON.parse(window.IcuNative.patientState() || "{}") || {};
    } catch (error) {
      return {};
    }
  }

  function clean(value) {
    if (value === undefined || value === null) {
      return "";
    }
    value = String(value).replace(/\s+/g, " ").trim();
    if (!value || value === "null" || value === "undefined" || value === "-" || value === "未连接" || value === "尚未收到响应") {
      return "";
    }
    return value;
  }

  function display(value) {
    return clean(value) || "--";
  }

  var PATIENT_DETAIL_PATHS = {
    petName: true,
    species: true,
    sex: true,
    age: true,
    ownerName: true,
    ownerPhone: true,
    doctor: true,
    note: true
  };

  function displayPatientField(value) {
    var text = clean(value);
    return !text || text === "待录入" || text === "未命名" || text === "未知" ? "待录入" : text;
  }

  function getPath(root, path) {
    if (path === "monitorLevel") {
      return monitorLevelText(root);
    }
    var parts = path.split(".");
    var current = root;
    for (var i = 0; i < parts.length; i += 1) {
      if (current === undefined || current === null) {
        return "";
      }
      current = current[parts[i]];
    }
    return current;
  }

  function readStoredMonitorLevel() {
    try {
      if (!window.localStorage) {
        return '';
      }
      var key = monitorLevelStorageKey();
      var value = window.localStorage.getItem(key) || '';
      if (!value) {
        // 旧版本没分舱，监护等级只有一份：迁移到当前舱后删掉旧键。
        // 不能再拿旧键兜底，否则左右舱会共用同一个等级。
        var legacy = window.localStorage.getItem(MONITOR_LEVEL_STORAGE_KEY) || '';
        if (legacy) {
          window.localStorage.setItem(key, legacy);
          window.localStorage.removeItem(MONITOR_LEVEL_STORAGE_KEY);
          value = legacy;
        }
      }
      return MONITOR_LEVELS.indexOf(value) >= 0 ? value : '';
    } catch (error) {
      return '';
    }
  }

  /** 监护等级按舱保存，左舱和右舱各自独立。 */
  function monitorLevelStorageKey() {
    return MONITOR_LEVEL_STORAGE_KEY + '.' + currentZone();
  }

  /**
   * 用户点过监护等级后，主机回读需要时间。这段时间内按本地选择显示，
   * 否则界面会在蓝牙往返的几秒里闪回旧等级。
   */
  function pendingMonitorLevelStorageKey() {
    return MONITOR_LEVEL_STORAGE_KEY + '.pending.' + currentZone();
  }

  function readPendingMonitorLevel() {
    try {
      var raw = window.localStorage ? window.localStorage.getItem(pendingMonitorLevelStorageKey()) : '';
      if (!raw) {
        return '';
      }
      var parts = raw.split('|');
      var level = parts[0];
      var timestamp = parseInt(parts[1], 10);
      if (MONITOR_LEVELS.indexOf(level) < 0) {
        return '';
      }
      if (!timestamp || (Date.now() - timestamp) > MONITOR_LEVEL_PENDING_MS) {
        window.localStorage.removeItem(pendingMonitorLevelStorageKey());
        return '';
      }
      return level;
    } catch (error) {
      return '';
    }
  }

  /** 主机回报的监护等级灯颜色（红/黄/绿）换算成监护等级。 */
  function monitorLevelFromHost(state) {
    var host = (state && state.host) || {};
    var label = clean(host.monitorLevel);
    if (MONITOR_LEVELS.indexOf(label) >= 0) {
      lastHostMonitorLevel[currentZone()] = label;
      return label;
    }
    // 原生给的是主机回报的原始颜色（red/yellow/green），
    // 老版本只给中文灯色（红/黄/绿），两种都认。
    var color = clean(host.statusLightColor).toLowerCase();
    if (!color) {
      var controls = host.controls || {};
      color = clean(controls.statusLight).toLowerCase();
    }
    var level = monitorLevelFromColor(color);
    if (level) {
      lastHostMonitorLevel[currentZone()] = level;
      return level;
    }
    return lastHostMonitorLevel[currentZone()] || '';
  }

  /** 灯颜色换算成监护等级名。 */
  function monitorLevelFromColor(color) {
    if (!color) {
      return '';
    }
    if (color.indexOf('红') >= 0 || color.indexOf('red') >= 0) {
      return '一级';
    }
    if (color.indexOf('黄') >= 0 || color.indexOf('yellow') >= 0) {
      return '二级';
    }
    if (color.indexOf('绿') >= 0 || color.indexOf('green') >= 0) {
      return '三级';
    }
    return '';
  }

  function monitorLevelText(state) {
    var pending = readPendingMonitorLevel();
    if (pending) {
      return pending;
    }
    var reported = monitorLevelFromHost(state);
    if (reported) {
      return reported;
    }
    var stored = readStoredMonitorLevel();
    if (stored) {
      return stored;
    }
    var monitor = (state && state.monitor) || {};
    if (!monitor.connected && !clean(monitor.summary)) {
      return "";
    }
    return clean(monitor.lastError) ? "一级" : "二级";
  }

  var MONITOR_LEVEL_MARKERS = [
    {
      page: 'lanhu_1icuzhuangtaikaobei',
      dots: ['.box_25', '.box_26', '.box_27'],
      rings: ['.box_28', '.box_29', '.box_30'],
      pill: '.text-wrapper_24'
    },
    {
      page: 'lanhu_2zhujikongzhi',
      dots: ['.block_16', '.block_17', '.block_18'],
      rings: ['.section_10', '.section_11', '.section_12'],
      pill: '.text-wrapper_28'
    }
  ];

  function monitorLevelMarkers() {
    var path = window.location.pathname || "";
    for (var i = 0; i < MONITOR_LEVEL_MARKERS.length; i += 1) {
      if (path.indexOf(MONITOR_LEVEL_MARKERS[i].page) >= 0) {
        return MONITOR_LEVEL_MARKERS[i];
      }
    }
    return null;
  }

  function renderMonitorLevelDots(level) {
    var markers = monitorLevelMarkers();
    if (!markers) {
      return;
    }
    var index = level ? MONITOR_LEVELS.indexOf(level) : -1;
    var ringIndex = index < 0 ? 0 : index;
    renderMonitorLevelPill(markers, index);
    for (var i = 0; i < markers.dots.length; i += 1) {
      var dot = document.querySelector(markers.dots[i]);
      if (dot) {
        dot.classList.add('icu-level-dot');
        dot.classList.toggle('icu-level-active', index === i);
      }
      var ring = document.querySelector(markers.rings[i]);
      if (ring) {
        ring.classList.add('icu-level-ring');
        ring.classList.toggle('icu-level-active', ringIndex === i);
      }
    }
  }

  function renderMonitorLevelPill(markers, index) {
    if (!markers || !markers.pill) {
      return;
    }
    var pill = document.querySelector(markers.pill);
    if (!pill) {
      return;
    }
    pill.classList.add('icu-level-pill');
    pill.classList.remove('icu-level-1', 'icu-level-2', 'icu-level-3');
    pill.classList.add('icu-level-' + (index < 0 ? 1 : index + 1));
  }

  function renderIcuSwitches(state) {
    for (var i = 0; i < ICU_SWITCHES.length; i += 1) {
      var item = ICU_SWITCHES[i];
      setSwitchImage(item.image, switchBool(state, item.onPath));
      var wrapper = document.querySelector(item.wrapper);
      if (!wrapper) {
        continue;
      }
      wrapper.style.visibility = 'visible';
      wrapper.style.background = 'transparent';
      wrapper.style.borderRadius = '0';
      wrapper.style.width = 'auto';
      wrapper.style.height = 'auto';
      wrapper.style.overflow = 'visible';
    }
  }

  function patientPath(root, path) {
    if (path === "petListName") {
      return "\u5ba0\u7269\u540d\uff1a" + displayPatientField(root.petName);
    }
    if (path === "speciesOwner") {
      return "\u79cd\u7c7b\uff1a" + displayPatientField(root.species) + "     \u4e3b\u4eba\uff1a" + displayPatientField(root.ownerName);
    }
    if (path === "caseNo") {
      return clean(root.caseNo) ? "\u7f16\u53f7\uff1a" + root.caseNo : "";
    }
    var parts = path.split(".");
    var current = root;
    for (var i = 0; i < parts.length; i += 1) {
      if (current === undefined || current === null) {
        return PATIENT_DETAIL_PATHS[path] ? "\u5f85\u5f55\u5165" : "";
      }
      current = current[parts[i]];
    }
    return PATIENT_DETAIL_PATHS[path] ? displayPatientField(current) : current;
  }

  function therapyControlKey(path) {
    var prefix = 'host.controls.';
    if (path.indexOf(prefix) !== 0) {
      return '';
    }
    var key = path.slice(prefix.length);
    return THERAPY_CONTROL_KEYS.indexOf(key) >= 0 ? key : '';
  }

  function therapyStorageKey(key) {
    return 'icu.therapy.' + currentZone() + '.' + key;
  }

  function therapySessionsCacheKey(key) {
    return currentZone() + '|' + key;
  }

  function readTherapySession(key) {
    var cacheKey = therapySessionsCacheKey(key);
    if (therapySessions[cacheKey]) {
      return therapySessions[cacheKey];
    }
    var raw = '';
    try {
      raw = window.sessionStorage ? window.sessionStorage.getItem(therapyStorageKey(key)) : '';
    } catch (error) {
      raw = '';
    }
    if (!raw) {
      return null;
    }
    try {
      var session = JSON.parse(raw);
      if (!session || !session.startedAt || !session.duration) {
        return null;
      }
      return session;
    } catch (error) {
      return null;
    }
  }

  function writeTherapySession(key, session) {
    therapySessions[therapySessionsCacheKey(key)] = session || null;
    try {
      if (!window.sessionStorage) {
        return;
      }
      if (session) {
        window.sessionStorage.setItem(therapyStorageKey(key), JSON.stringify(session));
      } else {
        window.sessionStorage.removeItem(therapyStorageKey(key));
      }
    } catch (error) {
      /* fall back to in-memory timing when storage is unavailable */
    }
  }

  function therapyPresetMinutes(value) {
    var text = clean(value);
    if (!text) {
      return 0;
    }
    var matched = /(\d+)/.exec(text);
    if (!matched) {
      return 0;
    }
    var minutes = parseInt(matched[1], 10);
    if (minutes <= 0 || minutes > MAX_THERAPY_PRESET_MINUTES) {
      return 0;
    }
    return minutes;
  }

  function formatTherapyClock(seconds) {
    var total = Math.max(0, Math.floor(seconds));
    var minutes = Math.floor(total / 60);
    var rest = total % 60;
    var pad = function (value) {
      return value <= 9 ? '0' + value : String(value);
    };
    if (minutes <= 99) {
      return pad(minutes) + ':' + pad(rest);
    }
    return Math.floor(minutes / 60) + ':' + pad(minutes % 60) + ':' + pad(rest);
  }

  function therapyDisplayText(state, key) {
    var valuePath = 'host.controls.' + key;
    var raw = getPath(state, valuePath);
    var enabled = switchOn(state, valuePath + 'On', valuePath);
    var session = readTherapySession(key);
    if (!enabled) {
      if (session) {
        writeTherapySession(key, null);
      }
      return '\u5173';
    }
    var preset = therapyPresetMinutes(raw);
    if (preset <= 0) {
      // 主机只报“开”或时间值无效时，不伪造 99 分钟倒计时。
      if (session) {
        writeTherapySession(key, null);
      }
      return '开';
    }
    var duration = preset * 60;
    var now = Date.now();
    if (!session) {
      session = { startedAt: now, duration: duration };
      writeTherapySession(key, session);
    } else if (preset > 0 && Math.abs(session.duration - preset * 60) > 60) {
      session = { startedAt: now, duration: preset * 60 };
      writeTherapySession(key, session);
    }
    var remaining = session.duration - Math.floor((now - session.startedAt) / 1000);
    if (remaining <= 0) {
      session = { startedAt: now, duration: duration };
      writeTherapySession(key, session);
      remaining = session.duration;
    }
    return formatTherapyClock(remaining);
  }

  function co2ValueText(state, path) {
    var text = clean(getPath(state, path));
    if (!text) {
      return '--';
    }
    return text.replace(/\s*ppm$/i, '');
  }

  // 红外测温模块（lanhu-thermal.js）给出的当前测量结果；该模块不在本页时返回 null
  function thermalMeasurement() {
    if (!window.IcuThermal || typeof window.IcuThermal.getMeasurement !== "function") {
      return null;
    }
    try {
      return window.IcuThermal.getMeasurement() || null;
    } catch (error) {
      return null;
    }
  }

  function hasThermalMeasurement() {
    var measurement = thermalMeasurement();
    return !!(measurement && measurement.hasData);
  }

  // 红外体温优先用红外测温取到的平均值，没有红外数据时退回主机蓝牙上报的值
  function infraredTempText(state) {
    var measurement = thermalMeasurement();
    if (measurement && measurement.hasData && isFinite(measurement.average)) {
      return measurement.average.toFixed(1);
    }
    return display(getPath(state, "host.infraredTemp"));
  }

  // ICU 状态页的数值区是 48px 大字号，标题里已经标了单位（℃ / % / h），
  // 这里只留数字，和二氧化碳浓度去掉 PPM 的处理保持一致
  function icuNumberText(state, path) {
    var text = clean(getPath(state, path));
    if (!text) {
      return "--";
    }
    if (path === "host.treatmentTime") {
      return treatmentTimeText(text);
    }
    return text.replace(/\s*(?:℃|°|%)\s*$/, "");
  }

  function treatmentTimeText(value) {
    var text = clean(value);
    if (!text) {
      return "--";
    }
    var clock = /^(\d+):(\d{1,2})$/.exec(text);
    if (clock) {
      return formatClockText(parseInt(clock[1], 10), parseInt(clock[2], 10));
    }
    var matched = /^(\d+)\s*h\s*(\d+)\s*m$/.exec(text);
    if (matched) {
      return formatClockText(parseInt(matched[1], 10), parseInt(matched[2], 10));
    }
    matched = /^(\d+)\s*h$/.exec(text);
    if (matched) {
      return formatClockText(parseInt(matched[1], 10), 0);
    }
    matched = /^(\d+)\s*(?:m|分钟)$/.exec(text);
    if (matched) {
      return formatClockMinutes(parseInt(matched[1], 10));
    }
    return text;
  }

  function formatClockText(hours, minutes) {
    var h = Math.max(0, hours);
    var m = Math.max(0, minutes);
    return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
  }

  function formatClockMinutes(totalMinutes) {
    var minutes = Math.max(0, totalMinutes);
    return formatClockText(Math.floor(minutes / 60), minutes % 60);
  }

  function isIcuStatusPage() {
    return String(window.location.pathname || "").indexOf("lanhu_1icuzhuangtaikaobei") >= 0;
  }

  function liveValueText(state, path) {
    var therapyKey = therapyControlKey(path);
    if (therapyKey) {
      return therapyDisplayText(state, therapyKey);
    }
    if (path === "host.controls.treatmentTime") {
      return treatmentTimeText(getPath(state, path));
    }
    if (CO2_VALUE_PATHS.indexOf(path) >= 0) {
      return co2ValueText(state, path);
    }
    if (path === "host.infraredTemp") {
      return infraredTempText(state);
    }
    if (isIcuStatusPage() && ICU_NUMBER_PATHS.indexOf(path) >= 0) {
      return icuNumberText(state, path);
    }
    return display(getPath(state, path));
  }

  // ICU 状态页三张小卡片（雾化器/暖光灯/内循环）开关左侧只有 33px，
  // 倒计时这种 00:00 文本比“开/关”长，按设计稿 16px 排版会压到开关上，
  // 这里按字符数反算字号；不是时钟文本时保持设计稿字号不变。
  var ICU_CLOCK_VALUE_WIDTH = 33;
  var ICU_CLOCK_VALUE_GAP = 3;
  var ICU_CLOCK_VALUE_MIN_FONT = 10;
  var ICU_CLOCK_VALUE_MAX_FONT = 13;
  var ICU_CLOCK_VALUE_SELECTOR = ".box_36 .text_73, .box_37 .text_75, .box_38 .text_77";

  function isClockText(value) {
    return /^\d{1,3}:\d{2}(:\d{2})?$/.test(value);
  }

  function applyIcuClockValueStyle(node, text) {
    var value = clean(text);
    if (!isClockText(value) || !node.closest || !node.closest(ICU_CLOCK_VALUE_SELECTOR)) {
      node.classList.remove("icu-clock-value");
      node.style.removeProperty("font-size");
      return;
    }
    node.classList.add("icu-clock-value");
    var fontSize = Math.floor((ICU_CLOCK_VALUE_WIDTH - ICU_CLOCK_VALUE_GAP) / (value.length * 0.5));
    fontSize = Math.max(ICU_CLOCK_VALUE_MIN_FONT, Math.min(ICU_CLOCK_VALUE_MAX_FONT, fontSize));
    node.style.setProperty("font-size", fontSize + "px", "important");
  }

  // 把诊断版本、阶段计数与最近丢弃原因暴露到界面，真机可直接判断
  // 安装包是否包含本轮诊断，以及 C305 帧卡在拼帧/解析/归属/采纳哪一步。
  function diagnosticHintText(host) {
    var lines = [];
    var version = clean(host.dataDiagVersion);
    if (version) {
      lines.push('诊断版本 ' + version);
    }
    var dataError = clean(host.dataError);
    if (dataError) {
      lines.push('数据通路 ' + dataError);
    } else if (clean(host.dataState) === 'timeout' || host.ready === false) {
      lines.push('数据通路超时，数值为最后真实缓存');
    }
    var lastError = clean(host.lastError);
    if (lastError && lastError !== dataError) {
      lines.push('主机提示 ' + lastError);
    }
    var diag = host.dataDiag;
    if (diag) {
      var drop = clean(diag.lastDropReason);
      if (drop) {
        lines.push('最近丢弃 ' + drop);
      }
      lines.push('帧 分片' + (diag.notifyChunkCount || 0)
        + '/完整' + (diag.completeFrameCount || 0)
        + '/解析失败' + (diag.parseFailureCount || 0)
        + '/归属拒绝' + (diag.zoneRejectedCount || 0)
        + '/实测采纳' + (diag.environmentAppliedCount || 0)
        + '/有效状态' + (diag.validHostStateCount || 0));
    }
    return lines;
  }

  var CONFIRM_LABELS = {
    pending: '等待回读',
    acknowledged: '已收到回包，等待主机回读',
    confirmed: '主机已回读',
    unconfirmed: '超时未确认',
    contradicted: '主机回读与下发不一致'
  };

  function controlHintText(host) {
    var confirm = host && host.controlConfirmations;
    if (!confirm) {
      return '';
    }
    var items = confirm.items || {};
    var parts = [];
    for (var key in items) {
      if (!Object.prototype.hasOwnProperty.call(items, key)) {
        continue;
      }
      var state = (items[key] || {}).state;
      if (CONFIRM_LABELS[state]) {
        parts.push(key + ':' + CONFIRM_LABELS[state]);
      }
    }
    if (parts.length) {
      return '控制状态 ' + parts.join('；');
    }
    var replyState = clean(confirm.replyState);
    if (replyState === 'waiting' || replyState === 'received') {
      return '控制状态 等待回读';
    }
    if (replyState === 'timeout') {
      return '控制状态 超时未确认';
    }
    return '';
  }

  // 主机环境实测值多久没刷新就在界面上灰显：
  // 原生 ENVIRONMENT_STALE_MS(15 秒)是补发分项查询的触发线，设备若只应答
  // 分项查询，updatedAt 最长约 15 秒前进一次，所以可见灰显阈值取 2 倍，
  // 避免正常设备在每轮补查前误闪灰。
  var ENV_VALUE_STALE_MS = 30000;
  var STALE_STYLE_ID = 'icu-stale-value-style';

  /** 注入陈旧数值的灰显样式：只降透明度并去色，不加任何文字浮层。 */
  function installStaleValueStyle() {
    if (document.getElementById(STALE_STYLE_ID)) {
      return;
    }
    var style = document.createElement('style');
    style.id = STALE_STYLE_ID;
    style.textContent = '[data-icu-stale="1"]{opacity:.45;'
      + '-webkit-filter:grayscale(60%);filter:grayscale(60%);'
      + 'transition:opacity .3s ease;}';
    (document.head || document.documentElement).appendChild(style);
  }

  /**
   * 用 zones 缓存的时间戳把"目标舱数据没回来"分成三种签名，
   * 现场不用再猜是链路问题还是载荷问题：
   * - 查询已发出但主机零回包（querySentAt 前进、replyAt 没跟上）；
   * - 主机有回包但帧里没有有效环境值（replyAt 前进、updatedAt 停滞）；
   * - 环境值超过阈值未更新（显示最后真实缓存）。
   * 主机刚就绪还没来得及查询时返回空串，不做任何判定。
   */
  function zoneEnvSignature(host) {
    var zones = host && host.zones ? host.zones : null;
    if (!zones) {
      return '';
    }
    var env = zones[normalizeZone(host.zone)];
    if (!env) {
      return '';
    }
    var updatedAt = Number(env.updatedAt) || 0;
    var querySentAt = Number(env.querySentAt) || 0;
    var replyAt = Number(env.replyAt) || 0;
    if (!updatedAt) {
      if (!querySentAt && !replyAt) {
        return '';
      }
      return replyAt
        ? '主机有回包但从未提供有效环境值'
        : '目标舱查询已发出，主机零回包';
    }
    if (Date.now() - updatedAt > ENV_VALUE_STALE_MS) {
      return replyAt && replyAt > querySentAt
        ? '主机有回包但环境值超过30秒未更新，显示最后真实缓存'
        : '主机超过30秒零回包，显示最后真实缓存';
    }
    return '';
  }

  /**
   * 数值卡继续显示最后真实实测值；陈旧判定在原有数据通路条件之外，
   * 增加"主机已连接且协议就绪，但当前舱实测值停滞"这一类——
   * 它覆盖主机有回包、dataState 仍是 ok、环境值却缺着的场景。
   */
  function isHostValueStale(host, envReason) {
    if (clean(host.dataState) === 'timeout' || !!clean(host.dataError)
      || host.ready === false) {
      return true;
    }
    if (host.connected && host.protocolReady) {
      return !!envReason;
    }
    return false;
  }

  function renderConfirmationHints(state, nodes) {
    installStaleValueStyle();
    var host = state && state.host ? state.host : {};
    // 只在主机链路本身活着时才用 zones 时间戳下结论；
    // 未连接/未就绪时原因交给 dataError，不用旧会话时间戳误报。
    var envReason = (host.connected && host.protocolReady)
      ? zoneEnvSignature(host) : '';
    var stale = isHostValueStale(host, envReason);
    var staleTitle = envReason
      || clean(host.dataError)
      || '数据已过期，显示最后真实实测值';
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      var valuePath = node.getAttribute('data-icu-live') || '';
      var measurement = valuePath.indexOf('host.temp') === 0
        || valuePath.indexOf('host.oxygen') === 0
        || valuePath.indexOf('host.humidity') === 0
        || valuePath.indexOf('host.co2') === 0
        || valuePath.indexOf('host.controls.temp') === 0
        || valuePath.indexOf('host.controls.oxygen') === 0
        || valuePath.indexOf('host.controls.humidity') === 0
        || valuePath.indexOf('host.controls.co2') === 0;
      if (!measurement) {
        continue;
      }
      if (stale) {
        node.setAttribute('data-icu-stale', '1');
        node.setAttribute('title', staleTitle);
      } else {
        node.removeAttribute('data-icu-stale');
        node.removeAttribute('title');
      }
    }
    // 签名写进不可见的 body 属性，供探针/adb 直接读取，不往页面盖文字。
    if (envReason) {
      document.body.setAttribute('data-icu-env-signature', envReason);
    } else {
      document.body.removeAttribute('data-icu-env-signature');
    }
    var hints = diagnosticHintText(host);
    if (envReason) {
      hints.unshift('当前舱 ' + envReason);
    }
    var confirmHint = controlHintText(host);
    if (confirmHint) {
      hints.push(confirmHint);
    }
    var hint = hints.join('\n');
    document.body.setAttribute('data-icu-diag-version', clean(host.dataDiagVersion));
    // 右下角的固定诊断浮层已按需求移除：诊断结论只写进不可见的 body 属性，
    // 仍可供探针/排障读取，但不再往页面上盖一层信息框。
    var legacyBadge = document.getElementById('icu-control-hint');
    if (legacyBadge && legacyBadge.parentNode) {
      legacyBadge.parentNode.removeChild(legacyBadge);
    }
    if (hint) {
      document.body.setAttribute('data-icu-control-hint', hint);
    } else {
      document.body.removeAttribute('data-icu-control-hint');
    }
  }

  function render() {
    var nodes = document.querySelectorAll("[data-icu-live]");
    var patientNodes = document.querySelectorAll("[data-patient-live]");
    var state = readState();
    renderConfirmationHints(state, nodes);
    if (!nodes.length && !patientNodes.length) {
      return;
    }
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      var path = node.getAttribute("data-icu-live") || "";
      var text = liveValueText(state, path);
      node.textContent = text;
      applyIcuClockValueStyle(node, text);
    }
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.render === "function") {
      window.IcuPatientTemp.render();
    } else {
      var patient = readPatient();
      for (var j = 0; j < patientNodes.length; j += 1) {
        var patientNode = patientNodes[j];
        var patientKey = patientNode.getAttribute("data-patient-live") || "";
        patientNode.textContent = display(patientPath(patient, patientKey));
      }
    }
    clearDeadVisuals(state);
    renderMonitorLevelDots(monitorLevelText(state));
  }

  function hasValue(state, path) {
    return !!clean(getPath(state, path));
  }

  function isOnValue(value) {
    if (value === true) {
      return true;
    }
    if (value === false || value === undefined || value === null) {
      return false;
    }
    var text = clean(value).toLowerCase();
    if (!text) {
      return false;
    }
    if (text === "off" || text === "false" || text === "0" || text.indexOf("\u5173") === 0 || text.indexOf("\u95ed") === 0 || text.indexOf("\u934f") === 0) {
      return false;
    }
    return text === "on" || text === "true" || text === "1" || text.indexOf("\u5f00") === 0 || text.indexOf("\u5f00\u542f") === 0 || text.indexOf("\u5bee") === 0;
  }

  function switchOn(state, onPath, valuePath) {
    var direct = getPath(state, onPath);
    if (direct === true || direct === false) {
      return direct;
    }
    if (clean(direct)) {
      return isOnValue(direct);
    }
    return isOnValue(getPath(state, valuePath));
  }

  function switchBool(state, onPath) {
    return getPath(state, onPath) === true;
  }

  function setVisible(selector, visible) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.visibility = visible ? "visible" : "hidden";
    }
  }

  function setSwitchImage(selector, on) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      var img = nodes[i];
      var parent = img.parentNode;
      if (!parent) {
        continue;
      }
      var fake = parent.querySelector(".icu-switch-fake[data-for='" + selector + "']");
      if (!fake) {
        fake = document.createElement("span");
        fake.className = "icu-switch-fake";
        fake.setAttribute("data-for", selector);
        parent.insertBefore(fake, img.nextSibling);
      }
      var action = switchAction(selector);
      if (action) {
        fake.setAttribute("data-native-action", action);
        fake.setAttribute("title", on ? "点击关闭" : "点击开启");
      }
      fake.classList.toggle("on", !!on);
      fake.classList.toggle("off", !on);
      img.style.display = "none";
    }
  }

  function switchAction(selector) {
    var map = {
      ".image_9": "control_red",
      ".image_10": "control_blue",
      ".image_11": "control_uv",
      ".image_12": "control_nebulizer",
      ".image_13": "control_anion",
      ".image_14": "control_cold_light",
      ".image_15": "host_mode_mother",
      ".image_16": "control_warm_light",
      ".image_17": "host_mode_postop",
      ".image_18": "control_outer",
      ".image_19": "host_mode_cardio",
      ".image_20": "control_inner",
      ".image_21": "host_mode_custom",
      ".thumbnail_10": "control_nebulizer",
      ".thumbnail_11": "control_warm_light",
      ".thumbnail_12": "control_inner"
    };
    return map[selector] || "";
  }

  function clearDeadVisuals(state) {
    var page = window.location.pathname || "";
    if (page.indexOf("lanhu_1icuzhuangtaikaobei") >= 0) {
      setVisible(".group_11", hasValue(state, "host.temp"));
      setVisible(".box_20", hasValue(state, "host.oxygen"));
      setVisible(".box_22", hasValue(state, "host.infraredTemp") || hasThermalMeasurement());
      setVisible(".group_14", hasValue(state, "host.treatmentTime"));
      setVisible(".box_45", switchBool(state, "host.controls.blueTherapyOn"));
      setVisible(".box_42", hasValue(state, "host.humidity"));
      setVisible(".box_44", hasValue(state, "host.co2"));
      renderIcuSwitches(state);
      return;
    }
    if (page.indexOf("lanhu_2zhujikongzhi") < 0) {
      return;
    }
    setVisible(".section_3", hasValue(state, "host.controls.temp"));
    setVisible(".section_4", hasValue(state, "host.controls.oxygen"));
    setVisible(".box_14", hasValue(state, "host.controls.humidity"));
    setVisible(".block_15", hasValue(state, "host.controls.co2"));
    setVisible(".box_48", true);
    setVisible(".group_39,.section_17,.box_37,.box_41,.box_44,.group_50,.label_3,.group_61", true);
    var redOn = switchBool(state, "host.controls.redTherapyOn");
    var blueOn = switchBool(state, "host.controls.blueTherapyOn");
    var uvOn = switchBool(state, "host.controls.uvOn");
    var nebulizerOn = switchBool(state, "host.controls.nebulizerOn");
    var anionOn = switchBool(state, "host.controls.anionOn");
    var coldOn = switchBool(state, "host.controls.coldLightOn");
    var warmOn = switchBool(state, "host.controls.warmLightOn");
    var outerOn = switchBool(state, "host.controls.outerCycleOn");
    var innerOn = switchBool(state, "host.controls.innerCycleOn");
    setSwitchImage(".image_9", redOn);
    setSwitchImage(".image_10", blueOn);
    setSwitchImage(".image_11", uvOn);
    setSwitchImage(".image_12", nebulizerOn);
    setSwitchImage(".image_13", anionOn);
    setSwitchImage(".image_14", coldOn);
    setSwitchImage(".image_16", warmOn);
    setSwitchImage(".image_18", outerOn);
    setSwitchImage(".image_20", innerOn);
    // 四个模式按原生下发的四选一 enabled 渲染；active 是主机确认标识，不能覆盖本地选择。
    var modes = state.hostModes || [];
    setSwitchImage(".image_15", !!((modes[0] || {}).enabled));
    setSwitchImage(".image_17", !!((modes[1] || {}).enabled));
    setSwitchImage(".image_19", !!((modes[2] || {}).enabled));
    setSwitchImage(".image_21", !!((modes[3] || {}).enabled));
  }

  function scheduleRender(delay) {
    window.clearTimeout(timer);
    timer = window.setTimeout(render, delay || 0);
  }

  document.addEventListener("DOMContentLoaded", function () {
    render();
    window.setInterval(render, 1000);
  });

  window.addEventListener("icu-native-state", function () {
    scheduleRender(0);
  });
  window.addEventListener("icu-monitor-level", function () {
    scheduleRender(0);
  });
})();
