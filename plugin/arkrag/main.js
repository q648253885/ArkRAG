/* ArkRAG 知识检索 — Host 半（CommonJS；跑在 ArkWork utilityProcess）
 * 职责（docs/v1.0/04-system-design.md §6 流程 3）：
 *   ① 运行期注册视图 view:arkrag（与清单 provides.views 声明配套）
 *   ② 注册两个模型工具：rag_search / rag_list_kbs（清单 provides.tools 已声明，双闸）
 *   ③ 桥方法 'arkrag'：Client 半面板经 host.call 调入，按 op 分发（配置/KB 管理/上传/检索试用）
 * 鉴权惯例：token 存插件私有 storage（ctx.ark.storage），随 net.fetch 以 X-Api-Key 头发送，
 *           不写进任何日志；net.fetch 由宿主主进程代发（规避 CORS）。
 */
module.exports = {
  apply: function (ctx) {
    var DEFAULT_BASE_URL = 'http://127.0.0.1:8964'

    /* ---------- 配置 ---------- */
    async function getConfig() {
      var cfg = await ctx.ark.storage.get('config')
      if (cfg && typeof cfg === 'object') {
        if (!cfg.baseUrl) cfg.baseUrl = DEFAULT_BASE_URL
        return cfg
      }
      return { baseUrl: DEFAULT_BASE_URL, token: '' }
    }

    async function saveConfig(next) {
      var cur = await getConfig()
      var merged = {
        baseUrl: String(next && next.baseUrl || cur.baseUrl || DEFAULT_BASE_URL).trim(),
        token: String(next && next.token !== undefined ? next.token : cur.token || '')
      }
      await ctx.ark.storage.set('config', merged)
      return merged
    }

    function humanizeStatus(status, bodyText) {
      if (status === 401) return '鉴权失败（401）：token 与服务端 arkrag.server.token 不一致，请到连接设置修改'
      if (status === 404) return '资源不存在（404）：' + safeDetail(bodyText)
      if (status === 0) return '无法连接 ArkRAG 服务：请确认已运行 java -jar arkrag-server.jar，且地址/端口正确'
      return null
    }

    function safeDetail(bodyText) {
      try {
        var b = JSON.parse(bodyText)
        return (b.error && b.error.message) || bodyText
      } catch (e) {
        return String(bodyText || '').slice(0, 200)
      }
    }

    /* ---------- HTTP 通道 ---------- */
    async function api(path, opts) {
      var cfg = await getConfig()
      var base = String(cfg.baseUrl || DEFAULT_BASE_URL).replace(/\/+$/, '')
      var url = base + path
      var init = opts || {}
      init.headers = Object.assign({}, (opts && opts.headers) || {}, { 'X-Api-Key': cfg.token || '' })
      var res
      try {
        res = await ctx.ark.net.fetch(url, init)
      } catch (e) {
        var err = new Error('无法连接 ArkRAG 服务（' + (e && e.message ? e.message : e) + '）。'
          + '请先启动：java -jar arkrag-server.jar，并检查连接设置里的服务地址')
        err.human = true
        throw err
      }
      if (res.status >= 400) {
        var h = humanizeStatus(res.status, res.body)
        var ex = new Error(h || ('请求失败（HTTP ' + res.status + '）：' + safeDetail(res.body)))
        ex.status = res.status
        throw ex
      }
      return res.body ? JSON.parse(res.body) : null
    }

    function okResult(text) {
      return { content: [{ type: 'text', text: text }] }
    }

    function errorResult(message) {
      return { isError: true, content: [{ type: 'text', text: message }] }
    }

    /* ---------- 工具实现 ---------- */
    async function toolRagSearch(args) {
      var a = args || {}
      var query = typeof a.query === 'string' ? a.query.trim() : ''
      if (!query) return errorResult('参数错误：query 不能为空')
      var body = { query: query }
      if (Array.isArray(a.kb_ids) && a.kb_ids.length) body.kbIds = a.kb_ids
      if (a.top_k !== undefined && a.top_k !== null) body.topK = a.top_k
      var out = await api('/api/v1/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
      var hits = (out && out.hits) || []
      if (!hits.length) {
        return okResult('知识库中没有命中内容。可尝试换一种问法，或提示用户先在 ArkRAG 面板上传相关文档。')
      }
      var lines = ['检索到 ' + hits.length + ' 条相关内容（相似度降序）：']
      hits.forEach(function (h, i) {
        lines.push('')
        lines.push('[' + (i + 1) + '] 来源：' + h.docName + '（知识库「' + h.kbName + '」第 ' + (h.chunkIndex + 1) + ' 块，相似度 ' + h.score + '）')
        lines.push(h.text)
      })
      return okResult(lines.join('\n'))
    }

    async function toolRagListKbs() {
      var kbs = await api('/api/v1/kb', { method: 'GET' })
      if (!kbs || !kbs.length) {
        return okResult('当前没有知识库。提示用户打开 ArkRAG 面板（侧边栏 Book 图标）创建并上传文档。')
      }
      var lines = ['可用知识库 ' + kbs.length + ' 个：']
      kbs.forEach(function (k) {
        lines.push('- ' + k.name + '（id: ' + k.id + '）'
          + ' · 文档 ' + (k.documentCount || 0) + ' · 块 ' + (k.chunkCount || 0)
          + (k.description ? ' · ' + k.description : ''))
      })
      return okResult(lines.join('\n'))
    }

    async function toolRagAsk(args) {
      var a = args || {}
      var query = typeof a.query === 'string' ? a.query.trim() : ''
      if (!query) return errorResult('参数错误：query 不能为空')
      var body = { query: query }
      if (Array.isArray(a.kb_ids) && a.kb_ids.length) body.kbIds = a.kb_ids
      if (a.top_k !== undefined && a.top_k !== null) body.topK = a.top_k
      var out = await api('/api/v1/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
      var lines = [out.answer]
      if (out.citations && out.citations.length) {
        lines.push('')
        lines.push('引用来源：')
        out.citations.forEach(function (c) {
          lines.push('[' + c.index + '] ' + c.docName + '（知识库「' + c.kbName + '」第 ' + (c.chunkIndex + 1) + ' 块，相似度 ' + c.score + '）')
        })
      }
      return okResult(lines.join('\n'))
    }

    /* ---------- ① 视图注册 ---------- */
    ctx.ark.views.register({
      viewRef: 'view:arkrag',
      title: 'RAG',
      icon: 'Book',
      renderer: 'index.html',
      placement: 'dock'
    })

    /* ---------- ② 工具注册（清单 provides.tools 已声明） ---------- */
    ctx.ark.tools.register({
      name: 'rag_search',
      description: '在用户的本地知识库中做语义检索，返回相关文本片段与出处。回答事实性问题、引用用户资料时优先使用。',
      inputSchema: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '检索问题（自然语言）' },
          kb_ids: { type: 'array', items: { type: 'string' }, description: '限定知识库 id；缺省全部' },
          top_k: { type: 'integer', description: '返回条数 1..50，默认 5' }
        },
        required: ['query']
      },
      handler: function (args) { return toolRagSearch(args) }
    })

    ctx.ark.tools.register({
      name: 'rag_list_kbs',
      description: '列出可用的本地知识库。检索前可用它确认目标知识库 id。',
      inputSchema: { type: 'object', properties: {} },
      handler: function () { return toolRagListKbs() }
    })

    ctx.ark.tools.register({
      name: 'rag_ask',
      description: '基于用户本地知识库回答问题：先语义检索相关资料，再由 Chat 模型生成带 [n] 引用编号的答案。需要基于用户文档给出结论性回答时优先使用。',
      inputSchema: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '问题（自然语言）' },
          kb_ids: { type: 'array', items: { type: 'string' }, description: '限定知识库 id；缺省全部' },
          top_k: { type: 'integer', description: '召回条数 1..50，默认 5' }
        },
        required: ['query']
      },
      handler: function (args) { return toolRagAsk(args) }
    })

    /* ---------- ③ 桥方法：面板 → Host ---------- */
    ctx.ark.views.onCall('arkrag', function (p) {
      var params = p && typeof p === 'object' ? p : {}
      var op = String(params.op || '')
      var args = params.args || {}
      var handlers = {
        getConfig: function () { return getConfig() },
        saveConfig: function () { return saveConfig(args.config) },
        testConnection: async function () {
          var cfg = await getConfig()
          var base = String(cfg.baseUrl || DEFAULT_BASE_URL).replace(/\/+$/, '')
          var res
          try {
            res = await ctx.ark.net.fetch(base + '/api/v1/health', { headers: { 'X-Api-Key': cfg.token || '' } })
          } catch (e) {
            return { ok: false, message: '无法连接服务：请确认 arkrag-server 已启动且地址正确' }
          }
          if (res.status === 200) {
            var health = {}
            try { health = JSON.parse(res.body) } catch (e) { /* 忽略解析失败 */ }
            var emb = health.embedding || {}
            return {
              ok: true,
              version: health.version,
              embeddingConfigured: emb.configured === true,
              embeddingModel: emb.model || null,
              message: '连接成功'
            }
          }
          return { ok: false, message: humanizeStatus(res.status, res.body) || ('服务异常（HTTP ' + res.status + '）') }
        },
        listKbs: function () { return api('/api/v1/kb') },
        listDocs: function () { return api('/api/v1/kb/' + encodeURIComponent(args.kbId) + '/documents') },
        createKb: async function () {
          if (!args.name || !String(args.name).trim()) throw new Error('知识库名称不能为空')
          var body = { name: String(args.name).trim() }
          if (args.description) body.description = String(args.description)
          return api('/api/v1/kb', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
          })
        },
        deleteKb: function () {
          return api('/api/v1/kb/' + encodeURIComponent(args.kbId), { method: 'DELETE' })
        },
        uploadFiles: async function () {
          var kbId = encodeURIComponent(args.kbId)
          var results = []
          var files = Array.isArray(args.files) ? args.files : []
          for (var i = 0; i < files.length; i++) {
            var f = files[i]
            var doc = await api('/api/v1/kb/' + kbId + '/documents', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ title: f.name, base64: f.base64 })
            })
            results.push(doc)
          }
          return results
        },
        deleteDoc: function () {
          return api('/api/v1/kb/' + encodeURIComponent(args.kbId)
            + '/documents/' + encodeURIComponent(args.docId), { method: 'DELETE' })
        },
        searchPreview: function () {
          return api('/api/v1/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: args.query, kbIds: args.kbIds || undefined, topK: args.topK || 5 })
          })
        }
      }
      var fn = handlers[op]
      if (typeof fn !== 'function') return Promise.reject(new Error('未知操作：' + op))
      return Promise.resolve(fn())
    })

    ctx.log && ctx.log('ArkRAG 插件已激活：rag_search / rag_list_kbs 已注册')
  },
}
