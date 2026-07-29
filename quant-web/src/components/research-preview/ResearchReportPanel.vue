<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  report: string
  synthetic: boolean
}>()

const expanded = ref(true)
const copyStatus = ref('')

async function copyReport(report: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(report)
    copyStatus.value = '已复制UI展示报告'
  } catch {
    copyStatus.value = '复制失败，请手动选择文本'
  }
}
</script>

<template>
  <section class="report-panel">
    <div class="panel-heading">
      <div><p class="eyebrow">UI_PRESENTATION_ONLY</p><h2>综合研究报告</h2></div>
      <div>
        <el-button size="small" plain @click="expanded = !expanded">{{ expanded ? '收起' : '展开' }}</el-button>
        <el-button size="small" type="primary" plain @click="copyReport(report)">复制报告文本</el-button>
      </div>
    </div>
    <div v-if="synthetic" class="synthetic">TEST_DEMO_EXPLICIT · SYNTHETIC · NOT_REAL_MARKET_RESULT</div>
    <pre v-if="expanded">{{ report }}</pre>
    <p v-if="copyStatus" class="copy-status">{{ copyStatus }}</p>
    <p class="boundary">报告只重排已有结构化结果，不调用LLM、不新增评分、预测、收益声明或买卖建议，也不写数据库。</p>
  </section>
</template>

<style scoped>
.report-panel { padding: 20px; border: 1px solid #28445f; border-radius: 12px; background: linear-gradient(145deg, #102239, #0b1727); }.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.panel-heading h2 { margin: 0; font-size: 19px; }.eyebrow { margin: 0 0 5px; color: #5eaaff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }.synthetic { margin: 12px 0; padding: 8px; border: 1px solid #765a29; border-radius: 6px; color: #ffc967; font: 10px ui-monospace, monospace; }
pre { max-height: 520px; margin: 14px 0 0; padding: 15px; overflow: auto; border: 1px solid #1e334b; border-radius: 8px; background: #050b13; color: #c1cede; white-space: pre-wrap; overflow-wrap: anywhere; font: 11px/1.75 ui-monospace, monospace; }.copy-status { color: #6ed6aa; font-size: 10px; }.boundary { color: #778aa2; font-size: 10px; }
</style>
