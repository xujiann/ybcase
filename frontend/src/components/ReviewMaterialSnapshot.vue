<template>
  <el-alert type="info" :closable="false" title="以下为选定审核申请送审时保存的材料快照，只读展示；当前案件后续修改不会改写本快照。" />
  <p class="fingerprint">申请编号：{{ review?.id ?? '—' }}；材料指纹：{{ review?.materialFingerprint || '未绑定' }}</p>
  <el-alert v-if="!sections.length" type="warning" :closable="false" title="此历史申请没有可用材料快照。请重新送审，不以当前案件材料替代历史快照。" />
  <el-collapse v-else class="snapshot-sections">
    <el-collapse-item v-for="section in sections" :key="section.key" :title="section.label" :name="section.key"><pre>{{ section.content }}</pre></el-collapse-item>
  </el-collapse>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { snapshotSections, type MaterialReview } from '../api/reviewMaterials'
const props = defineProps<{ review: MaterialReview | null }>()
const sections = computed(() => snapshotSections(props.review?.materialSnapshot))
</script>
<style scoped>
.fingerprint { overflow-wrap: anywhere; font-size: 12px; color: #666; }
.snapshot-sections { max-height: 320px; overflow: auto; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; font: inherit; font-size: 12px; }
</style>
