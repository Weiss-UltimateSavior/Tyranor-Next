/* 触屏手柄（issue #30/#35）：MV/MZ 共用。
 * 由 TyranoActivity 拼接进引擎 hook 注入；按键通过合成 keydown/keyup
 * （keyCode）派发，MV 与 MZ 的 Input 均读 keyCode，事件模型一致。
 *
 * 布局说明：所有尺寸/位置由 layout() 统一计算，锚定全视口
 * （window.innerWidth/innerHeight），portrait 模式下自然利用
 * letterbox 黑边空间；监听 resize/orientationchange 重排。
 * 布局完成后发布 window.__touchPadMetrics 并派发
 * tyranorpadlayout 事件，供修改器悬浮球避让动作键列。 */
window.addEventListener('load', () => {
  let padSize = 0
  let joyStickSR = 0
  let joyStickR = 0
  let joyStickCX = 0
  let joyStickCY = 0
  const allMargin = 10
  const lrMargin = 50
  let isKeysShown = true
  let useJoyStick = true
  let useDir8 = false
  const udlrEvents = {
    Up: false,
    Left: false,
    Right: false,
    Down: false
  }
  const joyStickStage = document.createElement('div')
  const joyStick = document.createElement('div')
  const actionsElement = document.createElement('div')
  const keySwitchElement = document.createElement('div')
  keySwitchElement.innerText = isKeysShown ? 'Hide' : 'Show'
  keySwitchElement.__pad = { id: 'btn.hide', text: 'Hide/Show' }
  const joyStickSwitchElement = document.createElement('div')
  joyStickSwitchElement.innerText = useJoyStick ? 'Button' : 'Stick'
  joyStickSwitchElement.__pad = { id: 'btn.stick', text: 'Button/Stick' }
  const dir8SwitchElement = document.createElement('div')
  dir8SwitchElement.innerText = useDir8 ? '4 Dir' : '8 Dir'
  dir8SwitchElement.__pad = { id: 'btn.dir8', text: '4/8 Dir' }
  const udlrElement = document.createElement('div')
  udlrElement.__pad = { id: 'dpad', text: '方向键' }
  const qwzxElement = document.createElement('div')
  qwzxElement.__pad = { id: 'qwzx', text: 'QWZX 键组' }
  joyStickStage.__pad = { id: 'joystick', text: '摇杆' }
  document.body.appendChild(actionsElement)
  actionsElement.appendChild(keySwitchElement)
  actionsElement.appendChild(joyStickSwitchElement)
  actionsElement.appendChild(dir8SwitchElement)
  document.body.appendChild(qwzxElement)
  document.body.appendChild(joyStickStage)
  joyStickStage.appendChild(joyStick)
  document.body.appendChild(udlrElement)

  // issue #30：自定义布局。window.__touchPadConfig 由 TyranoActivity 按游戏注入：
  // { buttons: { <id>: { x, y, scale, visible } } }
  // 坐标采用「归一化」（相对视口 0..1），横竖屏切换后按比例重映射，位置不漂移。
  const vw = () => window.innerWidth || 1
  const vh = () => window.innerHeight || 1
  // 归一化坐标统一钳制：允许小幅出界便于摆放半出屏按钮，但杜绝坐标漂离视口后无法拖回，
  // 也避免下次加载时被 x>1 的像素迁移启发式误判反复腐蚀
  const clampCoord = (v) => {
    const n = Number(v)
    return Math.max(-0.1, Math.min(1.1, isFinite(n) ? n : 0))
  }
  let padConfig = null
  try {
    const raw = window.__touchPadConfig
    if (raw && typeof raw === 'object' && raw.buttons) {
      // 一次性迁移：旧配置存的是视口像素（x>1），转成归一化
      const mig = raw.buttons
      const needsMig = Object.keys(mig).some(function (k) { var b = mig[k]; return b && (b.x > 1 || b.y > 1) })
      if (needsMig) {
        Object.keys(mig).forEach(function (k) {
          var b = mig[k]
          if (b && b.x != null) b.x = b.x / vw()
          if (b && b.y != null) b.y = b.y / vh()
        })
        // 迁移结果立即写回，避免未保存期间换朝向后以不同视口反复迁移、位置漂移
        if (window.TyranorTouchPadNative && window.TyranorTouchPadNative.saveConfig) {
          window.TyranorTouchPadNative.saveConfig(JSON.stringify(raw))
        }
      }
      padConfig = raw
    }
  } catch (e) { /* 忽略坏配置，回退默认 */ }
  const btnScale = (custom) => {
    const s = custom && custom.scale != null ? Number(custom.scale) : 1
    if (!isFinite(s) || s <= 0) return 1
    return Math.min(2, Math.max(0.01, s))
  }
  const parseHexColor = (hex) => {
    if (!hex || typeof hex !== 'string') return null
    const normalized = hex.trim().replace(/^#/, '')
    if (!/^[0-9a-fA-F]{6}$/.test(normalized)) return null
    return {
      r: parseInt(normalized.slice(0, 2), 16),
      g: parseInt(normalized.slice(2, 4), 16),
      b: parseInt(normalized.slice(4, 6), 16)
    }
  }
  const rgbaFromTheme = (hex, alpha, fallback) => {
    const rgb = parseHexColor(hex)
    if (!rgb) return fallback
    return `rgba(${rgb.r},${rgb.g},${rgb.b},${alpha})`
  }
  const padTheme = window.__touchPadTheme || {}
  const padPrimary = padTheme.primary || '#307DEF'
  const padOnPrimary = padTheme.onPrimary || '#FFFFFF'
  const padBgNormal = rgbaFromTheme(padPrimary, 0.22, 'rgba(48,125,239,0.22)')
  const padBgPressed = rgbaFromTheme(padPrimary, 0.46, 'rgba(48,125,239,0.46)')
  const padBgFaded = rgbaFromTheme(padPrimary, 0.16, 'rgba(48,125,239,0.16)')
  const padTextColor = rgbaFromTheme(padOnPrimary, 0.54, 'rgba(255,255,255,0.54)')
  const padShadowColor = rgbaFromTheme(padPrimary, 0.3, 'rgba(48,125,239,0.3)')
  const padFadedOutlineColor = rgbaFromTheme(padPrimary, 0.82, 'rgba(48,125,239,0.82)')

  const keyCodes = {
    Tab: 9,
    Enter: 13,
    Shift: 16,
    Ctrl: 17,
    Alt: 18,
    Esc: 27,
    Space: 32,
    PageUp: 33,
    PageDown: 34,
    Left: 37,
    Up: 38,
    Right: 39,
    Down: 40,
    Q: 81,
    W: 87,
    X: 88,
    Z: 90
  }
  const actionsBtns = [
    { id: 'pageup', text: 'PageUp', keyCode: keyCodes.PageUp },
    { id: 'pagedown', text: 'PageDown', keyCode: keyCodes.PageDown },
    { id: 'tab', text: 'Tab', keyCode: keyCodes.Tab },
    { id: 'alt', text: 'Alt', keyCode: keyCodes.Alt },
    { id: 'ctrl', text: 'Ctrl', keyCode: keyCodes.Ctrl },
    { id: 'shift', text: 'Shift', keyCode: keyCodes.Shift },
    { id: 'space', text: 'Space', keyCode: keyCodes.Space },
    { id: 'enter', text: 'Enter', keyCode: keyCodes.Enter },
    { id: 'esc', text: 'Esc', keyCode: keyCodes.Esc }
  ]
  const udlrBtns = [
    {
      keyCodes: [keyCodes.Up],
      style: {
        transform: 'translate(-50%,0%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '50%',
        top: '0%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left],
      style: {
        transform: 'translate(0%,-50%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        left: '0%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Right],
      style: {
        transform: 'translate(-100%,-50%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        borderTopLeftRadius: '50em',
        left: '100%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Down],
      style: {
        transform: 'translate(-50%,-100%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '50%',
        top: '100%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Up],
      style: {
        transform: 'translate(0%,0%)',
        borderBottomLeftRadius: '50em',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '0%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Down],
      style: {
        transform: 'translate(0%,-100%)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '0%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Up],
      style: {
        transform: 'translate(-100%,0%)',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Down],
      style: {
        transform: 'translate(-100%,-100%)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    }
  ]
  const qwzxBtns = [
    { id: 'q', text: 'Q', keyCode: keyCodes.Q, style: { transform: 'translate(0%,-50%)', left: '0%', top: '50%' } },
    { id: 'w', text: 'W', keyCode: keyCodes.W, style: { transform: 'translate(-50%,0%)', left: '50%', top: '0%' } },
    { id: 'z', text: 'Z', keyCode: keyCodes.Z, style: { transform: 'translate(-50%,-100%)', left: '50%', top: '100%' } },
    { id: 'x', text: 'X', keyCode: keyCodes.X, style: { transform: 'translate(-100%,-50%)', left: '100%', top: '50%' } }
  ]
  const commonStyle = {
    position: 'absolute',
    zIndex: '99999999'
  }
  const btnStyle = {
    ...commonStyle,
    background: padBgNormal,
    color: padTextColor,
    textAlign: 'center',
    boxShadow: `0 0 10px 0 ${padShadowColor}`
  }
  const textStyle = {
    ...commonStyle,
    color: padTextColor,
    transform: 'translate(-50%,-50%)',
    left: '50%',
    top: '50%'
  }
  const switchSize = () => `${padSize * 0.3}px`
  const actionBtnH = () => padSize * 0.125

  /* 全视口布局：始终使用整个屏幕，portrait 下按钮自然落入 letterbox 黑边。 */
  function gameRect() {
    return { rect: { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight } }
  }

  let actionEls = []
  const qwzxEls = []
  // 编辑模式把「透明」渲染为醒目的蓝色占位（仍可选中/调整）；普通模式才真正隐藏。
  // 用 CSS 类实现淡显，避免覆盖 btnStyle 的内联背景（否则普通按钮失去粉色）。
  function applyFade(el, custom) {
    var inv = custom && custom.visible === false
    if (editMode && inv) {
      el.style.display = 'block'
      el.classList.add('tm-pad-faded')
    } else {
      el.classList.remove('tm-pad-faded')
      if (inv) el.style.display = 'none'
      else el.style.display = el.__disp || ''
    }
  }
  // 中心定位统一入口：container 为元素定位参考容器（无则视口）。
  // 用 offsetWidth/offsetHeight（布局尺寸，不受 transform scale 影响）作为未缩放盒，
  // 使视觉中心恒定落在目标点，缩放/拖拽/微调全程不漂移。
  // custom.x/y 为归一化坐标（0..1 相对视口），此处换算回像素。
  function placeAtCenter(el, custom, scale, container) {
    var baseTransform = el.__baseTransform || ''
    if (custom && custom.x != null && custom.y != null) {
      var w = el.offsetWidth || 0
      var h = el.offsetHeight || 0
      var cl = container ? container.getBoundingClientRect().left : 0
      var ct = container ? container.getBoundingClientRect().top : 0
      el.style.transform = scale === 1 ? '' : 'scale(' + scale + ')'
      el.style.transformOrigin = 'center'
      el.style.left = (custom.x * vw() - w / 2 - cl) + 'px'
      el.style.top = (custom.y * vh() - h / 2 - ct) + 'px'
      el.style.right = 'auto'
    } else {
      el.style.transform = (scale === 1 ? '' : 'scale(' + scale + ')') + baseTransform
      el.style.transformOrigin = 'center'
      // 恢复默认定位（qwzx 用百分比；自定义清除后必须还原，否则残留在旧像素位）
      if (el.__baseLeft != null) el.style.left = el.__baseLeft
      if (el.__baseTop != null) el.style.top = el.__baseTop
      if (el.__baseLeft != null || el.__baseTop != null) el.style.right = ''
    }
  }
  function layout() {
    const g = gameRect()
    const r = g.rect
    padSize = Math.min(r.height * 0.4, r.width * 0.25)
    joyStickSR = padSize * 0.5
    joyStickR = joyStickSR * 0.4
    joyStickCX = r.left + joyStickSR + allMargin + lrMargin
    joyStickCY = r.top + r.height - joyStickSR - allMargin
    const activeCfg = (editMode ? editConfig : padConfig)
    // 编辑模式忽略「隐藏键盘」，让所有控件可选中/调整
    const showKeys = editMode || isKeysShown
    const switchTop = (i) => `${r.top + allMargin + i * (padSize * 0.3 + 5)}px`
    const switches = [
      { el: keySwitchElement, id: 'btn.hide', top: switchTop(0), disp: 'block' },
      { el: joyStickSwitchElement, id: 'btn.stick', top: switchTop(1), disp: showKeys ? 'block' : 'none' },
      { el: dir8SwitchElement, id: 'btn.dir8', top: switchTop(2), disp: showKeys ? 'block' : 'none' }
    ]
    switches.forEach(function (sw) {
      const custom = activeCfg && activeCfg.buttons && activeCfg.buttons[sw.id]
      const scale = btnScale(custom)
      Object.assign(sw.el.style, {
        ...btnStyle,
        width: switchSize(),
        height: switchSize(),
        lineHeight: switchSize(),
        borderRadius: '50em'
      })
      sw.el.style.left = (custom && custom.x != null ? 'auto' : `${r.left + allMargin}px`)
      sw.el.style.top = (custom && custom.y != null ? 'auto' : sw.top)
      sw.el.__disp = sw.disp
      applyFade(sw.el, custom)
      placeAtCenter(sw.el, custom, scale, null)
    })
    // 摇杆圆盘 / 方向键圆盘（可自定义，二者同一位置，useJoyStick 决定显示谁）
    const applyStage = function (el, id, active) {
      const custom = activeCfg && activeCfg.buttons && activeCfg.buttons[id]
      const scale = btnScale(custom)
      Object.assign(el.style, {
        ...commonStyle,
        boxShadow: `0 0 10px 0 ${padShadowColor}`,
        width: `${padSize}px`,
        height: `${padSize}px`,
        borderRadius: '50em'
      })
      el.__disp = active ? 'block' : 'none'
      if (editMode) {
        // 编辑模式：当前激活的实心显示，另一个以灰色虚线 ghost 占位（仍可选中/调整）
        el.style.display = 'block'
        el.classList.remove('tm-pad-faded')
        if (!active) {
          el.style.opacity = '0.4'
          el.style.outline = '2px dashed rgba(150,150,160,0.8)'
        } else {
          el.style.opacity = ''
          el.style.outline = ''
          applyFade(el, custom)
        }
      } else {
        el.style.opacity = ''
        el.style.outline = ''
        applyFade(el, custom)
      }
      // 自定义时用新中心定位；否则按默认锚点
      if (custom && custom.x != null && custom.y != null) {
        placeAtCenter(el, custom, scale, null)
      } else {
        el.style.transform = 'translate(0%,-100%)'
        el.style.left = `${r.left + allMargin + lrMargin}px`
        el.style.top = `${joyStickCY + joyStickSR}px`
        el.style.transformOrigin = 'center'
        if (scale !== 1) el.style.transform = 'translate(0%,-100%) scale(' + scale + ')'
      }
      if (id === 'joystick' && custom && custom.x != null && custom.y != null) {
        // 摇杆方向判定中心跟随新位置（归一化转像素）
        joyStickCX = custom.x * vw()
        joyStickCY = custom.y * vh()
      }
    }
    applyStage(joyStickStage, 'joystick', useJoyStick && isKeysShown)
    applyStage(udlrElement, 'dpad', !useJoyStick && isKeysShown)
    Object.assign(joyStick.style, {
      ...btnStyle,
      marginLeft: `${joyStickSR - joyStickR}px`,
      marginTop: `${joyStickSR - joyStickR}px`,
      width: `${2 * joyStickR}px`,
      height: `${2 * joyStickR}px`,
      borderRadius: '50em'
    })
    // QWZX 整组（可整体拖动/缩放/透明；默认锚定右下角菱形）
    const qcustom = activeCfg && activeCfg.buttons && activeCfg.buttons['qwzx']
    const qscale = btnScale(qcustom)
    Object.assign(qwzxElement.style, {
      ...commonStyle,
      width: `${padSize}px`,
      height: `${padSize}px`,
      borderRadius: '50em',
      boxShadow: `0 0 10px 0 ${padShadowColor}`
    })
    qwzxElement.__disp = showKeys ? 'block' : 'none'
    applyFade(qwzxElement, qcustom)
    if (qcustom && qcustom.x != null && qcustom.y != null) {
      placeAtCenter(qwzxElement, qcustom, qscale, null)
    } else {
      qwzxElement.style.transform = 'translate(-100%,-100%)'
      qwzxElement.style.left = `${r.left + r.width - allMargin}px`
      qwzxElement.style.top = `${r.top + r.height - allMargin}px`
      qwzxElement.style.transformOrigin = 'center'
      if (qscale !== 1) qwzxElement.style.transform = 'translate(-100%,-100%) scale(' + qscale + ')'
    }
    const btnW = padSize * 0.5
    const pitch = actionBtnH() + 5
    actionEls.forEach((el, i) => {
      const meta = el.__pad || {}
      const custom = activeCfg && activeCfg.buttons && activeCfg.buttons[meta.id]
      const scale = btnScale(custom)
      Object.assign(el.style, {
        ...btnStyle,
        width: `${btnW}px`,
        height: `${actionBtnH()}px`,
        lineHeight: `${actionBtnH()}px`,
        borderRadius: '50em',
        right: 'auto'
      })
      el.style.left = (custom && custom.x != null ? 'auto' : `${r.left + r.width - allMargin - btnW}px`)
      el.style.top = (custom && custom.y != null ? 'auto' : `${r.top + allMargin + i * pitch}px`)
      el.__disp = showKeys ? 'block' : 'none'
      el.style.transformOrigin = 'center'
      applyFade(el, custom)
      placeAtCenter(el, custom, scale, null)
    })
    // 发布动作键列区域，供修改器悬浮球避让（见 __rpgmaker_mod_ui.js）
    window.__touchPadMetrics = {
      actionLeft: r.left + r.width - allMargin - btnW,
      actionTop: r.top + allMargin,
      actionBottom: r.top + allMargin + actionEls.length * pitch - 5
    }
    window.dispatchEvent(new Event('tyranorpadlayout'))
  }

  const setKeyDownColor = (e) => {
    e.style.background = padBgPressed
  }
  const setKeyUpColor = (e) => {
    e.style.background = padBgNormal
  }
  const startKeyEvent = (e, keyCode, keyEvent) => {
    const evtObj = document.createEvent('UIEvents')
    Object.defineProperty(evtObj, 'keyCode', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    Object.defineProperty(evtObj, 'which', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    evtObj.initUIEvent(keyEvent, true, true, window, 1)
    evtObj.keyCodeVal = keyCode
    e.dispatchEvent(evtObj)
  }
  const setEventStart = (e, keyCodes) => {
    const press = (evt) => {
      // 编辑/预览模式也要拦截事件冒泡到游戏（游戏在 document 监听），仅跳过按键派发
      evt.stopPropagation()
      evt.preventDefault()
      if (editMode || previewMode) return
      setKeyDownColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keydown')
      })
    }
    // 触摸路径 preventDefault 会阻止浏览器合成鼠标事件，不会双触发；
    // 虚拟鼠标（__tyranor_mouse）只派发 MouseEvent，靠这里的 mousedown 命中
    e.addEventListener('touchstart', press)
    e.addEventListener('mousedown', press)
  }
  const setEventMove = (e) => {
    e.addEventListener('touchmove', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
    })
    e.addEventListener('mousemove', (evt) => { evt.stopPropagation() })
  }
  const setEventEnd = (e, keyCodes) => {
    const release = (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      if (editMode || previewMode) return
      setKeyUpColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keyup')
      })
    }
    e.addEventListener('touchend', release)
    e.addEventListener('mouseup', release)
  }
  const getDistance = (x1, y1, x2, y2) => {
    return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
  }
  const getAngle = (x1, y1, x2, y2) => {
    let angle = 180 * Math.atan((y1 - y2) / (x1 - x2)) / Math.PI
    if (x1 >= x2 && y1 < y2) angle += 360
    if (x1 < x2) angle += 180
    return angle
  }
  const endMoveEvent = () => {
    for (const key in udlrEvents) {
      if (udlrEvents[key]) {
        udlrEvents[key] = false
        startKeyEvent(joyStick, keyCodes[key], 'keyup')
      }
    }
  }
  const startMoveEvent = (touch) => {
    if (getDistance(touch.clientX, touch.clientY, joyStickCX, joyStickCY) > 20) {
      const angle = getAngle(touch.clientX, touch.clientY, joyStickCX, joyStickCY)
      const events = useDir8 ? {
        Up: angle > 202.5 && angle < 337.5,
        Right: (angle >= 0 && angle < 67.5) || (angle < 360 && angle > 292.5),
        Down: angle > 22.5 && angle < 157.5,
        Left: angle > 112.5 && angle < 247.5
      } : {
        Up: angle > 225 && angle < 315,
        Right: (angle >= 0 && angle < 45) || (angle < 360 && angle > 315),
        Down: angle > 45 && angle < 135,
        Left: angle > 135 && angle < 225
      }
      for (const key in events) {
        if (events[key] && !udlrEvents[key]) {
          udlrEvents[key] = true
          startKeyEvent(joyStick, keyCodes[key], 'keydown')
        }
        if (!events[key] && udlrEvents[key]) {
          udlrEvents[key] = false
          startKeyEvent(joyStick, keyCodes[key], 'keyup')
        }
      }
    } else {
      endMoveEvent()
    }
  }
  // 开关通用绑定：press/release 与按钮一致走 touchstart/touchend +
  // mousedown/mouseup 双路径（虚拟鼠标靠后者命中）
  const bindSwitch = (el, onRelease) => {
    el.addEventListener('touchstart', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      if (editMode || previewMode) return
      setKeyDownColor(el)
    })
    el.addEventListener('mousedown', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      if (editMode || previewMode) return
      setKeyDownColor(el)
    })
    setEventMove(el)
    const release = (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      if (editMode || previewMode) return
      setKeyUpColor(el)
      onRelease()
    }
    el.addEventListener('touchend', release)
    el.addEventListener('mouseup', release)
  }
  bindSwitch(keySwitchElement, () => {
    isKeysShown = !isKeysShown
    keySwitchElement.innerText = isKeysShown ? 'Hide' : 'Show'
    layout()
  })
  bindSwitch(joyStickSwitchElement, () => {
    useJoyStick = !useJoyStick
    joyStickSwitchElement.innerText = useJoyStick ? 'Button' : 'Stick'
    layout()
  })
  bindSwitch(dir8SwitchElement, () => {
    useDir8 = !useDir8
    dir8SwitchElement.innerText = useDir8 ? '4 Dir' : '8 Dir'
    for (let i = 4; i < udlrElement.children.length; i++) {
      udlrElement.children.item(i).style.display = useDir8 ? 'block' : 'none'
    }
  })
  const joyStart = (x, y) => {
    joyStick.style.left = `${x - joyStickCX}px`
    joyStick.style.top = `${y - joyStickCY}px`
    startMoveEvent({ clientX: x, clientY: y })
  }
  const joyMove = (x, y) => {
    const subLen = getDistance(x, y, joyStickCX, joyStickCY)
    if (subLen > joyStickSR) {
      joyStick.style.left = `${(x - joyStickCX) * joyStickSR / subLen}px`
      joyStick.style.top = `${(y - joyStickCY) * joyStickSR / subLen}px`
    } else {
      joyStick.style.left = `${x - joyStickCX}px`
      joyStick.style.top = `${y - joyStickCY}px`
    }
    startMoveEvent({ clientX: x, clientY: y })
  }
  const joyEnd = () => {
    joyStick.style.left = '0px'
    joyStick.style.top = '0px'
    endMoveEvent()
  }
  joyStickStage.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    if (editMode || previewMode) return
    const touch = evt.targetTouches[0]
    joyStart(touch.clientX, touch.clientY)
  })
  joyStickStage.addEventListener('touchmove', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    if (editMode || previewMode) return
    const touch = evt.targetTouches[0]
    joyMove(touch.clientX, touch.clientY)
  })
  joyStickStage.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    if (editMode || previewMode) return
    joyEnd()
  })
  // 虚拟鼠标拖拽摇杆：光标按下后跟随 mousemove
  let joyMouseDown = false
  joyStickStage.addEventListener('mousedown', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    if (editMode || previewMode) return
    joyMouseDown = true
    joyStart(evt.clientX, evt.clientY)
  })
  window.addEventListener('mousemove', (evt) => {
    if (editMode || previewMode) return
    if (!joyMouseDown) return
    evt.stopPropagation()
    joyMove(evt.clientX, evt.clientY)
  })
  window.addEventListener('mouseup', (evt) => {
    if (editMode || previewMode) return
    if (!joyMouseDown) return
    evt.stopPropagation()
    joyMouseDown = false
    joyEnd()
  })
  actionsBtns.forEach(it => {
    const childElement = document.createElement('div')
    actionsElement.appendChild(childElement)
    childElement.innerText = it.text
    childElement.__pad = { id: it.id, text: it.text, defaultKeyCode: it.keyCode }
    actionEls.push(childElement)
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
  })
  udlrBtns.forEach(it => {
    const childElement = document.createElement('div')
    udlrElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...btnStyle,
      width: '33%',
      height: '33%',
      ...it.style
    })
    setEventStart(childElement, it.keyCodes)
    setEventMove(childElement)
    setEventEnd(childElement, it.keyCodes)
  })
  qwzxBtns.forEach(it => {
    const childElement = document.createElement('div')
    qwzxElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...btnStyle,
      width: '40%',
      height: '40%',
      borderRadius: '50em',
      ...it.style
    })
    childElement.__pad = { id: it.id, text: it.text, defaultKeyCode: it.keyCode }
    childElement.__baseTransform = it.style.transform || ''
    childElement.__baseLeft = it.style.left || ''
    childElement.__baseTop = it.style.top || ''
    qwzxEls.push(childElement)
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
    const tElement = document.createElement('div')
    childElement.appendChild(tElement)
    Object.assign(tElement.style, textStyle)
    tElement.innerText = it.text
  })

  // ================= issue #30：游戏内按钮自定义（进入键盘映射） =================
  var editMode = false
  var selectedId = null
  var editConfig = null        // 编辑中的工作副本 { buttons: { id: {...} } }
  var overlay = null
  var panel = null
  var panelGrab = null         // 顶层控制框拖拽状态
  var NUDGE = 4
  var allEditableEls = []      // [{ el, id, group }]
  // 非数组型可编辑元素：3 个开关 + 摇杆外框 + 方向键圆盘 + QWZX 整组（视口锚定）
  var fixedEls = [
    { el: keySwitchElement, id: 'btn.hide', group: 'switch' },
    { el: joyStickSwitchElement, id: 'btn.stick', group: 'switch' },
    { el: dir8SwitchElement, id: 'btn.dir8', group: 'switch' },
    { el: joyStickStage, id: 'joystick', group: 'stage' },
    { el: udlrElement, id: 'dpad', group: 'stage' },
    { el: qwzxElement, id: 'qwzx', group: 'stage' }
  ]
  // 进入编辑时需要抬到遮罩层(100000000)之上的容器（其子元素的可点区域被其堆叠上下文包含）
  var editContainers = [actionsElement, qwzxElement, joyStickStage, udlrElement]
  var editContainersZ = []
  // 编辑模式下拦截输入冒泡到 document（游戏在 document 监听，点按会穿透到游戏）。
  // 按钮/开关/摇杆自身 handler 已改为始终 stopPropagation；这里兜底遮罩、面板与容器空白区。
  var editBlockers = []
  var EDIT_INPUT_EVENTS = ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mousemove', 'mouseup', 'pointerdown', 'pointermove', 'pointerup', 'click', 'contextmenu']
  function installEditBlock(el) {
    EDIT_INPUT_EVENTS.forEach(function (name) {
      var fn = function (ev) {
        ev.stopPropagation()
        // 允许输入框聚焦/输入，不 preventDefault
        var t = ev.target
        if (!t || (t.tagName !== 'INPUT' && t.tagName !== 'TEXTAREA')) ev.preventDefault()
      }
      el.addEventListener(name, fn)
      editBlockers.push({ el: el, name: name, fn: fn })
    })
  }
  function removeEditBlock() {
    editBlockers.forEach(function (b) {
      if (b.el && b.el.removeEventListener) b.el.removeEventListener(b.name, b.fn)
    })
    editBlockers = []
  }

  function collectEditable() {
    allEditableEls = []
    actionEls.forEach(el => { if (el.__pad) allEditableEls.push({ el: el, id: el.__pad.id, group: 'action' }) })
    fixedEls.forEach(f => { if (f.el.__pad) allEditableEls.push({ el: f.el, id: f.id, group: f.group }) })
  }

  function defaultButtonFor(id) {
    var label = null
    var found = actionsBtns.concat(qwzxBtns).filter(function (b) { return b.id === id })[0]
    if (found) { label = found.text }
    else {
      for (var i = 0; i < fixedEls.length; i++) {
        if (fixedEls[i].id === id) { label = fixedEls[i].el.__pad.text; break }
      }
    }
    if (!label) return null
    return { x: null, y: null, scale: 1, visible: true, label: label }
  }

  function ensureButtonCfg(id) {
    if (!id) return null
    if (!editConfig) editConfig = { buttons: {} }
    if (!editConfig.buttons[id]) editConfig.buttons[id] = defaultButtonFor(id)
    return editConfig.buttons[id]
  }

  function panelRect(el) {
    var r = el.getBoundingClientRect()
    return { cx: r.left + r.width / 2, cy: r.top + r.height / 2, w: r.width, h: r.height }
  }

  function styleButton(el, highlight) {
    var meta = el.__pad || {}
    var custom = editConfig && editConfig.buttons && editConfig.buttons[meta.id]
    var hidden = custom && custom.visible === false
    if (highlight) {
      el.style.outline = hidden ? '3px dashed #4a9eff' : '3px solid #4a9eff'
      el.style.boxShadow = hidden ? '0 0 0 2px rgba(74,158,255,0.5), inset 0 0 0 40px rgba(74,158,255,0.35)' : '0 0 0 2px rgba(74,158,255,0.5)'
    } else {
      el.style.outline = ''
      el.style.boxShadow = ''
    }
    el.style.zIndex = (highlight ? '100000002' : '100000001')
  }

  // 统一由 layout() 负责所有可编辑元素的定位/缩放/透明（编辑与普通模式一致）
  function refreshEditConfig() {
    layout()
    if (selectedId) setSelected(selectedId)
  }

  function setSelected(id) {
    selectedId = id
    allEditableEls.forEach(function (entry) { styleButton(entry.el, entry.id === id) })
    var custom = id && editConfig.buttons && editConfig.buttons[id]
    var nameLabel = panel && panel.querySelector('.tm-pad-selname')
    if (nameLabel) {
      var el = getElById(id)
      nameLabel.textContent = el && el.__pad ? el.__pad.text + ' (' + id + ')' : '—'
    }
    updatePanelControls(custom)
  }

  function updatePanelControls(custom) {
    var scalePct = Math.round(btnScale(custom) * 100)
    var scaleLabel = panel && panel.querySelector('.tm-pad-scaleval')
    if (scaleLabel) scaleLabel.textContent = scalePct + '%'
    var slider = panel && panel.querySelector('.tm-pad-scale')
    if (slider) slider.value = String(scalePct)
    var visLabel = panel && panel.querySelector('.tm-pad-vislabel')
    if (visLabel) visLabel.textContent = custom && custom.visible === false ? '（透明·编辑中蓝显）' : ''
    var transBtn = panel && panel.querySelector('[data-act="transparent"]')
    if (transBtn) {
      var sel = selectedId != null
      transBtn.style.opacity = sel ? '1' : '0.4'
      transBtn.style.pointerEvents = sel ? 'auto' : 'none'
      transBtn.textContent = custom && custom.visible === false ? '恢复显示' : '透明'
    }
  }

  function saveAndExit(force) {
    if (!editMode) return
    var small = allEditableEls.filter(function (entry) {
      var c = editConfig.buttons && editConfig.buttons[entry.id]
      return c && c.visible !== false && btnScale(c) < 0.5
    })
    var doSave = function () {
      padConfig = editConfig
      // 持久化到原生
      try {
        if (window && window.TyranorTouchPadNative && window.TyranorTouchPadNative.saveConfig) {
          window.TyranorTouchPadNative.saveConfig(JSON.stringify(padConfig))
        }
      } catch (e) { /* 忽略 */ }
      exitEdit()
      layout()
    }
    // 有 <50% 缩放的按钮时，用面板内联确认（WebView 的 window.confirm 不可靠）
    if (!force && small.length > 0) {
      showSaveConfirm(small.length, doSave)
    } else {
      doSave()
    }
  }

  // 面板内联确认行
  function showSaveConfirm(count, onConfirm) {
    if (!panel) return
    var row = panel.querySelector('[data-act="confirmrow"]')
    if (!row) return
    var msg = panel.querySelector('[data-act="confirmmsg"]')
    if (msg) msg.textContent = count + ' 个按钮缩放小于 50%，确认保存？'
    row.style.display = 'flex'
    var back = panel.querySelector('[data-act="confirmback"]')
    if (back) back.onpointerdown = function (ev) { ev.stopPropagation(); ev.preventDefault(); row.style.display = 'none' }
    var yes = panel.querySelector('[data-act="confirmyes"]')
    if (yes) yes.onpointerdown = function (ev) { ev.stopPropagation(); ev.preventDefault(); onConfirm() }
  }

  // 长按连发活动终止器登记表：exitEdit 统一 clearTimeout，
  // 防止面板被销毁/触摸流被打断后 setTimeout 链孤儿化永续空转
  var holdStops = []
  // 长按连发：按住持续触发回调，松开停止（用于 +/- 缩放与微调）
  function holdRepeat(el, fn, interval) {
    var timer = null
    var doFn = function (ev) {
      ev.stopPropagation()
      ev.preventDefault()
      if (timer != null) return // 重入保护：第二根手指同按不再叠加新的连发链
      fn()
      timer = setTimeout(function tick() {
        fn()
        timer = setTimeout(tick, interval || 100)
      }, 350)
    }
    var stop = function (ev) {
      if (ev) { ev.stopPropagation(); ev.preventDefault() }
      if (timer) { clearTimeout(timer); timer = null }
    }
    el.addEventListener('pointerdown', doFn)
    el.addEventListener('pointerup', stop)
    el.addEventListener('pointercancel', stop)
    el.addEventListener('pointerleave', stop)
    holdStops.push(stop)
  }
  // 面板动作键：pointerdown 触发（WebView 触摸路径 click 不可靠），并吞掉合成 click 防双触发
  function bindTap(el, fn) {
    el.addEventListener('pointerdown', function (ev) {
      ev.stopPropagation()
      ev.preventDefault()
      fn()
    })
    el.addEventListener('click', function (ev) {
      ev.stopPropagation()
      ev.preventDefault()
    })
  }
  // 从滑杆值(1..200)设置选中元素缩放
  function scaleFromValue(pct) {
    if (!selectedId) return
    var c = ensureButtonCfg(selectedId)
    c.scale = Math.max(1, Math.min(200, Math.round(pct))) / 100
    refreshEditConfig()
    setSelected(selectedId)
  }
  function nudgeSelected(dx, dy) {
    if (!selectedId) return
    var c = ensureButtonCfg(selectedId)
    var el = getElById(selectedId)
    if (!el) return
    var pr = el.getBoundingClientRect()
    c.x = clampCoord((pr.left + pr.width / 2 + dx) / vw())
    c.y = clampCoord((pr.top + pr.height / 2 + dy) / vh())
    refreshEditConfig()
    setSelected(selectedId)
  }
  function scaleSelected(delta) {
    if (!selectedId) return
    var c = ensureButtonCfg(selectedId)
    var cur = Math.round(btnScale(c) * 100)
    var next = Math.max(1, Math.min(200, cur + delta))
    c.scale = next / 100
    refreshEditConfig()
    setSelected(selectedId)
  }
  function buildPanel() {
    // 每次进入编辑都重建面板：清掉上一轮会话的登记，避免 holdStops 无限增长
    holdStops = []
    var style = document.getElementById('tm-pad-edit-css')
    if (!style) {
      style = document.createElement('style')
      style.id = 'tm-pad-edit-css'
      style.textContent =
        '.tm-pad-editable{cursor:move}' +
        '.tm-pad-faded{opacity:0.45;filter:grayscale(1);background:' + padBgFaded + ';outline:1px dashed ' + padFadedOutlineColor + '}' +
        '.tm-pad-btn{background:#3a3a4a;color:#fff;border:1px solid #666;border-radius:8px;padding:8px 12px;cursor:pointer;font:14px sans-serif;touch-action:none}' +
        '.tm-pad-btn.primary{background:' + padPrimary + ';color:' + padOnPrimary + '}' +
        '.tm-pad-btn:disabled{opacity:0.4}' +
        '.tm-pad-scalebtn{background:#3a3a4a;color:#fff;border:1px solid #666;border-radius:6px;width:34px;height:34px;cursor:pointer;font:18px sans-serif;touch-action:none}' +
        '.tm-pad-nudge{background:#3a3a4a;color:#fff;border:1px solid #666;width:34px;height:34px;cursor:pointer;font:16px sans-serif;touch-action:none;position:absolute}'
      document.body.appendChild(style)
    }
    panel = document.createElement('div')
    panel.className = 'tm-pad-editpanel'
    panel.style.cssText = [
      'position:fixed', 'left:50%', 'top:10px', 'transform:translateX(-50%)',
      'z-index:100000003', 'background:rgba(20,20,30,0.94)', 'color:#fff',
      'border:1px solid #555', 'border-radius:14px', 'padding:14px 18px',
      'max-width:min(340px,92vw)', 'max-height:82vh', 'overflow-y:auto',
      'box-shadow:0 4px 20px rgba(0,0,0,0.5)',
      'user-select:none', 'font:14px/1.5 sans-serif', 'touch-action:none'
    ].join(';')
    panel.innerHTML =
      '<div data-act="mainview">' +
      // 标题 + 缩放（滑杆 + +/- 按钮）+ 透明
      '<div class="tm-pad-title" style="font-weight:bold;margin-bottom:8px;cursor:move">键盘映射 · 拖动控制或按钮调整（拖动框可移动）</div>' +
      '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px">' +
      '<span>缩放</span>' +
      '<button class="tm-pad-scalebtn" data-scale="-1">－</button>' +
      '<input type="range" min="1" max="200" value="100" class="tm-pad-scale" style="flex:1;min-width:90px;touch-action:none">' +
      '<button class="tm-pad-scalebtn" data-scale="+1">＋</button>' +
      '<span class="tm-pad-scaleval" style="min-width:42px;text-align:center">100%</span>' +
      '</div>' +
      '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px">' +
      '<button class="tm-pad-btn" data-act="transparent">透明</button>' +
      '<span class="tm-pad-vislabel" style="color:#ff9"></span>' +
      '</div>' +
      // 微调（角落十字，位于面板右下）
      '<div style="display:flex;align-items:center;justify-content:space-between;margin-top:8px">' +
      '<span style="opacity:0.8">选择元素：<span class="tm-pad-selname" style="color:#7fd0ff">—</span></span>' +
      '<div class="tm-pad-cross" style="position:relative;width:102px;height:102px">' +
      '<button class="tm-pad-nudge" data-d="up" style="left:34px;top:0">↑</button>' +
      '<button class="tm-pad-nudge" data-d="left" style="left:0;top:34px">←</button>' +
      '<button class="tm-pad-nudge" data-d="right" style="left:68px;top:34px">→</button>' +
      '<button class="tm-pad-nudge" data-d="down" style="left:34px;top:68px">↓</button>' +
      '</div>' +
      '</div>' +
      // 内联确认行（默认隐藏）
      '<div data-act="confirmrow" style="display:none;align-items:center;gap:8px;margin-top:10px;flex-wrap:wrap;background:rgba(74,158,255,0.15);border:1px solid #4a9eff;border-radius:8px;padding:8px">' +
      '<span data-act="confirmmsg" style="flex:1;min-width:140px"></span>' +
      '<button class="tm-pad-btn primary" data-act="confirmyes">确认保存</button>' +
      '<button class="tm-pad-btn" data-act="confirmback">返回</button>' +
      '</div>' +
      '<div style="display:flex;align-items:center;gap:8px;margin-top:10px;flex-wrap:wrap">' +
      '<button class="tm-pad-btn primary" data-act="save">保存并退出</button>' +
      '<button class="tm-pad-btn" data-act="presets">预设</button>' +
      '<button class="tm-pad-btn" data-act="reset">恢复默认</button>' +
      '<button class="tm-pad-btn" data-act="cancel">取消</button>' +
      '</div>' +
      '</div>' +
      // 预设管理视图（默认隐藏）
      '<div data-act="presetview" style="display:none;margin-top:4px">' +
      '<div style="display:flex;align-items:center;justify-content:space-between">' +
      '<strong>预设管理（最多 10 个）</strong>' +
      '<button class="tm-pad-btn" data-act="presetback">返回</button>' +
      '</div>' +
      '<div data-act="presetlist" style="margin-top:6px;max-height:38vh;overflow-y:auto"></div>' +
      '<div style="display:flex;gap:8px;margin-top:8px;align-items:center">' +
      '<input class="tm-pad-input" data-act="presetname" maxlength="12" placeholder="预设名（≤12 字）" style="flex:1;min-width:0;background:#2a2a38;color:#fff;border:1px solid #555;border-radius:6px;padding:8px;font:14px sans-serif;user-select:text">' +
      '<button class="tm-pad-btn primary" data-act="presetsave">保存为新预设</button>' +
      '</div>' +
      '<div data-act="presetmsg" style="color:#7fd0ff;margin-top:4px;min-height:18px"></div>' +
      '</div>'
    document.body.appendChild(panel)
    var title = panel.querySelector('.tm-pad-title')
    panel.addEventListener('pointerdown', function (ev) {
      if (ev.target !== title) return
      var cr = panel.getBoundingClientRect()
      panelGrab = { sx: ev.clientX, sy: ev.clientY, ox: cr.left, oy: cr.top }
    })
    panel.addEventListener('pointermove', function (ev) {
      if (!panelGrab) return
      panel.style.transform = 'none'
      panel.style.left = (panelGrab.ox + (ev.clientX - panelGrab.sx)) + 'px'
      panel.style.top = (panelGrab.oy + (ev.clientY - panelGrab.sy)) + 'px'
    })
    panel.addEventListener('pointerup', function () { panelGrab = null })
    panel.addEventListener('pointercancel', function () { panelGrab = null })
    // 缩放 +/-
    panel.querySelectorAll('.tm-pad-scalebtn').forEach(function (b) {
      holdRepeat(b, function () {
        var sign = b.getAttribute('data-scale') === '+1' ? 1 : -1
        scaleSelected(sign)
      })
    })
    // 缩放滑杆：pointer 手动拖拽（面板 touch-action:none 下原生滑杆触摸拖动失效）
    var slider = panel.querySelector('.tm-pad-scale')
    var sliderDrag = null
    var sliderSet = function (ev) {
      var r = slider.getBoundingClientRect()
      var pct = Math.max(1, Math.min(200, Math.round((ev.clientX - r.left) / r.width * 199) + 1))
      slider.value = String(pct)
      scaleFromValue(pct)
    }
    slider.addEventListener('pointerdown', function (ev) {
      ev.stopPropagation()
      ev.preventDefault()
      sliderDrag = true
      try { slider.setPointerCapture(ev.pointerId) } catch (e) {}
      sliderSet(ev)
    })
    slider.addEventListener('pointermove', function (ev) {
      if (!sliderDrag) return
      sliderSet(ev)
    })
    slider.addEventListener('pointerup', function () { sliderDrag = false })
    slider.addEventListener('pointercancel', function () { sliderDrag = false })
    slider.addEventListener('input', function () { if (!sliderDrag) scaleFromValue(Number(slider.value)) })
    // 十字微调（pointer 路径，触摸可靠）
    panel.querySelectorAll('.tm-pad-nudge').forEach(function (b) {
      holdRepeat(b, function () {
        var d = b.getAttribute('data-d')
        var dxy = { up: [0, -NUDGE], down: [0, NUDGE], left: [-NUDGE, 0], right: [NUDGE, 0] }[d]
        nudgeSelected(dxy[0], dxy[1])
      })
    })
    // 透明/恢复（pointer 路径）
    bindTap(panel.querySelector('[data-act="transparent"]'), function () {
      if (!selectedId) return
      var c = ensureButtonCfg(selectedId)
      if (selectedId === 'btn.hide' && c.visible !== false) {
        // 隐藏键自身不允许透明：一旦真隐藏，普通模式下游戏内无出路（修改器关闭时连 FAB 都没有）
        var hl = panel && panel.querySelector('.tm-pad-vislabel')
        if (hl) hl.textContent = '隐藏键不可透明'
        return
      }
      if (c.visible === false) c.visible = true
      else c.visible = false
      refreshEditConfig()
      setSelected(selectedId)
    })
    bindTap(panel.querySelector('[data-act="save"]'), function () { saveAndExit(false) })
    bindTap(panel.querySelector('[data-act="reset"]'), function () {
      collectEditable()
      allEditableEls.forEach(function (entry) {
        var meta = entry.el.__pad || {}
        if (editConfig.buttons) delete editConfig.buttons[meta.id]
      })
      refreshEditConfig()
      if (selectedId) setSelected(selectedId)
    })
    bindTap(panel.querySelector('[data-act="cancel"]'), function () {
      editConfig = padConfig ? JSON.parse(JSON.stringify(padConfig)) : { buttons: {} }
      exitEdit()
      layout()
    })
    // 预设
    bindTap(panel.querySelector('[data-act="presets"]'), function () { showPresetView() })
    bindTap(panel.querySelector('[data-act="presetback"]'), function () { showMainView() })
    bindTap(panel.querySelector('[data-act="presetsave"]'), function () { onPresetSaveClick() })
  }

  function getElById(id) {
    for (var i = 0; i < allEditableEls.length; i++) if (allEditableEls[i].id === id) return allEditableEls[i].el
    return null
  }

  function enterEdit() {
    if (editMode) return
    if (previewMode) exitPreview()
    setFabHidden(true)
    editMode = true
    // 置位后任何一步失败都必须回滚，否则虚拟键失灵且 FAB 已隐藏、游戏内无法自救
    try {
      startEditSession()
    } catch (e) {
      console.error('enterEdit failed', e)
      try { exitEdit() } catch (_ignored) {}
    }
  }

  function startEditSession() {
    editConfig = padConfig ? JSON.parse(JSON.stringify(padConfig)) : { buttons: {} }
    overlay = document.createElement('div')
    overlay.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;' +
      'background:rgba(0,0,0,0.65);z-index:100000000;touch-action:none'
    document.body.appendChild(overlay)
    buildPanel()
    // 拦截编辑模式下冒泡到游戏的输入（遮罩/面板/容器空白区）
    removeEditBlock()
    installEditBlock(overlay)
    installEditBlock(panel)
    editContainers.forEach(function (el) { installEditBlock(el) })
    // 收集全部按钮并打上可编辑类
    collectEditable()
    // 抬升容器 zIndex，使 qwzx/摇杆/方向键等堆叠上下文内容可点（高于遮罩层）
    editContainersZ = editContainers.map(function (el) { var z = el.style.zIndex; el.style.zIndex = '100000005'; return z })
    allEditableEls.forEach(function (entry) {
      var el = entry.el
      el.__group = entry.group
      el.classList.add('tm-pad-editable')
      el.style.pointerEvents = 'auto'
    })
    // 先按编辑态布局一次：强制隐藏控件（如默认摇杆模式下的方向键、被 Hide 的键盘）
    // 渲染出实际矩形，避免随后采集 getBoundingClientRect 得到 (0,0) 并写入持久化配置
    refreshEditConfig()
    allEditableEls.forEach(function (entry) {
      var meta = entry.el.__pad || {}
      var c = editConfig.buttons[meta.id]
      if (!c || c.x == null || c.y == null) {
        var r = entry.el.getBoundingClientRect()
        if (!r.width && !r.height) return
        editConfig.buttons[meta.id] = c || defaultButtonFor(meta.id)
        editConfig.buttons[meta.id].x = clampCoord((r.left + r.width / 2) / vw())
        editConfig.buttons[meta.id].y = clampCoord((r.top + r.height / 2) / vh())
      }
    })
    refreshEditConfig()
    // 选中第一个可见按钮方便直接调整；无可见元素时确保控件处于禁用状态
    var first = allEditableEls.filter(function (e) { var c = editConfig.buttons[e.id]; return !c || c.visible !== false })[0]
    setSelected(first ? first.id : null)
    attachEditDrag()
  }

  // 幂等的拖拽绑定：重复进入也只会绑定一次；退出时按同一闭包精确解绑（排除监听泄漏）
  function bindDrag(el) {
    if (el.__dragBound) return
    var drag = null
    function onDown(ev) {
      if (!editMode) return
      ev.stopPropagation()
      ev.preventDefault()
      var meta = el.__pad || {}
      setSelected(meta.id)
      var c = ensureButtonCfg(meta.id)
      if (!c) return
      var r = el.getBoundingClientRect()
      c.x = clampCoord((r.left + r.width / 2) / vw())
      c.y = clampCoord((r.top + r.height / 2) / vh())
      drag = { id: meta.id, ox: ev.clientX, oy: ev.clientY, bx: c.x, by: c.y, moved: false }
    }
    function onMove(ev) {
      if (!editMode || !drag) return
      var dx = ev.clientX - drag.ox
      var dy = ev.clientY - drag.oy
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 8) return
      drag.moved = true
      var c = ensureButtonCfg(drag.id)
      if (!c) return
      c.x = clampCoord(drag.bx + dx / vw())
      c.y = clampCoord(drag.by + dy / vh())
      refreshEditConfig()
      setSelected(drag.id)
    }
    function onUp(ev) {
      if (ev) { ev.preventDefault(); ev.stopPropagation() }
      drag = null
    }
    el.__dragDown = onDown
    el.__dragMove = onMove
    el.__dragUp = onUp
    el.addEventListener('pointerdown', onDown)
    el.addEventListener('pointermove', onMove)
    el.addEventListener('pointerup', onUp)
    el.__dragBound = true
  }
  function unbindDrag(el) {
    if (!el.__dragBound) return
    el.removeEventListener('pointerdown', el.__dragDown)
    el.removeEventListener('pointermove', el.__dragMove)
    el.removeEventListener('pointerup', el.__dragUp)
    el.__dragDown = null
    el.__dragMove = null
    el.__dragUp = null
    el.__dragBound = false
    el.style.outline = ''
  }
  function attachEditDrag() {
    collectEditable()
    allEditableEls.forEach(function (entry) { bindDrag(entry.el) })
  }

  function exitEdit() {
    editMode = false
    removeEditBlock()
    blurPresetInput()
    // 终止所有长按连发链（可能在面板销毁后因触摸流被打断而残留）
    holdStops.forEach(function (stop) { stop() })
    holdStops = []
    setFabHidden(false)
    collectEditable()
    allEditableEls.forEach(function (entry) {
      unbindDrag(entry.el)
      entry.el.classList.remove('tm-pad-editable')
      entry.el.style.pointerEvents = ''
    })
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay)
    overlay = null
    if (panel && panel.parentNode) panel.parentNode.removeChild(panel)
    panel = null
    panelGrab = null
    selectedId = null
    editContainers.forEach(function (el, i) {
      el.style.zIndex = editContainersZ[i] || ''
    })
    layout()
  }

  // 恢复默认布局：清空配置。非编辑态即时持久化；编辑态仅重置工作副本，与整体草稿模型
  // 保持一致——立即持久化会让随后的「取消」无法回退到用户原有布局
  function resetToDefaults() {
    if (editMode) {
      editConfig = { buttons: {} }
      collectEditable()
      refreshEditConfig()
      if (selectedId) setSelected(selectedId)
    } else {
      padConfig = { buttons: {} }
      try {
        if (window && window.TyranorTouchPadNative && window.TyranorTouchPadNative.saveConfig) {
          window.TyranorTouchPadNative.saveConfig('{}')
        }
      } catch (e) { /* 忽略 */ }
      layout()
    }
  }

  // ================= 预设（保存/载入/重命名/删除，最多 10 个，名称 ≤12 字） =================
  // 用 null-prototype 对象，避免用户命名 __proto__/constructor 污染原型
  var presets = Object.create(null)
  var presetMode = false    // 是否在预设管理视图
  var presetRename = null   // 正在重命名的旧名称
  var PRESET_MAX = 10
  // 预览模式（只读查看预设布局）
  var previewMode = false
  var previewRestore = null
  var previewBanner = null
  var previewOverlay = null

  function sanitizeName(name) {
    return String(name || '').replace(/\s+/g, ' ').trim().slice(0, 12)
  }
  function loadPresetsRaw() {
    presets = Object.create(null)
    try {
      if (window && window.TyranorTouchPadNative && window.TyranorTouchPadNative.getPresets) {
        var raw = window.TyranorTouchPadNative.getPresets()
        if (raw) {
          var parsed = JSON.parse(raw)
          if (parsed && typeof parsed === 'object') {
            Object.keys(parsed).forEach(function (k) { presets[k] = parsed[k] })
          }
        }
      }
    } catch (e) { /* 忽略 */ }
    return presets
  }
  function persistPresets() {
    try {
      if (window && window.TyranorTouchPadNative && window.TyranorTouchPadNative.savePresets) {
        window.TyranorTouchPadNative.savePresets(JSON.stringify(presets))
      }
    } catch (e) { /* 忽略 */ }
  }
  function currentLayoutConfig() {
    var cfg = (editMode && editConfig) ? editConfig : padConfig
    return cfg ? JSON.parse(JSON.stringify(cfg)) : { buttons: {} }
  }
  function savePreset(name) {
    name = sanitizeName(name)
    if (!name) return { ok: false, msg: '预设名不能为空' }
    var names = Object.keys(presets)
    if (!(name in presets) && names.length >= PRESET_MAX) {
      return { ok: false, msg: '预设已达上限（' + PRESET_MAX + ' 个），请先删除或重命名' }
    }
    presets[name] = currentLayoutConfig()
    persistPresets()
    return { ok: true, msg: '已保存预设「' + name + '」' }
  }
  function loadPreset(name) {
    if (!(name in presets)) return { ok: false, msg: '预设「' + name + '」不存在' }
    var cfg = JSON.parse(JSON.stringify(presets[name]))
    if (editMode) {
      // 编辑中仅替换工作副本，由「保存并退出」统一持久化，取消可回退
      editConfig = JSON.parse(JSON.stringify(cfg))
      refreshEditConfig()
    } else {
      padConfig = cfg
      try {
        if (window && window.TyranorTouchPadNative && window.TyranorTouchPadNative.saveConfig) {
          window.TyranorTouchPadNative.saveConfig(JSON.stringify(cfg))
        }
      } catch (e) { /* 忽略 */ }
      layout()
    }
    return { ok: true, msg: '已载入预设「' + name + '」' }
  }
  function renamePreset(oldName, newName) {
    oldName = sanitizeName(oldName)
    newName = sanitizeName(newName)
    if (!(oldName in presets)) return { ok: false, msg: '预设不存在' }
    if (!newName) return { ok: false, msg: '预设名不能为空' }
    if (newName !== oldName && newName in presets) return { ok: false, msg: '预设名「' + newName + '」已存在' }
    if (newName !== oldName) {
      presets[newName] = presets[oldName]
      delete presets[oldName]
    }
    persistPresets()
    return { ok: true, msg: '已重命名为「' + newName + '」' }
  }
  function deletePreset(name) {
    name = sanitizeName(name)
    if (!(name in presets)) return { ok: false, msg: '预设不存在' }
    delete presets[name]
    persistPresets()
    return { ok: true, msg: '已删除「' + name + '」' }
  }

  // ---------- 预设管理 UI ----------
  function setPresetMsg(text) {
    var m = panel && panel.querySelector('[data-act="presetmsg"]')
    if (m) m.textContent = text || ''
  }
  function showMainView() {
    presetMode = false
    presetRename = null
    blurPresetInput()
    var main = panel && panel.querySelector('[data-act="mainview"]')
    var pv = panel && panel.querySelector('[data-act="presetview"]')
    if (main) main.style.display = ''
    if (pv) pv.style.display = 'none'
  }
  function showPresetView() {
    presetMode = true
    presetRename = null
    blurPresetInput()
    var main = panel && panel.querySelector('[data-act="mainview"]')
    var pv = panel && panel.querySelector('[data-act="presetview"]')
    if (main) main.style.display = 'none'
    if (pv) pv.style.display = 'block'
    var input = panel && panel.querySelector('[data-act="presetname"]')
    if (input) input.value = ''
    var saveBtn = panel && panel.querySelector('[data-act="presetsave"]')
    if (saveBtn) saveBtn.textContent = '保存为新预设'
    setPresetMsg('')
    loadPresetsRaw()
    renderPresetList()
  }
  function renderPresetList() {
    var list = panel && panel.querySelector('[data-act="presetlist"]')
    if (!list) return
    list.innerHTML = ''
    var names = Object.keys(presets).sort()
    if (names.length === 0) {
      list.innerHTML = '<div style="opacity:0.6;padding:6px 0">暂无预设。输入名称可保存当前布局。</div>'
      return
    }
    names.forEach(function (name) {
      var row = document.createElement('div')
      row.style.cssText = 'display:flex;align-items:center;gap:6px;padding:6px 0;border-bottom:1px solid #333'
      var label = document.createElement('span')
      label.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap'
      label.textContent = name
      row.appendChild(label)
      var load = document.createElement('button')
      var rn = document.createElement('button')
      var del = document.createElement('button')
      load.className = rn.className = del.className = 'tm-pad-btn'
      load.textContent = '载入'
      rn.textContent = '重命名'
      del.textContent = '删除'
      load.style.cssText = rn.style.cssText = del.style.cssText = 'padding:3px 8px;font:12px sans-serif'
      row.appendChild(load)
      row.appendChild(rn)
      row.appendChild(del)
      list.appendChild(row)
      bindTap(load, function () { applyPresetAction('load', name) })
      bindTap(rn, function () { startPresetRename(name) })
      bindTap(del, function () { applyPresetAction('delete', name) })
    })
  }
  function startPresetRename(name) {
    presetRename = name
    var input = panel && panel.querySelector('[data-act="presetname"]')
    if (input) input.value = name
    var saveBtn = panel && panel.querySelector('[data-act="presetsave"]')
    if (saveBtn) saveBtn.textContent = '重命名'
    setPresetMsg('正在重命名「' + name + '」')
  }
  function onPresetSaveClick() {
    var input = panel && panel.querySelector('[data-act="presetname"]')
    var name = sanitizeName(input ? input.value : '')
    if (!name) { setPresetMsg('请输入预设名'); return }
    var res = presetRename ? renamePreset(presetRename, name) : savePreset(name)
    setPresetMsg(res.msg)
    blurPresetInput()
    if (res.ok) {
      presetRename = null
      if (input) input.value = ''
      var saveBtn = panel && panel.querySelector('[data-act="presetsave"]')
      if (saveBtn) saveBtn.textContent = '保存为新预设'
      renderPresetList()
    }
  }
  function applyPresetAction(action, name) {
    var res
    if (action === 'load') {
      res = loadPreset(name)
      if (res.ok) { showMainView(); return }
    } else if (action === 'delete') {
      res = deletePreset(name)
    }
    setPresetMsg(res.msg)
    blurPresetInput()
    renderPresetList()
  }

  // 隐藏/恢复修改器悬浮球（编辑/预览时不可点开，保持模态）
  function setFabHidden(hidden) {
    try {
      var fab = document.getElementById('tyranor-mod-launcher')
      if (fab) fab.style.display = hidden ? 'none' : ''
    } catch (e) { /* 忽略 */ }
  }

  // 强制收起软键盘（编辑面板 preventDefault 会阻止点击外部自动失焦；
  // 仅 blur 在部分 WebView 不可靠，配合 readonly 技巧强制收起）
  function blurPresetInput() {
    var inp = panel && panel.querySelector('[data-act="presetname"]')
    if (!inp) return
    try { inp.setAttribute('readonly', 'readonly') } catch (e) {}
    if (inp.blur) inp.blur()
    setTimeout(function () {
      try { inp.removeAttribute('readonly') } catch (e) {}
    }, 150)
  }

  // ---------- 预设预览（只读，只能退出） ----------
  function previewPreset(name) {
    name = sanitizeName(name)
    loadPresetsRaw()
    if (!(name in presets)) return { ok: false, msg: '预设「' + name + '」不存在' }
    if (editMode) return { ok: false, msg: '请先退出键盘映射再预览' }
    if (!previewMode) {
      previewMode = true
      setFabHidden(true)
      previewRestore = padConfig
      // 建场失败必须回滚：否则 previewMode 卡在 true 且 FAB 已隐藏，游戏内没有退出入口
      try {
        startPreviewSession(name)
      } catch (e) {
        console.error('previewPreset failed', e)
        try { exitPreview() } catch (_ignored) {}
        return { ok: false, msg: '预览开启失败' }
      }
    } else {
      var nm = previewBanner && previewBanner.querySelector('.tm-pad-preview-name')
      if (nm) nm.textContent = '预览：' + name
    }
    padConfig = JSON.parse(JSON.stringify(presets[name]))
    layout()
    return { ok: true, msg: '' }
  }

  // 建立预览遮罩与只读横幅；异常由 previewPreset 兜底回滚（原 padConfig 已存入 previewRestore）
  function startPreviewSession(name) {
    previewOverlay = document.createElement('div')
    previewOverlay.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;' +
      'background:rgba(0,0,0,0.25);z-index:100000000;touch-action:none'
    document.body.appendChild(previewOverlay)
    previewBanner = document.createElement('div')
    previewBanner.className = 'tm-pad-preview-banner'
    previewBanner.style.cssText = [
      'position:fixed', 'left:50%', 'top:14px', 'transform:translateX(-50%)',
      'z-index:100000010', 'background:rgba(20,20,30,0.95)', 'color:#fff',
      'border:1px solid #4a9eff', 'border-radius:12px', 'padding:8px 14px',
      'display:flex', 'align-items:center', 'gap:10px',
      'font:14px/1.4 sans-serif', 'box-shadow:0 4px 20px rgba(0,0,0,0.5)',
      'user-select:none', 'touch-action:none', 'max-width:92vw'
    ].join(';')
    var label = document.createElement('span')
    label.className = 'tm-pad-preview-name'
    label.style.cssText = 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis'
    label.textContent = '预览：' + name
    previewBanner.appendChild(label)
    var btn = document.createElement('button')
    btn.className = 'tm-pad-btn primary'
    btn.textContent = '退出预览'
    btn.style.cssText = 'padding:6px 12px;font:13px sans-serif'
    previewBanner.appendChild(btn)
    document.body.appendChild(previewBanner)
    bindTap(btn, function () { exitPreview() })
    removeEditBlock()
    installEditBlock(previewOverlay)
    installEditBlock(previewBanner)
  }

  function exitPreview() {
    if (!previewMode) return
    previewMode = false
    if (previewOverlay && previewOverlay.parentNode) previewOverlay.parentNode.removeChild(previewOverlay)
    previewOverlay = null
    if (previewBanner && previewBanner.parentNode) previewBanner.parentNode.removeChild(previewBanner)
    previewBanner = null
    removeEditBlock()
    setFabHidden(false)
    padConfig = previewRestore
    previewRestore = null
    layout()
  }

  // 暴露 API 供修改器悬浮球（__rpgmaker_mod_ui.js）与外部调用
  window.__touchPad = {
    enterEdit: enterEdit,
    exitEdit: exitEdit,
    saveAndExit: saveAndExit,
    resetToDefaults: resetToDefaults,
    isEditMode: function () { return editMode },
    getConfig: function () { return padConfig ? JSON.stringify(padConfig) : '' },
    savePreset: savePreset,
    loadPreset: loadPreset,
    renamePreset: renamePreset,
    deletePreset: deletePreset,
    listPresets: function () { loadPresetsRaw(); return Object.keys(presets) },
    previewPreset: previewPreset,
    exitPreview: exitPreview,
    isPreview: function () { return previewMode }
  }

  loadPresetsRaw()
  layout()
  window.addEventListener('resize', function () {
    if (editMode) refreshEditConfig()
    else layout()
  })
  // 旋转后画布矩形可能滞后于视口变化，延迟一帧再排
  window.addEventListener('orientationchange', () => setTimeout(function () {
    if (editMode) refreshEditConfig()
    else layout()
  }, 150))
})
