<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'

const health = ref<any>(null)
const error = ref('')
const loading = ref(false)
const selectionStarting = ref(false)
const budgetRatio = computed(() => health.value
  ? Number(health.value.budget.projectCostCny) / Number(health.value.budget.projectLimitCny) * 100 : 0)

async function refresh() {
  loading.value = true
  error.value = ''
  try { health.value = await api.get('/system/health') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '系统健康检查失败' }
  finally { loading.value = false }
}

async function selectNow() {
  selectionStarting.value = true
  try {
    const started: any = await api.post('/research-selection/runs', {
      primaryWindow: 20
    })
    window.location.hash = `#/research-selection?run=${started.run.runId}`
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : ''
    error.value = message.includes('RESEARCH_SELECTION_ALREADY_RUNNING')
      ? '已有一次立即选股正在进行，请在选股页查看进度。'
      : message.startsWith('BUDGET:')
      ? 'API预算不足，本次未启动。'
      : message.startsWith('BROKER:') || message.startsWith('BUILD:')
        ? '本地研究服务暂不可用，请等待系统自动恢复。'
        : '选股启动失败，系统未产生真实交易。'
  } finally { selectionStarting.value = false }
}

onMounted(refresh)
</script>

<template>
  <div class="production-home" v-loading="loading">
    <header class="production-hero">
      <div><p>RESEARCH_PRODUCTION_V1 · LOCAL / PAPER ONLY</p><h1>研究生产总览</h1><span>日常健康、Shadow、Agent、策略与预算的统一只读入口。</span></div>
      <el-button @click="refresh">刷新</el-button>
    </header>
    <section class="selection-launch">
      <div><small>RESEARCH_UNIVERSE_V1 · 25只沪深主板</small>
        <h2>今天关注哪些股票？</h2>
        <p>过去20/60交易日量化扫描 → Top 10 → 7 Agent → Critic → 最终候选。</p></div>
      <el-button type="primary" size="large" :loading="selectionStarting" @click="selectNow">立即选股</el-button>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="health">
      <section class="health-metrics">
        <article><span>系统状态</span><strong :class="health.status.toLowerCase()">{{ health.status }}</strong><small>{{ health.reason }}</small></article>
        <article><span>Shadow Scheduler</span><strong>{{ health.scheduler.state }}</strong><small>下一计划 {{ health.scheduler.nextPlannedAt }}</small></article>
        <article><span>最近 Shadow</span><strong>{{ health.latestShadow.status }}</strong><small>{{ health.latestShadow.tradeDate || '尚无记录' }}</small></article>
        <article><span>真实交易</span><strong class="healthy">DISABLED</strong><small>Research / Paper only</small></article>
      </section>

      <section class="production-grid">
        <article class="production-panel">
          <h2>SYSTEM_HEALTH_V1</h2>
          <div v-for="item in health.components" :key="item.component" class="component-row">
            <span>{{ item.component }}</span><b :class="item.status.toLowerCase()">{{ item.status }}</b><small>{{ item.reason }}</small>
          </div>
        </article>
        <article class="production-panel">
          <h2>本月外部 API 预算</h2>
          <p>项目总账：¥{{ health.budget.projectCostCny }} / ¥{{ health.budget.projectLimitCny }}</p>
          <el-progress :percentage="Math.min(100, budgetRatio)" :stroke-width="12" />
          <p>Shadow 百炼：¥{{ health.budget.shadowCostCny }} / ¥{{ health.budget.shadowLimitCny }}</p>
          <p>Tushare：{{ health.budget.tushareRequests }} / {{ health.budget.tushareLimit }}</p>
          <p>pending / claimed：{{ health.pendingRequests }} / {{ health.claimedRequests }}</p>
        </article>
      </section>

      <section class="production-grid">
        <article class="production-panel"><h2>最新研究</h2><p>状态：{{ health.latestShadow.status }}</p><p>交易日：{{ health.latestShadow.tradeDate || 'N/A' }}</p><p>完成：{{ health.latestShadow.completedAt || 'N/A' }}</p><router-link to="/shadow-research">查看冻结报告与 Paper 账本 →</router-link></article>
        <article class="production-panel"><h2>Agent Evaluation</h2><p>Champion：{{ health.latestEvaluation.champion || 'INSUFFICIENT_SAMPLE' }}</p><p>Shadow 样本：{{ health.latestEvaluation.shadowSamples || 0 }}</p><p>状态：{{ health.latestEvaluation.status }}</p><router-link to="/agent-evaluation">查看 7 Agent Scorecard →</router-link></article>
      </section>
      <footer class="production-footer">HEAD {{ health.gitCommit }} · {{ health.checkedAt }} · 历史冻结结果不可覆盖</footer>
    </template>
  </div>
</template>

<style scoped>
.production-home{display:grid;gap:18px}.production-hero{height:auto;display:flex;justify-content:space-between;background:transparent;border:0;padding:0}.production-hero p{color:#56b6ff;font-size:12px;letter-spacing:.12em}.production-hero h1{margin:4px 0 8px}.production-hero span,.production-panel p,.production-footer{color:#8795aa}.selection-launch{display:flex;align-items:center;justify-content:space-between;padding:22px;border:1px solid #315a80;border-radius:12px;background:linear-gradient(135deg,#102b48,#101c2d)}.selection-launch small{color:#63b7ff;letter-spacing:.08em}.selection-launch h2{font-size:24px;margin:6px 0}.selection-launch p{margin:0;color:#90a2b8}.health-metrics,.production-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.health-metrics article,.production-panel{background:#0f1b2c;border:1px solid #28374b;border-radius:10px;padding:17px}.health-metrics span,.health-metrics small{display:block;color:#7f8da2}.health-metrics strong{display:block;font-size:20px;margin:8px 0}.production-grid{grid-template-columns:2fr 1fr}.production-panel h2{font-size:16px;margin:0 0 14px}.component-row{display:grid;grid-template-columns:170px 100px 1fr;gap:12px;padding:8px 0;border-bottom:1px solid #1e2d40}.component-row small{color:#7f8da2}.healthy{color:#4fd19a}.degraded{color:#e4b45f}.blocked{color:#ff6875}.production-panel a{color:#56b6ff}.production-footer{font-size:11px}@media(max-width:1000px){.health-metrics,.production-grid{grid-template-columns:1fr}.component-row{grid-template-columns:1fr}}
</style>
