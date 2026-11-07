import http from './http'

/**
 * 习题库 API
 *
 * 习题由 AI 从群组资料中抽取，每题保留来源资料与原文依据片段，
 * 支持按题型/难度/关键词筛选刷题，并即时判定答案。
 */

/** 题型 */
export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'SHORT_ANSWER'

/** 难度 */
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'

/** 习题条目 */
export interface ExerciseQuestion {
  id: number
  groupId: number
  questionType: QuestionType
  stem: string
  options: string[]
  answer: string
  analysis: string | null
  difficulty: Difficulty
  tags: string[]
  sourceSnippet: string | null
  sourceDocumentName: string | null
  answered: boolean
  lastCorrect: boolean | null
  createdAt: string
}

/** 作答判定结果 */
export interface SubmitAnswerResult {
  questionId: number
  correct: boolean
  answer: string
  analysis: string | null
}

/** 习题库统计 */
export interface ExerciseStats {
  totalQuestions: number
  answeredCount: number
  correctCount: number
  accuracy: number
}

/** 统一响应包装 */
interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string | null
}

/** 查询条件 */
export interface ExerciseQueryParams {
  groupId: number
  questionType?: QuestionType
  difficulty?: Difficulty
  onlyWrong?: boolean
  keyword?: string
}

/** 从群组资料中抽取习题，返回入库题量 */
export async function generateExercises(params: {
  groupId: number
  documentId?: number
  questionCount?: number
  questionType?: QuestionType
}): Promise<number> {
  const { data } = await http.post<ApiEnvelope<{ inserted: number }>>(
    '/exercises/generate',
    params,
    { timeout: 180000 },
  )
  return data.data?.inserted ?? 0
}

/** 按条件查询习题列表 */
export async function fetchExercises(params: ExerciseQueryParams): Promise<ExerciseQuestion[]> {
  const { data } = await http.get<ApiEnvelope<ExerciseQuestion[]>>('/exercises', { params })
  return data.data ?? []
}

/** 提交答案并即时判定 */
export async function submitAnswer(
  questionId: number,
  userAnswer: string,
): Promise<SubmitAnswerResult> {
  const { data } = await http.post<ApiEnvelope<SubmitAnswerResult>>('/exercises/submit', {
    questionId,
    userAnswer,
  })
  return data.data
}

/** 查询习题库统计概览 */
export async function fetchExerciseStats(groupId: number): Promise<ExerciseStats> {
  const { data } = await http.get<ApiEnvelope<ExerciseStats>>('/exercises/stats', {
    params: { groupId },
  })
  return data.data
}

/** 删除一道习题（仅群主） */
export async function deleteExercise(questionId: number): Promise<void> {
  await http.delete(`/exercises/${questionId}`)
}

/** 题型中文标签 */
export const QUESTION_TYPE_LABELS: Record<QuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTIPLE_CHOICE: '多选题',
  TRUE_FALSE: '判断题',
  SHORT_ANSWER: '简答题',
}

/** 难度中文标签 */
export const DIFFICULTY_LABELS: Record<Difficulty, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}
