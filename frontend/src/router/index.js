/**
 * 业务页面路由与导航配置；页面可见性不能代替后端报表、公司和管理员权限检查。
 */
import Vue from 'vue'
import VueRouter from 'vue-router'

const SalesReport = () => import('../views/SalesReport.vue')
const ReceivableReport = () => import('../views/ReceivableReport.vue')
const ExpenseReport = () => import('../views/ExpenseReport.vue')
const RuleAdmin = () => import('../views/RuleAdmin.vue')
const CatalogAdmin = () => import('../views/CatalogAdmin.vue')
const OperationsAdmin = () => import('../views/OperationsAdmin.vue')
const MyTasks = () => import('../views/MyTasks.vue')
const SemanticQuality = () => import('../views/SemanticQuality.vue')
const QueryWorkspace = () => import('../views/QueryWorkspace.vue')

Vue.use(VueRouter)

const routes = [
  { path: '/query-workspace', name: 'query-workspace', component: QueryWorkspace, meta: { title: '查询工作区', ownsQueryState: true } },
  { path: '/semantic-quality', name: 'semantic-quality', component: SemanticQuality, meta: { title: '语义质量' } },
  { path: '/tasks', name: 'tasks', component: MyTasks, meta: { title: '我的任务' } },
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
