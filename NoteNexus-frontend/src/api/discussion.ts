import http from './http'

/**
 * 知识库评论 API
 *
 * 评论以群组（知识库）为单位存放，入口在学习资料模块底部，
 * 用于成员对这个知识库整体发表看法，支持引用回复与撤回。
 * 所有接口都会校验调用者是目标群组成员。
 */

/** 评论消息 */
export interface DiscussionMessage {
  id: number
  groupId: number
  userId: number
  displayName: string
  content: string
  replyToId: number | null
  replyToContent: string | null
  replyToName: string | null
  mine: boolean
  createdAt: string
}

/** 统一响应包装 */
interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string | null
}

/**
 * 查询知识库评论（按时间倒序）
 *
 * @param groupId 群组 ID
 * @param beforeId 游标，只返回 ID 小于该值的消息，用于向前翻页
 * @param limit 返回条数，默认 50
 */
export async function fetchDiscussionMessages(
  groupId: number,
  beforeId?: number,
  limit = 50,
): Promise<DiscussionMessage[]> {
  const { data } = await http.get<ApiEnvelope<DiscussionMessage[]>>('/discussions', {
    params: { groupId, beforeId, limit },
  })
  return data.data ?? []
}

/** 发布一条知识库评论 */
export async function sendDiscussionMessage(params: {
  groupId: number
  content: string
  replyToId?: number | null
}): Promise<DiscussionMessage> {
  const { data } = await http.post<ApiEnvelope<DiscussionMessage>>('/discussions', params)
  return data.data
}

/** 撤回自己发布的评论 */
export async function revokeDiscussionMessage(messageId: number): Promise<void> {
  await http.delete(`/discussions/${messageId}`)
}
