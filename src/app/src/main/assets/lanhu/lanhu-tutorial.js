(function () {
  'use strict';

  // 使用教程页的六个入口：本机最近出现的素材，默认两个图片、两个视频、两个文档
  var SLOT_SELECTORS = [
    '.group_9',
    '.group_10',
    '.image-wrapper_1',
    '.image-wrapper_2',
    '.image-wrapper_3',
    '.image-wrapper_4'
  ];
  var UPDATE_POLL_MS = 20000;
  /** 素材指纹的会话内副本，避免同一份内容反复提示 */
  var FINGERPRINT_KEY = 'icuTutorialMediaFingerprint';
  var PERMISSION_DIALOG_ID = 'icu-tutorial-permission-dialog';
  /** 导入文档入口的 DOM id，页面里只保留一个。 */
  var IMPORT_ENTRY_ID = 'icu-tutorial-import-entry';
  var manifest = null;
  var gallery = [];
  var mediaState = null;
  var thumbnailCache = {};
  var thumbnailMediaKey = null;
  var updateTimer = 0;
  var dismissedVersion = 0;
  var notifiedFingerprint = '';
  /** 只抑制被动轮询重弹；用户主动操作产生的权限反馈不受此键限制。 */
  var dismissedPermissionKey = '';

  function isTutorialPage() {
    return (window.location.pathname || '').indexOf('lanhu_7jiaocheng') >= 0;
  }

  function nativeJson(name) {
    if (!window.IcuNative || typeof window.IcuNative[name] !== 'function') {
      return null;
    }
    try {
      return JSON.parse(window.IcuNative[name]() || '{}') || {};
    } catch (error) {
      return null;
    }
  }

  function nativeText(name) {
    if (!window.IcuNative || typeof window.IcuNative[name] !== 'function') {
      return '';
    }
    try {
      return window.IcuNative[name]() || '';
    } catch (error) {
      return '';
    }
  }

  function toast(message) {
    if (window.IcuToast && typeof window.IcuToast.show === 'function') {
      window.IcuToast.show(message);
    }
  }

  // 后台推送的教程清单：带素材的条目优先，其余位置仍然用本机素材
  function loadManifest() {
    var state = nativeJson('tutorialManifest');
    if (!state || !state.ok || !state.items || !state.items.length) {
      return null;
    }
    return state;
  }

  // 本机素材：图片、视频与文档按时间倒序合并后的前若干条
  function loadMedia() {
    var state = nativeJson('galleryItems');
    if (!state) {
      return null;
    }
    // 权限或素材集合变化后允许重试此前的无封面结果；状态刷新本身不清缓存。
    var nextMediaKey = (state.granted === true ? 'granted' : 'limited') + '|' + (state.fingerprint || '');
    if (thumbnailMediaKey !== null && thumbnailMediaKey !== nextMediaKey) {
      thumbnailCache = {};
    }
    thumbnailMediaKey = nextMediaKey;
    mediaState = state;
    return state;
  }

  /** 第 index 个入口展示的资料：推送的素材优先，否则用本机的第 index 条。 */
  function entryAt(index) {
    var pushed = pushedAt(index);
    if (pushed) {
      var pages = pushed.pages || [];
      // 只有带素材或带正文的条目才算推送资料，type=gallery 的入口仍然显示本机内容
      if (pushed.src || pushed.thumb || pages.length) {
        var pushedType = typeOfPushed(pushed);
        return {
          title: pushed.title || '教程资料',
          type: pushedType,
          // 图片和图文可直接用原文件；文档和视频不能把 PDF/视频地址塞进 img。
          thumb: pushed.thumb || (pushedType === 'image' || pushedType === 'imageText' ? pushed.src : '') || '',
          src: pushed.src || pushed.thumb || '',
          summary: pushed.summary || '',
          pages: pages,
          local: true
        };
      }
    }
    var media = gallery[index] || null;
    if (!media) {
      return null;
    }
    return {
      // 图片和视频不显示文件名；文档封面保留真实文件名，页面标题仍留空
      title: '',
      fileName: media.name || '',
      type: typeOfMedia(media),
      thumb: '',
      src: media.uri || '',
      summary: '',
      pages: [],
      local: false
    };
  }

  /** 清单条目归一化成页面能用的类型：image / video / document / imageText。 */
  function typeOfPushed(pushed) {
    var type = String(pushed.type || '').toLowerCase();
    if (type === 'video') {
      return 'video';
    }
    if (type === 'document' || type === 'pdf') {
      return 'document';
    }
    if (type === 'imagetext' || type === 'article' || type === 'text') {
      return 'imageText';
    }
    return 'image';
  }

  /** 本机素材的类型：图片 / 视频 / 文档。 */
  function typeOfMedia(media) {
    var type = String(media.type || '').toLowerCase();
    if (type === 'video') {
      return 'video';
    }
    if (type === 'document') {
      return 'document';
    }
    if (media.video) {
      return 'video';
    }
    return 'image';
  }

  /** 清单里对应第 index 个入口的条目：先按 slot_N 标号找，找不到再按顺序对应。 */
  function pushedAt(index) {
    if (!manifest || !manifest.items || !manifest.items.length) {
      return null;
    }
    var wanted = 'slot_' + (index + 1);
    for (var i = 0; i < manifest.items.length; i += 1) {
      var item = manifest.items[i];
      if (item && item.id === wanted) {
        return item;
      }
    }
    var fallback = manifest.items[index];
    return fallback && !fallback.id ? fallback : null;
  }

  function applySlot(index, item) {
    var slot = document.querySelector(SLOT_SELECTORS[index]);
    if (!slot) {
      return;
    }
    slot.classList.add('icu-tutorial-slot');
    slot.setAttribute('data-native-action', 'tutorial_item_' + index);
    slot.style.cursor = 'pointer';

    // 缩略图由脚本填进去，页面里不再写死任何图片
    var image = slot.querySelector('img');
    if (!image) {
      image = document.createElement('img');
      image.className = 'icu-tutorial-thumb';
      slot.insertBefore(image, slot.firstChild);
    }
    var type = item ? item.type : '';
    var isDocument = type === 'document';
    var isArticle = type === 'imageText';
    slot.classList.toggle('icu-tutorial-slot-doc', isDocument);
    slot.classList.toggle('icu-tutorial-slot-article', isArticle);
    if (isDocument) {
      slot.setAttribute('data-doc-label', docLabel(item.fileName || item.src || item.title));
      updateDocCover(slot, item);
    } else {
      slot.removeAttribute('data-doc-label');
      var oldCover = slot.querySelector('.icu-tutorial-doc-cover');
      if (oldCover) {
        oldCover.parentNode.removeChild(oldCover);
      }
    }

    // 文档也走同一条缩略图链：PDF 首页或后台指定封面优先，失败则保留类型封面。
    var source = item && (!isArticle || item.thumb) ? thumbnailOf(item) : '';
    if (source && slot._icuFailedCover === source) {
      source = '';
    }
    slot.classList.toggle('icu-tutorial-slot-has-cover', isDocument && !!source);
    image.onload = function () {
      slot._icuFailedCover = '';
    };
    image.onerror = function () {
      // 同一张坏图不随全局状态刷新反复加载；后台换了新封面地址仍会重试。
      slot._icuFailedCover = source;
      image.removeAttribute('src');
      slot.classList.remove('icu-tutorial-slot-has-cover');
    };
    if (source) {
      if (image.getAttribute('src') !== source) {
        image.setAttribute('src', source);
      }
    } else if (image.getAttribute('src')) {
      image.removeAttribute('src');
    }

    var label = slot.querySelector('.icu-tutorial-label');
    if (!label) {
      label = document.createElement('div');
      label.className = 'icu-tutorial-label';
      slot.appendChild(label);
    }
    label.innerHTML = '';
    // 图片和视频只显示资料本身；文档名称已在独立封面信息层显示
    if (item && item.type === 'video') {
      label.appendChild(makeBadge('视频'));
    } else if (isDocument) {
      label.appendChild(makeBadge('文档'));
    } else if (isArticle) {
      label.appendChild(makeBadge('图文'));
    }
  }

  /** 文档类型封面；有 PDF 首页或指定封面时，它退成底部的文件名信息层。 */
  function updateDocCover(slot, item) {
    var cover = slot.querySelector('.icu-tutorial-doc-cover');
    if (!cover) {
      cover = document.createElement('div');
      cover.className = 'icu-tutorial-doc-cover';
      var kind = document.createElement('div');
      kind.className = 'icu-tutorial-doc-kind';
      var name = document.createElement('div');
      name.className = 'icu-tutorial-doc-name';
      cover.appendChild(kind);
      cover.appendChild(name);
      slot.appendChild(cover);
    }
    cover.querySelector('.icu-tutorial-doc-kind').textContent =
      docLabel(item.fileName || item.src || item.title);
    cover.querySelector('.icu-tutorial-doc-name').textContent =
      item.fileName || item.title || '教程文档';
  }

  function makeBadge(text) {
    var node = document.createElement('span');
    node.className = 'icu-tutorial-badge';
    node.textContent = text;
    return node;
  }

  /** 文档占位图上的说明：按扩展名告诉用户这是哪一类文档。 */
  function docLabel(name) {
    var ext = String(name || '').split('.').pop().toLowerCase();
    var known = {
      pdf: 'PDF 文档',
      doc: 'Word 文档',
      docx: 'Word 文档',
      xls: 'Excel 表格',
      xlsx: 'Excel 表格',
      ppt: 'PPT 文档',
      pptx: 'PPT 文档',
      txt: '文本文档',
      log: '文本文档',
      md: '文本文档',
      json: '文本文档',
      xml: '文本文档',
      html: '网页文档'
    };
    return known[ext] || '文档';
  }

  /**
   * 素材缩略图：后台推送的资料自带缩略图，本机素材按需向原生要，
   * 结果缓存起来，翻页时不用反复解码。
   */
  function thumbnailOf(item) {
    if (!item || !item.src) {
      return '';
    }
    if (item.thumb) {
      return item.thumb;
    }
    if (Object.prototype.hasOwnProperty.call(thumbnailCache, item.src)) {
      return thumbnailCache[item.src];
    }
    if (!window.IcuNative || typeof window.IcuNative.galleryThumbnail !== 'function') {
      return '';
    }
    var data = '';
    try {
      data = window.IcuNative.galleryThumbnail(item.src) || '';
    } catch (error) {
      // 桥暂时异常时保留类型封面，下次状态变化仍可重试。
      return '';
    }
    // 空结果也缓存，避免不支持缩略图的DOCX等文档随全局状态刷新反复解码。
    thumbnailCache[item.src] = data;
    return data;
  }

  function render() {
    if (!isTutorialPage()) {
      return;
    }
    ensureImportEntry();
    var state = loadManifest();
    if (state) {
      manifest = state;
    }
    var media = loadMedia();
    gallery = (media && media.items) || [];
    applyPermissionState(media);
    for (var i = 0; i < SLOT_SELECTORS.length; i += 1) {
      applySlot(i, entryAt(i));
    }
    scheduleUpdateCheck();
    checkUpdate();
  }

  /**
   * 教程页底部的「导入图片 / 视频 / 文档」入口。
   *
   * <p>页面本身是蓝湖导出的静态结构，这里用脚本补一个按钮，不改动原有布局。
   * 导入的图片、视频、文档会和本机素材一起出现在六个入口里，点开即可查看。</p>
   */
  function ensureImportEntry() {
    if (document.getElementById(IMPORT_ENTRY_ID)) {
      return;
    }
    var host = document.querySelector('.group_7');
    if (!host) {
      return;
    }
    var bar = document.createElement('div');
    bar.className = 'icu-tutorial-actions';
    bar.id = IMPORT_ENTRY_ID;
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'icu-tutorial-import-button';
    button.textContent = '导入图片 / 视频 / 文档';
    button.setAttribute('data-native-action', 'tutorial_import');
    var tip = document.createElement('span');
    tip.className = 'icu-tutorial-import-tip';
    tip.textContent = '支持图片、视频和 PDF / Word(doc/docx) / Excel / txt，可一次选多个';
    bar.appendChild(button);
    bar.appendChild(tip);
    host.appendChild(bar);
  }

  function open(index) {
    var item = entryAt(index);
    if (!item) {
      toast('本机还没有可用的教程素材');
      return;
    }
    if (item.type === 'document') {
      openDocument(item);
      return;
    }
    if (item.type === 'imageText') {
      showArticle(item);
      return;
    }
    if (!item.src) {
      toast('本机还没有可用的教程素材');
      return;
    }
    openMedia(item);
  }

  /**
   * 打开图片或视频：素材带 content:// 或真实文件地址时交给原生查看器，
   * 只有内置 assets 里的素材才留在网页端显示。
   */
  function openMedia(item) {
    if (isAssetUri(item.src)) {
      showViewer(item);
      return;
    }
    if (window.IcuNative && typeof window.IcuNative.openTutorialMedia === 'function') {
      window.IcuNative.openTutorialMedia(item.src, item.title, item.type === 'video');
      return;
    }
    showViewer(item);
  }

  /** 内置 assets 的地址没有内容提供者，只能由网页直接加载。 */
  function isAssetUri(uri) {
    return String(uri || '').indexOf('file:///android_asset/') === 0;
  }

  function showViewer(item) {
    if (!window.IcuOverlay || typeof window.IcuOverlay.open !== 'function') {
      toast('查看器未就绪，请稍后再试');
      return;
    }
    var api = window.IcuOverlay.open({
      title: item.title || (item.type === 'video' ? '教程视频' : '教程图片'),
      className: 'icu-tutorial-viewer'
    });
    var body = api.body();
    body.innerHTML = '';
    var media;
    if (item.type === 'video') {
      media = document.createElement('video');
      media.className = 'icu-tutorial-video';
      media.setAttribute('controls', 'controls');
      media.setAttribute('playsinline', 'playsinline');
      media.setAttribute('preload', 'metadata');
      media.setAttribute('src', item.src);
    } else {
      media = document.createElement('img');
      media.className = 'icu-tutorial-image';
      media.setAttribute('alt', item.title);
      media.setAttribute('src', item.src);
    }
    body.appendChild(media);
    if (item.type === 'video') {
      var started = media.play();
      if (started && typeof started.catch === 'function') {
        started.catch(function () {});
      }
    }
  }

  /**
   * 图文资料：按页展示标题、正文与配图，可以翻页查看。
   * 没有正文时退回普通图片查看，避免出现空白页面。
   */
  function showArticle(item) {
    var pages = item.pages || [];
    if (!pages.length) {
      showViewer(item);
      return;
    }
    if (!window.IcuOverlay || typeof window.IcuOverlay.open !== 'function') {
      toast('查看器未就绪，请稍后再试');
      return;
    }
    var api = window.IcuOverlay.open({
      title: item.title || '教程资料',
      className: 'icu-tutorial-viewer icu-tutorial-article-viewer'
    });
    var body = api.body();
    body.innerHTML = '';

    var wrap = document.createElement('div');
    wrap.className = 'icu-tutorial-article';
    if (item.summary) {
      var summary = document.createElement('p');
      summary.className = 'icu-tutorial-article-summary';
      summary.textContent = item.summary;
      wrap.appendChild(summary);
    }
    var page = document.createElement('div');
    page.className = 'icu-tutorial-article-page';
    var nav = document.createElement('div');
    nav.className = 'icu-tutorial-article-nav';
    var prev = document.createElement('button');
    prev.type = 'button';
    prev.className = 'icu-tutorial-article-button';
    prev.textContent = '上一页';
    var next = document.createElement('button');
    next.type = 'button';
    next.className = 'icu-tutorial-article-button';
    next.textContent = '下一页';
    var counter = document.createElement('span');
    counter.className = 'icu-tutorial-article-counter';
    nav.appendChild(prev);
    nav.appendChild(counter);
    nav.appendChild(next);
    wrap.appendChild(page);
    wrap.appendChild(nav);
    body.appendChild(wrap);

    var index = 0;
    function renderPage() {
      var current = pages[index] || {};
      page.innerHTML = '';
      if (current.title) {
        var heading = document.createElement('h3');
        heading.className = 'icu-tutorial-article-title';
        heading.textContent = current.title;
        page.appendChild(heading);
      }
      if (current.text) {
        var paragraph = document.createElement('p');
        paragraph.className = 'icu-tutorial-article-text';
        paragraph.textContent = current.text;
        page.appendChild(paragraph);
      }
      if (current.image) {
        var picture = document.createElement('img');
        picture.className = 'icu-tutorial-article-image';
        picture.setAttribute('alt', current.title || '');
        picture.setAttribute('src', current.image);
        page.appendChild(picture);
      }
      counter.textContent = (index + 1) + ' / ' + pages.length;
      prev.disabled = index <= 0;
      next.disabled = index >= pages.length - 1;
    }
    prev.addEventListener('click', function () {
      if (index > 0) {
        index -= 1;
        renderPage();
      }
    });
    next.addEventListener('click', function () {
      if (index < pages.length - 1) {
        index += 1;
        renderPage();
      }
    });
    renderPage();
  }

  /**
   * 文档类教程：PDF 由原生只读预览（不能打印也不能下载），
   * 纯文本与 Word 文档在页面里直接读出正文。
   */
  function openDocument(item) {
    if (!window.IcuNative || typeof window.IcuNative.openTutorialDocument !== 'function') {
      toast('当前环境不支持打开文档');
      return;
    }
    var result = null;
    try {
      result = JSON.parse(window.IcuNative.openTutorialDocument(item.src, item.title || '教程文档') || '{}');
    } catch (error) {
      result = null;
    }
    if (!result || !result.ok) {
      toast((result && result.message) || '无法打开该文档');
      return;
    }
    if (result.kind === 'text') {
      showTextDocument(result);
    }
  }

  /** 文本与 Word 文档的正文展示。 */
  function showTextDocument(result) {
    if (!window.IcuOverlay || typeof window.IcuOverlay.open !== 'function') {
      toast('查看器未就绪，请稍后再试');
      return;
    }
    var api = window.IcuOverlay.open({
      title: result.title || '教程文档',
      className: 'icu-tutorial-viewer icu-tutorial-text-viewer'
    });
    var body = api.body();
    body.innerHTML = '';
    var content = document.createElement('pre');
    content.className = 'icu-tutorial-text';
    content.textContent = result.text || '';
    body.appendChild(content);
  }

  // ------------------------------------------------------------------ 权限提示

  /**
   * 只有相册权限没给时才弹全屏授权遮罩。
   *
   * <p>图片、视频本来就不依赖「所有文件访问权限」，缺这个权限只是看不到
   * 下载目录里的文档。以前为了文档也盖一层 z-index 99999 的半透明遮罩，
   * 整个教程页会一直灰着、六个入口和导入按钮全都点不动，
   * 用户看到的就是「替换文件一直是灰色的无法选中」。
   * 现在改成不遮挡：图片视频照常显示，文档靠导入按钮补上。</p>
   */
  function applyPermissionState(media) {
    if (!media) {
      return;
    }
    if (media.granted === false) {
      var key = 'media:' + (media.canAskAgain === false ? 'settings' : 'request');
      if (dismissedPermissionKey !== key) {
        showPermissionDialog(media, 'media', key);
      }
      return;
    }
    dismissedPermissionKey = '';
    closePermissionDialog();
  }

  function showPermissionDialog(state, mode, eventKey) {
    if (document.getElementById(PERMISSION_DIALOG_ID)) {
      return;
    }
    var permissionKey = eventKey || (mode + ':active');
    var isFiles = mode === 'files';
    // 系统对话框还能不能弹：false 表示用户勾过"不再询问"，只能去系统设置开
    var canAsk = isFiles || state.canAskAgain !== false;
    var dialogTitle = isFiles ? '需要文件访问权限' : '需要读取本机教程资料';
    var dialogBody = isFiles
      ? '使用教程里的文档（PDF、Word 等）需要读取本机文件，点击"去开启"后在系统设置里允许访问全部文件。'
      : '使用教程的封面、演示视频和文档来自本机相册与下载目录。'
        + '授权后即可在页面里查看这些资料；不授权则保留占位图，不影响其他功能。';
    var confirmText = isFiles ? '去开启' : (canAsk ? '确认授权' : '去设置');
    var mask = document.createElement('div');
    mask.className = 'icu-permission-mask';
    mask.id = PERMISSION_DIALOG_ID;
    mask.innerHTML = [
      '<div class="icu-permission-dialog" role="dialog" aria-modal="true">',
      '<h3 class="icu-permission-title">' + dialogTitle + '</h3>',
      '<p class="icu-permission-body">' + dialogBody + '</p>',
      canAsk ? '' : '<p class="icu-permission-tip">该权限此前已被拒绝，请在系统设置中手动开启。</p>',
      '<div class="icu-permission-actions">',
      '<button type="button" class="icu-permission-cancel">取消</button>',
      '<button type="button" class="icu-permission-confirm">' + confirmText + '</button>',
      '</div>',
      '</div>'
    ].join('');
    document.body.appendChild(mask);

    var cancel = mask.querySelector('.icu-permission-cancel');
    if (cancel) {
      cancel.addEventListener('click', function (event) {
        stopBtn(event);
        dismissedPermissionKey = permissionKey;
        closePermissionDialog();
      });
    }
    var confirm = mask.querySelector('.icu-permission-confirm');
    if (confirm) {
      confirm.addEventListener('click', function (event) {
        stopBtn(event);
        dismissedPermissionKey = permissionKey;
        if (isFiles) {
          openAllFilesAccessSettings();
        } else if (canAsk) {
          requestGalleryPermission();
        } else {
          openAppPermissionSettings();
        }
      });
    }
  }

  function closePermissionDialog() {
    var node = document.getElementById(PERMISSION_DIALOG_ID);
    if (node && node.parentNode) {
      node.parentNode.removeChild(node);
    }
  }

  function requestGalleryPermission() {
    if (window.IcuNative && typeof window.IcuNative.requestGalleryPermission === 'function') {
      window.IcuNative.requestGalleryPermission();
    }
  }

  function openAppPermissionSettings() {
    if (window.IcuNative && typeof window.IcuNative.openAppPermissionSettings === 'function') {
      window.IcuNative.openAppPermissionSettings();
    }
  }

  /** 去系统设置里开启"所有文件访问权限"，开启后教程页才能自动列出本机文档。 */
  function openAllFilesAccessSettings() {
    if (window.IcuNative && typeof window.IcuNative.openAllFilesAccessSettings === 'function') {
      window.IcuNative.openAllFilesAccessSettings();
    }
  }

  function stopBtn(event) {
    if (!event) {
      return;
    }
    if (event.stopPropagation) {
      event.stopPropagation();
    }
    if (event.preventDefault) {
      event.preventDefault();
    }
  }

  // ------------------------------------------------------------------ 更新提示

  /** 更新检查：本机素材指纹变化 + 后台推送的教程清单有新版本。 */
  function checkUpdate() {
    if (!isTutorialPage()) {
      return;
    }
    if (checkMediaFingerprint()) {
      return;
    }
    var status = nativeJson('tutorialUpdateStatus');
    if (!status || !status.available) {
      return;
    }
    var version = status.version || 0;
    if (version <= dismissedVersion) {
      return;
    }
    showUpdateBanner({
      title: '教程资料有新版本',
      text: (status.message || '发现新版本') + (status.title ? ('，' + status.title) : ''),
      actionText: '立即更新',
      onDismiss: function () {
        dismissedVersion = version;
      },
      onApply: applyUpdate
    });
  }

  /**
   * 本机素材指纹变化 = 教程内容更新了。
   * 第一次进入只记录基线，之后内容变了才提示。
   */
  function checkMediaFingerprint() {
    var state = mediaState || nativeJson('galleryItems');
    if (!state || state.granted !== true) {
      return false;
    }
    var current = state.fingerprint || '';
    if (!current || current === notifiedFingerprint) {
      return false;
    }
    var recorded = nativeText('tutorialFingerprint');
    if (!recorded) {
      recordFingerprint(current);
      notifiedFingerprint = current;
      return false;
    }
    if (recorded === current) {
      return false;
    }
    recordFingerprint(current);
    notifiedFingerprint = current;
    showUpdateBanner({
      title: '使用教程已更新',
      text: '本机教程资料有变化，点击刷新查看最新内容。',
      actionText: '立即刷新',
      onApply: function () {
        render();
        toast('已刷新教程资料');
      }
    });
    return true;
  }

  function recordFingerprint(fingerprint) {
    try {
      window.sessionStorage.setItem(FINGERPRINT_KEY, fingerprint);
    } catch (error) {
      // 会话存储不可用时忽略
    }
    if (window.IcuNative && typeof window.IcuNative.recordTutorialFingerprint === 'function') {
      try {
        window.IcuNative.recordTutorialFingerprint(fingerprint);
      } catch (error) {
        // 记录失败不影响查看
      }
    }
  }

  function showUpdateBanner(options) {
    options = options || {};
    if (!window.IcuOverlay || typeof window.IcuOverlay.showBanner !== 'function') {
      toast(options.text || options.title || '');
      return;
    }
    window.IcuOverlay.showBanner({
      title: options.title || '',
      text: options.text || '',
      buttons: [
        {
          text: '稍后再说',
          onClick: function () {
            if (typeof options.onDismiss === 'function') {
              options.onDismiss();
            }
          }
        },
        {
          text: options.actionText || '立即更新',
          primary: true,
          onClick: function () {
            if (typeof options.onApply === 'function') {
              options.onApply();
            }
          }
        }
      ]
    });
  }

  function applyUpdate() {
    var result = nativeJson('applyTutorialUpdate');
    if (result && result.ok) {
      dismissedVersion = 0;
      toast(result.message || '教程资料已更新');
      render();
      return;
    }
    toast((result && result.message) || '教程资料更新失败');
  }

  function scheduleUpdateCheck() {
    if (updateTimer) {
      // 已经在轮询中，不要被频繁的页面刷新反复重置
      return;
    }
    updateTimer = window.setTimeout(function () {
      updateTimer = 0;
      checkUpdate();
      scheduleUpdateCheck();
    }, UPDATE_POLL_MS);
  }

  window.IcuTutorial = {
    render: render,
    open: open,
    checkUpdate: checkUpdate
  };

  /** 权限结果回来以后关掉提示并重新读取素材。 */
  window.IcuOnTutorialPermission = function () {
    closePermissionDialog();
    render();
  };

  window.addEventListener('icu-gallery-updated', function () {
    render();
  });
  document.addEventListener('DOMContentLoaded', function () {
    render();
    window.setTimeout(checkUpdate, 400);
  });
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      checkUpdate();
    }
  });
})();

