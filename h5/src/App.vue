<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
  <ToastHost />
  <DialogHost />

  <!--
    二维码失效全屏不可关闭弹窗。
    由路由守卫统一置位（useQrGuardStore）；一旦显示，禁用页面滚动、点击、右键、
    系统返回与 history 导航，强制用户重新扫描电视端二维码。

    QR-invalidation fullscreen non-dismissible overlay.
    Toggled by the router guard (useQrGuardStore). When shown, page scroll,
    click, context menu, system back and history navigation are all blocked;
    the user must rescan the TV-side QR to proceed.
  -->
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="qrGuard.invalid"
        class="qr-invalid-overlay"
        @click.prevent
        @contextmenu.prevent
        @touchstart.prevent
        @wheel.prevent
        @keydown.prevent
        @mouseup.prevent
        @mousedown.prevent
      >
        <div class="qr-invalid-modal" @click.stop @contextmenu.stop>
          <div class="qr-invalid-icon">
            <svg xmlns="http://www.w3.org/2000/svg" width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
              <rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>
            </svg>
          </div>
          <h2>二维码已失效</h2>
          <p>{{ qrGuard.reason || '二维码无效或已过期' }}</p>
          <p class="hint">请在电视端刷新二维码后重新扫码</p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.qr-invalid-overlay {
  position: fixed;
  inset: 0;
  z-index: 2147483000; /* 接近最大 z-index，确保覆盖一切 */
  background: rgba(0, 0, 0, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(6px);
  /* 捕获所有指针 / Capture all pointer events */
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  overflow: hidden;
}
.qr-invalid-modal {
  text-align: center;
  color: #fff;
  padding: 40px 48px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  max-width: 360px;
  pointer-events: auto; /* 内部样式仍可点击文本选择（仅文案） */
}
.qr-invalid-icon {
  color: #ff6b6b;
  margin-bottom: 20px;
}
.qr-invalid-modal h2 {
  margin: 0 0 12px;
  font-size: 22px;
  font-weight: 600;
}
.qr-invalid-modal p {
  margin: 0 0 8px;
  font-size: 15px;
  color: rgba(255,255,255,0.7);
  line-height: 1.5;
  word-break: break-all;
}
.qr-invalid-modal .hint {
  margin-top: 16px;
  font-size: 13px;
  color: rgba(255,255,255,0.4);
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>

<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { usePlayerStore } from './stores/player'
import { useUserStore } from './stores/user'
import { useFavoritesStore } from './stores/favorites'
import { useQrGuardStore } from './stores/qrGuard'
import ToastHost from './components/ToastHost.vue'
import DialogHost from './components/DialogHost.vue'

const player = usePlayerStore()
const user = useUserStore()
const favorites = useFavoritesStore()
const qrGuard = useQrGuardStore()

/**
 * 处理浏览器/系统返回手势与外部 navigation：
 * 失效态下任何"返回"动作都强制拉回 /invalid，阻断用户逃出蒙版。
 *
 * Handle browser/system back gesture and external navigation:
 * In invalid state, any "back" action is forced to /invalid to keep the overlay.
 */
function blockBack() {
  // 把当前 history 项替换为 /invalid，防止 popstate 把用户带回上一个有效路由
  // Replace current history entry with /invalid so popstate cannot return to a valid route
  if (window.location.pathname.replace(/\/+$/, '') !== '/m/invalid') {
    history.replaceState(null, '', '/m/invalid')
  }
}

watch(
  () => qrGuard.invalid,
  (invalid) => {
    if (invalid) {
      // 阻断页面滚动 + 屏蔽系统返回
      // Block page scroll + intercept system back
      blockBack()
      document.documentElement.style.overflow = 'hidden'
      document.body.style.overflow = 'hidden'
      // 切断播放器连接，避免在失效态下继续消耗服务器资源
      // Disconnect player to avoid server-side resource consumption while invalid
      try { player.disconnect() } catch { /* noop */ }
    } else {
      document.documentElement.style.overflow = ''
      document.body.style.overflow = ''
    }
  }
)

/**
 * popstate 拦截：失效态下浏览器/系统返回一律推回 /invalid。
 *
 * popstate interceptor: while invalid, any back navigation is pushed back to /invalid.
 */
function onPopState() {
  if (qrGuard.invalid) {
    history.pushState(null, '', '/m/invalid')
  }
}

/**
 * 阻止用户在失效态下通过 a 链接、location.href 等逃离。
 *
 * Prevent the user from escaping the invalid state via anchor links or location.href.
 */
function beforeUnload(e) {
  if (qrGuard.invalid) {
    e.preventDefault()
    e.returnValue = ''
    return ''
  }
}

onMounted(() => {
  if (user.isRegistered && !qrGuard.invalid) {
    player.connect()
    favorites.load(user.clientToken).catch(() => {})
  }
  window.addEventListener('popstate', onPopState)
  window.addEventListener('beforeunload', beforeUnload)
  // 暴露给遗留逻辑（如有外部代码仍调用 window.__qrInvalid）— 已迁移到 store，保留为空安全垫
  // Expose legacy hook (some older code may still call window.__qrInvalid) — migrated to store; kept as a safe no-op
  window.__qrInvalid = (msg) => qrGuard.markInvalid(msg)
})

onUnmounted(() => {
  window.removeEventListener('popstate', onPopState)
  window.removeEventListener('beforeunload', beforeUnload)
  try { player.disconnect() } catch { /* noop */ }
})
</script>