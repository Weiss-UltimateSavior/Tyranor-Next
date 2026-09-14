package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativePluginConstants;
import com.core.nativeplugin.NativePluginManager;

/**
 * 自研 clean-room Artemis 兼容内核（artemis-compat 仓库构建产物）宿主。
 *
 * 独立进程（:artemis.clean）避免与官方 revision 的 native 全局状态相互污染；
 * 严格自研：启动侧不传 ARTEMIS_AUTO_FALLBACK，不参与官方版本回退链。
 */
public final class ArtemisActivityClean extends ArtemisLauncherBaseActivity {
 @Override public void loadEngineLibrary() {
   // bootstrap loader 在 super.onCreate() 阶段已 dlopen 真实库并转发 ANativeActivity_onCreate
   // 启动引擎主体；此处再 System.load 同一 so，使 ART 将其真实 native 方法登记到 native
   // library 表，否则 Java native 调用会 UnsatisfiedLinkError。
   String lib = NativePluginManager.artemisLibPath(this, NativePluginConstants.LIB_ARTEMIS_CLEAN);
   if (lib == null) throw new IllegalStateException("Artemis 外置插件未就绪，请重新导入插件");
   System.load(lib);
 }
}
