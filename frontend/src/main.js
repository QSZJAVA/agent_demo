import Vue from 'vue'
import Alert from 'element-ui/lib/alert'
import Aside from 'element-ui/lib/aside'
import Button from 'element-ui/lib/button'
import Card from 'element-ui/lib/card'
import Checkbox from 'element-ui/lib/checkbox'
import CheckboxGroup from 'element-ui/lib/checkbox-group'
import Collapse from 'element-ui/lib/collapse'
import CollapseItem from 'element-ui/lib/collapse-item'
import Container from 'element-ui/lib/container'
import Dialog from 'element-ui/lib/dialog'
import Drawer from 'element-ui/lib/drawer'
import Form from 'element-ui/lib/form'
import FormItem from 'element-ui/lib/form-item'
import Header from 'element-ui/lib/header'
import Input from 'element-ui/lib/input'
import InputNumber from 'element-ui/lib/input-number'
import Loading from 'element-ui/lib/loading'
import Main from 'element-ui/lib/main'
import Menu from 'element-ui/lib/menu'
import MenuItem from 'element-ui/lib/menu-item'
import Message from 'element-ui/lib/message'
import MessageBox from 'element-ui/lib/message-box'
import Option from 'element-ui/lib/option'
import Pagination from 'element-ui/lib/pagination'
import Select from 'element-ui/lib/select'
import Switch from 'element-ui/lib/switch'
import TabPane from 'element-ui/lib/tab-pane'
import Table from 'element-ui/lib/table'
import TableColumn from 'element-ui/lib/table-column'
import Tabs from 'element-ui/lib/tabs'
import Tag from 'element-ui/lib/tag'
import 'element-ui/lib/theme-chalk/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

const components = [
  Alert, Aside, Button, Card, Checkbox, CheckboxGroup, Collapse, CollapseItem,
  Container, Dialog, Drawer, Form, FormItem, Header, Input, InputNumber, Main,
  Menu, MenuItem, Option, Pagination, Select, Switch, TabPane, Table, TableColumn,
  Tabs, Tag
]
components.forEach((component) => Vue.use(component))
Vue.use(Loading.directive)
Vue.prototype.$ELEMENT = { size: 'small' }
Vue.prototype.$message = Message
Vue.prototype.$confirm = MessageBox.confirm
Vue.prototype.$prompt = MessageBox.prompt
Vue.config.productionTip = false

new Vue({
  router,
  render: (h) => h(App)
}).$mount('#app')
