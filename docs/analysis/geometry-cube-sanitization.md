# 几何清理：负尺寸 cube 与零面积 UV

来源：作者本地针对上游格式问题的一次排查笔记（未随仓库提供）。此文只取其中
**损坏几何的清理机制**这一条可复用结论，并补上 YSMU 侧的实现；上游逐条 bug 清单不在此复制。

## 为什么需要在烘焙前清理

BlockBench 的两类常见建模手法会让朴素烘焙器产出错误几何：

- **负尺寸 cube**（`size` 某一维为负）：用来做内翻壳 / 发光环 —— 一个略大的壳包住内部正向
  cube，靠背面在轮廓处露出形成描边。
- **零面积 UV**（`uv_size` 某一维为 0；作者有时干脆省略该键）：用来隐藏某个面。YSMU 只处理
  前者，见文末"已知限制"。

如果把负尺寸归一化成正尺寸，壳会变成实心并遮住内部 cube；如果照常烘焙零面积 UV，
GeckoLib 的 `GeoQuad` 会把它采样成单个 texel（通常是黑色），出现拉伸黑边。

## YSMU 的做法

所有格式（文件夹 / `.ysm` raw / 投掷物子模型）都经 `RawYsmModelAdapter.toLegacyModelData` →
`toGeometryJson`，在此统一调用 `sanitizeGeometryJson`：

1. **零面积 UV 面直接删除**：遍历每个 cube 的 `uv` 对象，`uv_size` 数组里任一维为 0 就把该
   面从 `uv` 里移除；`DEBUG_MODEL_LOAD && DEBUG_MODEL_PARSE` 时打印删除数。
2. **负尺寸 cube 保留并计数**，**不**在 JSON 里归一化（注释写明：归一化会让壳变实心）。
   归一化延后到构建期：`GeoCube.createFromPojoCube()` 取绝对值、调整 origin，并设置
   `hasNegSize` 标记。

## 负尺寸 cube 的渲染策略（`IGeoRenderer.renderBoneCubes`）

同一骨骼下若同时存在负尺寸与正尺寸 cube，分两趟绘制：

1. **先画负尺寸**：`GL_CULL_FACE` + `glCullFace(GL_FRONT)`，只画背面（离相机最远），写入
   远端深度；
2. **再画正尺寸**：正常深度测试（`GL_LEQUAL`），正尺寸 cube 的正面更近，通过测试并覆盖在
   中心；轮廓处没有正几何，负 cube 的背面环保留下来，成为描边。

若该骨骼只有正尺寸 cube，则单趟正常绘制，不做额外状态切换。

## 验证与排查

- `DEBUG_MODEL_LOAD && DEBUG_MODEL_PARSE` 时看 `sanitizeGeometryJson: removed N/M zero-uv
  faces` 与 `kept K negative-size cubes (hasNegSize)`：前者应只在确实使用隐藏手法的模型上非 0。
- 视觉验收只能在运行客户端：负尺寸壳的描边应可见，且不遮挡内部贴图；零面积 UV 的面不应出现
  黑边。给用户跑 `.\gradlew.bat runClient`，加载使用这些手法的模型观察即可。

## 已知限制 / 未验证

- `sanitizeGeometryJson` 只在 `uv_size` 是长度 ≥2 的数组且含 0 时删面；`uv_size` **整个键
  缺失**时该面被跳过（不删）。BlockBench 导出的零面积通常带 `uv_size:[0,0]`，所以够用，
  但"省略键"是否也该删面无定论。
- 负尺寸的报数只是日志；真正的归一化与 `hasNegSize` 在 `GeoCube`，两者若不一致只能靠视觉发现。
- 上述渲染策略的视觉效果未在本文写作时实机逐模型验证；只有代码路径可确认。
