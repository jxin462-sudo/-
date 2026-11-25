/**
 * 顶栏右上角的设备时间。
 *
 * 顶栏原先放的是"控制舱 / 左舱 / 右舱"切换控件，"控制舱"字样已按需求去掉，
 * 这里补上这台设备的当前时间，时钟排在舱位按钮左边、跟着账号区一起靠右，
 * 格式 "YYYY-MM-DD HH:mm:ss"，每秒刷新一次，取的是 WebView 所在设备的本地时间。
 *
 * 只有标准顶栏（动物ICU重症监护系统那一条）才插时钟：登录页没有顶栏，
 * 报告页虽然也有 .label_2，但那是报告自己的排版，不能混进来。
 */
(function () {
  "use strict";

  var CLOCK_ID = "icu-device-clock";
  var TITLE_TEXT = "动物ICU重症监护系统";
  var TICK_INTERVAL = 1000;
  var timer = 0;

  function pad2(value) {
    return value < 10 ? "0" + value : "" + value;
  }

  function formatNow() {
    var now = new Date();
    return now.getFullYear() + "-" + pad2(now.getMonth() + 1) + "-" + pad2(now.getDate()) +
      " " + pad2(now.getHours()) + ":" + pad2(now.getMinutes()) + ":" + pad2(now.getSeconds());
  }

  /** 标准顶栏：含应用标题、账号头像在最右侧的行式容器。 */
  function findTopBar() {
    var pageRoot = document.querySelector(".page") || document.body;
    if (!pageRoot || !pageRoot.querySelector) {
      return null;
    }
    var avatar = pageRoot.querySelector(".label_2");
    if (!avatar || !avatar.parentNode) {
      return null;
    }
    var nav = avatar.parentNode;
    if ((nav.textContent || "").indexOf(TITLE_TEXT) < 0) {
      return null;
    }
    return nav;
  }

  /** 找到（或补建）时钟节点，并保证它排在舱位按钮左边、紧挨在账号头像前面。 */
  function ensureClock() {
    var nav = findTopBar();
    if (!nav) {
      return null;
    }
    var clock = document.getElementById(CLOCK_ID);
    if (!clock) {
      clock = document.createElement("div");
      clock.id = CLOCK_ID;
      clock.className = "icu-device-clock";
      nav.appendChild(clock);
    }
    var avatar = nav.querySelector(".label_2");
    if (avatar && avatar.parentNode === nav) {
      // 舱位切换按钮在时钟右边，所以时钟插在按钮前面；没有按钮时直接贴着头像。
      var bar = document.getElementById("icu-zone-switch");
      var anchor = bar && bar.parentNode === nav ? bar : avatar;
      if (clock.parentNode !== nav || clock.nextSibling !== anchor) {
        nav.insertBefore(clock, anchor);
      }
    }
    // 头像原本靠固定外边距顶到最右侧，现在改由时钟的自动外边距把账号区推到右边，
    // 时钟才能贴着账号区显示；这里把那段固定外边距让出来。
    if (avatar && avatar.parentNode === nav) {
      avatar.style.marginLeft = "0px";
    }
    // 时钟已经接管了顶栏右侧的靠右排版，舱位按钮不用再自己顶到最右。
    var zoneBar = document.getElementById("icu-zone-switch");
    if (zoneBar) {
      zoneBar.classList.remove("icu-no-clock");
    }
    return clock;
  }

  function tick() {
    var clock = ensureClock();
    if (!clock) {
      return;
    }
    var text = formatNow();
    if (clock.textContent !== text) {
      clock.textContent = text;
    }
  }

  function start() {
    var clock = ensureClock();
    if (!clock) {
      return;
    }
    tick();
    if (!timer) {
      timer = window.setInterval(tick, TICK_INTERVAL);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
  // 顶栏结构各页类名不一致，DOM 就绪后可能还会被桥接层调整，这里再兜底两次。
  window.setTimeout(start, 120);
  window.setTimeout(start, 600);
})();
