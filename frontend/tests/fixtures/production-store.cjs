/** 用与 Element UI 相同的 CommonJS 入口创建响应式 store，检查生产打包后的 Vue 实例边界。 */
const imported = require('vue')
const Vue = imported.default || imported

module.exports = {
  Vue,
  createStore() {
    return new Vue({ data: () => ({ columns: [], rows: [] }) })
  }
}
