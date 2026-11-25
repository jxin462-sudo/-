(function () {
  var DESIGN_WIDTH = 1920;
  var DESIGN_HEIGHT = 1200;
  var rafId = 0;
  var observersReady = false;
  var stableViewport = { width: 0, height: 0 };

  function normalizeViewportMeta() {
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      document.head.appendChild(meta);
    }
    meta.setAttribute('content', 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover');
  }

  function editableFocused() {
    var node = document.activeElement;
    if (!node) {
      return false;
    }
    var tag = String(node.tagName || '').toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || node.isContentEditable;
  }

  function rawViewportSize() {
    var visualViewport = window.visualViewport;
    var width = Math.max(1, Math.floor(
      window.innerWidth ||
      document.documentElement.clientWidth ||
      (visualViewport && visualViewport.width) ||
      DESIGN_WIDTH
    ));
    var height = Math.max(1, Math.floor(
      window.innerHeight ||
      document.documentElement.clientHeight ||
      (visualViewport && visualViewport.height) ||
      DESIGN_HEIGHT
    ));
    return { width: width, height: height };
  }

  function getViewportSize() {
    var viewport = rawViewportSize();
    var focused = editableFocused();
    var sameWidth = stableViewport.width > 0 && Math.abs(viewport.width - stableViewport.width) < 80;
    var keyboardShrink = focused && sameWidth && stableViewport.height > 0 && viewport.height < stableViewport.height * 0.88;

    if (!keyboardShrink) {
      stableViewport.width = viewport.width;
      stableViewport.height = viewport.height;
      return viewport;
    }

    return {
      width: stableViewport.width || viewport.width,
      height: stableViewport.height || viewport.height
    };
  }

  function getPageSize(page) {
    var width = Math.max(DESIGN_WIDTH, page.scrollWidth || 0, page.offsetWidth || 0);
    var height = Math.max(DESIGN_HEIGHT, page.scrollHeight || 0, page.offsetHeight || 0);
    return { width: width, height: height };
  }

  var layerNode = null;

  function ensureFitLayer() {
    if (layerNode && layerNode.parentNode) {
      return layerNode;
    }
    if (!document.body) {
      return null;
    }
    layerNode = document.createElement('div');
    layerNode.className = 'icu-layer';
    document.body.appendChild(layerNode);
    return layerNode;
  }

  function fitLanhuPage() {
    rafId = 0;
    var page = document.querySelector('.page');
    if (!page) {
      return;
    }

    var viewport = getViewportSize();
    var pageSize = getPageSize(page);
    var scale = Math.min(viewport.width / pageSize.width, viewport.height / pageSize.height);
    scale = Math.min(1, Math.max(0.1, scale));

    var left = Math.max(0, (viewport.width - pageSize.width * scale) / 2);
    var top = Math.max(0, (viewport.height - pageSize.height * scale) / 2);
    var rootStyle = document.documentElement.style;
    rootStyle.setProperty('--lanhu-scale', scale.toFixed(6));
    rootStyle.setProperty('--lanhu-left', left.toFixed(2) + 'px');
    rootStyle.setProperty('--lanhu-top', top.toFixed(2) + 'px');
    rootStyle.setProperty('--lanhu-page-width', pageSize.width + 'px');
    rootStyle.setProperty('--lanhu-page-height', pageSize.height + 'px');
    document.documentElement.classList.add('lanhu-fit-ready');

    dispatchFitEvent(scale, left, top, pageSize);
  }

  function dispatchFitEvent(scale, left, top, pageSize) {
    var detail = {
      scale: scale,
      left: left,
      top: top,
      width: pageSize.width,
      height: pageSize.height
    };
    var event;
    if (typeof window.CustomEvent === 'function') {
      event = new CustomEvent('lanhu:fit', { detail: detail });
    } else {
      event = document.createEvent('CustomEvent');
      event.initCustomEvent('lanhu:fit', false, false, detail);
    }
    window.dispatchEvent(event);
  }

  function scheduleFit() {
    if (rafId) {
      return;
    }
    rafId = window.requestAnimationFrame(fitLanhuPage);
  }

  function installContentObservers() {
    if (observersReady || !document.body) {
      return;
    }
    observersReady = true;
    var page = document.querySelector('.page');
    if (window.ResizeObserver && page) {
      new ResizeObserver(scheduleFit).observe(page);
    }
    if (window.MutationObserver) {
      new MutationObserver(scheduleFit).observe(document.body, {
        childList: true,
        subtree: true,
        characterData: true
      });
    }
  }

  normalizeViewportMeta();
  document.addEventListener('DOMContentLoaded', function () {
    ensureFitLayer();
    installContentObservers();
    scheduleFit();
  });
  window.addEventListener('load', scheduleFit);
  window.addEventListener('resize', scheduleFit);
  window.addEventListener('orientationchange', scheduleFit);
  window.addEventListener('pageshow', scheduleFit);
  document.addEventListener('focusin', scheduleFit, true);
  document.addEventListener('focusout', function () {
    window.setTimeout(scheduleFit, 160);
  }, true);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', scheduleFit);
  }
  window.IcuFitLayer = { ensure: ensureFitLayer };
  scheduleFit();
})();
