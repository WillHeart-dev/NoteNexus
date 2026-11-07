<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createReviewDocument,
  deleteReviewDocument,
  downloadReviewDocument,
  fetchReviewDocuments,
  isGeneratingStatus,
  type ReviewDocumentItem,
} from '@/api/review'
import { extractApiError } from '@/api/http'

/**
 * AI 复习资料面板
 *
 * 把一个学习小组内全部「已就绪」资料汇总成一份结构统一的复习讲义（Markdown），
 * 生成过程异步：创建后轮询状态，完成后可下载。
 */
const props = defineProps<{
  groupId: number | null
  groupName: string
  isGroupMember: boolean
}>()

const items = ref<ReviewDocumentItem[]>([])
const loading = ref(false)
const error = ref('')
const creating = ref(false)
const downloadingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)

let pollTimer: number | null = null

const generatingCount = computed(
  () => items.value.filter((item) => isGeneratingStatus(item.status)).length,
)

const canGenerate = computed(() => props.groupId !== null && props.isGroupMember && !creating.value)

async function load() {
  if (props.groupId === null) {
    items.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    items.value = await fetchReviewDocuments(props.groupId)
  } catch (err) {
    error.value = extractApiError(err, '加载复习资料失败')
    items.value = []
  } finally {
    loading.value = false
    syncPolling()
  }
}

/** 有生成中的任务时每 3 秒刷新一次状态 */
function syncPolling() {
  const needPolling = items.value.some((item) => isGeneratingStatus(item.status))
  if (needPolling && pollTimer === null) {
    pollTimer = window.setInterval(() => {
      void refreshStatuses()
    }, 3000)
  } else if (!needPolling && pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 轮询刷新：只更新状态字段，避免整表重绘 */
async function refreshStatuses() {
  if (props.groupId === null) return
  try {
    const latest = await fetchReviewDocuments(props.groupId)
    items.value = latest
    if (!latest.some((item) => isGeneratingStatus(item.status))) {
      ElMessage.success('复习资料已生成，可下载查看')
    }
  } catch {
    // 轮询失败静默重试，不打断用户
  } finally {
    syncPolling()
  }
}

async function handleGenerate() {
  if (props.groupId === null) return
  try {
    await ElMessageBox.confirm(
      `将把「${props.groupName}」内全部已就绪的学习资料汇总成一份复习讲义（Markdown 文档）。\n资料较多时需要几分钟，生成期间可以离开页面，稍后回来下载。`,
      '生成复习资料',
      { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  creating.value = true
  try {
    const created = await createReviewDocument(props.groupId)
    items.value = [created, ...items.value]
    ElMessage.success('已开始生成，完成后可在此下载')
    syncPolling()
  } catch (err) {
    ElMessage.error(extractApiError(err, '生成失败，请稍后再试'))
  } finally {
    creating.value = false
  }
}

async function handleDownload(item: ReviewDocumentItem) {
  downloadingId.value = item.reviewId
  try {
    const blob = await downloadReviewDocument(item.reviewId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = item.fileName || '复习资料.md'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (err) {
    ElMessage.error(extractApiError(err, '下载失败'))
  } finally {
    downloadingId.value = null
  }
}

async function handleDelete(item: ReviewDocumentItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除复习资料「${item.title}」吗？删除后需要重新生成。`,
      '删除复习资料',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  deletingId.value = item.reviewId
  try {
    await deleteReviewDocument(item.reviewId)
    items.value = items.value.filter((row) => row.reviewId !== item.reviewId)
    ElMessage.success('已删除')
  } catch (err) {
    ElMessage.error(extractApiError(err, '删除失败'))
  } finally {
    deletingId.value = null
  }
}

function statusLabel(status: ReviewDocumentItem['status']): string {
  switch (status) {
    case 'PENDING':
      return '排队中'
    case 'GENERATING':
      return '生成中'
    case 'COMPLETED':
      return '已完成'
    default:
      return '生成失败'
  }
}

function statusClass(status: ReviewDocumentItem['status']): string {
  return `review-card__badge--${status.toLowerCase()}`
}

function formatSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

watch(
  () => props.groupId,
  () => {
    stopPolling()
    void load()
  },
  { immediate: true },
)

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

onUnmounted(stopPolling)
</script>

<template>
  <section class="review-panel">
    <header class="review-panel__header">
      <div class="review-panel__intro">
        <h3 class="review-panel__title">
          AI 复习资料
          <span v-if="generatingCount > 0" class="review-panel__pulse">{{ generatingCount }} 份生成中</span>
        </h3>
        <p class="review-panel__subtitle">
          把本小组已就绪的学习资料汇总成结构统一的复习讲义：知识点精讲、易混概念对比、例题、自测题与速记卡，导出 Markdown 文档可下载。
        </p>
      </div>
      <button class="review-panel__action" :disabled="!canGenerate" @click="handleGenerate">
        {{ creating ? '提交中...' : '生成复习文档' }}
      </button>
    </header>

    <div v-if="loading" class="review-panel__state">正在加载复习资料...</div>

    <div v-else-if="error" class="review-panel__state review-panel__state--error">
      {{ error }}
      <button class="review-panel__retry" @click="load">重试</button>
    </div>

    <div v-else-if="items.length === 0" class="review-panel__state">
      {{ groupId === null ? '请先选择一个学习小组' : '还没有生成过复习资料，点击右上角「生成复习文档」试试' }}
    </div>

    <div v-else class="review-panel__list">
      <article v-for="item in items" :key="item.reviewId" class="review-card">
        <div class="review-card__main">
          <div class="review-card__title-row">
            <span class="review-card__title">{{ item.title }}</span>
            <span class="review-card__badge" :class="statusClass(item.status)">
              {{ statusLabel(item.status) }}
            </span>
          </div>
          <div class="review-card__meta">
            <span>{{ item.sourceDocumentCount }} 篇资料</span>
            <span v-if="item.chunkCount > 0">{{ item.chunkCount }} 个知识片段</span>
            <span v-if="item.status === 'COMPLETED'">{{ formatSize(item.fileSize) }}</span>
            <span>创建于 {{ formatTime(item.createdAt) }}</span>
          </div>
          <p v-if="item.status === 'FAILED'" class="review-card__error">
            {{ item.failureReason || '生成失败，可重新生成' }}
          </p>
        </div>
        <div class="review-card__actions">
          <button
            v-if="item.status === 'COMPLETED'"
            class="review-card__btn review-card__btn--primary"
            :disabled="downloadingId === item.reviewId"
            @click="handleDownload(item)"
          >
            {{ downloadingId === item.reviewId ? '下载中...' : '下载 Markdown' }}
          </button>
          <span v-else-if="isGeneratingStatus(item.status)" class="review-card__spinner" />
          <button
            class="review-card__btn"
            :disabled="deletingId === item.reviewId"
            @click="handleDelete(item)"
          >
            删除
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.review-panel {
  margin-top: 22px;
  padding: 22px 24px;
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
}

.review-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding-bottom: 16px;
  border-bottom: 1px dashed var(--border-default);
}

.review-panel__title {
  margin: 0 0 6px;
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 1.02rem;
  font-weight: 700;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 10px;
}

.review-panel__pulse {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.1);
  border-radius: 999px;
  padding: 3px 10px;
  animation: review-pulse 1.6s ease-in-out infinite;
}

@keyframes review-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

.review-panel__subtitle {
  margin: 0;
  font-size: 0.84rem;
  line-height: 1.6;
  color: var(--text-secondary);
  max-width: 640px;
}

.review-panel__action {
  flex-shrink: 0;
  padding: 9px 20px;
  font-family: inherit;
  font-size: 0.86rem;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  border: none;
  border-radius: var(--radius-sm);
  box-shadow: 0 4px 14px rgba(74, 144, 217, 0.22);
  cursor: pointer;
  transition: all 0.2s ease;
}

.review-panel__action:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(74, 144, 217, 0.3);
}

.review-panel__action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.review-panel__state {
  padding: 26px 4px 6px;
  font-size: 0.86rem;
  color: var(--text-secondary);
  text-align: center;
}

.review-panel__state--error {
  color: var(--el-color-danger);
}

.review-panel__retry {
  margin-left: 10px;
  padding: 4px 12px;
  font-size: 0.8rem;
  color: var(--brand-primary);
  background: var(--surface-accent);
  border: 1px solid rgba(74, 144, 217, 0.2);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.review-panel__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 16px;
}

.review-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  background: var(--surface-muted);
  border: 1px solid transparent;
  border-radius: var(--radius-md, 10px);
  transition: border-color 0.18s ease;
}

.review-card:hover {
  border-color: rgba(74, 144, 217, 0.25);
}

.review-card__main {
  min-width: 0;
}

.review-card__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.review-card__title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
}

.review-card__badge {
  font-size: 0.7rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
}

.review-card__badge--completed {
  color: #1a7f5a;
  background: rgba(52, 168, 121, 0.12);
}

.review-card__badge--generating,
.review-card__badge--pending {
  color: var(--brand-primary);
  background: rgba(74, 144, 217, 0.12);
}

.review-card__badge--failed {
  color: var(--el-color-danger);
  background: rgba(245, 108, 108, 0.12);
}

.review-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 6px;
  font-size: 0.78rem;
  color: var(--text-secondary);
}

.review-card__error {
  margin: 8px 0 0;
  font-size: 0.78rem;
  color: var(--el-color-danger);
  max-width: 720px;
}

.review-card__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.review-card__btn {
  padding: 6px 14px;
  font-family: inherit;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.16s ease;
}

.review-card__btn:hover:not(:disabled) {
  color: var(--brand-primary);
  border-color: rgba(74, 144, 217, 0.35);
}

.review-card__btn--primary {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  border: none;
}

.review-card__btn--primary:hover:not(:disabled) {
  color: #fff;
  box-shadow: 0 4px 12px rgba(74, 144, 217, 0.28);
}

.review-card__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.review-card__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--surface-muted);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: review-spin 0.8s linear infinite;
}

@keyframes review-spin {
  to { transform: rotate(360deg); }
}
</style>
