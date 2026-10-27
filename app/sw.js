/* 工资监视器 Service Worker：导航网络优先 + 静态资源缓存优先，只缓存成功响应 */
const CACHE = 'salary-monitor-v15';
const SHARE = '?sharecode=IXGIAAOCCC';   /* 站点访问码：不带会被网关拒绝（403） */
const SHELL = ['./manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    /* 先缓存主页面（带访问码，存档归一化为 index.html） */
    try {
      const res = await fetch('./index.html' + SHARE, { cache: 'reload' });
      if (res && res.ok) await c.put('./index.html', res.clone());
    } catch (err) { /* 忽略 */ }
    /* 再逐个预缓存其它资源：只存真正成功（2xx）的响应；任一失败不影响安装 */
    await Promise.allSettled(SHELL.map(async (url) => {
      try {
        const res = await fetch(url, { cache: 'reload' });
        if (res && res.ok) await c.put(url, res.clone());
      } catch (err) { /* 忽略单个失败 */ }
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;  /* 跨域请求不接管 */

  /* 页面导航：网络优先（永远先拿最新版本），断网才回退缓存 */
  if (req.mode === 'navigate' || req.destination === 'document') {
    e.respondWith((async () => {
      try {
        /* no-store：绕过 HTTP 缓存，GitHub 上改完提交后即可见最新版 */
        const res = await fetch(req, { cache: 'no-store' });
        if (res && res.ok) {
          const c = await caches.open(CACHE);
          c.put('./index.html', res.clone());  /* 归一化存档，断网时任何带参链接都能命中 */
          return res;
        }
        /* 网关拒绝（403 等）时优先用本地缓存，避免白屏 */
        const c = await caches.open(CACHE);
        const fallback = (await c.match('./index.html', { ignoreSearch: true })) ||
                         (await c.match(req, { ignoreSearch: true }));
        return fallback || res;
      } catch (err) {
        const c = await caches.open(CACHE);
        const cached = (await c.match('./index.html', { ignoreSearch: true })) ||
                       (await c.match(req, { ignoreSearch: true }));
        if (cached) return cached;
        return new Response(
          '<!DOCTYPE html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
          '<body style="margin:0;background:#050814;color:#5df0ff;font-family:-apple-system,sans-serif;' +
          'display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px">' +
          '<div><div style="font-size:17px;font-weight:700">当前处于离线状态</div>' +
          '<div style="margin-top:10px;font-size:13px;color:#8fb8cc">请联网后重新打开 App</div></div></body>',
          { headers: { 'Content-Type': 'text/html; charset=utf-8' } }
        );
      }
    })());
    return;
  }

  /* 静态资源：缓存优先 + 后台更新（只缓存成功响应，不会缓存错误页） */
  e.respondWith((async () => {
    const c = await caches.open(CACHE);
    const cached = await c.match(req);
    const network = fetch(req).then((res) => {
      if (res && res.ok) c.put(req, res.clone());
      return res;
    }).catch(() => null);
    return cached || (await network) || new Response('', { status: 504 });
  })());
});
