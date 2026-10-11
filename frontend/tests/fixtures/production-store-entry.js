/** 生产响应性探针：ESM 页面读取 CommonJS 组件 store；不访问业务接口，不代替浏览器验收。 */
import Vue from 'vue'
import storeModule from './production-store.cjs'

export const sameRuntime = Vue === storeModule.Vue

export async function observeStoreChanges() {
  const store = storeModule.createStore()
  const table = new Vue({
    computed: {
      columnCount() { return store.columns.length },
      rowCount() { return store.rows.length }
    }
  })
  try {
    const before = { columns: table.columnCount, rows: table.rowCount }
    store.columns.push({ property: 'amount' })
    store.rows.push({ amount: '123.45' })
    await Vue.nextTick()
    return { before, after: { columns: table.columnCount, rows: table.rowCount } }
  } finally {
    table.$destroy()
    store.$destroy()
  }
}
