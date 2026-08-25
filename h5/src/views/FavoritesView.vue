<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>我的收藏</strong><span>{{ songs.length }} 首</span></header>
    <main>
      <!-- 收藏页头部 / Favorites header -->
      <div class="intro"><span>❤️</span><div><h1>喜欢的歌</h1><p>收藏保存在当前手机身份下</p></div></div>
      <div v-if="loading" class="tip">加载中…</div>
      <div v-else-if="!songs.length" class="empty"><span>♡</span><strong>还没有收藏歌曲</strong><p>在歌曲右侧点爱心即可收藏</p></div>
      <!-- 歌曲列表（含点歌按钮） / Song list with queuing -->
      <div v-else class="list">
        <SongRow v-for="song in songs" :key="song.id" :song="song" :ordered="orderedIds.has(song.id)" @order="order" />
      </div>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 收藏页面 — 展示用户收藏的歌曲列表，支持点歌操作。
 * 收藏数据与当前手机身份绑定，切换身份后数据不同。
 *
 * Favorites page — displays the user's favorited songs and supports queuing.
 * Favorites are tied to the current device identity and differ across identities.
 */

import { onMounted, reactive, ref, watch } from 'vue'
import api, { makeControls } from '../api/client'
import SongRow from '../components/SongRow.vue'
import TabBar from '../components/TabBar.vue'
import { useFavoritesStore } from '../stores/favorites'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useToast } from '../composables/useToast'

const user = useUserStore()
const player = usePlayerStore()
const favorites = useFavoritesStore()
const { toast } = useToast()
const controls = makeControls(user.clientToken)
const songs = ref([])
const loading = ref(true)
const orderedIds = reactive(new Set())

onMounted(load)
watch(() => favorites.ids.slice(), ids => {
  songs.value = songs.value.filter(song => ids.includes(song.id))
})

/**
 * 加载收藏歌曲列表，同时同步收藏 ID 到 store。
 * 失败时清空列表，避免展示过期数据。
 *
 * Loads the favorites song list and syncs favorite IDs to the store.
 * On failure, clears the list to avoid showing stale data.
 */
async function load() {
  loading.value = true
  try {
    songs.value = await api.favorites(user.clientToken)
    await favorites.load(user.clientToken, true)
  } catch {
    songs.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 将歌曲加入播放队列（点歌）。
 * 成功后将 song.id 标记为已点，避免重复操作。
 *
 * @param {Object} song - 歌曲对象，需包含 id 属性。
 *
 * Queues a song for playback.
 * On success, marks the song id as ordered to prevent duplicate operations.
 *
 * @param {Object} song - Song object, must contain an id property.
 */
async function order(song) {
  if (!player.tvOnline) { toast('电视离线中，无法点歌'); return }
  try {
    await controls.order(song.id)
    orderedIds.add(song.id)
    toast('已加入队列')
  } catch (error) {
    toast(error.code === 'SONG_IN_QUEUE' ? (error.message || '已在队列中') : (error.message || '点歌失败'))
  }
}
</script>

<style scoped>
.page { min-height: 100vh; padding-bottom: 74px; }
.top { height: 54px; padding: 0 16px; display: grid; grid-template-columns: 44px 1fr 44px; align-items: center; border-bottom: 1px solid var(--line); }
.top button { text-align: left; font-size: 28px; color: var(--dim); }
.top strong { text-align: center; }
.top span { color: var(--dim2); font-size: 12px; white-space: nowrap; }
main { padding: 16px; }
.intro { display: flex; gap: 14px; align-items: center; padding: 18px; margin-bottom: 10px; border-radius: var(--radius); background: linear-gradient(135deg, rgba(248,113,113,.16), var(--panel)); border: 1px solid rgba(248,113,113,.18); }
.intro > span { font-size: 34px; }
.intro h1 { font-size: 20px; }
.intro p, .empty p { color: var(--dim); font-size: 13px; margin-top: 4px; }
.tip, .empty { color: var(--dim2); text-align: center; padding: 48px 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 7px; }
.empty > span { color: #f87171; font-size: 48px; }
.empty strong { color: var(--text); }
</style>
