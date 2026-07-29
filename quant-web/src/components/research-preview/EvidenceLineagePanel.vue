<script setup lang="ts">
import { computed, ref } from 'vue'
import { uniqueEvidence } from '../../agent-team/presentation'
import { displayTime, displayValue } from '../../research-preview/presentation'
import type { Evidence } from '../../agent-team/types'
import type { PreviewQualification } from '../../research-preview/types'

const props = defineProps<{
  evidence: Evidence[]
  qualification: PreviewQualification
  synthetic: boolean
}>()

const expanded = ref(new Set<string>())
const result = computed(() => uniqueEvidence(props.evidence))

function toggle(evidenceId: string): void {
  const next = new Set(expanded.value)
  if (next.has(evidenceId)) next.delete(evidenceId)
  else next.add(evidenceId)
  expanded.value = next
}

function formatFields(fields: Evidence['fields']): string {
  return JSON.stringify(fields, null, 2)
}
</script>

<template>
  <section class="evidence-panel">
    <div class="panel-heading">
      <div><p class="eyebrow">EVIDENCE &amp; LINEAGE</p><h2>证据与lineage</h2></div>
      <div class="heading-tags"><code>{{ qualification }}</code><span>{{ result.items.length }}条</span></div>
    </div>
    <p class="boundary">证据展示不改变其原始来源资格。本地contentHash不得解释为Provider revision。</p>
    <div v-if="result.duplicateIds.length" class="duplicate-warning">
      重复evidenceId已告警并保留首次记录：{{ result.duplicateIds.join('、') }}
    </div>
    <div v-if="!result.items.length" class="empty">暂无证据</div>
    <article v-for="item in result.items" v-else :key="item.evidenceId">
      <header>
        <div>
          <strong>{{ item.evidenceId }}</strong>
          <span>{{ item.category }} · {{ item.sourceType }}</span>
        </div>
        <el-button text type="primary" @click="toggle(item.evidenceId)">
          {{ expanded.has(item.evidenceId) ? '收起fields' : '展开fields' }}
        </el-button>
      </header>
      <dl>
        <div><dt>sourceName</dt><dd>{{ displayValue(item.sourceName) }}</dd></div>
        <div><dt>sourceRef</dt><dd>{{ displayValue(item.sourceRef) }}</dd></div>
        <div><dt>symbol / tradeDate</dt><dd>{{ displayValue(item.symbol) }} / {{ displayValue(item.tradeDate) }}</dd></div>
        <div><dt>observedAt</dt><dd>{{ displayTime(item.observedAt) }}</dd></div>
        <div><dt>collectedAt</dt><dd>{{ displayTime(item.collectedAt) }}</dd></div>
        <div><dt>contentHash</dt><dd class="hash">{{ displayValue(item.contentHash) }}</dd></div>
      </dl>
      <div v-if="synthetic" class="synthetic">TEST_DEMO_EXPLICIT · NOT_REAL_MARKET_RESULT</div>
      <pre v-if="expanded.has(item.evidenceId)">{{ formatFields(item.fields) }}</pre>
    </article>
  </section>
</template>

<style scoped>
.evidence-panel { padding: 20px; border: 1px solid #223a57; border-radius: 12px; background: #0e1a2b; }.panel-heading, article header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.panel-heading h2 { margin: 0; font-size: 19px; }.eyebrow { margin: 0 0 5px; color: #5eaaff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.heading-tags { display: flex; gap: 8px; align-items: center; }.heading-tags code { color: #67d2a8; font-size: 10px; }.heading-tags span { padding: 3px 7px; border-radius: 99px; background: #20334a; font-size: 10px; }
.boundary { color: #8092aa; font-size: 11px; }.duplicate-warning { margin: 10px 0; padding: 9px; border: 1px solid #775b26; border-radius: 7px; color: #ffc466; }
article { margin-top: 10px; padding: 13px; border: 1px solid #1f334b; border-radius: 8px; background: #091422; }article header span { display: block; margin-top: 3px; color: #7890aa; font-size: 10px; }
dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 12px 0 0; }dl div { min-width: 0; }dt { color: #6f829a; font-size: 9px; }dd { margin: 3px 0 0; overflow-wrap: anywhere; color: #bbc8d8; font-size: 10px; }.hash { font-family: ui-monospace, monospace; }
.synthetic { margin-top: 10px; color: #ffc667; font: 10px ui-monospace, monospace; }pre { max-height: 340px; margin: 12px 0 0; padding: 12px; overflow: auto; border-radius: 6px; background: #050b13; color: #b7c7da; white-space: pre-wrap; overflow-wrap: anywhere; font: 11px/1.6 ui-monospace, monospace; }.empty { padding: 30px; color: #71849d; text-align: center; }
@media (max-width: 1200px) { dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
