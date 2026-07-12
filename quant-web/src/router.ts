import {createRouter,createWebHashHistory} from 'vue-router'
import Dashboard from './views/Dashboard.vue'
const placeholder=(title:string)=>()=>import('./views/Placeholder.vue').then(m=>({render(){return h(m.default,{title})}}))
import {h} from 'vue'
export default createRouter({history:createWebHashHistory(),routes:[
 {path:'/',component:Dashboard},{path:'/signals',component:()=>import('./views/Signals.vue')},{path:'/portfolio',component:()=>import('./views/Portfolio.vue')},{path:'/backtest',component:()=>import('./views/Backtest.vue')},{path:'/ai',component:()=>import('./views/Ai.vue')},
 {path:'/market',component:placeholder('全市场行情')},{path:'/watchlist',component:placeholder('自选股')},{path:'/strategy',component:placeholder('策略中心')},{path:'/risk',component:placeholder('风险中心')},{path:'/news',component:placeholder('新闻公告')},{path:'/data',component:placeholder('数据管理')},{path:'/settings',component:placeholder('系统设置')}
]})
