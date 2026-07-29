<script setup lang="ts">
import type {
  PreviewIssue,
  PreviewMode,
  PreviewQualification,
} from '../../research-preview/types'

defineProps<{
  mode: PreviewMode
  qualification: PreviewQualification
  synthetic: boolean
  issues: PreviewIssue[]
  loading: boolean
}>()

const emit = defineEmits<{
  switchMode: [mode: PreviewMode]
  reloadLocal: []
}>()
</script>

<template>
  <section class="qualification-panel">
    <div class="qualification-main">
      <div class="qualification-copy">
        <p class="eyebrow">RESEARCH PREVIEW · READ ONLY</p>
        <h1>免费研究预览</h1>
        <p class="disclaimer">本页面用于研究产品形态验证，不构成投资建议、收益承诺或自动交易指令。</p>
      </div>
      <div class="mode-actions">
        <el-button
          :type="mode === 'EXISTING_RESEARCH_SNAPSHOT' ? 'primary' : 'default'"
          :disabled="loading"
          @click="emit('switchMode', 'EXISTING_RESEARCH_SNAPSHOT')"
        >
          本地研究快照
        </el-button>
        <el-button
          :type="mode === 'TEST_DEMO_EXPLICIT' ? 'warning' : 'default'"
          :disabled="loading"
          @click="emit('switchMode', 'TEST_DEMO_EXPLICIT')"
        >
          显式固定演示
        </el-button>
        <el-button
          v-if="mode === 'EXISTING_RESEARCH_SNAPSHOT'"
          :loading="loading"
          plain
          @click="emit('reloadLocal')"
        >
          重新读取已有快照
        </el-button>
      </div>
    </div>

    <div class="primary-status">
      <span><b>当前模式</b>{{ mode }}</span>
      <span><b>数据资格</b>{{ qualification }}</span>
      <span class="safe"><b>访问边界</b>READ_ONLY</span>
      <span :class="{ demo: synthetic }"><b>数据性质</b>{{ synthetic ? 'SYNTHETIC' : '非合成' }}</span>
    </div>

    <div v-if="synthetic" class="demo-identity">
      <b>TEST_DEMO_EXPLICIT</b>
      <span>SYNTHETIC</span>
      <span>NOT_REAL_MARKET_RESULT</span>
    </div>
    <div v-else class="research-identity">
      <b>EXISTING_RESEARCH_SNAPSHOT</b>
      <span>RESEARCH_HISTORICAL_UNVERIFIED</span>
      <span>只读取已有结果</span>
    </div>

    <details class="safety-details">
      <summary>运行与安全边界</summary>
      <div>
        <span><b>外部Provider调用数</b>0</span>
        <span><b>iFinD调用数</b>0</span>
        <span><b>Shadow状态</b>未启动</span>
        <span><b>scheduler状态</b>关闭</span>
        <span><b>正常业务库V13</b>未执行</span>
      </div>
    </details>

    <div v-if="issues.length" class="issue-list">
      <article v-for="issue in issues" :key="issue.code">
        <code>{{ issue.code }}</code>
        <span>{{ issue.detail }}</span>
        <el-button
          v-if="issue.code === 'PREVIEW_LOCAL_API_UNAVAILABLE'"
          size="small"
          type="warning"
          plain
          @click="emit('switchMode', 'TEST_DEMO_EXPLICIT')"
        >
          切换到显式演示
        </el-button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.qualification-panel {
  display: grid;
  gap: 11px;
  padding: 17px 20px;
  overflow: hidden;
  border: 1px solid #2a4668;
  border-radius: 14px;
  background:
    radial-gradient(circle at 86% -60%, rgba(43, 126, 214, .24), transparent 42%),
    linear-gradient(145deg, #11233a, #0a1524);
  box-shadow: 0 13px 30px rgba(0, 0, 0, .16);
}
.qualification-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.qualification-copy h1 { margin: 0; font-size: 24px; }
.eyebrow { margin: 0 0 4px; color: #67b4f8; font-size: 11px; font-weight: 800; letter-spacing: .16em; }
.disclaimer { margin: 6px 0 0; color: #b9c7d8; font-size: 13px; line-height: 1.55; }
.mode-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.primary-status { display: flex; flex-wrap: wrap; gap: 8px; }
.primary-status span {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid #2b425d;
  border-radius: 999px;
  background: rgba(6, 15, 27, .56);
  color: #c9d4e2;
  font: 12px ui-monospace, monospace;
}
.primary-status b { color: #94a9c0; font: 600 12px system-ui, sans-serif; }
.primary-status .safe { border-color: #27614d; color: #79d7b2; }
.primary-status .demo { border-color: #795b27; color: #ffd080; }
.demo-identity, .research-identity {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}
.demo-identity { color: #ffd080; }
.research-identity { color: #77d7b0; }
.demo-identity span, .research-identity span { padding-left: 8px; border-left: 1px solid currentColor; }
.safety-details { border-top: 1px solid #233b55; }
.safety-details summary { padding-top: 10px; color: #84bff3; font-size: 12px; cursor: pointer; }
.safety-details > div { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.safety-details span { padding: 8px 10px; border-radius: 7px; background: #091523; color: #d1dbe7; font-size: 12px; }
.safety-details b { display: block; margin-bottom: 4px; color: #91a4ba; font-weight: 500; }
.issue-list { display: grid; gap: 7px; }
.issue-list article {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  border: 1px solid #68444a;
  border-radius: 8px;
  background: rgba(80, 27, 35, .3);
}
.issue-list code { color: #ff9ca6; }
.issue-list span { flex: 1; color: #cbd5e1; font-size: 13px; }
@media (max-width: 1200px) {
  .qualification-main { align-items: flex-start; }
  .safety-details > div { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 850px) {
  .qualification-main { display: grid; }
  .mode-actions { justify-content: flex-start; }
  .safety-details > div { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
