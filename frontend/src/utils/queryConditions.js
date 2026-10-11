/** 查询条件编辑的标量契约与展示；金额一直保留字符串，编辑结果仍交由服务端完整复核。 */
export const operatorLabels = { EQ: '等于', NE: '不等于', GT: '大于', GTE: '大于等于', LT: '小于', LTE: '小于等于', IN: '属于', NOT_IN: '不属于', CONTAINS: '包含', STARTS_WITH: '开头为', IS_NULL: '为空', NOT_NULL: '非空' }

export function operatorsFor(type) {
  const common = ['EQ', 'NE', 'IN', 'NOT_IN', 'IS_NULL', 'NOT_NULL']
  return type === 'string' ? [...common, 'CONTAINS', 'STARTS_WITH'] : type === 'boolean' ? common : ['integer', 'long', 'decimal', 'date'].includes(type) ? [...common, 'GT', 'GTE', 'LT', 'LTE'] : []
}

/** 编辑副本与原卡片分离，关闭弹窗不会改变已发布查询。 */
export function editableGroups(conditions) {
  return conditions.map(group => ({ allOf: group.allOf.map(filter => ({ field: filter.field, operator: filter.operator, text: filter.values.join('\n') })) }))
}

/** 只整理用户明确填写的值；不使用Number或自动丢弃空条件，防止大额和组合含义变化。 */
export function buildQueryEdit(queryRef, groups, columns) {
  if (!/^[a-f0-9]{32}$/.test(queryRef || '') || !Array.isArray(groups) || groups.length > 8) throw new Error('查询引用或条件组无效，请重新打开最新查询')
  const conditions = groups.map(group => {
    if (!group.allOf.length || group.allOf.length > 8) throw new Error('每组需要 1 至 8 个条件；删除整组表示不再使用该组')
    return { allOf: group.allOf.map(filter => {
      const field = columns.find(column => column.name === filter.field)
      if (!field || !operatorsFor(field.type).includes(filter.operator)) throw new Error('请选择有效字段与比较方式')
      const empty = ['IS_NULL', 'NOT_NULL'].includes(filter.operator)
      const values = empty ? [] : String(filter.text).split('\n').map(value => value.trim())
      if (!empty && (values.some(value => !value || value.length > 512) || values.length > 30 || (!['IN', 'NOT_IN'].includes(filter.operator) && values.length !== 1))) throw new Error('普通比较填写一个值；集合比较每行一个值，不能留空')
      return { field: filter.field, operator: filter.operator, values }
    }) }
  })
  return { queryRef, conditions }
}
