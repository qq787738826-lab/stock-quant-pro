<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'

const route=useRoute()
const f=reactive({symbol:'600000',initialCapital:100000,maxHoldingDays:10})
const r=ref<any>()
const err=ref('')
const loading=ref(false)

async function run(){
  err.value=''; loading.value=true
  try{r.value=await api.post('/backtests',f)}catch(e:any){err.value=e.message||'回测失败'}finally{loading.value=false}
}
onMounted(()=>{const q=String(route.query.symbol||'');if(/^\d{6}$/.test(q))f.symbol=q})
</script>
<template><h1 class="page-title">回测中心</h1><div class="card"><div class="form-row"><input v-model="f.symbol" maxlength="6"><input v-model.number="f.initialCapital" type="number"><input v-model.number="f.maxHoldingDays" type="number"><button class="btn" :disabled="loading" @click="run">{{loading?'回测中...':'运行回测'}}</button></div><div class="error">{{err}}</div></div><div v-if="r" class="grid4" style="margin-top:12px"><div class="card metric"><label>最终资金</label><strong>{{r.finalCapital}}</strong></div><div class="card metric"><label>总收益率</label><strong>{{(Number(r.totalReturn)*100).toFixed(2)}}%</strong></div><div class="card metric"><label>最大回撤</label><strong>{{(Number(r.maxDrawdown)*100).toFixed(2)}}%</strong></div><div class="card metric"><label>胜率</label><strong>{{(Number(r.winRate)*100).toFixed(2)}}%</strong></div></div><div v-if="r" class="card" style="margin-top:12px"><h3>交易明细（{{r.tradeCount}}笔）</h3><table class="table"><thead><tr><th>开仓</th><th>平仓</th><th>买价</th><th>卖价</th><th>数量</th><th>盈亏</th><th>原因</th></tr></thead><tbody><tr v-for="(t,i) in r.trades" :key="i"><td>{{t.entryDate}}</td><td>{{t.exitDate}}</td><td>{{t.entryPrice}}</td><td>{{t.exitPrice}}</td><td>{{t.quantity}}</td><td>{{t.pnl}}</td><td>{{t.exitReason}}</td></tr></tbody></table></div></template>
