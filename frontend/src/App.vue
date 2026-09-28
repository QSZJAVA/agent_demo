<template>
  <el-container class="app-layout">
    <el-aside width="210px" class="app-aside">
      <div class="logo">报表 Demo</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-menu-item v-if="canSee('sales')" index="/sales">
          <i class="el-icon-s-data"></i>
          <span slot="title">销售报表</span>
        </el-menu-item>
        <el-menu-item v-if="canSee('receivable')" index="/receivable">
          <i class="el-icon-money"></i>
          <span slot="title">应收报表</span>
        </el-menu-item>
        <el-menu-item v-if="canSee('expense')" index="/expense">
          <i class="el-icon-wallet"></i>
          <span slot="title">费用报表</span>
        </el-menu-item>
        <el-menu-item index="/rules">
          <i class="el-icon-setting"></i>
          <span slot="title">派单规则</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <span class="title">{{ currentTitle }}</span>
        <div class="right">
          <el-select v-model="currentUserId" size="small" class="user-select" @change="switchUser">
            <el-option v-for="u in users" :key="u.userId" :label="u.displayName" :value="u.userId" />
          </el-select>
          <el-button type="primary" size="small" icon="el-icon-chat-dot-round" @click="chatVisible = true">
            派单助手
          </el-button>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view :key="routeKey" />
      </el-main>
    </el-container>

    <agent-chat :key="currentUserId" :visible.sync="chatVisible" @dispatched="onDispatched" />
  </el-container>
</template>

<script>
import AgentChat from './components/agent/AgentChat.vue'
import { fetchUsers } from './api/agent'
import { fetchCatalog } from './api/catalog'
import { getCurrentUserId, setCurrentUserId } from './auth'

export default {
  name: 'App',
  components: { AgentChat },
  data() {
    return {
      users: [],
      currentUserId: getCurrentUserId(),
      chatVisible: false,
      refreshSeq: 0,
      // 当前用户有权限的报表编码（来自报表目录）；null 表示尚未加载，先全部显示
      visibleCodes: null
    }
  },
  computed: {
    activeMenu() {
      return this.$route.path
    },
    currentTitle() {
      return (this.$route.meta && this.$route.meta.title) || '报表查询'
    },
    routeKey() {
      // 切换用户或派单完成后强制刷新当前页面数据
      return `${this.$route.fullPath}-${this.currentUserId}-${this.refreshSeq}`
    }
  },
  async created() {
    this.loadCatalog()
    try {
      this.users = await fetchUsers()
    } catch (e) {
      this.users = [{ userId: this.currentUserId, displayName: this.currentUserId }]
    }
  },
  methods: {
    /** 报表菜单按报表目录的权限显示：没有权限的报表不出现在菜单里 */
    async loadCatalog() {
      try {
        this.visibleCodes = (await fetchCatalog()).map((r) => r.reportCode)
      } catch (e) {
        this.visibleCodes = null
      }
    },
    canSee(reportCode) {
      return !this.visibleCodes || this.visibleCodes.includes(reportCode)
    },
    switchUser(userId) {
      setCurrentUserId(userId)
      this.chatVisible = false
      this.refreshSeq++
      this.loadCatalog()
    },
    onDispatched() {
      this.refreshSeq++
    }
  }
}
</script>

<style scoped>
.app-layout {
  height: 100%;
}

.app-aside {
  background-color: #304156;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 2px;
  background-color: #263445;
}

.app-aside >>> .el-menu {
  border-right: none;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e6e6e6;
}

.app-header .title {
  font-size: 16px;
  font-weight: 600;
}

.app-header .right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-header .env {
  font-size: 12px;
  color: #909399;
}

.user-select {
  width: 230px;
}

.app-main {
  padding: 16px;
}
</style>
