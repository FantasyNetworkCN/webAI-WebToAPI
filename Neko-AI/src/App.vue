<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const defaultBase = import.meta.env.VITE_API_BASE || ''

const apiBaseInput = ref(defaultBase)
const nav = ref('overview')
const online = ref(false)
const loadingModels = ref(false)
const loadingLogs = ref(false)
const sending = ref(false)
const stream = ref(true)
const models = ref([])
const selectedModel = ref('')
const sessionId = ref(newSessionId())
const systemPrompt = ref('你是一个通过 Gemini WebToAPI 中转站接入的助手。')
const draft = ref('你好，简单介绍一下你自己。')
const messages = ref([])
const logs = ref([])
const error = ref('')
const playgroundPane = ref(null)
const copiedId = ref('')

const apiBase = computed(() => apiBaseInput.value.trim().replace(/\/$/, ''))
const chatUrl = computed(() => `${displayBase.value}/v1/chat/completions`)
const modelsUrl = computed(() => `${displayBase.value}/v1/models`)
const logsUrl = computed(() => `${displayBase.value}/debug/openai-logs?limit=80`)
const displayBase = computed(() => apiBase.value || window.location.origin)
const latestLog = computed(() => logs.value[0] || null)
const totalRequests = computed(() => logs.value.length)
const lastSessionSource = computed(() => latestLog.value?.session_source || '-')
const lastSessionHash = computed(() => latestLog.value?.session_key_hash || '-')
const modelRows = computed(() => models.value.map((id) => ({
  id,
  object: 'model',
  owner: 'local',
  status: id === selectedModel.value ? '默认测试' : '可用'
})))

const navItems = [
  { id: 'overview', label: '概览' },
  { id: 'playground', label: '调试' },
  { id: 'models', label: '模型' },
  { id: 'logs', label: '日志' },
  { id: 'docs', label: '接入' },
  { id: 'settings', label: '设置' }
]

const codeSamples = computed(() => ({
  curl: `curl ${chatUrl.value} \
  -H 'Content-Type: application/json' \
  -d '${JSON.stringify({
    model: selectedModel.value || 'gemini-3.5-Flash',
    stream: false,
    session_id: 'demo-session',
    messages: [{ role: 'user', content: '你好' }]
  })}'`,
  openai: `from openai import OpenAI

client = OpenAI(
    base_url="${displayBase.value}/v1",
    api_key="sk-local"
)

response = client.chat.completions.create(
    model="${selectedModel.value || 'gemini-3.5-Flash'}",
    messages=[{"role": "user", "content": "你好"}],
)
print(response.choices[0].message.content)`
}))

onMounted(async () => {
  await Promise.all([loadModels(), loadLogs()])
})

function newSessionId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID()
  }
  return `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function loadModels() {
  loadingModels.value = true
  error.value = ''
  try {
    const response = await fetch(`${displayBase.value}/v1/models`)
    if (!response.ok) {
      throw new Error(`模型接口失败：${response.status}`)
    }
    const data = await response.json()
    models.value = (data.data || []).map((item) => item.id)
    selectedModel.value = selectedModel.value || models.value[0] || 'gemini-3.5-Flash'
    online.value = true
  } catch (err) {
    error.value = err.message || String(err)
    online.value = false
  } finally {
    loadingModels.value = false
  }
}

async function loadLogs() {
  loadingLogs.value = true
  try {
    const response = await fetch(`${displayBase.value}/debug/openai-logs?limit=80`)
    const data = await response.json()
    if (!response.ok) {
      throw new Error(data?.error?.message || `日志接口失败：${response.status}`)
    }
    logs.value = (data.lines || [])
      .map((line) => {
        try {
          return JSON.parse(line)
        } catch {
          return { raw: line }
        }
      })
      .reverse()
  } catch (err) {
    if (!String(err.message || err).includes('Failed to fetch')) {
      error.value = err.message || String(err)
    }
  } finally {
    loadingLogs.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadModels(), loadLogs()])
}

async function sendTest() {
  const text = draft.value.trim()
  if (!text || sending.value) {
    return
  }

  error.value = ''
  pushMessage('user', text)
  const assistant = pushMessage('assistant', '', selectedModel.value)
  draft.value = ''
  await scrollPlayground()

  const payload = {
    model: selectedModel.value,
    stream: stream.value,
    session_id: sessionId.value,
    messages: openAiMessages(assistant.id)
  }

  sending.value = true
  const started = performance.now()
  try {
    if (stream.value) {
      await sendStream(payload, assistant)
    } else {
      await sendJson(payload, assistant)
    }
    assistant.latency = Math.round(performance.now() - started)
    await loadLogs()
  } catch (err) {
    assistant.role = 'system'
    assistant.content = err.message || String(err)
    error.value = assistant.content
  } finally {
    sending.value = false
    await scrollPlayground()
  }
}

function openAiMessages(skipAssistantId) {
  const out = []
  const system = systemPrompt.value.trim()
  if (system) {
    out.push({ role: 'system', content: system })
  }
  for (const item of messages.value) {
    if (item.id === skipAssistantId || item.role === 'system') {
      continue
    }
    out.push({ role: item.role, content: item.content })
  }
  return out
}

async function sendJson(payload, assistant) {
  const response = await fetch(`${displayBase.value}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  })
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(data?.error?.message || `请求失败：${response.status}`)
  }
  assistant.content = data?.choices?.[0]?.message?.content || ''
}

async function sendStream(payload, assistant) {
  const response = await fetch(`${displayBase.value}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!response.ok || !response.body) {
    const data = await response.json().catch(() => null)
    throw new Error(data?.error?.message || `请求失败：${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split('\n\n')
    buffer = parts.pop() || ''
    for (const part of parts) {
      consumeSse(part, assistant)
    }
    await scrollPlayground()
  }
  if (buffer.trim()) {
    consumeSse(buffer, assistant)
  }
}

function consumeSse(part, assistant) {
  for (const line of part.split('\n')) {
    if (!line.startsWith('data:')) {
      continue
    }
    const data = line.slice(5).trim()
    if (!data || data === '[DONE]') {
      continue
    }
    try {
      const json = JSON.parse(data)
      assistant.content += json.choices?.[0]?.delta?.content || ''
    } catch (e) {}
  }
}

function newChat() {
  sessionId.value = newSessionId()
  messages.value = []
  pushMessage('system', '已开启新的测试会话。')
}

function pushMessage(role, content, model = selectedModel.value) {
  const message = {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    role,
    content,
    model,
    latency: null
  }
  messages.value.push(message)
  return message
}

function roleName(role) {
  if (role === 'user') return 'User'
  if (role === 'assistant') return 'Assistant'
  return 'System'
}

async function scrollPlayground() {
  await nextTick()
  if (playgroundPane.value) {
    playgroundPane.value.scrollTop = playgroundPane.value.scrollHeight
  }
}

function pretty(value) {
  return JSON.stringify(value, null, 2)
}

function selectNav(id) {
  nav.value = id
  if (id === 'logs') {
    loadLogs()
  }
}

async function copy(text, id) {
  await navigator.clipboard?.writeText(text)
  copiedId.value = id
  setTimeout(() => {
    if (copiedId.value === id) copiedId.value = ''
  }, 2000)
}
</script>

<template>
  <div class="console-shell">
    <aside class="rail">
      <div class="brand">
        <div class="brand-mark">⚡</div>
        <div>
          <p>Gemini WebToAPI</p>
          <strong>中转控制台</strong>
        </div>
      </div>

      <nav class="nav-list">
        <button
          v-for="item in navItems"
          :key="item.id"
          class="nav-item"
          :class="{ active: nav === item.id }"
          @click="selectNav(item.id)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="rail-status">
        <span class="status-dot" :class="{ ok: online }" />
        <span>{{ online ? '中转站在线' : '中转站离线' }}</span>
      </div>
    </aside>

    <main class="console-main">
      <header class="topbar">
        <div>
          <p class="eyebrow">OpenAI Compatible Backend Web Gateway</p>
          <h1>{{ navItems.find((item) => item.id === nav)?.label }}</h1>
        </div>
        <div class="top-actions">
          <button class="btn" :disabled="loadingModels || loadingLogs" @click="refreshAll">全面刷新</button>
          <a class="btn primary" :href="displayBase" target="_blank" rel="noreferrer">浏览器测试端点</a>
        </div>
      </header>

      <div v-if="error" class="error-banner">
        <span class="error-icon">⚠️</span>
        <p>{{ error }}</p>
      </div>

      <section v-if="nav === 'overview'" class="page overview">
        <div class="stat-grid">
          <article class="stat-card">
            <span>服务状态</span>
            <strong :class="{ 'text-success': online, 'text-danger': !online }">{{ online ? 'Online' : 'Offline' }}</strong>
            <p>{{ displayBase }}</p>
          </article>
          <article class="stat-card">
            <span>模型数量</span>
            <strong>{{ models.length }}</strong>
            <p>{{ selectedModel || '-' }}</p>
          </article>
          <article class="stat-card">
            <span>请求记录数</span>
            <strong>{{ totalRequests }}</strong>
            <p>{{ latestLog?.time || '暂无数据' }}</p>
          </article>
          <article class="stat-card">
            <span>最近会话源</span>
            <strong>{{ lastSessionSource }}</strong>
            <p class="truncate">{{ lastSessionHash }}</p>
          </article>
        </div>

        <div class="two-column">
          <article class="panel">
            <div class="panel-head">
              <h2>API 核心接入端点</h2>
              <button class="mini-btn" @click="copy(chatUrl, 'chat')">
                {{ copiedId === 'chat' ? '已复制!' : '复制' }}
              </button>
            </div>
            <div class="endpoint-list">
              <div>
                <span>对话补全 (Chat)</span>
                <code>{{ chatUrl }}</code>
              </div>
              <div>
                <span>获取模型 (Models)</span>
                <code>{{ modelsUrl }}</code>
              </div>
              <div>
                <span>日志调试 (Logs)</span>
                <code>{{ logsUrl }}</code>
              </div>
            </div>
          </article>

          <article class="panel">
            <div class="panel-head">
              <h2>最新调用实时审计</h2>
              <button class="mini-btn" @click="selectNav('logs')">查看全部</button>
            </div>
            <pre class="code-block compact">{{ latestLog ? pretty(latestLog) : '当前无历史日志流水' }}</pre>
          </article>
        </div>
      </section>

      <section v-else-if="nav === 'playground'" class="page playground">
        <aside class="control-panel">
          <div class="field">
            <label>选用模型</label>
            <select v-model="selectedModel" :disabled="loadingModels || sending">
              <option v-for="model in models" :key="model" :value="model">{{ model }}</option>
            </select>
          </div>
          <label class="switch-line">
            <span>流式响应 (Stream)</span>
            <input v-model="stream" type="checkbox" :disabled="sending" />
          </label>
          <div class="field">
            <label>会话 Session ID (实现上下文隔离)</label>
            <input v-model.trim="sessionId" placeholder="自动指纹标识" />
          </div>
          <div class="button-row">
            <button class="btn" :disabled="sending" @click="newChat">开启新独立会话</button>
            <button class="btn danger" :disabled="sending || messages.length === 0" @click="messages = []">
              清空屏幕
            </button>
          </div>
          <div class="field">
            <label>系统提示词 (System Prompt)</label>
            <textarea v-model="systemPrompt" rows="4" />
          </div>
        </aside>

        <section class="playground-chat">
          <div ref="playgroundPane" class="message-list">
            <div v-if="messages.length === 0" class="empty-state">
              👋 欢迎来到中转站调试控制台。在此输入的聊天信息将严格遵循 OpenAI 标准封装，并发往后端智能调度转换为 Web 协议进行上下文处理。
            </div>
            <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
              <div class="message-meta">
                <span class="role-badge">{{ roleName(message.role) }}</span>
                <span class="meta-right">{{ message.latency ? `${message.latency}ms` : message.model }}</span>
              </div>
              <div class="message-content">{{ message.content }}</div>
            </article>
          </div>
          <footer class="composer">
            <textarea
              v-model="draft"
              placeholder="输入您的 Prompt。按 Enter 发送请求，Shift+Enter 换行。"
              :disabled="sending"
              @keydown.enter.exact.prevent="sendTest"
            />
            <button class="btn primary" :disabled="sending || !draft.trim()" @click="sendTest">
              {{ sending ? '数据传输中...' : '发送请求' }}
            </button>
          </footer>
        </section>
      </section>

      <section v-else-if="nav === 'models'" class="page">
        <article class="panel">
          <div class="panel-head">
            <h2>可用模型集群列表</h2>
            <button class="mini-btn" :disabled="loadingModels" @click="loadModels">快速重连获取</button>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>模型标识 (Model ID)</th>
                <th>映射类型</th>
                <th>提供商</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in modelRows" :key="model.id">
                <td><code>{{ model.id }}</code></td>
                <td>{{ model.object }}</td>
                <td>{{ model.owner }}</td>
                <td><span class="tag active">{{ model.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </article>
      </section>

      <section v-else-if="nav === 'logs'" class="page">
        <article class="panel">
          <div class="panel-head">
            <h2>中转逆向请求全量日志</h2>
            <button class="mini-btn" :disabled="loadingLogs" @click="loadLogs">即时刷新</button>
          </div>
          <div v-if="logs.length === 0" class="empty-state">系统暂无逆向中转调用流水的产生。</div>
          <div v-else class="log-list">
            <article v-for="(log, index) in logs" :key="index" class="log-card">
              <div class="log-head">
                <span class="tag time">{{ log.time || '-' }}</span>
                <span class="tag model">{{ log.model || '-' }}</span>
                <span class="tag source">源: {{ log.session_source || 'Unknown' }}</span>
                <span class="tag hash">SHA-256: {{ log.session_key_hash || '-' }}</span>
              </div>
              <pre class="code-block">{{ pretty(log) }}</pre>
            </article>
          </div>
        </article>
      </section>

      <section v-else-if="nav === 'docs'" class="page docs">
        <article class="panel">
          <div class="panel-head">
            <h2>极速命令行接入 (cURL)</h2>
            <button class="mini-btn" @click="copy(codeSamples.curl, 'curl')">
              {{ copiedId === 'curl' ? '已成功复制到剪贴板!' : '一键复制' }}
            </button>
          </div>
          <pre class="code-block">{{ codeSamples.curl }}</pre>
        </article>

        <article class="panel">
          <div class="panel-head">
            <h2>主流开发库接入 (Python OpenAI SDK)</h2>
            <button class="mini-btn" @click="copy(codeSamples.openai, 'openai')">
              {{ copiedId === 'openai' ? '已成功复制到剪贴板!' : '一键复制' }}
            </button>
          </div>
          <pre class="code-block">{{ codeSamples.openai }}</pre>
        </article>
      </section>

      <section v-else class="page settings">
        <article class="panel">
          <div class="panel-head">
            <h2>前端中转与网络连接调配</h2>
            <button class="mini-btn" @click="refreshAll">同步数据源</button>
          </div>
          <div class="settings-grid">
            <div class="field">
              <label>自定义代理中转 API Base</label>
              <input v-model.trim="apiBaseInput" placeholder="留空默认使用当前后端 Host (如 window.location.origin)" />
              <p class="help-text">如果您需要跨域或在开发阶段连接特定的本地/远程中转实例端口，请修改此字段。</p>
            </div>
            <div class="field">
              <label>当前演化 Chat 补全接口</label>
              <input :value="chatUrl" readonly class="readonly-input" />
            </div>
            <div class="field">
              <label>当前演化 Models 获取接口</label>
              <input :value="modelsUrl" readonly class="readonly-input" />
            </div>
            <div class="field">
              <label>当前演化 Logs 数据流水接口</label>
              <input :value="logsUrl" readonly class="readonly-input" />
            </div>
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* 极其丝滑现代化微光极客风控制台 */
.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>