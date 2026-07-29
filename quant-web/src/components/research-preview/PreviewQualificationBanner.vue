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

    <div class="qualification-grid">
      <div><span>当前模式</span><strong>{{ mode }}</strong></div>
      <div><span>数据资格</span><strong>{{ qualification }}</strong></div>
      <div><span>合成数据</span><strong>{{ synthetic ? 'SYNTHETIC' : '否' }}</strong></div>
      <div><span>访问边界</span><strong>READ_ONLY</strong></div>
      <div><span>外部Provider调用数</span><strong>0</strong></div>
      <div><span>iFinD调用数</span><strong>0</strong></div>
      <div><span>Shadow状态</span><strong>未启动 / scheduler关闭</strong></div>
      <div><span>正常业务库V13</span><strong>未执行</strong></div>
    </div>

    <div v-if="synthetic" class="synthetic-strip">
      <b>TEST_DEMO_EXPLICIT</b>
      <span>SYNTHETIC</span>
      <span>NOT_REAL_MARKET_RESULT</span>
    </div>
    <div v-else class="research-strip">
      <b>EXISTING_RESEARCH_SNAPSHOT</b>
      <span>RESEARCH_HISTORICAL_UNVERIFIED</span>
      <span>只读取已有结果，不创建任务</span>
    </div>

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
  gap: 18px;
  padding: 24px;
  overflow: hidden;
  border: 1px solid #2a4668;
  border-radius: 14px;
  background:
    radial-gradient(circle at 80% -30%, rgba(43, 126, 214, .27), transparent 42%),
    linear-gradient(145deg, #12243b, #0a1423);
  box-shadow: 0 18px 42px rgba(0, 0, 0, .2);
}
.qualification-copy h1 { margin: 0; font-size: 28px; }
.eyebrow { margin: 0 0 6px; color: #66b4ff; font-size: 11px; font-weight: 800; letter-spacing: .18em; }
.disclaimer { margin: 10px 0 0; color: #b8c7da; line-height: 1.7; }
.mode-actions { display: flex; flex-wrap: wrap; gap: 9px; }
.qualification-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.qualification-grid div { min-width: 0; padding: 11px 12px; border: 1px solid #243b58; border-radius: 8px; background: rgba(6, 14, 25, .55); }
.qualification-grid span { display: block; margin-bottom: 5px; color: #7890ad; font-size: 11px; }
.qualification-grid strong { overflow-wrap: anywhere; color: #ecf4ff; font-size: 12px; }
.synthetic-strip, .research-strip { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 12px; border-radius: 8px; font-size: 12px; }
.synthetic-strip { border: 1px solid #8b6427; background: rgba(111, 70, 12, .24); color: #ffd27b; }
.research-strip { border: 1px solid #24624c; background: rgba(14, 79, 57, .22); color: #74e4b9; }
.synthetic-strip span, .research-strip span { padding-left: 8px; border-left: 1px solid currentColor; opacity: .85; }
.issue-list { display: grid; gap: 8px; }
.issue-list article { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid #68444a; border-radius: 8px; background: rgba(80, 27, 35, .3); }
.issue-list code { color: #ff96a1; }
.issue-list span { flex: 1; color: #c7d2e1; font-size: 12px; }
@media (max-width: 1300px) { .qualification-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
