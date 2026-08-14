<template>
  <AdminLayout active="dashboard">
    <header class="page-head"><div><h1>仪表盘</h1><p>扫描源路径并查看曲库、转码与播放服务状态</p></div><button class="primary" :disabled="scanning" @click="scan">{{ scanning ? '扫描中…' : '扫描源路径' }}</button></header>
    <!-- 统计卡片 / Stats cards -->
    <section class="stats"><article><span>原始素材</span><strong>{{ sourceTotal }}</strong><small>/source-music</small></article><article><span>KTV曲库</span><strong>{{ d.totalSongs ?? 0 }}</strong><small>/music，可点歌</small></article><article><span>待转码</span><strong>{{ pendingCount }}</strong><small>等待批量转码入库</small></article><article><span>未识别</span><strong>{{ d.unrecognizedCount ?? 0 }}</strong><small>需补录元数据</small></article></section>
    <!-- 扫描进度 / Scan progress -->
    <section v-if="scanning || scanResult" class="scan-progress" :class="{complete:!scanning}">
      <div class="progress-head"><div><strong>{{ scanning ? '正在扫描源路径' : '扫描完成' }}</strong><span v-if="scanning">{{ scanProgress.currentFile || '正在读取文件列表…' }}</span><span v-else>{{ scanResult.finishedAt ? `完成于 ${formatTime(scanResult.finishedAt)}` : '' }}</span></div><b>{{ scanPercent }}%</b></div>
      <div class="track"><i :style="{width:`${scanPercent}%`}"></i></div>
      <div class="progress-meta"><span>已处理 {{ scanProgress.completed || 0 }} / {{ scanProgress.total || 0 }}</span><span>直接移动 {{ scanProgress.copied || 0 }}</span><span>待转码 {{ scanProgress.pendingTranscode || 0 }}</span><span>重复 {{ duplicateCount }}</span><span>未识别 {{ scanProgress.unrecognized || 0 }}</span><span :class="{'failed':scanProgress.failed}">失败 {{ scanProgress.failed || 0 }}</span></div>
    </section>
    <!-- 刮削进度 / Scrape progress -->
    <section v-if="scrapeTask" class="scrape-progress" :class="{complete:scrapeTask.status==='COMPLETED'}" @click="goScrape" role="button" tabindex="0" @keydown.enter="goScrape">
      <div class="progress-head"><div><strong>{{ scrapeTask.status === 'COMPLETED' ? '刮削完成' : '正在刮削元数据' }}</strong><span>{{ scrapeTask.mode === 'REVIEW' ? '待审核模式' : '自动写入模式' }} · 阈值 {{ formatScrapeThreshold(scrapeTask.autoApplyThreshold) }}</span></div><b>{{ scrapePercent }}%</b></div>
      <div class="track"><i :style="{width:`${scrapePercent}%`}"></i></div>
      <div class="progress-meta">
        <span>已处理 {{ scrapeTask.completed || 0 }} / {{ scrapeTask.total || 0 }}</span>
        <span>自动写入 {{ scrapeTask.autoApplied || 0 }}</span>
        <span>待审核 {{ scrapeTask.review || 0 }}</span>
        <span>失败 {{ scrapeTask.failed || 0 }}</span>
        <span v-if="scrapeTask.skippedExisting">已跳过 {{ scrapeTask.skippedExisting }}</span>
      </div>
      <!-- <div class="scrape-click-hint">点击查看详情 →</div> -->
    </section>
    <!-- 运行状态面板 / Status panel -->
    <section class="panel"><div class="panel-head"><strong>运行状态</strong><button class="text-btn" @click="load">刷新</button></div><table><thead><tr><th>模块</th><th>当前状态</th><th>详情</th><th>操作</th></tr></thead><tbody>
      <tr><td><strong>源路径扫描</strong><small>分析、去重、兼容文件直接移动入库</small></td><td><span class="status green">{{ scanning ? '扫描中' : '就绪' }}</span></td><td>兼容文件扫描后移入 KTV 曲库；需转码文件只进入待处理列表。</td><td><button class="link" @click="scan" :disabled="scanning">重新扫描</button></td></tr>
      <tr><td><strong>元数据刮削</strong><small>从平台补充歌曲元数据</small></td><td><span class="status" :class="scrapeTask ? (scrapeTask.status==='RUNNING'?'blue':scrapeTask.status==='COMPLETED'?'green':'neutral') : 'neutral'">{{ scrapeTask ? scrapeStatusText : '空闲' }}</span></td><td>{{ scrapeTask ? `批次 ${scrapeTask.total || 0} 首，已处理 ${scrapeTask.completed || 0} 首` : '暂无刮削任务' }}</td><td><router-link class="link" :to="{name:'admin-metadata-scrape'}">前往刮削</router-link></td></tr>
      <tr><td><strong>批量转码</strong><small>原始音乐管理任务</small></td><td><span class="status" :class="progress.running?'blue':'neutral'">{{ progress.running ? '进行中' : '空闲' }}</span></td><td>{{ progress.running ? `${progress.completed}/${progress.total}，当前：${progress.currentFile || '准备中'}` : lastProgressText }}</td><td><router-link class="link" :to="{name:'admin-source-library'}">查看进度</router-link></td></tr>
      <tr><td><strong>播放服务</strong><small>TV 与手机点歌</small></td><td><span class="status green">{{ queueState }}</span></td><td>当前连接 {{ d.connectedClients ?? 0 }} 台客户端，正式曲库 KTV {{ d.ktvCount||0 }} / MV {{ d.mvCount||0 }} / 音频 {{ d.audioCount||0 }}。</td><td><router-link class="link" :to="{name:'admin-ktv-library'}">管理曲库</router-link></td></tr>
    </tbody></table></section>
  </AdminLayout>
</template>
<script setup>
/**
 * 管理后台仪表盘页面 —— 展示源路径扫描、曲库统计、批量转码与播放服务状态。
 *
 * Admin dashboard page — displays source scan status, song library stats,
 * batch transcoding progress, and playback service status.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../../api/client'
import AdminLayout from './AdminLayout.vue'
import { alertDialog } from '../../composables/useDialog'
const d=ref({}),queue=ref({}),progress=ref({}),scanning=ref(false),scanResult=ref(null),scanProgress=ref({}),sourceTotal=ref(0),pendingCount=ref(0)
const scrapeTask=ref(null)
const router=useRouter()
let scanTimer=null
/**
 * 播放队列当前状态的中文映射。
 *
 * Chinese label for the current playback queue state.
 */
const queueState=computed(()=>({playing:'播放中',paused:'已暂停',idle:'空闲'}[queue.value.state]||'空闲'))
/**
 * 上次批量转码完成时的文本摘要。
 *
 * Text summary of the last batch transcoding run.
 */
const lastProgressText=computed(()=>progress.value.finishedAt?`上次完成：成功 ${progress.value.transcoded||0}，失败 ${progress.value.failed||0}`:'暂无批量转码记录')
/**
 * 扫描进度百分比（0–100），根据已完成数与总数计算。
 *
 * Scan progress percentage (0–100), computed from completed/total count.
 */
const scanPercent=computed(()=>scanProgress.value.total?Math.round((scanProgress.value.completed||0)*100/scanProgress.value.total):(scanning.value?0:100))
/**
 * 刮削进度百分比（0–100），根据已处理数与总数计算。
 *
 * Scrape progress percentage (0–100), computed from completed/total count.
 */
const scrapePercent=computed(()=>scrapeTask.value?.total?Math.round((scrapeTask.value.completed||0)*100/scrapeTask.value.total):0)
/**
 * 刮削任务状态中文文本。
 */
const scrapeStatusText=computed(()=>({RUNNING:'进行中',COMPLETED:'已完成',PAUSED:'已暂停',PENDING:'等待中'}[scrapeTask.value?.status]||'空闲'))
/**
 * 重复文件总数（源路径重复 + 输出路径重复）。
 *
 * Total duplicate file count (source duplicates + output duplicates).
 */
const duplicateCount=computed(()=>(scanProgress.value.skippedSourceDuplicate||0)+(scanProgress.value.skippedOutputDuplicate||0))
/**
 * 加载仪表盘全部数据：服务状态、队列、转码进度、源库统计、扫描进度、刮削进度。
 * 若扫描或刮削正在运行则自动开启轮询；若已完成则展示结果。
 *
 * Load all dashboard data: service status, queue, transcoding progress,
 * source library stats, scan progress, and scrape progress. Automatically
 * starts polling if scan or scrape is running.
 * @returns {Promise<void>}
 */
async function load() {
  const [status, q, p, sources, pending, sp] = await Promise.all([
    api.adminStatus().catch(() => ({})),
    api.getQueue().catch(() => ({})),
    api.adminSourceTranscodeProgress().catch(() => ({})),
    api.adminSourceLibrary({ page: 0, size: 1 }).catch(() => ({})),
    api.adminSourceLibrary({ status: 'pending', page: 0, size: 1 }).catch(() => ({})),
    api.adminScanProgress().catch(() => ({}))
  ])
  d.value = status
  queue.value = q
  progress.value = p
  sourceTotal.value = sources.total || 0
  pendingCount.value = pending.total || 0
  scanProgress.value = sp
  if (sp.running) {
    scanning.value = true
    startPolling()
  } else if (sp.finishedAt) {
    scanResult.value = sp
  }
  try {
    const r = await api.adminLatestMetadataScrape()
    scrapeTask.value = r.exists ? r : null
  } catch {
    scrapeTask.value = null
  }
  if (scrapeTask.value && scrapeTask.value.status === 'RUNNING') startPolling()
}

/**
 * 启动扫描+刮削进度轮询（每秒一次）。
 *
 * Start polling scan and scrape progress (once per second).
 */
function startPolling(){if(!scanTimer)scanTimer=setInterval(pollScan,1000)}

/**
 * 停止扫描进度轮询并清除定时器。
 *
 * Stop polling scan progress and clear the timer.
 */
function stopPolling(){if(scanTimer){clearInterval(scanTimer);scanTimer=null}}

/**
 * 轮询扫描+刮削进度；检测到扫描或刮削结束时自动停止轮询并刷新数据。
 *
 * Poll scan and scrape progress; when any of them completes, stop polling
 * and refresh dashboard data automatically.
 * @returns {Promise<void>}
 */
async function pollScan() {
  const prevScan = scanning.value
  const sp = await api.adminScanProgress().catch(() => scanProgress.value)
  scanProgress.value = sp
  scanning.value = !!sp.running
  if (prevScan && !sp.running) {
    scanResult.value = sp
    await load()
    return
  }
  try {
    const r = await api.adminLatestMetadataScrape()
    scrapeTask.value = r.exists ? r : null
    if (scrapeTask.value && scrapeTask.value.status === 'RUNNING' && !scanTimer) startPolling()
    else if (!scrapeTask.value || (scrapeTask.value.status !== 'RUNNING' && scrapeTask.value.status !== 'PENDING')) {
      const stillScanning = !!(await api.adminScanProgress().catch(() => ({}))).running
      if (!stillScanning) stopPolling()
    }
  } catch {
    const stillScanning = !!(await api.adminScanProgress().catch(() => ({}))).running
    if (!stillScanning) stopPolling()
  }
}

/**
 * 触发源路径扫描，启动后自动轮询进度。
 * 若已有扫描在运行则直接返回。
 *
 * Trigger a source path scan and start polling progress automatically.
 * No-op if a scan is already running.
 * @returns {Promise<void>}
 */
async function scan(){if(scanning.value)return;try{scanProgress.value=await api.adminStartScan();scanning.value=true;scanResult.value=null;startPolling()}catch(e){await alertDialog(e.message||'扫描失败')}}

/**
 * 跳转到元数据刮削页面。
 */
function goScrape(){router.push({name:'admin-metadata-scrape'})}

/**
 * 将 ISO 时间字符串格式化为中文本地时间。
 *
 * Format an ISO time string as Chinese locale date/time.
 * @param {string|number|Date} value - 时间值 / the time value to format
 * @returns {string} 格式化后的本地时间 / formatted local time string
 */
function formatTime(value){return new Date(value).toLocaleString('zh-CN',{hour12:false})}
/**
 * 格式化刮削阈值百分比。
 */
function formatScrapeThreshold(v){return v!=null?Math.round(v*100)+'%':'—'}
/** 挂载时加载仪表盘数据。 / Load dashboard data on mount. */
onMounted(load)
/** 卸载时停止轮询。 / Stop polling on unmount. */
onUnmounted(stopPolling)
</script>
<style scoped>
.page-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-head h1{font-size:22px}.page-head p{color:#64748b;font-size:13px;margin-top:6px}.primary{height:36px;padding:0 15px;border-radius:6px;background:#2563eb;color:#fff;font-size:13px}.primary:disabled{opacity:.5}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:14px}.stats article{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:16px}.stats span,.stats small{display:block;color:#64748b;font-size:12px}.stats strong{display:block;font-size:26px;margin:8px 0 6px}.stats small{color:#94a3b8}.scan-progress{padding:14px 16px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;margin-bottom:14px;color:#1e40af}.scan-progress.complete{background:#f0fdf4;border-color:#bbf7d0;color:#166534}.progress-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.progress-head div{min-width:0}.progress-head strong,.progress-head span{display:block}.progress-head span{margin-top:4px;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.progress-head b{font-size:18px}.track{height:7px;margin:12px 0 9px;background:#dbeafe;border-radius:4px;overflow:hidden}.complete .track{background:#dcfce7}.track i{display:block;height:100%;background:#2563eb;transition:width .25s}.complete .track i{background:#16a34a}.progress-meta{display:flex;flex-wrap:wrap;gap:8px 18px;font-size:12px}.progress-meta .failed{color:#b91c1c;font-weight:700}.scrape-progress{padding:14px 16px;background:#f5f0ff;border:1px solid #ddd6fe;border-radius:8px;margin-bottom:14px;color:#5b21b6;cursor:pointer;transition:box-shadow .15s,border-color .15s}.scrape-progress:hover{box-shadow:0 2px 12px rgba(139,92,246,.15);border-color:#c4b5fd}.scrape-progress.complete{background:#f0fdf4;border-color:#bbf7d0;color:#166534}.scrape-progress .progress-head{position:relative}.scrape-progress .progress-head div{flex:1}.scrape-click-hint{position:absolute;right:0;top:50%;transform:translateY(-50%);font-size:11px;color:#7c3aed;opacity:.7;white-space:nowrap}.scrape-progress.complete .scrape-click-hint{color:#166534}.panel-head{display:flex;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #e2e8f0}.text-btn,.link{color:#2563eb;font-size:12px}.link:disabled{color:#94a3b8}table{width:100%;border-collapse:collapse;font-size:12px}th{padding:11px 14px;text-align:left;background:#f8fafc;color:#64748b}td{padding:13px 14px;border-top:1px solid #eef2f7;color:#475569}td strong,td small{display:block}td small{color:#94a3b8;margin-top:4px}.status{display:inline-flex;padding:3px 8px;border-radius:999px;font-weight:600}.green{background:#dcfce7;color:#166534}.blue{background:#dbeafe;color:#1d4ed8}.neutral{background:#f1f5f9;color:#475569}@media(max-width:900px){.stats{grid-template-columns:1fr 1fr}.panel{overflow:auto}table{min-width:760px}}
</style>
