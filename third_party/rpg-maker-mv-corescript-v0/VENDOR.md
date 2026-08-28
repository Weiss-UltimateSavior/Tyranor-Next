# RPG Maker MV Corescript v0 — 來源說明（Tyranor-Next）

- **來源**：https://github.com/rpgtkoolmv/corescript（master，tag v1.3b，commit `182e314`）
- **版本**：v1.3b，`Utils.RPGMAKER_VERSION = "1.6.1"`（見 `js/rpg_core/Utils.js`），Pixi.js v4.5.4（`js/libs/pixi.js`）
- **授權**：MIT（見同目錄 `LICENSE`，原倉庫授權，兼容本項目 GPL-2.0 分發）
- **用途**：作為 Tyranor-Next 的 MV 行為基線與測試夾具，用於校準 `engine/src/main/assets/__rpg__.js` 與 `__rpgmaker_mod_core.js` 的 Hook 兼容面；**不打包進 APK runtime**，遊戲仍以自帶 `www/js/` 核心運行
- **對照**：
  - `Archeia/RPG-Maker-MV-Game-Player` 為同源 1.6.1 純淨鏡像，未另行 vendor，差異記錄於此
  - `siakoMobi/RPG-Maker-MV` 無 License，未引入
- **構建**：`npm install && npm run build`（`concat.js` 生成六合一 `rpg_*.js`），`js/` 下為拆分源碼
