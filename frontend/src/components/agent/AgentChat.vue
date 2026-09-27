<template>
  <el-drawer
    :visible="visible"
    direction="rtl"
    size="72%"
    :with-header="false"
    :wrapper-closable="true"
    custom-class="agent-drawer"
    @close="$emit('update:visible', false)"
  >
    <div class="agent-layout">
      <!-- 左侧：历史会话 -->
      <div class="conv-pane">
        <div class="conv-head">
          <span>历史会话</span>
          <el-button type="text" icon="el-icon-plus" @click="newConversation">新建</el-button>
        </div>
        <div v-loading="convLoading" class="conv-list">
          <div
            v-for="c in conversations"
            :key="c.id"
            :class="['conv-item', { active: c.id === activeId }]"
            @click="openConversation(c.id)"
          >
            <div class="conv-title" :title="c.title || '（未命名）'">{{ c.title || '（未命名）' }}</div>
            <div class="conv-meta">
              <span>{{ formatTime(c.lastMessageAt || c.createdAt) }}</span>
              <span class="conv-actions">
                <i class="el-icon-edit" title="改名" @click.stop="rename(c)"></i>
                <i class="el-icon-delete" title="删除" @click.stop="remove(c)"></i>
              </span>
            </div>
          </div>
          <div v-if="!conversations.length && !convLoading" class="conv-empty">还没有历史会话</div>
        </div>
      </div>

      <!-- 右侧：对话 -->
      <div class="chat-pane">
        <div class="chat-head">
          <span class="chat-title">派单助手</span>
          <span class="chat-model">模型：{{ modelName }}</span>
          <el-button type="text" icon="el-icon-close" class="chat-close" @click="$emit('update:visible', false)" />
        </div>

        <div ref="scroll" class="chat-messages">
          <div v-if="!messages.length" class="chat-welcome">
            <p>你好，我是派单助手。你可以这样问我：</p>
            <el-tag v-for="q in quickQuestions" :key="q" class="quick" @click="send(q)">{{ q }}</el-tag>
          </div>

          <div v-for="m in messages" :key="m.key" :class="['msg', m.role]">
            <template v-if="m.role === 'user'">
              <div class="bubble user">{{ m.content }}</div>
            </template>
            <template v-else-if="m.role === 'assistant'">
              <div :class="['bubble', 'assistant', { error: m.error, streaming: m.streaming && !m.pending }]">
                <span v-if="m.pending" class="typing">
                  <i></i><i></i><i></i><span class="typing-text">正在处理…</span>
                </span>
                <div v-else class="markdown" v-html="markdown(m.content)"></div>
              </div>
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'preview'">
              <preview-card
                :payload="m.payload"
                :status="m.status"
                :status-message="m.statusMessage"
                :busy="busy"
                @selection-change="onPreviewSelection(m, $event)"
                @dispatch-selected="dispatchSelected(m)"
              />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'choice'">
              <report-choice-card
                :payload="m.payload"
                :busy="busy"
                :chosen="m.chosen"
                @choose="chooseReports(m, $event)"
              />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'plan'">
              <plan-card
                :payload="m.payload"
                :status="m.status"
                :status-message="m.statusMessage"
                :busy="busy"
                :refresh-version="planRefreshVersion"
                @confirm="confirmPlan(m)"
                @cancel="cancelPlanCard(m)"
                @reconcile="reconcilePlanCard(m)"
              />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'result'">
              <result-card :payload="m.payload" :busy="busy" @retry="retryFailed(m)" />
            </template>
          </div>
        </div>

        <div class="chat-input">
          <div v-if="choosing" class="job-progress">{{ jobStage }}
            <el-button type="text" size="mini" @click="cancelCurrentJob">取消查询</el-button>
          </div>
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="例如：查一下我有哪些可以派单 / 我不想派 SO2026002，剩下的帮我派单吧（Enter 发送，Shift+Enter 换行）"
            :disabled="sending"
            @keydown.native.enter.exact.prevent="send()"
          />
          <div class="chat-input-actions">
            <span class="hint">
              <template v-if="uiExcludes.length">已在表格中取消勾选 {{ uiExcludes.length }} 条，派单时会自动排除</template>
              <template v-else>派单前会先生成待确认清单，点击"确认派单"才会真正执行</template>
            </span>
            <el-button type="primary" size="small" :loading="sending" @click="send()">发送</el-button>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script>
import PreviewCard from './PreviewCard.vue'
import PlanCard from './PlanCard.vue'
import ResultCard from './ResultCard.vue'
import ReportChoiceCard from './ReportChoiceCard.vue'
import { renderMarkdown } from '../../utils/markdown'
import { getCurrentUserId } from '../../auth'
import {
  cancelPlan,
  confirmPlan,
  retryFailedPlan,
  reconcilePlan,
  startPreviewJob,
  fetchPreviewJob,
  fetchLatestPreviewJob,
  fetchPreview,
  cancelPreviewJob,
  deleteConversation,
  fetchCardStates,
  fetchConversations,
  fetchMessages,
  fetchModel,
  renameConversation,
  streamChat
} from '../../api/agent'

let seq = 0

export default {
  name: 'AgentChat',
  components: { PreviewCard, PlanCard, ResultCard, ReportChoiceCard },
  props: {
    visible: { type: Boolean, default: false }
  },
  data() {
    return {
      modelName: '',
      conversations: [],
      convLoading: false,
      activeId: null,
      messages: [],
      input: '',
      sending: false,
      choosing: false,
      currentJobId: null,
      jobStage: '',
      // 预览表格里取消勾选的单据号，以及它们所在的那张预览卡片（服务端只对同一张预览生效）
      uiExcludes: [],
      uiPreviewId: null,
      executingPlanId: null,
      planRefreshVersion: 0,
      quickQuestions: ['查一下我有哪些可以派单', '查一下费用报表有哪些可以派单', '查一下客户对账有哪些可以派单', '剩下的帮我派单吧']
    }
  },
  computed: {
    busy() {
      return this.sending || this.choosing || !!this.executingPlanId
    }
  },
  watch: {
    visible(v) {
      if (v) {
        this.loadConversations()
        if (!this.modelName) {
          fetchModel().then((d) => (this.modelName = d.model)).catch(() => (this.modelName = '未知'))
        }
        this.refreshStates()
        this.resumePendingJob()
      }
    }
  },
  methods: {
    pendingJobKey() {
      return `agent-preview-job:${getCurrentUserId()}`
    },
    rememberPendingJob(jobId) {
      sessionStorage.setItem(this.pendingJobKey(), JSON.stringify({ jobId, conversationId: this.activeId }))
    },
    forgetPendingJob(jobId) {
      const stored = sessionStorage.getItem(this.pendingJobKey())
      if (stored && JSON.parse(stored).jobId === jobId) sessionStorage.removeItem(this.pendingJobKey())
    },
    async pollPreviewJob(jobId) {
      this.currentJobId = jobId
      this.choosing = true
      let job = await fetchPreviewJob(jobId)
      while (job.status === 'QUEUED' || job.status === 'RUNNING') {
        this.jobStage = job.status === 'QUEUED' ? '查询排队中…'
          : `正在查询可派单记录…已扫描 ${job.scannedRows || 0} 条`
        await new Promise((resolve) => setTimeout(resolve, 1000))
        job = await fetchPreviewJob(jobId)
      }
      if (job.status !== 'SUCCEEDED') this.forgetPendingJob(jobId)
      return job
    },
    async resumePendingJob(conversationId = null) {
      if (this.currentJobId || this.sending || this.choosing) return
      const stored = sessionStorage.getItem(this.pendingJobKey())
      let pending
      try { pending = stored ? JSON.parse(stored) : null } catch (e) { sessionStorage.removeItem(this.pendingJobKey()) }
      if (conversationId && conversationId !== pending?.conversationId) pending = { conversationId }
      if (!pending) pending = { conversationId: this.activeId }
      if (!pending.conversationId) return
      this.choosing = true
      try {
        if (this.activeId !== pending.conversationId) await this.openConversation(pending.conversationId, true)
        if (!pending.jobId) {
          const latest = await fetchLatestPreviewJob(pending.conversationId)
          if (!latest) { this.forgetPendingJob(null); return }
          pending.jobId = latest.id
          this.rememberPendingJob(pending.jobId)
        }
        const job = await this.pollPreviewJob(pending.jobId)
        if (job.status === 'SUCCEEDED' && !this.messages.some((m) => m.cardType === 'preview' &&
            (m.payload?.previewId || m.previewId) === job.previewId)) {
          const preview = await fetchPreview(job.previewId)
          this.clearSelection()
          this.push({ role: 'card', cardType: 'preview', payload: preview, status: preview.status, statusMessage: null })
          this.forgetPendingJob(pending.jobId)
        } else if (job.status === 'SUCCEEDED') {
          this.forgetPendingJob(pending.jobId)
        } else if (job.status === 'FAILED') {
          this.$message.warning(job.message || '预览查询失败')
        }
      } catch (e) {
        // 保留任务编号；重新打开对话抽屉或刷新页面后继续查询。
      } finally {
        this.choosing = false
        this.currentJobId = null
        this.jobStage = ''
        this.refreshStates()
      }
    },
    // ---------- 会话列表 ----------
    async loadConversations() {
      this.convLoading = true
      try {
        this.conversations = await fetchConversations()
      } finally {
        this.convLoading = false
      }
    },
    newConversation() {
      if (this.busy) return
      this.activeId = null
      this.messages = []
      this.clearSelection()
      this.input = ''
    },
    async openConversation(id, force = false) {
      if (this.busy && !force) return
      this.activeId = id
      this.clearSelection()
      const list = await fetchMessages(id)
      // 卡片状态由服务端随历史消息一起返回：刷新页面、换设备看到的都一样
      this.messages = this.textBeforeCards(list).map((m) => ({
        key: `h-${m.id}`,
        id: m.id,
        role: m.role,
        content: m.content,
        cardType: m.cardType,
        payload: m.payload,
        previewId: m.previewId,
        planId: m.planId,
        status: m.status,
        statusMessage: m.statusMessage,
        chosen: ''
      }))
      this.scrollToBottom()
      if (!force) await this.resumePendingJob(id)
    },
    async rename(c) {
      try {
        const { value } = await this.$prompt('新的会话标题', '改名', { inputValue: c.title, inputPattern: /\S+/, inputErrorMessage: '标题不能为空' })
        await renameConversation(c.id, value)
        c.title = value
      } catch (e) {
        /* cancelled */
      }
    },
    async remove(c) {
      try {
        await this.$confirm(`删除会话"${c.title || '（未命名）'}"？`, '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      await deleteConversation(c.id)
      if (this.activeId === c.id) this.newConversation()
      this.loadConversations()
    },

    // ---------- 卡片状态 ----------
    /**
     * 以服务端为准刷新本会话全部预览 / 清单卡片的状态。每轮对话结束、每次确认 / 取消 / 选择报表之后调用；
     * 新预览作废旧卡片、规则变化导致失效、超时过期，都在这里体现，前端不再按消息先后自行推导。
     */
    async refreshStates() {
      const id = this.activeId
      if (!id) return
      let states
      try {
        states = await fetchCardStates(id)
      } catch (e) {
        return
      }
      if (id !== this.activeId) return
      this.messages.forEach((m) => {
        if (m.role !== 'card' || !m.payload) return
        let state = null
        if (m.cardType === 'preview') state = states.previews[m.payload.previewId || m.previewId]
        else if (m.cardType === 'plan') state = states.plans[m.payload.planId || m.planId]
        if (state) {
          this.$set(m, 'status', state.status)
          this.$set(m, 'statusMessage', state.message)
        }
      })
      const selected = this.uiPreviewId && states.previews[this.uiPreviewId]
      if (this.uiPreviewId && (!selected || selected.status !== 'ACTIVE')) this.clearSelection()
    },
    clearSelection() {
      this.uiExcludes = []
      this.uiPreviewId = null
    },

    // ---------- 对话 ----------
    push(msg) {
      msg.key = msg.key || `m-${++seq}`
      this.messages.push(msg)
      this.scrollToBottom()
      return msg
    },
    markdown(text) {
      return renderMarkdown(text)
    },
    /**
     * 卡片是在工具执行时落库的，历史里排在同轮助手文字前面。
     * 这里按"每轮先文字、后卡片"重排，和实时对话保持一致。
     */
    textBeforeCards(list) {
      const result = []
      let texts = []
      let cards = []
      const flush = () => {
        result.push(...texts, ...cards)
        texts = []
        cards = []
      }
      list.forEach((m) => {
        if (m.role === 'user') {
          flush()
          result.push(m)
        } else if (m.role === 'card') {
          cards.push(m)
        } else {
          texts.push(m)
        }
      })
      flush()
      return result
    },
    onPreviewSelection(m, unselectedDocNos) {
      if (m.status !== 'ACTIVE') return
      this.uiExcludes = unselectedDocNos
      this.uiPreviewId = m.payload.previewId
    },
    dispatchSelected(m) {
      if (m.status !== 'ACTIVE') return
      this.send('把已勾选的记录帮我派单')
    },
    async send(text) {
      const message = (text || this.input || '').trim()
      if (!message || this.busy) return
      this.input = ''
      this.sending = true
      this.push({ role: 'user', content: message })
      // 本轮的结构化卡片先收集，等文字输出完再挂到文字下面，
      // 否则卡片会插在回答中间，看起来"卡片比回答先到"
      const cards = []
      let pendingJobId = null
      const excludeDocNos = this.uiExcludes.slice()
      const previewId = this.uiPreviewId
      const assistant = this.push({ role: 'assistant', content: '', streaming: true, pending: true })
      const onEvent = (type, data) => {
        switch (type) {
          case 'conversation':
            if (this.activeId !== data.conversationId) {
              this.activeId = data.conversationId
            }
            // 先保存会话，即使下一个任务事件丢失也能从服务端发现任务。
            this.rememberPendingJob(null)
            break
          case 'text':
            assistant.pending = false
            assistant.content += data.delta
            this.scrollToBottom()
            break
          case 'preview':
            this.clearSelection()
            cards.push({ role: 'card', cardType: 'preview', payload: data, status: data.status || 'ACTIVE', statusMessage: null })
            break
          case 'preview_job':
            pendingJobId = data.jobId
            this.currentJobId = data.jobId
            this.choosing = true
            this.jobStage = '查询排队中…'
            this.rememberPendingJob(data.jobId)
            break
          case 'choice':
            cards.push({ role: 'card', cardType: 'choice', payload: data, chosen: '' })
            break
          case 'plan':
            cards.push({ role: 'card', cardType: 'plan', payload: data, status: data.status || 'PENDING', statusMessage: null })
            break
          case 'result':
            this.clearSelection()
            cards.push({ role: 'card', cardType: 'result', payload: data })
            break
          case 'error':
            assistant.pending = false
            assistant.error = true
            assistant.content = assistant.content
              ? `${assistant.content}\n\n${data.message || '出错了'}`
              : data.message || '出错了'
            break
          default:
            break
        }
      }
      try {
        const { promise } = streamChat({ conversationId: this.activeId, message, excludeDocNos, previewId }, onEvent)
        await promise
        if (!pendingJobId) this.forgetPendingJob(null)
        if (pendingJobId) {
          const job = await this.pollPreviewJob(pendingJobId)
          if (job.status === 'SUCCEEDED') {
            const preview = await fetchPreview(job.previewId)
            this.clearSelection()
            cards.push({ role: 'card', cardType: 'preview', payload: preview,
              status: preview.status, statusMessage: null })
            this.forgetPendingJob(pendingJobId)
          } else if (job.status !== 'CANCELLED') {
            this.$message.warning(job.message || '预览查询失败')
          }
        }
      } catch (e) {
        let recovered = false
        if (!pendingJobId && this.activeId) {
          try {
            const latest = await fetchLatestPreviewJob(this.activeId)
            if (latest && !this.messages.some((m) => m.cardType === 'preview' &&
                (m.payload?.previewId || m.previewId) === latest.previewId)) {
              pendingJobId = latest.id
              this.rememberPendingJob(pendingJobId)
            }
          } catch (discoveryError) { /* 保留会话编号，重开页面后重新发现任务。 */ }
        }
        if (pendingJobId) {
          try {
            const job = await this.pollPreviewJob(pendingJobId)
            if (job.status === 'SUCCEEDED') {
              const preview = await fetchPreview(job.previewId)
              this.clearSelection()
              cards.push({ role: 'card', cardType: 'preview', payload: preview,
                status: preview.status, statusMessage: null })
              this.forgetPendingJob(pendingJobId)
            } else if (job.status === 'FAILED') {
              this.$message.warning(job.message || '预览查询失败')
            }
            recovered = true
          } catch (pollError) {
            // 任务 ID 留在 sessionStorage，网络恢复或页面重开后可继续查询。
          }
        }
        assistant.pending = false
        assistant.error = !recovered
        assistant.content = assistant.content || (recovered ? '连接已中断，查询结果已恢复。' : `请求失败：${e.message}`)
        if (!recovered && !pendingJobId && this.activeId) {
          try {
            await this.openConversation(this.activeId, true)
            cards.length = 0
          } catch (ignored) { /* 保留已收到的内容 */ }
        }
      } finally {
        assistant.pending = false
        assistant.streaming = false
        // 本轮只有卡片没有文字时，把等待占位删掉，避免留下空气泡
        if (!assistant.content) {
          this.messages.splice(this.messages.indexOf(assistant), 1)
        }
        cards.forEach((card) => this.push(card))
        if (cards.some((card) => card.cardType === 'result')) this.$emit('dispatched')
        this.sending = false
        this.choosing = false
        this.currentJobId = null
        this.jobStage = ''
        this.loadConversations()
        this.refreshStates()
      }
    },

    // ---------- 报表选择 ----------
    /** 选择卡片上选定报表后由服务端直接生成预览（不经过模型，服务端重新按权限校验） */
    async chooseReports(m, reportIds) {
      if (this.busy || !reportIds.length) return
      this.choosing = true
      try {
        let job = await startPreviewJob({
          conversationId: this.activeId,
          reportIds,
          companyCode: m.payload.companyCode,
          excludeDocNos: m.payload.excludeDocNos,
          scopeMode: m.payload.scopeMode
        })
        this.currentJobId = job.id
        this.rememberPendingJob(job.id)
        job = await this.pollPreviewJob(job.id)
        if (job.status === 'SUCCEEDED') {
          const preview = await fetchPreview(job.previewId)
          const names = preview.byReport.map((b) => b.reportName).join('、')
          this.$set(m, 'chosen', names)
          this.push({ role: 'user', content: `（选择报表）${names}` })
          this.clearSelection()
          this.push({ role: 'card', cardType: 'preview', payload: preview, status: preview.status, statusMessage: null })
          this.forgetPendingJob(job.id)
        } else {
          this.$message.warning(job.message || '没有找到可查询的报表')
        }
      } catch (e) {
        /* 失败原因已由请求拦截器提示 */
      } finally {
        this.choosing = false
        this.currentJobId = null
        this.jobStage = ''
        this.refreshStates()
      }
    },
    async cancelCurrentJob() {
      if (this.currentJobId) await cancelPreviewJob(this.currentJobId)
    },

    // ---------- 待确认清单 ----------
    async confirmPlan(m) {
      if (this.busy || m.status !== 'PENDING') return
      const planId = m.payload.planId
      this.executingPlanId = planId
      try {
        const result = await confirmPlan(planId)
        this.planRefreshVersion++
        this.push({ role: 'card', cardType: 'result', payload: result })
        this.clearSelection()
        this.$emit('dispatched')
      } catch (e) {
        /* 失败原因已由请求拦截器提示；卡片状态以服务端为准 */
      } finally {
        this.executingPlanId = null
        this.refreshStates()
      }
    },
    async retryFailed(m) {
      if (this.busy || !m.payload.planId) return
      this.executingPlanId = m.payload.planId
      try {
        const result = await retryFailedPlan(m.payload.planId)
        this.planRefreshVersion++
        this.push({ role: 'card', cardType: 'result', payload: result })
        this.$emit('dispatched')
      } catch (e) {
        /* 失败原因由请求拦截器提示；未知结果需要人工核对。 */
      } finally {
        this.executingPlanId = null
        this.refreshStates()
      }
    },
    async reconcilePlanCard(m) {
      if (this.busy || !m.payload.planId) return
      this.executingPlanId = m.payload.planId
      try {
        const result = await reconcilePlan(m.payload.planId)
        this.planRefreshVersion++
        this.push({ role: 'card', cardType: 'result', payload: result })
      } catch (e) {
        /* 外部结果仍未知时保持待核对状态，禁止重发。 */
      } finally {
        this.executingPlanId = null
        this.refreshStates()
      }
    },
    async cancelPlanCard(m) {
      if (this.busy) return
      try {
        await cancelPlan(m.payload.planId)
      } catch (e) {
        /* 卡片状态以服务端为准 */
      }
      this.refreshStates()
    },

    // ---------- 工具 ----------
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.scroll
        if (el) el.scrollTop = el.scrollHeight
      })
    },
    formatTime(t) {
      if (!t) return ''
      return String(t).replace('T', ' ').slice(0, 16)
    }
  }
}
</script>

<style scoped>
.agent-layout {
  display: flex;
  height: 100%;
}

.conv-pane {
  width: 230px;
  border-right: 1px solid #e6e6e6;
  display: flex;
  flex-direction: column;
  background: #fafafa;
}

.conv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-weight: 600;
  border-bottom: 1px solid #e6e6e6;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
}

.conv-item {
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f0f0f0;
}

.conv-item:hover {
  background: #f0f5ff;
}

.conv-item.active {
  background: #e6f0ff;
}

.conv-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.conv-actions i {
  margin-left: 6px;
  cursor: pointer;
}

.conv-actions i:hover {
  color: #409eff;
}

.conv-empty {
  padding: 20px;
  text-align: center;
  color: #909399;
  font-size: 12px;
}

.chat-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid #e6e6e6;
}

.chat-title {
  font-weight: 600;
  font-size: 15px;
}

.chat-model {
  font-size: 12px;
  color: #909399;
}

.chat-close {
  margin-left: auto;
  font-size: 18px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f7fa;
}

.chat-welcome {
  color: #606266;
  font-size: 13px;
}

.chat-welcome .quick {
  margin: 4px 8px 4px 0;
  cursor: pointer;
}

.msg {
  margin-bottom: 12px;
  display: flex;
}

.msg.user {
  justify-content: flex-end;
}

.msg.card {
  justify-content: stretch;
}

.bubble {
  max-width: 80%;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.user {
  background: #409eff;
  color: #fff;
}

.bubble.assistant {
  background: #fff;
  border: 1px solid #ebeef5;
  max-width: 88%;
  white-space: normal;
}

.bubble.assistant.error {
  border-color: #f56c6c;
  color: #f56c6c;
}

/* 等待模型返回时的动画 */
.typing {
  display: inline-flex;
  align-items: center;
  padding: 2px 0;
}

.typing i {
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: #c0c4cc;
  animation: typing 1.2s infinite ease-in-out;
}

.typing i:nth-child(2) {
  animation-delay: 0.2s;
}

.typing i:nth-child(3) {
  animation-delay: 0.4s;
}

.typing-text {
  margin-left: 4px;
  font-size: 12px;
  color: #909399;
}

/* 流式输出中：在最后一段文字末尾闪烁光标 */
.bubble.assistant.streaming >>> .markdown > *:last-child::after {
  content: '▍';
  margin-left: 2px;
  animation: blink 1s infinite;
}

/* Markdown 渲染出来的内容是 v-html 插入的，样式要用深度选择器 */
.bubble.assistant >>> .markdown p {
  margin: 0 0 6px;
}

.bubble.assistant >>> .markdown > *:last-child {
  margin-bottom: 0;
}

.bubble.assistant >>> .markdown ul,
.bubble.assistant >>> .markdown ol {
  margin: 4px 0 6px;
  padding-left: 20px;
}

.bubble.assistant >>> .markdown li {
  margin: 2px 0;
}

.bubble.assistant >>> .markdown code {
  background: #f2f3f5;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.bubble.assistant >>> .markdown pre {
  margin: 4px 0 8px;
  padding: 8px 10px;
  background: #f6f8fa;
  border-radius: 4px;
  overflow-x: auto;
}

.bubble.assistant >>> .markdown pre code {
  background: none;
  padding: 0;
}

.bubble.assistant >>> .markdown table {
  margin: 4px 0 8px;
  border-collapse: collapse;
  font-size: 12px;
}

.bubble.assistant >>> .markdown th,
.bubble.assistant >>> .markdown td {
  padding: 4px 8px;
  border: 1px solid #e4e7ed;
  text-align: left;
}

.bubble.assistant >>> .markdown th {
  background: #f5f7fa;
  font-weight: 600;
}

.bubble.assistant >>> .markdown blockquote {
  margin: 4px 0;
  padding: 2px 10px;
  color: #909399;
  border-left: 3px solid #dcdfe6;
}

.bubble.assistant >>> .markdown h3,
.bubble.assistant >>> .markdown h4,
.bubble.assistant >>> .markdown h5 {
  margin: 6px 0 4px;
  font-size: 13px;
}

.bubble.assistant >>> .markdown hr {
  margin: 8px 0;
  border: none;
  border-top: 1px solid #ebeef5;
}

.bubble.assistant >>> .markdown a {
  color: #409eff;
}

@keyframes typing {
  0%,
  80%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.chat-input {
  border-top: 1px solid #e6e6e6;
  padding: 10px 16px;
  background: #fff;
}

.chat-input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}

.chat-input-actions .hint {
  font-size: 12px;
  color: #909399;
}
</style>
