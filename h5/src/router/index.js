import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useQrGuardStore } from '../stores/qrGuard'
import { api } from '../api/client'

/**
 * 应用路由配置模块。
 * 定义 H5 前端所有页面的路由映射、懒加载及导航守卫。
 *
 * App router configuration module.
 * Defines route mappings, lazy loading, and navigation guards for all H5 frontend pages.
 */

// H5 页面地图（详设§3.2）
// H5 page map (design spec §3.2)
const routes = [
  { path: '/', name: 'entry', component: () => import('../views/EntryView.vue'), meta: { public: true } },        // H5-01
  { path: '/home', name: 'home', component: () => import('../views/HomeView.vue') },                              // H5-02
  { path: '/search', name: 'search', component: () => import('../views/SearchView.vue') },                        // H5-03
  { path: '/artist/:name', name: 'artist', component: () => import('../views/ArtistView.vue') },                  // H5-04
  { path: '/browse', name: 'browse', component: () => import('../views/CategoryBrowseView.vue') },
  { path: '/playlists', name: 'playlists', component: () => import('../views/PlaylistListView.vue') },
  { path: '/playlists/:id', name: 'playlist-detail', component: () => import('../views/PlaylistDetailView.vue') },
  { path: '/recent', name: 'recent-history', component: () => import('../views/RecentHistoryView.vue') },
  { path: '/favorites', name: 'favorites', component: () => import('../views/FavoritesView.vue') },
  { path: '/queue', name: 'queue', component: () => import('../views/QueueView.vue') },                           // H5-05
  { path: '/remote', name: 'remote', component: () => import('../views/RemoteView.vue') },                        // H5-06
  { path: '/lyric', name: 'lyric', component: () => import('../views/LyricView.vue') },                           // H5-07

  // 二维码失效专用路由：路由守卫会把任何失效访问重定向至此，仅渲染全屏不可关闭蒙版
  // QR-invalid exclusive route: guard redirects all invalid access here; only renders the non-dismissible overlay
  { path: '/invalid', name: 'invalid', component: () => import('../views/InvalidView.vue'), meta: { public: true, invalid: true } },

  // 管理后台（免登录，PC/手机浏览器）
  // Admin panel (no login required, accessible from PC/mobile browser)
  { path: '/admin', name: 'admin-dashboard', component: () => import('../views/admin/DashboardView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/source-library', name: 'admin-source-library', component: () => import('../views/admin/SourceLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library', name: 'admin-ktv-library', component: () => import('../views/admin/KtvLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/artists', name: 'admin-artists', component: () => import('../views/admin/ArtistLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/ktv-library/metadata-scrape', name: 'admin-metadata-scrape', component: () => import('../views/admin/MetadataScrapeView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/songs', redirect: { name: 'admin-ktv-library' } },
  { path: '/admin/ai', name: 'admin-ai', component: () => import('../views/admin/AiLibraryView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/rooms', name: 'admin-rooms', component: () => import('../views/admin/RoomManagementView.vue'), meta: { public: true, admin: true } },
  { path: '/admin/settings', name: 'admin-settings', component: () => import('../views/admin/SettingsView.vue'), meta: { public: true, admin: true } }
]

/**
 * 创建路由实例。
 * 使用 HTML5 History 模式，基础路径为 /m/。
 *
 * Create router instance.
 * Uses HTML5 History mode with base path /m/.
 */
const router = createRouter({
  // 部署在 /m 下
  // Deployed under /m/
  history: createWebHistory('/m/'),
  routes
})

/**
 * 从 URL 查询参数中读取二维码 token（兼容 ?room= 与 ?qr=）。
 *
 * Read the QR token from URL query (?room= or ?qr=).
 * @param {object} to - vue-router target route / vue-router target route
 * @returns {string} 二维码 token，空串表示无 / QR token, empty string if absent
 */
function qrFromRoute(to) {
  const q = to.query || {}
  const raw = q.room ?? q.qr
  return typeof raw === 'string' ? raw : ''
}

/**
 * 执行实际的房间加入校验。捕获 403 / 包含"二维码"字样的错误。
 *
 * Perform the actual room-join validation. Catches 403 or errors mentioning "二维码".
 * @param {string} qrCode - 二维码 token / QR token
 * @param {string} deviceId - 客户端设备 ID / client device ID
 * @param {string} nickname - 用户昵称 / user nickname
 * @returns {Promise<{ ok: boolean, message?: string, payload?: object }>}
 */
async function validateQr(qrCode, deviceId, nickname) {
  try {
    const result = await api.roomJoin(qrCode, deviceId, nickname)
    return { ok: true, payload: result }
  } catch (err) {
    if (err?.status === 403 || (err?.message && err.message.includes('二维码'))) {
      return { ok: false, message: err.message || '二维码无效或已过期' }
    }
    // 非 QR 错误（网络/超时）：放过本次，避免误杀；调用方可在业务里再处理
    // Non-QR errors (network/timeout): allow this time to avoid false positives; caller may handle in business code
    return { ok: true, networkError: true, message: err?.message || '' }
  }
}

/**
 * 获取或创建本机 device_id，EntryView 已有等价实现，此处保持独立避免循环依赖。
 *
 * Get or create the local device ID. EntryView has an equivalent; kept independent to avoid circular deps.
 * @returns {string} device id / device id
 */
function getDeviceId() {
  let id = localStorage.getItem('home-ktv.deviceId')
  if (!id) {
    id = 'h5-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem('home-ktv.deviceId', id)
  }
  return id
}

/**
 * 全局前置导航守卫。
 * 职责：
 *  1. 未注册用户强制跳转到进入页（详设 H5-01）；
 *  2. 已注册但 QR 失效时，所有 H5 业务页面跳转到 /invalid（仅显示不可关闭蒙版）；
 *  3. URL 含 ?room= 时同步校验，失败则失效，成功则缓存 token；
 *  4. 缺少 QR 凭证且目标为受保护页面，视为失效。
 *
 * Global beforeEach navigation guard.
 * Responsibilities:
 *  1. Redirect unregistered users to the entry page (design spec H5-01);
 *  2. Redirect all H5 business pages to /invalid when QR is invalid (non-dismissible overlay only);
 *  3. When URL carries ?room=, validate synchronously; mark invalid on failure, cache token on success;
 *  4. Treat missing QR credential as invalid for protected routes.
 */
router.beforeEach(async (to) => {
  const user = useUserStore()
  const qr = useQrGuardStore()

  // /invalid 自身放行：避免重定向循环；UI 层负责完全阻塞用户操作
  // Allow /invalid itself to prevent redirect loops; UI layer fully blocks user interaction
  if (to.name === 'invalid') return true

  // 1) 未注册 → 回到进入页
  // 1) Unregistered → back to entry
  if (!user.isRegistered) {
    if (to.meta.public) return true
    return { name: 'entry', query: to.query }
  }

  // 2) 已注册用户访问公共页（/、/admin/*）→ 直接放行
  // 2) Registered user accessing public pages (/, /admin/*) → allow
  if (to.meta.public) return true

  // 3) 已失效：所有受保护页面统一跳 /invalid
  // 3) Already invalidated: all protected pages redirect to /invalid
  if (qr.invalid) {
    return { name: 'invalid' }
  }

  // 4) 受保护页面：必须有 QR 凭证或 URL 中显式携带 QR
  // 4) Protected routes must carry a QR token in the URL or have a cached valid token
  const urlQr = qrFromRoute(to)

  if (urlQr) {
    // URL 显式带 QR：以本次校验结果为准（失败即失效）
    // URL carries QR explicitly: trust this validation result (failure → invalid)
    const deviceId = getDeviceId()
    const result = await validateQr(urlQr, deviceId, user.nickname)
    if (!result.ok) {
      user.clearJoinedRoom()
      qr.markInvalid(result.message || '二维码无效或已过期')
      return { name: 'invalid' }
    }
    user.setJoinedRoom(urlQr, result.payload?.room_id, result.payload?.room_name)
    // URL 上的 room / qr 参数只用于一次性校验，避免污染业务路由；继续前往目标页
    // Strip room/qr query so it doesn't pollute the target route; continue to target
    if (to.query && (to.query.room || to.query.qr)) {
      const { room: _r, qr: _q, ...rest } = to.query
      return { path: to.path, query: rest, hash: to.hash }
    }
    return true
  }

  // 没有 QR 参数：依赖缓存的 joinedRoomToken；缺失则视为"尚未扫码"，
  // 重定向回进入页让用户扫码；只有真正收到 403 才标记失效。
  // No QR in URL: rely on cached joinedRoomToken; missing means "not yet scanned",
  // redirect to entry so the user can scan; only true 403 marks invalid.
  if (!user.hasValidRoom) {
    return { name: 'entry' }
  }

  return true
})

export default router