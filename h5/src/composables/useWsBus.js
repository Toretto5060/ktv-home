/**
 * WebSocket 全局事件总线（Composable）。
 *
 * 管理一条共享的 /ws 长连接，所有管理后台页面复用。
 * 对每个事件类型维护一个 Set<callback>，支持多个组件同时监听同一事件。
 * 连接断开时自动重连，指数退避最大 10 秒。
 *
 * WebSocket global event bus (Composable).
 *
 * Manages a single shared /ws long connection, reused by all admin pages.
 * Maintains a Map<eventType, Set<callback>> so multiple components can
 * listen to the same event simultaneously.
 * Auto-reconnects on disconnect with exponential backoff (max 10s).
 */
import { KtvSocket } from '../api/ws'

const BACKOFF = [1000, 2000, 5000, 10000]

const handlers = new Map()   // eventType -> Set<callback>
let socket = null
let retryCount = 0
let retryTimer = null
let closed = false

function ensureSocket() {
  if (socket) return
  closed = false
  socket = new KtvSocket({
    onEvent(type, payload) {
      const cbs = handlers.get(type)
      if (cbs) cbs.forEach(cb => cb(payload))
    },
    onStatus(connected) {
      if (connected) {
        retryCount = 0
      } else if (!closed) {
        scheduleReconnect()
      }
    }
  })
  socket.connect()
}

function scheduleReconnect() {
  if (closed) return
  clearTimeout(retryTimer)
  const delay = BACKOFF[Math.min(retryCount, BACKOFF.length - 1)]
  retryCount++
  retryTimer = setTimeout(ensureSocket, delay)
}

function disconnect() {
  closed = true
  clearTimeout(retryTimer)
  socket?.close()
  socket = null
}

/**
 * 监听指定事件类型的回调函数。
 *
 * @param {string}   eventType - WS 事件类型（如 'scan_progress'）
 * @param {Function} callback  - 事件到达时的回调，参数为 payload
 * @returns {Function} 取消监听函数，调用后不再接收该事件
 */
export function onWsEvent(eventType, callback) {
  ensureSocket()
  if (!handlers.has(eventType)) handlers.set(eventType, new Set())
  handlers.get(eventType).add(callback)
  return () => handlers.get(eventType)?.delete(callback)
}

/** 主动建立连接（首次调用时自动执行，可提前调用预热连接）。 */
export function connectWs() { ensureSocket() }

/** 主动断开连接并停止重连。页面卸载时不自动调用，生命周期由路由控制。 */
export function disconnectWs() { disconnect() }
