<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { extractApiError } from '@/api/http'
import { fetchGroups } from '@/api/group'
import {
  DIFFICULTY_LABELS,
  QUESTION_TYPE_LABELS,
  deleteExercise,
  fetchExerciseStats,
  fetchExercises,
  generateExercises,
  submitAnswer,
  type Difficulty,
  type ExerciseQuestion,
  type ExerciseStats,
  type QuestionType,
} from '@/api/exercise'

const route = useRoute()
const router = useRouter()

// ── 群组 ──
interface GroupOption {
  id: number
  groupName: string
}
const groups = ref<GroupOption[]>([])
const currentGroupId = ref<number | null>(null)

// ── 数据 ──
const questions = ref<ExerciseQuestion[]>([])
const stats = ref<ExerciseStats | null>(null)
const loading = ref(false)
const generating = ref(false)

// ── 筛选 ──
const filters = reactive({
  questionType: '' as QuestionType | '',
  difficulty: '' as Difficulty | '',
  onlyWrong: false,
  keyword: '',
})

// ── 刷题状态 ──
const answers = reactive<Record<number, string>>({})
const results = reactive<Record<number, { correct: boolean; answer: string; analysis: string | null }>>(
  {},
)
const expanded = reactive<Record<number, boolean>>({})
/** 当前卡片下标 */
const currentIndex = ref(0)
/** 当前展示的题目 */
const currentQuestion = computed(() => questions.value[currentIndex.value] ?? null)

/** 选中选项（已判定后不可改） */
function pickOption(option: string) {
  const question = currentQuestion.value
  if (!question || results[question.id]) return
  answers[question.id] = option
}

/** 上一题 */
function prevCard() {
  if (currentIndex.value > 0) currentIndex.value -= 1
}

/** 下一题 */
function nextCard() {
  if (currentIndex.value < questions.value.length - 1) currentIndex.value += 1
}

const currentGroupName = computed(
  () => groups.value.find((g) => g.id === currentGroupId.value)?.groupName ?? '',
)

/** 加载群组列表，默认选中路由参数或第一个 */
async function loadGroups() {
  try {
    const response = await fetchGroups()
    const owned = (response.ownedGroups ?? []).map((g) => ({ id: g.groupId, groupName: g.groupName }))
    const joined = (response.joinedGroups ?? []).map((g) => ({ id: g.groupId, groupName: g.groupName }))
    const merged = [...owned, ...joined]
    const unique = new Map(merged.map((g) => [g.id, g]))
    groups.value = [...unique.values()].sort((a, b) => a.groupName.localeCompare(b.groupName))

    const fromRoute = Number(route.query.groupId)
    if (fromRoute && groups.value.some((g) => g.id === fromRoute)) {
      currentGroupId.value = fromRoute
    } else if (groups.value.length > 0) {
      currentGroupId.value = groups.value[0]?.id ?? null
    }
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载群组失败'))
  }
}

/** 加载习题与统计 */
async function loadData() {
  if (!currentGroupId.value) return
  loading.value = true
  try {
    const [list, stat] = await Promise.all([
      fetchExercises({
        groupId: currentGroupId.value,
        questionType: filters.questionType || undefined,
        difficulty: filters.difficulty || undefined,
        onlyWrong: filters.onlyWrong || undefined,
        keyword: filters.keyword.trim() || undefined,
      }),
      fetchExerciseStats(currentGroupId.value),
    ])
    questions.value = list
    stats.value = stat
    // 保持当前题目可见，超出范围则回到第一题
    if (currentIndex.value >= list.length) {
      currentIndex.value = 0
    }
  } catch (error) {
    ElMessage.error(extractApiError(error, '加载习题失败'))
  } finally {
    loading.value = false
  }
}

/** 从群组资料抽题 */
async function handleGenerate() {
  if (!currentGroupId.value) return
  try {
    await ElMessageBox.confirm(
      `将从「${currentGroupName.value}」中所有已就绪的资料里抽取 10 道习题，需要调用大模型，大约耗时 30 秒。是否继续？`,
      '生成习题',
      { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }

  generating.value = true
  try {
    const inserted = await generateExercises({
      groupId: currentGroupId.value,
      questionCount: 10,
    })
    ElMessage.success(`成功生成 ${inserted} 道习题`)
    await loadData()
  } catch (error) {
    ElMessage.error(extractApiError(error, '生成习题失败'))
  } finally {
    generating.value = false
  }
}

/** 提交单题答案 */
async function handleSubmit(question: ExerciseQuestion) {
  const answer = (answers[question.id] ?? '').trim()
  if (!answer) {
    ElMessage.warning('请先作答')
    return
  }
  try {
    const result = await submitAnswer(question.id, answer)
    results[question.id] = {
      correct: result.correct,
      answer: result.answer,
      analysis: result.analysis,
    }
    expanded[question.id] = true
    ElMessage[result.correct ? 'success' : 'warning'](result.correct ? '回答正确' : '回答错误')
    await loadData()
  } catch (error) {
    ElMessage.error(extractApiError(error, '提交失败'))
  }
}

/** 删除习题 */
async function handleDelete(question: ExerciseQuestion) {
  try {
    await ElMessageBox.confirm('确定删除这道习题？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteExercise(question.id)
    ElMessage.success('已删除')
    await loadData()
  } catch (error) {
    ElMessage.error(extractApiError(error, '删除失败'))
  }
}

/** 切换群组时跳到对应路由并重载 */
watch(currentGroupId, (groupId) => {
  if (!groupId) return
  router.replace({ query: { ...route.query, groupId } })
  Object.keys(answers).forEach((key) => delete answers[Number(key)])
  Object.keys(results).forEach((key) => delete results[Number(key)])
  loadData()
})

onMounted(async () => {
  await loadGroups()
  await loadData()
})
</script>

<template>
  <div class="exercise-page">
    <header class="exercise-header">
      <div class="exercise-header__title">
        <h1>习题库</h1>
        <p>从小组资料中抽取习题，边刷边复习</p>
      </div>
      <div class="exercise-header__actions">
        <el-select v-model="currentGroupId" placeholder="选择小组" class="group-select" filterable>
          <el-option v-for="g in groups" :key="g.id" :label="g.groupName" :value="g.id" />
        </el-select>
        <el-button type="primary" :loading="generating" @click="handleGenerate">生成习题</el-button>
      </div>
    </header>

    <!-- 统计 -->
    <section v-if="stats" class="stats-row">
      <div class="stat-card">
        <span class="stat-card__value">{{ stats.totalQuestions }}</span>
        <span class="stat-card__label">题库总量</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__value">{{ stats.answeredCount }}</span>
        <span class="stat-card__label">已作答</span>
      </div>
      <div class="stat-card">
        <span class="stat-card__value">{{ stats.correctCount }}</span>
        <span class="stat-card__label">已掌握</span>
      </div>
      <div class="stat-card stat-card--accent">
        <span class="stat-card__value">{{ (stats.accuracy * 100).toFixed(0) }}%</span>
        <span class="stat-card__label">正确率</span>
      </div>
    </section>

    <!-- 筛选 -->
    <section class="filters">
      <el-select v-model="filters.questionType" placeholder="全部题型" clearable class="filter-item">
        <el-option
          v-for="(label, key) in QUESTION_TYPE_LABELS"
          :key="key"
          :label="label"
          :value="key"
        />
      </el-select>
      <el-select v-model="filters.difficulty" placeholder="全部难度" clearable class="filter-item">
        <el-option
          v-for="(label, key) in DIFFICULTY_LABELS"
          :key="key"
          :label="label"
          :value="key"
        />
      </el-select>
      <el-input
        v-model="filters.keyword"
        placeholder="搜索题干关键词"
        clearable
        class="filter-item filter-item--wide"
        @keyup.enter="loadData"
      />
      <el-checkbox v-model="filters.onlyWrong" label="只看错题" />
      <el-button @click="loadData">查询</el-button>
    </section>

    <!-- 卡片式刷题：一次一题，可切换 -->
    <section v-loading="loading" class="card-stage">
      <el-empty
        v-if="!loading && questions.length === 0"
        description="还没有习题，点击右上角「生成习题」从资料中抽取"
      />

      <template v-else-if="currentQuestion">
        <!-- 顶部进度与导航 -->
        <div class="card-nav">
          <div class="card-nav__progress">
            <span class="card-nav__index">{{ currentIndex + 1 }} / {{ questions.length }}</span>
            <el-progress
              :percentage="((currentIndex + 1) / questions.length) * 100"
              :show-text="false"
              :stroke-width="4"
              class="card-nav__bar"
            />
          </div>
          <div class="card-nav__buttons">
            <el-button :disabled="currentIndex === 0" @click="prevCard">上一题</el-button>
            <el-button :disabled="currentIndex >= questions.length - 1" @click="nextCard">
              下一题
            </el-button>
          </div>
        </div>

        <!-- 题目卡片 -->
        <article :key="currentQuestion.id" class="qcard">
          <div class="qcard__meta">
            <el-tag size="small" type="info">
              {{ QUESTION_TYPE_LABELS[currentQuestion.questionType] }}
            </el-tag>
            <el-tag
              size="small"
              :type="
                currentQuestion.difficulty === 'EASY'
                  ? 'success'
                  : currentQuestion.difficulty === 'HARD'
                    ? 'danger'
                    : 'warning'
              "
            >
              {{ DIFFICULTY_LABELS[currentQuestion.difficulty] }}
            </el-tag>
            <el-tag v-if="currentQuestion.lastCorrect === true" size="small" type="success">
              已掌握
            </el-tag>
            <el-tag v-else-if="currentQuestion.lastCorrect === false" size="small" type="danger">
              曾答错
            </el-tag>
            <el-button
              link
              type="danger"
              size="small"
              class="qcard__delete"
              @click="handleDelete(currentQuestion)"
            >
              删除
            </el-button>
          </div>

          <p class="qcard__stem">{{ currentQuestion.stem }}</p>

          <!-- 选择题 -->
          <div v-if="currentQuestion.options.length > 0" class="qcard__options">
            <div
              v-for="(option, index) in currentQuestion.options"
              :key="index"
              :class="[
                'option-chip',
                answers[currentQuestion.id] === option ? 'option-chip--picked' : '',
                results[currentQuestion.id]
                  ? option === results[currentQuestion.id]?.answer
                    ? 'option-chip--right'
                    : answers[currentQuestion.id] === option
                      ? 'option-chip--wrong'
                      : ''
                  : '',
              ]"
              @click="pickOption(option)"
            >
              {{ option }}
            </div>
          </div>

          <!-- 判断题 -->
          <div v-else-if="currentQuestion.questionType === 'TRUE_FALSE'" class="qcard__options qcard__options--row">
            <div
              v-for="option in ['正确', '错误']"
              :key="option"
              :class="[
                'option-chip',
                answers[currentQuestion.id] === option ? 'option-chip--picked' : '',
                results[currentQuestion.id]
                  ? option === results[currentQuestion.id]?.answer
                    ? 'option-chip--right'
                    : answers[currentQuestion.id] === option
                      ? 'option-chip--wrong'
                      : ''
                  : '',
              ]"
              @click="pickOption(option)"
            >
              {{ option }}
            </div>
          </div>

          <!-- 简答题 -->
          <el-input
            v-else
            v-model="answers[currentQuestion.id]"
            type="textarea"
            :rows="4"
            placeholder="请输入你的答案"
            :disabled="!!results[currentQuestion.id]"
            class="qcard__textarea"
          />

          <div class="qcard__footer">
            <el-button
              type="primary"
              :disabled="!!results[currentQuestion.id]"
              @click="handleSubmit(currentQuestion)"
            >
              提交答案
            </el-button>
            <el-button link @click="expanded[currentQuestion.id] = !expanded[currentQuestion.id]">
              {{ expanded[currentQuestion.id] ? '收起解析' : '查看答案与解析' }}
            </el-button>
            <span v-if="currentQuestion.sourceDocumentName" class="qcard__source">
              来源：{{ currentQuestion.sourceDocumentName }}
            </span>
          </div>

          <!-- 判定与解析 -->
          <div v-if="results[currentQuestion.id] || expanded[currentQuestion.id]" class="answer-panel">
            <p v-if="results[currentQuestion.id]" class="answer-panel__verdict">
              <span :class="results[currentQuestion.id]?.correct ? 'verdict--ok' : 'verdict--bad'">
                {{ results[currentQuestion.id]?.correct ? '✓ 回答正确' : '✗ 回答错误' }}
              </span>
            </p>
            <p class="answer-panel__row">
              <strong>标准答案：</strong>
              {{ results[currentQuestion.id]?.answer ?? currentQuestion.answer }}
            </p>
            <p
              v-if="results[currentQuestion.id]?.analysis ?? currentQuestion.analysis"
              class="answer-panel__row"
            >
              <strong>解析：</strong>
              {{ results[currentQuestion.id]?.analysis ?? currentQuestion.analysis }}
            </p>
            <p v-if="currentQuestion.sourceSnippet" class="answer-panel__snippet">
              <strong>原文依据：</strong>{{ currentQuestion.sourceSnippet }}
            </p>
            <p v-if="currentQuestion.tags.length > 0" class="answer-panel__tags">
              <el-tag v-for="tag in currentQuestion.tags" :key="tag" size="small" effect="plain">
                {{ tag }}
              </el-tag>
            </p>
          </div>
        </article>
      </template>
    </section>
  </div>
</template>

<style scoped>
.exercise-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 32px 24px 60px;
}

/* ── 卡片式刷题 ── */
.card-stage {
  min-height: 380px;
}

.card-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 16px;
}

.card-nav__progress {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-nav__index {
  font-size: 14px;
  font-weight: 600;
  color: #4b5563;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.card-nav__bar {
  flex: 1;
}

.card-nav__buttons {
  display: flex;
  gap: 8px;
}

.qcard {
  padding: 28px 32px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid #eef0f3;
  box-shadow: 0 4px 24px rgba(17, 24, 39, 0.06);
  animation: card-in 0.22s ease-out;
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.qcard__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.qcard__delete {
  margin-left: auto;
}

.qcard__stem {
  margin: 0 0 24px;
  font-size: 16px;
  line-height: 1.8;
  color: #111827;
  white-space: pre-wrap;
}

.qcard__options {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}

.qcard__options--row {
  flex-direction: row;
}

.option-chip {
  padding: 14px 18px;
  border-radius: 10px;
  border: 1.5px solid #e5e7eb;
  background: #fafafa;
  font-size: 14px;
  line-height: 1.5;
  color: #374151;
  cursor: pointer;
  transition: all 0.15s;
  flex: 1;
}

.qcard__options--row .option-chip {
  text-align: center;
}

.option-chip:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
}

.option-chip--picked {
  border-color: #3b82f6;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 500;
}

.option-chip--right {
  border-color: #22c55e;
  background: #f0fdf4;
  color: #15803d;
  font-weight: 500;
}

.option-chip--wrong {
  border-color: #ef4444;
  background: #fef2f2;
  color: #b91c1c;
}

.qcard__textarea {
  margin-bottom: 24px;
}

.qcard__footer {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.qcard__source {
  margin-left: auto;
  font-size: 12px;
  color: #9ca3af;
}

.exercise-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.exercise-header__title h1 {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 600;
  color: #1f2937;
}

.exercise-header__title p {
  margin: 0;
  color: #6b7280;
  font-size: 14px;
}

.exercise-header__actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.group-select {
  width: 180px;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 16px;
  border-radius: 12px;
  background: #f9fafb;
  border: 1px solid #eef0f3;
}

.stat-card--accent {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.stat-card__value {
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
}

.stat-card__label {
  font-size: 13px;
  color: #6b7280;
}

.filters {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.filter-item {
  width: 140px;
}

.filter-item--wide {
  width: 220px;
}


.answer-panel {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 10px;
  background: #f9fafb;
  font-size: 14px;
  line-height: 1.7;
}

.answer-panel__verdict {
  margin: 0 0 8px;
  font-weight: 600;
}

.verdict--ok {
  color: #16a34a;
}

.verdict--bad {
  color: #dc2626;
}

.answer-panel__row {
  margin: 0 0 8px;
  color: #374151;
  white-space: pre-wrap;
}

.answer-panel__snippet {
  margin: 0 0 8px;
  color: #6b7280;
  font-style: italic;
}

.answer-panel__tags {
  margin: 8px 0 0;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .exercise-header {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
