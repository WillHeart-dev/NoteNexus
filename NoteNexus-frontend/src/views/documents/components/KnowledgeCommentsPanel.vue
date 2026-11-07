<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { extractApiError } from '@/api/http'
import {
  fetchDiscussionMessages,
  revokeDiscussionMessage,
  sendDiscussionMessage,
  type DiscussionMessage,
} from '@/api/discussion'
import { useAuthStore } from '@/stores/auth'

/**
 * 知识库评论面板
 *
 * 挂在学习资料模块底部，让成员针对当前小组的「整个知识库」发表评论，
 * 而不是针对单份文档。复用群组讨论消息存储：消息按 groupId 归属，
 * 天然就是「一库一评论区」，同时保留引用回复与撤回能力。
 */
const props = defineProps<{
  groupId: number | null
  groupName: string
  documentCount: number
  readyCount: number
}>()

/** 单次拉取条数，同时也是「加载更早」的步长 */
const PAGE_SIZE = 30
/** 与后端 CreateDiscussionMessageRequest 的校验上限保持一致 */
const MAX_LENGTH = 5000

const authStore = useAuthStore()

const messages = ref<DiscussionMessage[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const sending = ref(false)
const revokingId = ref<number | null>(null)
const draft = ref('')
const replyTarget = ref<DiscussionMessage | null>(null)
const hasMore = ref(false)

const myName = computed(() => authStore.currentUser?.displayName ?? '我')
const myInitial = computed(() => myName.value.charAt(0).toUpperCase())

/** 最新评论排在最前；后端本身按 ID 倒序，这里做一次兜底排序 */
const orderedMessages = computed(() => [...messages.value].sort((a, b) => b.id - a.id))

const commentCount = computed(() => messages.value.length)

const participantCount = computed(
  () => new Set(messages.value.map((item) => item.userId)).size,
)

const scopeHint = computed(
  () =>
    `对「${props.groupName || '当前知识库'}」整体发表评论：资料是否够用、组织是否清晰，或者留下学习心得与补充线索。`,
)

const knowledgeScope = computed(() => {
  if (props.documentCount === 0) return '这个知识库还没有资料，欢迎先聊聊你的期待'
  return `知识库现有 ${props.documentCount} 份资料，其中 ${props.readyCount} 份已可检索`
})

const canSend = computed(
  () => draft.value.trim().length > 0 && props.groupId !== null && !sending.value,
)

const showLengthHint = computed(() => draft.value.length > MAX_LENGTH - 200)

/** 加载最新一页评论 */
async function load() {
  if (props.groupId === null) {
    messages.value = []
    hasMore.value = false
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const list = await fetchDiscussionMessages(props.groupId, undefined, PAGE_SIZE)
    messages.value = list
    hasMore.value = list.length >= PAGE_SIZE
  } catch (err) {
    error.value = extractApiError(err, '加载评论失败')
    messages.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

/** 以当前最早一条为游标，向前加载更早的评论 */
async function loadMore() {
  if (props.groupId === null || messages.value.length === 0) return
  const oldestId = Math.min(...messages.value.map((item) => item.id))
  loadingMore.value = true
  try {
    const list = await fetchDiscussionMessages(props.groupId, oldestId, PAGE_SIZE)
    const known = new Set(messages.value.map((item) => item.id))
    messages.value = [...messages.value, ...list.filter((item) => !known.has(item.id))]
    hasMore.value = list.length >= PAGE_SIZE
  } catch (err) {
    ElMessage.error(extractApiError(err, '加载更早评论失败'))
  } finally {
    loadingMore.value = false
  }
}

/** 发布评论（或发布一条引用回复） */
async function handleSend() {
  const content = draft.value.trim()
  if (!content || props.groupId === null) return
  if (content.length > MAX_LENGTH) {
    ElMessage.warning(`评论不能超过 ${MAX_LENGTH} 个字符`)
    return
  }

  sending.value = true
  try {
    const created = await sendDiscussionMessage({
      groupId: props.groupId,
      content,
      replyToId: replyTarget.value?.id ?? null,
    })
    messages.value = [created, ...messages.value.filter((item) => item.id !== created.id)]
    draft.value = ''
    replyTarget.value = null
    ElMessage.success('评论已发布')
  } catch (err) {
    ElMessage.error(extractApiError(err, '发布评论失败'))
  } finally {
    sending.value = false
  }
}

/** 引用某条评论进行回复 */
function handleReply(message: DiscussionMessage) {
  replyTarget.value = message
  draft.value = ''
}

/** 取消引用 */
function cancelReply() {
  replyTarget.value = null
}

/** 撤回自己发布的评论 */
async function handleRevoke(message: DiscussionMessage) {
  try {
    await ElMessageBox.confirm('撤回后其他成员将看不到这条评论，确定撤回？', '撤回评论', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  revokingId.value = message.id
  try {
    await revokeDiscussionMessage(message.id)
    messages.value = messages.value.filter((item) => item.id !== message.id)
    if (replyTarget.value?.id === message.id) replyTarget.value = null
    ElMessage.success('已撤回')
  } catch (err) {
    ElMessage.error(extractApiError(err, '撤回失败'))
  } finally {
    revokingId.value = null
  }
}

/** 头像首字母 */
function initialOf(name: string): string {
  return (name || '?').trim().charAt(0).toUpperCase()
}

/** 相对时间：一天内显示「刚刚 / N 分钟前 / 时分」，更早显示「月日 时分」 */
function formatTime(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const diffSeconds = Math.floor((Date.now() - date.getTime()) / 1000)
  if (diffSeconds < 60) return '刚刚'
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)} 分钟前`

  const timeText = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (date.toDateString() === new Date().toDateString()) return `今天 ${timeText}`
  const dateText = date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
  return `${dateText} ${timeText}`
}

/** 切换群组即切换评论区 */
watch(
  () => props.groupId,
  () => {
    replyTarget.value = null
    draft.value = ''
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section class="comment-panel">
    <header class="comment-panel__header">
      <div class="comment-panel__intro">
        <h3 class="comment-panel__title">
          <span class="comment-panel__icon" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path
                d="M4 7C4 5.9 4.9 5 6 5H15C16.1 5 17 5.9 17 7V13C17 14.1 16.1 15 15 15H9L5 18V15C4.4 15 4 14.6 4 14V7Z"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linejoin="round"
              />
              <path
                d="M20 10.5V16C20 16.6 19.6 17 19 17V19L16.5 17H12C11.4 17 11 16.6 11 16"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </span>
          知识库评论
          <span v-if="commentCount > 0" class="comment-panel__count">{{ commentCount }} 条</span>
        </h3>
        <p class="comment-panel__subtitle">{{ scopeHint }}</p>
      </div>
      <div v-if="commentCount > 0" class="comment-panel__stats">
        <span>{{ participantCount }} 人参与</span>
        <span class="comment-panel__stats-divider" aria-hidden="true">·</span>
        <span>{{ knowledgeScope }}</span>
      </div>
    </header>

    <!-- ── 编辑器 ── -->
    <div class="composer" :class="{ 'composer--disabled': groupId === null }">
      <div class="composer__avatar">{{ myInitial }}</div>
      <div class="composer__main">
        <div v-if="replyTarget" class="composer__reply">
          <span class="composer__reply-text">
            回复 <strong>{{ replyTarget.displayName }}</strong>：{{ replyTarget.content.slice(0, 48) }}
          </span>
          <button type="button" class="composer__reply-cancel" @click="cancelReply">取消</button>
        </div>
        <textarea
          v-model="draft"
          class="composer__input"
          rows="3"
          :maxlength="MAX_LENGTH"
          :disabled="groupId === null"
          :placeholder="
            groupId === null ? '请先在页面顶部选择一个学习小组' : '写下你对这个知识库的整体评价…'
          "
          @keydown.ctrl.enter.prevent="handleSend"
          @keydown.meta.enter.prevent="handleSend"
        />
        <div class="composer__actions">
          <span class="composer__hint">
            <template v-if="showLengthHint">{{ draft.length }} / {{ MAX_LENGTH }}</template>
            <template v-else>Ctrl + Enter 快捷发布</template>
          </span>
          <button type="button" class="composer__send" :disabled="!canSend" @click="handleSend">
            {{ sending ? '发布中…' : replyTarget ? '发布回复' : '发布评论' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ── 列表 ── -->
    <div v-if="loading" class="comment-panel__state">正在加载评论…</div>

    <div v-else-if="error" class="comment-panel__state comment-panel__state--error">
      {{ error }}
      <button type="button" class="comment-panel__retry" @click="load">重试</button>
    </div>

    <div v-else-if="orderedMessages.length === 0" class="comment-panel__empty">
      <div class="comment-panel__empty-icon" aria-hidden="true">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none">
          <path
            d="M4 7C4 5.9 4.9 5 6 5H15C16.1 5 17 5.9 17 7V13C17 14.1 16.1 15 15 15H9L5 18V15C4.4 15 4 14.6 4 14V7Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
      </div>
      <p>{{ groupId === null ? '选择小组后即可查看评论' : '这个知识库还没有评论，写下第一条吧' }}</p>
    </div>

    <div v-else class="comment-list">
      <article v-for="msg in orderedMessages" :key="msg.id" class="comment">
        <div class="comment__avatar" :class="{ 'comment__avatar--mine': msg.mine }">
          {{ initialOf(msg.displayName) }}
        </div>
        <div class="comment__body">
          <div class="comment__head">
            <span class="comment__name">{{ msg.displayName }}</span>
            <span v-if="msg.mine" class="comment__badge">我</span>
            <span class="comment__time">{{ formatTime(msg.createdAt) }}</span>
          </div>

          <div v-if="msg.replyToContent" class="comment__quote">
            <strong>{{ msg.replyToName }}</strong>
            <span>{{ msg.replyToContent }}</span>
          </div>

          <p class="comment__content">{{ msg.content }}</p>

          <div class="comment__actions">
            <button type="button" class="comment__action" @click="handleReply(msg)">回复</button>
            <button
              v-if="msg.mine"
              type="button"
              class="comment__action comment__action--danger"
              :disabled="revokingId === msg.id"
              @click="handleRevoke(msg)"
            >
              {{ revokingId === msg.id ? '撤回中…' : '撤回' }}
            </button>
          </div>
        </div>
      </article>

      <button
        v-if="hasMore"
        type="button"
        class="comment-list__more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '加载中…' : '加载更早的评论' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.comment-panel {
  margin-top: 22px;
  padding: 22px 24px 20px;
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
}

.comment-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 16px;
  border-bottom: 1px dashed var(--border-default);
}

.comment-panel__title {
  margin: 0 0 6px;
  display: flex;
  align-items: center;
  gap: 9px;
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 1.02rem;
  font-weight: 700;
  color: var(--text-primary);
}

.comment-panel__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  color: var(--brand-primary);
  background: var(--surface-accent);
}

.comment-panel__count {
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--brand-primary-dark);
  background: var(--surface-accent);
}

.comment-panel__subtitle {
  margin: 0;
  max-width: 640px;
  font-size: 0.82rem;
  line-height: 1.65;
  color: var(--text-secondary);
}

.comment-panel__stats {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
  font-size: 0.76rem;
  color: var(--text-muted);
  white-space: nowrap;
}

.comment-panel__stats-divider {
  opacity: 0.6;
}

/* ── 编辑器 ── */
.composer {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-subtle);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.composer:focus-within {
  border-color: var(--brand-primary-light);
  box-shadow: 0 0 0 3px rgba(74, 144, 217, 0.1);
}

.composer--disabled {
  opacity: 0.72;
}

.composer__avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-accent));
}

.composer__main {
  flex: 1;
  min-width: 0;
}

.composer__reply {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border-left: 3px solid var(--brand-primary-light);
  border-radius: 0 6px 6px 0;
  background: var(--surface-muted);
  font-size: 0.78rem;
  color: var(--text-secondary);
}

.composer__reply-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer__reply-cancel {
  flex-shrink: 0;
  padding: 0;
  border: none;
  background: none;
  font-size: 0.78rem;
  color: var(--brand-primary);
  cursor: pointer;
}

.composer__input {
  display: block;
  width: 100%;
  padding: 0;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-family: inherit;
  font-size: 0.9rem;
  line-height: 1.7;
  color: var(--text-primary);
}

.composer__input::placeholder {
  color: var(--text-muted);
}

.composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.composer__hint {
  font-size: 0.74rem;
  color: var(--text-muted);
}

.composer__send {
  padding: 7px 18px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 0.82rem;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  box-shadow: 0 4px 12px rgba(74, 144, 217, 0.24);
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease,
    opacity 0.15s ease;
}

.composer__send:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(74, 144, 217, 0.3);
}

.composer__send:disabled {
  cursor: not-allowed;
  opacity: 0.5;
  box-shadow: none;
}

/* ── 列表 ── */
.comment-list {
  margin-top: 8px;
  max-height: 460px;
  overflow-y: auto;
  padding-right: 4px;
}

.comment-list::-webkit-scrollbar {
  width: 6px;
}

.comment-list::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: var(--border-default);
}

.comment {
  display: flex;
  gap: 12px;
  padding: 16px 2px;
  border-bottom: 1px dashed var(--border-subtle);
}

.comment:last-of-type {
  border-bottom: none;
}

.comment__avatar {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-muted);
}

.comment__avatar--mine {
  color: var(--brand-primary-dark);
  background: var(--surface-accent);
}

.comment__body {
  flex: 1;
  min-width: 0;
}

.comment__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.comment__name {
  font-size: 0.84rem;
  font-weight: 600;
  color: var(--text-primary);
}

.comment__badge {
  padding: 0 6px;
  border-radius: 999px;
  font-size: 0.68rem;
  font-weight: 600;
  color: var(--brand-primary-dark);
  background: var(--surface-accent);
}

.comment__time {
  font-size: 0.74rem;
  color: var(--text-muted);
}

.comment__quote {
  margin-top: 8px;
  padding: 6px 10px;
  border-left: 3px solid var(--brand-primary-light);
  border-radius: 0 6px 6px 0;
  background: var(--surface-muted);
  font-size: 0.78rem;
  line-height: 1.6;
  color: var(--text-muted);
  display: flex;
  gap: 6px;
}

.comment__quote strong {
  flex-shrink: 0;
  color: var(--text-secondary);
}

.comment__quote span {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.comment__content {
  margin: 7px 0 0;
  font-size: 0.89rem;
  line-height: 1.78;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.comment__actions {
  display: flex;
  gap: 14px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.comment:hover .comment__actions,
.comment:focus-within .comment__actions {
  opacity: 1;
}

.comment__action {
  padding: 0;
  border: none;
  background: none;
  font-size: 0.76rem;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.15s ease;
}

.comment__action:hover:not(:disabled) {
  color: var(--brand-primary);
}

.comment__action--danger:hover:not(:disabled) {
  color: var(--el-color-danger);
}

.comment-list__more {
  display: block;
  width: 100%;
  margin: 6px 0 4px;
  padding: 9px 0;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-sm);
  background: var(--surface-subtle);
  font-size: 0.78rem;
  color: var(--text-secondary);
  cursor: pointer;
}

.comment-list__more:hover:not(:disabled) {
  color: var(--brand-primary);
  border-color: var(--brand-primary-light);
}

/* ── 状态 ── */
.comment-panel__state {
  padding: 32px 0;
  text-align: center;
  font-size: 0.84rem;
  color: var(--text-secondary);
}

.comment-panel__state--error {
  color: var(--el-color-danger);
}

.comment-panel__retry {
  margin-left: 10px;
  padding: 3px 12px;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 0.78rem;
  color: var(--brand-primary);
  background: var(--surface-accent);
  cursor: pointer;
}

.comment-panel__empty {
  padding: 34px 0 26px;
  text-align: center;
  color: var(--text-muted);
}

.comment-panel__empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: 10px;
  border-radius: 16px;
  color: var(--brand-primary-light);
  background: var(--surface-subtle);
}

.comment-panel__empty p {
  margin: 0;
  font-size: 0.84rem;
}

@media (max-width: 720px) {
  .comment-panel {
    padding: 18px 16px;
  }

  .comment-panel__header {
    flex-direction: column;
    gap: 8px;
  }

  .comment-panel__stats {
    padding-top: 0;
  }

  .comment-list {
    max-height: 420px;
  }
}
</style>
