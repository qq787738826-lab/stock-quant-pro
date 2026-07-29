<script setup lang="ts">
import { ref } from 'vue'
import type { StructuredResearchReport } from '../../research-preview/types'

defineProps<{
  report: StructuredResearchReport
  rawText: string
  synthetic: boolean
}>()

const copyStatus = ref('')

async function copyReport(report: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(report)
    copyStatus.value = '已复制UI展示报告'
  } catch {
    copyStatus.value = '复制失败，请手动选择文本'
  }
}
</script>

<template>
  <section class="report-panel">
    <header class="panel-heading">
      <div>
        <p class="eyebrow">UI_PRESENTATION_ONLY</p>
        <h2>综合研究报告</h2>
        <span>结构化展示已有结果，不新增权威判断。</span>
      </div>
      <div class="report-actions">
        <i v-if="synthetic">演示数据</i>
        <el-button size="small" type="primary" plain @click="copyReport(rawText)">复制报告文本</el-button>
      </div>
    </header>

    <div class="structured-report">
      <section class="report-section conclusion-section">
        <h3>1. 研究结论</h3>
        <dl class="field-grid">
          <div v-for="field in report.conclusion" :key="field.label">
            <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
          </div>
        </dl>
        <p>{{ report.conclusionSummary }}</p>
      </section>

      <section class="report-section">
        <h3>2. 数据资格与限制</h3>
        <dl class="field-grid compact-grid">
          <div v-for="field in report.qualification" :key="field.label">
            <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
          </div>
        </dl>
        <ul>
          <li v-for="item in report.limitations" :key="item">{{ item }}</li>
        </ul>
      </section>

      <section class="report-section">
        <h3>3. 六智能体摘要</h3>
        <div class="agent-report-grid">
          <article v-for="agent in report.agents" :key="agent.agentCode">
            <header><b>{{ agent.name }}</b><code>{{ agent.status }}</code></header>
            <p>{{ agent.summary }}</p>
            <span>门禁 {{ agent.gateStatus }} · 评分 {{ agent.score }} · 置信度 {{ agent.confidence }}</span>
          </article>
        </div>
      </section>

      <section class="report-section">
        <h3>4. 主要风险</h3>
        <div v-if="report.risks.length" class="risk-list">
          <article v-for="risk in report.risks" :key="`${risk.source}-${risk.code}-${risk.title}`">
            <div><code>{{ risk.code }}</code><b>{{ risk.level }}</b></div>
            <strong>{{ risk.title }} · {{ risk.source }}</strong>
            <p>{{ risk.detail }}</p>
          </article>
        </div>
        <p v-else class="empty">暂无明确结构化风险finding或正式veto。</p>
      </section>

      <section class="report-section">
        <h3>5. 结构化原因</h3>
        <div v-if="report.reasons.length" class="reason-list">
          <article v-for="reason in report.reasons" :key="`${reason.source}-${reason.agentCode}-${reason.code}`">
            <code>{{ reason.code }}</code>
            <span>{{ reason.source }}<template v-if="reason.agentCode"> · {{ reason.agentCode }}</template></span>
          </article>
        </div>
        <p v-else class="empty">暂无明确结构化reasonCode。</p>
      </section>

      <section class="report-section">
        <h3>6. 证据索引</h3>
        <div v-if="report.evidence.length" class="evidence-index">
          <article v-for="item in report.evidence" :key="item.evidenceId">
            <code>{{ item.evidenceId }}</code>
            <span>{{ item.category }} · {{ item.sourceType }}</span>
          </article>
        </div>
        <p v-else class="empty">暂无证据索引。</p>
      </section>

      <section class="report-section">
        <h3>7. 技术审计摘要</h3>
        <dl class="field-grid compact-grid audit-grid">
          <div v-for="field in report.audit" :key="field.label">
            <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
          </div>
        </dl>
      </section>

      <section class="report-section disclaimer-section">
        <h3>8. 免责声明</h3>
        <p>{{ report.disclaimer }}</p>
      </section>
    </div>

    <details class="raw-report">
      <summary>复制 / 查看原始报告文本</summary>
      <pre>{{ rawText }}</pre>
    </details>
    <p v-if="copyStatus" class="copy-status">{{ copyStatus }}</p>
    <p class="boundary">报告不调用LLM、不新增评分或预测，也不写数据库。</p>
  </section>
</template>

<style scoped>
.report-panel { position: relative; min-width: 0; padding: 18px; border: 1px solid #29445f; border-radius: 12px; background: linear-gradient(145deg, #102239, #0b1727); }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.panel-heading h2 { margin: 0; font-size: 20px; }
.panel-heading span { display: block; margin-top: 5px; color: #9badc1; font-size: 13px; }
.eyebrow { margin: 0 0 5px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.report-actions { display: flex; align-items: center; gap: 8px; }
.report-actions i { padding: 4px 8px; border-radius: 999px; background: #4a3518; color: #ffd080; font-size: 12px; font-style: normal; }
.structured-report { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 15px; }
.report-section { min-width: 0; padding: 15px; border: 1px solid #243c57; border-radius: 9px; background: #091624; }
.report-section h3 { margin: 0 0 12px; color: #e5edf7; font-size: 16px; }
.conclusion-section, .report-section:nth-child(3), .disclaimer-section { grid-column: 1 / -1; }
.field-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 0; }
.field-grid div { min-width: 0; padding: 8px; border-radius: 7px; background: #0c1d2f; }
.field-grid dt { color: #91a5bc; font-size: 12px; }
.field-grid dd { margin: 5px 0 0; overflow-wrap: anywhere; color: #d2dce8; font-size: 13px; }
.compact-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.audit-grid dd { font-family: ui-monospace, monospace; font-size: 11px; }
.report-section > p, .report-section li { color: #b8c6d5; font-size: 13px; line-height: 1.65; }
.report-section ul { margin: 10px 0 0; padding-left: 18px; }
.agent-report-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.agent-report-grid article { min-width: 0; padding: 10px; border-radius: 7px; background: #0c1d2f; }
.agent-report-grid header { display: flex; justify-content: space-between; gap: 8px; }
.agent-report-grid b { font-size: 13px; }
.agent-report-grid code { color: #82bff0; font-size: 11px; }
.agent-report-grid p { min-height: 40px; color: #b4c2d2; font-size: 13px; line-height: 1.55; }
.agent-report-grid span { color: #91a4ba; font-size: 12px; }
.risk-list { display: grid; gap: 8px; }
.risk-list article { padding: 9px; border: 1px solid #5a3f48; border-radius: 7px; background: #251820; }
.risk-list article div { display: flex; justify-content: space-between; gap: 8px; }
.risk-list code { color: #eba0a8; font-size: 11px; }
.risk-list article div b { color: #d7a06c; font-size: 11px; }
.risk-list strong { display: block; margin-top: 5px; font-size: 13px; }
.risk-list p { margin: 5px 0 0; color: #c7b4b8; font-size: 13px; line-height: 1.55; }
.reason-list, .evidence-index { display: grid; gap: 7px; }
.reason-list article, .evidence-index article { min-width: 0; padding: 8px; border-radius: 7px; background: #0c1d2f; }
.reason-list code, .evidence-index code { display: block; color: #82bff0; font-size: 11px; overflow-wrap: anywhere; }
.reason-list span, .evidence-index span { display: block; margin-top: 4px; color: #9cafc4; font-size: 12px; }
.empty { color: #97a9bd !important; }
.disclaimer-section { border-color: #2b5d4d; background: #0e2a22; }
.raw-report { margin-top: 12px; border: 1px solid #243c57; border-radius: 8px; background: #07111d; }
.raw-report summary { padding: 11px 13px; color: #82bff0; font-size: 13px; cursor: pointer; }
pre {
  max-height: 340px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border-top: 1px solid #20364e;
  color: #bbc8d7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 11px/1.7 ui-monospace, monospace;
}
.copy-status { color: #77d3ad; font-size: 12px; }
.boundary { color: #97a9bd; font-size: 12px; }
@media (max-width: 1250px) {
  .agent-report-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 900px) {
  .structured-report { grid-template-columns: 1fr; }
  .conclusion-section, .report-section:nth-child(3), .disclaimer-section { grid-column: auto; }
  .field-grid, .compact-grid { grid-template-columns: 1fr 1fr; }
}
</style>
