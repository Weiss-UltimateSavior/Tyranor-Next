package com.core.input

/**
 * 宿主按键出口：canonical 键位 → 目标引擎。
 *
 * 每个引擎宿主实现一份薄适配（Web 见 [WebInputSink]；后续 KRKR/Siglus/Framebuffer 等
 * 按同一接口接入，注入 API 见《输入重映射与手柄映射方案》§3.3）。虚拟按键层与手柄
 * 路由器共用同一出口，保证两条输入链路语义一致。
 */
interface InputSink {

    /** 派发一次按下 / 抬起；实现方需自行忽略不支持的键位（动作段不经本方法）。 */
    fun send(key: Int, down: Boolean)

    /** 释放全部仍处于按下状态的键（退后台 / 页面重载 / 退出编辑时调用）。 */
    fun releaseAll()

    /**
     * 执行宿主侧动作（canonical 动作段，如 [CanonicalKeys.ACTION_SCREENSHOT]）。
     *
     * 与 [send] 的区别：动作没有按下/抬起语义，只在触发瞬间执行一次；
     * 返回宿主是否真的处理了该动作。默认不支持。
     */
    fun performAction(action: Int): Boolean = false
}
