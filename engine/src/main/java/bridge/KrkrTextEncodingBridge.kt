package bridge

/**
 * krkrsdl3 内核的文本编码桥（POC）。
 *
 * 设计意图：在 TJS 运行时初始化完成之后，通过 native 侧
dlopen("libkrkrsdl3.so") + dlsym("TVPSetDefaultReadEncoding") 设置默认读取编码，
 * 使日文 Shift-JIS / 汉化 GBK 脚本无需修改系统语言即可正确解码。
 *
 * 约束：
 * - 必须在 KRKRActivity 的 native 初始化之后调用（super.onCreate 之后）。
 * - 任何失败都只返回 false，由调用方记录日志，绝不阻断游戏启动。
 * - 不写入任何持久化文件，编码是进程级状态。
 */
object KrkrTextEncodingBridge {

    /**
     * 设置引擎默认读取编码。
     *
     * @param so 引擎库名（与 [NativeBridge.initialize] 的约定一致；null 表示自动选择）
     * @param encoding 编码名，如 SJIS / GBK / UTF-8；null 或空串表示不修改
     * @return true 表示已成功调用引擎接口；false 表示任一步失败
     */
    @JvmStatic
    external fun setDefaultReadEncoding(so: String?, encoding: String?): Boolean
}
