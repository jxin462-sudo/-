(function () {
  var overlayNode = null;
  var cardNode = null;
  var titleNode = null;
  var bodyNode = null;
  var closeButton = null;
  var currentOptions = null;
  var hostedNode = null;
  var hostedHome = null;
  var toastNode = null;
  var toastTimer = 0;
  var keyListenerInstalled = false;
  var bannerNode = null;
  var bannerTimer = 0;

  function fitLayer() {
    if (window.IcuFitLayer && typeof window.IcuFitLayer.ensure === 'function') {
      var node = window.IcuFitLayer.ensure();
      if (node) {
        return node;
      }
    }
    return document.body;
  }

  function ensureOverlay() {
    if (overlayNode && overlayNode.parentNode) {
      return overlayNode;
    }
    overlayNode = document.createElement('div');
    overlayNode.className = 'icu-overlay';
    overlayNode.setAttribute('data-icu-overlay', '1');
    cardNode = document.createElement('div');
    cardNode.className = 'icu-overlay-card';
    var head = document.createElement('div');
    head.className = 'icu-overlay-head';
    titleNode = document.createElement('div');
    titleNode.className = 'icu-overlay-title';
    closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'icu-overlay-close';
    closeButton.setAttribute('data-icu-overlay-close', '1');
    closeButton.setAttribute('aria-label', '\u5173\u95ed');
    closeButton.textContent = '\u00d7';
    head.appendChild(titleNode);
    head.appendChild(closeButton);
    bodyNode = document.createElement('div');
    bodyNode.className = 'icu-overlay-body';
    cardNode.appendChild(head);
    cardNode.appendChild(bodyNode);
    overlayNode.appendChild(cardNode);
    overlayNode.addEventListener('click', onOverlayClick, true);
    fitLayer().appendChild(overlayNode);
    return overlayNode;
  }

  function onOverlayClick(event) {
    var node = event.target;
    if (node && node.getAttribute && node.getAttribute('data-icu-overlay-close') === '1') {
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) {
        event.stopImmediatePropagation();
      }
      close();
      return;
    }
    if (node === overlayNode) {
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) {
        event.stopImmediatePropagation();
      }
      close();
    }
  }

  function onKeyDown(event) {
    if ((event.key === 'Escape' || event.keyCode === 27) && isOpen()) {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }

  function releaseHost() {
    if (!hostedNode) {
      return;
    }
    var node = hostedNode;
    hostedNode = null;
    var home = hostedHome;
    hostedHome = null;
    if (home && home.parent && home.parent.isConnected !== false) {
      if (home.next && home.next.parentNode === home.parent) {
        home.parent.insertBefore(node, home.next);
      } else {
        home.parent.appendChild(node);
      }
      return;
    }
    if (node.parentNode) {
      node.parentNode.removeChild(node);
    }
  }

  function open(config) {
    config = config || {};
    if (isOpen()) {
      close();
    }
    ensureOverlay();
    currentOptions = config;
    titleNode.textContent = config.title || '';
    cardNode.className = 'icu-overlay-card' + (config.className ? ' ' + config.className : '');
    bodyNode.innerHTML = '';
    if (typeof config.html === 'string') {
      bodyNode.innerHTML = config.html;
    }
    if (config.host) {
      hostedNode = config.host;
      hostedHome = hostedNode.parentNode ? { parent: hostedNode.parentNode, next: hostedNode.nextSibling } : null;
      bodyNode.appendChild(hostedNode);
    }
    document.documentElement.classList.add('icu-overlay-open');
    if (!keyListenerInstalled) {
      keyListenerInstalled = true;
      document.addEventListener('keydown', onKeyDown, true);
    }
    if (typeof config.onMount === 'function') {
      config.onMount(bodyNode, api);
    }
    return api;
  }

  function close() {
    if (!overlayNode || !overlayNode.parentNode) {
      return false;
    }
    var options = currentOptions;
    currentOptions = null;
    releaseHost();
    if (overlayNode.parentNode) {
      overlayNode.parentNode.removeChild(overlayNode);
    }
    document.documentElement.classList.remove('icu-overlay-open');
    if (options && typeof options.onClose === 'function') {
      try {
        options.onClose();
      } catch (error) {
      }
    }
    return true;
  }

  function isOpen() {
    return !!(overlayNode && overlayNode.parentNode);
  }

  function contains(node) {
    while (node && node !== document.body) {
      if (node.classList && (node.classList.contains('icu-overlay') || node.classList.contains('icu-toast') || node.classList.contains('icu-banner'))) {
        return true;
      }
      node = node.parentNode;
    }
    return false;
  }

  function showToast(message, duration) {
    if (!message) {
      return;
    }
    if (!toastNode || !toastNode.parentNode) {
      toastNode = document.createElement('div');
      toastNode.className = 'icu-toast';
      fitLayer().appendChild(toastNode);
    }
    toastNode.textContent = message;
    toastNode.classList.add('icu-toast-visible');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () {
      if (toastNode) {
        toastNode.classList.remove('icu-toast-visible');
      }
    }, duration || 2400);
  }

  function buildBannerButton(item) {
    item = item || {};
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'icu-banner-button' + (item.primary ? ' icu-banner-button-primary' : '');
    button.textContent = item.text || '';
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      hideBanner();
      if (typeof item.onClick === 'function') {
        item.onClick();
      }
    });
    return button;
  }

  function ensureBanner() {
    if (bannerNode && bannerNode.parentNode) {
      return bannerNode;
    }
    bannerNode = document.createElement('div');
    bannerNode.className = 'icu-banner';
    bannerNode.setAttribute('data-icu-banner', '1');
    bannerNode.setAttribute('role', 'status');
    fitLayer().appendChild(bannerNode);
    return bannerNode;
  }

  function showBanner(config) {
    config = config || {};
    var node = ensureBanner();
    node.innerHTML = '';
    var title = document.createElement('div');
    title.className = 'icu-banner-title';
    title.textContent = config.title || '';
    var text = document.createElement('div');
    text.className = 'icu-banner-text';
    text.textContent = config.text || '';
    var actions = document.createElement('div');
    actions.className = 'icu-banner-actions';
    var buttons = config.buttons || [];
    for (var i = 0; i < buttons.length; i += 1) {
      actions.appendChild(buildBannerButton(buttons[i]));
    }
    node.appendChild(title);
    node.appendChild(text);
    node.appendChild(actions);
    node.classList.add('icu-banner-visible');
    window.clearTimeout(bannerTimer);
    if (config.duration) {
      bannerTimer = window.setTimeout(function () {
        hideBanner();
      }, config.duration);
    }
    return node;
  }

  function hideBanner() {
    window.clearTimeout(bannerTimer);
    if (bannerNode && bannerNode.parentNode) {
      bannerNode.parentNode.removeChild(bannerNode);
    }
    bannerNode = null;
  }

  var api = {
    open: open,
    close: close,
    isOpen: isOpen,
    contains: contains,
    showBanner: showBanner,
    hideBanner: hideBanner,
    showToast: showToast,
    body: function () {
      return bodyNode;
    },
    title: function () {
      return titleNode;
    },
    setTitle: function (text) {
      titleNode.textContent = text || '';
    },
    setHtml: function (html) {
      bodyNode.innerHTML = html || '';
    }
  };

  window.IcuOverlay = api;
  window.IcuToast = { show: showToast };
})();

