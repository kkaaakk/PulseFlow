// 声明 Vite 环境变量和 Vue 文件模块类型，供类型检查使用。
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_DEMO_MODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
