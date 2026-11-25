/**
 * 仪器状态页（lanhu_82shezhi）的页内编辑逻辑。
 *
 * 设计约束：不改动既有 UI 结构与样式，只在原有的五个白底输入框
 * （.text-wrapper_2/.box_3/.box_5/.section_2/.section_3）内部
 * 填充真实可交互内容，页面观感与医院信息页保持一致。
 *
 * 数据流：原生 buildFirstPhaseStateJson 下发 state.deviceProfile，
 * 页面回填五个输入框；点击“保存”时把输入框当前值交回原生落库，
 * 原生落库后再通过 state 下发完成回填。
 */
(function () {
  var PAGE_NAME = "lanhu_82shezhi";
  // 输入框的幂等/输入法安全规则与医院信息页共用同一份实现
  var settings = window.IcuSettingsField;
  if (!settings) {
    return;
  }
  var FIELD_MAP = [
    { selector: ".text-wrapper_2", key: "productModel", placeholder: "未登记" },
    { selector: ".box_3", key: "machineType", placeholder: "未登记" },
    { selector: ".box_5", key: "serialNo", placeholder: "未登记" },
    { selector: ".section_2", key: "manufactureDate", placeholder: "未登记" },
    { selector: ".section_3", key: "softwareVersion", placeholder: "未登记" }
  ];
  var timer = 0;

  function isDevicePage() {
    var path = window.location.pathname || "";
    return path.indexOf(PAGE_NAME) >= 0;
  }

  function readNativeState() {
    return settings.readState();
  }

  function currentProfile() {
    var state = readNativeState();
    if (!state) {
      return null;
    }
    return state.deviceProfile || {};
  }

  function schedule(delay) {
    window.clearTimeout(timer);
    timer = window.setTimeout(render, delay || 0);
  }

  function render() {
    if (!isDevicePage()) {
      return;
    }
    var profile = currentProfile();
    if (!profile) {
      // 原生状态还没准备好时保留页面原始文案，避免闪一下空输入框
      return;
    }
    for (var i = 0; i < FIELD_MAP.length; i += 1) {
      var field = FIELD_MAP[i];
      settings.setField(field.selector, profile[field.key], field.placeholder);
    }
    // “保存”把页内五个输入框写回 native；“取消”沿用 bridge 既有的离开本页语义，
    // 重新进入时会从 native 重新回填，等价于丢弃未保存的改动。
    settings.bindAction(".text-wrapper_3", "device_profile_save", "点击保存仪器信息");
  }

  document.addEventListener("DOMContentLoaded", function () {
    if (!isDevicePage()) {
      return;
    }
    render();
    window.setInterval(render, 1500);
  });

  window.addEventListener("icu-native-state", function () {
    schedule(0);
  });
})();
