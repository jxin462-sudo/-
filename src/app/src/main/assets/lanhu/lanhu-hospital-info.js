/**
 * 医院信息页（lanhu_81shezhi）的页内编辑逻辑。
 *
 * 设计约束：不改动既有 UI 结构与样式，只在原有的三个白底输入框
 * （.group_4/.group_5/.group_6）和 LOGO 占位框（.text-wrapper_2）内部
 * 填充真实可交互内容。
 */
(function () {
  var PAGE_NAME = "lanhu_81shezhi";
  // 页内编辑的通用实现由 lanhu-settings-field.js 提供，
  // 医院信息页与仪器状态页共用同一份，避免两份实现各自漂移。
  var settings = window.IcuSettingsField;
  if (!settings) {
    return;
  }
  var FIELD_MAP = [
    { selector: ".group_4", key: "name", placeholder: "未设置医院" },
    { selector: ".group_5", key: "address", placeholder: "未设置地址" },
    { selector: ".group_6", key: "phone", placeholder: "未设置电话" }
  ];
  var timer = 0;

  function isHospitalPage() {
    var path = window.location.pathname || "";
    return path.indexOf(PAGE_NAME) >= 0;
  }

  function clean(value) {
    return settings.clean(value);
  }

  function readNativeState() {
    return settings.readState();
  }

  function currentOrganization() {
    var state = readNativeState();
    if (!state) {
      return null;
    }
    return state.organization || {};
  }

  function schedule(delay) {
    window.clearTimeout(timer);
    timer = window.setTimeout(render, delay || 0);
  }

  /**
   * 把空白容器改造成可输入的文本框。
   * 幂等、输入法安全、与原生状态同步的具体规则统一放在
   * lanhu-settings-field.js，医院信息页与仪器状态页共用同一份实现。
   */
  function setField(selector, value, placeholder) {
    settings.setField(selector, value, placeholder);
  }

  function bindAction(selector, action, title) {
    settings.bindAction(selector, action, title);
  }

  function render() {
    if (!isHospitalPage()) {
      return;
    }
    var organization = currentOrganization();
    if (!organization) {
      return;
    }

    // LOGO 图片由公共 account-brand 模块渲染；这里只保留占位框布局。
    var logoSlot = document.querySelector(".text-wrapper_2");
    if (logoSlot) {
      logoSlot.style.setProperty("display", "flex", "important");
      logoSlot.style.setProperty("align-items", "center", "important");
      logoSlot.style.setProperty("justify-content", "center", "important");
      var plus = logoSlot.querySelector(".text_17");
      if (plus) {
        plus.style.setProperty("text-align", "center", "important");
        plus.style.setProperty("margin", "0", "important");
      }
    }

    for (var i = 0; i < FIELD_MAP.length; i += 1) {
      var field = FIELD_MAP[i];
      setField(field.selector, organization[field.key], field.placeholder);
    }

    // “保存”把页内三个输入框写回 native；“取消”沿用 bridge 既有的离开本页语义，
    // 重新进入时会从 native 重新回填，等价于丢弃未保存的改动。
    bindAction(".text-wrapper_3", "organization_save", "点击保存医院信息");
    bindAction(".text-wrapper_2", "organization_logo_pick", "点击上传医院 Logo");
  }

  document.addEventListener("DOMContentLoaded", function () {
    if (!isHospitalPage()) {
      return;
    }
    render();
    window.setInterval(render, 1500);
  });

  window.addEventListener("icu-native-state", function () {
    schedule(0);
  });
})();
