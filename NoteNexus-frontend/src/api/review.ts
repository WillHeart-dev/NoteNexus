import http from './http'

/**
 * 复习文档（AI 汇总小组资料生成的可下载 Markdown 文档）
 *
 * 生成过程异步：创建后状态为 PENDING，前端轮询该记录直到 COMPLETED 或 FAILED。
 */
export interface ReviewDocumentItem {
  reviewId: number
  groupId: number
  title: string
  status: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED'
  sourceDocumentCount: number
  chunkCount: number
  fileName: string
  fileSize: number
  failureReason: string | null
  createdBy: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

/** 统一响应包装 */
interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string | null
}

/** 查询小组的复习文档列表 */
export async function fetchReviewDocuments(groupId: number): Promise<ReviewDocumentItem[]> {
  const { data } = await http.get<ApiEnvelope<ReviewDocumentItem[]>>('/review-documents', {
    params: { groupId },
  })
  return data.data ?? []
}

/** 触发一次复习文档生成（异步） */
export async function createReviewDocument(groupId: number): Promise<ReviewDocumentItem> {
  const { data } = await http.post<ApiEnvelope<ReviewDocumentItem>>('/review-documents', { groupId })
  return data.data
}

/** 查询单份复习文档（用于轮询进度） */
export async function fetchReviewDocument(reviewId: number): Promise<ReviewDocumentItem> {
  const { data } = await http.get<ApiEnvelope<ReviewDocumentItem>>(`/review-documents/${reviewId}`)
  return data.data
}

/** 删除复习文档 */
export async function deleteReviewDocument(reviewId: number): Promise<void> {
  await http.delete(`/review-documents/${reviewId}`)
}

/** 下载复习文档（Markdown） */
export async function downloadReviewDocument(reviewId: number): Promise<Blob> {
  const response = await http.get(`/review-documents/${reviewId}/download`, {
    responseType: 'blob',
    timeout: 120000,
  })
  return response.data as Blob
}

/** 状态是否仍在生成中 */
export function isGeneratingStatus(status: ReviewDocumentItem['status']): boolean {
  return status === 'PENDING' || status === 'GENERATING'
}
