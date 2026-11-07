<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { extractApiError } from '@/api/http'
import { fetchGroups } from '@/api/group'
import {
  MASTERY_COLORS,
  MASTERY_LABELS,
  RELATION_LABELS,
  buildGraph,
  fetchGraph,
  fetchGraphTask,
  fetchLatestGraphTask,
  fetchLearningPath,
  type GraphEdge,
  type GraphNode,
  type GraphTask,
  type GraphView,
  type LearningPath,
  type MasteryLevel,
} from '@/api/graph'

const route = useRoute()

// ── 画布尺寸（与 SVG viewBox 一致） ──
const VIEW_W = 1000
const VIEW_H = 660
const PADDING_X = 96
const PADDING_Y = 78

// ── 群组 ──
interface GroupOption {
  id: number
  groupName: string
}
const groups = ref<GroupOption[]>([])
const currentGroupId = ref<number | null>(null)
const currentGroupName = computed(
  () => groups.value.find((item) => item.id === currentGroupId.value)?.groupName ?? '',
)

// ── 数据 ──
const graph = ref<GraphView | null>(null)
const path = ref<LearningPath | null>(null)
const loading = ref(false)
const building = ref(false)
const buildTask = ref<GraphTask | null>(null)
const activeTab = ref<'graph' | 'path'>('graph')
const selectedNodeId = ref<number | null>(null)

// ── 模拟状态 ──
/** 布局冷却系数，衰减到阈值以下自动停帧 */
let alpha = 0
/** 当前动画帧句柄 */
let rafId: number | null = null
/** 模拟中的节点与连线（非响应式，逐帧写入 layoutNodes 触发渲染） */
let simNodes: LayoutNode[] = []
let simLinks: Array<{ a: LayoutNode; b: LayoutNode; rest: number }> = []
let pollTimer: ReturnType<typeof setInterval> | null = null

// ── 布局结果 ──
interface LayoutNode {
  id: number
  name: string
  x: number
  y: number
  vx: number
  vy: number
  r: number
  mastery: MasteryLevel
  importance: number
  source: GraphNode
}
const layoutNodes = shallowRef<LayoutNode[]>([])
const layoutIndex = computed(() => new Map(layoutNodes.value.map((item) => [item.id, item])))

const selectedNode = computed<GraphNode | null>(
  () => graph.value?.nodes.find((node) => node.id === selectedNodeId.value) ?? null,
)

/** 选中节点的邻接节点集合，用于高亮 */
const neighborIds = computed(() => {
  const result = new Set<number>()
  if (selectedNodeId.value == null || !graph.value) return result
  result.add(selectedNodeId.value)
  for (const edge of graph.value.edges) {
    if (edge.sourceId === selectedNodeId.value) result.add(edge.targetId)
    if (edge.targetId === selectedNodeId.value) result.add(edge.sourceId)
  }
  return result
})

/** 选中节点的关系明细 */
const selectedRelations = computed(() => {
  if (!selectedNodeId.value || !graph.value) return []
  return graph.value.edges
    .filter(
      (edge) => edge.sourceId === selectedNodeId.value || edge.targetId === selectedNodeId.value,
    )
    .map((edge) => ({
      id: edge.id,
      outgoing: edge.sourceId === selectedNodeId.value,
      relation: edge.relation,
      name: edge.sourceId === selectedNodeId.value ? edge.targetName : edge.sourceName,
      evidence: edge.evidence,
    }))
})

/** 渲染用关系曲线：避开节点圆，末端留箭头空间，双向边按奇偶交替弯曲 */
const renderEdges = computed(() => {
  if (!graph.value) return []
  const index = layoutIndex.value
  const selected = selectedNodeId.value
  const active = neighborIds.value
  return graph.value.edges.flatMap((edge) => {
    const source = index.get(edge.sourceId)
    const target = index.get(edge.targetId)
    if (!source || !target) return []

    const dx = target.x - source.x
    const dy = target.y - source.y
    const distance = Math.hypot(dx, dy) || 1
    const ux = dx / distance
    const uy = dy / distance
    const x1 = source.x + ux * (source.r + 3)
    const y1 = source.y + uy * (source.r + 3)
    const x2 = target.x - ux * (target.r + 11)
    const y2 = target.y - uy * (target.r + 11)
    // 控制点沿法线偏移；奇偶边方向相反，正反两条关系不会完全重合
    const bend = Math.min(30, distance * 0.14) * (edge.id % 2 === 0 ? 1 : -1)
    const cx = (x1 + x2) / 2 - uy * bend
    const cy = (y1 + y2) / 2 + ux * bend
    const dimmed = selected != null && (!active.has(edge.sourceId) || !active.has(edge.targetId))

    return [
      {
        id: edge.id,
        path: `M${x1.toFixed(1)},${y1.toFixed(1)} Q${cx.toFixed(1)},${cy.toFixed(1)} ${x2.toFixed(1)},${y2.toFixed(1)}`,
        relation: edge.relation,
        opacity: dimmed ? 0.08 : 0.5,
      },
    ]
  })
})

/** 掌握度分布 */
const masterySummary = computed(() => {
  const nodes = graph.value?.nodes ?? []
  const summary: Record<MasteryLevel, number> = { STRONG: 0, MEDIUM: 0, WEAK: 0, UNTOUCHED: 0 }
  for (const node of nodes) summary[node.mastery] += 1
  return summary
})

const nodeRadius = (node: GraphNode) => 13 + (node.importance ?? 5) * 1.5

/** 节点之间保持的最小间距，避免圆与标签互相压住 */
const MIN_GAP = 62
/** 冷却系数下限：低于该值视为收敛，停止动画帧 */
const ALPHA_MIN = 0.015
/** 关系类型决定连线舒适长度：先修更远、组成更近 */
const REST_LENGTH: Record<string, number> = {
  PREREQUISITE_OF: 178,
  PART_OF: 138,
  RELATED_TO: 206,
}

/**
 * 初始化力模拟：节点先按序号铺在同心圆上（确定性初值），
 * 再由斥力 / 弹簧 / 向心三种力逐帧推向稳定位置，形成「展开」动画。
 * 物理迭代跑在非响应式数组上，每帧整体写回 layoutNodes，只触发一次渲染。
 */
function initSimulation(nodes: GraphNode[], edges: GraphEdge[]) {
  if (nodes.length === 0) {
    stopSimulation()
    simNodes = []
    simLinks = []
    layoutNodes.value = []
    return
  }

  simNodes = nodes.map((node, index) => {
    const angle = (2 * Math.PI * index) / nodes.length - Math.PI / 2
    const ring = 90 + (index % 4) * 26
    return {
      id: node.id,
      name: node.name,
      x: VIEW_W / 2 + Math.cos(angle) * ring,
      y: VIEW_H / 2 + Math.sin(angle) * ring,
      vx: 0,
      vy: 0,
      r: nodeRadius(node),
      mastery: node.mastery,
      importance: node.importance,
      source: node,
    }
  })

  const index = new Map(simNodes.map((item) => [item.id, item]))
  simLinks = edges.flatMap((edge) => {
    const a = index.get(edge.sourceId)
    const b = index.get(edge.targetId)
    if (!a || !b) return []
    return [{ a, b, rest: REST_LENGTH[edge.relation] ?? 170 }]
  })

  layoutNodes.value = simNodes.slice()
  reheat()
}

/** 重新加热：把冷却系数拉回 1，让图重新动起来 */
function reheat() {
  if (simNodes.length === 0) return
  alpha = 1
  startSimulation()
}

function startSimulation() {
  if (rafId != null) return
  const loop = () => {
    rafId = null
    if (alpha < ALPHA_MIN) return
    stepSimulation()
    rafId = requestAnimationFrame(loop)
  }
  rafId = requestAnimationFrame(loop)
}

function stopSimulation() {
  if (rafId != null) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
}

/** 推进一帧：斥力 → 弹簧 → 向心 → 阻尼积分 → 边界回弹 */
function stepSimulation() {
  alpha *= 0.986

  const count = simNodes.length
  const centerX = VIEW_W / 2
  const centerY = VIEW_H / 2

  // 斥力：全对遍历。节点数在几十量级，n² 比四叉树更稳且开销可忽略
  for (let i = 0; i < count; i += 1) {
    const a = simNodes[i]!
    for (let j = i + 1; j < count; j += 1) {
      const b = simNodes[j]!
      let dx = b.x - a.x
      let dy = b.y - a.y
      let d2 = dx * dx + dy * dy
      if (d2 < 1) {
        dx = i % 2 === 0 ? 0.8 : -0.8
        dy = 0.6
        d2 = dx * dx + dy * dy
      }
      // 过远的节点不必互相计算，省掉一次开方
      if (d2 > 240000) continue
      const d = Math.sqrt(d2)
      // 小于最小间距时额外加力，防止圆重叠
      const gapFactor = d < MIN_GAP ? 1 + ((MIN_GAP - d) / MIN_GAP) * 1.8 : 1
      const force = ((32000 * alpha) / d2) * gapFactor
      const fx = (dx / d) * force
      const fy = (dy / d) * force
      a.vx -= fx
      a.vy -= fy
      b.vx += fx
      b.vy += fy
    }
  }

  // 弹簧：把有关系的节点拉回各自舒适长度
  for (const link of simLinks) {
    const dx = link.b.x - link.a.x
    const dy = link.b.y - link.a.y
    const d = Math.hypot(dx, dy) || 1
    const force = (d - link.rest) * 0.045 * alpha
    const fx = (dx / d) * force
    const fy = (dy / d) * force
    link.a.vx += fx
    link.a.vy += fy
    link.b.vx -= fx
    link.b.vy -= fy
  }

  // 向心 + 阻尼 + 积分 + 边界回弹
  // 下边距额外留出「标签高度 + 图例条」的空间，避免节点收进图例下方被遮住
  const minX = 58
  const maxX = VIEW_W - 58
  const minY = 56
  const maxY = VIEW_H - 104

  for (const node of simNodes) {
    node.vx += (centerX - node.x) * 0.0055 * alpha
    node.vy += (centerY - node.y) * 0.0065 * alpha
    node.vx *= 0.84
    node.vy *= 0.84

    const speed = Math.hypot(node.vx, node.vy)
    if (speed > 22) {
      node.vx = (node.vx / speed) * 22
      node.vy = (node.vy / speed) * 22
    }

    node.x += node.vx
    node.y += node.vy

    // 触边回弹：保证收敛后全部落在画布内，避免最后整体缩放导致跳一下
    if (node.x < minX) {
      node.x = minX
      node.vx = Math.abs(node.vx) * 0.45
    } else if (node.x > maxX) {
      node.x = maxX
      node.vx = -Math.abs(node.vx) * 0.45
    }
    if (node.y < minY) {
      node.y = minY
      node.vy = Math.abs(node.vy) * 0.45
    } else if (node.y > maxY) {
      node.y = maxY
      node.vy = -Math.abs(node.vy) * 0.45
    }
  }

  layoutNodes.value = simNodes.slice()
}

// ── 数据加载 ──
async function loadGroups() {
  try {
    const response = await fetchGroups()
    const owned = (response.ownedGroups ?? []).map((group) => ({
      id: group.groupId,
      groupName: group.groupName,
    }))
    const joined = (response.joinedGroups ?? []).map((group) => ({
      id: group.groupId,
      groupName: group.groupName,
    }))
    const unique = new Map([...owned, ...joined].map((group) => [group.id, group]))
    groups.value = [...unique.values()].sort((a, b) => a.groupName.localeCompare(b.groupName))

    const fromRoute = Number(route.query.groupId)
    if (fromRoute && groups.value.some((group) => group.id === fromRoute)) {
      currentGroupId.value = fromRoute
    } else if (groups.value.length > 0) {
      currentGroupId.value = groups.value[0]!.id
    }
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载群组失败'))
  }
}

async function loadData() {
  if (!currentGroupId.value) return
  loading.value = true
  try {
    const [graphData, pathData] = await Promise.all([
      fetchGraph(currentGroupId.value),
      fetchLearningPath(currentGroupId.value),
    ])
    graph.value = graphData
    path.value = pathData
    initSimulation(graphData.nodes, graphData.edges)
    if (selectedNodeId.value != null && !graphData.nodes.some((n) => n.id === selectedNodeId.value)) {
      selectedNodeId.value = null
    }
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载知识图谱失败'))
  } finally {
    loading.value = false
  }
}

/** 页面打开时恢复未完成的构建任务 */
async function resumeTask() {
  if (!currentGroupId.value) return
  try {
    const latest = await fetchLatestGraphTask(currentGroupId.value)
    buildTask.value = latest
    if (latest && (latest.status === 'PENDING' || latest.status === 'GENERATING')) {
      building.value = true
      startPolling(latest.id)
    }
  } catch {
    // 恢复失败不阻塞页面
  }
}

// ── 构建 ──
function startPolling(taskId: number) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const task = await fetchGraphTask(taskId)
      buildTask.value = task
      if (task.status === 'COMPLETED') {
        stopPolling()
        building.value = false
        ElMessage.success(`图谱构建完成：${task.nodeCount} 个知识点，${task.edgeCount} 条关系`)
        await loadData()
      } else if (task.status === 'FAILED') {
        stopPolling()
        building.value = false
        ElMessage.error(task.failureReason ?? '图谱构建失败')
      }
    } catch {
      stopPolling()
      building.value = false
    }
  }, 2500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function handleBuild() {
  if (!currentGroupId.value) return
  try {
    building.value = true
    selectedNodeId.value = null
    const task = await buildGraph(currentGroupId.value)
    buildTask.value = task
    activeTab.value = 'graph'
    startPolling(task.id)
  } catch (error) {
    building.value = false
    ElMessage.error(extractApiError(error, '发起构建失败'))
  }
}

// ── 画布交互 ──
function selectNode(id: number) {
  selectedNodeId.value = selectedNodeId.value === id ? null : id
}

/** 掌握度百分比展示 */
function accuracyText(value: number | null) {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

// ── 生命周期 ──
onMounted(async () => {
  await loadGroups()
  await loadData()
  await resumeTask()
})

watch(currentGroupId, async () => {
  selectedNodeId.value = null
  buildTask.value = null
  stopPolling()
  building.value = false
  await loadData()
  await resumeTask()
})

onBeforeUnmount(() => {
  stopPolling()
  stopSimulation()
})
</script>

<template>
  <div class="graph-page">
    <header class="page-head">
      <div class="page-head__text">
        <div class="eyebrow">Knowledge Graph</div>
        <h1>知识图谱</h1>
        <p>从小组资料中抽取知识点与先修关系，并结合你的做题情况排出学习顺序。</p>
      </div>
      <div class="page-head__actions">
        <el-select
          v-model="currentGroupId"
          class="group-select"
          placeholder="选择学习小组"
          size="large"
          :teleported="true"
        >
          <el-option
            v-for="group in groups"
            :key="group.id"
            :label="group.groupName"
            :value="group.id"
          />
        </el-select>
        <el-button
          type="primary"
          size="large"
          :loading="building"
          :disabled="!currentGroupId"
          @click="handleBuild"
        >
          {{ graph && graph.nodeCount > 0 ? '重新构建图谱' : '构建知识图谱' }}
        </el-button>
      </div>
    </header>

    <section v-if="currentGroupId" class="stat-row">
      <div class="stat-card">
        <span class="stat-card__label">知识点</span>
        <strong class="stat-card__value">{{ graph?.nodeCount ?? 0 }}</strong>
        <span class="stat-card__hint">已抽取概念</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">关系</span>
        <strong class="stat-card__value">{{ graph?.edgeCount ?? 0 }}</strong>
        <span class="stat-card__hint">先修 / 组成 / 相关</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">已掌握</span>
        <strong class="stat-card__value stat-card__value--strong">
          {{ path?.masteredCount ?? 0 }}
        </strong>
        <span class="stat-card__hint">正确率 ≥ 85%</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__label">待补强</span>
        <strong class="stat-card__value stat-card__value--weak">{{ path?.weakCount ?? 0 }}</strong>
        <span class="stat-card__hint">正确率 &lt; 60%</span>
      </div>
    </section>

    <div v-if="currentGroupId" class="tab-bar">
      <button
        v-for="tab in [
          { key: 'graph', label: '图谱视图' },
          { key: 'path', label: '学习路径' },
        ]"
        :key="tab.key"
        class="tab-bar__item"
        :class="{ active: activeTab === tab.key }"
        type="button"
        @click="activeTab = tab.key as 'graph' | 'path'"
      >
        {{ tab.label }}
      </button>
      <span v-if="graph?.lastBuiltAt" class="tab-bar__meta">
        最近构建 {{ new Date(graph.lastBuiltAt).toLocaleString('zh-CN') }}
      </span>
    </div>

    <!-- 构建进行中 -->
    <section v-if="building" class="build-card">
      <div class="build-card__head">
        <span class="build-card__spinner" />
        <div>
          <h3>正在构建知识图谱</h3>
          <p>
            逐份资料调用大模型抽取知识点与关系，通常需要 30~90 秒。
            <template v-if="buildTask?.documentCount">
              本次参与资料 {{ buildTask.documentCount }} 份。
            </template>
          </p>
        </div>
      </div>
      <el-progress :percentage="100" :indeterminate="true" :show-text="false" :stroke-width="6" />
      <p class="build-card__status">
        当前状态：{{
          buildTask?.status === 'GENERATING' ? '正在抽取并合并知识点' : '任务排队中'
        }}
      </p>
    </section>

    <template v-else-if="!loading">
      <!-- 图谱视图 -->
      <div v-if="activeTab === 'graph' && graph && graph.nodeCount > 0" class="graph-layout">
        <div class="canvas-card">
          <svg :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`" class="canvas" role="img" aria-label="知识点关系图">
            <defs>
              <marker
                id="arrow-prerequisite"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6.5"
                markerHeight="6.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
              </marker>
              <marker
                id="arrow-partof"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6.5"
                markerHeight="6.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#c4b5fd" />
              </marker>
              <marker
                id="arrow-related"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6.5"
                markerHeight="6.5"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#cbd5e1" />
              </marker>
            </defs>

            <!-- 关系曲线 -->
            <path
              v-for="edge in renderEdges"
              :key="edge.id"
              :d="edge.path"
              fill="none"
              :stroke="
                edge.relation === 'PART_OF'
                  ? '#c4b5fd'
                  : edge.relation === 'RELATED_TO'
                    ? '#cbd5e1'
                    : '#94a3b8'
              "
              :stroke-width="edge.relation === 'PREREQUISITE_OF' ? 1.9 : 1.3"
              :stroke-dasharray="edge.relation === 'RELATED_TO' ? '5 4' : undefined"
              :opacity="edge.opacity"
              :marker-end="
                edge.relation === 'PART_OF'
                  ? 'url(#arrow-partof)'
                  : edge.relation === 'RELATED_TO'
                    ? 'url(#arrow-related)'
                    : 'url(#arrow-prerequisite)'
              "
            />

            <!-- 节点 -->
            <g
              v-for="node in layoutNodes"
              :key="node.id"
              class="node"
              :class="{ 'node--active': selectedNodeId === node.id }"
              :style="{
                opacity: selectedNodeId == null || neighborIds.has(node.id) ? 1 : 0.24,
              }"
              @click.stop="selectNode(node.id)"
            >
              <circle
                v-if="node.mastery === 'WEAK'"
                class="node__halo"
                :cx="node.x"
                :cy="node.y"
                :r="node.r + 8"
                :stroke="MASTERY_COLORS.WEAK"
              />
              <circle
                class="node__body"
                :cx="node.x"
                :cy="node.y"
                :r="node.r"
                :fill="MASTERY_COLORS[node.mastery]"
                fill-opacity="0.16"
                :stroke="MASTERY_COLORS[node.mastery]"
                :stroke-width="selectedNodeId === node.id ? 3 : 1.8"
              />
              <circle
                :cx="node.x"
                :cy="node.y"
                :r="Math.max(3.2, node.r * 0.22)"
                :fill="MASTERY_COLORS[node.mastery]"
              />
              <text
                :x="node.x"
                :y="node.y + node.r + 15"
                class="node__label"
                :class="{ 'node__label--active': selectedNodeId === node.id }"
              >
                {{ node.name }}
              </text>
            </g>
          </svg>

          <div class="canvas__legend">
            <span
              v-for="level in (['STRONG', 'MEDIUM', 'WEAK', 'UNTOUCHED'] as MasteryLevel[])"
              :key="level"
              class="legend-item"
            >
              <i class="legend-dot" :style="{ background: MASTERY_COLORS[level] }" />
              {{ MASTERY_LABELS[level] }}
            </span>
            <span class="legend-sep" />
            <span class="legend-item">实线＝先修</span>
            <span class="legend-item">实线淡紫＝组成</span>
            <span class="legend-item">虚线＝相关</span>
          </div>

          <div class="canvas__toolbar">
            <button type="button" class="ghost-btn" @click="reheat">重新布局</button>
          </div>
        </div>

        <aside class="detail-card">
          <template v-if="selectedNode">
            <div class="detail-card__head">
              <h2>{{ selectedNode.name }}</h2>
              <span
                class="mastery-pill"
                :style="{
                  color: MASTERY_COLORS[selectedNode.mastery],
                  borderColor: MASTERY_COLORS[selectedNode.mastery],
                }"
              >
                {{ MASTERY_LABELS[selectedNode.mastery] }}
              </span>
            </div>
            <p class="detail-card__summary">{{ selectedNode.summary || '暂无释义' }}</p>

            <div class="detail-metrics">
              <div>
                <span>重要度</span>
                <strong>{{ selectedNode.importance }}/10</strong>
              </div>
              <div>
                <span>关联习题</span>
                <strong>{{ selectedNode.questionCount }}</strong>
              </div>
              <div>
                <span>正确率</span>
                <strong>{{ accuracyText(selectedNode.accuracy) }}</strong>
              </div>
            </div>

            <h3 class="detail-card__section">关联知识点</h3>
            <ul v-if="selectedRelations.length" class="relation-list">
              <li v-for="item in selectedRelations" :key="item.id">
                <span class="relation-tag" :class="`relation-tag--${item.relation.toLowerCase()}`">
                  {{ item.outgoing ? '→' : '←' }} {{ RELATION_LABELS[item.relation] }}
                </span>
                <span class="relation-name">{{ item.name }}</span>
              </li>
            </ul>
            <p v-else class="detail-card__empty">暂无关联</p>

            <h3 class="detail-card__section">来源资料</h3>
            <div class="doc-chips">
              <span v-for="doc in selectedNode.documents" :key="doc" class="doc-chip">{{ doc }}</span>
              <span v-if="!selectedNode.documents.length" class="detail-card__empty">—</span>
            </div>
          </template>

          <template v-else>
            <h2 class="detail-card__title">掌握度分布</h2>
            <ul class="mastery-list">
              <li
                v-for="level in (['STRONG', 'MEDIUM', 'WEAK', 'UNTOUCHED'] as MasteryLevel[])"
                :key="level"
              >
                <i class="legend-dot" :style="{ background: MASTERY_COLORS[level] }" />
                <span>{{ MASTERY_LABELS[level] }}</span>
                <strong>{{ masterySummary[level] }}</strong>
              </li>
            </ul>

            <h3 class="detail-card__section">重点补强</h3>
            <ul v-if="path?.weakPoints.length" class="weak-list">
              <li v-for="item in path.weakPoints.slice(0, 4)" :key="item.id">
                <span class="weak-list__name">{{ item.name }}</span>
                <span class="weak-list__rate">{{ accuracyText(item.accuracy) }}</span>
              </li>
            </ul>
            <p v-else class="detail-card__empty">还没有薄弱知识点，去习题库刷几题试试</p>

            <button type="button" class="text-btn" @click="activeTab = 'path'">
              查看学习路径 →
            </button>
          </template>
        </aside>
      </div>

      <!-- 学习路径 -->
      <div v-else-if="activeTab === 'path' && path && path.nodeCount > 0" class="path-layout">
        <div v-if="path.weakPoints.length" class="weak-panel">
          <div class="weak-panel__head">
            <h3>优先补强</h3>
            <p>按掌握度与重要度排序，建议先补齐这些知识点</p>
          </div>
          <div class="weak-panel__items">
            <span v-for="item in path.weakPoints" :key="item.id" class="weak-pill">
              {{ item.name }}
              <em>{{ accuracyText(item.accuracy) }}</em>
            </span>
          </div>
        </div>

        <div class="path-timeline">
          <section v-for="stage in path.stages" :key="stage.level" class="stage">
            <div class="stage__rail">
              <span class="stage__badge">{{ stage.level }}</span>
              <span class="stage__line" />
            </div>
            <div class="stage__body">
              <div class="stage__head">
                <h3>{{ stage.title }}</h3>
                <span class="stage__count">{{ stage.count }} 个知识点</span>
              </div>
              <p class="stage__desc">{{ stage.description }}</p>
              <div class="stage__nodes">
                <button
                  v-for="item in stage.nodes"
                  :key="item.id"
                  type="button"
                  class="path-node"
                  :class="{ 'path-node--active': selectedNodeId === item.id }"
                  @click="selectNode(item.id)"
                >
                  <span class="path-node__dot" :style="{ background: MASTERY_COLORS[item.mastery] }" />
                  <span class="path-node__name">{{ item.name }}</span>
                  <span class="path-node__meta">
                    <template v-if="item.questionCount">正确率 {{ accuracyText(item.accuracy) }}</template>
                    <template v-else>未出题</template>
                  </span>
                </button>
              </div>
            </div>
          </section>
        </div>

        <p v-if="path.cycleDetected" class="path-note">
          资料中的前置关系存在环（概念互相引用），已按入度最小优先断开，路径仍可使用。
        </p>
      </div>

      <!-- 空状态 -->
      <section v-else class="empty-card">
        <div class="empty-card__icon">
          <svg viewBox="0 0 64 64" width="56" height="56" fill="none">
            <circle cx="18" cy="20" r="7" stroke="#4A90D9" stroke-width="2" />
            <circle cx="46" cy="18" r="6" stroke="#5CC9C1" stroke-width="2" />
            <circle cx="34" cy="46" r="6" stroke="#4A90D9" stroke-width="2" />
            <path d="M24 22L40 20M22 26L30 40M40 24L36 40" stroke="#cbd5e1" stroke-width="2" />
          </svg>
        </div>
        <h3>{{ graph && graph.nodeCount === 0 ? '还没有知识图谱' : '正在加载' }}</h3>
        <p>
          点击右上角「构建知识图谱」，系统会调用大模型阅读
          「{{ currentGroupName }}」的全部资料，抽取知识点与先修关系。
        </p>
        <el-button type="primary" size="large" @click="handleBuild">立即构建</el-button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.graph-page {
  max-width: 1320px;
  margin: 0 auto;
  padding: 8px 4px 48px;
}

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}

.eyebrow {
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-muted);
  font-weight: 600;
}

.page-head h1 {
  margin: 6px 0 8px;
  font-size: 30px;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}

.page-head p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  max-width: 620px;
  line-height: 1.65;
}

.page-head__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.group-select {
  width: 230px;
}

/* ── 统计卡 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-shadow: var(--shadow-xs);
}

.stat-card__label {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.stat-card__value {
  font-size: 26px;
  line-height: 1.15;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.stat-card__value--strong {
  color: #0f9d76;
}

.stat-card__value--weak {
  color: #dc2626;
}

.stat-card__hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 标签栏 ── */
.tab-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border-subtle);
  padding-bottom: 10px;
}

.tab-bar__item {
  border: none;
  background: transparent;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.18s ease;
}

.tab-bar__item:hover {
  background: var(--surface-muted);
}

.tab-bar__item.active {
  background: var(--surface-accent);
  color: var(--brand-primary-dark);
}

.tab-bar__meta {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 构建中 ── */
.build-card {
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: 26px 28px;
  box-shadow: var(--shadow-sm);
}

.build-card__head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.build-card__head h3 {
  margin: 0 0 6px;
  font-size: 17px;
  color: var(--text-primary);
}

.build-card__head p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.build-card__spinner {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2.5px solid var(--surface-accent);
  border-top-color: var(--brand-primary);
  animation: spin 0.9s linear infinite;
  flex: none;
  margin-top: 2px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.build-card__status {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 图谱布局 ── */
.graph-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 16px;
  align-items: start;
}

.canvas-card {
  position: relative;
  background: radial-gradient(circle at 30% 20%, #ffffff 0%, #f8fafc 60%, #f1f5f9 100%);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.canvas {
  display: block;
  width: 100%;
  height: 620px;
  cursor: default;
  touch-action: none;
  user-select: none;
  shape-rendering: geometricPrecision;
}

.node {
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.node circle {
  transition:
    stroke-width 0.15s ease,
    fill-opacity 0.15s ease;
}

.node:hover .node__body {
  fill-opacity: 0.3;
}

/* 薄弱知识点：呼吸光环，静态时也能一眼看到 */
.node__halo {
  fill: none;
  stroke-width: 1.4;
  pointer-events: none;
  animation: haloPulse 2.8s ease-in-out infinite;
}

@keyframes haloPulse {
  0%,
  100% {
    opacity: 0.1;
  }
  50% {
    opacity: 0.42;
  }
}

.node__label {
  font-size: 12.5px;
  font-weight: 600;
  fill: #334155;
  text-anchor: middle;
  paint-order: stroke;
  stroke: #ffffff;
  stroke-width: 3.4px;
  stroke-linejoin: round;
  pointer-events: none;
}

.node__label--active {
  fill: var(--brand-primary-dark);
}

.canvas__legend {
  position: absolute;
  left: 16px;
  bottom: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(6px);
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 11.5px;
  color: var(--text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.legend-sep {
  width: 1px;
  height: 12px;
  background: var(--border-default);
}

.canvas__toolbar {
  position: absolute;
  right: 14px;
  top: 14px;
}

.ghost-btn {
  border: 1px solid var(--border-default);
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-secondary);
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.ghost-btn:hover {
  border-color: var(--brand-primary-light);
  color: var(--brand-primary-dark);
}

/* ── 详情卡 ── */
.detail-card {
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 22px 22px 24px;
  min-height: 320px;
  position: sticky;
  top: 16px;
}

.detail-card__title {
  margin: 0 0 14px;
  font-size: 16px;
  color: var(--text-primary);
}

.detail-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.detail-card__head h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
  line-height: 1.35;
}

.mastery-pill {
  flex: none;
  font-size: 11.5px;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid currentColor;
  background: rgba(255, 255, 255, 0.6);
}

.detail-card__summary {
  margin: 12px 0 16px;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--text-secondary);
}

.detail-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  background: var(--surface-subtle);
  border-radius: var(--radius-md);
  padding: 12px 10px;
  margin-bottom: 18px;
}

.detail-metrics div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: center;
}

.detail-metrics span {
  font-size: 11.5px;
  color: var(--text-muted);
}

.detail-metrics strong {
  font-size: 15px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.detail-card__section {
  margin: 18px 0 10px;
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--text-muted);
  text-transform: uppercase;
}

.relation-list,
.mastery-list,
.weak-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.relation-list li,
.mastery-list li,
.weak-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.relation-tag {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 6px;
  background: var(--surface-muted);
  color: var(--text-secondary);
}

.relation-tag--prerequisite_of {
  background: #eef2f7;
  color: #475569;
}

.relation-tag--part_of {
  background: #f3eeff;
  color: #7c5cd6;
}

.relation-tag--related_to {
  background: #f1f5f9;
  color: #64748b;
}

.relation-name {
  color: var(--text-primary);
  font-weight: 500;
}

.detail-card__empty {
  font-size: 12.5px;
  color: var(--text-muted);
  margin: 0;
}

.doc-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.doc-chip {
  font-size: 11.5px;
  color: var(--text-secondary);
  background: var(--surface-muted);
  border-radius: 6px;
  padding: 3px 8px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mastery-list li strong {
  margin-left: auto;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.weak-list__name {
  color: var(--text-primary);
  font-weight: 500;
}

.weak-list__rate {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.text-btn {
  margin-top: 20px;
  border: none;
  background: none;
  padding: 0;
  color: var(--brand-primary-dark);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

/* ── 学习路径 ── */
.path-layout {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.weak-panel {
  background: linear-gradient(135deg, #fff8f0 0%, #ffffff 70%);
  border: 1px solid #fbe3c8;
  border-radius: var(--radius-lg);
  padding: 18px 22px;
}

.weak-panel__head h3 {
  margin: 0 0 4px;
  font-size: 15px;
  color: #b45309;
}

.weak-panel__head p {
  margin: 0 0 12px;
  font-size: 12.5px;
  color: #a16207;
}

.weak-panel__items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.weak-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #fde3c4;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12.5px;
  color: #92400e;
  font-weight: 600;
}

.weak-pill em {
  font-style: normal;
  font-weight: 500;
  color: #c2410c;
  font-variant-numeric: tabular-nums;
}

.path-timeline {
  display: flex;
  flex-direction: column;
}

.stage {
  display: flex;
  gap: 16px;
}

.stage__rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 34px;
  flex: none;
}

.stage__badge {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--brand-primary);
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 0 4px rgba(74, 144, 217, 0.12);
}

.stage__line {
  flex: 1;
  width: 2px;
  background: linear-gradient(to bottom, #dbe6f3, #eef2f7);
  margin: 6px 0;
  min-height: 20px;
}

.stage:last-child .stage__line {
  background: transparent;
}

.stage__body {
  flex: 1;
  background: var(--surface-white);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: 18px 20px 20px;
  box-shadow: var(--shadow-xs);
  margin-bottom: 14px;
}

.stage__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.stage__head h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary);
}

.stage__count {
  font-size: 12px;
  color: var(--text-muted);
}

.stage__desc {
  margin: 6px 0 14px;
  font-size: 12.5px;
  color: var(--text-muted);
}

.stage__nodes {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(186px, 1fr));
  gap: 8px;
}

.path-node {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--surface-subtle);
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  padding: 9px 12px;
  cursor: pointer;
  text-align: left;
  transition: all 0.16s ease;
}

.path-node:hover {
  border-color: var(--brand-primary-light);
  background: var(--surface-accent);
}

.path-node--active {
  border-color: var(--brand-primary);
  background: var(--surface-accent);
}

.path-node__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}

.path-node__name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path-node__meta {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted);
  flex: none;
  font-variant-numeric: tabular-nums;
}

.path-note {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

/* ── 空状态 ── */
.empty-card {
  background: var(--surface-white);
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-lg);
  padding: 56px 24px;
  text-align: center;
}

.empty-card__icon {
  display: flex;
  justify-content: center;
  margin-bottom: 14px;
}

.empty-card h3 {
  margin: 0 0 8px;
  font-size: 17px;
  color: var(--text-primary);
}

.empty-card p {
  margin: 0 auto 20px;
  max-width: 520px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--text-secondary);
}

@media (max-width: 1080px) {
  .graph-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .detail-card {
    position: static;
  }

  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
