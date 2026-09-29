<template>
  <div class="app-frame">
  <div v-if="!authReady" class="login-page">正在连接服务…</div>
  <div v-else-if="loginRequired && !authenticated" class="login-page">
    <el-card class="login-card">
      <h2>报表与派单工作台</h2>
      <p>请使用已分配的账号登录</p>
      <form @submit.prevent="login">
        <label for="login-user">账号</label>
        <el-input id="login-user" v-model="loginUser" autocomplete="username" :maxlength="64" />
        <label for="login-password">密码</label>
        <el-input id="login-password" v-model="loginPassword" type="password" autocomplete="current-password" show-password :maxlength="256" />
        <p v-if="loginError" role="alert" class="login-error">{{ loginError }}</p>
        <el-button native-type="submit" type="primary" :loading="loginBusy">登录</el-button>
      </form>
    </el-card>
  </div>
  <el-container v-else class="app-layout">
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
        <el-menu-item v-if="isAdmin" index="/catalog"><i class="el-icon-folder-opened"></i><span slot="title">报表目录</span></el-menu-item>
        <el-menu-item v-if="isAdmin" index="/operations"><i class="el-icon-monitor"></i><span slot="title">运营治理</span></el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <span class="title">{{ currentTitle }}</span>
        <div class="right">
          <el-select v-if="!loginRequired" v-model="currentUserId" size="small" class="user-select" @change="switchUser">
            <el-option v-for="u in users" :key="u.userId" :label="u.displayName" :value="u.userId" />
          </el-select>
          <template v-else><span>{{ users[0] && users[0].displayName }}</span><el-button size="small" @click="logout">退出登录</el-button></template>
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
  </div>
</template>

<script>
import AgentChat from './components/agent/AgentChat.vue'
import { fetchUsers } from './api/agent'
import { fetchCatalog } from './api/catalog'
import { getCurrentUserId, setCurrentUserId, getSessionToken, saveSession, clearSession } from './auth'
import http from './api/http'

export default {
  name: 'App',
  components: { AgentChat },
  data() {
    return {
      authReady: false, loginRequired: true, authenticated: false,
      loginUser: '', loginPassword: '', loginError: '', loginBusy: false,
      users: [],
      currentUserId: getCurrentUserId(),
      chatVisible: false,
      refreshSeq: 0,
      // 当前用户有权限的报表编码（来自报表目录）；null 表示尚未加载，先全部显示
      visibleCodes: null
    }
  },
  computed: {
    isAdmin() {
      return this.users.some(u => u.userId === this.currentUserId && u.admin)
    },
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
    window.addEventListener('session-expired', this.onSessionExpired)
    try {
      this.loginRequired = (await http.get('/auth/mode')).loginRequired
      if (this.loginRequired && getSessionToken()) {
        const user = await http.get('/auth/me')
        this.currentUserId = user.userId
        setCurrentUserId(user.userId)
        this.authenticated = true
      }
      if (!this.loginRequired || this.authenticated) await this.loadWorkspace()
    } catch (e) {
      this.loginError = '无法恢复登录，请重新登录或检查服务连接'
    } finally { this.authReady = true }
  },
  beforeDestroy() { window.removeEventListener('session-expired', this.onSessionExpired) },
  methods: {
    async loadWorkspace() { this.users = await fetchUsers(); await this.loadCatalog() },
    async login() {
      this.loginBusy = true; this.loginError = ''
      try {
        const result = await http.post('/auth/login', { userId: this.loginUser, password: this.loginPassword })
        saveSession(result.token, result.user)
        this.currentUserId = result.user.userId; this.loginPassword = ''; this.authenticated = true
        await this.loadWorkspace()
      } catch (e) { this.loginError = e.message || '登录失败' }
      finally { this.loginBusy = false }
    },
    async logout() { try { await http.post('/auth/logout') } finally { this.onSessionExpired() } },
    onSessionExpired() { clearSession(); this.authenticated = false; this.chatVisible = false; this.users = []; this.visibleCodes = [] },
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
.app-frame { height: 100%; }
.login-page { min-height: 100%; display: flex; justify-content: center; align-items: center; background: #f3f6fa; }
.login-card { width: 360px; max-width: calc(100vw - 40px); }
.login-card h2 { margin-top: 0; }
.login-card label { display: block; margin: 18px 0 8px; }
.login-card button { margin-top: 22px; width: 100%; }
.login-error { color: #c23030; }
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
  white-space: nowrap;
  flex-shrink: 0;
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
@media (max-width: 900px) {
  .app-aside { width: 140px !important; }
  .app-header { flex-wrap: wrap; height: auto !important; min-height: 80px; padding: 10px 16px; gap: 8px; }
  .app-header .title { width: 100%; }
  .app-header .right { flex-wrap: wrap; }
  .user-select { width: 190px; }
}
</style>
