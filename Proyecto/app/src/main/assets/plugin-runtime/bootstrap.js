// Rizx plugin runtime bootstrap (ADR 0014). Evaluated once per QuickJs instance. Defines the minimal
// browser-ish environment Nuclear plugins expect (fetch/console/timers/base64/URL) plus the `__rizx`
// host object: a provider registry, a per-plugin `api` factory, and an async value-capture used by the
// Kotlin bridge to read back the result of a plugin's async method (QuickJS `evaluate` returns the
// Promise object, not its resolved value, so we stash JSON on a global and read it after the job queue
// drains). Kotlin injects the host functions `__rizx_fetch` / `__rizx_log` / `__rizx_onRegister`.

(function () {
  const g = globalThis;

  // ---- console -> Kotlin log ------------------------------------------------
  const log = (level) => (...args) =>
    __rizx_log(level, args.map((a) => (typeof a === 'string' ? a : safeStringify(a))).join(' '));
  g.console = { log: log('info'), info: log('info'), warn: log('warn'), error: log('error'), debug: log('debug') };

  function safeStringify(v) { try { return JSON.stringify(v); } catch (e) { return String(v); } }

  // ---- base64 (UTF-8-safe enough for tokens/ids) ----------------------------
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = function (input) {
    let out = ''; let i = 0; const s = String(input);
    while (i < s.length) {
      const c1 = s.charCodeAt(i++), c2 = s.charCodeAt(i++), c3 = s.charCodeAt(i++);
      const e1 = c1 >> 2, e2 = ((c1 & 3) << 4) | (c2 >> 4);
      let e3 = ((c2 & 15) << 2) | (c3 >> 6), e4 = c3 & 63;
      if (isNaN(c2)) { e3 = e4 = 64; } else if (isNaN(c3)) { e4 = 64; }
      out += B64.charAt(e1) + B64.charAt(e2) + (e3 === 64 ? '=' : B64.charAt(e3)) + (e4 === 64 ? '=' : B64.charAt(e4));
    }
    return out;
  };
  g.atob = function (input) {
    const s = String(input).replace(/=+$/, ''); let out = '';
    for (let bc = 0, bs = 0, i = 0; i < s.length; i++) {
      const c = B64.indexOf(s.charAt(i)); if (c === -1) continue;
      bs = bc % 4 ? bs * 64 + c : c;
      if (bc++ % 4) out += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
    }
    return out;
  };

  // ---- timers (best-effort; advance while an evaluate is pending) ------------
  let timerId = 1; const timers = {};
  g.setTimeout = function (fn, ms) { const id = timerId++; timers[id] = fn; __rizx_sleep(ms | 0).then(() => { if (timers[id]) { delete timers[id]; try { fn(); } catch (e) { console.error('timer', e); } } }); return id; };
  g.clearTimeout = function (id) { delete timers[id]; };

  // ---- fetch -> Kotlin OkHttp ----------------------------------------------
  g.fetch = async function (url, init) {
    init = init || {};
    let body = init.body;
    if (body != null && typeof body !== 'string') { try { body = String(body); } catch (e) { body = null; } }
    const params = { url: String(url), method: (init.method || 'GET').toUpperCase(), headers: init.headers || {}, body: body };
    const raw = await __rizx_fetch(JSON.stringify(params));
    const res = JSON.parse(raw);
    return {
      ok: res.status >= 200 && res.status < 300,
      status: res.status,
      statusText: res.statusText || '',
      url: res.url || String(url),
      headers: { get: (name) => { const k = String(name).toLowerCase(); return (res.headers && res.headers[k]) || null; } },
      text: async () => res.body || '',
      json: async () => JSON.parse(res.body || 'null'),
    };
  };

  // ---- minimal URLSearchParams ---------------------------------------------
  g.URLSearchParams = function (init) {
    const pairs = [];
    if (init && typeof init === 'object') for (const k in init) pairs.push([k, String(init[k])]);
    this.append = (k, v) => pairs.push([k, String(v)]);
    this.set = (k, v) => { for (let i = pairs.length - 1; i >= 0; i--) if (pairs[i][0] === k) pairs.splice(i, 1); pairs.push([k, String(v)]); };
    this.get = (k) => { for (const p of pairs) if (p[0] === k) return p[1]; return null; };
    this.toString = () => pairs.map((p) => encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1])).join('&');
  };

  // ---- __rizx host ----------------------------------------------------------
  const rizx = {
    providers: {},   // uid -> descriptor object
    plugins: {},     // pluginId -> plugin object
    __last: null,    // last captured async result (JSON string) — read by Kotlin
    __err: null,     // last captured error message — read by Kotlin
  };

  rizx.makeApi = function (pluginId) {
    return {
      Providers: {
        register: function (descriptor) {
          const uid = pluginId + ':' + descriptor.id;
          rizx.providers[uid] = descriptor;
          const meta = {
            uid: uid, id: descriptor.id, kind: descriptor.kind, name: descriptor.name || descriptor.id,
            searchCapabilities: descriptor.searchCapabilities || [],
            methods: Object.keys(descriptor).filter((k) => typeof descriptor[k] === 'function'),
          };
          __rizx_onRegister(pluginId, JSON.stringify(meta));
          return uid;
        },
        unregister: function (uid) { delete rizx.providers[uid]; return true; },
      },
      Logger: g.console,
      // Some plugins take an injected fetch (e.g. `new Client(api.Http.fetch)`) instead of the global.
      Http: {
        fetch: function (url, init) { return g.fetch(url, init); },
        get: function (url, init) { return g.fetch(url, init); },
      },
      Settings: { register: function () {}, get: function () { return null; }, set: function () {} },
      Storage: { get: function () { return null; }, set: function () {}, remove: function () {} },
    };
  };

  // Invoke a registered provider's method and stash the resolved value (or error) on globals. Returns
  // the Promise so QuickJS drains it during `evaluate`; Kotlin then reads rizx.__last / rizx.__err.
  rizx.invokeAndCapture = function (uid, method, argsJson) {
    rizx.__last = null; rizx.__err = null;
    const p = rizx.providers[uid];
    if (!p) { rizx.__err = 'no provider ' + uid; return; }
    const fn = p[method];
    if (typeof fn !== 'function') { rizx.__err = 'no method ' + method + ' on ' + uid; return; }
    let args = [];
    try { args = argsJson ? JSON.parse(argsJson) : []; } catch (e) { rizx.__err = 'bad args: ' + e; return; }
    return Promise.resolve().then(() => fn.apply(p, args)).then(
      (v) => { rizx.__last = JSON.stringify(v === undefined ? null : v); },
      (e) => { rizx.__err = String((e && e.message) || e); }
    );
  };

  // Run a plugin lifecycle hook (onLoad/onEnable/onDisable/onUnload) and capture success/error.
  rizx.runHook = function (pluginId, hook) {
    rizx.__last = null; rizx.__err = null;
    const plugin = rizx.plugins[pluginId];
    if (!plugin) { rizx.__err = 'no plugin ' + pluginId; return; }
    const fn = plugin[hook];
    if (typeof fn !== 'function') { rizx.__last = 'null'; return; } // hook optional
    return Promise.resolve().then(() => fn.call(plugin, rizx.makeApi(pluginId))).then(
      () => { rizx.__last = 'null'; },
      (e) => { rizx.__err = String((e && e.message) || e); }
    );
  };

  g.__rizx = rizx;
})();
