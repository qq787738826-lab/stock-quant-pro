<script setup lang="ts">
import { computed } from 'vue'
import type { ResearchReport } from '../agent-research/types'
import {
  buildAgentConclusionCards,
  buildAgentExecutionTrace,
} from '../agent-research/presentation'
import {
  displayAgentRole,
  displayClaimType,
  displayDecision,
  displayPhase,
  displayResearchList,
  displayResearchText,
  displayRisk,
  displayStatus,
  displayTool,
} from '../localization/display'

const props = defineProps<{
  report: ResearchReport
  paperEnabled: boolean
  shadowRunId?: number | null
}>()

const conclusionCards = computed(() =>
  buildAgentConclusionCards(props.report.agentRuns))
const executionTrace = computed(() =>
  buildAgentExecutionTrace(props.report.agentRuns))
const pct = (value: number) => `${(Number(value) * 100).toFixed(0)}%`
const tools = (values: string[]) => values.length
  ? values.map(displayTool).join('、') : '无工具调用'
</script>

<template>
  <section class="agent-panel" data-testid="seven-agent-conclusions">
    <h2>七智能体研究结论</h2>
    <p class="panel-note">每个角色仅展示最终研究阶段；计划与工具选择过程收纳在内部执行轨迹中。</p>
    <div class="agent-grid">
      <article
        v-for="card in conclusionCards"
        :key="card.agentRole"
        class="agent-card"
        :class="{
          'coordinator-card': card.agentRole === 'RESEARCH_COORDINATOR',
          'critic-card': card.agentRole === 'CRITIC_REVIEW',
        }"
        data-agent-conclusion-card
        :data-agent-role="card.agentRole"
        :data-agent-phase="card.phase"
      >
        <header>
          <b>{{ displayAgentRole(card.agentRole) }}</b>
          <span>{{ displayStatus(card.run?.status || 'UNKNOWN') }}</span>
        </header>
        <div class="agent-phase">{{ displayPhase(card.phase) }}</div>
        <template v-if="card.run">
          <p v-for="finding in card.run.findings" :key="finding.findingId">
            <em>{{ displayClaimType(finding.claimType) }}</em>
            {{ displayResearchText(finding.statement) }}
          </p>
          <small v-if="!card.run.findings.length">该角色的最终阶段没有形成可展示结论。</small>
        </template>
        <small v-else>该角色的最终结论尚未生成。</small>

        <div v-if="card.agentRole === 'RESEARCH_COORDINATOR'" class="role-summary">
          <strong>最终判断</strong>
          {{ displayDecision(report.finalDecision.code) }} ·
          风险 {{ displayRisk(report.finalDecision.riskLevel) }} ·
          置信度 {{ pct(report.finalDecision.confidence) }}
        </div>
        <div v-if="card.agentRole === 'CRITIC_REVIEW'" class="role-summary">
          <strong>审查结果</strong>
          {{ displayResearchList(report.criticReview.issues) || '未发现阻断性问题' }}
          <small>
            修正：{{ report.criticReview.correctionApplied ? '已应用' : '无需修正' }} ·
            返工 {{ report.criticReview.reworkRounds }} 轮
          </small>
        </div>
      </article>
    </div>

    <details class="execution-trace">
      <summary>内部执行轨迹（{{ executionTrace.length }} 次模型阶段）</summary>
      <div class="trace-table-wrap">
        <table>
          <thead><tr><th>智能体</th><th>阶段</th><th>状态</th><th>工具</th><th>令牌</th><th>修订</th></tr></thead>
          <tbody>
            <tr v-for="trace in executionTrace" :key="trace.runId" data-agent-execution-trace>
              <td>{{ displayAgentRole(trace.agentRole) }}</td>
              <td>{{ displayPhase(trace.phase) }}<small>{{ trace.phase }}</small></td>
              <td>{{ displayStatus(trace.status) }}</td>
              <td>{{ tools(trace.requestedTools) }}</td>
              <td>{{ trace.tokenTotal }}</td>
              <td>{{ trace.revised ? '已修订' : '未修订' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>

    <div class="paper-box">
      <b>模拟研究：{{ paperEnabled ? '已开启' : '已关闭' }}</b>
      <span>{{ shadowRunId ? '研究结论已冻结并进入模拟流程' : '本次未生成模拟记录' }}；真实交易始终关闭。</span>
    </div>
  </section>
</template>

<style scoped>
.agent-panel{background:#0f1b2c;border:1px solid #263951;border-radius:10px;padding:18px}.agent-panel h2{margin:0 0 5px}.panel-note{margin:0 0 14px;color:#71859d;font-size:12px}.agent-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agent-card{background:#111f31;border:1px solid #263a52;border-radius:7px;padding:14px}.agent-card header{display:flex;justify-content:space-between;color:#dcecff}.agent-card header span,.agent-card small{color:#70839a;font-size:11px}.agent-card p{font-size:12px;color:#9aacc1}.agent-card em{font-style:normal;color:#5db2ff;margin-right:7px}.agent-phase{margin-top:5px;color:#5f7894;font-size:11px}.coordinator-card{border-color:#3b6f9d;grid-column:1/-1}.critic-card{border-color:#775f48}.role-summary{margin-top:12px;padding:10px;background:#16283c;border-radius:5px;color:#aebed0;font-size:12px}.role-summary strong{display:block;color:#dcecff;margin-bottom:4px}.role-summary small{display:block;margin-top:6px}.execution-trace{margin-top:14px;background:#0d1826;border:1px solid #21354b;border-radius:7px;padding:10px 12px}.execution-trace summary{cursor:pointer;color:#88a4c1;font-size:12px}.trace-table-wrap{overflow:auto;margin-top:10px}.execution-trace table{width:100%;border-collapse:collapse;font-size:11px}.execution-trace th{text-align:left;color:#71839b;padding:7px;border-bottom:1px solid #2a3a50}.execution-trace td{padding:8px 7px;border-bottom:1px solid #1d2c40;color:#98aabc}.execution-trace td small{display:block;color:#5d7087}.paper-box{margin-top:12px;display:flex;gap:12px;align-items:center;background:#16263a;padding:12px;border-radius:6px}.paper-box span{color:#a6b5c7;flex:1}@media(max-width:1100px){.agent-grid{grid-template-columns:1fr}.coordinator-card{grid-column:auto}}
</style>
