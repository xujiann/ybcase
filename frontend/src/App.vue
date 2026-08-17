<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

/* ===== 手机端全局适配（≤768px），一处覆盖全部弹窗/表格，不用逐页改 ===== */
@media (max-width: 768px) {
  /* 固定像素宽的弹窗（480/560/640px 等）在窄屏会溢出——统一收窄到视口内 */
  .el-dialog {
    width: 94vw !important;
    max-width: 94vw !important;
    margin-top: 8vh !important;
  }
  .el-message-box { width: 90vw !important; max-width: 340px; }

  /* 抽屉最多占屏宽 85% */
  .el-drawer.rtl, .el-drawer.ltr { width: 85% !important; max-width: 320px; }

  /* 宽表格横向滚动，不撑破页面 */
  .el-table { max-width: 100%; }
  .el-table .cell { word-break: break-word; }

  /* 表单标签在窄屏改为顶部对齐更省横向空间（各页 label-width 仍生效，仅收窄内边距） */
  .el-form-item { margin-bottom: 14px; }

  /* 主内容区留白收紧 */
  .el-main { padding: 10px !important; }

  /* 分页在窄屏允许换行 */
  .el-pagination { flex-wrap: wrap; }
}
</style>
