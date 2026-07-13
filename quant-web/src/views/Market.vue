<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'

const query = reactive({ keyword: '', page: 1, size: 50 })
const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const syncingSymbol = ref('')
const message = ref('')
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result: any = await api.get('/market/securities', { params: query })
    rows.value = result.items || []
    total.value = Number(result.total || 0)
  } catch (e: any) {
    error.value = e.message || '股票列表加载失败'
  } finally {
    loading.value = false
  }
}

async function search() {
  query.page = 1
  await load()
}

async function syncHistory(symbol: string) {
  syncingSymbol.value = symbol
  error.value = ''
  message.value = ''
  try {
    const result: any = await api.post('/data/history/sync', null, { params: { symbol, days: 260 } })
    message.value = `${symbol} 已同步 ${result.count} 条K线，最新日期 ${result.lastDate}`
    await load()
  } catch (e: any) {
    error.value = e.message || 'K线同步失败'
  } finally {
    syncingSymbol.value = ''
  }
}

function previous() {
  if (query.page > 1) { query.page--; load() }
}
function next() {
  if (query.page * query.size < total.value) { query.page++; load() }
}

onMounted(load)
</script>

<template>
  <h1 class="page-title">全市场行情库</h1>
  <div class="card">
    <div class="form-row">
      <input v-model.trim="query.keyword" placeholder="股票代码或名称" @keyup.enter="search">
      <button class="btn" @click="search">查询</button>
      <span class="muted">共 {{ total }} 只沪深主板股票</span>
    </div>

    <table class="table">
      <thead><tr><th>代码</th><th>名称</th><th>交易所</th><th>最新交易日</th><th>最新收盘</th><th>数据源</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row.symbol">
          <td>{{ row.symbol }}</td>
          <td>{{ row.name }}</td>
          <td>{{ row.exchange }}</td>
          <td>{{ row.latest_trade_date || '未缓存' }}</td>
          <td>{{ row.latest_close ?? '--' }}</td>
          <td>{{ row.data_source || '--' }}</td>
          <td class="actions">
            <router-link :to="`/ai?symbol=${row.symbol}`">分析</router-link>
            <router-link :to="`/backtest?symbol=${row.symbol}`">回测</router-link>
            <button class="link-btn" :disabled="syncingSymbol===row.symbol" @click="syncHistory(row.symbol)">
              {{ syncingSymbol===row.symbol ? '同步中' : '同步K线' }}
            </button>
          </td>
        </tr>
        <tr v-if="!loading && rows.length===0"><td colspan="7" class="muted">暂无数据，请先到数据管理中心同步股票列表。</td></tr>
      </tbody>
    </table>

    <div class="pager">
      <button class="btn secondary" :disabled="query.page<=1" @click="previous">上一页</button>
      <span>第 {{ query.page }} 页</span>
      <button class="btn secondary" :disabled="query.page*query.size>=total" @click="next">下一页</button>
    </div>
  </div>
  <p v-if="message" class="success">{{ message }}</p>
  <p v-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.actions { display:flex; gap:10px; align-items:center; }
.actions a, .link-btn { color:#5da9ff; text-decoration:none; font-size:12px; }
.link-btn { background:none; border:0; padding:0; cursor:pointer; }
.link-btn:disabled { opacity:.5; cursor:wait; }
.pager { display:flex; justify-content:flex-end; align-items:center; gap:12px; margin-top:14px; color:#7d8da5; font-size:12px; }
</style>
