<script setup lang="ts">
import * as echarts from 'echarts'
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'

const account = ref<any>({ positions: [] })
const orders = ref<any[]>([])
const trades = ref<any[]>([])
const plans = ref<any[]>([])
const risks = ref<any[]>([])
const equity = ref<any[]>([])
const loading = ref(false)
const actionLoading = ref('')
const message = ref('')
const error = ref('')
const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined

const form = reactive({
  symbol: '600000',
  side: 'BUY',
  quantity: 100,
  price: 10,
})

function money(value: any) {
  return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function price(value: any) {
  return Number(value || 0).toFixed(2)
}

function pct(value: any) {
  const number = Number(value || 0) * 100
  return `${number >= 0 ? '+' : ''}${number.toFixed(2)}%`
}

function statusLabel(value: string) {
  const labels: Record<string, string> = {
    ACTIVE: '待执行', ORDER_CREATED: '已生成委托', OPEN: '持仓中', CLOSED: '已完成', CANCELLED: '已取消',
    PENDING_CONFIRM: '待确认', FILLED: '已成交', REJECTED: '已拒绝',
  }
  return labels[value] || value
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [summary, orderRows, tradeRows, planRows, riskRows, equityRows] = await Promise.all([
      api.get('/portfolio'),
      api.get('/portfolio/orders'),
      api.get('/portfolio/trades'),
      api.get('/portfolio/plans'),
      api.get('/portfolio/risk-events'),
      api.get('/portfolio/equity', { params: { days: 180 } }),
    ])
    account.value = summary
    orders.value = orderRows as unknown as any[]
    trades.value = tradeRows as unknown as any[]
    plans.value = planRows as unknown as any[]
    risks.value = riskRows as unknown as any[]
    equity.value = equityRows as unknown as any[]
    await nextTick()
    renderChart()
  } catch (e: any) {
    error.value = e.message || '模拟账户加载失败'
  } finally {
    loading.value = false
  }
}

async function createOrder() {
  await act('create', async () => {
    const result: any = await api.post('/portfolio/orders', form)
    message.value = `已创建待确认模拟委托 #${result.orderId}`
  })
}

async function orderFromPlan(plan: any) {
  await act(`plan-${plan.id}`, async () => {
    const result: any = await api.post(`/portfolio/plans/${plan.id}/orders`, {})
    message.value = `计划 ${plan.symbol} 已生成委托 #${result.orderId}`
  })
}

async function cancelPlan(plan: any) {
  await act(`cancel-plan-${plan.id}`, async () => {
    await api.post(`/portfolio/plans/${plan.id}/cancel`)
    message.value = `交易计划 ${plan.symbol} 已取消`
  })
}

async function confirm(order: any) {
  await act(`confirm-${order.id}`, async () => {
    await api.post(`/portfolio/orders/${order.id}/confirm`)
    message.value = `模拟委托 #${order.id} 已成交`
  })
}

async function cancelOrder(order: any) {
  await act(`cancel-${order.id}`, async () => {
    await api.post(`/portfolio/orders/${order.id}/cancel`)
    message.value = `模拟委托 #${order.id} 已撤销`
  })
}

async function sellPosition(position: any) {
  form.symbol = position.symbol
  form.side = 'SELL'
  form.quantity = Number(position.available_quantity || 0)
  form.price = Number(position.last_price || 0)
  message.value = `已将 ${position.symbol} 可卖数量带入委托区，请核对价格和数量后提交。`
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function refreshRisk() {
  await act('risk', async () => {
    const result: any = await api.post('/portfolio/refresh-risk')
    message.value = `已刷新 ${result.refreshedPositions || 0} 个持仓，并完成风险检查`
  })
}

async function resolveRisk(row: any) {
  await act(`risk-${row.id}`, async () => {
    await api.post(`/portfolio/risk-events/${row.id}/resolve`)
    message.value = `风险事件 #${row.id} 已标记处理`
  })
}

async function act(key: string, action: () => Promise<void>) {
  actionLoading.value = key
  error.value = ''
  message.value = ''
  try {
    await action()
    await load()
  } catch (e: any) {
    error.value = e.message || '操作失败'
  } finally {
    actionLoading.value = ''
  }
}

function renderChart() {
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  const dates = equity.value.map(row => row.snapshot_date)
  const total = equity.value.map(row => Number(row.total_asset || 0))
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 20, top: 20, bottom: 32 },
    xAxis: { type: 'category', data: dates, axisLabel: { color: '#71819c' }, axisLine: { lineStyle: { color: '#273a55' } } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#71819c' }, splitLine: { lineStyle: { color: '#17263a' } } },
    series: [{ type: 'line', data: total, smooth: true, showSymbol: false, areaStyle: { opacity: 0.12 } }],
  })
}

function resizeChart() { chart?.resize() }

onMounted(() => {
  load()
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>

<template>
  <div class="title-row">
    <h1 class="page-title">模拟交易与持仓风控</h1>
    <button class="btn secondary" :disabled="loading || !!actionLoading" @click="refreshRisk">
      {{ actionLoading === 'risk' ? '检查中...' : '刷新行情并检查风险' }}
    </button>
  </div>

  <div class="account-grid">
    <div class="card metric"><label>总资产</label><strong>{{ money(account.totalAsset) }}</strong><small :class="Number(account.totalReturn)>=0?'up':'down'">{{ pct(account.totalReturn) }}</small></div>
    <div class="card metric"><label>可用资金</label><strong>{{ money(account.availableCash) }}</strong><small>冻结 {{ money(account.frozenCash) }}</small></div>
    <div class="card metric"><label>持仓市值</label><strong>{{ money(account.marketValue) }}</strong><small>{{ account.positionCount || 0 }} / {{ account.maxPositions || 5 }} 只</small></div>
    <div class="card metric"><label>浮动盈亏</label><strong :class="Number(account.unrealizedPnl)>=0?'up':'down'">{{ money(account.unrealizedPnl) }}</strong><small>已实现 {{ money(account.realizedPnl) }}</small></div>
    <div class="card metric"><label>累计费用</label><strong>{{ money(account.totalFees) }}</strong><small>单股仓位上限 {{ pct(account.maxPositionWeight) }}</small></div>
  </div>

  <div class="section-grid order-section">
    <div class="card">
      <h3>模拟委托录入</h3>
      <div class="form-row order-form">
        <input v-model.trim="form.symbol" maxlength="6" placeholder="股票代码">
        <select v-model="form.side"><option value="BUY">买入</option><option value="SELL">卖出</option></select>
        <input v-model.number="form.quantity" type="number" min="100" step="100" placeholder="数量">
        <input v-model.number="form.price" type="number" min="0.01" step="0.01" placeholder="价格">
        <button class="btn" :disabled="actionLoading === 'create'" @click="createOrder">
          {{ actionLoading === 'create' ? '创建中...' : '创建待确认委托' }}
        </button>
      </div>
      <p class="muted">创建委托会冻结模拟资金或可卖数量；点击“确认成交”后才会更新模拟账户。</p>
    </div>
    <div class="card rules">
      <h3>账户规则</h3>
      <span>A股数量必须为100股整数倍</span>
      <span>单股不超过总资产 {{ pct(account.maxPositionWeight) }}</span>
      <span>最多同时持有或等待买入 {{ account.maxPositions || 5 }} 只</span>
      <span>当日买入数量按T+1解锁可卖</span>
    </div>
  </div>

  <div v-if="message" class="notice success">{{ message }}</div>
  <div v-if="error" class="notice error">{{ error }}</div>

  <div class="card block">
    <h3>当前持仓</h3>
    <div class="table-wrap">
      <table class="table position-table">
        <thead><tr><th>代码</th><th>名称</th><th>持仓/可卖</th><th>成本</th><th>现价</th><th>市值</th><th>浮盈亏</th><th>收益率</th><th>止损</th><th>目标</th><th>移动线</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in account.positions || []" :key="row.symbol">
            <td>{{ row.symbol }}</td><td>{{ row.name }}</td><td>{{ row.quantity }} / {{ row.available_quantity }}</td>
            <td>{{ price(row.average_cost) }}</td><td>{{ price(row.last_price) }}</td><td>{{ money(row.market_value) }}</td>
            <td :class="Number(row.unrealized_pnl)>=0?'up':'down'">{{ money(row.unrealized_pnl) }}</td>
            <td :class="Number(row.pnl_rate)>=0?'up':'down'">{{ pct(row.pnl_rate) }}</td>
            <td class="down">{{ row.stop_loss ? price(row.stop_loss) : '--' }}</td>
            <td class="up">{{ row.target_price ? price(row.target_price) : '--' }}</td>
            <td>{{ row.trailing_stop_price ? price(row.trailing_stop_price) : '--' }}</td>
            <td><button class="mini-btn" :disabled="Number(row.available_quantity)<=0" @click="sellPosition(row)">带入卖出</button></td>
          </tr>
          <tr v-if="!(account.positions || []).length"><td colspan="12" class="muted">当前没有模拟持仓</td></tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="two-col block">
    <div class="card">
      <h3>交易计划</h3>
      <div class="scroll-list">
        <div v-for="plan in plans" :key="plan.id" class="plan-row">
          <div><b>{{ plan.symbol }} {{ plan.name }}</b><span>{{ statusLabel(plan.status) }} · 评分 {{ plan.score || '--' }}</span></div>
          <div class="plan-prices"><span>买 {{ plan.buy_low }}–{{ plan.buy_high }}</span><span class="down">止 {{ plan.stop_loss }}</span><span class="up">目 {{ plan.target1 }}</span></div>
          <div class="row-actions">
            <button v-if="plan.status==='ACTIVE'" class="mini-btn primary" :disabled="actionLoading===`plan-${plan.id}`" @click="orderFromPlan(plan)">生成委托</button>
            <button v-if="['ACTIVE','ORDER_CREATED'].includes(plan.status)" class="mini-btn" @click="cancelPlan(plan)">取消</button>
          </div>
        </div>
        <div v-if="!plans.length" class="muted empty">从“智能选股”页面将候选加入交易计划。</div>
      </div>
    </div>

    <div class="card">
      <h3>未处理风险事件</h3>
      <div class="scroll-list">
        <div v-for="row in risks" :key="row.id" class="risk-row">
          <div><b :class="row.level==='HIGH'?'down':''">{{ row.symbol }} · {{ row.event_type }}</b><span>{{ row.message }}</span></div>
          <div class="risk-price">现 {{ row.current_price || '--' }} / 线 {{ row.trigger_price || '--' }}</div>
          <button class="mini-btn" @click="resolveRisk(row)">已处理</button>
        </div>
        <div v-if="!risks.length" class="muted empty">当前没有未处理风险事件</div>
      </div>
    </div>
  </div>

  <div class="card block">
    <h3>待确认与历史委托</h3>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>ID</th><th>编号</th><th>代码</th><th>方向</th><th>数量</th><th>限价</th><th>状态</th><th>冻结</th><th>成交净额</th><th>时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in orders" :key="row.id">
            <td>{{ row.id }}</td><td>{{ row.client_order_no }}</td><td>{{ row.symbol }} {{ row.display_name }}</td>
            <td :class="row.side==='BUY'?'up':'down'">{{ row.side }}</td><td>{{ row.quantity }}</td><td>{{ price(row.limit_price) }}</td>
            <td>{{ statusLabel(row.status) }}</td><td>{{ row.side==='BUY' ? money(row.frozen_amount) : `${row.frozen_quantity || 0}股` }}</td>
            <td>{{ row.net_amount ? money(row.net_amount) : '--' }}</td><td>{{ row.created_at }}</td>
            <td class="row-actions">
              <button v-if="row.status==='PENDING_CONFIRM'" class="mini-btn primary" @click="confirm(row)">确认成交</button>
              <button v-if="row.status==='PENDING_CONFIRM'" class="mini-btn" @click="cancelOrder(row)">撤销</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="two-col block">
    <div class="card">
      <h3>账户权益曲线</h3>
      <div ref="chartEl" class="equity-chart"></div>
    </div>
    <div class="card">
      <h3>模拟成交流水</h3>
      <div class="table-wrap trade-scroll">
        <table class="table">
          <thead><tr><th>时间</th><th>股票</th><th>方向</th><th>数量</th><th>价格</th><th>净额</th><th>费用</th><th>已实现盈亏</th></tr></thead>
          <tbody>
            <tr v-for="row in trades" :key="row.id">
              <td>{{ row.trade_time }}</td><td>{{ row.symbol }} {{ row.name }}</td><td :class="row.side==='BUY'?'up':'down'">{{ row.side }}</td>
              <td>{{ row.quantity }}</td><td>{{ price(row.price) }}</td><td>{{ money(row.net_amount) }}</td>
              <td>{{ money(Number(row.commission||0)+Number(row.stamp_duty||0)+Number(row.transfer_fee||0)) }}</td>
              <td :class="Number(row.realized_pnl)>=0?'up':'down'">{{ money(row.realized_pnl) }}</td>
            </tr>
            <tr v-if="!trades.length"><td colspan="8" class="muted">暂无模拟成交记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.title-row{display:flex;align-items:center;justify-content:space-between}.account-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}.metric small{display:block;margin-top:6px;color:#7d8da5;font-size:11px}.order-section{grid-template-columns:3fr 1fr}.order-form input{width:130px}.order-form select{width:90px}.rules{display:grid;gap:7px}.rules span{color:#8ea0b8;font-size:12px}.notice{margin:12px 0;padding:10px 12px;border:1px solid #29425e;background:#0f1b2c;border-radius:4px}.block{margin-top:12px}.table-wrap{overflow:auto}.position-table{min-width:1260px}.two-col{display:grid;grid-template-columns:1fr 1fr;gap:12px}.scroll-list{max-height:350px;overflow:auto}.plan-row,.risk-row{display:grid;grid-template-columns:1.4fr 1.1fr auto;gap:10px;align-items:center;padding:10px 0;border-bottom:1px solid #1c2b40}.plan-row b,.risk-row b{display:block;font-size:12px}.plan-row span,.risk-row span{display:block;color:#7d8da5;font-size:11px;margin-top:3px}.plan-prices{display:flex;gap:10px}.risk-price{font-size:11px;color:#8fa2bb}.row-actions{display:flex;gap:5px;white-space:nowrap}.mini-btn{border:1px solid #30455f;background:#1a2b40;color:#b8c6d8;border-radius:3px;padding:5px 8px;cursor:pointer;font-size:11px}.mini-btn.primary{border-color:#1677ff;background:#1677ff;color:white}.mini-btn:disabled{opacity:.45;cursor:not-allowed}.empty{padding:20px 0;text-align:center}.equity-chart{height:300px}.trade-scroll{max-height:300px}@media(max-width:1450px){.account-grid{grid-template-columns:repeat(3,1fr)}.two-col{grid-template-columns:1fr}.order-section{grid-template-columns:1fr}}
</style>
