import Vue from 'vue'
import VueRouter from 'vue-router'

const SalesReport = () => import('../views/SalesReport.vue')
const ReceivableReport = () => import('../views/ReceivableReport.vue')
const ExpenseReport = () => import('../views/ExpenseReport.vue')
const RuleAdmin = () => import('../views/RuleAdmin.vue')
const CatalogAdmin = () => import('../views/CatalogAdmin.vue')
const OperationsAdmin = () => import('../views/OperationsAdmin.vue')

Vue.use(VueRouter)

const routes = [
  { path: '/catalog', name: 'catalog', component: CatalogAdmin, meta: { title: '报表目录管理' } },
  { path: '/operations', name: 'operations', component: OperationsAdmin, meta: { title: '运营治理' } },
  { path: '/', redirect: '/sales' },
  { path: '/sales', name: 'sales', component: SalesReport, meta: { title: '销售报表' } },
  { path: '/receivable', name: 'receivable', component: ReceivableReport, meta: { title: '应收报表' } },
  { path: '/expense', name: 'expense', component: ExpenseReport, meta: { title: '费用报表' } },
  { path: '/rules', name: 'rules', component: RuleAdmin, meta: { title: '派单规则管理' } }
]

export default new VueRouter({
  mode: 'hash',
  routes
})
