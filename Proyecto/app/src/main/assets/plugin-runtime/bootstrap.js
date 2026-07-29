// Rizx plugin runtime bootstrap (ADR 0014/0019). Evaluated once per QuickJs instance. Defines the
// browser-ish environment Nuclear plugins expect (fetch/console/timers/base64/URL/TextEncoder/crypto)
// plus the `__rizx` host object: a provider registry, a per-plugin `api` factory, and an async
// value-capture used by the Kotlin bridge to read back the result of a plugin's async method (QuickJS
// `evaluate` returns the Promise object, not its resolved value, so we stash JSON on a global and read
// it after the job queue drains). Kotlin injects the host functions `__rizx_fetch` / `__rizx_log` /
// `__rizx_onRegister` / `__rizx_kv_*` / `__rizx_open_external` / `__rizx_random_hex` /
// `__rizx_hmac_hex` / `__rizx_digest_hex`.

(function () {
  const g = globalThis;

  // ---- console -> Kotlin log ------------------------------------------------
  const log = (level) => (...args) =>
    __rizx_log(level, args.map((a) => (typeof a === 'string' ? a : safeStringify(a))).join(' '));
  g.console = { log: log('info'), info: log('info'), warn: log('warn'), error: log('error'), debug: log('debug') };

  function safeStringify(v) { try { return JSON.stringify(v); } catch (e) { return String(v); } }

  // ---- bytes helpers (shared by base64 / TextEncoder / crypto) --------------
  function utf8Encode(str) {
    const s = String(str); const out = [];
    for (let i = 0; i < s.length; i++) {
      let c = s.codePointAt(i);
      if (c > 0xffff) i++; // surrogate pair consumed
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return new Uint8Array(out);
  }
  function utf8Decode(bytes) {
    const b = toBytes(bytes); let out = ''; let i = 0;
    while (i < b.length) {
      const c = b[i++];
      if (c < 0x80) out += String.fromCharCode(c);
      else if (c < 0xe0) out += String.fromCharCode(((c & 31) << 6) | (b[i++] & 63));
      else if (c < 0xf0) out += String.fromCharCode(((c & 15) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63));
      else {
        const cp = ((c & 7) << 18) | ((b[i++] & 63) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
        out += String.fromCodePoint(cp);
      }
    }
    return out;
  }
  function toBytes(v) {
    if (v instanceof Uint8Array) return v;
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    if (ArrayBuffer.isView(v)) return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
    if (typeof v === 'string') return utf8Encode(v);
    throw new TypeError('expected bytes');
  }
  function bytesToHex(bytes) {
    const b = toBytes(bytes); let out = '';
    for (let i = 0; i < b.length; i++) out += (b[i] < 16 ? '0' : '') + b[i].toString(16);
    return out;
  }
  function hexToBytes(hex) {
    const out = new Uint8Array(hex.length >> 1);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
  }

  g.TextEncoder = function () { this.encoding = 'utf-8'; this.encode = (s) => utf8Encode(s); };
  g.TextDecoder = function () { this.encoding = 'utf-8'; this.decode = (b) => utf8Decode(b); };

  // ---- base64 (spec semantics: latin1 strings; use TextEncoder for UTF-8) ---
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = function (input) {
    let out = ''; let i = 0; const s = String(input);
    while (i < s.length) {
      const c1 = s.charCodeAt(i++), c2 = s.charCodeAt(i++), c3 = s.charCodeAt(i++);
      if (c1 > 255 || c2 > 255 || c3 > 255) throw new Error('btoa: character out of latin1 range');
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
  g.setInterval = function (fn, ms) {
    const id = timerId++; timers[id] = fn;
    const tick = () => __rizx_sleep(ms | 0).then(() => {
      if (!timers[id]) return;
      try { fn(); } catch (e) { console.error('interval', e); }
      tick();
    });
    tick();
    return id;
  };
  g.clearInterval = g.clearTimeout;

  // ---- URLSearchParams (parsing ctor + full surface) ------------------------
  function qsDecode(s) { try { return decodeURIComponent(String(s).replace(/\+/g, ' ')); } catch (e) { return String(s); } }
  g.URLSearchParams = class URLSearchParams {
    constructor(init) {
      this._pairs = [];
      if (init == null) return;
      if (typeof init === 'string') {
        const raw = init.charAt(0) === '?' ? init.slice(1) : init;
        for (const part of raw.split('&')) {
          if (!part) continue;
          const eq = part.indexOf('=');
          if (eq < 0) this._pairs.push([qsDecode(part), '']);
          else this._pairs.push([qsDecode(part.slice(0, eq)), qsDecode(part.slice(eq + 1))]);
        }
      } else if (Array.isArray(init)) {
        for (const p of init) this._pairs.push([String(p[0]), String(p[1])]);
      } else if (init instanceof URLSearchParams) {
        for (const p of init._pairs) this._pairs.push([p[0], p[1]]);
      } else if (typeof init === 'object') {
        for (const k in init) this._pairs.push([k, String(init[k])]);
      }
    }
    append(k, v) { this._pairs.push([String(k), String(v)]); }
    set(k, v) {
      k = String(k);
      for (let i = this._pairs.length - 1; i >= 0; i--) if (this._pairs[i][0] === k) this._pairs.splice(i, 1);
      this._pairs.push([k, String(v)]);
    }
    get(k) { k = String(k); for (const p of this._pairs) if (p[0] === k) return p[1]; return null; }
    getAll(k) { k = String(k); return this._pairs.filter((p) => p[0] === k).map((p) => p[1]); }
    has(k) { return this.get(k) !== null; }
    delete(k) { k = String(k); for (let i = this._pairs.length - 1; i >= 0; i--) if (this._pairs[i][0] === k) this._pairs.splice(i, 1); }
    forEach(fn, thisArg) { for (const p of this._pairs.slice()) fn.call(thisArg, p[1], p[0], this); }
    keys() { return this._pairs.map((p) => p[0])[Symbol.iterator](); }
    values() { return this._pairs.map((p) => p[1])[Symbol.iterator](); }
    entries() { return this._pairs.map((p) => [p[0], p[1]])[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
    toString() { return this._pairs.map((p) => encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1])).join('&'); }
  };

  // ---- URL (lightweight; absolute http(s) + relative-with-base) -------------
  const URL_RE = /^(https?:)\/\/([^/?#:]+)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/;
  g.URL = class URL {
    constructor(url, base) {
      let href = String(url);
      if (!/^https?:/.test(href) && base != null) {
        const b = base instanceof URL ? base : new URL(String(base));
        if (href.charAt(0) === '/') href = b.origin + href;
        else href = b.origin + b.pathname.replace(/[^/]*$/, '') + href;
      }
      const m = URL_RE.exec(href);
      if (!m) throw new TypeError('invalid URL: ' + url);
      this.protocol = m[1];
      this.hostname = m[2];
      this.port = m[3] || '';
      this.pathname = m[4] || '/';
      this.search = m[5] || '';
      this.hash = m[6] || '';
      this.searchParams = new g.URLSearchParams(this.search);
    }
    get host() { return this.hostname + (this.port ? ':' + this.port : ''); }
    get origin() { return this.protocol + '//' + this.host; }
    get href() {
      const qs = this.searchParams.toString();
      return this.origin + this.pathname + (qs ? '?' + qs : '') + this.hash;
    }
    toString() { return this.href; }
  };

  // ---- Headers-lite ---------------------------------------------------------
  g.Headers = class Headers {
    constructor(init) {
      this._map = {};
      if (init instanceof Headers) { for (const k in init._map) this._map[k] = init._map[k]; }
      else if (Array.isArray(init)) { for (const p of init) this.set(p[0], p[1]); }
      else if (init && typeof init === 'object') { for (const k in init) this.set(k, init[k]); }
    }
    get(k) { const v = this._map[String(k).toLowerCase()]; return v === undefined ? null : v; }
    set(k, v) { this._map[String(k).toLowerCase()] = String(v); }
    has(k) { return String(k).toLowerCase() in this._map; }
    append(k, v) { const key = String(k).toLowerCase(); this._map[key] = this.has(key) ? this._map[key] + ', ' + String(v) : String(v); }
    delete(k) { delete this._map[String(k).toLowerCase()]; }
    forEach(fn, thisArg) { for (const k in this._map) fn.call(thisArg, this._map[k], k, this); }
    entries() { const out = []; for (const k in this._map) out.push([k, this._map[k]]); return out[Symbol.iterator](); }
    keys() { const out = []; for (const k in this._map) out.push(k); return out[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
  };

  // ---- AbortController (inert: fetch has its own host-side call timeout) ----
  g.AbortController = class AbortController {
    constructor() {
      this.signal = { aborted: false, onabort: null, addEventListener: function () {}, removeEventListener: function () {}, throwIfAborted: function () {} };
    }
    abort() { this.signal.aborted = true; if (typeof this.signal.onabort === 'function') { try { this.signal.onabort(); } catch (e) {} } }
  };

  // ---- crypto ---------------------------------------------------------------
  g.crypto = {
    getRandomValues: function (arr) {
      const bytes = hexToBytes(__rizx_random_hex(arr.byteLength));
      const view = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
      view.set(bytes);
      return arr;
    },
    randomUUID: function () {
      const b = hexToBytes(__rizx_random_hex(16));
      b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
      const h = bytesToHex(b);
      return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
    },
    subtle: {
      importKey: async function (format, keyData, algorithm, extractable, usages) {
        if (format !== 'raw') throw new Error('importKey: only raw keys supported');
        const hash = (algorithm && algorithm.hash && (algorithm.hash.name || algorithm.hash)) || 'SHA-1';
        return { __hmacHex: bytesToHex(keyData), hash: String(hash) };
      },
      sign: async function (algorithm, key, data) {
        const hex = __rizx_hmac_hex(key.hash, key.__hmacHex, bytesToHex(data));
        return hexToBytes(hex).buffer;
      },
      digest: async function (algorithm, data) {
        const name = (algorithm && (algorithm.name || algorithm)) || 'SHA-256';
        const hex = __rizx_digest_hex(String(name), bytesToHex(data));
        return hexToBytes(hex).buffer;
      },
    },
  };

  // ---- fetch -> Kotlin OkHttp ----------------------------------------------
  g.fetch = async function (url, init) {
    init = init || {};
    let body = init.body;
    if (body != null && typeof body !== 'string') {
      if (body instanceof g.URLSearchParams) body = body.toString();
      else { try { body = String(body); } catch (e) { body = null; } }
    }
    let headers = init.headers || {};
    if (headers instanceof g.Headers) { const o = {}; headers.forEach((v, k) => { o[k] = v; }); headers = o; }
    const params = { url: String(url), method: (init.method || 'GET').toUpperCase(), headers: headers, body: body };
    const raw = await __rizx_fetch(JSON.stringify(params));
    const res = JSON.parse(raw);
    return {
      ok: res.status >= 200 && res.status < 300,
      status: res.status,
      statusText: res.statusText || '',
      url: res.url || String(url),
      headers: new g.Headers(res.headers || {}),
      text: async () => res.body || '',
      json: async () => JSON.parse(res.body || 'null'),
      // The host boundary is UTF-8 text; fine for every registry plugin (none fetch binaries).
      arrayBuffer: async () => utf8Encode(res.body || '').buffer,
    };
  };

  // ---- __rizx host ----------------------------------------------------------
  const rizx = {
    providers: {},   // uid -> descriptor object
    plugins: {},     // pluginId -> plugin object
    events: {},      // pluginId -> { eventName -> [handlers] }
    widgets: {},     // pluginId -> [widget names] (recorded; nothing renders)
    __last: null,    // last captured async result (JSON string) — read by Kotlin
    __err: null,     // last captured error message — read by Kotlin
  };

  // ---- bare-specifier stubs (module loader asks here before failing) --------
  // The plugin SDK is type-only (verified across the registry) so an empty module is correct. The
  // React/UI stubs exist solely so a bundled plugin that ships widget code evaluates — nothing renders.
  const reactStub = {
    createElement: function () { return null; },
    Fragment: 'fragment',
    useState: function (v) { return [v, function () {}]; },
    useEffect: function () {},
    useMemo: function (f) { return f(); },
    useCallback: function (f) { return f; },
    useRef: function (v) { return { current: v }; },
    useContext: function () { return null; },
  };
  const uiStub = new Proxy({}, { get: function () { return function Noop() { return null; }; } });
  rizx.requireStub = function (spec) {
    if (spec === '@nuclearplayer/plugin-sdk') return {};
    if (spec === 'react') return reactStub;
    if (spec === 'react/jsx-runtime' || spec === 'react/jsx-dev-runtime')
      return { jsx: function () { return null; }, jsxs: function () { return null; }, Fragment: 'fragment' };
    if (spec === '@nuclearplayer/ui' || spec === 'react-dom' || spec === 'react-dom/client') return uiStub;
    return undefined;
  };

  // Drop every descriptor a plugin registered (unload path) so its closures can be collected.
  rizx.dropProviders = function (pluginId) {
    for (const uid in rizx.providers) if (uid.indexOf(pluginId + ':') === 0) delete rizx.providers[uid];
    delete rizx.events[pluginId];
    delete rizx.widgets[pluginId];
  };

  // Dispatch a host event (trackStarted/trackFinished/…) to every subscribed plugin, isolated.
  rizx.emit = function (name, payloadJson) {
    let payload = null;
    try { payload = payloadJson ? JSON.parse(payloadJson) : null; } catch (e) {}
    for (const pluginId in rizx.events) {
      const handlers = rizx.events[pluginId][name];
      if (!handlers) continue;
      for (const fn of handlers.slice()) {
        try { fn(payload); } catch (e) { console.error('event ' + name + ' (' + pluginId + ')', e); }
      }
    }
  };

  rizx.makeApi = function (pluginId) {
    const kvGet = (scope, key) => {
      const raw = __rizx_kv_get(pluginId, scope, String(key));
      if (raw == null) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    const kvSet = (scope, key, value) =>
      __rizx_kv_set(pluginId, scope, String(key), JSON.stringify(value === undefined ? null : value));
    const kvRemove = (scope, key) => __rizx_kv_remove(pluginId, scope, String(key));
    return {
      Providers: {
        register: function (descriptor) {
          const uid = pluginId + ':' + descriptor.id;
          rizx.providers[uid] = descriptor;
          // Walk the prototype chain: class-instance descriptors keep methods on the prototype.
          const methods = [];
          let obj = descriptor;
          while (obj && obj !== Object.prototype) {
            for (const k of Object.getOwnPropertyNames(obj)) {
              if (k !== 'constructor' && typeof descriptor[k] === 'function' && methods.indexOf(k) < 0) methods.push(k);
            }
            obj = Object.getPrototypeOf(obj);
          }
          const meta = {
            uid: uid, id: descriptor.id, kind: descriptor.kind, name: descriptor.name || descriptor.id,
            searchCapabilities: descriptor.searchCapabilities || [],
            artistMetadataCapabilities: descriptor.artistMetadataCapabilities || [],
            albumMetadataCapabilities: descriptor.albumMetadataCapabilities || [],
            capabilities: descriptor.capabilities || [],
            metadataProviderId: descriptor.metadataProviderId || null,
            methods: methods,
          };
          __rizx_onRegister(pluginId, JSON.stringify(meta));
          return uid;
        },
        unregister: function (idOrUid) {
          const uid = rizx.providers[idOrUid] ? idOrUid : pluginId + ':' + idOrUid;
          delete rizx.providers[uid];
          return true;
        },
      },
      Logger: g.console,
      // Some plugins take an injected fetch (e.g. `new Client(api.Http.fetch)`) instead of the global.
      Http: {
        fetch: function (url, init) { return g.fetch(url, init); },
        get: function (url, init) { return g.fetch(url, init); },
      },
      Settings: {
        register: function (defs) {
          const list = Array.isArray(defs) ? defs : [defs];
          for (const d of list) {
            if (!d || d.id == null) continue;
            if (__rizx_kv_get(pluginId, 'settings', String(d.id)) == null && d.default !== undefined)
              kvSet('settings', d.id, d.default);
          }
        },
        get: function (key) { return kvGet('settings', key); },
        set: function (key, value) { kvSet('settings', key, value); },
        registerWidget: function (w) { (rizx.widgets[pluginId] = rizx.widgets[pluginId] || []).push((w && w.id) || 'widget'); },
        unregisterWidget: function () {},
      },
      Storage: {
        get: function (key) { return kvGet('storage', key); },
        set: function (key, value) { kvSet('storage', key, value); },
        remove: function (key) { kvRemove('storage', key); },
        delete: function (key) { kvRemove('storage', key); },
      },
      Events: {
        on: function (name, fn) {
          if (typeof fn !== 'function') return;
          const forPlugin = (rizx.events[pluginId] = rizx.events[pluginId] || {});
          (forPlugin[String(name)] = forPlugin[String(name)] || []).push(fn);
        },
        off: function (name, fn) {
          const forPlugin = rizx.events[pluginId]; if (!forPlugin) return;
          const list = forPlugin[String(name)]; if (!list) return;
          const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1);
        },
      },
      Shell: {
        openExternal: function (url) { __rizx_open_external(String(url)); },
      },
      // Backed by the native YouTube extractor (no yt-dlp binary on Android). When the host facade
      // is absent the call rejects, which lets multi-source plugins drop the YouTube leg under
      // Promise.allSettled while everything else works.
      Ytdlp: {
        search: function (query) {
          return __rizx_ytdlp('search', JSON.stringify({ query: String(query) })).then(JSON.parse);
        },
        getStream: function (idOrUrl) {
          return __rizx_ytdlp('getStream', JSON.stringify({ id: String(idOrUrl) })).then(JSON.parse);
        },
        getPlaylist: function (url) {
          return __rizx_ytdlp('getPlaylist', JSON.stringify({ url: String(url) })).then(JSON.parse);
        },
      },
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
