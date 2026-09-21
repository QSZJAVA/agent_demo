import Vue from 'vue'
import VueRouter from 'vue-router'

import SalesReport from '../views/SalesReport.vue'
import ReceivableReport from '../views/ReceivableReport.vue'
import ExpenseReport from '../views/ExpenseReport.vue'
import RuleAdmin from '../views/RuleAdmin.vue'

Vue.use(VueRouter)

const routes = [
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
