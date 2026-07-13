import { createRouter, createWebHashHistory } from 'vue-router'
import { h } from 'vue'
import Dashboard from './views/Dashboard.vue'

const placeholder = (title: string) => () => import('./views/Placeholder.vue').then(m => ({ render() { return h(m.default, { title }) } }))

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Dashboard },
    { path: '/signals', component: () => import('./views/Signals.vue') },
    { path: '/portfolio', component: () => import('./views/Portfolio.vue') },
    { path: '/backtest', component: () => import('./views/Backtest.vue') },
    { path: '/ai', component: () => import('./views/Ai.vue') },
    { path: '/market', component: () => import('./views/Market.vue') },
    { path: '/data', component: () => import('./views/DataCenter.vue') },
    { path: '/watchlist', component: placeholder('自选股') },
    { path: '/strategy', component: placeholder('策略中心') },
    { path: '/risk', component: placeholder('风险中心') },
    { path: '/news', component: placeholder('新闻公告') },
    { path: '/settings', component: placeholder('系统设置') },
  ],
})
