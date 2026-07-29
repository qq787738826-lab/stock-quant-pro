<script setup lang="ts">
import { computed } from 'vue'
import { displayTime, displayValue } from '../../research-preview/presentation'
import type { PreviewTaskBundle } from '../../research-preview/types'

const props = defineProps<{
  bundle: PreviewTaskBundle | null
}>()

const task = computed(() => props.bundle?.task ?? null)
const decision = computed(() => props.bundle?.decision ?? null)
</script>

<template>
  <section class="audit-panel">
    <details>
      <summary>
        <span>
          <b>技术审计详情</b>
          <small>任务身份、context、运行来源与时间线</small>
        </span>
        <i>按需展开</i>
      </summary>
      <div v-if="!bundle" class="empty">暂无已加载任务，无法展示技术审计字段。</div>
      <div v-else class="audit-body">
        <dl>
          <div><dt>taskId</dt><dd>{{ displayValue(task?.id) }}</dd></div>
          <div><dt>ruleVersion</dt><dd>{{ displayValue(task?.ruleVersion) }}</dd></div>
          <div><dt>contextSchemaVersion</dt><dd>{{ displayValue(task?.contextSchemaVersion) }}</dd></div>
          <div><dt>contextHash</dt><dd class="hash">{{ displayValue(task?.contextHash) }}</dd></div>
          <div><dt>triggerType</dt><dd>{{ displayValue(task?.triggerType) }}</dd></div>
          <div><dt>executionMode</dt><dd>{{ displayValue(task?.executionMode) }}</dd></div>
          <div><dt>任务状态</dt><dd>{{ displayValue(task?.status) }}</dd></div>
          <div><dt>sourceRunIds</dt><dd>{{ (decision?.sourceRunIds ?? []).join(', ') || '暂无' }}</dd></div>
          <div><dt>vetoIds</dt><dd>{{ (decision?.vetoIds ?? []).join(', ') || '暂无' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ displayTime(task?.createdAt) }}</dd></div>
          <div><dt>开始时间</dt><dd>{{ displayTime(task?.startedAt) }}</dd></div>
          <div><dt>完成时间</dt><dd>{{ displayTime(task?.finishedAt) }}</dd></div>
        </dl>
        <p>
          上述字段仅用于重放和审计。contextHash是本地权威上下文Hash，不代表Provider revision。
        </p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.audit-panel {
  border: 1px solid #263e59;
  border-radius: 12px;
  background: #0d1a2a;
}
details > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 16px 18px;
  cursor: pointer;
  list-style: none;
}
details > summary::-webkit-details-marker { display: none; }
summary span { display: grid; gap: 4px; }
summary b { color: #e5edf7; font-size: 15px; }
summary small { color: #91a4bb; font-size: 12px; }
summary i { color: #7abcf6; font-size: 12px; font-style: normal; }
.audit-body { padding: 0 18px 18px; border-top: 1px solid #21364e; }
dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 16px 0 0;
}
dl div { min-width: 0; padding: 10px; border-radius: 8px; background: #091422; }
dt { color: #8ea2ba; font-size: 12px; }
dd { margin: 5px 0 0; overflow-wrap: anywhere; color: #c7d2df; font-size: 12px; }
.hash { font-family: ui-monospace, monospace; }
p { margin: 14px 0 0; color: #9fb0c4; font-size: 13px; }
.empty { padding: 18px; border-top: 1px solid #21364e; color: #93a5bb; font-size: 13px; }
@media (max-width: 1200px) {
  dl { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
