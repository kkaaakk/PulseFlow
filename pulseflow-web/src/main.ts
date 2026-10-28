// 创建 Vue 应用，安装状态管理、路由和组件库后挂载控制台。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
