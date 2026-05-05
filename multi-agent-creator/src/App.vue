<script setup lang="ts">
import { provide } from 'vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

// 提供全局中文语言配置
provide('locale', zhCN)
</script>

<template>
  <a-config-provider :locale="zhCN">
    <BasicLayout />
  </a-config-provider>
</template>

<style>
/* 引入 Google Fonts - Geometric Modern */
@import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&family=Work+Sans:wght@300;400;500;600;700&display=swap');

/* 全局CSS变量 - 橙绿配色方案 */
:root {
  /* 品牌色 - 橙绿色调 */
  --color-primary: #467599;
  --color-primary-light: #F9F2EF;
  --color-primary-dark: #2c5282;
  --color-secondary: #0F172A;
  --color-secondary-light: #334155;

  /* 功能色 */
  --color-cta: #22C55E;
  --color-success: #22C55E;
  --color-warning: #FFF8E7;
  --color-error: #EF4444;
  --color-info: #ABD7FB;

  /* 中性色 */
  --color-background: #FFFFFF;
  --color-background-secondary: #F8FAFC;
  --color-background-tertiary: #F1F5F9;
  --color-background-dark: #0F172A;
  --color-text: #0F172A;
  --color-text-secondary: #475569;
  --color-text-muted: #94A3B8;
  --color-border: #E2E8F0;
  --color-border-light: #F1F5F9;

  /* 渐变 */
  --gradient-primary: linear-gradient(135deg, #467599 0%, #2c5282 100%);
  --gradient-hero: linear-gradient(180deg, #cce2f4 0%, #F0F8FF 100%);
  --gradient-hero-dark: linear-gradient(180deg, #2c5282 0%, #1a365d 100%);

  /* 纯色背景 */
  --bg-primary: #467599;
  --bg-primary-hover: #2c5282;
  --bg-dark: #0F172A;
  --bg-dark-hover: #1E293B;
  --bg-success: #22C55E;
  --bg-accent: #FFF8E7;

  /* 阴影 */
  --shadow-sm: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
  --shadow-xl: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  --shadow-card: 0 1px 3px rgba(0, 0, 0, 0.08), 0 1px 2px rgba(0, 0, 0, 0.04);
  --shadow-card-hover: 0 8px 24px rgba(70, 117, 153, 0.2);
  --shadow-blue: 0 4px 14px rgba(70, 117, 153, 0.3);

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-xl: 16px;
  --radius-2xl: 24px;
  --radius-full: 9999px;

  /* 过渡 */
  --transition-fast: 150ms ease-out;
  --transition-normal: 200ms ease-out;
  --transition-slow: 300ms ease-out;

  /* 毛玻璃 */
  --glass-bg: rgba(255, 255, 255, 0.9);
  --glass-bg-blue: rgba(70, 117, 153, 0.4);
  --glass-blur: blur(12px);
}

/* 全局样式优化 */
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  padding: 0;
  font-family: 'Work Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu', 'Helvetica Neue', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
  background-color: var(--color-background);
  color: var(--color-text);
  line-height: 1.6;
}

html {
  overflow-x: hidden;
}

h1, h2, h3, h4, h5, h6 {
  font-family: 'Outfit', sans-serif;
}

/* 全局链接样式 */
a {
  color: var(--color-text);
  text-decoration: none;
  transition: color var(--transition-fast);
}

a:hover {
  color: var(--color-primary);
}

/* 全局按钮增强 */
.ant-btn {
  transition: all var(--transition-normal);
  font-weight: 500;
  border-radius: var(--radius-md);
}

.ant-btn-primary {
  background: var(--bg-primary);
  border-color: var(--bg-primary);
  box-shadow: var(--shadow-blue);
}

.ant-btn-primary:not(:disabled):hover {
  background: var(--bg-primary-hover);
  border-color: var(--bg-primary-hover);
  box-shadow: 0 6px 20px rgba(70, 117, 153, 0.35);
}

/* 全局卡片增强 */
.ant-card {
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  transition: all var(--transition-normal);
}

/* 可点击元素统一cursor */
.clickable,
[role="button"],
button,
.ant-btn,
.ant-card.hoverable {
  cursor: pointer;
}

/* 选中状态 */
::selection {
  background: #FFF8E7;
  color: var(--color-text);
}

/* Ant Design 菜单覆盖 */
.ant-menu-horizontal > .ant-menu-item:hover::after,
.ant-menu-horizontal > .ant-menu-item-active::after,
.ant-menu-horizontal > .ant-menu-item-selected::after {
  border-bottom-color: var(--color-primary) !important;
}

.ant-menu-horizontal > .ant-menu-item:hover,
.ant-menu-horizontal > .ant-menu-item-active {
  color: var(--color-text) !important;
}

/* Ant Design 按钮 disabled 状态 */
.ant-btn:disabled {
  background: var(--color-border) !important;
  color: var(--color-text-muted) !important;
  border-color: var(--color-border) !important;
  box-shadow: none !important;
}

.ant-btn-primary:disabled {
  background: var(--color-border) !important;
  color: var(--color-text-muted) !important;
}

/* Steps 组件样式覆盖 */
.ant-steps .ant-steps-item-process .ant-steps-item-icon {
  background: var(--color-primary);
  border-color: var(--color-primary);
}

.ant-steps .ant-steps-item-finish .ant-steps-item-icon {
  border-color: var(--color-primary);
}

.ant-steps .ant-steps-item-finish .ant-steps-item-icon > .ant-steps-icon {
  color: var(--color-primary);
}

.ant-steps .ant-steps-item-finish > .ant-steps-item-container > .ant-steps-item-tail::after {
  background-color: var(--color-primary);
}

/* 输入框聚焦状态 */
.ant-input:focus,
.ant-input-focused,
.ant-input-affix-wrapper:focus,
.ant-input-affix-wrapper-focused {
  border-color: var(--color-primary) !important;
  box-shadow: 0 0 0 2px rgba(34, 110, 197, 0.1) !important;
}

.ant-input:hover {
  border-color: var(--color-primary-light);
}

/* 文本域 */
.ant-input-textarea-show-count::after {
  color: var(--color-text-muted);
}

/* Progress 组件 */
.ant-progress-bg {
  background: var(--gradient-primary);
}

/* Spin 组件 */
.ant-spin-dot-item {
  background-color: var(--color-primary);
}
</style>
