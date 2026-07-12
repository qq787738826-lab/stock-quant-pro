<script setup lang="ts">
defineProps<{ title: string }>();
</script>

<template>
  <h1 class="page-title">{{ title }}</h1>
  <div class="card">
    <h3>模块已预留</h3>
    <p class="muted">专业版架构、路由和权限边界已建立。接入真实全市场数据后在此展示对应功能。</p>
  </div>
</template>
