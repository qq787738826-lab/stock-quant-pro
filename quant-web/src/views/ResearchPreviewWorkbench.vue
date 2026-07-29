<script setup lang="ts">
import { computed } from 'vue'
import AgentMetricsChart from '../components/research-preview/AgentMetricsChart.vue'
import AgentRunSection from '../components/research-preview/AgentRunSection.vue'
import CandidatePoolPanel from '../components/research-preview/CandidatePoolPanel.vue'
import EvidenceLineagePanel from '../components/research-preview/EvidenceLineagePanel.vue'
import HistoryComparisonPanel from '../components/research-preview/HistoryComparisonPanel.vue'
import PreviewQualificationBanner from '../components/research-preview/PreviewQualificationBanner.vue'
import ResearchReportPanel from '../components/research-preview/ResearchReportPanel.vue'
import TaskOutcomePanel from '../components/research-preview/TaskOutcomePanel.vue'
import { buildResearchReport } from '../research-preview/presentation'
import { useResearchPreview } from '../research-preview/useResearchPreview'

const preview = useResearchPreview()
const report = computed(() => buildResearchReport(preview.activeBundle.value, preview.issues.value))
const activeTaskId = computed(() => preview.activeBundle.value?.task.id ?? null)
</script>

<template>
  <main class="research-preview" v-loading="preview.loading.value">
    <PreviewQualificationBanner
      :mode="preview.mode.value"
      :qualification="preview.qualification.value"
      :synthetic="preview.synthetic.value"
      :issues="preview.issues.value"
      :loading="preview.loading.value"
      @switch-mode="preview.switchMode"
      @reload-local="preview.reloadExistingSnapshots"
    />

    <CandidatePoolPanel
      :mode="preview.mode.value"
      :qualification="preview.qualification.value"
      :tasks="preview.scanHistory.value"
      :selected-task-id="preview.selectedScanTaskId.value"
      :selected-task="preview.selectedScanTask.value"
      :candidates="preview.candidates.value"
      :loading="preview.scanLoading.value"
      @select-scan="preview.loadScan"
      @select-candidate="preview.selectCandidate"
    />

    <TaskOutcomePanel
      :bundle="preview.activeBundle.value"
      :mode="preview.mode.value"
      :issues="preview.issues.value"
    />

    <AgentRunSection
      :runs="preview.activeBundle.value?.runs ?? []"
      :mode="preview.mode.value"
      :qualification="preview.qualification.value"
    />

    <AgentMetricsChart
      :runs="preview.activeBundle.value?.runs ?? []"
      :synthetic="preview.synthetic.value"
    />

    <EvidenceLineagePanel
      :evidence="preview.activeBundle.value?.evidence ?? []"
      :qualification="preview.qualification.value"
      :synthetic="preview.synthetic.value"
    />

    <HistoryComparisonPanel
      :mode="preview.mode.value"
      :history="preview.history.value"
      :active-task-id="activeTaskId"
      :left="preview.comparisonLeft.value"
      :right="preview.comparisonRight.value"
      :loading="preview.taskLoading.value"
      @page="preview.loadHistory"
      @load-task="preview.loadBundle"
      @compare="preview.setComparison"
    />

    <ResearchReportPanel :report="report" :synthetic="preview.synthetic.value" />
  </main>
</template>

<style scoped>
.research-preview {
  --preview-border: #223a57;
  display: grid;
  gap: 16px;
  min-width: 0;
  color: #dce7f5;
}
.research-preview :deep(.el-input__wrapper),
.research-preview :deep(.el-select__wrapper) {
  background: #091523;
  box-shadow: 0 0 0 1px #263d58 inset;
}
.research-preview :deep(.el-input__inner),
.research-preview :deep(.el-select__selected-item) { color: #d6e2f1; }
</style>
