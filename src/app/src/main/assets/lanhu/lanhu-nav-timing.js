(function () {
  'use strict';

  // 页签为整页加载：点击时由 lanhu-bridge.js 把 navId、起始墙钟和目标路由写进 URL，
  // 目标页在此校验规范化路由与该页专属首屏节点，再用 requestAnimationFrame 记录首次正确渲染。
  // 结果只保留最近一次，日志每次导航最多两条，不累积历史、不含任何隐私或病例信息。
  var MAX_WAIT_MS = 3000;
  var PREFIX = '[导航计时] ';
  var FIRST_FRAME_SELECTORS = {
    'lanhu_1icuzhuangtaikaobei': '.page [data-icu-live="host.temp"]',
    'lanhu_2zhujikongzhi': '.page [data-icu-live="host.controls.humidity"]',
    'lanhu_3shishijianhu': '.page .group_26',
    'lanhu_4jiankong': '.page .section_3',
    'lanhu_6zhiliaojilu': '.page .section_20',
    'lanhu_7jiaocheng': '.page .section_5'
  };

  var finished = false;

  function nativeLog(message) {
    try {
      if (window.IcuNative && typeof window.IcuNative.log === 'function') {
        window.IcuNative.log(message);
      }
    } catch (error) {
      // 原生日志不可用时不影响页面
    }
    try {
      if (window.console && console.log) {
        console.log(message);
      }
    } catch (error) {
      // 控制台不可用时忽略
    }
  }

  function queryValue(name) {
    var search = String(window.location.search || '').replace(/^\?/, '');
    var parts = search.split('&');
    for (var i = 0; i < parts.length; i += 1) {
      var pair = parts[i].split('=');
      if (pair[0] === name) {
        var raw = pair.slice(1).join('=');
        try {
          return decodeURIComponent(raw);
        } catch (error) {
          return raw;
        }
      }
    }
    return '';
  }

  function currentPageKey() {
    var match = String(window.location.pathname || '').match(/src\/views\/([^/]+)\//);
    return match ? match[1] : '';
  }

  function firstFrameSelector(key) {
    return FIRST_FRAME_SELECTORS[key] || '.page';
  }

  function raf(callback) {
    if (window.requestAnimationFrame) {
      window.requestAnimationFrame(callback);
    } else {
      window.setTimeout(callback, 16);
    }
  }

  function finish(result) {
    if (finished) {
      return;
    }
    finished = true;
    window.IcuNavTiming = result;
    nativeLog(PREFIX + '结束 id=' + result.navId + ' to=' + result.target
      + ' route=' + result.routeKey + ' selector=' + result.selector
      + ' ok=' + result.ok + ' reason=' + result.reason
      + ' startAt=' + result.startedAt + ' arrivedAt=' + result.arrivedAt
      + ' firstFrameAt=' + result.firstFrameAt + ' elapsedMs=' + result.elapsedMs);
  }

  function start() {
    var navId = queryValue('icuNavId');
    var target = queryValue('icuNavTo');
    var startedAt = parseInt(queryValue('icuNavAt'), 10);
    var arrivedAt = Date.now();
    var routeKey = currentPageKey();
    var selector = firstFrameSelector(target);
    var result = {
      navId: navId,
      target: target,
      routeKey: routeKey,
      selector: selector,
      startedAt: isFinite(startedAt) ? startedAt : 0,
      arrivedAt: arrivedAt,
      firstFrameAt: 0,
      elapsedMs: 0,
      ok: false,
      reason: ''
    };
    // 普通页面加载不带计时参数，不产生结果也不写日志。
    if (!navId || !target || !result.startedAt) {
      return;
    }
    if (routeKey !== target) {
      result.reason = 'route_mismatch';
      result.elapsedMs = arrivedAt - result.startedAt;
      finish(result);
      return;
    }
    if (arrivedAt - result.startedAt > MAX_WAIT_MS) {
      result.reason = 'timeout';
      result.elapsedMs = arrivedAt - result.startedAt;
      finish(result);
      return;
    }

    function checkFirstFrame() {
      if (finished) {
        return;
      }
      if (!document.querySelector(selector)) {
        if (Date.now() - result.startedAt >= MAX_WAIT_MS) {
          result.reason = 'first_frame_missing';
          result.elapsedMs = Date.now() - result.startedAt;
          finish(result);
          return;
        }
        raf(checkFirstFrame);
        return;
      }
      // 再等一帧，确认节点已经进入首帧渲染，而不是刚被插入 DOM。
      raf(function () {
        if (finished) {
          return;
        }
        result.firstFrameAt = Date.now();
        result.elapsedMs = result.firstFrameAt - result.startedAt;
        if (result.elapsedMs > MAX_WAIT_MS) {
          result.reason = 'timeout';
        } else {
          result.reason = 'ok';
          result.ok = true;
        }
        finish(result);
      });
    }

    raf(checkFirstFrame);
    window.setTimeout(function () {
      if (finished) {
        return;
      }
      result.reason = 'timeout';
      result.elapsedMs = Date.now() - result.startedAt;
      finish(result);
    }, MAX_WAIT_MS);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
