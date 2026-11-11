<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'
import { session } from '../session.js'
import { ROLE_LABELS, timeLabel } from '../format.js'

const users = ref([])
const error = ref('')
async function load() {
  try {
    users.value = await api.users()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}
onMounted(load)

const form = ref({ username: '', display_name: '', role: 'AGENT', password: '' })
const saving = ref(false)
const formError = ref('')
const formDone = ref('')
async function create() {
  saving.value = true
  formError.value = ''
  formDone.value = ''
  try {
    const u = await api.createUser({ ...form.value, username: form.value.username.trim() })
    formDone.value = `已创建${ROLE_LABELS[u.role]}账号 ${u.username}`
    form.value = { username: '', display_name: '', role: form.value.role, password: '' }
    load()
  } catch (e) {
    formError.value = e.message
  } finally {
    saving.value = false
  }
}

const rowError = ref('')
async function toggle(u) {
  rowError.value = ''
  try {
    await api.setUserEnabled(u.id, !u.enabled)
    load()
  } catch (e) {
    rowError.value = e.message
  }
}
</script>

<template>
  <div class="page">
    <div class="wrap">
      <div class="page-head">
        <div>
          <h1>账号</h1>
          <p class="muted">客户可以自己注册；坐席和管理员在这里创建。停用后，这个账号已经登录的设备会立刻掉线。</p>
        </div>
      </div>
      <p v-if="error" class="notice" style="margin: 0 0 16px">{{ error }}</p>

      <div class="grid-2">
        <section class="card" style="margin-top: 0">
          <div style="overflow-x: auto">
            <table class="table">
              <thead>
                <tr>
                  <th>账号</th>
                  <th>角色</th>
                  <th>最近登录</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="u in users" :key="u.id">
                  <td>
                    <strong>{{ u.display_name }}</strong>
                    <div class="faint">{{ u.username }}</div>
                  </td>
                  <td>{{ ROLE_LABELS[u.role] || u.role }}</td>
                  <td class="muted">{{ u.last_login_at ? timeLabel(u.last_login_at) : '从未登录' }}</td>
                  <td>
                    <button
                      class="btn btn-small"
                      :class="{ 'btn-danger': u.enabled }"
                      :disabled="u.username === session.user.username"
                      :title="u.username === session.user.username ? '不能停用自己' : ''"
                      @click="toggle(u)"
                    >{{ u.enabled ? '停用' : '启用' }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="rowError" class="notice" style="margin: 10px 0 0">{{ rowError }}</p>
        </section>

        <section class="card" style="margin-top: 0">
          <h2 style="margin-bottom: 12px">新建账号</h2>
          <form style="display: grid; gap: 10px" @submit.prevent="create">
            <label><span class="label">用户名</span><input v-model="form.username" class="field" placeholder="比如 agent-03" required /></label>
            <label><span class="label">显示名</span><input v-model="form.display_name" class="field" placeholder="比如 坐席小陈" /></label>
            <label>
              <span class="label">角色</span>
              <select v-model="form.role" class="field">
                <option value="AGENT">坐席</option>
                <option value="ADMIN">管理员</option>
                <option value="CUSTOMER">客户</option>
              </select>
            </label>
            <label><span class="label">初始密码</span><input v-model="form.password" class="field" type="password" autocomplete="new-password" required /></label>
            <div><button class="btn btn-primary" :disabled="saving">创建账号</button></div>
          </form>
          <p v-if="formDone" class="notice notice-ok" style="margin: 10px 0 0">{{ formDone }}</p>
          <p v-if="formError" class="notice" style="margin: 10px 0 0">{{ formError }}</p>
        </section>
      </div>
    </div>
  </div>
</template>
