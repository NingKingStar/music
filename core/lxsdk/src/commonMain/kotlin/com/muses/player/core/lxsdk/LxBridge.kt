package com.muses.player.core.lxsdk

/**
 * 洛雪宿主 `globalThis.lx` 的 JS 侧引导脚本（shim）。
 *
 * 设计依据（见 spike-lx/README.md §4）：
 * 1. **`lx` 对象在 JS 侧组装**，Kotlin 只 binding 一个 `__native` 原子能力对象。
 *    这与洛雪真实宿主（preload shim）实现方式一致，避免跨语言对象语义偏差。
 * 2. **宿主 binding 一律传 JSON 字符串**：实测 Kotlin `Map` → JS 对象会丢字段（变成 `{}`），
 *    导致脚本 `data.url` 取到 `undefined`。
 * 3. **`lx.request` 必须复刻洛雪的自动 JSON 解析**：脚本普遍写 `.then(data => data.url)`，
 *    依赖宿主已把响应体解析成对象。
 * 4. `lx.on('request', handler)` 的 handler 存入 `__lxHandlers` 表，
 *    宿主后续按表调用 —— 这是「加载一次、多次请求复用」的前提。
 */
internal object LxBridge {

    /**
     * 引导脚本。执行后建立：
     * - `globalThis.lx`：完整宿主 API 对象；
     * - `globalThis.__lxHandlers`：脚本注册的 handler 表；
     * - `globalThis.__lxInited`：inited 数据暂存槽（由 Kotlin 读回）。
     */
    val BOOTSTRAP: String = """
        (() => {
          const handlers = {};
          globalThis.__lxHandlers = handlers;
          globalThis.__lxInited = null;

          // QuickJS 只有 ECMAScript 内建对象；音源直连渠道还依赖查询参数 API。
          const encodeQuery = value => encodeURIComponent(String(value))
            .replace(/[!'()~]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase())
            .replace(/%20/g, '+');
          const decodeQuery = value => {
            const text = value.replace(/\+/g, ' ');
            try { return decodeURIComponent(text); } catch (e) { return text; }
          };
          globalThis.URLSearchParams = class URLSearchParams {
            constructor(init = '') {
              this.pairs = [];
              if (typeof init === 'string') {
                const text = init.startsWith('?') ? init.slice(1) : init;
                for (const part of text.split('&')) {
                  if (!part) continue;
                  const index = part.indexOf('=');
                  this.append(decodeQuery(index < 0 ? part : part.slice(0, index)),
                    decodeQuery(index < 0 ? '' : part.slice(index + 1)));
                }
              } else if (init != null && typeof init[Symbol.iterator] === 'function') {
                for (const pair of init) {
                  const values = Array.from(pair);
                  if (values.length !== 2) throw new TypeError('查询参数必须包含名称和值');
                  this.append(values[0], values[1]);
                }
              } else if (init != null) {
                for (const key of Object.keys(init)) this.append(key, init[key]);
              }
            }
            get size() { return this.pairs.length; }
            append(name, value) { this.pairs.push([String(name), String(value)]); }
            set(name, value) {
              name = String(name); value = String(value);
              let found = false;
              this.pairs = this.pairs.filter(pair => {
                if (pair[0] !== name) return true;
                if (found) return false;
                pair[1] = value; found = true; return true;
              });
              if (!found) this.append(name, value);
            }
            get(name) { const pair = this.pairs.find(p => p[0] === String(name)); return pair ? pair[1] : null; }
            getAll(name) { return this.pairs.filter(p => p[0] === String(name)).map(p => p[1]); }
            has(name) { return this.pairs.some(p => p[0] === String(name)); }
            delete(name) { this.pairs = this.pairs.filter(p => p[0] !== String(name)); }
            sort() { this.pairs.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0); }
            *entries() { for (const pair of this.pairs) yield [...pair]; }
            *keys() { for (const pair of this.pairs) yield pair[0]; }
            *values() { for (const pair of this.pairs) yield pair[1]; }
            [Symbol.iterator]() { return this.entries(); }
            forEach(callback, thisArg) { for (const pair of this.pairs) callback.call(thisArg, pair[1], pair[0], this); }
            toString() { return this.pairs.map(p => encodeQuery(p[0]) + '=' + encodeQuery(p[1])).join('&'); }
          };

          // QuickJS 不带宿主定时器；通过 native sleep + Promise 提供洛雪脚本常用计时 API。
          const __timers = new Map();
          let __nextTimerId = 1;
          globalThis.setTimeout = (callback, ms = 0, ...args) => {
            const id = __nextTimerId++;
            const timer = { cancelled: false };
            __timers.set(id, timer);
            globalThis.__native.sleep(Math.max(0, Number(ms) || 0)).then(() => {
              if (!timer.cancelled) {
                __timers.delete(id);
                if (typeof callback === 'function') callback(...args);
              }
            }).catch(() => {});
            return id;
          };
          globalThis.clearTimeout = id => {
            const timer = __timers.get(id);
            if (timer) timer.cancelled = true;
            __timers.delete(id);
          };
          globalThis.setInterval = (callback, ms = 0, ...args) => {
            const id = __nextTimerId++;
            const timer = { cancelled: false };
            __timers.set(id, timer);
            const tick = () => globalThis.__native.sleep(Math.max(1, Number(ms) || 0)).then(() => {
              if (timer.cancelled) return;
              if (typeof callback === 'function') callback(...args);
              tick();
            }).catch(() => {});
            tick();
            return id;
          };
          globalThis.clearInterval = globalThis.clearTimeout;

          // console（洛雪规范示例脚本大量使用 console.log 排错；
          // QuickJS 无 console，缺失会让脚本在错误分支抛 ReferenceError）
          const __log = (level) => (...args) => {
            try { globalThis.__native.log(level, args.map(a => {
              if (a === null) return 'null';
              if (a === undefined) return 'undefined';
              if (typeof a === 'object') { try { return JSON.stringify(a); } catch (e) { return String(a); } }
              return String(a);
            }).join(' ')); } catch (e) { /* 日志失败不影响脚本 */ }
          };
          globalThis.console = {
            log: __log('log'),
            info: __log('info'),
            warn: __log('warn'),
            error: __log('error'),
            debug: __log('debug'),
            group: __log('log'),
            groupCollapsed: __log('log'),
            groupEnd: () => {},
          };

          globalThis.lx = {
            version: '1.0.0',
            env: 'desktop',
            currentScriptInfo: {},
            EVENT_NAMES: {
              inited: 'inited',
              request: 'request',
              updateAlert: 'updateAlert',
            },

            on(name, handler) {
              if (typeof handler !== 'function') return;
              handlers[name] = handler;
            },

            // send 按规范返回 Promise；数据经 __native.send 回传宿主
            send(name, data) {
              try {
                globalThis.__native.send(name, JSON.stringify(data === undefined ? null : data));
              } catch (e) {
                return Promise.reject(e);
              }
              return Promise.resolve();
            },

            // 宿主 HTTP：不经 CORS 限制。options 支持 method/headers/body/form/formData/timeout
            request(url, options, callback) {
              const opts = JSON.stringify(options || {});
              // __native.http 为 asyncFunction（返回 Promise），参数为字符串
              const promise = globalThis.__native.http(url, opts);
              if (typeof callback === 'function') {
                Promise.resolve(promise).then(raw => {
                  const response = JSON.parse(raw);
                  let body = response.body;
                  // 复刻洛雪宿主的自动 JSON 解析：脚本依赖 data.url 这类字段访问
                  if (typeof body === 'string') {
                    const t = body.trim();
                    if (t.startsWith('{') || t.startsWith('[')) {
                      try { body = JSON.parse(t); } catch (e) { /* 保留原始正文 */ }
                    }
                  }
                  callback(null, { body, statusCode: response.statusCode, headers: response.headers }, body);
                }).catch(err => {
                  callback(err instanceof Error ? err : new Error(String(err)));
                });
              }
              // 返回取消函数（占位：保留签名兼容）
              return () => {};
            },

            utils: {
              buffer: {
                from: (data, encoding) => globalThis.__native.bufferFrom(String(data), encoding || 'utf8'),
                bufToString: (buf, format) => globalThis.__native.bufferToString(buf, format || 'utf8'),
              },
              crypto: {
                md5: str => globalThis.__native.md5(String(str)),
                randomBytes: size => globalThis.__native.randomBytes(Number(size) || 0),
                aesEncrypt: (buf, mode, key, iv) =>
                  globalThis.__native.aesEncrypt(buf, mode, key, iv === undefined ? null : iv),
                rsaEncrypt: (buf, key) => globalThis.__native.rsaEncrypt(buf, key),
              },
              zlib: {
                inflate: buf => Promise.resolve(globalThis.__native.inflate(buf)),
                deflate: buf => Promise.resolve(globalThis.__native.deflate(buf)),
              },
            },
          };
        })();
    """.trimIndent()

    /**
     * 注入脚本元信息到 `lx.currentScriptInfo`。
     * [metaJson] 为已序列化的 JSON 对象字符串（Kotlin 侧保证转义）。
     */
    fun currentScriptInfoScript(metaJson: String): String =
        "globalThis.lx.currentScriptInfo = $metaJson;"

    /**
     * 调用脚本注册的 `request` handler 并把结果回填到 `__lxResult`。
     *
     * 关键（spike §4.3）：`evaluate()` **不会 await 脚本自定义的 async IIFE**，
     * 故必须在 JS 侧 await 完成后主动回调 Kotlin binding（`__native.resolve`），
     * Kotlin 侧用 `CompletableDeferred` 等待。
     *
     * 错误同样回填（经 `__native.reject`），避免 Kotlin 侧只能靠超时兜底。
     *
     * @param payloadJson 请求参数 JSON：`{ source, action, info }`
     * @param resultKey 结果槽位名（并发请求时各用独立槽位）
     */
    fun requestScript(payloadJson: String, resultKey: String): String = """
        (async () => {
          try {
            const handler = globalThis.__lxHandlers['request'];
            if (typeof handler !== 'function') {
              throw new Error('脚本未注册 request handler');
            }
            const result = await handler($payloadJson);
            globalThis.__native.resolve('$resultKey', result === undefined ? null : JSON.stringify(result));
          } catch (err) {
            globalThis.__native.reject('$resultKey', (err && err.message) ? err.message : String(err));
          }
        })();
    """.trimIndent()
}
