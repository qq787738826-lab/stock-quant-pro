<script setup lang="ts">
import { useRoute } from 'vue-router'
const route=useRoute()
const menus=[['/','行情总览'],['/watchlist','自选股'],['/market','全市场'],['/signals','智能选股'],['/portfolio','持仓管理'],['/backtest','回测中心'],['/strategy','策略中心'],['/risk','风险中心'],['/ai','AI分析'],['/news','新闻公告'],['/data','数据管理'],['/settings','系统设置']]
const aiMenuIndex=menus.findIndex(menu=>menu[0]==='/ai')
menus.splice(aiMenuIndex+1,0,['/agent-team','智能体团队'])
</script>
<template><div class="terminal"><aside><div class="brand"><b>SQ</b><span>Stock Quant Pro<small>A股短线量化终端</small></span></div><nav><router-link v-for="m in menus" :key="m[0]" :to="m[0]" :class="{active:route.path===m[0]}">{{m[1]}}</router-link></nav><div class="status"><i></i>本地服务模式</div></aside><main><header><div class="search">输入股票代码 / 名称快速搜索</div><div class="clock">沪深主板 · 人工确认交易</div></header><section class="content"><router-view/></section></main></div></template>
