import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { AgentTask, PageResult } from '../agent-team/types'
import {
  getLatestOfficialScanTask,
  getLatestScanTask,
  getPreviewAgentHistory,
  getPreviewTaskBundle,
  getScanHistory,
  getScanResults,
  getScanTask,
} from './api'
import { RESEARCH_PREVIEW_DEMO } from './demo'
import { normalizeScanCandidate } from './presentation'
import type {
  PreviewCandidate,
  PreviewIssue,
  PreviewMode,
  PreviewQualification,
  PreviewSection,
  PreviewTaskBundle,
  ScanTaskSnapshot,
} from './types'

const EMPTY_HISTORY: PageResult<AgentTask> = {
  content: [],
  page: 0,
  size: 100,
  total: 0,
}

const PREVIEW_SECTIONS = new Set<PreviewSection>([
  'overview',
  'agents',
  'evidence',
  'history',
  'report',
])

function safeError(error: unknown): string {
  if (!(error instanceof Error)) return '本地只读接口不可用'
  const message = error.message.trim()
  if (!message) return '本地只读接口不可用'
  return message.length <= 160 ? message : '本地只读接口不可用'
}

function safeTaskId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function safeSection(value: unknown): PreviewSection {
  return typeof value === 'string' && PREVIEW_SECTIONS.has(value as PreviewSection)
    ? value as PreviewSection
    : 'overview'
}

export function useResearchPreview() {
  const route = useRoute()
  const router = useRouter()
  const initialMode: PreviewMode = route.query.mode === 'demo'
    ? 'TEST_DEMO_EXPLICIT'
    : 'EXISTING_RESEARCH_SNAPSHOT'

  const mode = ref<PreviewMode>(initialMode)
  const section = ref<PreviewSection>(safeSection(route.query.section))
  const qualification = computed<PreviewQualification>(() =>
    mode.value === 'TEST_DEMO_EXPLICIT'
      ? 'TEST_DEMO_EXPLICIT'
      : 'RESEARCH_HISTORICAL_UNVERIFIED',
  )
  const synthetic = computed(() => mode.value === 'TEST_DEMO_EXPLICIT')
  const loading = ref(false)
  const scanLoading = ref(false)
  const taskLoading = ref(false)
  const scanHistory = shallowRef<ScanTaskSnapshot[]>([])
  const selectedScanTaskId = ref<number | null>(null)
  const selectedScanTask = shallowRef<ScanTaskSnapshot | null>(null)
  const candidates = shallowRef<PreviewCandidate[]>([])
  const history = shallowRef<PageResult<AgentTask>>({ ...EMPTY_HISTORY })
  const knownAgentTasks = shallowRef<AgentTask[]>([])
  const activeBundle = shallowRef<PreviewTaskBundle | null>(null)
  const selectedSymbol = ref<string | null>(null)
  const comparisonLeft = shallowRef<PreviewTaskBundle | null>(null)
  const comparisonRight = shallowRef<PreviewTaskBundle | null>(null)
  const issues = shallowRef<PreviewIssue[]>([])

  const knownAgentSymbols = computed(() =>
    new Set(knownAgentTasks.value.map((task) => task.symbol)),
  )
  const selectedCandidate = computed(() =>
    candidates.value.find((candidate) => candidate.symbol === selectedSymbol.value) ?? null,
  )

  function removeIssue(code: string): void {
    issues.value = issues.value.filter((issue) => issue.code !== code)
  }

  function addIssue(code: string, detail: string): void {
    removeIssue(code)
    issues.value = [...issues.value, { code, detail }]
  }

  function clearLocalState(): void {
    scanHistory.value = []
    selectedScanTaskId.value = null
    selectedScanTask.value = null
    candidates.value = []
    history.value = { ...EMPTY_HISTORY }
    knownAgentTasks.value = []
    activeBundle.value = null
    selectedSymbol.value = null
    comparisonLeft.value = null
    comparisonRight.value = null
    issues.value = []
  }

  function refreshCandidateAgentFlags(): void {
    const symbols = knownAgentSymbols.value
    candidates.value = candidates.value.map((candidate) => ({
      ...candidate,
      hasAgentResult: symbols.has(candidate.symbol),
    }))
  }

  async function updateRoute(taskId?: number): Promise<void> {
    const query: Record<string, string> = {
      mode: mode.value === 'TEST_DEMO_EXPLICIT' ? 'demo' : 'local',
      section: section.value,
    }
    if (mode.value === 'EXISTING_RESEARCH_SNAPSHOT' && taskId && taskId > 0) {
      query.taskId = String(taskId)
    }
    await router.replace({ path: '/research-preview', query })
  }

  function applyDemo(): void {
    clearLocalState()
    mode.value = 'TEST_DEMO_EXPLICIT'
    scanHistory.value = [RESEARCH_PREVIEW_DEMO.scanTask]
    selectedScanTask.value = RESEARCH_PREVIEW_DEMO.scanTask
    selectedScanTaskId.value = RESEARCH_PREVIEW_DEMO.scanTask.id ?? null
    candidates.value = RESEARCH_PREVIEW_DEMO.candidates
    knownAgentTasks.value = RESEARCH_PREVIEW_DEMO.bundles.map((bundle) => bundle.task)
    history.value = {
      content: knownAgentTasks.value,
      page: 0,
      size: 100,
      total: knownAgentTasks.value.length,
    }
    activeBundle.value = RESEARCH_PREVIEW_DEMO.bundles[0] ?? null
    selectedSymbol.value = activeBundle.value?.task.symbol ?? null
    comparisonLeft.value = RESEARCH_PREVIEW_DEMO.bundles[0] ?? null
    comparisonRight.value = RESEARCH_PREVIEW_DEMO.bundles[1] ?? null
  }

  async function loadHistory(page = 0): Promise<void> {
    if (mode.value !== 'EXISTING_RESEARCH_SNAPSHOT') return
    removeIssue('PREVIEW_LOCAL_API_UNAVAILABLE')
    try {
      const loaded = await getPreviewAgentHistory(page, 100)
      history.value = {
        content: loaded?.content ?? [],
        page: loaded?.page ?? page,
        size: loaded?.size ?? 100,
        total: loaded?.total ?? 0,
      }
      const byId = new Map(knownAgentTasks.value.map((task) => [task.id, task]))
      history.value.content.forEach((task) => byId.set(task.id, task))
      knownAgentTasks.value = [...byId.values()]
      refreshCandidateAgentFlags()
    } catch (error) {
      addIssue('PREVIEW_LOCAL_API_UNAVAILABLE', safeError(error))
    }
  }

  async function loadScan(taskId: number): Promise<void> {
    if (mode.value !== 'EXISTING_RESEARCH_SNAPSHOT') return
    scanLoading.value = true
    removeIssue('PREVIEW_SCAN_SNAPSHOT_UNAVAILABLE')
    removeIssue('PREVIEW_SCAN_RESULTS_EMPTY')
    try {
      const [task, rows] = await Promise.all([
        getScanTask(taskId),
        getScanResults(taskId, 200, false),
      ])
      selectedScanTask.value = task
      selectedScanTaskId.value = taskId
      candidates.value = (rows ?? []).flatMap((row) => {
        const candidate = normalizeScanCandidate(
          row,
          knownAgentSymbols.value,
          'RESEARCH_HISTORICAL_UNVERIFIED',
        )
        return candidate ? [candidate] : []
      })
      if (!candidates.value.some((candidate) => candidate.symbol === selectedSymbol.value)) {
        selectedSymbol.value = null
      }
      if (!candidates.value.length) {
        addIssue('PREVIEW_SCAN_RESULTS_EMPTY', '所选已有扫描任务没有可展示的结果。')
      }
    } catch (error) {
      selectedScanTask.value = null
      selectedScanTaskId.value = taskId
      candidates.value = []
      addIssue('PREVIEW_SCAN_SNAPSHOT_UNAVAILABLE', safeError(error))
    } finally {
      scanLoading.value = false
    }
  }

  async function loadBundle(taskId: number, updateUrl = true): Promise<PreviewTaskBundle | null> {
    if (mode.value === 'TEST_DEMO_EXPLICIT') {
      const bundle = RESEARCH_PREVIEW_DEMO.bundles.find((item) => item.task.id === taskId) ?? null
      if (bundle) {
        activeBundle.value = bundle
        selectedSymbol.value = bundle.task.symbol
      }
      return bundle
    }
    taskLoading.value = true
    removeIssue('PREVIEW_AGENT_RESULT_NOT_FOUND')
    try {
      const bundle = await getPreviewTaskBundle(taskId)
      activeBundle.value = bundle
      selectedSymbol.value = bundle.task.symbol
      if (updateUrl) await updateRoute(taskId)
      return bundle
    } catch (error) {
      activeBundle.value = null
      addIssue('PREVIEW_AGENT_RESULT_NOT_FOUND', safeError(error))
      return null
    } finally {
      taskLoading.value = false
    }
  }

  async function selectCandidate(candidate: PreviewCandidate): Promise<void> {
    selectedSymbol.value = candidate.symbol
    if (mode.value === 'TEST_DEMO_EXPLICIT') {
      const bundle = RESEARCH_PREVIEW_DEMO.bundles.find(
        (item) => item.task.symbol === candidate.symbol,
      )
      if (bundle) activeBundle.value = bundle
      return
    }
    const task = knownAgentTasks.value.find((item) => item.symbol === candidate.symbol)
    if (!task) {
      activeBundle.value = null
      addIssue(
        'PREVIEW_AGENT_RESULT_NOT_FOUND',
        `${candidate.symbol}在已加载的Agent历史中没有匹配任务；页面不会自动创建任务。`,
      )
      return
    }
    await loadBundle(task.id)
  }

  async function setComparison(
    side: 'left' | 'right',
    taskId: number,
  ): Promise<void> {
    let bundle: PreviewTaskBundle | null
    if (mode.value === 'TEST_DEMO_EXPLICIT') {
      bundle = RESEARCH_PREVIEW_DEMO.bundles.find((item) => item.task.id === taskId) ?? null
    } else {
      bundle = await getPreviewTaskBundle(taskId).catch(() => null)
    }
    if (!bundle) {
      addIssue('PREVIEW_AGENT_RESULT_NOT_FOUND', `任务${taskId}无法用于只读对比。`)
      return
    }
    if (side === 'left') comparisonLeft.value = bundle
    else comparisonRight.value = bundle
  }

  async function loadLocal(): Promise<void> {
    clearLocalState()
    mode.value = 'EXISTING_RESEARCH_SNAPSHOT'
    loading.value = true
    try {
      const [loadedHistory, scans, official, latest] = await Promise.all([
        getPreviewAgentHistory(0, 100),
        getScanHistory(50),
        getLatestOfficialScanTask(),
        getLatestScanTask(),
      ])
      history.value = {
        content: loadedHistory?.content ?? [],
        page: loadedHistory?.page ?? 0,
        size: loadedHistory?.size ?? 100,
        total: loadedHistory?.total ?? 0,
      }
      knownAgentTasks.value = history.value.content
      scanHistory.value = scans ?? []
      const defaultTask = official?.id
        ? official
        : latest?.id
          ? latest
          : scanHistory.value[0] ?? null
      if (defaultTask?.id) {
        await loadScan(defaultTask.id)
      } else {
        addIssue('PREVIEW_SCAN_SNAPSHOT_UNAVAILABLE', '没有可读取的既有扫描任务。')
      }
      const taskId = safeTaskId(route.query.taskId)
      if (taskId) {
        await loadBundle(taskId, false)
      } else if (history.value.content[0]) {
        await loadBundle(history.value.content[0].id, false)
      }
      await updateRoute(activeBundle.value?.task.id)
    } catch (error) {
      addIssue('PREVIEW_LOCAL_API_UNAVAILABLE', safeError(error))
    } finally {
      loading.value = false
    }
  }

  async function switchMode(nextMode: PreviewMode): Promise<void> {
    if (nextMode === mode.value) return
    if (nextMode === 'TEST_DEMO_EXPLICIT') {
      applyDemo()
      await updateRoute()
      return
    }
    await loadLocal()
  }

  async function setSection(nextSection: PreviewSection): Promise<void> {
    section.value = PREVIEW_SECTIONS.has(nextSection) ? nextSection : 'overview'
    await updateRoute(activeBundle.value?.task.id)
  }

  async function reloadExistingSnapshots(): Promise<void> {
    if (mode.value === 'EXISTING_RESEARCH_SNAPSHOT') await loadLocal()
  }

  onMounted(async () => {
    if (mode.value === 'TEST_DEMO_EXPLICIT') {
      applyDemo()
      await updateRoute()
    } else {
      await loadLocal()
    }
  })

  return {
    mode,
    section,
    qualification,
    synthetic,
    loading,
    scanLoading,
    taskLoading,
    scanHistory,
    selectedScanTaskId,
    selectedScanTask,
    candidates,
    history,
    activeBundle,
    selectedSymbol,
    selectedCandidate,
    comparisonLeft,
    comparisonRight,
    issues,
    switchMode,
    setSection,
    reloadExistingSnapshots,
    loadHistory,
    loadScan,
    loadBundle,
    selectCandidate,
    setComparison,
  }
}
