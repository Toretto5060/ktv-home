<template>
  <div class="entry">
    <!-- 品牌区：Logo + 标题 / Branding: logo + title -->
    <div class="logo">🎤</div>
    <div class="title">家庭KTV</div>
    <div class="room" v-if="roomName">房间：{{ roomName }}</div>

    <!-- 扫码成功提示 / Scan success indicator -->
    <div v-if="scanStatus" class="scan-banner" :class="scanStatus.type">
      {{ scanStatus.message }}
    </div>

    <!-- 昵称输入区 / Nickname input -->
    <div class="field">
      <label>你的昵称（点歌时显示）</label>
      <div class="input-wrap">
        <input v-model="nickname" maxlength="12" placeholder="输入昵称" />
      </div>
      <div class="hint">已为你随机生成，可修改；本机记忆，下次免填</div>
    </div>

    <button class="btn enter-btn" @click="enter">进入点歌</button>
    <!-- 页脚提示 / Footer note -->
    <div class="foot">仅限家庭局域网使用 · 无需注册</div>
  </div>
</template>

<script setup>
/**
 * 入口页面 — 用户输入昵称后进入点歌系统。
 * 支持随机昵称生成、本地记忆和昵称冲突自动去重。
 * 也支持扫描二维码后直接进入房间（携带 ?room=xxx 或 ?qr=xxx 参数）。
 *
 * Entry page — user enters a nickname and proceeds to the song-request system.
 * Supports random nickname generation, local memory, and automatic dedup on nickname conflict.
 * Also supports direct entry via scanning a QR code (carries ?room=xxx or ?qr=xxx parameter).
 */
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { usePlayerStore } from '../stores/player'
import { useQrGuardStore } from '../stores/qrGuard'
import api from '../api/client'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const player = usePlayerStore()
const qrGuard = useQrGuardStore()

const nickname = ref(user.suggestNickname())
const roomName = ref('')
const scanStatus = ref(null)
const scannedQrCode = ref('')
const joinedRoomId = ref('')

// 默认回填已存昵称或随机建议值（详设 H5-01）
// Default: fallback to saved nickname or a random suggestion (spec H5-01)
onMounted(async () => {
  // 兼容两种参数名：?room=xxx（TV APK 生成的加密 token）和 ?qr=xxx（旧兼容）
  const qrFromUrl = route.query.room ?? route.query.qr
  if (qrFromUrl) {
    scannedQrCode.value = qrFromUrl
    // 如果已经注册过，直接尝试加入房间
    if (user.isRegistered) {
      nickname.value = user.nickname
      await tryJoinRoom()
    }
  }
})

/**
 * 尝试通过二维码加入房间。
 * 失败时不再允许用户继续点歌——调用 qrGuard.markInvalid 进入全屏蒙版状态。
 *
 * Try to join a room via QR code.
 * On failure the user is no longer allowed to proceed—qrGuard.markInvalid
 * flips the app into the non-dismissible overlay state.
 */
async function tryJoinRoom() {
  if (!scannedQrCode.value) return
  scanStatus.value = { type: 'pending', message: '正在加入房间...' }
  try {
    const deviceId = getDeviceId()
    const result = await api.roomJoin(scannedQrCode.value, deviceId, nickname.value)
    if (result.success) {
      roomName.value = result.room_name
      joinedRoomId.value = result.room_id || ''
      user.setJoinedRoom(scannedQrCode.value, joinedRoomId.value)
      scanStatus.value = {
        type: 'success',
        message: `已加入房间"${result.room_name}"${result.is_new_member ? '' : '（您已在房间中）'}`
      }
    } else {
      const msg = result.message || '二维码无效或已过期'
      scanStatus.value = { type: 'error', message: msg }
      scannedQrCode.value = ''
      user.clearJoinedRoom()
      qrGuard.markInvalid(msg)
    }
  } catch (e) {
    const msg = e.message || '加入失败，请检查网络'
    if (e?.status === 403 || (e?.message && e.message.includes('二维码'))) {
      // 失效：触发路由层不可关闭蒙版
      // Invalid: trigger router-layer non-dismissible overlay
      scannedQrCode.value = ''
      user.clearJoinedRoom()
      qrGuard.markInvalid(msg)
      return
    }
    scanStatus.value = { type: 'error', message: msg }
    scannedQrCode.value = ''
  }
}

function getDeviceId() {
  let deviceId = localStorage.getItem('home-ktv.deviceId')
  if (!deviceId) {
    deviceId = 'h5-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem('home-ktv.deviceId', deviceId)
  }
  return deviceId
}

/**
 * 点击"进入点歌"按钮：注册昵称并跳转到首页。
 * 先将昵称注册到本地 store，再同步到服务端以处理昵称冲突（P2.13）。
 * 注册完成后建立 WebSocket 连接并路由到 home。
 *
 * Handles the "Enter" button: registers the nickname and navigates to home.
 * Registers locally first, then syncs to server to resolve nickname conflicts (P2.13).
 * After registration, establishes the WebSocket connection and routes to home.
 *
 * 若用户是从扫码进入的：必须先成功加入房间才能进入点歌；
 * QR 失效时由 qrGuard 拦截，并跳转到 /invalid 显示不可关闭蒙版。
 *
 * If the user arrived via QR scan: must successfully join the room before entering
 * the song-request UI; QR invalidation is enforced by qrGuard → /invalid overlay.
 */
async function enter() {
  user.register(nickname.value)
  // 同步到服务端并取回去重后的最终昵称（P2.13 昵称冲突显序号）
  // Sync to server and fetch the deduped final nickname (P2.13 nickname conflict → suffix)
  try {
    const res = await api.registerUser(user.clientToken, user.nickname)
    if (res?.nickname) user.setNickname(res.nickname)
  } catch { /* 离线也可继续，稍后重连同步 / offline is ok, re-sync on reconnect */ }

  // 如果是扫码进入，先尝试加入房间
  if (scannedQrCode.value) {
    await tryJoinRoom()
    if (qrGuard.invalid) {
      // QR 已失效：路由守卫会接管跳转 /invalid，无需手动操作
      // QR invalidated: router guard will redirect to /invalid automatically
      return
    }
  }

  player.connect()
  router.replace({ name: 'home' })
}
</script>

<style scoped>
.entry {
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center;
  padding: 0 32px calc(28px + var(--safe-bottom));
  background: radial-gradient(ellipse 400px 300px at 50% 30%, rgba(240,199,66,.06), transparent),
              linear-gradient(175deg, rgba(20,26,42,.9), var(--bg));
}
.logo {
  width: 88px; height: 88px; border-radius: 24px; margin-top: 22vh;
  background: linear-gradient(135deg, var(--gold), #dba70e);
  display: flex; align-items: center; justify-content: center; font-size: 40px;
  box-shadow: 0 12px 40px rgba(240,199,66,.25);
}
.title { font-size: 28px; font-weight: 800; letter-spacing: -.5px; margin-top: 22px; }
.room {
  font-size: 13px; color: var(--dim); margin-top: 12px;
  background: var(--panel2); border: 1px solid var(--glass-border);
  border-radius: 999px; padding: 5px 13px;
}

.scan-banner {
  margin-top: 20px;
  padding: 12px 16px;
  border-radius: 10px;
  font-size: 13px;
  max-width: 100%;
  text-align: center;
  word-break: break-all;
}
.scan-banner.success {
  background: rgba(34, 197, 94, 0.1);
  border: 1px solid rgba(34, 197, 94, 0.3);
  color: #22c55e;
}
.scan-banner.error {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #ef4444;
}
.scan-banner.pending {
  background: rgba(59, 130, 246, 0.1);
  border: 1px solid rgba(59, 130, 246, 0.3);
  color: #3b82f6;
}

.field { width: 100%; margin-top: 42px; }
.field label { font-size: 13px; color: var(--dim); display: block; margin-bottom: 10px; }
.input-wrap {
  background: var(--panel2); border: 1px solid rgba(240,199,66,.2);
  border-radius: 12px; padding: 12px 14px;
}
.input-wrap input {
  width: 100%; background: none; border: none; outline: none;
  color: var(--text); font-size: 15px;
}
.hint { font-size: 11px; color: var(--dim2); margin-top: 10px; }
.enter-btn { width: 100%; padding: 16px; font-size: 17px; border-radius: 14px; margin-top: 28px; }
.foot { margin-top: auto; font-size: 11px; color: var(--dim2); }
</style>