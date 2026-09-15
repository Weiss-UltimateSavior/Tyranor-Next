package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

public final class ArtemisActivityV6 extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // 官方拆解 Rev.3294（含 E-mote）：作为 V6 单独加载，避免覆盖现有 V1/V2/V3/V4/V5。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS_V6);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}
