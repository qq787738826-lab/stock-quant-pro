<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '../api'

type Metrics = {
  latestClose: number
  return5Pct: number
  return20Pct: number
  return60Pct: number
  ma5: number
  ma10: number
  ma20: number
  ma60: number
  rsi14: number
  volumeRatio20: number
  atr14Pct: number
  volatility20Pct: number
  drawdown20Pct: number
  rangePosition20Pct: number
  turnoverRate: number
  macdHistogram: number
  breakout20: boolean
}

type AnalysisResult = {
  symbol: string
  mode: string
  dataSource: string
  tradeDate: string
  score: number
  signalLevel: string
  summary: string
  bullish: string[]
  bearish: string[]
  riskLevel: string
  metrics: Metrics
  disclaimer: string
}

const symbol = ref('600000')
const result = ref<AnalysisResult>()
const err = ref('')
const loading = ref(false)

const scoreClass = computed(() => {
  const score = result.value?.score ?? 0
  if (score >= 75) return 'score-strong'
  if (score >= 60) return 'score-watch'
  return 'score-weak'
})

function pct(value: number) {
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

async function go() {
  err.value = ''
  result.value = undefined

  if (!/^\d{6}$/.test(symbol.value)) {
    err.value = '请输入6位股票代码'
    return
  }

  loading.value = true
  try {
    result.value = await api.post('/ai/analyze', { symbol: symbol.value }) as unknown as AnalysisResult
  } catch (e: any) {
    err.value = e.message || '分析失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <h1 class="page-title">AI综合分析</h1>

  <div class="card">
    <div class="form-row">
      <input
        v-model.trim="symbol"
        maxlength="6"
        placeholder="请输入6位股票代码"
        @keyup.enter="go"
      >
      <button class="btn" :disabled="loading" @click="go">
        {{ loading ? '分析中...' : '分析' }}
      </button>
    </div>
    <p class="muted">
      当前使用真实历史行情驱动的动态规则分析。在线大模型未配置时，不影响指标计算和回测。
    </p>
  </div>

  <div v-if="result" class="analysis-layout">
    <div class="card">
      <div class="analysis-header">
        <div>
          <h3>{{ result.symbol }} · {{ result.mode }}</h3>
          <p class="muted">
            数据源：{{ result.dataSource }}　交易日：{{ result.tradeDate }}
          </p>
        </div>
        <div class="score-box" :class="scoreClass">
          <strong>{{ result.score }}</strong>
          <span>{{ result.signalLevel }}</span>
        </div>
      </div>

      <p class="summary">{{ result.summary }}</p>

      <div class="metric-grid">
        <div class="metric-item">
          <span>最新收盘</span>
          <b>{{ result.metrics.latestClose.toFixed(2) }}</b>
        </div>
        <div class="metric-item">
          <span>近5日</span>
          <b :class="result.metrics.return5Pct >= 0 ? 'up' : 'down'">
            {{ pct(result.metrics.return5Pct) }}
          </b>
        </div>
        <div class="metric-item">
          <span>近20日</span>
          <b :class="result.metrics.return20Pct >= 0 ? 'up' : 'down'">
            {{ pct(result.metrics.return20Pct) }}
          </b>
        </div>
        <div class="metric-item">
          <span>RSI14</span>
          <b>{{ result.metrics.rsi14.toFixed(1) }}</b>
        </div>
        <div class="metric-item">
          <span>MA5 / MA20</span>
          <b>{{ result.metrics.ma5.toFixed(2) }} / {{ result.metrics.ma20.toFixed(2) }}</b>
        </div>
        <div class="metric-item">
          <span>量比20</span>
          <b>{{ result.metrics.volumeRatio20.toFixed(2) }}</b>
        </div>
        <div class="metric-item">
          <span>ATR占比</span>
          <b>{{ result.metrics.atr14Pct.toFixed(2) }}%</b>
        </div>
        <div class="metric-item">
          <span>20日回撤</span>
          <b :class="result.metrics.drawdown20Pct >= 0 ? 'up' : 'down'">
            {{ pct(result.metrics.drawdown20Pct) }}
          </b>
        </div>
      </div>
    </div>

    <div class="factor-grid">
      <div class="card">
        <h3>积极因素</h3>
        <p v-for="item in result.bullish" :key="item" class="factor up">
          {{ item }}
        </p>
      </div>

      <div class="card">
        <h3>风险 · {{ result.riskLevel }}</h3>
        <p v-for="item in result.bearish" :key="item" class="factor down">
          {{ item }}
        </p>
        <p class="muted disclaimer">{{ result.disclaimer }}</p>
      </div>
    </div>
  </div>

  <p v-if="err" class="error">{{ err }}</p>
</template>

<style scoped>
.analysis-layout {
  display: grid;
  gap: 12px;
  margin-top: 12px;
}

.analysis-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.analysis-header h3 {
  margin-bottom: 4px;
}

.score-box {
  width: 92px;
  min-width: 92px;
  padding: 10px;
  border: 1px solid #2a3e59;
  border-radius: 6px;
  text-align: center;
  background: #101e31;
}

.score-box strong {
  display: block;
  font-size: 30px;
  line-height: 1;
}

.score-box span {
  display: block;
  margin-top: 6px;
  color: #8ca0bd;
  font-size: 11px;
}

.score-strong strong {
  color: #ff5a67;
}

.score-watch strong {
  color: #f0b84a;
}

.score-weak strong {
  color: #3fd38a;
}

.summary {
  margin: 14px 0;
  line-height: 1.8;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 10px;
}

.metric-item {
  padding: 11px;
  border: 1px solid #223149;
  border-radius: 5px;
  background: #0c1727;
}

.metric-item span {
  display: block;
  margin-bottom: 7px;
  color: #7d8da5;
  font-size: 12px;
}

.metric-item b {
  font-size: 15px;
}

.factor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.factor {
  margin: 9px 0;
  line-height: 1.6;
}

.disclaimer {
  margin-top: 18px;
}

.btn:disabled {
  cursor: wait;
  opacity: 0.65;
}

@media (max-width: 1280px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }
}
</style>
