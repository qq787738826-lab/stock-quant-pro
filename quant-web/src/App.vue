<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from './api'

const route = useRoute()
const health = ref('CHECKING')
const menus = [
  ['/', '总览'],
  ['/research-selection', '立即选股'],
  ['/agent-research', 'Research'],
  ['/backtest', 'Strategy'],
  ['/shadow-research', 'Shadow'],
  ['/agent-evaluation', 'Evaluation'],
  ['/data', '数据'],
  ['/portfolio', '模拟组合'],
  ['/risk', '风险'],
]

onMounted(async () => {
  try {
    const value: any = await api.get('/system/health')
    health.value = value.status
  } catch {
    health.value = 'BLOCKED'
  }
})
</script>

<template>
  <div class="terminal">
    <aside>
      <div class="brand"><b>SQ</b><span>Stock Quant Pro<small>RESEARCH PRODUCTION V1</small></span></div>
      <nav><router-link v-for="menu in menus" :key="menu[0]" :to="menu[0]" :class="{ active: route.path === menu[0] }">{{ menu[1] }}</router-link></nav>
      <div class="status"><i :class="health.toLowerCase()"></i>{{ health }} · PAPER ONLY</div>
    </aside>
    <main>
      <header><div class="search">本地研究终端 · 数据、策略、Agent、Shadow、评测</div><div class="clock">沪深主板 · 无券商连接 · 禁止真实交易</div></header>
      <section class="content"><router-view /></section>
    </main>
  </div>
</template>
