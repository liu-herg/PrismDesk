<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'

const props = defineProps({ canEdit: { type: Boolean, default: false } })

const total = ref(null)
const statsError = ref('')
async function loadStats() {
  try {
    total.value = (await api.knowledgeStats()).total_chunks
    statsError.value = ''
  } catch (e) {
    statsError.value = e.message
  }
}
onMounted(loadStats)

// 检索
const query = ref('')
const results = ref(null)
const reranked = ref(false)
const searching = ref(false)
const searchError = ref('')
async function search() {
  if (!query.value.trim()) return
  searching.value = true
  searchError.value = ''
  try {
    const res = await api.search(query.value.trim(), 5)
    results.value = res.results || []
    reranked.value = Boolean(res.reranked)
  } catch (e) {
    searchError.value = e.message
  } finally {
    searching.value = false
  }
}

// 添加文档
const title = ref('')
const content = ref('')
const saving = ref(false)
const saveMsg = ref('')
const saveError = ref('')
async function addDoc() {
  saving.value = true
  saveMsg.value = ''
  saveError.value = ''
  try {
    const res = await api.addDocuments([{ title: title.value.trim(), content: content.value.trim() }])
    saveMsg.value = `已添加 ${res.added_chunks} 个片段，知识库现在共 ${res.total_chunks} 个`
    total.value = res.total_chunks
    title.value = ''
    content.value = ''
  } catch (e) {
    saveError.value = e.message
  } finally {
    saving.value = false
  }
}

// 上传文件
const uploading = ref(false)
const uploadMsg = ref('')
const uploadError = ref('')
async function onFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploading.value = true
  uploadMsg.value = ''
  uploadError.value = ''
  try {
    const res = await api.upload(file)
    uploadMsg.value = `${res.message}，新增 ${res.added_chunks} 个片段`
    total.value = res.total_chunks
  } catch (e) {
    uploadError.value = e.message
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}
</script>

<template>
  <div class="page">
    <div class="wrap">
      <div class="page-head">
        <div>
          <h1>知识库</h1>
          <p class="muted">客服回答时会先在这里检索，再交给模型生成回复。{{ canEdit ? '' : '添加和上传文档需要管理员账号。' }}</p>
        </div>
        <div style="text-align: right">
          <div class="big-number">{{ total ?? '—' }}</div>
          <div class="faint">个知识片段</div>
        </div>
      </div>
      <p v-if="statsError" class="notice" style="margin: 0 0 16px">{{ statsError }}</p>

      <div class="grid-2">
        <section class="card" style="margin-top: 0">
          <h2 style="margin-bottom: 12px">试一下检索</h2>
          <form class="composer-row" @submit.prevent="search">
            <input v-model="query" class="field" placeholder="比如：退款多久能到账" aria-label="检索内容" />
            <button class="btn btn-primary" :disabled="searching || !query.trim()">{{ searching ? '检索中…' : '检索' }}</button>
          </form>
          <p v-if="searchError" class="notice" style="margin: 12px 0 0">{{ searchError }}</p>
          <template v-if="results">
            <p class="faint" style="margin-top: 12px">
              找到 {{ results.length }} 条{{ reranked ? '，已由模型重新排序' : '' }}
            </p>
            <div v-if="!results.length" class="empty">没有相关内容，可以在右边补充一篇文档。</div>
            <div v-for="r in results" :key="r.id" class="result">
              <div style="display: flex; justify-content: space-between; gap: 8px">
                <strong>{{ r.title }}</strong>
                <span class="tag">相关度 {{ Number(r.score).toFixed(2) }}</span>
              </div>
              <p>{{ r.content }}</p>
            </div>
          </template>
        </section>

        <div v-if="canEdit">
          <section class="card" style="margin-top: 0">
            <h2 style="margin-bottom: 12px">添加文档</h2>
            <form @submit.prevent="addDoc" style="display: grid; gap: 10px">
              <label><span class="label">标题</span><input v-model="title" class="field" placeholder="比如：大促期间退款说明" /></label>
              <label>
                <span class="label">内容</span>
                <textarea v-model="content" class="field" rows="6" placeholder="大促期间退款审核时间可能延长到 3-5 个工作日。"></textarea>
              </label>
              <div><button class="btn btn-primary" :disabled="saving || !title.trim() || !content.trim()">添加到知识库</button></div>
            </form>
            <p v-if="saveMsg" class="notice notice-ok" style="margin: 10px 0 0">{{ saveMsg }}</p>
            <p v-if="saveError" class="notice" style="margin: 10px 0 0">{{ saveError }}</p>
          </section>

          <section class="card">
            <h2 style="margin-bottom: 6px">上传文件</h2>
            <p class="faint" style="margin-bottom: 10px">支持 .txt、.md，或者 JSON 数组 [{"title": "...", "content": "..."}]，最大 10MB。</p>
            <label class="btn" :aria-disabled="uploading">
              {{ uploading ? '上传中…' : '选择文件' }}
              <input type="file" accept=".txt,.md,.json" hidden :disabled="uploading" @change="onFile" />
            </label>
            <p v-if="uploadMsg" class="notice notice-ok" style="margin: 10px 0 0">{{ uploadMsg }}</p>
            <p v-if="uploadError" class="notice" style="margin: 10px 0 0">{{ uploadError }}</p>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>
