(function () {
  var timer = 0;
  var empty = "--";
  var selectedMonitorWave = "all";
  var cameraPlaybackMode = false;
  var selectedTreatmentRow = -1;
  // 实时监护波形只使用监护仪真实采样：缓存最近一次采样、连接状态和当前要绘制的通道。
  var waveData = { ecg: [], spo2: [], resp: [] };
  // 血压/体温在监护仪协议里没有连续波形，这里保存真实测量值的变化趋势。
  var waveTrend = { heartRate: [], spo2: [], pulseRate: [], respRate: [], nibpSys: [], nibpDia: [], temp: [], tempEar: [] };
  var waveStats = { ecg: null, spo2: null, resp: null };
  var waveConnected = false;
  var waveSignature = "";
  var wavePlan = [];
  var WAVE_POLL_INTERVAL_MS = 120;
  // 数值单独走 120ms 轻量桥：波形轮询要构建并解析上千个采样点，
  // 把数值挂在它后面会连数值一起被大 JSON 拖慢。
  var VITALS_POLL_INTERVAL_MS = 60;
  var WAVE_WINDOW_SAMPLES = 240;
  var TREND_WINDOW_SAMPLES = 60;
  // 采样恒定说明该通道没有真实波形信号（例如未接导联/探头），按无信号处理，不画一条假直线。
  var WAVE_FLAT_AMPLITUDE = 2;
  // 摄像头里当前版本还没有开放的功能：图标和文字一起置灰，并绑定各自的提示动作。
  var CAMERA_FEATURE_OFF = [
    { wrapper: ".image-wrapper_1", label: ".text_64", action: "camera_feature_talk" },
    { wrapper: ".image-wrapper_2", label: ".text_65", action: "camera_feature_privacy" },
    { wrapper: ".image-wrapper_3", label: ".text_66", action: "camera_feature_flip" },
    { wrapper: ".image-wrapper_4", label: ".text_67", action: "camera_feature_alarm" },
    { wrapper: ".image-wrapper_5", label: ".text_68", action: "camera_feature_original" },
    { wrapper: ".image-wrapper_6", label: ".text_69", action: "camera_feature_cruise" },
    { wrapper: ".image-wrapper_8", label: ".text_71", action: "camera_feature_chart" },
    { wrapper: ".image-wrapper_9", label: ".text_72", action: "camera_feature_custom" }
  ];

  function nativeReady() {
    return !!(window.IcuNative && typeof window.IcuNative.firstPhaseState === "function");
  }

  // 打印结果回推：原生每次打印结束都会带一个新序号，这里只在序号变化时提示一次。
  var lastPrintStatusSeq = 0;

  function checkPrintStatus(state) {
    var status = state && state.printStatus;
    if (!status) {
      return;
    }
    var seq = Number(status.seq || 0);
    if (!seq || seq === lastPrintStatusSeq) {
      return;
    }
    lastPrintStatusSeq = seq;
    var stage = clean(status.stage);
    var phase = clean(status.phase);
    var requestId = Number(status.requestId || 0);
    window.__icuPdfDownloadStatus = {
      seq: seq,
      requestId: requestId,
      stage: stage,
      phase: phase,
      ok: status.ok === true,
      msg: clean(status.msg),
      fileName: clean(status.fileName),
      uri: clean(status.uri),
      elapsedMs: Number(status.elapsedMs || 0)
    };
    // generating/publishing 只更新诊断，避免一次下载连续弹出多条 Toast。
    if (stage === "generating" || stage === "publishing") {
      return;
    }
    var pdfResult = requestId > 0 || stage === "queued" || stage === "success" || stage === "error";
    var fallback = phase === "running" ? "PDF下载请求已受理，等待文件发布"
      : (status.ok ? (pdfResult ? "PDF下载成功" : "打印成功")
        : (pdfResult ? "PDF下载失败" : "打印失败"));
    showPrintToast(clean(status.msg) || fallback);
  }

  function showPrintToast(message) {
    if (window.IcuToast && typeof window.IcuToast.show === "function") {
      window.IcuToast.show(message);
      return;
    }
    if (window.IcuOverlay && typeof window.IcuOverlay.showToast === "function") {
      window.IcuOverlay.showToast(message);
    }
  }

  function readFirstPhaseState() {
    if (!nativeReady()) {
      return {};
    }
    try {
      var state = JSON.parse(window.IcuNative.firstPhaseState() || "{}") || {};
      checkPrintStatus(state);
      return state;
    } catch (error) {
      return {};
    }
  }

  function callAction(action, label) {
    if (!window.IcuNative || typeof window.IcuNative.action !== "function") {
      return;
    }
    try {
      window.IcuNative.action(action, label || action, window.location.pathname || "");
    } catch (error) {
    }
    schedule(0);
  }

  function clean(value) {
    if (value === undefined || value === null) {
      return "";
    }
    value = String(value).replace(/\s+/g, " ").trim();
    if (!value || value === "-" || value === "--" || value === "/" || value === "null" || value === "undefined") {
      return "";
    }
    // 这里只规范化真实数据，不再按“咪咪/李某/手机号”等内容猜演示值。
    // 设计稿占位只在首次数据注入前由 normalizeDeadText 清理。
    if (isCommonAnimalSpecies(value)) {
      return value;
    }
    return value;
  }

  function isCommonAnimalSpecies(value) {
    var compact = String(value || "").replace(/\s+/g, "");
    return compact === "\u732b" ||
      compact === "\u72d7" ||
      compact === "\u72ac" ||
      compact === "\u5154" ||
      compact === "\u9e1f" ||
      compact === "\u4ed3\u9f20" ||
      compact === "\u8c5a\u9f20" ||
      compact === "\u9f99\u732b";
  }

  function isDemoValue(value) {
    var compact = String(value || "").replace(/\s+/g, "");
    return compact === "饭团" ||
      compact === "饭团饭团" ||
      compact === "咪咪" ||
      compact === "李某" ||
      compact === "铲屎官" ||
      compact === "力大狮" ||
      compact === "黄医生" ||
      compact === "猫" ||
      compact === "01234567891" ||
      compact === "A0000001" ||
      compact === "A00000001";
  }

  function display(value) {
    return clean(value) || empty;
  }

  var PATIENT_DETAIL_KEYS = {
    petName: true,
    species: true,
    sex: true,
    age: true,
    ownerName: true,
    ownerPhone: true,
    doctor: true,
    note: true
  };

  // 只统一患者资料的显示占位；原始病例与临时编辑表单仍保留真实空值。
  function displayPatientField(value) {
    var text = clean(value);
    if (!text || text === "待录入" || text === "未命名" || text === "未知") {
      return "待录入";
    }
    return text;
  }

  // 患者信息表单页：报告页/设置页复用了同名字段类名，按“宠物名称”标签判定，避免误裁剪
  // 页面标记（markPageContext）与患者数据裁剪都要用到，故放在顶层作用域
  function isPatientInfoPage() {
    var label = document.querySelector(".text_33");
    return !!(label && (label.textContent || "").replace(/\s+/g, "") === "宠物名称");
  }

  // renderCommon 在多数页面都会走患者资料兜底分支，
  // 这里必须放在 IIFE 顶层，不能只定义在 installPatientTempStore 内部。
  function patientValue(patient, key) {
    var p = patient || {};
    if (key === "petListName") {
      return "宠物名：" + displayPatientField(p.petName);
    }
    if (key === "speciesOwner") {
      return "种类：" + displayPatientField(p.species) + "     主人：" + displayPatientField(p.ownerName);
    }
    if (key === "caseNo") {
      var no = clean(p.caseNo) || clean(p.recordNo);
      return no ? "编号：" + no : "";
    }
    if (key === "recordNo") {
      return clean(p.recordNo) || clean(p.caseNo);
    }
    return p[key] || "";
  }

  function installPatientTempStore() {
    if (window.IcuPatientTemp) {
      return;
    }
    // 左右舱隔离：病例临时数据按舱存放，左右舱各看各的宠物档案
    var storeKeyBase = "icu_temp_patients_v1";
    var selectedKeyBase = "icu_temp_patient_selected_v1";
    var storeZone = "right";
    var windowNameKey = "__icu_patient_temp__";
    var memoryPatients = [];
    var searchQuery = "";
    var clicksInstalled = false;
    var modal = null;
    var lastNativePatientPayload = "";
    var localSelectionPendingUntil = 0;
    var lastSelectionId = "";
    var lastSelectionAt = 0;
    // 新建样本只在末尾追加。局部刷新不得改动侧栏滚动位置：
    // 旧实现检测到记录变多就滚到 scrollHeight，六张静态卡会整块上移，
    // 而且没有任何代码把 scrollTop 复位，只有切页面重建 DOM 才恢复。

    function blankPatient() {
      return {
        id: "",
        petName: "",
        species: "",
        sex: "",
        age: "",
        recordNo: "",
        caseNo: "",
        ownerName: "",
        ownerPhone: "",
        doctor: "",
        visitDate: "",
        note: "",
        currentTreatment: false,
        // 用户主动新建的样本：三项必填没录入也要留在列表里
        explicitSample: false,
        treatmentStartTime: "",
        treatmentEndTime: ""
      };
    }

    function storageGet(key) {
      var value = "";
      try {
        value = window.sessionStorage.getItem(key) || "";
      } catch (error) {
      }
      if (value) {
        return value;
      }
      try {
        var bag = JSON.parse(window.name || "{}") || {};
        return bag[windowNameKey] && bag[windowNameKey][key] ? String(bag[windowNameKey][key]) : "";
      } catch (error2) {
        return "";
      }
    }

    function storageSet(key, value) {
      try {
        window.sessionStorage.setItem(key, value);
      } catch (error) {
      }
      try {
        var bag = JSON.parse(window.name || "{}") || {};
        bag[windowNameKey] = bag[windowNameKey] || {};
        bag[windowNameKey][key] = value;
        window.name = JSON.stringify(bag);
      } catch (error2) {
      }
    }

    function normalizeStoreZone(value) {
      var zone = String(value || "").toLowerCase();
      return zone === "left" ? "left" : "right";
    }

    function storeKey() {
      return storeKeyBase + "_" + normalizeStoreZone(storeZone);
    }

    function selectedKey() {
      return selectedKeyBase + "_" + normalizeStoreZone(storeZone);
    }

    /**
     * 切换舱位：临时病例列表按舱分开存，切舱时换成另一舱的列表，
     * 并清掉内存缓存，避免把上一舱的宠物显示到当前舱。
     */
    function setZone(zone) {
      var normalized = normalizeStoreZone(zone);
      if (normalized === storeZone) {
        return;
      }
      storeZone = normalized;
      memoryPatients = [];
      loadPatients();
    }

    /** 病例列表当前属于哪个舱：列表按舱存，这个舱位才是数据的归属。 */
    function currentStoreZone() {
      return normalizeStoreZone(storeZone);
    }

    function loadPatients() {
      try {
        var raw = storageGet(storeKey());
        var parsed = raw ? JSON.parse(raw) : [];
        memoryPatients = normalizePatientList(Array.isArray(parsed) ? parsed : []);
      } catch (error) {
        memoryPatients = normalizePatientList(memoryPatients || []);
      }
      return memoryPatients;
    }

    function savePatients(list) {
      memoryPatients = normalizePatientList(list || []);
      storageSet(storeKey(), JSON.stringify(memoryPatients));
    }

    function selectedId() {
      return storageGet(selectedKey());
    }

    function setSelectedId(id) {
      storageSet(selectedKey(), id || "");
    }

    function currentPatient() {
      var list = loadPatients();
      var id = selectedId();
      for (var i = 0; i < list.length; i += 1) {
        if (list[i].id === id) {
          return copyPatient(list[i]);
        }
      }
      if (list.length) {
        setSelectedId(list[0].id);
        return copyPatient(list[0]);
      }
      return blankPatient();
    }

    function copyPatient(item) {
      var p = blankPatient();
      if (!item) {
        return p;
      }
      for (var key in p) {
        if (Object.prototype.hasOwnProperty.call(p, key)) {
          if (key === "currentTreatment" || key === "explicitSample") {
            p[key] = item[key] === true || item[key] === "true";
          } else {
            p[key] = clean(item[key]);
          }
        }
      }
      p.id = item.id || "";
      p.caseNo = clean(item.caseNo) || clean(item.recordNo);
      p.recordNo = clean(item.recordNo) || clean(item.caseNo);
      return p;
    }

    function isPlaceholderPatientText(value) {
      var text = clean(value);
      if (!text || text === "-" || text === "--" || text === "/") {
        return true;
      }
      if (/^0{1,6}$/.test(text)) {
        return true;
      }
      return text === "\u5f85\u5f55\u5165" ||
        text === "\u672a\u547d\u540d" ||
        text === "\u672a\u77e5" ||
        text === "null" ||
        text === "undefined";
    }

    function patientHasListData(item) {
      if (!item) {
        return false;
      }
      var p = copyPatient(item);
      // 用户点新建产生的样本永远保留：新建下一份时上一份会结束治疗，
      // 若按「有没有录入字段」过滤，没填过的样本会被整条丢掉，
      // 页面就只剩最新那一张卡，看起来像只换了病例编号。
      if (p.explicitSample) {
        return true;
      }
      // 当前治疗即使还没填写患者信息也要保留在列表里，否则新建的空记录会直接消失
      if (p.currentTreatment) {
        return true;
      }
      var keys = ["petName", "species", "ownerName", "ownerPhone", "doctor"];
      for (var i = 0; i < keys.length; i += 1) {
        if (!isPlaceholderPatientText(p[keys[i]])) {
          return true;
        }
      }
      return false;
    }

    function normalizePatientList(list) {
      var result = [];
      var seen = {};
      for (var i = 0; i < list.length; i += 1) {
        var item = copyPatient(list[i]);
        if (!patientHasListData(item)) {
          continue;
        }
        if (!item.id) {
          item.id = "tmp_" + Date.now() + "_" + i;
        }
        if (seen[item.id]) {
          item.id = item.id + "_" + i;
        }
        seen[item.id] = true;
        result.push(item);
      }
      return result;
    }

    function writePatientLive(patient) {
      var nodes = document.querySelectorAll("[data-patient-live]");
      for (var i = 0; i < nodes.length; i += 1) {
        var key = nodes[i].getAttribute("data-patient-live") || "";
        var value = patientValue(patient, key);
        nodes[i].textContent = PATIENT_DETAIL_KEYS[key] ? displayPatientField(value) : display(value);
      }
    }

    // 患者字段：蓝湖导出的类名在各页面一致，值过长时必须在所属白框内裁剪
    var PATIENT_FIELD_SELECTORS = [
      ".text_10", ".text_11", ".text_12", ".text_13",
      ".text_35", ".text_36", ".text_39", ".text_40",
      ".text_43", ".text_44", ".text_47", ".text_48",
      ".paragraph_1", ".text-group_1"
    ];

    // 侧栏宠物名：优先使用属性标记，兼容没有属性标记的页面
    function petListNameNode() {
      var live = document.querySelector("[data-patient-live=petListName]");
      if (live) {
        return live;
      }
      return isPatientInfoPage() ? document.querySelector(".text_10") : null;
    }

    // 侧栏患者卡片：同时包含宠物名与种类字段的最近祖先容器
    function patientSidebarCard() {
      var name = petListNameNode();
      if (!name) {
        return null;
      }
      var desc = document.querySelector(".text_11");
      var node = name.parentElement;
      while (node && node !== document.body) {
        if (desc && node.contains(desc)) {
          return node;
        }
        node = node.parentElement;
      }
      return null;
    }

    // 侧栏患者列表行：与 sidebarConfig 槽位一致，覆盖各页面不同的类名
    function sidebarValueNodes() {
      var nodes = [];
      var slots = sidebarConfig().slots;
      for (var i = 0; i < slots.length; i += 1) {
        var selectors = [slots[i].name, slots[i].desc, slots[i].no, slots[i].date];
        for (var j = 0; j < selectors.length; j += 1) {
          var node = querySidebar(selectors[j]);
          if (node && nodes.indexOf(node) < 0) {
            nodes.push(node);
          }
        }
      }
      return nodes;
    }

    // 需要裁剪的数值节点：属性驱动页面 + 结构驱动页面 + 侧栏患者列表行
    function patientValueNodes() {
      var nodes = [];
      var i;
      var live = document.querySelectorAll("[data-patient-live]");
      for (i = 0; i < live.length; i += 1) {
        nodes.push(live[i]);
      }
      if (isPatientInfoPage()) {
        for (i = 0; i < PATIENT_FIELD_SELECTORS.length; i += 1) {
          var field = document.querySelector(PATIENT_FIELD_SELECTORS[i]);
          if (field && nodes.indexOf(field) < 0) {
            nodes.push(field);
          }
        }
        var slotNodes = sidebarValueNodes();
        for (i = 0; i < slotNodes.length; i += 1) {
          if (nodes.indexOf(slotNodes[i]) < 0) {
            nodes.push(slotNodes[i]);
          }
        }
      }
      return nodes;
    }

    // 数值输入框：超长数值必须在框内裁剪显示，不能压到相邻字段
    function syncPatientValueBoxes() {
      var nodes = patientValueNodes();
      var nameRowWidth = 0;
      var i;
      var name = petListNameNode();
      if (name && name.parentElement && name.parentElement.clientWidth > 0) {
        nameRowWidth = name.parentElement.clientWidth;
      }
      for (i = 0; i < nodes.length; i += 1) {
        var node = nodes[i];
        if (node.getAttribute("data-value-box-ready") === "1") {
          continue;
        }
        var parent = node.parentElement;
        if (!parent) {
          continue;
        }
        node.classList.add("icu-value-text");
        parent.classList.add("icu-value-box");
        if (isColumnFlexBox(parent)) {
          node.classList.add("icu-value-fill");
        } else if (isSidebarValue(parent)) {
          fitSidebarValue(node, parent, nameRowWidth);
        } else {
          node.classList.add("icu-value-grow");
        }
        node.setAttribute("data-value-box-ready", "1");
      }
    }

    function isColumnFlexBox(node) {
      if (!node) {
        return false;
      }
      if (node.classList.contains("flex-col")) {
        return true;
      }
      if (node.classList.contains("flex-row")) {
        return false;
      }
      var style = window.getComputedStyle(node);
      return String(style.display || "").indexOf("column") >= 0;
    }

    // 侧栏患者卡片：数值行统一卡片内宽，长数值在卡片内省略而不是溢出
    function isSidebarValue(parent) {
      if (parent.closest && parent.closest(".icu-patient-sidebar")) {
        return true;
      }
      var card = patientSidebarCard();
      return !!(card && card.contains(parent));
    }

    function fitSidebarValue(node, parent, nameRowWidth) {
      if (nameRowWidth > 0 && parent.clientWidth < nameRowWidth) {
        parent.style.setProperty("width", nameRowWidth + "px", "important");
      }
      node.classList.add("icu-value-grow");
    }

    // 底部医嘱面板：显示当前医嘱正文，超长时在面板内滚动
    function writeAdvicePanel(patient) {
      var body = document.querySelector("[data-patient-advice-body]");
      if (!body) {
        return;
      }
      var text = displayPatientField(patientValue(patient, "note"));
      if (body.textContent !== text) {
        body.textContent = text;
      }
    }

    function writePatientFallback(patient) {
      if (!document.querySelector(".box_6")) {
        return;
      }
      setText(".text_10", patientValue(patient, "petListName"));
      setText(".text_11", patientValue(patient, "speciesOwner"));
      setText(".text_12", patientValue(patient, "caseNo"));
      setText(".text_13", patient.visitDate);
      setText(".text_35", displayPatientField(patient.petName));
      setText(".text_36", displayPatientField(patient.species));
      setText(".text_39", displayPatientField(patient.age));
      setText(".text_40", patient.recordNo || patient.caseNo);
      setText(".text_43", displayPatientField(patient.ownerName));
      setText(".text_44", displayPatientField(patient.ownerPhone));
      setText(".text_47", displayPatientField(patient.doctor));
      setText(".text_48", patient.visitDate);
      setText(".paragraph_1", displayPatientField(patient.note));
    }

    function withZone(patient) {
      var data = {};
      for (var key in patient) {
        if (Object.prototype.hasOwnProperty.call(patient, key)) {
          data[key] = patient[key];
        }
      }
      data.zone = normalizeStoreZone(storeZone);
      return data;
    }

    function syncNativePatient(patient, force) {
      if (!window.IcuNative || typeof window.IcuNative.syncTempPatient !== "function") {
        return;
      }
      try {
        // 带上舱位，原生据此判断这份数据是不是当前舱的
        var payload = JSON.stringify(withZone(patient || currentPatient() || blankPatient()));
        if (!force && payload === lastNativePatientPayload) {
          return;
        }
        lastNativePatientPayload = payload;
        window.IcuNative.syncTempPatient(payload);
      } catch (error) {
      }
    }

    function currentPageName() {
      var path = window.location.pathname || "";
      var match = path.match(/src\/views\/([^/]+)\//);
      return match ? match[1] : "";
    }

    function sidebarConfig() {
      var page = currentPageName();
      if (page === "lanhu_2zhujikongzhi") {
        return {
          container: ".block_2",
          search: ".block_5",
          slots: [
            { box: ".block_6", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".section_2" },
            { box: ".section_31", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
            { box: ".section_32", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".group_6" },
            { box: ".section_33", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".group_7" },
            { box: ".section_34", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".block_11" },
            { box: ".section_35", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".box_1" }
          ]
        };
      }
      if (page === "lanhu_3shishijianhu") {
        return {
          container: ".box_3",
          search: ".group_6",
          slots: [
            { box: ".group_7", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".section_2" },
            { box: ".group_30", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
            { box: ".group_31", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".group_10" },
            { box: ".group_32", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".block_1" },
            { box: ".group_33", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".block_2" },
            { box: ".group_34", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".box_5" }
          ]
        };
      }
      if (page === "lanhu_4jiankong") {
        return {
          container: ".group_1",
          search: ".box_5",
          slots: [
            { box: ".box_6", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".block_5" },
            { box: ".box_37", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
            { box: ".box_38", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".box_9" },
            { box: ".box_39", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".box_11" },
            { box: ".box_40", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".block_6" },
            { box: ".box_41", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".box_14" }
          ]
        };
      }
      if (page === "lanhu_5jiankonghuifang") {
        return {
          container: ".group_2",
          search: ".box_5",
          slots: [
            { box: ".box_6", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".section_3" },
            { box: ".box_35", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
            { box: ".box_36", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".group_5" },
            { box: ".box_37", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".block_1" },
            { box: ".box_38", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".block_2" },
            { box: ".box_39", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".group_6" }
          ]
        };
      }
      if (page === "lanhu_6zhiliaojilu") {
        return {
          container: ".group_1",
          search: ".section_3",
          slots: [
            { box: ".section_4", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".group_2" },
            { box: ".box_41", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
            { box: ".box_42", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".box_5" },
            { box: ".box_43", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".group_3" },
            { box: ".box_44", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".section_9" },
            { box: ".box_45", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".box_6" }
          ]
        };
      }
      if (page === "lanhu_7jiaocheng") {

        return {

          container: ".group_2",

          search: ".box_5",

          slots: [

            { box: ".box_6", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".section_2" },

            { box: ".section_9", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },

            { box: ".section_10", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".box_9" },

            { box: ".section_11", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".block_4" },

            { box: ".section_12", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".section_3" },

            { box: ".section_13", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".group_3" }

          ]

        };

      }

      return {
        container: ".section_1",
        search: ".box_6",
        slots: [
          { box: ".box_7", name: ".text_10", desc: ".text_11", no: ".text_12", date: ".text_13", bullet: ".box_8" },
          { box: ".box_9", name: ".text-group_1", desc: ".text_14", no: ".text_15", date: ".text_16", bullet: ".thumbnail_6" },
          { box: ".box_10", name: ".text_17", desc: ".text_18", no: ".text_19", date: ".text_20", bullet: ".section_3" },
          { box: ".box_11", name: ".text_21", desc: ".text_22", no: ".text_23", date: ".text_24", bullet: ".group_3" },
          { box: ".box_12", name: ".text_25", desc: ".text_26", no: ".text_27", date: ".text_28", bullet: ".block_2" },
          { box: ".box_13", name: ".text_29", desc: ".text_30", no: ".text_31", date: ".text_32", bullet: ".block_3" }
        ]
      };
    }

    function sidebarContainer() {
      var config = sidebarConfig();
      var node = document.querySelector(config.container);
      if (node) {
        node.classList.add("icu-patient-sidebar");
      }
      return node;
    }

    function querySidebar(selector) {
      var container = sidebarContainer();
      return container ? container.querySelector(selector) : null;
    }

    function hideSidebarRows(selector) {
      var container = sidebarContainer();
      var nodes = container ? container.querySelectorAll(selector) : [];
      for (var i = 0; i < nodes.length; i += 1) {
        nodes[i].style.visibility = "hidden";
      }
    }

    function containsNode(parent, child) {
      while (child && child !== document.body) {
        if (child === parent) {
          return true;
        }
        child = child.parentNode;
      }
      return false;
    }

    function layoutScale() {
      var value = "1";
      try {
        value = window.getComputedStyle(document.documentElement).getPropertyValue("--lanhu-scale") || "1";
      } catch (error) {
      }
      var scale = parseFloat(value);
      return scale > 0 ? scale : 1;
    }

    function ensureSearchInput() {
      var config = sidebarConfig();
      var container = sidebarContainer();
      var box = container ? container.querySelector(config.search) : null;
      if (!box || box.querySelector(".icu-patient-search-input")) {
        return;
      }
      var label = box.querySelector(".text_9");
      if (label) {
        label.style.display = "none";
      }
      var input = document.createElement("input");
      input.className = "icu-patient-search-input";
      input.type = "text";
      input.setAttribute("inputmode", "search");
      input.setAttribute("autocomplete", "off");
      input.setAttribute("autocorrect", "off");
      input.setAttribute("spellcheck", "false");
      input.placeholder = "搜索宠物名字/主人";
      input.value = searchQuery;
      box.setAttribute("data-icu-search-box", "1");
      input.addEventListener("input", function () {
        searchQuery = input.value || "";
        render();
      });
      input.addEventListener("click", function (event) {
        event.stopPropagation();
      }, true);
      input.addEventListener("touchend", function (event) {
        event.stopPropagation();
      }, { capture: true, passive: true });
      box.appendChild(input);
    }

    // 医院信息 / LOGO / 机构切换 / 搜索属于侧栏固定头部，不能跟样本列表一起滚走。
    // 只在首张样本卡之前包一层，连续局部刷新复用同一个节点，不重建搜索输入和 LOGO 状态。
    function ensureSidebarFixedHeader(container) {
      if (!container) {
        return null;
      }
      var existing = container.querySelector(".icu-sidebar-fixed-header");
      if (existing) {
        return existing;
      }
      var slots = listSlots();
      var firstBox = slots.length ? container.querySelector(slots[0].box) : null;
      if (!firstBox) {
        return null;
      }
      var boundary = firstBox;
      while (boundary && boundary.parentElement !== container) {
        boundary = boundary.parentElement;
      }
      if (!boundary) {
        return null;
      }
      var leading = [];
      for (var node = container.firstElementChild; node && node !== boundary; node = node.nextElementSibling) {
        leading.push(node);
      }
      if (!leading.length) {
        return null;
      }
      var header = document.createElement("div");
      header.className = "icu-sidebar-fixed-header";
      container.insertBefore(header, boundary);
      for (var i = 0; i < leading.length; i += 1) {
        header.appendChild(leading[i]);
      }
      return header;
    }

    function listSlots() {
      return sidebarConfig().slots;
    }

    function matchesQuery(item) {
      var q = clean(searchQuery).toLowerCase();
      if (!q) {
        return true;
      }
      var text = [item.petName, item.ownerName, item.species, item.recordNo, item.caseNo, item.ownerPhone].join(" ").toLowerCase();
      return text.indexOf(q) >= 0;
    }

    function filteredPatients() {
      // 顺序就是原生下发的顺序：旧样本在上，新建样本依次追加在下面。
      // 不能再按 currentTreatment 把新样本顶到第一行，否则新建永远像「只换编号」。
      return loadPatients().filter(patientHasListData).filter(matchesQuery);
    }

    function importNativeRecords(records, selectedIndex) {
      if (!Array.isArray(records)) {
        return;
      }
      var previousSelected = selectedId();
      var list = [];
      var selectedNativeId = "";
      for (var i = 0; i < records.length; i += 1) {
        var item = copyPatient(records[i]);
        if (!patientHasListData(item)) {
          continue;
        }
        item.id = "native_" + i;
        list.push(item);
        if (i === selectedIndex) {
          selectedNativeId = item.id;
        }
      }
      // 不再因为记录数增加请求「滚到底」：新样本追加在末尾即可，
      // 侧栏本身可滑动查看，改动 scrollTop 会让整张列表上移。
      savePatients(list);
      var keepLocalSelection = false;
      if (previousSelected && Date.now() < localSelectionPendingUntil) {
        for (var j = 0; j < list.length; j += 1) {
          if (list[j].id === previousSelected) {
            keepLocalSelection = true;
            break;
          }
        }
      }
      if (keepLocalSelection) {
        setSelectedId(previousSelected);
      } else if (selectedNativeId) {
        setSelectedId(selectedNativeId);
      } else if (list.length) {
        setSelectedId(list[0].id);
      } else {
        setSelectedId("");
      }
    }

    function isCurrentSelected() {
      return !!currentPatient().currentTreatment;
    }

    /**
     * 当前宠物的治疗还没结束时，不允许切到同一舱里的其他宠物。
     * 治疗数据、监护门限与波形都是跟着宠物走的，中途换走这一段治疗就断了。
     */
    function isPetSwitchBlocked(targetId) {
      if (!targetId || targetId === selectedId()) {
        return false;
      }
      if (!isCurrentSelected()) {
        return false;
      }
      var text = "当前宠物的治疗尚未结束，不能切换到其他宠物";
      if (window.IcuToast && typeof window.IcuToast.show === "function") {
        window.IcuToast.show(text);
      } else if (window.IcuOverlay && typeof window.IcuOverlay.showToast === "function") {
        window.IcuOverlay.showToast(text);
      }
      return true;
    }

    function selectCurrentTreatment() {
      var list = loadPatients();
      for (var i = 0; i < list.length; i += 1) {
        if (list[i].currentTreatment) {
          setSelectedId(list[i].id);
          render();
          window.dispatchEvent(new CustomEvent("icu-patient-temp-change"));
          return true;
        }
      }
      return false;
    }

    function setTextIn(scope, selector, value) {
      var node = scope ? scope.querySelector(selector) : querySidebar(selector);
      if (node) {
        node.style.visibility = "";
        node.textContent = display(value);
      }
    }

    function slotNodes(slot) {
      var selectors = [slot.box, slot.name, slot.desc, slot.no, slot.date];
      var nodes = [];
      var container = sidebarContainer();
      for (var i = 0; i < selectors.length; i += 1) {
        var node = container ? container.querySelector(selectors[i]) : null;
        if (node && nodes.indexOf(node) < 0) {
          nodes.push(node);
        }
      }
      return nodes;
    }

    function slotVisualNodes(slot) {
      var nodes = slotNodes(slot);
      var bullet = slot.bullet ? querySidebar(slot.bullet) : null;
      if (bullet && nodes.indexOf(bullet) < 0) {
        nodes.push(bullet);
      }
      return nodes;
    }

    function setSlotVisible(slot, visible) {
      var nodes = slotVisualNodes(slot);
      for (var i = 0; i < nodes.length; i += 1) {
        nodes[i].style.visibility = visible ? "" : "hidden";
      }
    }

    function markSlotTargets(slot, item, index) {
      var nodes = slotVisualNodes(slot);
      var id = item ? item.id : "";
      for (var i = 0; i < nodes.length; i += 1) {
        nodes[i].setAttribute("data-temp-patient-slot", String(index));
        nodes[i].setAttribute("data-temp-patient-id", id);
      }
    }

    function removeRecordLayers() {
      var layers = document.querySelectorAll(".icu-record-hit-layer,.icu-record-active-bg");
      for (var i = 0; i < layers.length; i += 1) {
        if (layers[i].parentNode) {
          layers[i].parentNode.removeChild(layers[i]);
        }
      }
    }

    function clampRowBounds(top, height, sectionHeight) {
      var rowTop = Math.max(0, top);
      var rowHeight = Math.max(0, Math.min(height, sectionHeight - rowTop));
      if (rowHeight <= 0) {
        return null;
      }
      return {
        top: rowTop,
        height: rowHeight
      };
    }

    function rowBounds(sectionRect, slot, scale) {
      scale = scale || layoutScale();
      var section = sidebarContainer();
      // getBoundingClientRect 是可视区坐标；绝对定位图层的 top 则是滚动内容坐标。
      // 局部刷新发生在滚动之后时必须加回 scrollTop，否则白色选中背景会盖到别的样本上。
      var scrollTop = section ? section.scrollTop : 0;
      var sectionHeight = Math.max(
        sectionRect.height / scale,
        section ? section.scrollHeight : 0
      );
      var box = querySidebar(slot.box);
      var firstBox = sidebarConfig().slots[0] && sidebarConfig().slots[0].box;
      if (box && slot.box === firstBox) {
        var boxRect = box.getBoundingClientRect();
        if (boxRect && boxRect.width > 0 && boxRect.height > 0) {
          return clampRowBounds(
            (boxRect.top - sectionRect.top) / scale + scrollTop,
            boxRect.height / scale,
            sectionHeight
          );
        }
      }
      var nodes = slotNodes(slot);
      var top = Infinity;
      var bottom = -Infinity;
      for (var i = 0; i < nodes.length; i += 1) {
        var rect = nodes[i].getBoundingClientRect();
        if (!rect || rect.width <= 0 || rect.height <= 0) {
          continue;
        }
        top = Math.min(top, rect.top);
        bottom = Math.max(bottom, rect.bottom);
      }
      if (top === Infinity || bottom <= top) {
        return null;
      }
      return clampRowBounds(
        (top - sectionRect.top) / scale + scrollTop - 12,
        (bottom - top) / scale + 24,
        sectionHeight
      );
    }

    function setColor(selector, color) {
      var node = querySidebar(selector);
      if (node) {
        node.style.color = color;
      }
    }

    function setBulletColor(slot, active) {
      var bullet = slot.bullet ? querySidebar(slot.bullet) : null;
      if (bullet) {
        bullet.style.backgroundColor = active ? "rgba(0, 116, 255, 1)" : "rgba(255, 255, 255, 1)";
      }
    }

    function applySlotVisualState(slot, item, currentId) {
      var active = !!item && item.id === currentId;
      var nodes = slotNodes(slot);
      var firstBox = sidebarConfig().slots[0] && sidebarConfig().slots[0].box;
      for (var i = 0; i < nodes.length; i += 1) {
        nodes[i].classList.add("icu-temp-patient-row-node");
        nodes[i].classList.toggle("icu-temp-patient-row-active", active);
      }
      var box = querySidebar(slot.box);
      if (box) {
        box.classList.toggle("icu-temp-patient-active", active);
        if (slot.box === firstBox) {
          box.style.backgroundColor = active ? "rgba(255, 255, 255, 1)" : "transparent";
        }
      }
      setColor(slot.name, active ? "rgba(0, 116, 255, 1)" : "rgba(255, 255, 255, 1)");
      setColor(slot.desc, active ? "rgba(87, 87, 87, 1)" : "rgba(255, 255, 255, 1)");
      setColor(slot.no, active ? "rgba(87, 87, 87, 1)" : "rgba(255, 255, 255, 1)");
      setColor(slot.date, active ? "rgba(87, 87, 87, 1)" : "rgba(255, 255, 255, 1)");
      setBulletColor(slot, active);
    }

    function ensureRecordLayers(slots, rows, currentId) {
      var section = sidebarContainer();
      var config = sidebarConfig();
      if (!section || !section.querySelector(config.search)) {
        removeRecordLayers();
        return;
      }
      removeRecordLayers();
      var sectionRect = section.getBoundingClientRect();
      if (!sectionRect || !sectionRect.width || !sectionRect.height) {
        return;
      }
      var scale = layoutScale();
      var sectionWidth = section.offsetWidth || (sectionRect.width / scale);
      for (var i = 0; i < slots.length; i += 1) {
        var item = rows[i] ? copyPatient(rows[i]) : null;
        if (!item || !item.id) {
          continue;
        }
        var bounds = rowBounds(sectionRect, slots[i], scale);
        if (!bounds) {
          continue;
        }
        if (item.id === currentId) {
          var activeBg = document.createElement("div");
          activeBg.className = "icu-record-active-bg";
          activeBg.style.top = bounds.top + "px";
          activeBg.style.height = bounds.height + "px";
          section.appendChild(activeBg);
        }
        var layer = document.createElement("div");
        layer.className = "icu-record-hit-layer";
        layer.setAttribute("data-temp-patient-slot", String(i));
        layer.setAttribute("data-temp-patient-id", item.id);
        layer.style.left = "0px";
        layer.style.top = bounds.top + "px";
        layer.style.width = sectionWidth + "px";
        layer.style.height = bounds.height + "px";
        section.appendChild(layer);
      }
    }

    // 侧栏样本卡各行：设计稿给的是固定 13~16px 行高，实际行盒约 21px，
    // 叠加上层 overflow:hidden 会把中文/数字下缘裁掉，这里统一按内容自适应。
    function applySidebarRowMetrics(slot, container) {
      var selectors = [slot.name, slot.desc, slot.no, slot.date];
      for (var i = 0; i < selectors.length; i += 1) {
        var node = container ? container.querySelector(selectors[i]) : querySidebar(selectors[i]);
        if (!node) {
          continue;
        }
        node.classList.add("icu-sidebar-text");
        var parent = node.parentElement;
        // 文本行直接挂在侧栏容器上时不要动容器本身，避免侧栏高度被改成自适应
        if (parent && parent !== container) {
          parent.classList.add(i === 0 ? "icu-sidebar-name-wrap" : "icu-sidebar-data-wrap");
        } else if (i > 0) {
          node.classList.add("icu-sidebar-text-row");
        }
      }
    }

    // 左侧样本信息框：ICU 标识统一钉在卡片右上角。
    // 蓝湖导出的六个槽位各自给了不同的 margin-left（有的 151px、有的 0），
    // 新建样本换槽位后位置就会变；这里统一打标，由 CSS 绝对定位到卡片右上角。
    function pinIcuBadges(container) {
      if (!container) {
        return 0;
      }
      var imgs = container.querySelectorAll("img");
      var count = 0;
      for (var i = 0; i < imgs.length; i += 1) {
        if (/(^|\s)image_[2-7](\s|$)/.test(imgs[i].className || "")) {
          imgs[i].setAttribute("data-icu-badge", "1");
          count += 1;
        }
      }
      return count;
    }

    // 设计稿只画了 6 个静态样本槽。第 7 条起按同样顺序生成追加卡片，
    // 侧栏本体改成可上下滑动，新建的样本永远排在上一个样本下面。
    function renderExtraCards(container, startIndex, rows, currentId) {
      var host = container.querySelector(".icu-patient-extra-list");
      if (!host) {
        host = document.createElement("div");
        host.className = "icu-patient-extra-list";
        container.appendChild(host);
      }
      var extraCount = Math.max(0, rows.length - startIndex);
      while (host.children.length > extraCount) {
        host.removeChild(host.lastChild);
      }
      for (var i = 0; i < extraCount; i += 1) {
        var item = copyPatient(rows[startIndex + i]);
        var card = host.children[i];
        if (!card) {
          card = document.createElement("div");
          card.className = "icu-patient-extra-card icu-sidebar-card";
          card.innerHTML = '<div class="icu-extra-name"></div>'
            + '<div class="icu-extra-desc"></div>'
            + '<div class="icu-extra-no"></div>'
            + '<div class="icu-extra-date"></div>'
            + '<span class="icu-extra-icu">ICU</span>';
          host.appendChild(card);
        }
        var slotIndex = startIndex + i;
        card.setAttribute("data-temp-patient-slot", String(slotIndex));
        card.setAttribute("data-temp-patient-id", item.id || "");
        card.classList.toggle("icu-temp-patient-active",
          !!item.id && item.id === currentId);
        card.querySelector(".icu-extra-name").textContent =
          patientValue(item, "petListName") || "宠物名：待录入";
        card.querySelector(".icu-extra-desc").textContent =
          patientValue(item, "speciesOwner") || "种类：待录入     主人：待录入";
        card.querySelector(".icu-extra-no").textContent =
          patientValue(item, "caseNo") || "编号：待录入";
        card.querySelector(".icu-extra-date").textContent =
          display(item.visitDate || "待录入");
      }
      // 原生点击热区只覆盖静态 6 槽；追加卡片自带 data-temp-patient-id，走事件委托
      host.style.display = extraCount ? "" : "none";
      container.classList.toggle("icu-patient-scrollable", extraCount > 0);
    }

    function renderList() {
      var slots = listSlots();
      var rows = filteredPatients();
      var current = selectedId();
      var container = sidebarContainer();
      if (!container) {
        removeRecordLayers();
        return;
      }
      // 局部刷新保持滚动锚点：增删末尾卡片、切换显示态或浏览器自身的
      // 滚动钳制都不能把用户当前看到的样本列表整块推上去。
      var anchorScrollTop = container.scrollTop;
      ensureSidebarFixedHeader(container);
      pinIcuBadges(container);
      for (var i = 0; i < slots.length; i += 1) {
        var box = container.querySelector(slots[i].box);
        if (!box) {
          continue;
        }
        box.style.visibility = "";
        var item = rows[i] ? copyPatient(rows[i]) : null;
        box.setAttribute("data-temp-patient-slot", String(i));
        box.setAttribute("data-temp-patient-id", item ? item.id : "");
        markSlotTargets(slots[i], item, i);
        applySidebarRowMetrics(slots[i], container);
        box.classList.add("icu-sidebar-card");
        // 空字段统一显示“待录入”，新建的空记录也能把每一行完整显示出来
        setTextIn(box, slots[i].name, item ? (patientValue(item, "petListName") || "宠物名：待录入") : "");
        setTextIn(container, slots[i].desc, item ? (patientValue(item, "speciesOwner") || "种类：待录入     主人：待录入") : "");
        setTextIn(container, slots[i].no, item ? (patientValue(item, "caseNo") || "编号：待录入") : "");
        setTextIn(container, slots[i].date, item ? (item.visitDate || "待录入") : "");
        applySlotVisualState(slots[i], item, current);
        setSlotVisible(slots[i], !!item);
      }
      var emptyHint = document.querySelector(".icu-patient-empty-hint");
      if (!emptyHint && container) {
        emptyHint = document.createElement("div");
        emptyHint.className = "icu-patient-empty-hint";
        container.appendChild(emptyHint);
      }
      if (emptyHint) {
        emptyHint.textContent = "";
      }
      renderExtraCards(container, slots.length, rows, current);
      ensureRecordLayers(slots, rows, current);
      if (container.scrollTop !== anchorScrollTop) {
        container.scrollTop = anchorScrollTop;
      }
      if (window.requestAnimationFrame) {
        window.requestAnimationFrame(function () {
          ensureRecordLayers(slots, rows, selectedId());
        });
      }
    }

    function installClicks() {
      if (clicksInstalled) {
        return;
      }
      clicksInstalled = true;
      function handleListSelection(event) {
        var node = event.target;
        var container = sidebarContainer();
        while (node && node !== document.body) {
          if (node.getAttribute && node.getAttribute("data-temp-patient-id") !== null) {
            if (!container || !containsNode(container, node)) {
              return;
            }
            var id = node.getAttribute("data-temp-patient-id") || "";
            if (id) {
              var now = Date.now();
              event.preventDefault();
              event.stopPropagation();
              if (event.stopImmediatePropagation) {
                event.stopImmediatePropagation();
              }
              if (id === lastSelectionId && now - lastSelectionAt < 360) {
                return;
              }
              if (isPetSwitchBlocked(id)) {
                return;
              }
              lastSelectionId = id;
              lastSelectionAt = now;
              setSelectedId(id);
              localSelectionPendingUntil = Date.now() + 1800;
              syncNativePatient(currentPatient(), true);
              render();
              window.dispatchEvent(new CustomEvent("icu-patient-temp-change"));
              window.setTimeout(function () {
                schedule(0);
              }, 180);
            }
            return;
          }
          node = node.parentNode;
        }
      }
      document.addEventListener("touchend", handleListSelection, { capture: true, passive: false });
      document.addEventListener("click", handleListSelection, true);
    }

    // 新建/编辑样本的三项必填：宠物名、病历编号、联系电话
    var REQUIRED_PATIENT_FIELDS = {
      petName: "宠物名称",
      recordNo: "病历编号",
      ownerPhone: "联系电话"
    };

    function missingRequiredPatientFields(values) {
      var missing = [];
      for (var key in REQUIRED_PATIENT_FIELDS) {
        if (Object.prototype.hasOwnProperty.call(REQUIRED_PATIENT_FIELDS, key)) {
          var text = clean(values && values[key]);
          if (!text || text === "--" || text === "待录入" || text === "未填写") {
            missing.push(REQUIRED_PATIENT_FIELDS[key]);
          }
        }
      }
      return missing;
    }

    function field(label, key, value, area) {
      var wrap = document.createElement("label");
      wrap.className = "icu-patient-field";
      if (area) {
        wrap.className += " icu-patient-field-wide";
      }
      if (Object.prototype.hasOwnProperty.call(REQUIRED_PATIENT_FIELDS, key)) {
        wrap.setAttribute("data-required", "1");
      }
      var title = document.createElement("span");
      title.textContent = label;
      wrap.appendChild(title);
      var input = area ? document.createElement("textarea") : document.createElement("input");
      input.value = value || "";
      input.setAttribute("data-field", key);
      wrap.appendChild(input);
      return wrap;
    }

    function fitLayer() {
      if (window.IcuFitLayer && typeof window.IcuFitLayer.ensure === "function") {
        var node = window.IcuFitLayer.ensure();
        if (node) {
          return node;
        }
      }
      return document.body;
    }

    function openEditor() {
      closeModal();
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      document.documentElement.classList.add("icu-patient-modal-open");
      var p = currentPatient();
      modal = document.createElement("div");
      modal.className = "icu-patient-modal";
      modal.innerHTML = '<div class="icu-patient-dialog"><div class="icu-patient-title">动物样本临时编辑</div><div class="icu-patient-error" data-role="error"></div><div class="icu-patient-form"></div><div class="icu-patient-actions"><button data-act="cancel">取消</button><button data-act="save">保存</button></div></div>';
      var form = modal.querySelector(".icu-patient-form");
      form.appendChild(field("宠物名称", "petName", p.petName));
      form.appendChild(field("动物种类", "species", p.species));
      form.appendChild(field("年龄", "age", p.age));
      form.appendChild(field("病历编号", "recordNo", p.recordNo || p.caseNo));
      form.appendChild(field("宠物主人", "ownerName", p.ownerName));
      form.appendChild(field("联系电话", "ownerPhone", p.ownerPhone));
      form.appendChild(field("主治医生", "doctor", p.doctor));
      form.appendChild(field("就诊时间", "visitDate", p.visitDate));
      form.appendChild(field("医嘱", "note", p.note, true));
      modal.addEventListener("click", function (event) {
        var act = event.target && event.target.getAttribute ? event.target.getAttribute("data-act") : "";
        if (act === "cancel") {
          closeModal();
        } else if (act === "save") {
          saveFromModal();
        }
      });
      fitLayer().appendChild(modal);
    }

    function closeModal() {
      if (modal && modal.parentNode) {
        modal.parentNode.removeChild(modal);
      }
      modal = null;
      document.documentElement.classList.remove("icu-patient-modal-open");
      schedule(60);
    }

    function saveFromModal() {
      if (!modal) {
        return;
      }
      var p = currentPatient();
      if (!p.id) {
        p.id = "tmp_" + Date.now();
      }
      var inputs = modal.querySelectorAll("[data-field]");
      var values = {};
      for (var i = 0; i < inputs.length; i += 1) {
        values[inputs[i].getAttribute("data-field")] = clean(inputs[i].value);
      }
      var missing = missingRequiredPatientFields(values);
      var errorNode = modal.querySelector('[data-role="error"]');
      if (missing.length) {
        if (errorNode) {
          errorNode.textContent = "请填写必填项：" + missing.join("、");
        }
        if (window.IcuToast && typeof window.IcuToast.show === "function") {
          window.IcuToast.show("宠物名称、病历编号、联系电话为必填项");
        }
        return;
      }
      if (errorNode) {
        errorNode.textContent = "";
      }
      for (var fieldKey in values) {
        if (Object.prototype.hasOwnProperty.call(values, fieldKey)) {
          p[fieldKey] = values[fieldKey];
        }
      }
      // caseNo 是原生按舱自增发的号，只有原生能改；病历编号(recordNo)不能覆盖它。
      var list = loadPatients();
      var replaced = false;
      for (var j = 0; j < list.length; j += 1) {
        if (list[j].id === p.id) {
          list[j] = p;
          replaced = true;
          break;
        }
      }
      if (!replaced) {
        list.unshift(p);
      }
      savePatients(list);
      setSelectedId(p.id);
      closeModal();
      syncNativePatient(p, true);
      render();
      window.dispatchEvent(new CustomEvent("icu-patient-temp-change"));
    }

    function handleAction(action) {
      if (action === "patient_edit") {
        openEditor();
        return true;
      }
      if (action === "patient_delete") {
        // 删除交给原生做：原生才是病例的真正来源，
        // 只在页面里删列表的话，下一次状态同步这条记录又会回来
        callAction("patient_delete", "删除");
        return true;
      }
      return false;
    }

    function render() {
      ensureSearchInput();
      installClicks();
      var current = currentPatient();
      writePatientLive(current);
      writePatientFallback(current);
      writeAdvicePanel(current);
      syncPatientValueBoxes();
      renderList();
      syncNativePatient(current, false);
    }

    window.IcuPatientTemp = {
      currentPatient: currentPatient,
      handleAction: handleAction,
      render: render,
      syncNative: syncNativePatient,
      importNativeRecords: importNativeRecords,
      isCurrentSelected: isCurrentSelected,
      selectCurrentTreatment: selectCurrentTreatment,
      setZone: setZone,
      zone: currentStoreZone,
      storageGet: storageGet,
      storageSet: storageSet,
      pinIcuBadges: pinIcuBadges,
      renderExtraCards: renderExtraCards,
      hideSidebarRows: hideSidebarRows,
      requiredFields: REQUIRED_PATIENT_FIELDS,
      missingRequired: missingRequiredPatientFields
    };
  }

  function writeTextNode(node, value, prefix, suffix) {
    if (!node) {
      return;
    }
    var cleanValue = clean(value);
    node.textContent = cleanValue ? (prefix || "") + cleanValue + (suffix || "") : empty;
  }

  function setText(selector, value, prefix, suffix) {
    var node = document.querySelector(selector);
    writeTextNode(node, value, prefix, suffix);
  }

  function setAllText(selector, value, prefix, suffix) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      writeTextNode(nodes[i], value, prefix, suffix);
    }
  }

  function setPreferredText(preferredSelector, fallbackSelector, value, prefix, suffix) {
    var nodes = document.querySelectorAll(preferredSelector);
    if (nodes.length) {
      for (var i = 0; i < nodes.length; i += 1) {
        writeTextNode(nodes[i], value, prefix, suffix);
      }
      return;
    }
    setAllText(fallbackSelector, value, prefix, suffix);
  }

  function showIfAnyText(selector) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.visibility = "";
    }
  }

  function hide(selector) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.visibility = "hidden";
    }
  }

  function show(selector) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.visibility = "";
    }
  }

  function pageName() {
    var path = window.location.pathname || "";
    var match = path.match(/src\/views\/([^/]+)\//);
    return match ? match[1] : "";
  }

  function markPageContext() {
    var page = document.querySelector(".page");
    if (!page) {
      return;
    }
    page.classList.toggle("icu-page-monitor", pageName() === "lanhu_3shishijianhu");
    page.classList.toggle("icu-page-patient-info", isPatientInfoPage());
  }

  function patient(state) {
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.currentPatient === "function") {
      return window.IcuPatientTemp.currentPatient();
    }
    return state.patient || {};
  }

  function org(state) {
    return state.organization || {};
  }

  function ble(state) {
    return state.ble || {};
  }

  function monitor(state) {
    return ble(state).monitor || {};
  }

  function host(state) {
    return ble(state).host || {};
  }

  function monitorValue(state, key) {
    return display(monitor(state)[key]);
  }

  var deadTextNormalized = false;
  function normalizeDeadText() {
    // 只清首次渲染前的静态设计稿；之后同名真实患者/主人绝不能被周期渲染删掉。
    if (deadTextNormalized) {
      return;
    }
    deadTextNormalized = true;
    var spans = document.querySelectorAll("span,div");
    for (var i = 0; i < spans.length; i += 1) {
      var node = spans[i];
      if (node.children && node.children.length) {
        continue;
      }
      var text = (node.textContent || "").replace(/\s+/g, " ").trim();
      if (/^X+$/.test(text) || isDemoValue(text)) {
        node.textContent = empty;
      } else if (/^(宠物名：|动物名称：)(饭团|咪咪)/.test(text)) {
        node.textContent = text.replace(/(饭团|咪咪).*/, empty);
      } else if (/2026[-/](05|06)/.test(text) || /0773-0000/.test(text) || /012\s*3456\s*7891/.test(text)) {
        node.textContent = empty;
      } else if (text.indexOf("瑞鹏宠物医院") >= 0 || text.indexOf("桂林优利特宠物医院") >= 0 || text.indexOf("广东省深圳市龙岗区") >= 0) {
        node.textContent = empty;
      }
    }
  }

  function renderCommon(state) {
    var organization = org(state);
    var p = patient(state);
    setPreferredText("[data-org-live='name']", ".text_6", organization.name);
    setPreferredText("[data-org-live='address']", ".text_7", organization.address);
    setPreferredText("[data-account-live='name']", ".text_4", state.accountName || "");
    var logoText = clean(organization.logoName) || "LOGO";
    // 顶栏 LOGO 槽统一交给 lanhu-account-brand.js 渲染（含上传的图片）。这里只写显式
    // 打了 data-org-live='logo' 的文字节点，不再用 .text-wrapper_1 > .text_5 宽泛兜底：
    // 那个兜底会把设计稿的 "LOGO" 占位文字改成文件名，公共模块随后按文字识别槽时
    // 就再也认不出来，上传的 LOGO 图片因此永远不显示；设置页左侧菜单也是同款结构，
    // 宽泛兜底还会把「医院信息」等菜单文字改掉。
    setAllText("[data-org-live='logo']", logoText);
    showIfAnyText("[data-org-live],[data-account-live]");

    if (!document.querySelector("[data-patient-live]")) {
      setText(".text_10", patientValue(p, "petListName"));
      setText(".text_11", patientValue(p, "speciesOwner"));
      setText(".text_12", p.caseNo || p.recordNo, "编号：");
      setText(".text_13", p.visitDate);
      setText(".text-group_1", displayPatientField(p.petName), "治疗记录：");
      setText(".text_14", patientValue(p, "speciesOwner"));
      setText(".text_15", p.recordNo || p.caseNo, "编号：");
      setText(".text_16", p.visitDate);
      setText(".text_35", displayPatientField(p.petName));
      setText(".text_36", displayPatientField(p.species));
      setText(".text_39", displayPatientField(p.age));
      setText(".text_40", p.recordNo || p.caseNo);
      setText(".text_43", displayPatientField(p.ownerName));
      setText(".text_44", displayPatientField(p.ownerPhone));
      setText(".text_47", displayPatientField(p.doctor));
      setText(".text_48", p.visitDate);
      setText(".paragraph_1", displayPatientField(p.note));
    }

    var page = pageName();
    if (page !== "lanhu_1icuzhuangtaikaobei" && page !== "lanhu_2zhujikongzhi") {
      var staleRows = ".group_30,.group_31,.group_32,.group_33,.group_34,.box_9,.box_10,.box_11,.box_12,.box_13,.box_41,.box_43,.box_44,.box_45,.text_14,.text_15,.text_16,.text_17,.text_18,.text_19,.text_20,.text_21,.text_22,.text_23,.text_24,.text_25,.text_26,.text_27,.text_28,.text_29,.text_30,.text_31,.text_32";
      if (page === "lanhu_4jiankong") {
        staleRows += ",.box_37,.box_38,.box_39,.box_40";
      }
      // 只隐藏侧栏里的静态样本行。蓝湖 class 会在外层页面复用（例如回放页
      // 最外层也是 .group_30），全局 hide 会把医院/LOGO/搜索连同整页一起隐藏。
      if (window.IcuPatientTemp && typeof window.IcuPatientTemp.hideSidebarRows === "function") {
        window.IcuPatientTemp.hideSidebarRows(staleRows);
      }
    }
  }

  function hideReportTemplateNavigation() {
    var nodes = document.querySelectorAll("span,div");
    for (var i = 0; i < nodes.length; i += 1) {
      var node = nodes[i];
      if (node.children && node.children.length) {
        continue;
      }
      var text = (node.textContent || "").replace(/\s+/g, "").trim();
      if (text === "报告模版-默认" || text === "报告模板-默认") {
        node.style.display = "none";
        node.style.visibility = "hidden";
      }
    }
  }

  function setWaveVisible(selector, visible) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.visibility = visible ? "" : "hidden";
    }
  }

  function setDisplay(selector, visible) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].style.display = visible ? "" : "none";
    }
  }

  function setMonitorSingleWaveMode(enabled) {
    var page = document.querySelector(".section_5");
    if (page) {
      page.classList.toggle("icu-monitor-single-wave", !!enabled);
    }
  }

  function clearWave(selector) {
    var target = document.querySelector(selector);
    if (!target) {
      return;
    }
    var old = target.querySelector(".icu-wave");
    if (old) {
      old.remove();
    }
    target.classList.remove("icu-wave-empty");
    target.removeAttribute("data-empty-label");
  }

  function monitorWaveDefs() {
    return [
      { key: "heartRate", label: "ECG", singleLabel: "\u5fc3\u7387", name: "\u5fc3\u7387", row: ".block_9", wave: ".group_37", title: ".text_60", card: ".box_7", color: "#4ee070", type: 0 },
      { key: "bloodPressure", label: "NIBP", singleLabel: "\u8840\u538b", name: "\u8840\u538b", row: ".block_9", wave: ".group_37", title: ".text_60", card: ".box_8", color: "#ffd166", type: 0 },
      { key: "spo2", label: "PLETH", singleLabel: "\u8840\u6c27", name: "\u8840\u6c27", row: ".block_10", wave: ".group_39", title: ".text_68", card: ".block_11", color: "#ee635d", type: 1 },
      { key: "pulse", label: "PR", singleLabel: "\u8109\u7387", name: "\u8109\u7387", row: ".block_10", wave: ".group_39", title: ".text_68", card: ".block_12", color: "#ff8f5a", type: 1 },
      { key: "bodyTemp", label: "TEMP", singleLabel: "\u4f53\u6e29", name: "\u4f53\u6e29", row: ".block_13", wave: ".box_30", title: ".text_75", card: ".box_13", color: "#f7c948", type: 2 },
      { key: "resp", label: "RESP", singleLabel: "\u547c\u5438\u7387", name: "\u547c\u5438\u7387", row: ".block_13", wave: ".box_30", title: ".text_75", card: ".box_14", color: "#5ea9f3", type: 2 }
    ];
  }

  function monitorWaveRows() {
    return [".block_9", ".block_10", ".block_13"];
  }

  // 每个参数的显示来源：有连续波形且波形有真实信号时画实时波形，
  // 否则回退到监护仪真实测量值的趋势，保证任何参数都有会变化的曲线。
  function monitorWaveSpecOf(key, color) {
    if (key === "heartRate") {
      return { key: "heartRate", channel: "ECG", wave: "ecg", type: 0, trend: [
        { name: "心率", unit: "bpm", color: color, digits: 0, values: waveTrend.heartRate || [] }
      ] };
    }
    if (key === "bloodPressure") {
      return { key: "bloodPressure", channel: "", wave: "", type: -1, trend: [
        { name: "收缩压", unit: "mmHg", color: color, digits: 0, values: waveTrend.nibpSys || [] },
        { name: "舒张压", unit: "mmHg", color: "#8fd3ff", digits: 0, values: waveTrend.nibpDia || [] }
      ] };
    }
    if (key === "spo2") {
      return { key: "spo2", channel: "PLETH", wave: "pleth", type: 1, trend: [
        { name: "血氧", unit: "%", color: color, digits: 0, values: waveTrend.spo2 || [] }
      ] };
    }
    if (key === "pulse") {
      return { key: "pulse", channel: "PLETH", wave: "pleth", type: 1, trend: [
        { name: "脉率", unit: "bpm", color: color, digits: 0, values: waveTrend.pulseRate || [] }
      ] };
    }
    if (key === "bodyTemp") {
      return { key: "bodyTemp", channel: "", wave: "", type: -1, trend: [
        { name: "体温", unit: "℃", color: color, digits: 1, values: trendTempValues() }
      ] };
    }
    if (key === "resp") {
      return { key: "resp", channel: "RESP", wave: "resp", type: 2, trend: [
        { name: "呼吸率", unit: "brpm", color: color, digits: 0, values: waveTrend.respRate || [] }
      ] };
    }
    return { key: key, channel: "", wave: "", type: -1, trend: [] };
  }

  function trendTempValues() {
    var raw = waveTrend.temp && waveTrend.temp.length ? waveTrend.temp : (waveTrend.tempEar || []);
    var values = [];
    for (var i = 0; i < raw.length; i += 1) {
      values.push(raw[i] / 10);
    }
    return values;
  }

  function monitorWaveTargets() {
    return [".group_37", ".group_39", ".box_30"];
  }

  function setMonitorWaveTitle(selector, text, color) {
    var node = document.querySelector(selector);
    if (!node) {
      return;
    }
    node.textContent = text;
    node.style.color = color || "";
  }

  function resetMonitorWaveTitles() {
    setMonitorWaveTitle(".text_60", "ECG", "");
    setMonitorWaveTitle(".text_68", "PLETH", "");
    setMonitorWaveTitle(".text_75", "RESP", "");
  }

  function showOnlyMonitorWaveRow(rowSelector) {
    var rows = monitorWaveRows();
    for (var i = 0; i < rows.length; i += 1) {
      setDisplay(rows[i], rows[i] === rowSelector);
    }
  }

  function showAllMonitorWaveRows() {
    var rows = monitorWaveRows();
    for (var i = 0; i < rows.length; i += 1) {
      setDisplay(rows[i], true);
    }
  }

  function clearOtherMonitorWaves(activeSelector) {
    var targets = monitorWaveTargets();
    for (var i = 0; i < targets.length; i += 1) {
      if (targets[i] !== activeSelector) {
        clearWave(targets[i]);
      }
    }
  }

  function updateMonitorMetricSelection() {
    var defs = monitorWaveDefs();
    for (var i = 0; i < defs.length; i += 1) {
      var nodes = document.querySelectorAll(defs[i].card);
      for (var j = 0; j < nodes.length; j += 1) {
        nodes[j].classList.toggle("icu-monitor-card-active", selectedMonitorWave === defs[i].key);
      }
    }
  }

  function findMonitorWaveDef(key) {
    var defs = monitorWaveDefs();
    for (var i = 0; i < defs.length; i += 1) {
      if (defs[i].key === key) {
        return defs[i];
      }
    }
    return null;
  }

  function setSelectedMonitorWave(key) {
    key = key || "all";
    selectedMonitorWave = selectedMonitorWave === key ? "all" : key;
    render();
  }

  function ensureMonitorFilter() {
    var page = document.querySelector(".section_5");
    if (!page) {
      return;
    }
    var bar = page.querySelector(".icu-monitor-filter");
    if (!bar) {
      bar = document.createElement("div");
      bar.className = "icu-monitor-filter";
      page.appendChild(bar);
    }
    var defs = [{ key: "all", name: "\u5168\u90e8" }].concat(monitorWaveDefs());
    if (bar.getAttribute("data-ready") !== "1") {
      var html = [];
      for (var i = 0; i < defs.length; i += 1) {
        html.push('<button type="button" data-wave="' + defs[i].key + '">' + defs[i].name + '</button>');
      }
      bar.innerHTML = html.join("");
      bar.setAttribute("data-ready", "1");
      bar.addEventListener("click", function (event) {
        var target = event.target;
        if (!target || target.tagName !== "BUTTON") {
          return;
        }
        event.preventDefault();
        event.stopPropagation();
        setSelectedMonitorWave(target.getAttribute("data-wave") || "all");
      }, true);
    }
    var buttons = bar.querySelectorAll("button");
    for (var j = 0; j < buttons.length; j += 1) {
      var active = buttons[j].getAttribute("data-wave") === selectedMonitorWave;
      buttons[j].classList.toggle("active", active);
    }
  }

  function ensureMonitorMetricClicks() {
    var defs = monitorWaveDefs();
    for (var i = 0; i < defs.length; i += 1) {
      (function (def) {
        var nodes = document.querySelectorAll(def.card);
        for (var j = 0; j < nodes.length; j += 1) {
          if (nodes[j].getAttribute("data-icu-wave-click") === "1") {
            continue;
          }
          nodes[j].setAttribute("data-icu-wave-click", "1");
          nodes[j].style.cursor = "pointer";
          nodes[j].addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();
            setSelectedMonitorWave(def.key);
          }, true);
        }
      })(defs[i]);
    }
  }

  function renderMonitorWaves() {
    updateMonitorMetricSelection();
    if (selectedMonitorWave === "all") {
      setMonitorSingleWaveMode(false);
      showAllMonitorWaveRows();
      resetMonitorWaveTitles();
      setWaveVisible(".group_37", true);
      setWaveVisible(".group_39", true);
      setWaveVisible(".box_30", true);
      wavePlan = [
        { selector: ".group_37", color: "#4ee070", key: "heartRate" },
        { selector: ".group_39", color: "#ee635d", key: "spo2" },
        { selector: ".box_30", color: "#5ea9f3", key: "resp" }
      ];
      installMonitorWaves();
      return;
    }
    var def = findMonitorWaveDef(selectedMonitorWave);
    if (!def) {
      selectedMonitorWave = "all";
      renderMonitorWaves();
      return;
    }
    setMonitorSingleWaveMode(true);
    showOnlyMonitorWaveRow(def.row);
    resetMonitorWaveTitles();
    clearOtherMonitorWaves(def.wave);
    setWaveVisible(def.wave, true);
    setMonitorWaveTitle(def.title, def.singleLabel || def.name || def.label, def.color);
    wavePlan = [{ selector: def.wave, color: def.color, key: def.key }];
    installMonitorWaves();
  }

  // 监护蓝牙的显示名：优先用蓝牙名称。设备没有广播名称时给一个可读的名字，
  // 物理地址放在“蓝牙与网络连接”面板里备查，不占底栏的位置。
  function monitorDeviceLabel(state) {
    var mon = monitor(state);
    var name = clean(mon.deviceName);
    if (name) {
      return name;
    }
    return mon.connected ? "未命名监护仪" : "监护仪";
  }

  function setMonitorFooterHighlight(selector, active) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].classList.toggle("icu-monitor-status-on", active);
      nodes[i].classList.toggle("icu-monitor-status-off", !active);
      nodes[i].setAttribute("data-monitor-highlight", active ? "on" : "off");
    }
  }

  // 六个监护数值（心率/血压/血氧/脉率/体温/呼吸率）的独立写入通道。
  // 原生事件全量渲染与 120ms 轻量轮询共用：数值不变时不写 DOM、不重绘波形。
  var lastVitalValues = {};
  var lastValidVitalValues = {};
  var lastVitalDeviceId = "";

  /**
   * 监护数值不做断连残留：断连时原生已清空标量，这里立即显示 "--"。
   * 连接中收到的无效测量同样保持空态，不冒充实时数据。
   */
  function monitorVitalText(key, raw, connected) {
    var value = clean(raw);
    if (!connected) {
      lastValidVitalValues[key] = "";
      return display(raw);
    }
    if (value && value !== "--/--") {
      lastValidVitalValues[key] = value;
      return value;
    }
    return display(raw);
  }

  function writeMonitorValues(m) {
    if (!m) {
      return;
    }
    var deviceId = clean(m.deviceId);
    if (deviceId && deviceId !== lastVitalDeviceId) {
      // 旧监护仪的读数不能带到另一台设备；同设备断连/重连仍保留。
      lastVitalDeviceId = deviceId;
      lastValidVitalValues = {};
    }
    var connected = m.connected === true;
    var hr = monitorVitalText("heartRate", m.heartRate, connected);
    var bp = monitorVitalText("bloodPressure", m.bloodPressure, connected);
    var sp = monitorVitalText("spo2", m.spo2, connected);
    var pl = monitorVitalText("pulse", m.pulse, connected);
    var bt = monitorVitalText("bodyTemp", m.bodyTemp, connected);
    var rp = monitorVitalText("resp", m.resp, connected);
    if (hr !== lastVitalValues.heartRate) {
      setText(".text_62", hr);
      lastVitalValues.heartRate = hr;
    }
    if (bp !== lastVitalValues.bloodPressure) {
      setText(".text_65", bp, "平均压：");
      setText(".text_66", bp);
      lastVitalValues.bloodPressure = bp;
    }
    if (sp !== lastVitalValues.spo2) {
      setText(".text_70", sp);
      lastVitalValues.spo2 = sp;
    }
    if (pl !== lastVitalValues.pulse) {
      setText(".text_73", pl);
      lastVitalValues.pulse = pl;
    }
    if (bt !== lastVitalValues.bodyTemp) {
      setText(".text_77", bt);
      lastVitalValues.bodyTemp = bt;
    }
    if (rp !== lastVitalValues.resp) {
      setText(".text_80", rp);
      lastVitalValues.resp = rp;
    }
  }

  function renderMonitor(state) {
    if (pageName() !== "lanhu_3shishijianhu") {
      return;
    }
    cleanupMonitorOverlays();
    ensureMonitorMetricClicks();
    show(".box_13,.box_14");
    writeMonitorValues(monitor(state));
    setText(".text_63", "bpm");
    var link = monitor(state);
    var linkState = clean(link.state);
    var protocolReady = link.protocolReady === true || link.ready === true;
    // 同一快照必须同时证明协议就绪且状态文字仍为“已连接”；重连中的旧 connected 不得高亮。
    var connected = link.connected === true && protocolReady && link.active !== false
      && linkState === "已连接";
    var pending = !connected && (link.active === true || link.scanning === true
      || /自动重连|连接中|发现服务|启用Notify|断开中/.test(linkState));
    var linkLabel = "蓝牙";
    if (connected) {
      linkLabel = "已连接";
    } else if (pending) {
      linkLabel = linkState && linkState !== "已连接" ? linkState : (link.scanning ? "扫描中" : "连接中");
    }
    var alarmOn = !state.monitorSettings || state.monitorSettings.alarmSound !== false;
    setText(".text-group_2", linkLabel);
    setText(".text-group_4", alarmOn ? "报警音" : "报警音(关)");
    setText(".text-group_3", monitorDeviceLabel(state));
    setMonitorFooterHighlight(".group_16", connected);
    setMonitorFooterHighlight(".group_17", connected);
    setMonitorFooterHighlight(".group_18", alarmOn);
    renderMonitorWaves();
    renderMonitorAlarms(state);
  }

  // 超限参数与页面上的数值卡片一一对应：心率/血压/脉率/血氧/体温/呼吸率。
  var MONITOR_ALARM_CARDS = {
    0: ".box_7",
    1: ".box_8",
    2: ".box_8",
    3: ".block_12",
    4: ".block_11",
    5: ".box_13",
    6: ".box_14"
  };

  function monitorAlarms(state) {
    // 原生断连事件与整页状态可能先后到达；旧 JSON 不能在断开后留下报警条。
    if (monitor(state).connected !== true) {
      return [];
    }
    var settings = state.monitorSettings || {};
    var alarms = settings.alarms || [];
    return alarms.length ? alarms : [];
  }

  function alarmHost() {
    if (window.IcuFitLayer && typeof window.IcuFitLayer.ensure === "function") {
      var node = window.IcuFitLayer.ensure();
      if (node) {
        return node;
      }
    }
    return document.body;
  }

  /**
   * 报警条贴在顶栏下方，不能盖住标题、设备时间和左右舱按钮。
   * 顶栏高度按页面实际布局量出来，蓝湖改版后也不会错位。
   */
  function monitorAlarmTopOffset() {
    var header = document.querySelector(".box_1");
    if (!header && document.querySelector(".text_1")) {
      header = document.querySelector(".text_1").parentNode;
    }
    if (header && typeof header.getBoundingClientRect === "function") {
      var rect = header.getBoundingClientRect();
      if (rect.height > 0) {
        return Math.round(rect.height);
      }
    }
    return 23;
  }

  /**
   * 超限报警：数值越界时页面顶部挂一条红色报警条，
   * 同时把对应的数值卡片标红，操作者一眼就能看出是哪一项越界。
   */
  function renderMonitorAlarms(state) {
    var alarms = monitorAlarms(state);
    var host = alarmHost();
    var bar = host.querySelector ? host.querySelector(".icu-monitor-alarm") : null;
    if (!alarms.length) {
      if (bar) {
        bar.remove();
      }
      clearMonitorAlarmMarks();
      return;
    }
    if (!bar) {
      bar = document.createElement("div");
      bar.className = "icu-monitor-alarm";
      host.appendChild(bar);
    }
    bar.style.setProperty("top", monitorAlarmTopOffset() + "px", "important");
    var items = [];
    for (var i = 0; i < alarms.length; i += 1) {
      var alarm = alarms[i] || {};
      items.push('<span class="icu-monitor-alarm-item">' + display(alarm.name) + " " + display(alarm.value)
        + display(alarm.unit) + (alarm.high ? " 超上限" : " 低于下限") + "</span>");
    }
    bar.innerHTML = "<strong>监护报警</strong>" + items.join("");
    markMonitorAlarmCards(alarms);
  }

  function markMonitorAlarmCards(alarms) {
    clearMonitorAlarmMarks();
    for (var i = 0; i < alarms.length; i += 1) {
      var selector = MONITOR_ALARM_CARDS[alarms[i].index];
      if (!selector) {
        continue;
      }
      var nodes = document.querySelectorAll(selector);
      for (var j = 0; j < nodes.length; j += 1) {
        nodes[j].classList.add("icu-monitor-card-alarm");
      }
    }
  }

  function clearMonitorAlarmMarks() {
    var nodes = document.querySelectorAll(".icu-monitor-card-alarm");
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].classList.remove("icu-monitor-card-alarm");
    }
  }

  function cleanupMonitorOverlays() {
    var nodes = document.querySelectorAll(".icu-monitor-filter,.icu-monitor-tools");
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].remove();
    }
  }

  function ensureMonitorTools(state) {
    var page = document.querySelector(".section_5");
    if (!page) {
      return;
    }
    var panel = page.querySelector(".icu-monitor-tools");
    if (!panel) {
      panel = document.createElement("div");
      panel.className = "icu-monitor-tools";
      page.appendChild(panel);
    }
    var settings = state.monitorSettings || {};
    var mode = settings.mode || "live";
    var volumeOn = settings.pulseBeep !== false || settings.alarmSound !== false;
    panel.innerHTML = [
      '<div class="icu-monitor-toolbar">',
      '<button type="button" data-native-action="monitor_history" class="' + (mode === "history" ? "active" : "") + '">历史数据</button>',
      '<button type="button" data-native-action="monitor_limits" class="' + (mode === "settings" ? "active" : "") + '">上下限设置</button>',
      '<button type="button" data-native-action="monitor_volume" class="' + (volumeOn ? "active" : "") + '">音量' + (volumeOn ? "开" : "关") + '</button>',
      '<button type="button" data-native-action="monitor_ble">监护蓝牙</button>',
      '</div>',
      '<div class="icu-monitor-panel">',
      monitorSummaryHtml(state),
      monitorLimitsHtml(settings),
      '</div>'
    ].join("");
  }

  function monitorSummaryHtml(state) {
    var m = monitor(state);
    var items = [
      ["心率", m.heartRate, "bpm"],
      ["血压", m.bloodPressure, "mmHg"],
      ["血氧", m.spo2, "%"],
      ["脉率", m.pulse || m.pulseRate, "bpm"],
      ["体温", m.bodyTemp, "℃"],
      ["呼吸率", m.resp, "brpm"]
    ];
    var html = ['<div class="icu-monitor-summary">'];
    for (var i = 0; i < items.length; i += 1) {
      html.push('<div class="icu-monitor-summary-item"><strong>' + items[i][0] + '</strong><span>' + display(items[i][1]) + '</span><em>' + items[i][2] + '</em></div>');
    }
    html.push('</div>');
    html.push('<div class="icu-monitor-last">最近帧：' + display(m.summary || m.lastError || m.state) + '</div>');
    if (m.waveStats) {
      html.push('<div class="icu-monitor-last">波形采样：' + display(m.waveStats) + '</div>');
    }
    if (m.numericDiag) {
      html.push('<div class="icu-monitor-last">数值诊断：' + display(m.numericDiag) + '</div>');
    }
    return html.join("");
  }

  function monitorLimitsHtml(settings) {
    var limits = settings.limits || [];
    if (!limits.length) {
      return '<div class="icu-monitor-limits"><div>上下限：等待监护设置同步</div></div>';
    }
    var html = ['<div class="icu-monitor-limits">'];
    for (var i = 0; i < limits.length; i += 1) {
      var item = limits[i] || {};
      html.push('<div class="icu-monitor-limit-row"><span>' + display(item.name) + '</span><b>' + display(item.low) + '-' + display(item.high) + ' ' + display(item.unit) + '</b></div>');
    }
    html.push('</div>');
    return html.join("");
  }

  function monitorLimitRowsHtml(settings) {
    var limits = settings.limits || [];
    if (!limits.length) {
      return '<div class="icu-overlay-empty">监护上下限尚未同步</div>';
    }
    var html = [];
    for (var i = 0; i < limits.length; i += 1) {
      var item = limits[i] || {};
      html.push('<div class="icu-monitor-limit-row" data-icu-monitor-limit="' + i + '">');
      html.push('<span>' + display(item.name) + '</span>');
      html.push('<b>' + display(item.low) + ' - ' + display(item.high) + ' ' + display(item.unit) + '</b>');
      html.push('</div>');
    }
    return html.join("");
  }

  function monitorSwitchHtml(label, key, on) {
    return '<div class="icu-monitor-setting-item"><span>' + label + '</span>' +
      '<button type="button" class="icu-switch' + (on ? " on" : "") + '" data-icu-monitor-toggle="' + key + '"><i></i></button></div>';
  }

  function monitorSettingsHtml(state) {
    var settings = state.monitorSettings || {};
    var volumeOn = settings.pulseBeep !== false || settings.alarmSound !== false;
    return [
      '<div class="icu-monitor-toolbar">',
      '<button type="button" data-icu-monitor-panel="history">历史趋势</button>',
      '</div>',
      '<div class="icu-monitor-setting-row">',
      monitorSwitchHtml("监护音量", "volume", volumeOn),
      monitorSwitchHtml("报警音", "alarmSound", settings.alarmSound !== false),
      monitorSwitchHtml("脉搏音", "pulseBeep", settings.pulseBeep !== false),
      '</div>',
      '<div class="icu-monitor-section-title">监护上下限（点击修改）</div>',
      monitorLimitRowsHtml(settings)
    ].join("");
  }

  // 历史趋势按时间维度查看：时间窗口与参数都能切换。
  var monitorHistoryRange = 5;
  var monitorHistoryMetric = "heartRate";
  var monitorHistoryDirty = true;
  var MONITOR_HISTORY_RANGES = [
    { minutes: 5, name: "近5分钟" },
    { minutes: 30, name: "近30分钟" },
    { minutes: 0, name: "全程" }
  ];
  var MONITOR_HISTORY_METRICS = [
    { key: "heartRate", name: "心率", unit: "bpm", digits: 0, limits: [0], series: [{ param: 0, name: "心率", color: "#4ee070" }] },
    { key: "spo2", name: "血氧", unit: "%", digits: 0, limits: [4], series: [{ param: 4, name: "血氧", color: "#ee635d" }] },
    { key: "pulse", name: "脉率", unit: "bpm", digits: 0, limits: [3], series: [{ param: 3, name: "脉率", color: "#ff8f5a" }] },
    { key: "bloodPressure", name: "血压", unit: "mmHg", digits: 0, limits: [1, 2], series: [{ param: 1, name: "收缩压", color: "#ffd166" }, { param: 2, name: "舒张压", color: "#8fd3ff" }] },
    { key: "bodyTemp", name: "体温", unit: "℃", digits: 1, limits: [5], series: [{ param: 5, name: "体温", color: "#f7c948" }] },
    { key: "resp", name: "呼吸率", unit: "brpm", digits: 0, limits: [6], series: [{ param: 6, name: "呼吸率", color: "#5ea9f3" }] }
  ];

  // 趋势图的几何信息与当前悬停位置：绘图和"点击读数"共用同一套坐标换算。
  var monitorHistoryPlot = null;
  var monitorHistoryHoverTime = null;
  var monitorHistoryPointerType = "mouse";

  /** 当前时间范围的名称，空数据提示要说明"这段时间"到底是哪一段。 */
  function monitorHistoryRangeName() {
    for (var i = 0; i < MONITOR_HISTORY_RANGES.length; i += 1) {
      if (MONITOR_HISTORY_RANGES[i].minutes === monitorHistoryRange) {
        return MONITOR_HISTORY_RANGES[i].name;
      }
    }
    return MONITOR_HISTORY_RANGES[0].name;
  }

  /**
   * 没有测量数据时的提示：说清楚为什么没有数据、下一步该做什么，
   * 免得只剩一句"该时间段还没有数据"，看着像功能坏了。
   */
  function monitorHistoryEmptyHtml(def) {
    var connected = false;
    try {
      connected = !!(monitor(readFirstPhaseState()).connected);
    } catch (error) {
      connected = false;
    }
    var hint = connected
      ? "监护仪已连接，但这个时间段内没有测量记录，可以换个时间范围或参数再看看。"
      : "连接监护仪后，测得的数据会按时间自动记录在这里。";
    return '<div class="icu-monitor-history-empty">'
      + "<strong>" + monitorHistoryRangeName() + "还没有" + def.name + "测量数据</strong>"
      + "<span>" + hint + "</span>"
      + '<button type="button" data-icu-monitor-history-connect="1">连接监护仪</button>'
      + "</div>";
  }

  function monitorHistoryMetricDef() {
    for (var i = 0; i < MONITOR_HISTORY_METRICS.length; i += 1) {
      if (MONITOR_HISTORY_METRICS[i].key === monitorHistoryMetric) {
        return MONITOR_HISTORY_METRICS[i];
      }
    }
    return MONITOR_HISTORY_METRICS[0];
  }

  function monitorHistoryHtml(state) {
    var ranges = [];
    for (var i = 0; i < MONITOR_HISTORY_RANGES.length; i += 1) {
      var range = MONITOR_HISTORY_RANGES[i];
      ranges.push('<button type="button" class="' + (monitorHistoryRange === range.minutes ? "active" : "")
        + '" data-icu-monitor-range="' + range.minutes + '">' + range.name + "</button>");
    }
    var metrics = [];
    for (var j = 0; j < MONITOR_HISTORY_METRICS.length; j += 1) {
      var metric = MONITOR_HISTORY_METRICS[j];
      metrics.push('<button type="button" class="' + (monitorHistoryMetric === metric.key ? "active" : "")
        + '" data-icu-monitor-metric="' + metric.key + '">' + metric.name + "</button>");
    }
    return [
      '<div class="icu-monitor-history-controls">',
      '<div class="icu-monitor-history-row">', ranges.join(""), "</div>",
      '<div class="icu-monitor-history-row">', metrics.join(""), "</div>",
      "</div>",
      '<div data-icu-monitor-history-chart="1" class="icu-monitor-history-chart"></div>',
      '<div data-icu-monitor-history-readout="1" class="icu-monitor-history-readout"></div>',
      '<div data-icu-monitor-history-stats="1" class="icu-monitor-history-stats"></div>',
      '<div class="icu-monitor-toolbar">',
      '<button type="button" data-icu-monitor-panel="settings">监护设置</button>',
      "</div>"
    ].join("");
  }

  /** 读取原生记录的带时间戳测量值。 */
  function readMonitorHistory() {
    if (!window.IcuNative || typeof window.IcuNative.monitorHistory !== "function") {
      return { now: Date.now(), items: [] };
    }
    try {
      var data = JSON.parse(window.IcuNative.monitorHistory() || "{}") || {};
      return { now: data.now || Date.now(), items: data.items || [] };
    } catch (error) {
      return { now: Date.now(), items: [] };
    }
  }

  function monitorHistorySeries(def) {
    var history = readMonitorHistory();
    var from = monitorHistoryRange > 0 ? history.now - monitorHistoryRange * 60000 : 0;
    var result = [];
    for (var s = 0; s < def.series.length; s += 1) {
      var param = def.series[s].param;
      var points = [];
      for (var i = 0; i < history.items.length; i += 1) {
        var item = history.items[i] || {};
        var itemParam = Number(item.p);
        var itemTime = Number(item.t);
        var itemValue = Number(item.v);
        // 坏记录直接跳过，绝不把未知参数、缺时间或 NaN 当成 0 画进真实趋势。
        if (!isFinite(itemParam) || itemParam !== param
          || !isFinite(itemTime) || itemTime <= 0 || itemTime < from
          || !isFinite(itemValue)) {
          continue;
        }
        points.push({ t: itemTime, v: itemValue });
      }
      points.sort(function (a, b) { return a.t - b.t; });
      result.push(points);
    }
    return { series: result, now: history.now };
  }

  function monitorHistoryLimits(state, def) {
    var limits = (state.monitorSettings || {}).limits || [];
    var result = [];
    for (var i = 0; i < def.limits.length; i += 1) {
      var item = limits[def.limits[i]];
      if (!item || item.enabled === false) {
        continue;
      }
      var low = Number(item.low);
      var high = Number(item.high);
      if (isNaN(low) || isNaN(high)) {
        continue;
      }
      result.push({ name: item.name, low: low, high: high });
    }
    return result;
  }

  function monitorHistoryTimeText(time) {
    var date = new Date(time);
    function pad(value) {
      return value < 10 ? "0" + value : String(value);
    }
    return pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
  }

  function monitorHistoryChartHtml(def, data, limits) {
    var series = data.series || [];
    var total = 0;
    var min = Infinity;
    var max = -Infinity;
    var startTime = 0;
    var endTime = data.now || 0;
    for (var s = 0; s < series.length; s += 1) {
      var points = series[s];
      for (var i = 0; i < points.length; i += 1) {
        total += 1;
        var value = points[i].v;
        if (value < min) {
          min = value;
        }
        if (value > max) {
          max = value;
        }
        if (!startTime || points[i].t < startTime) {
          startTime = points[i].t;
        }
        if (points[i].t > endTime) {
          endTime = points[i].t;
        }
      }
    }
    for (var l = 0; l < limits.length; l += 1) {
      if (limits[l].low < min) {
        min = limits[l].low;
      }
      if (limits[l].high > max) {
        max = limits[l].high;
      }
    }
    monitorHistoryPlot = null;
    if (!total) {
      return monitorHistoryEmptyHtml(def);
    }
    if (max - min < 1) {
      var center = (max + min) / 2;
      min = center - 1;
      max = center + 1;
    }
    var padding = (max - min) * 0.12;
    min -= padding;
    max += padding;
    var width = 660;
    var height = 250;
    var left = 58;
    var right = 20;
    var top = 16;
    var bottom = 34;
    var plotWidth = width - left - right;
    var plotHeight = height - top - bottom;
    var span = Math.max(1, endTime - startTime);
    monitorHistoryPlot = {
      def: def,
      series: series,
      min: min,
      max: max,
      startTime: startTime,
      endTime: endTime,
      span: span,
      left: left,
      top: top,
      plotWidth: plotWidth,
      plotHeight: plotHeight,
      width: width,
      height: height
    };
    var html = ['<svg viewBox="0 0 ' + width + " " + height + '" width="100%" height="100%" preserveAspectRatio="xMidYMid meet">'];
    for (var g = 0; g <= 4; g += 1) {
      var ratio = g / 4;
      var gy = (top + plotHeight * ratio).toFixed(1);
      var gvalue = max - (max - min) * ratio;
      html.push('<line x1="' + left + '" y1="' + gy + '" x2="' + (left + plotWidth) + '" y2="' + gy
        + '" stroke="#d7e3f0" stroke-width="1" />');
      html.push('<text x="' + (left - 8) + '" y="' + (Number(gy) + 4).toFixed(1)
        + '" text-anchor="end" fill="#7a8da0" font-size="12">' + Number(gvalue).toFixed(def.digits) + "</text>");
    }
    for (var m = 0; m < limits.length; m += 1) {
      var limit = limits[m];
      html.push(monitorHistoryLimitLine(def, limit.high, "上限", min, max, left, top, plotWidth, plotHeight));
      html.push(monitorHistoryLimitLine(def, limit.low, "下限", min, max, left, top, plotWidth, plotHeight));
    }
    for (var k = 0; k < series.length; k += 1) {
      var line = series[k];
      if (!line.length) {
        continue;
      }
      var d = "";
      for (var p = 0; p < line.length; p += 1) {
        var x = monitorHistoryX(monitorHistoryPlot, line[p].t).toFixed(1);
        var y = monitorHistoryY(monitorHistoryPlot, line[p].v).toFixed(1);
        d += (p === 0 ? "M" : "L") + x + " " + y;
      }
      html.push('<path d="' + d + '" fill="none" stroke="' + def.series[k].color
        + '" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round" />');
      // 最新一点的标记：不悬停时也能看出曲线走到哪儿了
      var latest = line[line.length - 1];
      html.push('<circle cx="' + monitorHistoryX(monitorHistoryPlot, latest.t).toFixed(1)
        + '" cy="' + monitorHistoryY(monitorHistoryPlot, latest.v).toFixed(1)
        + '" r="4" fill="#ffffff" stroke="' + def.series[k].color + '" stroke-width="2" />');
    }
    html.push('<text x="' + left + '" y="' + (height - 10) + '" fill="#7a8da0" font-size="12">'
      + monitorHistoryTimeText(startTime) + "</text>");
    html.push('<text x="' + (left + plotWidth) + '" y="' + (height - 10)
      + '" text-anchor="end" fill="#7a8da0" font-size="12">' + monitorHistoryTimeText(endTime) + "</text>");
    // 悬停读数的图层：默认隐藏，指针落到曲线上时才显示
    html.push('<g class="icu-monitor-history-hover" visibility="hidden">');
    html.push('<line class="icu-monitor-history-crosshair" x1="0" y1="' + top + '" x2="0" y2="' + (top + plotHeight)
      + '" stroke="#8ba0b5" stroke-width="1" stroke-dasharray="4 3" />');
    html.push('<g class="icu-monitor-history-hover-dots"></g>');
    html.push('<g class="icu-monitor-history-hover-tip"></g>');
    html.push("</g>");
    html.push("</svg>");
    return html.join("");
  }

  /** 曲线坐标换算：时间 -> 横坐标。 */
  function monitorHistoryX(plot, time) {
    return plot.left + (time - plot.startTime) / plot.span * plot.plotWidth;
  }

  /** 曲线坐标换算：数值 -> 纵坐标。 */
  function monitorHistoryY(plot, value) {
    return plot.top + (plot.max - value) / (plot.max - plot.min) * plot.plotHeight;
  }

  /**
   * 取某个时刻各条曲线的读数。time 为空时取最新一条，
   * 悬停时取离该时刻最近的一条。
   */
  function monitorHistoryValuesAt(plot, time) {
    var result = [];
    if (!plot) {
      return result;
    }
    for (var s = 0; s < plot.series.length; s += 1) {
      var points = plot.series[s];
      if (!points.length) {
        continue;
      }
      var point = null;
      if (time === null || time === undefined) {
        point = points[points.length - 1];
      } else {
        var bestDiff = Infinity;
        for (var i = 0; i < points.length; i += 1) {
          var diff = Math.abs(points[i].t - time);
          if (diff < bestDiff) {
            bestDiff = diff;
            point = points[i];
          }
        }
      }
      if (!point) {
        continue;
      }
      result.push({
        name: plot.def.series[s].name,
        color: plot.def.series[s].color,
        value: point.v,
        text: point.v.toFixed(plot.def.digits) + " " + plot.def.unit,
        time: point.t,
        x: monitorHistoryX(plot, point.t),
        y: monitorHistoryY(plot, point.v)
      });
    }
    return result;
  }

  /** 指针横坐标 -> 最近的数据点时间。 */
  function monitorHistoryTimeAtX(plot, x) {
    var best = null;
    var bestDist = Infinity;
    for (var s = 0; s < plot.series.length; s += 1) {
      var points = plot.series[s];
      for (var i = 0; i < points.length; i += 1) {
        var dist = Math.abs(monitorHistoryX(plot, points[i].t) - x);
        if (dist < bestDist) {
          bestDist = dist;
          best = points[i].t;
        }
      }
    }
    return best;
  }

  /** 读数条：没有悬停时显示最新一条，悬停时显示选中时刻。 */
  function monitorHistoryReadoutHtml(values) {
    if (!values.length) {
      return '<span class="icu-monitor-history-readout-empty">暂无读数</span>';
    }
    var parts = [];
    for (var i = 0; i < values.length; i += 1) {
      parts.push('<span class="icu-monitor-history-readout-item">'
        + '<i style="background:' + values[i].color + '"></i>'
        + values[i].name + " " + values[i].text + "</span>");
    }
    return '<span class="icu-monitor-history-readout-time">' + monitorHistoryTimeText(values[0].time) + "</span>"
      + '<span class="icu-monitor-history-readout-values">' + parts.join("") + "</span>";
  }

  /** 估算文本宽度，用来给浮层定尺寸。 */
  function monitorHistoryTextWidth(text, fontSize) {
    var width = 0;
    for (var i = 0; i < text.length; i += 1) {
      width += text.charCodeAt(i) > 255 ? fontSize : fontSize * 0.56;
    }
    return width;
  }

  /** 悬停浮层：时间 + 各条曲线的数值。 */
  function monitorHistoryTooltipSvg(plot, values) {
    var fontSize = 13;
    var lineHeight = fontSize + 5;
    var lines = [monitorHistoryTimeText(values[0].time)];
    for (var i = 0; i < values.length; i += 1) {
      lines.push(values[i].name + " " + values[i].text);
    }
    var width = 0;
    for (var j = 0; j < lines.length; j += 1) {
      width = Math.max(width, monitorHistoryTextWidth(lines[j], fontSize));
    }
    width = Math.round(width) + 20;
    var height = lines.length * lineHeight + 12;
    var anchorX = values[0].x;
    var anchorY = values[0].y;
    var x = anchorX + 12;
    if (x + width > plot.width - 4) {
      x = anchorX - width - 12;
    }
    if (x < 4) {
      x = 4;
    }
    var y = anchorY - height / 2;
    if (y < 2) {
      y = 2;
    }
    if (y + height > plot.height - 2) {
      y = plot.height - height - 2;
    }
    var html = ['<rect x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" width="' + width + '" height="' + height
      + '" rx="6" fill="#ffffff" stroke="#c8d8e8" stroke-width="1" />'];
    for (var k = 0; k < lines.length; k += 1) {
      html.push('<text x="' + (x + 10).toFixed(1) + '" y="' + (y + 8 + lineHeight * k + fontSize * 0.8).toFixed(1)
        + '" fill="' + (k === 0 ? "#6c8095" : "#1f3b57") + '" font-size="' + fontSize + '">' + lines[k] + "</text>");
    }
    return html.join("");
  }

  /** 把当前悬停位置画到图上，并刷新读数条。 */
  function monitorHistoryApplyHover(body) {
    if (!body) {
      return;
    }
    var readoutNode = body.querySelector("[data-icu-monitor-history-readout]");
    var plot = monitorHistoryPlot;
    if (!plot) {
      if (readoutNode) {
        readoutNode.innerHTML = '<span class="icu-monitor-history-readout-empty">连接监护仪后，这里会显示每个时刻的实测数值</span>';
      }
      return;
    }
    var values = monitorHistoryValuesAt(plot, monitorHistoryHoverTime);
    if (readoutNode) {
      readoutNode.innerHTML = monitorHistoryReadoutHtml(values);
    }
    var chartNode = body.querySelector("[data-icu-monitor-history-chart]");
    var svg = chartNode ? chartNode.querySelector("svg") : null;
    var group = svg ? svg.querySelector(".icu-monitor-history-hover") : null;
    if (!group) {
      return;
    }
    if (!values.length) {
      group.setAttribute("visibility", "hidden");
      return;
    }
    group.setAttribute("visibility", "visible");
    var crosshair = group.querySelector(".icu-monitor-history-crosshair");
    var dots = group.querySelector(".icu-monitor-history-hover-dots");
    var tip = group.querySelector(".icu-monitor-history-hover-tip");
    var anchorX = values[0].x;
    if (crosshair) {
      crosshair.setAttribute("x1", anchorX.toFixed(1));
      crosshair.setAttribute("x2", anchorX.toFixed(1));
    }
    if (dots) {
      var dotHtml = "";
      for (var i = 0; i < values.length; i += 1) {
        dotHtml += '<circle cx="' + values[i].x.toFixed(1) + '" cy="' + values[i].y.toFixed(1)
          + '" r="5" fill="#ffffff" stroke="' + values[i].color + '" stroke-width="2.5" />';
      }
      dots.innerHTML = dotHtml;
    }
    if (tip) {
      tip.innerHTML = monitorHistoryTooltipSvg(plot, values);
    }
  }

  /** 屏幕坐标 -> 曲线坐标系。 */
  function monitorHistoryLocalPoint(event, body) {
    var chartNode = body.querySelector("[data-icu-monitor-history-chart]");
    var svg = chartNode ? chartNode.querySelector("svg") : null;
    if (!svg || !monitorHistoryPlot || typeof svg.getScreenCTM !== "function") {
      return null;
    }
    var matrix = svg.getScreenCTM();
    if (!matrix) {
      return null;
    }
    var point = null;
    if (typeof DOMPoint === "function") {
      point = new DOMPoint(event.clientX, event.clientY);
    } else if (typeof svg.createSVGPoint === "function") {
      point = svg.createSVGPoint();
      point.x = event.clientX;
      point.y = event.clientY;
    }
    if (!point) {
      return null;
    }
    var local = point.matrixTransform(matrix.inverse());
    if (local.x < 0 || local.x > monitorHistoryPlot.width || local.y < 0 || local.y > monitorHistoryPlot.height) {
      return null;
    }
    return local;
  }

  /** 指针在曲线上移动：显示该时刻的读数。 */
  function monitorHistoryPointerMove(event) {
    if (monitorPanelMode !== "history") {
      return;
    }
    var body = window.IcuOverlay && typeof window.IcuOverlay.body === "function" ? window.IcuOverlay.body() : null;
    if (!body) {
      return;
    }
    monitorHistoryPointerType = event.pointerType || "mouse";
    var local = monitorHistoryLocalPoint(event, body);
    if (!local) {
      // 指针移出曲线范围：鼠标回到"最新值"，触摸则保留点选结果
      if (monitorHistoryPointerType === "mouse" && monitorHistoryHoverTime !== null) {
        monitorHistoryHoverTime = null;
        monitorHistoryApplyHover(body);
      }
      return;
    }
    monitorHistoryHoverTime = monitorHistoryTimeAtX(monitorHistoryPlot, local.x);
    monitorHistoryApplyHover(body);
  }

  /** 指针离开面板：清除悬停读数。 */
  function monitorHistoryPointerLeave() {
    if (monitorPanelMode !== "history" || monitorHistoryPointerType !== "mouse") {
      return;
    }
    var body = window.IcuOverlay && typeof window.IcuOverlay.body === "function" ? window.IcuOverlay.body() : null;
    if (!body) {
      return;
    }
    monitorHistoryHoverTime = null;
    monitorHistoryApplyHover(body);
  }

  function monitorHistoryLimitLine(def, value, label, min, max, left, top, plotWidth, plotHeight) {
    if (value < min || value > max) {
      return "";
    }
    var y = (top + (max - value) / (max - min) * plotHeight).toFixed(1);
    return '<line x1="' + left + '" y1="' + y + '" x2="' + (left + plotWidth) + '" y2="' + y
      + '" stroke="#f0a93c" stroke-width="1.5" stroke-dasharray="6 4" />'
      + '<text x="' + (left + 6) + '" y="' + (Number(y) - 4).toFixed(1)
      + '" fill="#c07b12" font-size="11">' + label + " " + Number(value).toFixed(def.digits) + "</text>";
  }

  function monitorHistoryStatsHtml(def, data) {
    var series = data.series || [];
    var rows = [];
    for (var s = 0; s < series.length; s += 1) {
      var points = series[s];
      if (!points.length) {
        continue;
      }
      var min = points[0].v;
      var max = points[0].v;
      var sum = 0;
      for (var i = 0; i < points.length; i += 1) {
        var value = points[i].v;
        if (value < min) {
          min = value;
        }
        if (value > max) {
          max = value;
        }
        sum += value;
      }
      var latest = points[points.length - 1];
      rows.push('<div class="icu-monitor-history-stat">'
        + "<span>" + def.series[s].name + "</span>"
        + "<b>" + latest.v.toFixed(def.digits) + " " + def.unit + "</b>"
        + "<em>最低 " + min.toFixed(def.digits) + " · 最高 " + max.toFixed(def.digits)
        + " · 平均 " + (sum / points.length).toFixed(def.digits)
        + " · " + points.length + " 次</em></div>");
    }
    if (!rows.length) {
      return "";
    }
    return rows.join("");
  }

  function renderMonitorHistoryChart(body, state) {
    var def = monitorHistoryMetricDef();
    var data = monitorHistorySeries(def);
    var limits = monitorHistoryLimits(state, def);
    var chartNode = body.querySelector("[data-icu-monitor-history-chart]");
    var statsNode = body.querySelector("[data-icu-monitor-history-stats]");
    if (chartNode) {
      chartNode.innerHTML = monitorHistoryChartHtml(def, data, limits);
    }
    if (statsNode) {
      statsNode.innerHTML = monitorHistoryStatsHtml(def, data);
    }
    monitorHistoryApplyHover(body);
  }

  var monitorPanelMode = "";
  /** 监护设置面板上一次渲染的 HTML：内容没变就别重建 DOM。 */
  var monitorSettingsHtmlCache = "";

  function onMonitorPanelClick(event) {
    var node = event.target;
    if (!node || !node.closest) {
      return;
    }
    var panel = node.closest("[data-icu-monitor-panel]");
    if (panel) {
      event.preventDefault();
      event.stopPropagation();
      openMonitorPanel(panel.getAttribute("data-icu-monitor-panel"));
      return;
    }
    var range = node.closest("[data-icu-monitor-range]");
    if (range) {
      event.preventDefault();
      event.stopPropagation();
      monitorHistoryRange = parseInt(range.getAttribute("data-icu-monitor-range"), 10) || 0;
      monitorHistoryHoverTime = null;
      monitorHistoryDirty = true;
      refreshMonitorPanel();
      return;
    }
    var metric = node.closest("[data-icu-monitor-metric]");
    if (metric) {
      event.preventDefault();
      event.stopPropagation();
      monitorHistoryMetric = metric.getAttribute("data-icu-monitor-metric") || "heartRate";
      monitorHistoryHoverTime = null;
      monitorHistoryDirty = true;
      refreshMonitorPanel();
      return;
    }
    var connect = node.closest("[data-icu-monitor-history-connect]");
    if (connect) {
      event.preventDefault();
      event.stopPropagation();
      if (window.IcuBlePanel && typeof window.IcuBlePanel.open === "function") {
        window.IcuBlePanel.open();
      }
      return;
    }
    var toggle = node.closest("[data-icu-monitor-toggle]");
    if (toggle) {
      event.preventDefault();
      event.stopPropagation();
      var kind = toggle.getAttribute("data-icu-monitor-toggle");
      if (kind === "volume") {
        callAction("monitor_volume", "监护音量");
      } else if (kind === "alarmSound") {
        callAction("monitor_alarm_toggle", "报警音");
      } else if (kind === "pulseBeep") {
        callAction("monitor_pulse_beep", "脉搏音");
      }
      return;
    }
    var limit = node.closest("[data-icu-monitor-limit]");
    if (limit) {
      event.preventDefault();
      event.stopPropagation();
      callAction("monitor_limit_edit_" + limit.getAttribute("data-icu-monitor-limit"), limit.textContent || "监护上下限");
    }
  }

  function refreshMonitorPanel() {
    if (!monitorPanelMode || !window.IcuOverlay || !window.IcuOverlay.isOpen()) {
      return;
    }
    var body = window.IcuOverlay.body();
    if (!body) {
      return;
    }
    var state = readFirstPhaseState();
    if (monitorPanelMode === "history") {
      if (monitorHistoryDirty || !body.querySelector("[data-icu-monitor-history-chart]")) {
        monitorHistoryDirty = false;
        body.innerHTML = monitorHistoryHtml(state);
      }
      renderMonitorHistoryChart(body, state);
      return;
    }
    // 原生状态是高频推送的，面板内容不变时若整块重建，
    // 手指按下到抬起之间节点被换掉，点击就丢了，表现为"点了没反应"。
    var html = monitorSettingsHtml(state);
    if (html !== monitorSettingsHtmlCache) {
      monitorSettingsHtmlCache = html;
      body.innerHTML = html;
    }
  }

  function openMonitorPanel(mode) {
    if (!window.IcuOverlay || typeof window.IcuOverlay.open !== "function") {
      return false;
    }
    monitorPanelMode = mode;
    if (mode === "history") {
      monitorHistoryDirty = true;
      monitorHistoryHoverTime = null;
    }
    var state = readFirstPhaseState();
    var html = mode === "history" ? monitorHistoryHtml(state) : monitorSettingsHtml(state);
    if (mode === "settings") {
      monitorSettingsHtmlCache = html;
    }
    window.IcuOverlay.open({
      title: mode === "history" ? "监护历史数据" : "监护设置",
      className: "icu-overlay-monitor",
      html: html,
      onMount: function (body) {
        body.addEventListener("click", onMonitorPanelClick);
        body.addEventListener("pointermove", monitorHistoryPointerMove);
        body.addEventListener("pointerdown", monitorHistoryPointerMove);
        body.addEventListener("pointerleave", monitorHistoryPointerLeave);
        // 面板之间切换时 open() 会先 close()，onClose 会清掉 monitorPanelMode，
        // 这里补回来，否则切换过去的面板不再刷新内容。
        monitorPanelMode = mode;
        if (mode === "history") {
          // 面板刚打开时图表容器是空的：立刻按当前数据画一次，
          // 否则用户只能看到一块白板，要等下一次状态推送才有内容。
          renderMonitorHistoryChart(body, state);
        }
      },
      onClose: function () {
        monitorPanelMode = "";
      }
    });
    return true;
  }

  /**
   * 面板打开期间定时重画趋势图：新测到的数值要能直接出现在曲线上，
   * 否则曲线停在打开那一刻的数据上，看不出监护过程中的变化。
   */
  function refreshMonitorHistoryIfOpen() {
    if (monitorPanelMode !== "history" || !window.IcuOverlay || !window.IcuOverlay.isOpen()) {
      return;
    }
    var body = window.IcuOverlay.body();
    if (!body) {
      return;
    }
    renderMonitorHistoryChart(body, readFirstPhaseState());
  }

  function showCameraPlayback() {
    cameraPlaybackMode = true;
    setCameraModeButtons();
    renderCamera();
    syncCameraPreview();
  }

  function showCameraLive() {
    cameraPlaybackMode = false;
    setCameraModeButtons();
    renderCamera();
    syncCameraPreview();
  }

  function setCameraModeClass(selector, active) {
    var node = document.querySelector(selector);
    if (!node) {
      return;
    }
    node.classList.toggle("icu-camera-mode-active", !!active);
    node.classList.toggle("icu-camera-mode-inactive", !active);
  }

  function setCameraModeButtons() {
    var page = pageName();
    if (page === "lanhu_4jiankong") {
      setCameraModeClass(".text-wrapper_23", !cameraPlaybackMode);
      setCameraModeClass(".text-wrapper_24", cameraPlaybackMode);
    } else if (page === "lanhu_5jiankonghuifang") {
      setCameraModeClass(".text-wrapper_29", false);
      setCameraModeClass(".text-wrapper_30", true);
    }
  }

  function ensureCameraConfigButton() {
    var hostNode = document.querySelector(".block_7");
    if (!hostNode || hostNode.querySelector(".icu-camera-config-button")) {
      return;
    }
    var button = document.createElement("button");
    button.type = "button";
    button.className = "icu-camera-config-button";
    button.textContent = "\u914d\u7f6e\u6444\u50cf\u5934";
    button.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      callAction("camera_config", "camera_config");
    }, true);
    hostNode.appendChild(button);
  }

  /**
   * 没有配置摄像头地址时，画面区给出明确的引导，而不是留一块空白。
   */
  function ensureCameraEmptyState() {
    var hostNode = document.querySelector(".block_7");
    if (!hostNode) {
      return null;
    }
    var node = hostNode.querySelector(".icu-camera-empty-state");
    if (node) {
      return node;
    }
    node = document.createElement("div");
    node.className = "icu-camera-empty-state";
    node.innerHTML = [
      '<div class="icu-camera-empty-title">\u672a\u8fde\u63a5\uff0c\u8bf7\u70b9\u51fb\u914d\u7f6e\u6444\u50cf\u5934</div>',
      '<button type="button" class="icu-camera-empty-button">\u914d\u7f6e\u6444\u50cf\u5934</button>'
    ].join("");
    var button = node.querySelector(".icu-camera-empty-button");
    button.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      callAction("camera_config", "camera_config");
    }, true);
    hostNode.appendChild(node);
    return node;
  }

  function syncCameraEmptyState() {
    var hostNode = document.querySelector(".block_7");
    if (!hostNode) {
      return;
    }
    var node = ensureCameraEmptyState();
    if (!node) {
      return;
    }
    var show = !cameraConfigured() && !cameraPlaybackMode;
    node.style.display = show ? "flex" : "none";
    // 空状态里已经有配置按钮了，角落里那个先收起来，避免重复。
    var configButton = hostNode.querySelector(".icu-camera-config-button");
    if (configButton) {
      configButton.style.display = show ? "none" : "";
    }
  }

  /**
   * 还没开放的功能统一置灰，并给图标和文字都绑上分项提示动作。
   */
  function markCameraFeatureOff() {
    for (var i = 0; i < CAMERA_FEATURE_OFF.length; i += 1) {
      var item = CAMERA_FEATURE_OFF[i];
      var wrapper = document.querySelector(item.wrapper);
      var label = document.querySelector(item.label);
      if (wrapper) {
        wrapper.classList.add("icu-camera-feature-off");
        wrapper.setAttribute("data-native-action", item.action);
      }
      if (label) {
        label.classList.add("icu-camera-feature-off");
        label.setAttribute("data-native-action", item.action);
      }
    }
  }

  function ensureCameraPlaybackPanel() {
    var hostNode = document.querySelector(".block_7");
    if (!hostNode) {
      return null;
    }
    var panel = hostNode.querySelector(".icu-camera-playback-panel");
    if (panel) {
      return panel;
    }
    panel = document.createElement("div");
    panel.className = "icu-camera-playback-panel";
    panel.innerHTML = [
      '<div class="icu-camera-playback-title">\u6444\u50cf\u56de\u653e</div>',
      '<div class="icu-camera-playback-list"></div>',
      '<div class="icu-camera-playback-recordings"></div>',
      '<div class="icu-camera-playback-empty">\u8fd8\u6ca1\u6709\u622a\u56fe\uff0c\u70b9\u51fb\u5b9e\u51b5\u9875\u7684\u201c\u622a\u56fe\u201d\u540e\u4f1a\u51fa\u73b0\u5728\u8fd9\u91cc\u3002</div>',
      '<div class="icu-camera-playback-actions">',
      '<button type="button" data-camera-action="live">\u8fd4\u56de\u5b9e\u51b5</button>',
      '<button type="button" data-camera-action="snapshot">\u622a\u56fe</button>',
      '<button type="button" data-camera-action="download">\u4e0b\u8f7d\u622a\u56fe</button>',
      '<button type="button" data-camera-action="delete">\u5220\u9664\u622a\u56fe</button>',
      '<button type="button" data-camera-action="config">\u914d\u7f6e\u6444\u50cf\u5934</button>',
      '</div>'
    ].join("");
    panel.addEventListener("click", function (event) {
      var target = event.target;
      if (!target || target.tagName !== "BUTTON") {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      var action = target.getAttribute("data-camera-action") || "";
      if (action === "live") {
        showCameraLive();
      } else if (action === "snapshot") {
        callAction("camera_snapshot", "camera_snapshot");
      } else if (action === "download") {
        callAction("camera_snapshot_download", "camera_snapshot_download");
      } else if (action === "delete") {
        callAction("camera_snapshot_delete", "camera_snapshot_delete");
      } else if (action === "config") {
        callAction("camera_config", "camera_config");
      }
    }, true);
    hostNode.appendChild(panel);
    return panel;
  }

  /**
   * 回放列表的数据来自原生：截图条目、当前选中项和本次会话保存的录像。
   */
  function cameraSnapshotData() {
    var fallback = { selected: -1, items: [], recordings: [] };
    if (!window.IcuNative || typeof window.IcuNative.cameraSnapshots !== "function") {
      return fallback;
    }
    try {
      return JSON.parse(window.IcuNative.cameraSnapshots() || "{}") || fallback;
    } catch (error) {
      return fallback;
    }
  }

  function cameraRecordingState() {
    if (!window.IcuNative || typeof window.IcuNative.cameraRecording !== "function") {
      return false;
    }
    try {
      return !!window.IcuNative.cameraRecording();
    } catch (error) {
      return false;
    }
  }

  function buildCameraSnapshotItem(item, selectedIndex) {
    var node = document.createElement("div");
    node.className = "icu-camera-playback-item";
    if (item.index === selectedIndex) {
      node.classList.add("icu-camera-playback-item-active");
    }
    node.setAttribute("data-camera-index", String(item.index));
    node.setAttribute("data-native-action", "camera_snapshot_select_" + item.index);
    var thumb = document.createElement("img");
    thumb.className = "icu-camera-playback-thumb";
    thumb.alt = item.timeText || "";
    if (item.uri) {
      thumb.src = item.uri;
    }
    node.appendChild(thumb);
    var meta = document.createElement("div");
    meta.className = "icu-camera-playback-meta";
    var time = document.createElement("div");
    time.className = "icu-camera-playback-time";
    time.textContent = item.timeText || "";
    var state = document.createElement("div");
    state.className = "icu-camera-playback-state";
    state.textContent = item.downloaded ? "\u5df2\u5bfc\u51fa\u5230\u76f8\u518c" : "\u672c\u5730";
    meta.appendChild(time);
    meta.appendChild(state);
    node.appendChild(meta);
    return node;
  }

  function buildCameraRecordingItem(item) {
    var node = document.createElement("div");
    node.className = "icu-camera-playback-item icu-camera-recording-item";
    node.setAttribute("data-native-action", "camera_recording_play_" + item.index);
    var thumb = document.createElement("div");
    thumb.className = "icu-camera-recording-thumb";
    thumb.textContent = "\u25b6";
    node.appendChild(thumb);
    var meta = document.createElement("div");
    meta.className = "icu-camera-playback-meta";
    var time = document.createElement("div");
    time.className = "icu-camera-playback-time";
    time.textContent = item.timeText || "";
    var state = document.createElement("div");
    state.className = "icu-camera-playback-state";
    state.textContent = "\u70b9\u51fb\u64ad\u653e";
    meta.appendChild(time);
    meta.appendChild(state);
    node.appendChild(meta);
    return node;
  }

  function renderCameraPlaybackList(panel) {
    if (!panel) {
      return;
    }
    var list = panel.querySelector(".icu-camera-playback-list");
    var recordList = panel.querySelector(".icu-camera-playback-recordings");
    var empty = panel.querySelector(".icu-camera-playback-empty");
    if (!list) {
      return;
    }
    var data = cameraSnapshotData();
    var items = data.items || [];
    var recordings = data.recordings || [];
    var selected = data.selected === undefined ? -1 : data.selected;
    var signature = JSON.stringify(items) + "|" + JSON.stringify(recordings) + "|" + selected;
    list.style.display = items.length ? "flex" : "none";
    if (list.getAttribute("data-signature") === signature) {
      return;
    }
    list.setAttribute("data-signature", signature);
    list.innerHTML = "";
    for (var i = 0; i < items.length; i += 1) {
      list.appendChild(buildCameraSnapshotItem(items[i], selected));
    }
    if (empty) {
      empty.style.display = items.length ? "none" : "flex";
    }
    if (recordList) {
      recordList.innerHTML = "";
      for (var j = 0; j < recordings.length; j += 1) {
        recordList.appendChild(buildCameraRecordingItem(recordings[j]));
      }
      recordList.style.display = recordings.length ? "flex" : "none";
    }
  }

  /**
   * 正在录像时把“录制”按钮切换成“停止录制”。
   */
  function syncCameraRecordState() {
    var recording = cameraRecordingState();
    var label = document.querySelector(".text-group_3");
    if (label) {
      label.textContent = recording ? "\u505c\u6b62\u5f55\u5236" : "\u5f55\u5236";
    }
    var box = document.querySelector(".box_19");
    if (box) {
      box.classList.toggle("icu-camera-record-active", recording);
    }
  }

  function renderCameraPlaybackPanel() {
    var panel = ensureCameraPlaybackPanel();
    if (!panel) {
      return;
    }
    panel.style.display = cameraPlaybackMode ? "flex" : "none";
    renderCameraPlaybackList(panel);
  }

  function renderCamera() {
    setCameraModeButtons();
    if (pageName() !== "lanhu_4jiankong") {
      return;
    }
    ensureCameraConfigButton();
    markCameraFeatureOff();
    syncCameraEmptyState();
    syncCameraRecordState();
    renderCameraPlaybackPanel();
    var now = new Date();
    setText(".text_60", formatDateTime(now));
    setText(".text_61", weekdayText(now));
  }

  function nativeCameraPreview(left, top, width, height, viewportWidth, viewportHeight, visible) {
    if (!window.IcuNative || typeof window.IcuNative.cameraPreview !== "function") {
      return;
    }
    try {
      window.IcuNative.cameraPreview(left || 0, top || 0, width || 0, height || 0,
        viewportWidth || 1920, viewportHeight || 1200, !!visible);
    } catch (error) {
    }
  }

  function cameraConfigured() {
    if (!window.IcuNative || typeof window.IcuNative.cameraConfigured !== "function") {
      return true;
    }
    try {
      return !!window.IcuNative.cameraConfigured();
    } catch (error) {
      return true;
    }
  }

  function syncCameraPreview() {
    if (document.querySelector(".icu-patient-modal")) {
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      return;
    }
    // 未配置摄像头地址时不显示内嵌预览，露出后面页面自身的内容
    if (!cameraConfigured()) {
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      return;
    }
    var page = pageName();
    var selector = "";
    var inset = 8;
    if (page === "lanhu_4jiankong") {
      if (cameraPlaybackMode) {
        nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
        return;
      }
      // 摄像监控页已移除摄像头底图，预览面板直接嵌入原图片位置

      if (document.querySelector(".icu-camera-viewport")) {

        selector = ".icu-camera-viewport";

        inset = 0;

      } else {

        selector = ".block_7";

      }

    } else if (page === "lanhu_1icuzhuangtaikaobei") {
      selector = ".image-wrapper_4";
      // ICU 状态页已移除摄像头底图，预览面板直接铺满该容器
      inset = 0;
    } else {
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      return;
    }
    var target = document.querySelector(selector);
    if (!target || !target.getBoundingClientRect) {
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      return;
    }
    var rect = target.getBoundingClientRect();
    if (!rect || rect.width < 20 || rect.height < 20) {
      nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
      return;
    }
    var viewportWidth = document.documentElement.clientWidth || window.innerWidth || 1920;
    var viewportHeight = document.documentElement.clientHeight || window.innerHeight || 1200;
    nativeCameraPreview(rect.left + inset, rect.top + inset,
      Math.max(1, rect.width - inset * 2), Math.max(1, rect.height - inset * 2),
      viewportWidth, viewportHeight, true);
  }

  /** 波形覆盖层：只按监护仪真实采样绘制，采样不足时显示等待提示。 */
  function installWave(selector, color, key) {
    var target = document.querySelector(selector);
    if (!target) {
      return;
    }
    var old = target.querySelector(".icu-wave");
    var spec = monitorWaveSpecOf(key, color);
    var samples = spec.type >= 0 ? waveSamplesOfType(spec.type) : [];
    if (samples.length >= 2 && waveHasSignal(samples)) {
      showWaveContent(target, old, waveSvg(samples, color));
      return;
    }
    var total = 0;
    for (var i = 0; i < spec.trend.length; i += 1) {
      total += spec.trend[i].values.length;
    }
    if (total) {
      showWaveContent(target, old, trendSvg(spec.trend) + trendMetaHtml(spec.trend, waveSignalNote(spec, samples)));
      return;
    }
    showWaveEmpty(target, old, waveEmptyLabel(samples.length >= 2 ? "flat" : "waiting", key, spec.channel));
  }

  function showWaveEmpty(target, old, label) {
    if (old) {
      old.remove();
    }
    target.classList.add("icu-wave-empty");
    target.setAttribute("data-empty-label", label);
  }

  function showWaveContent(target, old, html) {
    target.classList.remove("icu-wave-empty");
    target.removeAttribute("data-empty-label");
    if (!old) {
      old = document.createElement("div");
      old.className = "icu-wave";
      target.appendChild(old);
    }
    old.innerHTML = html;
  }

  /** 采样恒定说明通道没有真实波形信号，画一条直线会让人误以为曲线卡住了。 */
  function waveHasSignal(samples) {
    var count = Math.min(samples.length, WAVE_WINDOW_SAMPLES);
    if (count < 2) {
      return false;
    }
    var start = samples.length - count;
    var min = samples[start];
    var max = samples[start];
    for (var i = start + 1; i < samples.length; i += 1) {
      if (samples[i] < min) {
        min = samples[i];
      }
      if (samples[i] > max) {
        max = samples[i];
      }
    }
    return max - min > WAVE_FLAT_AMPLITUDE;
  }

  function trendSvg(series) {
    var top = 26;
    var bottom = 72;
    var min = Infinity;
    var max = -Infinity;
    var total = 0;
    for (var s = 0; s < series.length; s += 1) {
      var values = series[s].values || [];
      for (var i = 0; i < values.length; i += 1) {
        total += 1;
        if (values[i] < min) {
          min = values[i];
        }
        if (values[i] > max) {
          max = values[i];
        }
      }
    }
    if (!total) {
      return "";
    }
    if (max - min < 1) {
      var center = (max + min) / 2;
      min = center - 0.5;
      max = center + 0.5;
    }
    var html = ['<svg viewBox="0 0 320 86" width="100%" height="100%" preserveAspectRatio="none">'];
    for (var g = 0; g <= 2; g += 1) {
      var gy = (top + (bottom - top) * g / 2).toFixed(1);
      html.push('<line x1="0" y1="' + gy + '" x2="320" y2="' + gy + '" stroke="#5f7d99" stroke-opacity="0.25" stroke-width="1" vector-effect="non-scaling-stroke"/>');
    }
    for (var k = 0; k < series.length; k += 1) {
      var item = series[k];
      var points = buildTrendPoints(item.values, min, max, top, bottom);
      if (!points.length) {
        continue;
      }
      if (points.length > 1) {
        html.push('<path d="' + trendAreaPath(points, bottom) + '" fill="' + item.color + '" fill-opacity="0.10" stroke="none"/>');
      }
      html.push('<path d="' + wavePath(points) + '" fill="none" stroke="' + item.color + '" stroke-width="3" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>');
    }
    html.push('</svg>');
    return html.join("");
  }

  function buildTrendPoints(values, min, max, top, bottom) {
    if (!values || !values.length) {
      return [];
    }
    var count = Math.min(values.length, TREND_WINDOW_SAMPLES);
    var start = values.length - count;
    var span = (max - min) || 1;
    var points = [];
    for (var i = 0; i < count; i += 1) {
      var t = count <= 1 ? 1 : i / (count - 1);
      var ratio = (values[start + i] - min) / span;
      points.push([t * 316, bottom - ratio * (bottom - top)]);
    }
    return points;
  }

  function trendAreaPath(points, bottom) {
    var d = "M" + points[0][0].toFixed(1) + " " + bottom.toFixed(1);
    for (var i = 0; i < points.length; i += 1) {
      d += "L" + points[i][0].toFixed(1) + " " + points[i][1].toFixed(1);
    }
    d += "L" + points[points.length - 1][0].toFixed(1) + " " + bottom.toFixed(1) + "Z";
    return d;
  }

  function trendMetaHtml(series, note) {
    var legend = [];
    var ranges = [];
    var total = 0;
    for (var i = 0; i < series.length; i += 1) {
      var item = series[i];
      var values = item.values || [];
      if (!values.length) {
        continue;
      }
      var min = values[0];
      var max = values[0];
      for (var j = 1; j < values.length; j += 1) {
        if (values[j] < min) {
          min = values[j];
        }
        if (values[j] > max) {
          max = values[j];
        }
      }
      total = Math.max(total, values.length);
      legend.push('<span class="icu-wave-legend-item"><i style="background:' + item.color + '"></i>'
        + item.name + " " + formatTrendValue(item, values[values.length - 1]) + " " + item.unit + "</span>");
      ranges.push(item.name + " " + formatTrendValue(item, min) + "~" + formatTrendValue(item, max) + " " + item.unit);
    }
    if (!legend.length) {
      return "";
    }
    var meta = '<div class="icu-wave-meta">'
      + '<span class="icu-wave-mode">测量趋势</span>'
      + '<div class="icu-wave-legend">' + legend.join("") + "</div>"
      + '<div class="icu-wave-range">' + ranges.join(" · ") + " · 共 " + total + " 次测量</div>"
      + "</div>";
    if (note) {
      meta += '<div class="icu-wave-note">' + note + "</div>";
    }
    return meta;
  }

  function formatTrendValue(item, value) {
    var digits = item.digits === undefined ? 0 : item.digits;
    return Number(value).toFixed(digits);
  }

  /** 按当前选中的波形通道重绘，只做绘制，不触发整页数值渲染。 */
  function installMonitorWaves() {
    if (pageName() !== "lanhu_3shishijianhu") {
      return;
    }
    for (var i = 0; i < wavePlan.length; i += 1) {
      installWave(wavePlan[i].selector, wavePlan[i].color, wavePlan[i].key);
    }
  }

  function waveSamplesOfType(type) {
    var samples = waveData[waveKeyOfType(type)];
    return samples && samples.length ? samples : [];
  }

  function waveKeyOfType(type) {
    if (type === 0) {
      return "ecg";
    }
    if (type === 1) {
      return "spo2";
    }
    return "resp";
  }

  function waveEmptyLabel(reason, key, channel) {
    if (!waveConnected) {
      return "未连接监护仪，无波形数据";
    }
    if (reason === "flat") {
      var stat = waveStats[waveStatsKeyOf(key)] || null;
      var detail = stat && stat.count
        ? "（采样 " + stat.count + " 点，幅度 " + stat.amplitude + "）"
        : "";
      return (channel ? channel + " " : "该") + "通道无波形信号" + detail + "，请检查导联/探头连接";
    }
    if (!channel) {
      return "该参数暂无测量数据";
    }
    return "等待监护仪数据";
  }

  /**
   * 波形通道（ECG/PLETH/RESP）没有真实信号时必须如实说明，
   * 同时保留监护仪真实测量值的趋势，让使用者知道“数值在变、波形通道没信号”。
   */
  function waveSignalNote(spec, samples) {
    if (!spec || spec.type < 0 || !spec.channel) {
      return "";
    }
    return waveEmptyLabel(samples.length >= 2 ? "flat" : "waiting", spec.key, spec.channel);
  }

  function waveStatsKeyOf(key) {
    if (key === "heartRate") {
      return "ecg";
    }
    if (key === "spo2" || key === "pulse") {
      return "spo2";
    }
    return key;
  }

  function waveSvg(samples, color) {
    var points = buildWavePoints(samples);
    if (!points.length) {
      return "";
    }
    var path = wavePath(points);
    return [
      '<svg viewBox="0 0 320 86" width="100%" height="100%" preserveAspectRatio="none">',
      '<path d="' + path + '" fill="none" stroke="' + color + '" stroke-width="11" stroke-opacity="0.16" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>',
      '<path d="' + path + '" fill="none" stroke="' + color + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>',
      '</svg>'
    ].join("");
  }

  // 与原生兜底面板 drawSmoothSampleWave 同一套算法：窗口内 min/max 归一化，再做一阶平滑。
  function buildWavePoints(samples) {
    if (!samples || samples.length < 2) {
      return [];
    }
    var count = Math.min(samples.length, WAVE_WINDOW_SAMPLES);
    var start = samples.length - count;
    var min = samples[start];
    var max = samples[start];
    for (var i = start + 1; i < samples.length; i += 1) {
      var value = samples[i];
      if (value < min) {
        min = value;
      }
      if (value > max) {
        max = value;
      }
    }
    var range = Math.max(12, max - min);
    var center = (max + min) / 2;
    var baseline = 43;
    var amp = 24;
    var top = baseline - amp * 1.72;
    var bottom = baseline + amp * 1.72;
    var points = [];
    var lastY = baseline;
    for (var j = 0; j < count; j += 1) {
      var t = count <= 1 ? 0 : j / (count - 1);
      var normalized = (samples[start + j] - center) / range;
      var y = baseline - normalized * amp * 1.62;
      if (j > 0) {
        y = lastY * 0.18 + y * 0.82;
      }
      if (y < top) {
        y = top;
      }
      if (y > bottom) {
        y = bottom;
      }
      lastY = y;
      points.push([t * 320, y]);
    }
    return points;
  }

  function wavePath(points) {
    var d = "M" + points[0][0].toFixed(1) + " " + points[0][1].toFixed(1);
    for (var i = 1; i < points.length - 1; i += 1) {
      var midX = ((points[i][0] + points[i + 1][0]) / 2).toFixed(1);
      var midY = ((points[i][1] + points[i + 1][1]) / 2).toFixed(1);
      d += "Q" + points[i][0].toFixed(1) + " " + points[i][1].toFixed(1) + " " + midX + " " + midY;
    }
    var last = points[points.length - 1];
    d += "L" + last[0].toFixed(1) + " " + last[1].toFixed(1);
    return d;
  }

  /**
   * 轻量数值桥：只解析七个字段。旧版本 WebView 没有 vitalsState 时退回
   * waveState().vitals，保证降级包也不会丢数值。
   */
  function readVitalsState() {
    if (window.IcuNative && typeof window.IcuNative.vitalsState === "function") {
      try {
        return JSON.parse(window.IcuNative.vitalsState() || "{}") || null;
      } catch (error) {
        return null;
      }
    }
    var wave = readWaveState();
    return wave ? wave.vitals : null;
  }

  /** 只写六个监护数值，不碰波形、病例、治疗记录等整页渲染内容。 */
  function refreshVitals() {
    if (pageName() !== "lanhu_3shishijianhu") {
      return;
    }
    writeMonitorValues(readVitalsState());
  }

  /** 读取监护仪真实波形采样；原生未提供该接口时返回 null。 */
  function readWaveState() {
    if (!window.IcuNative || typeof window.IcuNative.waveState !== "function") {
      return null;
    }
    try {
      return JSON.parse(window.IcuNative.waveState() || "{}") || null;
    } catch (error) {
      return null;
    }
  }

  function waveSignatureOf(samples) {
    if (!samples || !samples.length) {
      return "-";
    }
    var last = samples[samples.length - 1];
    var prev = samples.length > 1 ? samples[samples.length - 2] : last;
    return samples.length + ":" + prev + ":" + last;
  }

  /** 轮询原生波形采样，只有采样变化时才重绘曲线。 */
  function refreshWaveData() {
    if (pageName() !== "lanhu_3shishijianhu") {
      return;
    }
    // 先用轻量桥把数值写进 DOM，再去构建/解析整套波形 JSON。
    // 数值显示不能排在 1200+ 采样点的序列化后面。
    refreshVitals();
    var next = readWaveState();
    if (!next) {
      return;
    }
    var connected = !!next.connected;
    // 轻量数值通道：波形未变也按 120ms 把六项数值刷到最新，不等全量渲染。
    writeMonitorValues(next.vitals);
    var signature = waveSignatureOf(next.ecg) + "|" + waveSignatureOf(next.spo2) + "|"
      + waveSignatureOf(next.resp) + "|" + waveSignatureOf(next.heartRateTrend) + "|"
      + waveSignatureOf(next.spo2Trend) + "|" + waveSignatureOf(next.pulseTrend) + "|"
      + waveSignatureOf(next.respTrend) + "|" + waveSignatureOf(next.nibpSysTrend) + "|"
      + waveSignatureOf(next.nibpDiaTrend) + "|" + waveSignatureOf(next.tempTrend) + "|"
      + waveSignatureOf(next.tempEarTrend) + "|" + (connected ? 1 : 0);
    if (signature === waveSignature) {
      return;
    }
    waveSignature = signature;
    waveConnected = connected;
    waveData.ecg = next.ecg || [];
    waveData.spo2 = next.spo2 || [];
    waveData.resp = next.resp || [];
    waveTrend.nibpSys = next.nibpSysTrend || [];
    waveTrend.nibpDia = next.nibpDiaTrend || [];
    waveTrend.temp = next.tempTrend || [];
    waveTrend.tempEar = next.tempEarTrend || [];
    waveTrend.heartRate = next.heartRateTrend || [];
    waveTrend.spo2 = next.spo2Trend || [];
    waveTrend.pulseRate = next.pulseTrend || [];
    waveTrend.respRate = next.respTrend || [];
    waveStats.ecg = next.stats ? next.stats.ecg : null;
    waveStats.spo2 = next.stats ? next.stats.spo2 : null;
    waveStats.resp = next.stats ? next.stats.resp : null;
    installMonitorWaves();
  }

  function renderHostModes(state) {
    if (pageName() !== "lanhu_2zhujikongzhi") {
      return;
    }
    var modeSelectors = [
      { text: ".text_104", icon: ".group_47", toggle: ".image_15", action: "host_mode_mother" },
      { text: ".text_106", icon: ".group_52", toggle: ".image_17", action: "host_mode_postop" },
      { text: ".text_108", icon: ".group_57", toggle: ".image_19", action: "host_mode_cardio" },
      { text: ".text_110", icon: ".box_47", toggle: ".image_21", action: "host_mode_custom" }
    ];
    for (var i = 0; i < modeSelectors.length; i += 1) {
      var mode = (state.hostModes || [])[i] || {};
      var textNode = document.querySelector(modeSelectors[i].text);
      var iconNode = document.querySelector(modeSelectors[i].icon);
      if (textNode) {
        textNode.textContent = display(mode.name);
      }
      if (iconNode) {
        iconNode.classList.remove("icu-mode-dot", "on", "last");
      }
      renderHostModeSwitch(modeSelectors[i].toggle, modeSelectors[i].action,
        !!mode.enabled, !!mode.configured, !!mode.pending);
    }
  }

  function renderHostModeSwitch(selector, action, on, configured, pending) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i += 1) {
      var img = nodes[i];
      var parent = img.parentNode;
      if (!parent) {
        continue;
      }
      var fake = parent.querySelector('.icu-switch-fake[data-for="' + selector + '"]');
      if (!fake) {
        fake = document.createElement("span");
        fake.className = "icu-switch-fake";
        fake.setAttribute("data-for", selector);
        parent.insertBefore(fake, img.nextSibling);
      }
      fake.setAttribute("data-native-action", action);
      fake.setAttribute("title", pending
        ? "模式指令已排队，等待主机确认"
        : (on
          ? "当前模式已开启：点击关闭该模式已下发的功能"
          : (configured
            ? "点击切换到该模式；四个模式一次只开启一个"
            : "尚未自定义，先点模式名进入设置")));
      fake.classList.toggle("on", !!on);
      fake.classList.toggle("off", !on);
      img.style.display = "none";
    }
  }

  function renderTreatments(state) {
    if (pageName() !== "lanhu_6zhiliaojilu") {
      return;
    }
    var container = document.querySelector(".block_6");
    if (!container) {
      return;
    }
    container.classList.add("icu-treatment-table");
    var header = container.querySelector(".text-wrapper_24");
    ensureTreatmentActions(container);
    renderTreatmentPeriodControls(container, state);
    if (header) {
      header.classList.add("icu-treatment-header");
      markTreatmentHeaderCells(header.querySelectorAll("span"));
    }
    var rows = [".group_51", ".group_6", ".group_52", ".group_8", ".group_53", ".group_10", ".group_54", ".group_12", ".group_55", ".group_14", ".group_56", ".group_16", ".group_57", ".group_18", ".group_58", ".group_20", ".group_59", ".group_22", ".group_60", ".group_24", ".group_61", ".box_25"];
    var treatments = state.treatments || [];
    for (var i = 0; i < rows.length; i += 1) {
      var row = container.querySelector(rows[i]);
      if (!row) {
        continue;
      }
      row.classList.add("icu-treatment-row");
      var item = treatments[i];
      if (!item) {
        clearTreatmentRow(row);
        row.style.visibility = "hidden";
        row.removeAttribute("data-native-action");
        row.classList.remove("icu-treatment-selected");
        continue;
      }
      row.style.visibility = "";
      row.classList.toggle("icu-treatment-selected", selectedTreatmentRow === i);
      row.setAttribute("data-native-action", "treatment_edit_" + i);
      row.setAttribute("title", "编辑治疗记录 - " + display(item.itemName));
      bindTreatmentRowSelection(row, i);
      var spans = row.querySelectorAll("span");
      markTreatmentRowCells(row, spans);
      // 按语义字段写值，不再按 span 出现顺序：否则"是否打开"列会被写入时段文本。
      var fields = {
        index: pad2(item.index || i + 1),
        itemName: display(item.itemName),
        startTime: display(item.startTime),
        endTime: display(item.endTime),
        period: treatmentPeriodText(item, state),
        average: display(item.average),
        high: display(item.high),
        low: display(item.low)
      };
      var cells = row.querySelectorAll("[data-icu-col-field]");
      for (var k = 0; k < cells.length; k += 1) {
        var node = cells[k];
        if (node.tagName === "IMG") {
          continue;
        }
        var field = node.getAttribute("data-icu-col-field");
        var text = Object.prototype.hasOwnProperty.call(fields, field) ? fields[field] : "";
        node.textContent = text;
        node.setAttribute("title", text);
        if (field === "average" && clean(item.statsNote)) {
          node.setAttribute("data-icu-stats-note", clean(item.statsNote));
          node.setAttribute("title", text + "｜" + clean(item.statsNote));
        } else {
          node.removeAttribute("data-icu-stats-note");
        }
      }
      setTreatmentChecks(row, item);
      setTreatmentRowState(row, item);
    }
    setText(".text_60", "治疗记录表");
  }

  function ensureTreatmentActions(container) {
    if (!container || container.querySelector(".icu-treatment-actions")) {
      return;
    }
    var actions = document.createElement("div");
    actions.className = "icu-treatment-actions";
    actions.innerHTML = [
      '<button type="button" data-native-action="treatment_new">新建治疗记录</button>',
      '<button type="button" data-native-action="treatment_finish">结束当前治疗</button>',
      '<span class="icu-treatment-period">',
      '<button type="button" data-period-mode="all" data-native-action="treatment_period_all">全时段</button>',
      '<button type="button" data-period-mode="enabled" data-native-action="treatment_period_enabled">功能开启时段</button>',
      '<button type="button" data-period-mode="custom" data-native-action="treatment_period_custom">自定义时段</button>',
      '</span>'
    ].join("");
    // 表格下方的空白区放操作条，不占用表头和表格本身
    container.appendChild(actions);
  }

  function renderTreatmentPeriodControls(container, state) {
    var mode = state.treatmentPeriodMode || "all";
    var buttons = container.querySelectorAll("[data-period-mode]");
    for (var i = 0; i < buttons.length; i += 1) {
      buttons[i].classList.toggle("active", buttons[i].getAttribute("data-period-mode") === mode);
    }
  }

  function clearTreatmentColClasses(node) {
    for (var i = 0; i <= 12; i += 1) {
      node.classList.remove("icu-col-" + i);
    }
  }

  function markTreatmentCell(node, col) {
    if (!node) {
      return;
    }
    node.classList.add("icu-treatment-cell");
    clearTreatmentColClasses(node);
    node.classList.add("icu-col-" + col);
    node.style.gridColumn = String(col + 1);
    node.style.gridRow = "1";
  }

  // 10 列的语义名：表头、文本单元格、图标单元格共用，避免按 DOM 顺序错位。
  var TREATMENT_COL_FIELDS = [
    "index", "itemName", "startTime", "endTime", "enabled", "includeInReport",
    "period", "average", "high", "low"
  ];
  var TREATMENT_SPAN_FIELDS = [
    "index", "itemName", "startTime", "endTime", "period", "average", "high", "low"
  ];

  function setTreatmentField(node, field) {
    if (node && field) {
      node.setAttribute("data-icu-col-field", field);
    }
  }

  function markTreatmentHeaderCells(spans) {
    for (var i = 0; i < spans.length; i += 1) {
      markTreatmentCell(spans[i], i);
      setTreatmentField(spans[i], TREATMENT_COL_FIELDS[i]);
    }
  }

  function markTreatmentRowCells(row, spans) {
    var spanCols = [0, 1, 2, 3, 6, 7, 8, 9];
    for (var i = 0; i < spans.length && i < spanCols.length; i += 1) {
      markTreatmentCell(spans[i], spanCols[i]);
      setTreatmentField(spans[i], TREATMENT_SPAN_FIELDS[i]);
    }
    var icons = row.querySelectorAll("img");
    for (var j = 0; j < icons.length; j += 1) {
      icons[j].classList.add("icu-treatment-icon");
      icons[j].style.gridColumn = String(j + 5);
      icons[j].style.gridRow = "1";
      setTreatmentField(icons[j], TREATMENT_COL_FIELDS[j + 4]);
    }
  }

  function bindTreatmentRowSelection(row, index) {
    row.setAttribute("data-treatment-index", String(index));
    if (row.getAttribute("data-treatment-select-ready") === "1") {
      return;
    }
    row.setAttribute("data-treatment-select-ready", "1");
    function selectRow() {
      var parsed = Number(row.getAttribute("data-treatment-index") || -1);
      if (parsed < 0 || selectedTreatmentRow === parsed) {
        return;
      }
      selectedTreatmentRow = parsed;
      var rows = document.querySelectorAll(".icu-treatment-row");
      for (var i = 0; i < rows.length; i += 1) {
        rows[i].classList.toggle("icu-treatment-selected", rows[i] === row);
      }
    }
    row.addEventListener("touchstart", selectRow, { passive: true });
    row.addEventListener("mousedown", selectRow, true);
    row.addEventListener("click", selectRow, true);
  }

  function clearTreatmentRow(row) {
    var spans = row.querySelectorAll("span");
    for (var i = 0; i < spans.length; i += 1) {
      spans[i].textContent = "";
      spans[i].removeAttribute("title");
    }
    var icons = row.querySelectorAll("img");
    for (var j = 0; j < icons.length; j += 1) {
      icons[j].style.opacity = "0";
    }
  }

  // 治疗记录表的“对勾/叉”素材：蓝湖导出时每个行给的是不同素材，这里统一成一套。
  var TREATMENT_CHECK_ON_SRC = "./assets/img/5018c4d5ecb4758ebda973afc1bd34a4.png";
  var TREATMENT_CHECK_OFF_SRC = "./assets/img/4be4dcada12c963209dd5832a77bbc85.png";

  function treatmentHasData(item) {
    return !!item && (
      Number(item.sampleCount || 0) > 0 ||
      !!clean(item.manualValue) ||
      !!clean(item.lastValue) ||
      !!clean(item.average) ||
      !!clean(item.high) ||
      !!clean(item.low) ||
      // “是否打开”列反映的是功能实时状态，这一项还没采到数也要显示出来
      item.enabled !== undefined
    );
  }

  /**
   * “选取时段”列：原生给出的时段口径优先，
   * 没有则按当前选择的时段模式推断一个。
   */
  function treatmentPeriodText(item, state) {
    var explicit = clean(item && item.period);
    if (explicit) {
      return explicit;
    }
    var mode = clean(state && state.treatmentPeriodMode) || "all";
    if (mode === "custom") {
      return "自定义时段";
    }
    if (mode === "enabled" || isEnabledWindowTreatment(clean(item && item.itemName))) {
      return "功能开启时段";
    }
    return "全时段";
  }

  function isEnabledWindowTreatment(name) {
    return /红外|蓝光|雾化|负离子|紫外/.test(name);
  }

  function setTreatmentChecks(row, item) {
    var icons = row.querySelectorAll("img");
    var sessionItem = isEnabledWindowTreatment(clean(item && item.itemName));
    // “是否打开”列必须反映真实状态，不能拿 entry.enabled（纳入统计，默认勾选）来画对勾：
    // 那样即使主机与监护蓝牙都没连接，也会因为"纳入统计"默认为真而整列显示 √。
    // 会话项（红外/蓝光/雾化/负离子/紫外）看真实使用 used；
    // 读数型项（4 个环境传感器 + 监护生命体征/波形）看 hasReading：
    // 首次读到有效数值后即为 √，之后断连或关开关也不回退。
    // 其余项看设备/链路真实状态 featureOn，断连即清零。
    var featureOn = item && item.featureOn !== undefined ? item.featureOn : false;
    var used = item && item.used !== undefined
      ? item.used : !!(item && (item.featureOn || clean(item.usage)));
    var readingTracked = !!(item && item.readingTracked);
    var hasReading = !!(item && item.hasReading);
    var opened = sessionItem ? !!used : (readingTracked ? hasReading : !!featureOn);
    var states = [opened, !!(item && item.includeInReport)];
    var labels = [sessionItem ? "是否打开（真实使用）"
      : (readingTracked ? "是否打开（已读数）" : "是否打开（设备状态）"), "报告录入"];
    for (var i = 0; i < icons.length && i < states.length; i += 1) {
      var state = states[i];
      var label = labels[i] + "：" + (state ? "是" : "否");
      // 蓝湖导出的图标素材不统一，这里统一切换；同时把状态写进属性，
      // 便于真机导出 DOM 时确认"列显示"与原生字段一致，而不是停在 "--"。
      icons[i].src = state ? TREATMENT_CHECK_ON_SRC : TREATMENT_CHECK_OFF_SRC;
      icons[i].style.opacity = "1";
      icons[i].style.filter = "";
      icons[i].alt = label;
      icons[i].setAttribute("data-icu-state", state ? "on" : "off");
      icons[i].setAttribute("data-icu-text", state ? "是" : "否");
      icons[i].setAttribute("title", label);
    }
  }

  function setTreatmentRowState(row, item) {
    if (!row || !item) {
      return;
    }
    var featureOn = item.featureOn !== undefined ? item.featureOn : false;
    var recordEnabled = item.recordEnabled !== undefined ? item.recordEnabled : item.enabled;
    var sessionItem = isEnabledWindowTreatment(clean(item.itemName));
    var used = item.used !== undefined ? item.used : !!(featureOn || clean(item.usage));
    var readingTracked = !!item.readingTracked;
    var hasReading = !!item.hasReading;
    var sampleCount = Number(item.periodSampleCount || 0);
    row.setAttribute("data-icu-feature-on", featureOn ? "1" : "0");
    row.setAttribute("data-icu-used", used ? "1" : "0");
    row.setAttribute("data-icu-reading-tracked", readingTracked ? "1" : "0");
    row.setAttribute("data-icu-has-reading", hasReading ? "1" : "0");
    row.setAttribute("data-icu-record-enabled", recordEnabled ? "1" : "0");
    row.setAttribute("data-icu-include-report", item.includeInReport ? "1" : "0");
    row.setAttribute("data-icu-sample-count", String(sampleCount));
    row.setAttribute("data-icu-stats-reliable", item.statsReliable ? "1" : "0");
    row.setAttribute("title", "编辑治疗记录 - " + display(item.itemName)
      + (sessionItem ? "｜是否打开（真实使用）:" + (used ? "是" : "否") : "")
      + "｜纳入统计:" + (recordEnabled ? "是" : "否")
      + "｜设备实时状态:" + (featureOn ? "开" : "关")
      + "｜报告录入:" + (item.includeInReport ? "是" : "否")
      + "｜有效样本:" + sampleCount
      + (clean(item.statsNote) ? "｜" + clean(item.statsNote) : ""));
  }

  function renderTutorial() {
    if (pageName() !== "lanhu_7jiaocheng") {
      return;
    }
    cleanupTutorialPanel();
    // 六个入口展示本机相册里最新的六条素材，后台推送的资料可以覆盖对应位置
    renderTutorialMaterials();
  }

  function renderTutorialMaterials() {
    if (window.IcuTutorial && typeof window.IcuTutorial.render === "function") {
      window.IcuTutorial.render();
      return;
    }
    // 教程模块是动态注入的，等它加载完再渲染一次
    window.setTimeout(renderTutorialMaterials, 150);
  }

  function cleanupTutorialPanel() {
    var nodes = document.querySelectorAll(".icu-tutorial-panel");
    for (var i = 0; i < nodes.length; i += 1) {
      nodes[i].remove();
    }
  }

  function pad2(value) {
    value = String(value || "");
    return value.length < 2 ? "0" + value : value;
  }

  function formatDateTime(date) {
    return date.getFullYear() + "-" +
      pad2(date.getMonth() + 1) + "-" +
      pad2(date.getDate()) + "  " +
      pad2(date.getHours()) + ":" +
      pad2(date.getMinutes()) + ":" +
      pad2(date.getSeconds());
  }

  function weekdayText(date) {
    return ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"][date.getDay()];
  }

  function installActions() {
    document.addEventListener("click", function (event) {
      var node = event.target;
      while (node && node !== document.body) {
        var text = (node.textContent || "").replace(/\s+/g, "").trim();
        if (text === "母幼护理模式") {
          event.preventDefault();
          callAction("host_mode_config_mother", text);
          return;
        }
        if (text === "术后护理模式") {
          event.preventDefault();
          callAction("host_mode_config_postop", text);
          return;
        }
        if (text === "心肺护理模式") {
          event.preventDefault();
          callAction("host_mode_config_cardio", text);
          return;
        }
        if (text === "自定义模式") {
          event.preventDefault();
          callAction("host_mode_config_custom", text);
          return;
        }
        node = node.parentNode;
      }
    }, true);
  }

  var STARTUP_TREATMENT_PROMPT_KEY = "icu_startup_treatment_prompt_v1";

  function ensureStartupTreatmentPrompt() {
    var store = window.IcuPatientTemp;
    if (!store || typeof store.storageGet !== "function" || typeof store.storageSet !== "function") {
      return;
    }
    if (store.storageGet(STARTUP_TREATMENT_PROMPT_KEY) === "1") {
      return;
    }
    if (!window.IcuOverlay || typeof window.IcuOverlay.showBanner !== "function") {
      return;
    }
    store.storageSet(STARTUP_TREATMENT_PROMPT_KEY, "1");
    var state = readFirstPhaseState();
    var records = state.cases || [];
    if (!records.length) {
      // 一条记录都没有：先让人建第一条，"继续当前记录"没有意义
      window.IcuOverlay.showBanner({
        title: "暂无治疗记录",
        text: "当前舱还没有治疗记录，点击下方按钮新建一条空白记录后再开始监护。",
        buttons: [
          { text: "创建新记录", primary: true, onClick: function () { callAction("treatment_new", "创建新记录"); } }
        ]
      });
      return;
    }
    window.IcuOverlay.showBanner({
      title: "治疗记录确认",
      text: "本次开机是否创建新的治疗记录？异常断电或误关机后继续治疗时，请选择继续当前记录。",
      buttons: [
        { text: "继续当前记录" },
        { text: "创建新记录", primary: true, onClick: function () { callAction("treatment_new", "创建新记录"); } }
      ]
    });
  }

  function render() {
    installPatientTempStore();
    ensureStartupTreatmentPrompt();
    var state = readFirstPhaseState();
    markPageContext();
    // 原生舱位和网页的乐观舱位对账：原生切过来了才认新舱，
    // 否则网页会拿另一个舱的数据来渲染。
    if (typeof window.__icuReconcileZone === "function") {
      window.__icuReconcileZone(state.zone || "");
    }
    // 病例列表跟着原生舱位走：原生报的是哪个舱，列表就显示哪个舱的档案。
    var nativeZone = String(state.zone || "").toLowerCase();
    if ((nativeZone === "left" || nativeZone === "right")
        && window.IcuPatientTemp && typeof window.IcuPatientTemp.setZone === "function") {
      window.IcuPatientTemp.setZone(nativeZone);
    }
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.importNativeRecords === "function") {
      window.IcuPatientTemp.importNativeRecords(state.cases || [], state.selectedCaseIndex || 0);
    }
    normalizeDeadText();
    hideReportTemplateNavigation();
    renderCommon(state);
    renderMonitor(state);
    renderCamera(state);
    renderHostModes(state);
    renderTreatments(state);
    renderTutorial();
    if (window.IcuPatientTemp && typeof window.IcuPatientTemp.render === "function") {
      window.IcuPatientTemp.render();
    }
    refreshMonitorPanel();
    syncCameraPreview();
  }

  // 合并为"最早截止 + 单 pending"：连续原生事件不再无限把紧急渲染往后推。
  // 已有不晚于新请求的排期就直接复用；仅当新请求更早时才取消并重排到较早时间。
  var timerDueAt = 0;
  function schedule(delay) {
    var d = (delay || 0);
    var due = Date.now() + d;
    if (timer && timerDueAt > 0 && timerDueAt <= due) {
      return;
    }
    if (timer) {
      window.clearTimeout(timer);
    }
    timer = window.setTimeout(function () {
      timer = 0;
      timerDueAt = 0;
      render();
    }, d);
    timerDueAt = due;
  }

  window.IcuCameraRuntime = {
    showPlayback: showCameraPlayback,
    showLive: showCameraLive
  };

  window.IcuMonitorPanel = {
    openSettings: function () {
      return openMonitorPanel("settings");
    },
    openHistory: function () {
      return openMonitorPanel("history");
    },
    isOpen: function () {
      return !!monitorPanelMode;
    }
  };

  document.addEventListener("DOMContentLoaded", function () {
    installActions();
    render();
    window.setInterval(render, 1500);
    window.setInterval(refreshVitals, VITALS_POLL_INTERVAL_MS);
    window.setInterval(refreshWaveData, WAVE_POLL_INTERVAL_MS);
    window.setInterval(refreshMonitorHistoryIfOpen, 3000);
  });

  window.addEventListener("icu-native-state", function () {
    // AM4100 每帧都会派发这个事件：先用轻量桥即时写数值，
    // 不等整页 render() 构建 firstPhaseState 大 JSON，也不等波形轮询。
    refreshVitals();
    schedule(0);
    refreshMonitorPanel();
  });

  window.addEventListener("pagehide", function () {
    nativeCameraPreview(0, 0, 0, 0, 1920, 1200, false);
  });
})();
