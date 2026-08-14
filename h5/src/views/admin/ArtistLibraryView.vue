<template>
  <AdminLayout active="artists">
    <header class="page-head">
      <div class="title-block">
        <div class="title-mark"><UsersRound :size="19" /></div>
        <div>
          <h1>歌手库</h1>
          <p>管理歌手头像与类型，同步刮削头像后自动更新。</p>
        </div>
      </div>
      <div class="head-actions">
        <button class="secondary action-btn" :disabled="loading" @click="load">
          <RefreshCw :size="15" :class="{spin: loading}" />刷新
        </button>
        <button class="secondary action-btn" :disabled="loading || noAvatarCount === 0" @click="startBackgroundScrape">
          <Download :size="15" :class="{spin: scrapeBgRunning}" />批量刮削头像
        </button>
        <button class="secondary action-btn" :disabled="loading" @click="syncArtists">
          <RefreshCw :size="15" :class="{spin: syncing}" />同步清理
        </button>
      </div>
    </header>

    <section class="stats-row">
      <article><span>歌手总数</span><strong>{{ total }}</strong><small>数据库中</small></article>
      <article><span>有头像</span><strong>{{ hasAvatarCount }}</strong><small>已刮削</small></article>
      <article>
        <span>无头像</span><strong>{{ noAvatarCount }}</strong>
        <small v-if="!scrapeBgRunning">待刮削</small>
        <small v-else class="scrape-tip">
          <RefreshCw :size="10" class="spin" />
          刮削中 {{ scrapeBgProgress.done }}/{{ scrapeBgProgress.total }}
        </small>
      </article>
    </section>

    <section class="filter-panel">
      <label class="keyword-field">
        <span>歌手名称</span>
        <input v-model.trim="filters.keyword" placeholder="搜索歌手" @keyup.enter="load" />
      </label>
      <label>
        <span>歌手类型</span>
        <span class="select-control">
          <select v-model="filters.gender" @change="load">
            <option value="">全部</option>
            <option value="男歌手">男歌手</option>
            <option value="女歌手">女歌手</option>
            <option value="组合">组合</option>
            <option value="未知">未知</option>
          </select>
          <ChevronDown :size="15" />
        </span>
      </label>
      <label>
        <span>头像状态</span>
        <span class="select-control">
          <select v-model="filters.avatar" @change="load">
            <option value="">全部</option>
            <option value="true">有头像</option>
            <option value="false">无头像</option>
          </select>
          <ChevronDown :size="15" />
        </span>
      </label>
      <div class="filter-actions">
        <button class="secondary" @click="reset">重置</button>
        <button class="primary" @click="load">查询</button>
      </div>
    </section>

    <section class="table-panel">
      <div class="toolbar">
        <div><strong>歌手列表</strong><span>共 {{ total }} 位</span></div>
        <small>点击「刮削头像」从平台下载；直接下拉编辑歌手类型。</small>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>头像</th>
              <th>歌手</th>
              <th>类型</th>
              <th>歌曲数</th>
              <th>代表作</th>
              <th class="action-cell">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="artist in artists" :key="artist.name">
              <td>
                <div class="avatar-cell">
                  <img v-if="artist.avatarUrl" :src="artist.avatarUrl" :alt="artist.name" class="artist-avatar" @error="$event.target.style.display='none'" />
                  <span v-else class="avatar-placeholder">{{ artist.name.slice(0,1) }}</span>
                </div>
              </td>
              <td><strong>{{ artist.name }}</strong></td>
              <td>
                <select class="gender-select" :value="artist.gender" @change="updateGender(artist.name, $event.target.value)">
                  <option value="男歌手">男歌手</option>
                  <option value="女歌手">女歌手</option>
                  <option value="组合">组合</option>
                  <option value="未知">未知</option>
                </select>
              </td>
              <td><strong class="count">{{ artist.songCount }}</strong><small>首歌曲</small></td>
              <td class="samples-cell">
                <span v-for="song in (artist.songs || [])" :key="song.id" class="sample-chip">{{ song.title }}</span>
                <span v-if="!artist.songs || !artist.songs.length" class="no-samples">—</span>
              </td>
              <td class="action-cell">
                <button class="link" @click="scrapeArtist(artist.name)" :disabled="scrapeLoading[artist.name]">
                  <Download :size="14" />
                  {{ scrapeLoading[artist.name] ? '刮削中…' : (artist.avatarUrl ? '重新刮削' : '刮削头像') }}
                </button>
              </td>
            </tr>
            <tr v-if="!artists.length">
              <td colspan="6" class="empty">暂无歌手数据</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager"><span>第 {{ page + 1 }} / {{ totalPages || 1 }} 页</span><div><button class="secondary" :disabled="page===0" @click="go(page-1)">上一页</button><button class="secondary" :disabled="page>=totalPages-1" @click="go(page+1)">下一页</button></div></div>
    </section>

  </AdminLayout>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ChevronDown, Download, RefreshCw, UsersRound } from 'lucide-vue-next'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog } from '../../composables/useDialog'

const artists = ref([]), loading = ref(false), total = ref(0), page = ref(0), totalPages = ref(1)
const stats = ref({ total: 0, hasAvatar: 0, noAvatar: 0 })
const filters = reactive({ keyword: '', gender: '', avatar: '' })
const scrapeLoading = ref({})
const syncing = ref(false)

// 后台刮削
const scrapeBgRunning = ref(false)
const scrapeBgProgress = ref({})
let scrapeBgTimer = null

const hasAvatarCount = computed(() => stats.value.hasAvatar)
const noAvatarCount = computed(() => stats.value.noAvatar)

onMounted(async () => {
  loadStats()
  load()
  // 页面刷新后，检查后端任务是否还在跑，如果是则恢复轮询
  try {
    const s = await api.adminScrapeAllStatus()
    if (s.running) {
      scrapeBgRunning.value = true
      scrapeBgProgress.value = s
      scrapeBgTimer = setInterval(pollScrapeStatus, 2000)
    }
  } catch {}
})
async function loadStats() {
  try {
    const r = await api.adminArtistStats()
    stats.value = r || { total: 0, hasAvatar: 0, noAvatar: 0 }
    total.value = r.total || 0
  } catch {}
}
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: 20 }
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.gender) params.gender = filters.gender
    if (filters.avatar) params.avatar = filters.avatar
    const r = await api.adminArtists(params)
    artists.value = r.content || []
    if (page.value === 0) {
      total.value = r.total || 0
      totalPages.value = r.totalPages || 1
    }
  } catch (e) {
    await alertDialog(e.message || '歌手列表加载失败')
  } finally { loading.value = false }
}
function reset() { Object.assign(filters, { keyword: '', gender: '', avatar: '' }); page.value = 0; loadStats(); load() }
function go(p) { if (p >= 0 && p < totalPages.value) { page.value = p; load() } }

async function updateGender(name, gender) {
  try {
    await api.adminUpdateArtist({ name, gender, avatarUrl: '' })
    const a = artists.value.find(x => x.name === name)
    if (a) a.gender = gender
  } catch (e) { await alertDialog(e.message || '保存失败') }
}

async function scrapeArtist(name) {
  scrapeLoading.value[name] = true
  try {
    const result = await api.adminScrapeArtist(name)
    const a = artists.value.find(x => x.name === name)
    if (a) { a.avatarUrl = result.avatarUrl; a.gender = result.gender }
  } catch (e) { await alertDialog(e.message || '刮削失败') }
  finally { scrapeLoading.value[name] = false }
}

async function startBackgroundScrape() {
  if (scrapeBgRunning.value) return
  try {
    await api.adminScrapeAllArtists()
    scrapeBgRunning.value = true
    scrapeBgProgress.value = { phase: 'SCANNING', done: 0, succeeded: 0 }
    scrapeBgTimer = setInterval(pollScrapeStatus, 2000)
  } catch (e) { await alertDialog(e.message || '启动刮削失败') }
}

async function pollScrapeStatus() {
  try {
    const s = await api.adminScrapeAllStatus()
    scrapeBgProgress.value = s
    if (!s.running) {
      clearInterval(scrapeBgTimer)
      scrapeBgTimer = null
      scrapeBgRunning.value = false
      await loadStats()
      await load()
      const msg = s.succeeded > 0 ? `刮削完成：成功 ${s.succeeded} / ${s.done} 位` : `刮削完成：共处理 ${s.done} 位`
      await alertDialog(msg)
    } else {
      // 刮削进行中，同步刷新统计让"无头像"数实时减少
      await loadStats()
    }
  } catch {
    clearInterval(scrapeBgTimer)
    scrapeBgTimer = null
    scrapeBgRunning.value = false
  }
}

onUnmounted(() => { if (scrapeBgTimer) clearInterval(scrapeBgTimer) })

async function syncArtists() {
  syncing.value = true
  try {
    const result = await api.adminSyncArtists()
    await alertDialog(`已清理 ${result.deleted || 0} 位不在曲库中的歌手`)
    await loadStats()
    await load()
  } catch (e) { await alertDialog(e.message || '同步失败') }
  finally { syncing.value = false }
}
</script>

<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px}
.title-block,.head-actions,.action-btn,.toolbar>div{display:flex;align-items:center}
.title-block{gap:11px}
.title-mark{display:grid;width:38px;height:38px;place-items:center;border:1px solid #bfdbfe;border-radius:8px;background:#eff6ff;color:#2563eb}
.page-head h1{font-size:22px;line-height:1.2}
.page-head p{margin-top:6px;color:#64748b;font-size:12px}
.head-actions{gap:8px}
.action-btn{justify-content:center;gap:6px}
.stats-row{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:16px}
.stats-row article{padding:12px 14px;border:1px solid #e2e8f0;border-radius:8px;background:#fff}
.stats-row span,.stats-row small{display:block;color:#64748b;font-size:11px}
.stats-row strong{display:block;margin:5px 0 2px;color:#172033;font-size:22px;line-height:1}
.stats-row small{color:#94a3b8;font-size:10px}
.stats-row .scrape-tip{display:flex;align-items:center;gap:3px;color:#2563eb;font-size:10px}
.filter-panel{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;padding:14px 16px;margin-bottom:16px;border:1px solid #e2e8f0;border-radius:8px;background:#fff}
.filter-panel label{display:flex;flex:0 0 150px;flex-direction:column;gap:6px;color:#64748b;font-size:11px}
.filter-panel .keyword-field{flex-basis:250px}
.filter-panel input,.filter-panel select{height:35px;width:100%;padding:0 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff;color:#172033;font:inherit;font-size:12px}
.filter-panel select{appearance:none;padding-right:30px}
.select-control{position:relative;display:block}
.select-control svg{position:absolute;right:9px;top:50%;color:#64748b;pointer-events:none;transform:translateY(-50%)}
.filter-actions{display:flex;gap:8px}
.table-panel{overflow:hidden;border:1px solid #e2e8f0;border-radius:8px;background:#fff}
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:13px 16px;border-bottom:1px solid #e2e8f0}
.toolbar>div{gap:10px}
.toolbar strong{color:#172033;font-size:13px}
.toolbar span,.toolbar small{color:#94a3b8;font-size:11px}
.table-scroll{overflow:auto}
table{width:100%;min-width:600px;border-collapse:separate;border-spacing:0}
th,td{padding:11px 12px;border-bottom:1px solid #eef2f7;text-align:left;white-space:nowrap}
th{background:#f8fafc;color:#64748b;font-size:11px}
td{color:#334155;font-size:12px}
td strong,td small{display:block}
td small{margin-top:4px;color:#94a3b8;font-size:10px}
.count{font-size:14px}
.action-cell{position:sticky;right:0;z-index:2;width:140px;min-width:140px;background:#fff;border-left:1px solid #e2e8f0;box-shadow:-10px 0 14px -14px rgba(15,23,42,.55)}
th.action-cell{z-index:3;background:#f8fafc}
.samples-cell{max-width:300px}
.samples-cell .sample-chip{display:inline-block;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:2px 6px;margin:2px 3px 2px 0;background:#f1f5f9;border-radius:4px;font-size:10px;color:#334155}
.samples-cell .no-samples{color:#94a3b8;font-size:11px}
.gender-select{height:30px;padding:0 8px;border:1px solid #cbd5e1;border-radius:5px;background:#fff;font-size:11px;color:#334155}
.avatar-cell{width:42px;height:42px;border-radius:50%;overflow:hidden}
.artist-avatar{width:100%;height:100%;object-fit:cover;border-radius:50%}
.avatar-placeholder{width:42px;height:42px;display:grid;place-items:center;border-radius:50%;background:var(--gold-glow,#fef3c7);color:var(--gold,#d97706);font-weight:700;font-size:16px}
.link,.primary,.secondary{display:inline-flex;align-items:center;justify-content:center;min-height:34px;padding:0 11px;border-radius:6px;font-size:11px;font-weight:600;gap:5px}
.link{border:1px solid #dbe3ee;background:#fff;color:#2563eb}
.primary{border:1px solid #2563eb;background:#2563eb;color:#fff}
.secondary{border:1px solid #cbd5e1;background:#fff;color:#475569}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:760px){.page-head{align-items:flex-start;flex-direction:column}.head-actions{width:100%;flex-wrap:wrap}.filter-panel label,.filter-panel .keyword-field{flex:1 1 140px}.filter-actions{width:100%}.filter-actions>*{flex:1}}
</style>
