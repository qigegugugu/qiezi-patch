# 茄子快门 · Lumen Dark 设计稿 v1.7

针对现有 App（apktool 反编译版 `com.lifeipeng.magicaca`）做的整界面视觉重构提案。
基准画布 **360 × 640 dp**，与真机密度无关，全部落在 `dp / sp` 上。

## 文件

| 文件 | 用途 |
|---|---|
| `redesign.html` | 完整设计稿：四屏（延时 / 曲线 / 拍摄中 / 抽屉）+ 设计决策 + 令牌 + Android 映射表 + 动效规格 |
| `tokens.css` | 可复用令牌（Web/CSS 版），同时是 Android 侧 `colors.xml` / `dimens.xml` 的取值依据 |

## 四屏

1. **延时摄影（待机）** — 参数收进两张玻璃卡（间隔/次数/曝光 + 拍摄时长/视频长度），机内参数三连胶囊，底部滑轨 + 88dp 快门
2. **曲线摄影** — 霓虹折线图（辉光 + 5.5% 白栅格 + 跟手读数气泡），三连统计胶囊
3. **拍摄中** — 倒计时独占一行（30/600），快门环转红呼吸脉冲
4. **抽屉** — 玻璃面板，右侧 28dp 圆角，当前项左侧 2.5dp 青色光条

## 六个关键判断

1. **底不用 #121212 → #1E1E1E 平铺**：中灰会把玻璃洗平，层级只剩描边在撑。改近黑 `#08090C` + 右上角一团青色天光。
2. **层级靠字重差，不靠线**：标签 300 / 数值 600 / 单位再降一档 11px。
3. **强调色只给"正在发生的事"**：青 `#38E1F2` 只出现在曲线、运行态、当前值单位；其余全白/灰。
4. **快门是唯一的重物**：88dp 双环，外环 conic 扫光、内盘磨砂、aperture 30dp 带辉光。
5. **Picker 用滚轮不用弹窗填数字**：透明度 + 缩放模拟景深，不做逐行模糊（Android 会掉帧）。
6. **不做真模糊**：Android 没有页面级 blur API，玻璃 = 半透明渐变 + 1dp 高光边 + 大圆角。

## 落地到 APK 的顺序

前提：`output/com.lifeipeng.magicaca/raw` 已解包；流程 `EXTRA_DEX2=... tools/rebuild.sh`。

1. **改色板**（影响面最大、风险最低）：`res/values/colors.xml` 里 `qz_bg / qz_label / qz_label2 / qz_label3 / shutter` 换成新值，新增 `qz_accent / qz_bg_top / qz_bloom`。
2. **加背景**：`res/drawable/qz_page_bg.xml`（垂直渐变）+ `qz_bloom.xml`（radial），页面根布局换成渐变。
3. **玻璃卡**：`qz_lg_card.xml` 圆角 24 → 22，渐变起点 7.5%。
4. **行组件**：`comp_input_time.xml` / `comp_input_number.xml` 的标签字号 15sp、数值 22sp、单位 11sp（本次 1.6.28 已做行式重排，只需调字号与色值）。
5. **曲线辉光**：`ViewCurve.onDraw` 在画实线前用 `BlurMaskFilter(8dp, NORMAL)` 再画一遍半透明描边。
6. **快门**：按 88dp 重绘 `circle_normal.png` / `circle_selected.png`（PIL 4x 超采样 + LANCZOS）。

改完务必：`aapt2 dump badging` 核版本 + `tools/class_diff.py` 核类名集合没丢。

## 尚未落地的部分

- 滚轮 Picker：工程里已有 `com.aigestudio.wheelpicker.WheelPicker`（B 门曝光在用），抽成 `qz_picker.xml` 复用，不需要引新库。
- 动效：进页 8dp 上浮 + 40ms 错开、按压 scale .96、录制环脉冲 2.4s——都是纯 `ObjectAnimator`，不涉及布局改动。
- 抽屉的玻璃面在 Android 上会退化成「深色渐变 + 高光边」，视觉与稿接近但不等于模糊。
