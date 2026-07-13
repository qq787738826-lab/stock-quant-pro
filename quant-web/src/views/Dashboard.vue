<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api'

const rows = ref<any[]>([])
const health = ref<any>({ status: '检查中' })
const overview = ref<any>({ securities: 0, dailyBars: 0, cachedSymbols: 0 })
const err = ref('')

onMounted(async () => {
  try {
    health.value = await api.get('/health')
    overview.value = await api.get('/data/overview')
    rows.value = await api.get('/scans/latest?limit=8') as unknown as any[]
  } catch (e: any) {
    err.value = e.message || '首页加载失败'
  }

  const chart = echarts.init(document.getElementById('chart')!)
  const scores = rows.value.slice(0, 8).map(r => Number(r.score || 0))
  const labels = rows.value.slice(0, 8).map(r => r.symbol)
  chart.setOption({
    grid: { left: 40, right: 12, top: 20, bottom: 28 },
    xAxis: { type: 'category', data: labels, axisLine: { lineStyle: { color: '#31425b' } } },
    yAxis: { type: 'value', min: 0, max: 100, splitLine: { lineStyle: { color: '#1d2c41' } } },
    series: [{ type: 'bar', data: scores }],
  })
})
</script>

<template>
  <h1 class="page-title">行情总览</h1>
  <div v-if="err" class="error">{{ err }}</div>
  <div class="grid4">
    <div class="card metric"><label>核心服务</label><strong class="success">{{ health.status }}</strong></div>
    <div class="card metric"><label>主板股票</label><strong>{{ overview.securities || 0 }}</strong></div>
    <div class="card metric"><label>本地K线</label><strong>{{ Number(overview.dailyBars || 0).toLocaleString() }}</strong></div>
    <div class="card metric"><label>最新候选</label><strong>{{ rows.length }}</strong></div>
  </div>

  <div class="section-grid">
    <div class="card"><h3>候选评分分布</h3><div id="chart" style="height:300px"></div></div>
    <div class="card">
      <h3>短线风控参数</h3>
      <p class="muted">持仓周期：2–10个交易日</p>
      <p class="muted">最大持仓：5只</p>
      <p class="muted">单股上限：20%</p>
      <p class="muted">固定止损：5%</p>
      <p class="muted">第一止盈：8%</p>
      <p class="muted">移动止盈回撤：4%</p>
    </div>
  </div>

  <div class="card" style="margin-top:12px">
    <h3>最近全市场扫描候选</h3>
    <table class="table">
      <thead><tr><th>排名</th><th>代码</th><th>名称</th><th>评分</th><th>信号</th><th>价格</th><th>风险</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row.symbol">
          <td>{{ row.rank_no }}</td><td>{{ row.symbol }}</td><td>{{ row.name }}</td><td>{{ row.score }}</td><td>{{ row.signal_level }}</td><td>{{ row.latest_close }}</td><td>{{ row.risk_level }}</td>
        </tr>
        <tr v-if="rows.length===0"><td colspan="7" class="muted">暂无全市场扫描结果，请前往“数据管理”同步并扫描。</td></tr>
      </tbody>
    </table>
  </div>
</template>
