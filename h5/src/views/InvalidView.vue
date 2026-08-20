<template>
  <!--
    QR 失效专用视图：路由层会把所有无效访问重定向至此。
    此页面有意为空壳——真正的全屏不可关闭蒙版由 App.vue 统一渲染，
    保证在任何 H5 路由下都可见，且点击/手势/系统返回均无法关闭。
  -->
  <!--
    QR-invalidation exclusive view: router redirects every invalid access here.
    This page is intentionally an empty shell — the actual non-dismissible
    fullscreen overlay is rendered globally by App.vue so it remains visible on
    every H5 route and cannot be closed by tap / gesture / system back.
  -->
  <div class="invalid-shell" aria-hidden="true" />
</template>

<script setup>
// 该组件不挂载任何可交互元素；交互阻断全部交给 App.vue 蒙版。
// No interactive content here; all interaction blocking is handled by the App.vue overlay.
</script>

<style scoped>
.invalid-shell {
  display: none;
}
</style>