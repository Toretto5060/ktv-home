/**
 * 二维码有效性管理 store。
 * 路由守卫 / 视图组件均通过它查询与设置当前 QR 失效状态。
 *
 * QR code validity store.
 * Both the router guard and view components read/write invalidation state via this store.
 */
import { defineStore } from 'pinia'

export const useQrGuardStore = defineStore('qrGuard', {
  state: () => ({
    // true 表示二维码已失效，应触发全屏不可关闭弹窗并阻断点歌路由
    // true means the QR is invalid: show non-dismissible overlay and block song-request routes
    invalid: false,
    // 失效原因（用于弹窗文案展示）
    // Reason string displayed in the overlay
    reason: ''
  }),
  actions: {
    /**
     * 标记二维码失效。可由路由守卫或扫码失败回调调用。
     *
     * Mark QR as invalid. Called from router guard or scan-failure callbacks.
     * @param {string} [reason=''] - 失效说明 / invalidation reason
     */
    markInvalid(reason = '') {
      this.invalid = true
      this.reason = reason || '二维码无效或已过期'
    },

    /**
     * 清除失效状态（保留给未来"重新扫码成功"场景，目前主要用作占位 API）。
     *
     * Clear invalidation state (placeholder for future "rescan success" flows).
     */
    reset() {
      this.invalid = false
      this.reason = ''
    }
  }
})