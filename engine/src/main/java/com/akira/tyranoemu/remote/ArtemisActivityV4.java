package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

public final class ArtemisActivityV4 extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // yrrw_1 提取的 Artemis 构建：作为 V4 单独加载，避免覆盖现有 V1/V2/V3。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS_V4);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}
