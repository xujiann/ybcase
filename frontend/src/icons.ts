/**
 * 按需注册图标。
 *
 * 原先 `import * as Icons` 再全量 app.component()，把整个图标库（370 个 svg）打进入口 chunk。
 * 菜单图标名存在数据库（sys_menu.icon），模板里用 <component :is="名字"> 动态解析，
 * 所以不能只靠静态引用摇树——这里显式列出"数据库种子在用的 + 模板直接用的"，
 * 未列出的图标名会回落到 Menu（不会渲染成空白或报错）。
 *
 * 新增菜单若要用新图标，在此补一行即可。
 */
import {
  AlarmClock, ArrowDown, Bell, Calendar, ChatDotRound, Collection, Document, Files, Folder,
  HomeFilled, List, Lock, Menu, Notebook, OfficeBuilding, Postcard, Present, Promotion,
  Search, Setting, Stamp, Tools, TrendCharts, User, View,
} from '@element-plus/icons-vue'
import type { App, Component } from 'vue'

const ICONS: Record<string, Component> = {
  AlarmClock, ArrowDown, Bell, Calendar, ChatDotRound, Collection, Document, Files, Folder,
  HomeFilled, List, Lock, Menu, Notebook, OfficeBuilding, Postcard, Present, Promotion,
  Search, Setting, Stamp, Tools, TrendCharts, User, View,
}

export function registerIcons(app: App) {
  for (const [name, comp] of Object.entries(ICONS)) app.component(name, comp)
  // 数据库里配了未注册的图标名时，渲染成通用图标而不是空白/告警
  app.component('IconFallback', Menu)
}

/** 菜单图标解析：未注册的名字回落 Menu */
export function iconOf(name?: string | null) {
  return (name && ICONS[name]) ? name : 'Menu'
}
