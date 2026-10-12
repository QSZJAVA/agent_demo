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
            role="button"
            tabindex="0"
            @click="openConversation(c.id)"
            @keydown.enter.prevent="openConversation(c.id)"
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
          <el-button v-if="hasMoreConversations" type="text" :loading="convLoading" @click="loadConversations(true)">加载更多会话</el-button>
        </div>
      </div>

      <!-- 右侧：对话 -->
      <div class="chat-pane">
        <div class="chat-head">
          <span class="chat-title">业务助手</span>
          <span class="chat-model">模型：{{ modelName }}</span>
          <el-button type="text" icon="el-icon-close" class="chat-close" @click="$emit('update:visible', false)" />
        </div>

        <div ref="scroll" class="chat-messages">
          <el-button v-if="hasOlderMessages" type="text" :loading="historyLoading" @click="loadOlderMessages">加载更早消息</el-button>
          <div v-if="!messages.length" class="chat-welcome">
            <p>你好，我可以查报表、解答业务知识、分析查询结果，也可以帮你准备派单：</p>
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
                :busy="busy || selectionRestoreFailed"
                :server-exclusions="uiPreviewId === m.payload.previewId ? uiExcludes : []"
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
                @resume="resumeDispatch(m)"
              />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'result'">
              <result-card :payload="m.payload" :busy="busy" @retry="retryFailed(m)" />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'business_query'">
              <business-query-card :payload="m.payload" :busy="busy" :latest="isLatestBusinessQuery(m)"
                @edit="openQueryEditor(m)"
                @workspace="openQueryWorkspace(m, 1)"
                @page="openQueryWorkspace(m, $event)"
                @detail="send('查看工单 ' + $event + ' 的进度和处理总结')" />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'analysis_task'">
              <analysis-task-card :payload="m.payload" :card-state="m.cardState || m.status" :active="visible" @open="openAnalysisRun" />
            </template>
            <template v-else-if="m.role === 'card' && m.cardType === 'semantic_clarification'">
              <section class="clarification-card"><p>{{ m.payload.question }}</p><el-button v-for="choice in m.payload.choices" :key="choice.value" size="small" :disabled="busy || !isLatestClarification(m)" @click="answerClarification(m, choice)">{{ choice.label }}</el-button><p v-if="!isLatestClarification(m)" class="hint">历史澄清，请按最新问题继续。</p></section>
            </template>
          </div>
        </div>

        <div class="chat-input">
          <div v-if="chatPendingNotice || chatPendingError || chatPending" class="turn-recovery" role="status">
            <p>{{ chatPendingError || chatPendingNotice || '本次提交结果尚未确认，正在核对原请求。' }}</p>
            <el-button v-if="chatCanReconcile" size="small" :loading="sending" :disabled="chatRecovering" @click="reconcileChatTurn">核对本次提交</el-button>
            <el-button v-if="chatPending || chatPendingError" type="text" size="small" :loading="chatRecovering" :disabled="sending" @click="initializeChatRecovery">重新读取状态</el-button>
            <el-button v-if="chatUnavailable && chatPending" type="text" size="small" :disabled="sending || chatRecovering" @click="finishUnavailableTurn">结束本页核对</el-button>
            <p v-if="chatUnavailable && chatPending" class="hint">结束本页核对会保留服务端原请求及任务；之后输入新的完整问题才会开始新请求。</p>
          </div>
          <div v-if="selectionRestoreFailed" class="job-progress" role="alert">
            勾选状态未能保存或恢复，重新读取成功后才能继续操作。
            <el-button type="text" size="mini" :loading="openingHistory" @click="retrySelectionRestore">重新读取勾选</el-button>
          </div>
          <div v-if="choosing" class="job-progress">{{ jobStage }}
            <el-button type="text" size="mini" @click="cancelCurrentJob">取消查询</el-button>
          </div>
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）"
            :disabled="busy"
            :maxlength="2000"
            @keydown.native.enter.exact.prevent="send()"
          />
          <div class="chat-input-actions">
            <span class="hint">
              <template v-if="uiExcludes.length">已在表格中取消勾选 {{ uiExcludes.length }} 条，派单时会自动排除</template>
              <template v-else>派单前会先生成待确认清单，点击"确认派单"才会真正执行</template>
            </span>
            <el-button type="primary" size="small" :loading="sending" :disabled="busy || selectionRestoreFailed" @click="send()">发送</el-button>
          </div>
        </div>
      </div>
    </div>
    <query-condition-editor v-if="queryEditTarget" :key="queryEditTarget.payload.interpretation.queryRef" :payload="queryEditTarget.payload" :busy="busy" @close="queryEditTarget = null" @submit="submitQueryEdit" />
  </el-drawer>
</template>

<script>
/**
 * 会话及结构化业务卡片交互；服务端语义状态决定查询范围，卡片动作绑定其来源预览或清单。
 * 会话切换、组件销毁与登录变化后忽略旧请求；SSE和任务中断时读取已保存状态，再决定允许的用户动作。
 */
import PreviewCard from './PreviewCard.vue'
import PlanCard from './PlanCard.vue'
import ResultCard from './ResultCard.vue'
import ReportChoiceCard from './ReportChoiceCard.vue'
import BusinessQueryCard from './BusinessQueryCard.vue'
import QueryConditionEditor from './QueryConditionEditor.vue'
import AnalysisTaskCard from './AnalysisTaskCard.vue'
import { renderMarkdown } from '../../utils/markdown'
import { getCurrentUserId, getSessionToken } from '../../auth'
import { createChatPendingStore } from '../../utils/chatPending'
import { analysisTaskReference, readChatReceipt, chatTurnSettled } from '../../utils/chatReceipt'
import { resumePlanAction } from '../../api/dispatchJob'
import {
  cancelPlan,
  confirmPlan,
  createPlan,
  retryFailedPlan,
  reconcilePlan,
  startPreviewJob,
  fetchPreviewJob,
  fetchLatestPreviewJob,
  fetchPreview,
  cancelPreviewJob,
  deleteConversation,
  fetchCardStates,
  fetchDialogueSelection,
  saveDialogueSelection,
  fetchConversations,
  fetchMessages,
  fetchChatTurn,
  fetchModel,
  renameConversation,
  streamChat
} from '../../api/agent'

let seq = 0

export default {
  name: 'AgentChat',
  components: { PreviewCard, PlanCard, ResultCard, ReportChoiceCard, BusinessQueryCard, QueryConditionEditor, AnalysisTaskCard },
  props: {
    visible: { type: Boolean, default: false }
  },
  data() {
    return {
      queryEditTarget: null,
      sessionUserId: getCurrentUserId(),
      sessionToken: getSessionToken(),
      chatPending: null,
      chatReceipt: null,
      chatRequestId: null,
      chatPendingNotice: '',
      chatPendingError: '',
      chatReady: false,
      chatRecovering: false,
      chatNotFound: false,
      chatUnavailable: false,
      turnVersion: 0,
      disposed: false,
      abortStream: null,
      stopPolling: null,
      conversationPage: 1,
      hasMoreConversations: false,
      hasOlderMessages: false,
      historyLoading: false,
      openingHistory: false,
      selectionRestoreFailed: false,
      savingSelection: false,
      historyVersion: 0,
      oldestMessageId: null,
      modelName: '',
      conversations: [],
      convLoading: false,
      activeId: null,
      messages: [],
      input: '',
      sending: false,
      creatingPlan: false,
      choosing: false,
      currentJobId: null,
      jobStage: '',
      // 取消勾选的稳定记录键及其来源预览。
      uiExcludes: [],
      uiPreviewId: null,
      executingPlanId: null,
      planRefreshVersion: 0,
      quickQuestions: ['查询销售报表数据', 'A公司销售金额的业务口径是什么', '按产品汇总A公司上月销售额，取金额最高的两个产品', '查询派单记录', '查一下我有哪些可以派单']
    }
  },
  computed: {
    busy() {
      return this.sending || this.creatingPlan || this.choosing || this.openingHistory || this.savingSelection || !!this.executingPlanId || !this.chatReady || this.chatRecovering || !!this.chatPending || !!this.chatPendingError
    },
    chatCanReconcile() { return !!this.chatPending && !this.chatUnavailable && (this.chatNotFound || (this.chatReceipt?.historyReady === true && ['RECEIVED', 'READY'].includes(this.chatReceipt.status) && this.chatReceipt.canReconcile)) }
  },
  watch: {
    activeId() { this.queryEditTarget = null },
    visible(v) {
      if (!v) { this.queryEditTarget = null; this.stopTurnPolling() }
      if (v) {
        this.loadConversations()
        if (!this.modelName) {
          fetchModel().then((d) => { if (this.isCurrentSession()) this.modelName = d.model }).catch(() => { if (this.isCurrentSession()) this.modelName = '未知' })
        }
        this.refreshStates()
        this.initializeChatRecovery().then(() => { if (this.isCurrentSession() && !this.chatPending) this.resumePendingJob() })
      }
    }
  },
  beforeDestroy() {
    this.disposed = true
    this.turnVersion++
    this.stopTurnPolling()
    if (this.abortStream) this.abortStream()
    if (this.stopPolling) this.stopPolling()
    this.messages = []
    this.activeId = null
    this.input = ''
    this.chatPending = null
    this.clearSelection()
  },
  methods: {
    pendingStore() { if (!this._chatPendingStore) this._chatPendingStore = createChatPendingStore({ getIdentity: () => ({ userId: getCurrentUserId(), token: getSessionToken() }) }); return this._chatPendingStore },
    currentTurn(version) { return this.isCurrentSession() && version === this.turnVersion },
    stopTurnPolling() { if (this._turnTimer != null) clearTimeout(this._turnTimer); this._turnTimer = null },
    /** 只轮询原轮次；处理或历史投影未完成时不重发POST，抽屉隐藏后暂停，重新打开再核对。 */
    scheduleTurn(pending, version) {
      this.stopTurnPolling()
      if (!this.visible || !this.currentTurn(version) || this.chatPending?.key !== pending.key || !this.chatReceipt || (this.chatReceipt.status !== 'PLANNING' && this.chatReceipt.historyReady)) return
      this._turnTimer = setTimeout(() => this.lookupChatTurn(pending, version, false), this.chatReceipt.historyReady ? 1500 : 3000)
    },
    /** 发送前与重新打开抽屉时先恢复当前身份记录；读取不会创建会话、触发模型或改变原始载荷。 */
    async initializeChatRecovery() {
      if (!this.isCurrentSession() || this.sending || this.chatRecovering) return
      const version = ++this.turnVersion; this.stopTurnPolling(); this.chatRecovering = true; this.chatReady = false; this.chatPendingError = ''
      try {
        const store = this.pendingStore(), saved = await store.read()
        await store.checkSession(); if (!this.currentTurn(version)) return
        this.chatPending = saved.pending; this.chatPendingNotice = saved.notice; this.chatReceipt = null; this.chatRequestId = null; this.chatNotFound = false; this.chatUnavailable = false
        if (saved.pending) await this.lookupChatTurn(saved.pending, version, true)
      } catch (error) {
        if (!this.currentTurn(version)) return
        this.chatPendingError = error.message || '无法恢复待核对请求，请重新读取状态。'
      } finally { if (this.currentTurn(version)) { this.chatRecovering = false; this.chatReady = true } }
    },
    /** 从准确客户端键取得回执；404允许用户显式核对，权限或期限失败隐藏旧内容并停止自动恢复。 */
    async lookupChatTurn(pending, version, history) {
      if (!this.currentTurn(version) || this.chatPending?.key !== pending.key) return null
      try {
        const value = await fetchChatTurn(pending.key)
        await this.pendingStore().checkSession()
        if (!this.currentTurn(version)) return null
        return await this.acceptChatReceipt(value, pending, version, history)
      } catch (error) {
        if (!this.currentTurn(version)) return null
        this.stopTurnPolling(); this.chatReceipt = null; this.chatNotFound = false
        if (error.serverResponse === true && error.code === 404) {
          // 404也可能用于隐藏当前无权读取的轮次；移除旧展示，显式原key核对仍由服务器检查身份和防重记录。
          this.messages = []; this.input = ''; this.clearSelection()
          this.chatNotFound = true; this.chatUnavailable = false; this.chatPendingError = ''; this.chatPendingNotice = '本次请求尚未找到或当前不可读取。可点击“核对本次提交”，沿用原问题和请求号核对。'
        } else if (error.serverResponse === true && [401, 403, 410].includes(error.code)) {
          this.chatUnavailable = true; this.messages = []; this.input = ''; this.activeId = null; this.clearSelection()
          this.chatPendingError = '本次请求当前不可读取，已隐藏原内容。请核对登录、访问范围或请求期限后重新读取。'
        } else this.chatPendingError = '原请求结果尚未完成核对，请重新读取状态；不会自动重新提交。'
        return null
      }
    },
    /**
     * 回执先核对归属；执行终态与历史投影分别确认，投影未完成时保留原展示和key，只读等待。
     * 返回回执及本次historyRestored标记，供流结束时移除已被持久化历史替换的临时卡片；不按文本去重。
     */
    async acceptChatReceipt(value, pending, version, history) {
      const receipt = readChatReceipt(value, pending)
      if (!this.currentTurn(version) || this.chatPending?.key !== pending.key) return null
      if (this.chatRequestId && this.chatRequestId !== receipt.requestId) throw new Error('回执与已收到的会话事件不属于同一轮次')
      if (this.chatReceipt?.clientKey === pending.key && this.chatReceipt.requestId !== receipt.requestId) throw new Error('原请求的服务端轮次发生变化')
      const previous = this.chatReceipt, settled = chatTurnSettled(receipt.status)
      const needsHistory = receipt.historyReady && (history || previous?.historyReady === false || (previous?.status === 'PLANNING' && settled))
      const reason = receipt.message || (['INTERRUPTED', 'FAILED'].includes(receipt.status) ? '原轮次已结束。请核对原因；重新发送会创建新的请求。' : (receipt.status === 'SUBMITTED' && !receipt.analysisTask ? '原分析任务已不可读取，请发送新的完整问题。' : ''))
      let historyRestored = false, historyNotice = ''
      this.chatReceipt = receipt; this.chatNotFound = false; this.chatUnavailable = false; this.chatPendingError = ''
      this.chatPendingNotice = !receipt.historyReady ? [reason, '原请求的历史消息正在同步，正在只读核对；请勿重复提交。'].filter(Boolean).join(' ') : (reason || (receipt.status === 'PLANNING' ? '原请求仍在处理中，正在读取进度。' : '原请求已保存，可点击“核对本次提交”继续核对。'))
      if (needsHistory) {
        try { historyRestored = await this.openConversation(receipt.conversationId, true, version) === true }
        catch (_) { historyNotice = '原轮次已找到，历史消息暂时无法读取；可稍后重新打开会话。' }
      } else {
        if (this.activeId && this.activeId !== receipt.conversationId) { this.messages = []; this.clearSelection() }
        this.activeId = receipt.conversationId
      }
      if (!this.currentTurn(version)) return null
      // 已提交但当前不可读的权威回执撤下本轮旧引用；其他轮次和服务端中性不可用卡片不受影响。
      if (receipt.status === 'SUBMITTED' && !receipt.analysisTask) this.messages = this.messages.filter(message => message.cardType !== 'analysis_task' || (message.requestId !== receipt.requestId && message.payload?.requestId !== receipt.requestId))
      if (receipt.analysisTask) this.addAnalysisTask(receipt.analysisTask)
      if (settled && receipt.historyReady) {
        const cleared = await this.pendingStore().clear(pending)
        if (!this.currentTurn(version)) return null
        if (!cleared) throw new Error('保存记录已变化，请重新恢复')
        this.chatPending = null; this.stopTurnPolling()
        // 任务不可读、依据失效等具体原因优先于历史加载提示，不能随终态清理被抹掉。
        this.chatPendingNotice = reason || historyNotice
      } else {
        if (historyNotice && !reason) this.chatPendingNotice = historyNotice
        this.scheduleTurn(pending, version)
      }
      return { ...receipt, historyRestored }
    },
    addAnalysisTask(value) {
      const payload = analysisTaskReference(value)
      const existing = this.messages.find(message => message.cardType === 'analysis_task' && message.payload?.runId === payload.runId)
      if (existing) { if (existing.payload.requestId !== payload.requestId) throw new Error('任务引用不属于同一轮次'); return }
      this.push({ role: 'card', cardType: 'analysis_task', requestId: payload.requestId, payload })
    },
    openAnalysisRun(id) { if (!this.isCurrentSession()) return; this.$emit('update:visible', false); this.$router.push({ path: '/analysis', query: { run: id } }) },
    /** 只有明确可继续的回执或404核对入口可以重放；同一payload和key先重验本地保存，不重建请求。 */
    async reconcileChatTurn() {
      if (!this.isCurrentSession() || !this.chatCanReconcile || this.sending || this.chatRecovering || this.choosing || this.openingHistory) return
      const pending = this.chatPending, version = ++this.turnVersion
      this.sending = true; this.stopTurnPolling(); this.chatPendingError = ''
      try { await this.pendingStore().verify(pending); if (this.currentTurn(version)) await this.transmitChatTurn(pending, version, true) }
      catch (error) { if (this.currentTurn(version)) this.chatPendingError = error.message || '原请求尚未完成核对。' }
      finally { if (this.currentTurn(version)) this.sending = false }
    },
    /** 仅在明确不可访问或到期后由用户结束本页核对；不删除服务端任务、不自动新建会话或重发原问题。 */
    async finishUnavailableTurn() {
      if (!this.isCurrentSession() || !this.chatUnavailable || !this.chatPending || this.sending || this.chatRecovering) return
      const pending = this.chatPending, version = ++this.turnVersion; this.chatRecovering = true; this.stopTurnPolling()
      try {
        const cleared = await this.pendingStore().clear(pending)
        if (!this.currentTurn(version)) return
        if (!cleared) throw new Error('待核对记录已经变化，请先重新读取状态。')
        this.chatPending = null; this.chatReceipt = null; this.chatRequestId = null; this.chatUnavailable = false; this.chatNotFound = false; this.chatPendingError = ''; this.messages = []; this.input = ''; this.activeId = null; this.clearSelection()
        this.chatPendingNotice = '本页核对已结束，服务端原请求及任务保留。输入新的完整问题才会开始新请求。'
      } catch (error) { if (this.currentTurn(version)) this.chatPendingError = error.message || '无法结束本页核对，请重新读取状态。' }
      finally { if (this.currentTurn(version)) this.chatRecovering = false }
    },
    /** 只编辑当前卡片副本；服务器仍检查会话的当前查询引用，阻止其他标签页的过期提交。 */
    openQueryEditor(message) { if (!this.busy && this.isLatestBusinessQuery(message) && message.payload.interpretation) this.queryEditTarget = message },
    submitQueryEdit(edit) { this.queryEditTarget = null; return this.send('按已编辑的完整条件重新查询', { queryEdit: edit }) },
    isLatestClarification(message) { const index = this.messages.indexOf(message); return index >= 0 && !this.messages.slice(index + 1).some(item => item.role === 'user') },
    answerClarification(message, choice) { if (this.busy || !this.isLatestClarification(message)) return; return this.send('针对刚才的问题，我选择：' + choice.label, { clarificationReply: { clarificationRef: message.payload.clarificationRef, value: choice.value } }) },
    /** 历史卡片保留原查询时间与结果，翻页只允许最新业务查询，避免按钮误操作其他查询范围。 */
    isLatestBusinessQuery(message) {
      const queries = this.messages.filter(m => m.role === 'card' && m.cardType === 'business_query')
      // 新一轮即使澄清或失败也取代旧操作入口；后端另核对最新成功查询引用和当前焦点。
      return queries.length > 0 && queries[queries.length - 1] === message && !this.messages.slice(this.messages.indexOf(message) + 1).some(m => m.role === 'user')
    },
    /** 明确按钮分页进入固定快照工作区，不产生新的自然语言请求，也不改写原会话中的记录引用。 */
    openQueryWorkspace(message, page) {
      if (!this.isCurrentSession() || !message.payload.snapshot) return
      this.$emit('update:visible', false)
      this.$router.push({ path: '/query-workspace', query: { run: message.payload.snapshot.id, page: String(page) } })
    },
    isCurrentSession() {
      if (this.disposed) return false
      if (this.sessionUserId === getCurrentUserId() && this.sessionToken === getSessionToken()) return true
      // 同一用户重新登录也属于新会话；旧SSE、轮次GET和卡片不能把其内容写进新页面。
      this.disposed = true; this.turnVersion++; this.stopTurnPolling()
      if (this.abortStream) this.abortStream()
      if (this.stopPolling) this.stopPolling()
      this.messages = []; this.input = ''; this.activeId = null; this.chatPending = null; this.chatReceipt = null; this.chatReady = false; this.clearSelection()
      this.chatPendingError = '登录会话已变化，请重新进入页面。'
      return false
    },
    pendingJobKey() {
      return `agent-preview-job:${this.sessionUserId}`
    },
    rememberPendingJob(jobId) {
      if (!this.isCurrentSession()) return
      sessionStorage.setItem(this.pendingJobKey(), JSON.stringify({ jobId, conversationId: this.activeId }))
    },
    forgetPendingJob(jobId) {
      if (!this.isCurrentSession()) return
      const stored = sessionStorage.getItem(this.pendingJobKey())
      if (stored && JSON.parse(stored).jobId === jobId) sessionStorage.removeItem(this.pendingJobKey())
    },
    async pollPreviewJob(jobId) {
      if (!this.isCurrentSession()) throw new Error('会话已关闭')
      this.currentJobId = jobId
      this.choosing = true
      let job = await fetchPreviewJob(jobId)
      if (!this.isCurrentSession()) throw new Error('会话已关闭')
      while (job.status === 'QUEUED' || job.status === 'RUNNING') {
        this.jobStage = job.status === 'QUEUED' ? '查询排队中…'
          : `正在查询可派单记录…已扫描 ${job.scannedRows || 0} 条`
        await new Promise((resolve) => {
          const timer = setTimeout(resolve, 1000)
          this.stopPolling = () => { clearTimeout(timer); resolve() }
        })
        this.stopPolling = null
        if (!this.isCurrentSession()) throw new Error('会话已关闭')
        job = await fetchPreviewJob(jobId)
        if (!this.isCurrentSession()) throw new Error('会话已关闭')
      }
      if (job.status !== 'SUCCEEDED') this.forgetPendingJob(jobId)
      return job
    },
    /** 刷新后按会话恢复已存在的预览任务；完成窗口也从数据库查询，防止SSE丢失最后一帧导致卡片遗漏。 */
    async resumePendingJob(conversationId = null) {
      if (!this.isCurrentSession()) return
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
        if (!this.isCurrentSession()) return
        const latest = await fetchLatestPreviewJob(pending.conversationId)
        if (!this.isCurrentSession()) return
        if (!latest) { this.forgetPendingJob(null); return }
        pending.jobId = latest.id
        this.rememberPendingJob(pending.jobId)
        const job = await this.pollPreviewJob(pending.jobId)
        if (!this.isCurrentSession()) return
        if (job.status === 'SUCCEEDED' && !this.messages.some((m) => m.cardType === 'preview' &&
            (m.payload?.previewId) === job.previewId)) {
          const preview = await fetchPreview(job.previewId)
          if (!this.isCurrentSession()) return
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
        if (!this.isCurrentSession()) return
        this.choosing = false
        this.currentJobId = null
        this.jobStage = ''
        this.refreshStates()
      }
    },
    // ---------- 会话列表 ----------
    async loadConversations(more = false) {
      if (!this.isCurrentSession() || this.convLoading) return
      const page = more ? this.conversationPage + 1 : 1
      this.convLoading = true
      try {
        const list = await fetchConversations(page, 50)
        if (!this.isCurrentSession()) return
        const combined = more ? [...this.conversations, ...list] : list
        this.conversations = [...new Map(combined.map((c) => [c.id, c])).values()]
        this.conversationPage = page
        this.hasMoreConversations = list.length === 50
      } finally {
        this.convLoading = false
      }
    },
    newConversation() {
      if (this.busy) return
      this.turnVersion++; this.stopTurnPolling(); this.chatReceipt = null; this.chatPendingNotice = ''
      this.activeId = null
      this.historyVersion++
      this.hasOlderMessages = false
      this.messages = []
      this.clearSelection()
      this.selectionRestoreFailed = false
      this.input = ''
    },
    /** 读取并按持久化消息标识替换会话；恢复入口额外绑定轮次代次，阻止迟到历史覆盖新页面。 */
    async openConversation(id, force = false, expectedTurn = null) {
      if (!this.isCurrentSession() || (expectedTurn !== null && !this.currentTurn(expectedTurn))) return
      if (this.busy && !force) return
      this.activeId = id
      const version = ++this.historyVersion
      this.messages = []
      this.hasOlderMessages = false
      this.clearSelection()
      this.selectionRestoreFailed = false
      this.openingHistory = true
      let list
      try {
        list = await fetchMessages(id)
      } finally {
        if (version === this.historyVersion) this.openingHistory = false
      }
      if (!this.isCurrentSession() || version !== this.historyVersion || this.activeId !== id || (expectedTurn !== null && !this.currentTurn(expectedTurn))) return
      this.hasOlderMessages = list.length === 100
      this.oldestMessageId = list[0]?.id || null
      // 卡片状态由服务端随历史消息一起返回：刷新页面、换设备看到的都一样
      this.messages = this.textBeforeCards(list).map((m) => ({
        key: `h-${m.id}`,
        id: m.id,
        requestId: m.requestId,
        role: m.role,
        content: m.content,
        cardType: m.cardType,
        payload: m.payload,
        previewId: m.previewId,
        planId: m.planId,
        status: m.status,
        cardState: m.cardState,
        statusMessage: m.statusMessage,
        chosen: ''
      }))
      this.scrollToBottom()
      this.openingHistory = true
      try { await this.restoreSelection(id, version, expectedTurn) }
      finally { if (version === this.historyVersion) this.openingHistory = false }
      if (!this.isCurrentSession() || version !== this.historyVersion || this.activeId !== id || (expectedTurn !== null && !this.currentTurn(expectedTurn))) return
      if (!force) await this.resumePendingJob(id)
      return true
    },
    async loadOlderMessages() {
      if (!this.isCurrentSession() || this.historyLoading || !this.activeId || !this.hasOlderMessages) return
      const id = this.activeId
      const version = this.historyVersion
      const beforeId = this.oldestMessageId
      if (beforeId == null) return
      this.historyLoading = true
      try {
        const list = await fetchMessages(id, beforeId, 100)
        if (!this.isCurrentSession() || version !== this.historyVersion) return
        const existing = new Set(this.messages.filter((m) => m.id != null).map((m) => String(m.id)))
        const older = this.textBeforeCards(list).filter((m) => !existing.has(String(m.id)))
          .map((m) => ({ ...m, key: `h-${m.id}`, chosen: '' }))
        const scroll = this.$refs.scroll
        const height = scroll?.scrollHeight || 0
        const top = scroll?.scrollTop || 0
        this.messages = [...older, ...this.messages]
        this.hasOlderMessages = list.length === 100
        if (list.length) this.oldestMessageId = list[0].id
        this.$nextTick(() => { if (scroll) scroll.scrollTop = top + scroll.scrollHeight - height })
      } finally { this.historyLoading = false }
    },
    async rename(c) {
      if (!this.isCurrentSession()) return
      try {
        const { value } = await this.$prompt('新的会话标题', '改名', { inputValue: c.title, inputPattern: /\S+/, inputErrorMessage: '标题不能为空' })
        if (!this.isCurrentSession()) return
        await renameConversation(c.id, value)
        c.title = value
      } catch (e) {
        /* cancelled */
      }
    },
    async remove(c) {
      if (!this.isCurrentSession()) return
      try {
        await this.$confirm(`删除会话"${c.title || '（未命名）'}"？`, '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      if (!this.isCurrentSession()) return
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
      if (!this.isCurrentSession()) return
      const id = this.activeId
      if (!id) return
      let states
      try {
        states = await fetchCardStates(id)
      } catch (e) {
        return
      }
      if (!this.isCurrentSession() || id !== this.activeId) return
      this.messages.forEach((m) => {
        if (m.role !== 'card' || !m.payload) return
        let state = null
        if (m.cardType === 'preview') state = states.previews[m.payload.previewId]
        else if (m.cardType === 'plan') state = states.plans[m.payload.planId]
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
    applySelection(data) {
      // 当前协议的损坏选择必须显式失败，不能恢复成全部选中。
      if (!data || !Array.isArray(data.excludedRecords) || data.excludedRecords.some(r => !r || typeof r.reportId !== 'string' || typeof r.recordId !== 'string')) {
        this.selectionRestoreFailed = true
        throw new Error('选择状态格式无效，请刷新重试')
      }
      this.uiPreviewId = data.previewId || null
      this.uiExcludes = data.excludedRecords
    },
    /** 读取服务器权威选择；校验会话及请求版本后才写入页面，避免迟到响应把新会话范围覆盖。 */
    async restoreSelection(id, version, expectedTurn = null) {
      try {
        const selection = await fetchDialogueSelection(id)
        if (!this.isCurrentSession() || this.activeId !== id || this.historyVersion !== version || (expectedTurn !== null && !this.currentTurn(expectedTurn))) return
        // Keep selection even when its card is expired or outside the current history page.
        // The server decides whether it can migrate to a refreshed preview.
        this.applySelection(selection)
        this.selectionRestoreFailed = false
      } catch (e) {
        if (this.isCurrentSession() && this.activeId === id && this.historyVersion === version && (expectedTurn === null || this.currentTurn(expectedTurn))) this.selectionRestoreFailed = true
      }
    },
    async retrySelectionRestore() {
      if (!this.isCurrentSession() || this.busy || !this.activeId) return
      const id = this.activeId, version = this.historyVersion
      this.openingHistory = true
      try { await this.restoreSelection(id, version) }
      finally { if (version === this.historyVersion) this.openingHistory = false }
    },

    // ---------- 对话 ----------
    push(msg) {
      if (!this.isCurrentSession()) return msg
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
    /** 手动选择立即持久化；保存期间阻止建单和新轮次，失败则暂停操作并要求重读，不以本地状态冒充已保存。 */
    async onPreviewSelection(m, excludedRecords) {
      if (!this.isCurrentSession() || this.busy || m.status !== 'ACTIVE' || this.selectionRestoreFailed || !this.activeId) return
      const id = this.activeId, version = this.historyVersion, previewId = m.payload.previewId
      const expectedExclusions = this.uiPreviewId === previewId ? this.uiExcludes.slice() : []
      this.savingSelection = true
      try {
        const saved = await saveDialogueSelection(id, { previewId, expectedExclusions, excludedRecords: excludedRecords.slice() })
        if (!this.isCurrentSession() || this.activeId !== id || this.historyVersion !== version) return
        this.applySelection(saved)
      } catch (e) {
        if (this.isCurrentSession() && this.activeId === id && this.historyVersion === version) this.selectionRestoreFailed = true
      } finally {
        this.savingSelection = false
      }
    },
    async dispatchSelected(m) {
      if (!this.isCurrentSession() || this.busy || this.selectionRestoreFailed || m.status !== 'ACTIVE' || !m.payload.previewId) return
      const previewId = m.payload.previewId
      const excludedRecords = this.uiPreviewId === previewId ? this.uiExcludes.slice() : []
      // 同一选择在网络重试时沿用键；用户改变选择则是一次新的建单操作。
      const selection = JSON.stringify(excludedRecords)
      if (!m.planRequest || m.planRequest.selection !== selection) {
        m.planRequest = { selection, key: Array.from(window.crypto.getRandomValues(new Uint32Array(4)),
          value => value.toString(16).padStart(8, '0')).join('') }
      }
      this.creatingPlan = true
      try {
        const plan = await createPlan({ conversationId: this.activeId, previewId, excludedRecords }, m.planRequest.key)
        if (!this.isCurrentSession()) return
        if (!this.messages.some(message => message.cardType === 'plan' && message.payload?.planId === plan.planId)) {
          this.push({ role: 'card', cardType: 'plan', payload: plan, status: plan.status, statusMessage: null })
        }
        // 收到确定响应后允许用户再次生成清单；丢失响应时保留键用于安全重放。
        m.planRequest = null
      } catch (e) {
        /* 请求拦截器展示原因；失效预览不会自动换成最新预览。 */
      } finally {
        this.creatingPlan = false
        this.refreshStates()
        this.loadConversations()
      }
    },
    /** 新轮次先保存并回读完整载荷；首帧丢失也保留稳定key，未知请求不允许被新的问题覆盖。 */
    async send(text, interaction = {}) {
      if (!this.isCurrentSession()) return
      const message = (text || this.input || '').trim()
      if (!message || this.busy || this.selectionRestoreFailed) return
      const version = ++this.turnVersion
      this.sending = true; this.chatPendingNotice = ''; this.chatPendingError = ''; this.chatReceipt = null; this.chatRequestId = null; this.chatNotFound = false; this.chatUnavailable = false
      try {
        const request = { conversationId: this.activeId || null, message, excludedRecords: this.uiExcludes.map(value => ({ ...value })), previewId: this.uiPreviewId || null, queryEdit: interaction.queryEdit ?? null, clarificationReply: interaction.clarificationReply ?? null }
        const pending = await this.pendingStore().create(request, 'chat-' + crypto.randomUUID())
        if (!this.currentTurn(version)) return
        this.chatPending = pending
        await this.pendingStore().verify(pending)
        if (!this.currentTurn(version)) return
        this.input = ''
        await this.transmitChatTurn(pending, version, false)
      } catch (error) {
        if (!this.currentTurn(version)) return
        if (error.pending) this.chatPending = error.pending
        this.chatPendingError = error.message || '本次尚未发送，请先重新检查待核对记录。'
      } finally { if (this.currentTurn(version)) this.sending = false }
    },
    /** 只发送已保存的请求；重放消费同一轮次回执，断流后仅GET核对，不自动重复调用模型或派单。 */
    async transmitChatTurn(pending, version, reconcile) {
      if (!this.currentTurn(version)) return
      const message = pending.request.message
      const userMessage = reconcile ? null : this.push({ role: 'user', content: message, clientKey: pending.key })
      // 本轮的结构化卡片先收集，等文字输出完再挂到文字下面，
      // 否则卡片会插在回答中间，看起来"卡片比回答先到"
      const cards = []
      let pendingJobId = null
      let returnedReceipt = null, requestId = null, replayed = false, receivedResult = false
      const assistant = this.push({ role: 'assistant', content: '', streaming: true, pending: true })
      const onEvent = (type, data) => {
        if (!this.currentTurn(version)) return
        if (['analysis_task', 'preview', 'preview_job', 'business_query', 'semantic_clarification', 'choice', 'plan', 'result'].includes(type) || (type === 'text' && typeof data.delta === 'string' && data.delta.length > 0)) receivedResult = true
        switch (type) {
          case 'conversation':
            if (typeof data.replayed !== 'boolean' || typeof data.requestId !== 'string' || /^[a-f0-9]{32}$/.exec(data.requestId)?.[0] !== data.requestId || (pending.request.conversationId !== null && data.conversationId !== pending.request.conversationId) || (requestId && requestId !== data.requestId)) throw new Error('会话事件与原请求不一致')
            requestId = data.requestId; this.chatRequestId = requestId; replayed = data.replayed
            if (userMessage) userMessage.requestId = requestId
            assistant.requestId = requestId
            if (this.activeId !== data.conversationId) {
              this.activeId = data.conversationId
            }
            // 先保存会话，即使下一个任务事件丢失也能从服务端发现任务。
            this.rememberPendingJob(null)
            break
          case 'turn_receipt':
            returnedReceipt = readChatReceipt(data, pending)
            if (requestId && returnedReceipt.requestId !== requestId) throw new Error('会话回执轮次不一致')
            break
          case 'analysis_task': {
            const payload = analysisTaskReference(data)
            if (requestId && payload.requestId !== requestId) throw new Error('分析任务轮次不一致')
            cards.push({ role: 'card', cardType: 'analysis_task', requestId: payload.requestId, payload })
            break
          }
          case 'text':
            assistant.pending = false
            assistant.content += data.delta
            this.scrollToBottom()
            break
          case 'preview':
            this.clearSelection()
            cards.push({ role: 'card', cardType: 'preview', payload: data, status: data.status, statusMessage: null })
            break
          case 'preview_job':
            pendingJobId = data.jobId
            this.currentJobId = data.jobId
            this.choosing = true
            this.jobStage = '查询排队中…'
            this.rememberPendingJob(data.jobId)
            break
          case 'selection':
            this.applySelection(data)
            break
          case 'business_query':
            cards.push({ role: 'card', cardType: 'business_query', payload: data })
            break
          case 'semantic_clarification':
            cards.push({ role: 'card', cardType: 'semantic_clarification', payload: data })
            break
          case 'choice':
            cards.push({ role: 'card', cardType: 'choice', payload: data, chosen: '' })
            break
          case 'plan':
            cards.push({ role: 'card', cardType: 'plan', payload: data, status: data.status, statusMessage: null })
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
        const { promise, abort } = streamChat(pending.request, onEvent, pending.key)
        this.abortStream = abort
        await promise
        if (!this.currentTurn(version)) return
        // replayed只表示接收时是否已有记录；并发执行者可能完成本轮而当前流只拿到回执。
        // 显式核对、缺少首尾回执或未收到业务结果时必须读历史；完整正常流保留已收到的内容。
        const history = reconcile || replayed || !returnedReceipt || !requestId || !receivedResult
        const receipt = returnedReceipt ? await this.acceptChatReceipt(returnedReceipt, pending, version, history) : await this.lookupChatTurn(pending, version, history)
        if (!this.currentTurn(version)) return
        if (receipt?.historyRestored) cards.length = 0
        if (!pendingJobId) this.forgetPendingJob(null)
        if (pendingJobId) {
          const job = await this.pollPreviewJob(pendingJobId)
          if (!this.currentTurn(version)) return
          if (job.status === 'SUCCEEDED') {
            const preview = await fetchPreview(job.previewId)
            if (!this.currentTurn(version)) return
            this.clearSelection()
            cards.push({ role: 'card', cardType: 'preview', payload: preview,
              status: preview.status, statusMessage: null })
            this.forgetPendingJob(pendingJobId)
          } else if (job.status !== 'CANCELLED') {
            this.$message.warning(job.message || '预览查询失败')
          }
        }
      } catch (e) {
        if (!this.currentTurn(version)) return
        if (e.serverResponse === true && [401, 403, 404, 410].includes(e.code)) {
          this.chatUnavailable = true; this.chatNotFound = false; this.messages = []; this.input = ''; this.activeId = null; this.clearSelection()
          this.chatPendingError = '本次请求当前不可继续，已隐藏原内容。请核对访问范围或请求期限后重新读取状态。'
          return
        }
        // 仅进入流前的结构性拒绝释放原记录；409、429、断流及传输错误可能已有轮次，继续按原key读取。
        if (e.serverResponse === true && [400, 422].includes(e.code)) {
          const cleared = await this.pendingStore().clear(pending)
          if (!this.currentTurn(version)) return
          if (!cleared) throw new Error('待核对记录已经变化，请重新读取状态。')
          this.chatPending = null; this.chatPendingNotice = '服务器已拒绝本次提交，请核对原因后修改问题。'; this.chatPendingError = ''
          assistant.pending = false; assistant.error = true; assistant.content = e.message
          return
        }
        const receipt = await this.lookupChatTurn(pending, version, true)
        if (!this.currentTurn(version)) return
        let recovered = !!receipt && chatTurnSettled(receipt.status)
        if (receipt?.historyRestored) cards.length = 0
        if (!pendingJobId && receipt?.historyReady && !receipt.analysisTask && chatTurnSettled(receipt.status) && this.activeId) {
          try {
            const latest = await fetchLatestPreviewJob(this.activeId)
            if (!this.currentTurn(version)) return
            if (latest && !this.messages.some((m) => m.cardType === 'preview' &&
                (m.payload?.previewId) === latest.previewId)) {
              pendingJobId = latest.id
              this.rememberPendingJob(pendingJobId)
            }
          } catch (discoveryError) { /* 保留会话编号，重开页面后重新发现任务。 */ }
        }
        if (pendingJobId) {
          try {
            const job = await this.pollPreviewJob(pendingJobId)
            if (!this.currentTurn(version)) return
            if (job.status === 'SUCCEEDED') {
              const preview = await fetchPreview(job.previewId)
              if (!this.currentTurn(version)) return
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
        assistant.error = !recovered && !receipt
        assistant.content = assistant.content || (receipt && !receipt.historyReady ? '' : (recovered ? '连接已中断，查询结果已恢复。' : (receipt ? '' : `请求失败：${e.message}`)))
      } finally {
        if (!this.currentTurn(version)) return
        this.abortStream = null
        assistant.pending = false
        assistant.streaming = false
        // 本轮只有卡片没有文字时，把等待占位删掉，避免留下空气泡
        if (!assistant.content) {
          const index = this.messages.indexOf(assistant)
          if (index >= 0) this.messages.splice(index, 1)
        }
        if (this.chatUnavailable || this.chatNotFound) cards.length = 0
        cards.forEach((card) => {
          if (card.cardType === 'analysis_task') {
            if (this.chatReceipt?.status === 'SUBMITTED' && !this.chatReceipt.analysisTask && this.chatReceipt.requestId === card.payload.requestId) return
            this.addAnalysisTask(card.payload)
          }
          else this.push({ ...card, requestId })
        })
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
      if (!this.isCurrentSession()) return
      if (this.busy || !reportIds.length) return
      this.choosing = true
      try {
        let job = await startPreviewJob({
          conversationId: this.activeId,
          reportIds,
          companyCode: m.payload.companyCode,
          scopeMode: m.payload.scopeMode
        })
        if (!this.isCurrentSession()) return
        this.currentJobId = job.id
        this.rememberPendingJob(job.id)
        job = await this.pollPreviewJob(job.id)
        if (!this.isCurrentSession()) return
        if (job.status === 'SUCCEEDED') {
          const preview = await fetchPreview(job.previewId)
          if (!this.isCurrentSession()) return
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
      if (!this.isCurrentSession()) return
      if (this.currentJobId) await cancelPreviewJob(this.currentJobId)
    },

    // ---------- 待确认清单 ----------
    /** 确认按钮提交稳定幂等任务，处理中禁止重复动作；完成后刷新卡片权威状态，业务结果由服务器提供。 */
    async confirmPlan(m) {
      if (!this.isCurrentSession()) return
      if (this.busy || m.status !== 'PENDING') return
      const planId = m.payload.planId
      this.executingPlanId = planId
      try {
        const result = await confirmPlan(planId, () => this.refreshStates())
        if (!this.isCurrentSession()) return
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
      if (!this.isCurrentSession()) return
      if (this.busy || !m.payload.planId) return
      this.executingPlanId = m.payload.planId
      try {
        const result = await retryFailedPlan(m.payload.planId, () => this.refreshStates())
        if (!this.isCurrentSession()) return
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
      if (!this.isCurrentSession()) return
      if (this.busy || !m.payload.planId) return
      this.executingPlanId = m.payload.planId
      try {
        const result = await reconcilePlan(m.payload.planId, () => this.refreshStates())
        if (!this.isCurrentSession()) return
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
      if (!this.isCurrentSession()) return
      if (this.busy) return
      try {
        await cancelPlan(m.payload.planId)
      } catch (e) {
        /* 卡片状态以服务端为准 */
      }
      this.refreshStates()
    },
    /** 仅恢复并读取现有派单任务，不能创建新的CONFIRM或RETRY命令。 */
    async resumeDispatch(m) {
      if (!this.isCurrentSession() || this.busy || !m.payload.planId) return
      this.executingPlanId = m.payload.planId
      try {
        const result = await resumePlanAction(m.payload.planId)
        if (!this.isCurrentSession() || !result) return
        this.planRefreshVersion++
        this.push({ role: 'card', cardType: 'result', payload: result })
        this.$emit('dispatched')
      } catch (e) { /* 状态查询失败时保留清单，禁止自动发送。 */ }
      finally { this.executingPlanId = null; this.refreshStates() }
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
.turn-recovery { margin-bottom:10px; padding:10px 12px; border:1px solid #ead3a4; background:#fffaf0; border-radius:6px; font-size:13px; color:#72562a; }.turn-recovery p { margin:0 0 5px; line-height:1.7; }
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

<style>
@media (max-width: 900px) {
  .agent-drawer { width: 100% !important; }
  .agent-drawer .agent-layout { flex-direction: column; }
  .agent-drawer .conv-pane { width: auto; max-height: 130px; flex-shrink: 0; }
  .agent-drawer .conv-list { overflow-y: auto; }
  .agent-drawer .chat-pane { min-height: 0; }
  .agent-drawer .foot-actions { flex-wrap: wrap; }
  .agent-drawer .sel-hint { flex-basis: 100%; }
}
</style>
