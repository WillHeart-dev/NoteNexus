import http from './http'

/**
 * 知识图谱与学习路径 API
 *
 * 知识点与关系由 AI 从群组资料中抽取，节点按归一化概念名合并；
 * 学习路径以前置关系做拓扑分层，并结合当前用户的做题情况排序。
 */

/** 掌握度档位 */
export type MasteryLevel = 'UNTOUCHED' | 'WEAK' | 'MEDIUM' | 'STRONG'

/** 关系类型 */
export type RelationType = 'PREREQUISITE_OF' | 'PART_OF' | 'RELATED_TO'

/** 构建任务状态 */
export type GraphTaskStatus = 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED'

/** 图谱节点 */
export interface GraphNode {
  id: number
  name: string
  summary: string
  importance: number
  documentCount: number
  documents: string[]
  mastery: MasteryLevel
  accuracy: number | null
  questionCount: number
}

/** 图谱关系 */
export interface GraphEdge {
  id: number
  sourceId: number
  targetId: number
  sourceName: string
  targetName: string
  relation: RelationType
  weight: number
  evidence: string
}

/** 图谱整体视图 */
export interface GraphView {
  groupId: number
  nodeCount: number
  edgeCount: number
  lastBuiltAt: string | null
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** 构建任务 */
export interface GraphTask {
  id: number
  groupId: number
  status: GraphTaskStatus
  documentCount: number
  nodeCount: number
  edgeCount: number
  failureReason: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string | null
}

/** 学习路径中的知识点 */
export interface LearningPathNode {
  id: number
  name: string
  summary: string
  importance: number
  mastery: MasteryLevel
  accuracy: number | null
  questionCount: number
  prerequisiteCount: number
}

/** 学习路径阶段 */
export interface LearningPathStage {
  level: number
  title: string
  description: string
  count: number
  nodes: LearningPathNode[]
}

/** 学习路径 */
export interface LearningPath {
  groupId: number
  nodeCount: number
  edgeCount: number
  masteredCount: number
  weakCount: number
  cycleDetected: boolean
  stages: LearningPathStage[]
  weakPoints: LearningPathNode[]
  generatedAt: string
}

/** 统一响应包装 */
interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string | null
}

/** 发起图谱构建 */
export async function buildGraph(groupId: number): Promise<GraphTask> {
  const { data } = await http.post<ApiEnvelope<GraphTask>>('/graph/build', { groupId })
  return data.data
}

/** 查询构建任务状态 */
export async function fetchGraphTask(taskId: number): Promise<GraphTask> {
  const { data } = await http.get<ApiEnvelope<GraphTask>>(`/graph/tasks/${taskId}`)
  return data.data
}

/** 查询最近一次构建任务（页面刷新后恢复进度） */
export async function fetchLatestGraphTask(groupId: number): Promise<GraphTask | null> {
  const { data } = await http.get<ApiEnvelope<GraphTask | null>>('/graph/tasks/latest', {
    params: { groupId },
  })
  return data.data
}

/** 查询知识图谱 */
export async function fetchGraph(groupId: number): Promise<GraphView> {
  const { data } = await http.get<ApiEnvelope<GraphView>>('/graph', { params: { groupId } })
  return data.data
}

/** 查询学习路径 */
export async function fetchLearningPath(groupId: number): Promise<LearningPath> {
  const { data } = await http.get<ApiEnvelope<LearningPath>>('/graph/learning-path', {
    params: { groupId },
  })
  return data.data
}

/** 掌握度中文标签 */
export const MASTERY_LABELS: Record<MasteryLevel, string> = {
  UNTOUCHED: '未检测',
  WEAK: '薄弱',
  MEDIUM: '一般',
  STRONG: '已掌握',
}

/** 掌握度配色 */
export const MASTERY_COLORS: Record<MasteryLevel, string> = {
  UNTOUCHED: '#94a3b8',
  WEAK: '#ef4444',
  MEDIUM: '#f59e0b',
  STRONG: '#10b981',
}

/** 关系中文标签 */
export const RELATION_LABELS: Record<RelationType, string> = {
  PREREQUISITE_OF: '先修',
  PART_OF: '组成',
  RELATED_TO: '相关',
}
