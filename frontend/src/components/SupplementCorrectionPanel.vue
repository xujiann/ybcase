<template>
  <HistoricalReportSupplement :case-id="caseId" correction />
</template>
<script setup lang="ts">
import HistoricalReportSupplement from '../views/case/HistoricalReportSupplement.vue'
defineProps<{ caseId?: number }>()
</script>
