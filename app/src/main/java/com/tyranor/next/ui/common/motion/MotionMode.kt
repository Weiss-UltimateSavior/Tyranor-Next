package com.tyranor.next.ui.common.motion

/**
 * 界面动效：本目录工具集的新动效（弹簧、Q 弹按压、入场、模糊替换、水平侧推……）是唯一的效果，没有「普通 / 增强」档位。
 * 系统关闭动画（动画时长倍率为 0）时由各入口自己瞬间完成，不在这里判断。
 *
 * [enhanced] 恒为 true，只为保留既有 `if (MotionMode.enhanced)` 判断点；新代码不要再引用它。
 * 下拉选择在这套效果里是无选框选择（按下即为选择，可滑选）：候选只改文字色，松手才提交；
 * 第三方 Miuix 弹层的进退场仍使用库本身的动画，不在这里反射修改库常量。
 */
object MotionMode {
    const val enhanced: Boolean = true
}
