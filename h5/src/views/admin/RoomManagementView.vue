<template>
  <AdminLayout active="rooms">
    <div class="room-management">
      <header class="page-header">
        <h1>房间管理</h1>
        <button class="btn-outline" @click="loadData">
          <span>↻</span> 刷新
        </button>
      </header>

      <div class="main-layout">
        <!-- 左侧: 已允许房间 + 空闲房间（上下排列） -->
        <div class="left-column">
          <div class="approved-section">
            <div class="section-header">
              <h2>已允许的房间</h2>
            </div>

            <div v-if="loading" class="loading">加载中...</div>
            <div v-else-if="approvedRooms.length === 0" class="empty-state">
              暂无已允许的房间
            </div>
            <div v-else class="room-list">
              <div v-for="room in approvedRooms" :key="room.id" class="room-card">
                <div class="room-info">
                  <div class="room-name">
                    <span class="name">{{ room.name || displayDeviceId(room.deviceId) }}</span>
                    <span v-if="room.activeStart || room.activeEnd" class="time-badge">
                      {{ formatTimeRange(room) }}
                    </span>
                  </div>
                  <div class="room-meta">
                    <span class="device-id">设备ID: {{ displayDeviceId(room.deviceId) }}</span>
                  </div>
                </div>
                <div class="room-actions">
                  <button class="btn-icon" title="编辑名称" @click="editRoomName(room)">✎</button>
                  <button class="btn-icon" title="设置有效期" @click="editQrExpiry(room)">⏰</button>
                  <button class="btn-icon" title="刷新二维码" @click="refreshQr(room)">⟳</button>
                  <button class="btn-icon" title="解散房间" @click="dissolveRoom(room)">⚡</button>
                  <button class="btn-icon danger" title="关闭房间" @click="disableRoom(room)">✕</button>
                </div>
              </div>
            </div>
          </div>

          <!-- 空闲房间 -->
          <div class="idle-section">
            <div class="section-header">
              <h2>空闲房间</h2>
            </div>

            <div v-if="loading" class="loading">加载中...</div>
            <div v-else-if="idleRooms.length === 0" class="empty-state small">
              暂无空闲房间
            </div>
            <div v-else class="room-list">
              <div v-for="room in idleRooms" :key="room.id" class="room-card idle-card">
                <div class="room-info">
                  <div class="room-name">
                    <span class="name">{{ room.name || displayDeviceId(room.deviceId) }}</span>
                    <span v-if="room.activeStart || room.activeEnd" class="time-badge idle-badge">
                      {{ formatTimeRange(room) }}
                    </span>
                  </div>
                  <div class="room-meta">
                    <span class="device-id">设备ID: {{ displayDeviceId(room.deviceId) }}</span>
                  </div>
                </div>
                <div class="room-actions">
                  <button class="btn-icon" title="编辑名称" @click="editRoomName(room)">✎</button>
                  <button class="btn-icon" title="设置有效期" @click="editQrExpiry(room)">⏰</button>
                  <button class="btn-icon" title="开启房间" @click="enableRoom(room)">▶</button>
                  <button class="btn-icon danger" title="关闭房间" @click="disableRoom(room)">✕</button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧: 申请房间 -->
        <div class="application-section">
          <div class="section-header">
            <h2>申请房间</h2>
            <span v-if="pendingApplications.length" class="badge">{{ pendingApplications.length }}</span>
          </div>

          <div v-if="pendingApplications.length === 0" class="empty-state small">
            暂无待审核申请
          </div>
          <div v-else class="application-list">
            <!-- tickRef 强制 vue 响应式刷新倒计时 -->
            <span style="display:none">{{ tickRef }}</span>
            <div v-for="app in pendingApplications" :key="app.id" class="application-card">
              <div class="app-info">
                <div class="app-device">{{ displayDeviceId(app.deviceId) }}</div>
                <div class="app-time">
                  申请于 {{ formatTime(app.createdAt) }}
                  <span class="countdown" :class="{ urgent: getRemainingSeconds(app) < 60 }">
                    · {{ getRemainingTime(app) }}
                  </span>
                </div>
              </div>
              <div class="app-actions">
                <button class="btn-approve" @click="approveApp(app)">允许</button>
                <button class="btn-reject" @click="rejectApp(app)">拒绝</button>
              </div>
            </div>
          </div>

          <!-- 黑名单管理 -->
          <div class="section-header">
            <h2>黑名单</h2>
          </div>

          <div v-if="blacklist.length === 0" class="empty-state small">
            无黑名单设备
          </div>
          <div v-else class="blacklist-list">
            <div v-for="item in blacklist" :key="item.deviceId" class="blacklist-card">
              <div class="bl-info">
                <div class="bl-device">{{ displayDeviceId(item.deviceId) }}</div>
                <div class="bl-reason">{{ item.reason }}</div>
                <div class="bl-time">{{ formatTime(item.createdAt) }}</div>
              </div>
              <button class="btn-unblock" @click="unblockDevice(item)">解除</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 编辑名称弹窗 -->
    <div v-if="editNameDialog" class="dialog-mask" @click.self="editNameDialog = false">
      <div class="dialog">
        <h3>编辑房间名称</h3>
        <input v-model="editNameValue" type="text" placeholder="房间名称" class="dialog-input" />
        <div class="dialog-actions">
          <button class="btn-secondary" @click="editNameDialog = false">取消</button>
          <button class="btn-primary" @click="saveRoomName">保存</button>
        </div>
      </div>
    </div>

    <!-- 设置二维码有效期弹窗 -->
    <div v-if="editExpiryDialog" class="dialog-mask" @click.self="editExpiryDialog = false">
      <div class="dialog">
        <h3>设置二维码有效期</h3>
        <div class="time-inputs">
          <div class="time-field">
            <label>开始时间</label>
            <input v-model="editExpiryStart" type="datetime-local" class="dialog-input" />
          </div>
          <div class="time-field">
            <label>结束时间{{ editExpiryHours === 0 && editExpiryDays === 0 ? '（手动选择）' : '（自动计算）' }}</label>
            <input v-model="editExpiryEnd" type="datetime-local" class="dialog-input" :disabled="editExpiryHours > 0 || editExpiryDays > 0" />
          </div>
        </div>
        <div class="expiry-quick">
          <div class="expiry-field">
            <label>小时</label>
            <select v-model="editExpiryHours" class="dialog-select" :disabled="editExpiryDays > 0">
              <option v-for="h in hourOptions" :key="h" :value="h">{{ h === 0 ? '不设置' : h + ' 小时' }}</option>
            </select>
          </div>
          <div class="expiry-field">
            <label>天数</label>
            <select v-model="editExpiryDays" class="dialog-select" :disabled="editExpiryHours > 0">
              <option v-for="d in dayOptions" :key="d" :value="d">{{ d === 0 ? '不设置' : d + ' 天' }}</option>
            </select>
          </div>
        </div>
        <p class="time-hint">小时和天数均为 0 表示永久有效（不设置结束时间）</p>
        <div class="dialog-actions">
          <button class="btn-secondary" @click="editExpiryDialog = false">取消</button>
          <button class="btn-primary" @click="saveQrExpiry">保存</button>
        </div>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { api } from '../../api/client'
import { KtvSocket } from '../../api/ws'
import AdminLayout from './AdminLayout.vue'

const loading = ref(false)
const approvedRooms = ref([])
const idleRooms = ref([])
const pendingApplications = ref([])
const blacklist = ref([])
/** 每秒 tick 一次触发响应式刷新（getRemainingSeconds 不是反应式，需要抖动引用）。 */
const tickRef = ref(0)
let countdownTimer = null
let socket = null

function handleWsEvent(type, payload) {
  console.log('[RoomManagement] WS 事件:', type, payload)
  switch (type) {
    case 'new_room_application': {
      // 直接添加新申请到列表，避免事务时序问题
      const newApp = {
        id: payload.room_id,
        deviceId: payload.device_id,
        roomId: payload.room_id,
        name: payload.name || '',
        createdAt: payload.created_at,
        expiredAt: null, // 后端会在下次 loadData 时提供
        expiredInSeconds: 180 // 默认 3 分钟
      }
      // 避免重复添加
      if (!pendingApplications.value.find(a => a.id === newApp.id)) {
        pendingApplications.value.unshift(newApp)
      }
      break
    }
    case 'application_approved': {
      // 从申请列表移除
      pendingApplications.value = pendingApplications.value.filter(a => a.id !== payload.application_id)
      // 触发完整刷新获取最新的房间数据
      loadData()
      break
    }
    case 'application_expired': {
      // 从申请列表移除
      pendingApplications.value = pendingApplications.value.filter(a => a.id !== payload.application_id)
      break
    }
    case 'device_blacklisted': {
      // 添加到黑名单
      if (!blacklist.value.find(b => b.deviceId === payload.device_id)) {
        blacklist.value.unshift({
          deviceId: payload.device_id,
          reason: payload.reason || '申请超时自动拉黑',
          createdAt: new Date().toISOString()
        })
      }
      break
    }
    case 'room_disabled':
    case 'room_dissolved':
    case 'room_expired':
    case 'room_promoted':
    case 'room_name_changed':
    case 'room_time_changed':
    case 'qr_code_refreshed':
    case 'room_list_updated':
      // 这些事件需要完整刷新
      loadData()
      break
  }
}

// 编辑名称
const editNameDialog = ref(false)
const editNameValue = ref('')
const editingRoom = ref(null)

// 二维码有效期
const editExpiryDialog = ref(false)
const editExpiryStart = ref('')
const editExpiryEnd = ref('')
const editExpiryHours = ref(0)
const editExpiryDays = ref(0)
const hourOptions = [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24]
const dayOptions = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30]

function calcExpiryEnd() {
  if (!editExpiryStart.value || (editExpiryHours.value === 0 && editExpiryDays.value === 0)) {
    editExpiryEnd.value = ''
    return
  }
  const start = new Date(editExpiryStart.value)
  const hours = (editExpiryDays.value || 0) * 24 + (editExpiryHours.value || 0)
  start.setHours(start.getHours() + hours)
  const pad = (n) => String(n).padStart(2, '0')
  editExpiryEnd.value = `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}T${pad(start.getHours())}:${pad(start.getMinutes())}`
}

watch([editExpiryStart, editExpiryHours, editExpiryDays], calcExpiryEnd)

onMounted(() => {
  loadData()
  countdownTimer = setInterval(tickCountdown, 1000) // 每秒推动倒计时，倒计时归零主动 expire
  socket = new KtvSocket({ onEvent: handleWsEvent })
  socket.connect()
})

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
  if (socket) socket.close()
})

async function loadData() {
  console.log('[RoomManagement] loadData 开始调用 API')
  try {
    const [rooms, idle, applications, bl] = await Promise.all([
      api.roomList(),
      api.idleRooms(),
      api.roomApplications(),
      api.blacklist()
    ])
    console.log('[RoomManagement] loadData 结果:', { rooms, idle, applications, bl })
    console.log('[RoomManagement] pendingApplications 数量:', applications?.length || 0)
    approvedRooms.value = rooms || []
    idleRooms.value = idle || []
    pendingApplications.value = applications || []
    blacklist.value = bl || []
  } catch (e) {
    console.error('加载数据失败', e)
  }
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}

function formatTimeRange(room) {
  if (room.activeStart && room.activeEnd) {
    return `${formatTime(room.activeStart)} - ${formatTime(room.activeEnd)}`
  } else if (room.activeStart) {
    return `从 ${formatTime(room.activeStart)} 开放`
  } else if (room.activeEnd) {
    return `至 ${formatTime(room.activeEnd)} 结束`
  }
  return '永久开放'
}

function getRemainingSeconds(app) {
  // 直接使用后端计算好的剩余秒数，避免前端时区计算误差
  return Math.max(0, app.expiredInSeconds || 0)
}

function getRemainingTime(app) {
  const seconds = getRemainingSeconds(app)
  if (seconds <= 0) return '已超时'
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

/** 将 "tv-abc123de" 转成 "abc123de" 用于显示。 */
function displayDeviceId(deviceId) {
  if (!deviceId) return ''
  return deviceId.startsWith('tv-') ? deviceId.slice(3) : deviceId
}

/** 实时倒计时：每秒推动一次，倒计时归零主动调 expire 接口让后端立即拉黑。 */
const expireTriggeredIds = new Set()
function tickCountdown() {
  // 推动模板重算 getRemainingSeconds
  tickRef.value = (tickRef.value + 1) % 1000000
  for (const app of pendingApplications.value) {
    // 前端递减剩余秒数（由后端计算初值，避免时区问题）
    if (app.expiredInSeconds && app.expiredInSeconds > 0) {
      app.expiredInSeconds--
    }
    const secs = getRemainingSeconds(app)
    if (secs <= 0 && app.id && !expireTriggeredIds.has(app.id)) {
      expireTriggeredIds.add(app.id)
      // 主动告诉后端去处理这条过期，不用等 1 分钟定时器
      api.expireApplication(app.id).catch(() => {}).then(loadData)
    }
  }
}

function editRoomName(room) {
  editingRoom.value = room
  editNameValue.value = room.name || ''
  editNameDialog.value = true
}

async function saveRoomName() {
  if (!editingRoom.value) return
  try {
    await api.updateRoomName(editingRoom.value.id, editNameValue.value)
    editNameDialog.value = false
    loadData()
  } catch (e) {
    alert('保存失败: ' + e.message)
  }
}

function editQrExpiry(room) {
  editingRoom.value = room
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const toLocal = (d) =>
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
  editExpiryStart.value = room.activeStart ? room.activeStart.slice(0, 16) : toLocal(now)
  editExpiryEnd.value = room.activeEnd ? room.activeEnd.slice(0, 16) : ''
  editExpiryHours.value = 0
  editExpiryDays.value = 0
  editExpiryDialog.value = true
}

async function saveQrExpiry() {
  if (!editingRoom.value) return
  try {
    const start = editExpiryStart.value ? new Date(editExpiryStart.value).toISOString() : null
    const end = editExpiryEnd.value ? new Date(editExpiryEnd.value).toISOString() : null
    await api.setQrExpiry(editingRoom.value.id, start, end)
    editExpiryDialog.value = false
    loadData()
  } catch (e) {
    alert('保存失败: ' + e.message)
  }
}

async function refreshQr(room) {
  try {
    await api.refreshRoomQr(room.id)
    loadData()
  } catch (e) {
    alert('刷新失败: ' + e.message)
  }
}

async function deleteRoom(room) {
  if (!confirm(`确定删除房间 "${room.name || room.deviceId}" 吗？删除后将清除该设备的全部授权记录。`)) return
  try {
    await api.deleteRoom(room.id)
    loadData()
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
}

async function dissolveRoom(room) {
  if (!confirm(`确定解散房间 "${room.name || room.deviceId}" 吗？解散后二维码立即失效，房间进入空闲列表。`)) return
  try {
    await api.dissolveRoom(room.id)
    loadData()
  } catch (e) {
    alert('解散失败: ' + e.message)
  }
}

async function enableRoom(room) {
  try {
    await api.enableRoom(room.id)
    loadData()
  } catch (e) {
    alert('开启失败: ' + e.message)
  }
}

async function disableRoom(room) {
  if (!confirm(`确定关闭房间 "${room.name || room.deviceId}" 吗？关闭后房间将进入黑名单。`)) return
  try {
    await api.disableRoom(room.id)
    loadData()
  } catch (e) {
    alert('关闭失败: ' + e.message)
  }
}

async function approveApp(app) {
  try {
    await api.approveApplication(app.id)
    loadData()
  } catch (e) {
    alert('批准失败: ' + e.message)
  }
}

function rejectApp(app) {
  // 直接拒绝，不需要填原因
  confirmingReject(app)
}

async function confirmingReject(app) {
  try {
    await api.rejectApplication(app.id, '管理员拒绝')
    loadData()
  } catch (e) {
    alert('拒绝失败: ' + e.message)
  }
}

async function unblockDevice(item) {
  if (!confirm(`确定解除设备 "${item.deviceId}" 的黑名单吗？`)) return
  try {
    await api.removeFromBlacklist(item.deviceId)
    loadData()
  } catch (e) {
    alert('解除失败: ' + e.message)
  }
}
</script>

<style scoped>
.room-management {
  max-width: 1400px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.page-header h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0;
}

.btn-outline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  font-size: 13px;
  cursor: pointer;
}

.btn-outline:hover {
  background: #f8fafc;
  border-color: #94a3b8;
}

.main-layout {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 24px;
}

.left-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.approved-section,
.idle-section,
.application-section {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 20px;
}

.idle-card {
  border-left: 3px solid #94a3b8;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
}

.section-header h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}

.badge {
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
}

.loading,
.empty-state {
  text-align: center;
  color: #94a3b8;
  padding: 40px 20px;
  font-size: 14px;
}

.empty-state.small {
  padding: 20px;
  font-size: 13px;
}

.room-list,
.application-list,
.blacklist-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.room-card,
.application-card,
.blacklist-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
}

.room-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.room-name .name {
  font-weight: 600;
  color: #1e293b;
}

.time-badge {
  font-size: 11px;
  padding: 2px 8px;
  background: #dbeafe;
  color: #1d4ed8;
  border-radius: 4px;
}

.idle-badge {
  background: #f1f5f9;
  color: #64748b;
}

.room-meta,
.app-device,
.bl-device {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.app-time {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.countdown {
  color: #64748b;
}

.countdown.urgent {
  color: #ef4444;
  font-weight: 600;
}

.room-actions,
.app-actions {
  display: flex;
  gap: 6px;
}

.btn-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  color: #64748b;
  font-size: 14px;
  cursor: pointer;
}

.btn-icon:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.btn-icon.danger:hover {
  background: #fef2f2;
  color: #ef4444;
  border-color: #fecaca;
}

.btn-approve {
  padding: 6px 16px;
  background: #16a34a;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.btn-approve:hover {
  background: #15803d;
}

.btn-reject {
  padding: 6px 16px;
  background: #fff;
  color: #dc2626;
  border: 1px solid #fecaca;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.btn-reject:hover {
  background: #fef2f2;
}

.bl-info {
  flex: 1;
}

.bl-reason {
  font-size: 12px;
  color: #64748b;
  margin-top: 2px;
}

.bl-time {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.btn-unblock {
  padding: 6px 12px;
  background: #fff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}

.btn-unblock:hover {
  background: #eff6ff;
}

/* 弹窗样式 */
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  display: grid;
  place-items: center;
  z-index: 100;
}

.dialog {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: min(400px, calc(100vw - 40px));
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
}

.dialog h3 {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
}

.dialog-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.dialog-input:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.time-inputs {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 8px;
}

.time-field label {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

.time-hint {
  font-size: 12px;
  color: #94a3b8;
  margin: 0 0 16px;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}

.btn-secondary {
  padding: 8px 16px;
  background: #fff;
  color: #475569;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.btn-secondary:hover {
  background: #f8fafc;
}

.btn-primary {
  padding: 8px 16px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.btn-primary:hover {
  background: #1d4ed8;
}

.btn-danger {
  padding: 8px 16px;
  background: #dc2626;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.btn-danger:hover {
  background: #b91c1c;
}

.dialog-select {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 14px;
  background: #fff;
  cursor: pointer;
  box-sizing: border-box;
}

.dialog-select:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.dialog-select:disabled {
  background: #f1f5f9;
  color: #94a3b8;
  cursor: not-allowed;
}

.expiry-row {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
}

.expiry-field {
  flex: 1;
}

.expiry-field label {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}

@media (max-width: 900px) {
  .main-layout {
    grid-template-columns: 1fr;
  }
}
</style>
