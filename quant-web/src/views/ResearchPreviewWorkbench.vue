<script setup lang="ts">
import { computed } from 'vue'
import AgentMetricsChart from '../components/research-preview/AgentMetricsChart.vue'
import AgentRunSection from '../components/research-preview/AgentRunSection.vue'
import CandidatePoolPanel from '../components/research-preview/CandidatePoolPanel.vue'
import EvidenceLineagePanel from '../components/research-preview/EvidenceLineagePanel.vue'
import HistoryComparisonPanel from '../components/research-preview/HistoryComparisonPanel.vue'
import PreviewQualificationBanner from '../components/research-preview/PreviewQualificationBanner.vue'
import ResearchOverviewPanel from '../components/research-preview/ResearchOverviewPanel.vue'
import ResearchReportPanel from '../components/research-preview/ResearchReportPanel.vue'
import TaskOutcomePanel from '../components/research-preview/TaskOutcomePanel.vue'
import TechnicalAuditDetails from '../components/research-preview/TechnicalAuditDetails.vue'
import {
  buildResearchReport,
  buildStructuredResearchReport,
} from '../research-preview/presentation'
import type { PreviewSection } from '../research-preview/types'
import { useResearchPreview } from '../research-preview/useResearchPreview'

const preview = useResearchPreview()
const rawReport = computed(() => buildResearchReport(preview.activeBundle.value, preview.issues.value))
const structuredReport = computed(() =>
  buildStructuredResearchReport(preview.activeBundle.value, preview.issues.value),
)
const activeTaskId = computed(() => preview.activeBundle.value?.task.id ?? null)
const activeSection = computed({
  get: () => preview.section.value,
  set: (section: string) => {
    void preview.setSection(section as PreviewSection)
  },
})
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

    <section class="section-navigation">
      <el-tabs v-model="activeSection" class="preview-tabs">
        <el-tab-pane label="研究总览" name="overview" lazy>
          <div class="tab-content overview-content">
            <ResearchOverviewPanel
              :bundle="preview.activeBundle.value"
              :candidate="preview.selectedCandidate.value"
              :scan-task="preview.selectedScanTask.value"
              :qualification="preview.qualification.value"
              :synthetic="preview.synthetic.value"
              :issues="preview.issues.value"
            />
            <CandidatePoolPanel
              :mode="preview.mode.value"
              :qualification="preview.qualification.value"
              :tasks="preview.scanHistory.value"
              :selected-task-id="preview.selectedScanTaskId.value"
              :selected-task="preview.selectedScanTask.value"
              :selected-symbol="preview.selectedSymbol.value"
              :candidates="preview.candidates.value"
              :loading="preview.scanLoading.value"
              @select-scan="preview.loadScan"
              @select-candidate="preview.selectCandidate"
            />
            <TaskOutcomePanel
              :bundle="preview.activeBundle.value"
              :issues="preview.issues.value"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="六智能体" name="agents" lazy>
          <div class="tab-content">
            <AgentRunSection
              :runs="preview.activeBundle.value?.runs ?? []"
              :vetoes="preview.activeBundle.value?.vetoes ?? []"
              :execution-mode="preview.activeBundle.value?.task.executionMode ?? null"
            />
            <AgentMetricsChart
              :runs="preview.activeBundle.value?.runs ?? []"
              :synthetic="preview.synthetic.value"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="证据与审计" name="evidence" lazy>
          <div class="tab-content">
            <EvidenceLineagePanel
              :evidence="preview.activeBundle.value?.evidence ?? []"
              :qualification="preview.qualification.value"
              :synthetic="preview.synthetic.value"
            />
            <TechnicalAuditDetails :bundle="preview.activeBundle.value" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="历史对比" name="history" lazy>
          <div class="tab-content">
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
          </div>
        </el-tab-pane>

        <el-tab-pane label="综合报告" name="report" lazy>
          <div class="tab-content">
            <ResearchReportPanel
              :report="structuredReport"
              :raw-text="rawReport"
              :synthetic="preview.synthetic.value"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<style scoped>
.research-preview {
  --preview-border: #263f5b;
  display: grid;
  gap: 14px;
  min-width: 0;
  overflow-x: hidden;
  color: #dce7f5;
  font-size: 14px;
}
.section-navigation {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #213a55;
  border-radius: 14px;
  background: #091523;
}
.preview-tabs { min-width: 0; }
.tab-content { display: grid; gap: 14px; min-width: 0; padding: 4px 16px 18px; }
.overview-content { align-content: start; }
.research-preview :deep(.el-tabs__header) {
  margin: 0 0 12px;
  padding: 0 18px;
  background: #0d1b2c;
}
.research-preview :deep(.el-tabs__nav-wrap::after) { background: #223a54; }
.research-preview :deep(.el-tabs__item) {
  height: 54px;
  padding: 0 24px;
  color: #9fb1c6;
  font-size: 14px;
  font-weight: 600;
}
.research-preview :deep(.el-tabs__item.is-active) { color: #70bfff; }
.research-preview :deep(.el-tabs__active-bar) { height: 3px; background: #4ca5e8; }
.research-preview :deep(.el-input__wrapper),
.research-preview :deep(.el-select__wrapper) {
  background: #091523;
  box-shadow: 0 0 0 1px #2c4663 inset;
}
.research-preview :deep(.el-input__inner),
.research-preview :deep(.el-select__selected-item) {
  color: #d6e2f1;
  font-size: 13px;
}
.research-preview :deep(.el-button) { font-size: 13px; }
.research-preview :deep(code) { overflow-wrap: anywhere; }
@media (max-width: 1100px) {
  .research-preview :deep(.el-tabs__item) { padding: 0 14px; }
  .tab-content { padding-inline: 12px; }
}
@media (max-width: 760px) {
  .research-preview :deep(.el-tabs__nav-scroll) { overflow-x: auto; }
  .research-preview :deep(.el-tabs__nav) { float: none; min-width: max-content; }
}
</style>
