import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../views/MainLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('../views/case/StatsView.vue') },
        { path: 'case/clues', component: () => import('../views/case/CluesView.vue') },
        { path: 'case/list', component: () => import('../views/case/CasesView.vue') },
        { path: 'case/detail/:id', component: () => import('../views/case/CaseDetailView.vue') },
        { path: 'case/causes', component: () => import('../views/case/CausesView.vue') },
        { path: 'case/enforce-items', component: () => import('../views/case/EnforceItemsView.vue') },
        { path: 'case/transfers', component: () => import('../views/case/TransfersView.vue') },
        { path: 'case/inspections', component: () => import('../views/case/InspectionsView.vue') },
        { path: 'case/rewards', component: () => import('../views/case/RewardsView.vue') },
        { path: 'case/delegates', component: () => import('../views/case/DelegatesView.vue') },
        { path: 'case/approvals', component: () => import('../views/case/ApprovalsView.vue') },
        { path: 'system/templates', component: () => import('../views/system/TemplatesView.vue') },
        { path: 'system/enforcers', component: () => import('../views/system/EnforcersView.vue') },
        { path: 'system/holidays', component: () => import('../views/system/HolidaysView.vue') },
        { path: 'case/supervise', component: () => import('../views/case/SupervisionView.vue') },
        { path: 'case/stats', component: () => import('../views/case/StatsView.vue') },
        { path: 'system/users', component: () => import('../views/system/UsersView.vue') },
        { path: 'system/settings', component: () => import('../views/system/SettingsView.vue') },
        { path: 'system/depts', component: () => import('../views/system/DeptsView.vue') },
        { path: 'system/roles', component: () => import('../views/system/RolesView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('bureau_token')
  if (!token && to.path !== '/login') return '/login'
  if (token && to.path === '/login') return '/'
})

export default router
