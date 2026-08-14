<template>
  <div class="page">
    <header class="top"><button @click="$router.back()">‹</button><strong>分类浏览</strong><span></span></header>
    <!-- 标签切换栏 / Tab switcher -->
    <div class="tabs"><button v-for="item in tabs" :key="item.key" :class="{ on: tab === item.key }" @click="tab = item.key">{{ item.label }}</button></div>
    <main>
      <!-- 歌手模式 / Artists mode -->
      <template v-if="tab === 'artists'">
        <div class="gender-filter"><button v-for="item in genderOptions" :key="item.value" :class="{on:artistGender===item.value}" @click="artistGender=item.value">{{ item.label }}</button></div>
        <!-- 首字母索引 / Initial letter index -->
        <div class="letters"><button v-for="letter in availableLetters" :key="letter" :class="{ on: activeLetter === letter }" @click="activeLetter = letter">{{ letter }}</button></div>
        <div v-if="loading" class="tip">加载中…</div>
        <!-- 歌手列表 / Artist list -->
        <div v-else-if="filteredArtists.length" class="artist-list"><router-link v-for="artist in filteredArtists" :key="artist.name" :to="{ name:'artist', params:{ name:artist.name } }"><img v-if="artist.avatarUrl" :src="artist.avatarUrl" :alt="artist.name" class="avatar-img" @error="e => e.target.style.display='none'" /><span v-else class="avatar">{{ artist.name.slice(0,1) }}</span><span><strong>{{ artist.name }}</strong><small>{{ artist.songCount }} 首</small></span><em>›</em></router-link></div>
        <div v-else class="tip">暂无{{ activeGenderLabel }}</div>
      </template>
      <!-- 语种/分类模式 / Languages & tags mode -->
      <template v-else>
        <div v-if="loading" class="tip">加载中…</div>
        <!-- 卡片网格 / Card grid -->
        <div v-else class="cards"><router-link v-for="item in currentItems" :key="item.name" :to="songLink(item)"><span>{{ iconFor(item.name) }}</span><strong>{{ item.name }}</strong><small>{{ item.songCount }} 首</small></router-link></div>
      </template>
    </main>
    <TabBar active="home" />
  </div>
</template>

<script setup>
/**
 * 分类浏览页 — 按歌手首字母、语种或分类标签浏览歌曲库。
 *
 * Category browse page — browse the song library by artist initial,
 * language, or category tag.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api/client'
import TabBar from '../components/TabBar.vue'

const route = useRoute()
/** 顶部标签页：歌手 / 语种 / 分类。Top tabs: artists / languages / tags. */
const tabs = [{ key:'artists',label:'歌手' },{ key:'languages',label:'语种' },{ key:'tags',label:'分类' }]
const tab = ref(route.query.tab || 'artists'), artists = ref([]), languages = ref([]), tags = ref([]), activeLetter = ref('热门'), loading = ref(true)
const artistGender = ref('')
const genderOptions = [{value:'',label:'全部歌手'},{value:'男歌手',label:'男歌手'},{value:'女歌手',label:'女歌手'},{value:'组合',label:'组合'}]
onMounted(load); watch(tab, load)
watch(artistGender, () => { activeLetter.value = '热门' })

/**
 * 按当前标签页懒加载数据，每个分类只请求一次。
 *
 * Lazy-loads data for the active tab; each category is fetched at most once.
 */
async function load() {
  loading.value = true
  try {
    if (tab.value === 'artists' && !artists.value.length) artists.value = await api.browseArtists()
    if (tab.value === 'languages' && !languages.value.length) languages.value = await api.browseLanguages()
    if (tab.value === 'tags' && !tags.value.length) tags.value = await api.browseTags()
  } finally { loading.value = false }
}

/** 可选首字母列表，热门置顶。Available initials, with "热门" pinned first. */
const genderArtists = computed(() => artistGender.value ? artists.value.filter(item => item.gender === artistGender.value) : artists.value)
const activeGenderLabel = computed(() => genderOptions.find(item => item.value === artistGender.value)?.label || '歌手')
const availableLetters = computed(() => ['热门', ...[...new Set(genderArtists.value.map(item => item.initial))].sort()])

/** 按选中首字母过滤歌手，热门取前30。Filter artists by selected initial; "热门" returns top 30. */
const filteredArtists = computed(() => activeLetter.value === '热门' ? genderArtists.value.slice(0,30) : genderArtists.value.filter(item => item.initial === activeLetter.value))

/** 当前标签页对应的数据列表。Data list for the current tab. */
const currentItems = computed(() => tab.value === 'languages' ? languages.value : tags.value)

/**
 * 根据语种/标签生成歌曲列表路由链接。
 *
 * Builds a route link to the song list filtered by language or tag.
 * @param {{ name: string }} item
 * @returns {{ name: string, params: object, query: object }}
 */
function songLink(item) { return { name:'artist', params:{ name:'all' }, query:{ mode:tab.value === 'languages' ? 'language' : 'tag', value:item.name } } }

/**
 * 根据类别名称返回对应图标。
 *
 * Returns an emoji icon for the given category name.
 * @param {string} name
 * @returns {string}
 */
function iconFor(name) { if (/国语|粤语|英语|日语|韩语/.test(name)) return '🌏'; if (/儿歌|儿童/.test(name)) return '🧸'; if (/摇滚/.test(name)) return '🎸'; if (/情歌/.test(name)) return '💞'; return '🎶' }
</script>

<style scoped>
.page{min-height:100vh;padding-bottom:74px}.top{height:52px;display:flex;align-items:center;justify-content:space-between;padding:0 14px;border-bottom:1px solid var(--line)}.top button{border:0;background:none;color:var(--text);font-size:30px;width:35px}.top span{width:35px}.tabs{display:flex;padding:10px 16px 0;border-bottom:1px solid var(--line)}.tabs button{flex:1;border:0;background:none;color:var(--dim);padding:10px;border-bottom:2px solid transparent}.tabs button.on{color:var(--gold);border-color:var(--gold)}main{padding:13px 16px}.gender-filter,.letters{display:flex;gap:6px;overflow-x:auto;padding-bottom:10px}.gender-filter button,.letters button{flex:none;border:1px solid var(--glass-border);background:var(--panel2);color:var(--dim);border-radius:8px;padding:6px 10px}.gender-filter button.on,.letters button.on{color:var(--gold);border-color:rgba(240,199,66,.3);background:var(--gold-glow)}.artist-list{background:var(--panel2);border:1px solid var(--glass-border);border-radius:14px;padding:0 12px}.artist-list a{display:flex;align-items:center;gap:11px;padding:10px 0;border-bottom:1px solid var(--line);color:var(--text)}.artist-list a:last-child{border-bottom:0}.avatar,.avatar-img{width:40px;height:40px;display:grid;place-items:center;border-radius:50%;background:var(--gold-glow);color:var(--gold);font-weight:800;flex:none}
.avatar-img{object-fit:cover}.artist-list a>span:nth-child(2){display:flex;flex:1;flex-direction:column;gap:3px}.artist-list small{color:var(--dim2)}.artist-list em{font-style:normal;color:var(--dim2);font-size:22px}.cards{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}.cards a{display:flex;flex-direction:column;gap:6px;padding:16px;border:1px solid var(--glass-border);background:var(--panel2);border-radius:14px;color:var(--text)}.cards a>span{font-size:24px}.cards strong{font-size:14px}.cards small{color:var(--dim2)}.tip{text-align:center;color:var(--dim2);padding:50px}
</style>
