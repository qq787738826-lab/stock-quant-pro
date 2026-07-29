<script setup lang="ts">
import { computed } from 'vue'
import { uniqueEvidence } from '../../agent-team/presentation'
import { displayTime, displayValue } from '../../research-preview/presentation'
import type { Evidence } from '../../agent-team/types'
import type { PreviewQualification } from '../../research-preview/types'

const props = defineProps<{
  evidence: Evidence[]
  qualification: PreviewQualification
  synthetic: boolean
}>()

const result = computed(() => uniqueEvidence(props.evidence))

function distribution(
  selector: (item: Evidence) => string,
): Array<{ label: string; count: number }> {
  const counts = new Map<string, number>()
  for (const item of result.value.items) {
    const label = selector(item)
    counts.set(label, (counts.get(label) ?? 0) + 1)
  }
  return [...counts.entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((left, right) => left.label.localeCompare(right.label))
}

const categoryDistribution = computed(() => distribution((item) => item.category))
const sourceDistribution = computed(() => distribution((item) => item.sourceType))

function formatFields(fields: Evidence['fields']): string {
  return JSON.stringify(fields, null, 2)
}
</script>

<template>
  <section class="evidence-panel">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">EVIDENCE &amp; LINEAGE</p>
        <h2>证据摘要与来源追溯</h2>
      </div>
      <span>{{ result.items.length }}条唯一证据</span>
    </div>

    <div class="evidence-summary">
      <article>
        <span>数据资格</span>
        <strong>{{ qualification }}</strong>
      </article>
      <article>
        <span>category分布</span>
        <p>
          <b v-for="item in categoryDistribution" :key="item.label">{{ item.label }} {{ item.count }}</b>
          <i v-if="!categoryDistribution.length">暂无</i>
        </p>
      </article>
      <article>
        <span>sourceType分布</span>
        <p>
          <b v-for="item in sourceDistribution" :key="item.label">{{ item.label }} {{ item.count }}</b>
          <i v-if="!sourceDistribution.length">暂无</i>
        </p>
      </article>
      <article>
        <span>contentHash说明</span>
        <strong>本地完整性标识，不是Provider revision</strong>
      </article>
    </div>

    <p class="boundary">
      证据展示不改变其原始来源资格，本地contentHash不得解释为Provider revision。
    </p>
    <div v-if="result.duplicateIds.length" class="duplicate-warning">
      重复evidenceId已告警并保留首次记录：{{ result.duplicateIds.join('、') }}
    </div>
    <div v-if="!result.items.length" class="empty">暂无证据</div>

    <div v-else class="evidence-list">
      <details v-for="item in result.items" :key="item.evidenceId" class="evidence-item">
        <summary>
          <span>
            <strong>{{ item.evidenceId }}</strong>
            <small>{{ item.category }} · {{ item.sourceType }}</small>
          </span>
          <i>{{ synthetic ? '演示数据' : '展开证据' }}</i>
        </summary>
        <div class="evidence-body">
          <dl>
            <div><dt>evidenceId</dt><dd>{{ item.evidenceId }}</dd></div>
            <div><dt>category</dt><dd>{{ item.category }}</dd></div>
            <div><dt>sourceType</dt><dd>{{ item.sourceType }}</dd></div>
            <div><dt>sourceName</dt><dd>{{ displayValue(item.sourceName) }}</dd></div>
            <div><dt>sourceRef</dt><dd>{{ displayValue(item.sourceRef) }}</dd></div>
            <div><dt>symbol</dt><dd>{{ displayValue(item.symbol) }}</dd></div>
            <div><dt>tradeDate</dt><dd>{{ displayValue(item.tradeDate) }}</dd></div>
            <div><dt>observedAt</dt><dd>{{ displayTime(item.observedAt) }}</dd></div>
            <div><dt>collectedAt</dt><dd>{{ displayTime(item.collectedAt) }}</dd></div>
            <div class="wide"><dt>contentHash</dt><dd class="hash">{{ displayValue(item.contentHash) }}</dd></div>
          </dl>
          <details class="fields-details">
            <summary>展开只读fields</summary>
            <pre>{{ formatFields(item.fields) }}</pre>
          </details>
        </div>
      </details>
    </div>
  </section>
</template>

<style scoped>
.evidence-panel { min-width: 0; padding: 18px; border: 1px solid #263f5b; border-radius: 12px; background: #0e1b2c; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.panel-heading h2 { margin: 0; font-size: 19px; }
.panel-heading > span { color: #9fb2c8; font-size: 13px; }
.eyebrow { margin: 0 0 5px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.evidence-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; margin-top: 14px; }
.evidence-summary article { min-width: 0; padding: 11px; border-radius: 8px; background: #091624; }
.evidence-summary span { display: block; color: #91a5bc; font-size: 12px; }
.evidence-summary strong { display: block; margin-top: 5px; overflow-wrap: anywhere; color: #d2dce8; font-size: 12px; }
.evidence-summary p { display: flex; flex-wrap: wrap; gap: 5px; margin: 6px 0 0; }
.evidence-summary p b { padding: 3px 6px; border-radius: 999px; background: #17334b; color: #a9cbe7; font-size: 11px; }
.evidence-summary i { color: #95a7bb; font-size: 12px; font-style: normal; }
.boundary { color: #9aacc0; font-size: 13px; line-height: 1.55; }
.duplicate-warning { margin: 10px 0; padding: 9px; border: 1px solid #775b26; border-radius: 7px; color: #efc173; font-size: 13px; }
.evidence-list { display: grid; gap: 8px; }
.evidence-item { border: 1px solid #213851; border-radius: 8px; background: #091523; }
.evidence-item > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 13px;
  cursor: pointer;
  list-style: none;
}
.evidence-item > summary::-webkit-details-marker { display: none; }
.evidence-item > summary strong { display: block; color: #dbe5ef; font-size: 13px; overflow-wrap: anywhere; }
.evidence-item > summary small { display: block; margin-top: 4px; color: #96a9bf; font-size: 12px; }
.evidence-item > summary i { color: #82bff0; font-size: 12px; font-style: normal; }
.evidence-body { padding: 0 13px 13px; border-top: 1px solid #21364e; }
dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 13px 0 0; }
dl div { min-width: 0; padding: 8px; border-radius: 6px; background: #0c1d2f; }
dl div.wide { grid-column: 1 / -1; }
dt { color: #91a5bc; font-size: 11px; }
dd { margin: 4px 0 0; overflow-wrap: anywhere; color: #c7d3df; font-size: 12px; }
.hash { font-family: ui-monospace, monospace; }
.fields-details { margin-top: 10px; }
.fields-details summary { color: #82bff0; font-size: 12px; cursor: pointer; }
pre {
  max-height: 300px;
  margin: 10px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 6px;
  background: #050b13;
  color: #b8c7d8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 11px/1.6 ui-monospace, monospace;
}
.empty { padding: 30px; color: #93a6bd; font-size: 13px; text-align: center; }
@media (max-width: 1250px) {
  .evidence-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  dl { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
