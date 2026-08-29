package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

public final class ArtemisActivityV5 extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // ar-test 提取的 Artemis Rev.3288 构建：作为 TyranorNext V3 单独加载。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS_V5);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}
