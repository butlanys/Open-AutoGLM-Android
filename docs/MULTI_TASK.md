# Multi-Task Concurrent Processing / 多任务并发处理

This document describes the multi-task concurrent processing system that enables parallel app automation on virtual displays.

## 系统概述

多任务并发系统包含两个层次：

1. **智能编排层 (OrchestratorAgent)** - 使用高级模型进行任务分析、分解和决策
2. **执行层 (MultiTaskPhoneAgent)** - 管理虚拟显示器和并发执行

### 智能编排流程

```
用户任务 → 任务分析 → 子任务分解 → 并发执行 → 结果汇总 → 流程图渲染
              ↓           ↓          ↓         ↓
         高级模型判断   确定依赖   虚拟显示器  高级模型总结
         是否需要并发   关系       资源管理
```

### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                    MultiTaskPhoneAgent                       │
│  (协调多个并发任务的主控制器)                                    │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Task 1    │  │   Task 2    │  │   Task 3    │         │
│  │ Display #1  │  │ Display #2  │  │ Display #3  │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                  VirtualDisplayManager                       │
│  (管理虚拟显示器的创建、分配和销毁)                              │
├─────────────────────────────────────────────────────────────┤
│  • createVirtualDisplay()  - 创建虚拟显示器                   │
│  • destroyVirtualDisplay() - 销毁虚拟显示器                   │
│  • assignPackageToDisplay() - 分配应用到显示器                 │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│     DisplayInputManager      │   DisplayScreenshotService    │
│  (为特定显示器注入输入事件)     │   (从特定显示器捕获截图)         │
├─────────────────────────────────────────────────────────────┤
│  • tap(x, y, displayId)     │   • capture(displayId)        │
│  • swipe(..., displayId)    │   • captureWithDimensions()   │
│  • pressKey(key, displayId) │                               │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                  AppDisplayCompatibility                     │
│  (检测应用是否支持虚拟显示器)                                    │
├─────────────────────────────────────────────────────────────┤
│  • checkCompatibility()     - 检查清单属性                    │
│  • verifyRuntimeCompatibility() - 运行时验证                  │
│  • launchOnDisplay()        - 启动应用到指定显示器              │
└─────────────────────────────────────────────────────────────┘
```

## 工作原理

### 1. 虚拟显示器创建 (VirtualDisplayManager)

使用反射访问 Android 隐藏 API `DisplayManagerGlobal.createVirtualDisplay()`:

```kotlin
// 类似 scrcpy 的实现方式
val dmgInstance = DisplayManagerGlobal.getInstance()
val virtualDisplay = dmgInstance.createVirtualDisplay(
    name = "AutoGLM-Display",
    width = 1080,
    height = 2400,
    density = 420,
    flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or 
            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
)
```

### 2. 输入事件注入 (DisplayInputManager)

使用 `InputEvent.setDisplayId()` 将输入事件定向到特定显示器:

```kotlin
// 设置事件的目标显示器
val motionEvent = MotionEvent.obtain(...)
inputEventClass.getMethod("setDisplayId", Int::class.java)
    .invoke(motionEvent, displayId)

// 注入事件
inputManager.injectInputEvent(motionEvent, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
```

对于不支持反射方式的设备，回落到 shell 命令:
```bash
input -d <displayId> tap <x> <y>
```

### 3. 虚拟显示器截图 (DisplayScreenshotService)

优先使用 SurfaceControl 进行截图，回落到 screencap 命令:

```kotlin
// 方法1: SurfaceControl (API 31+)
val displayToken = SurfaceControl.getPhysicalDisplayToken(displayId)
SurfaceControl.screenshot(displayToken)

// 方法2: Shell命令回落
screencap -d <displayId> -p /path/to/screenshot.png
```

### 4. 应用兼容性检测 (AppDisplayCompatibility)

检测应用是否支持在虚拟显示器上运行:

```kotlin
// 检查清单属性
val supportsResize = appInfo.privateFlags and PRIVATE_FLAG_RESIZABLE_ACTIVITY != 0
val supportsMultiWindow = targetSdkVersion >= Build.VERSION_CODES.N

// 运行时验证 - 启动后检查实际运行位置
am start -n com.example.app/.MainActivity --display <displayId>
// 检查 dumpsys activity 确认运行在目标显示器
```

## 回落机制

当应用不支持虚拟显示器时，系统自动回落到主屏幕执行:

```kotlin
suspend fun executeTask(task: TaskDefinition) {
    val displayId = acquireVirtualDisplay()
    
    if (displayId > 0) {
        val launchResult = AppDisplayCompatibility.launchOnDisplay(
            packageName = task.targetApp,
            displayId = displayId
        )
        
        if (launchResult.fellBackToMainDisplay) {
            // 应用回落到主屏幕
            Log.w(TAG, "App fell back to main display")
            displayId = 0  // 切换到主屏幕执行
            
            // 标记为不支持虚拟显示器
            VirtualDisplayManager.markDisplayAsUnsupported(displayId)
        }
    }
    
    // 继续在 displayId 上执行任务...
}
```

## 使用方法

### 代码调用

```kotlin
// 1. 创建多任务配置
val config = MultiTaskConfig(
    maxConcurrentTasks = 3,
    enableVirtualDisplays = true,
    fallbackToSequential = true
)

// 2. 定义任务
val tasks = listOf(
    TaskDefinition(
        id = "task1",
        description = "打开微信发送消息给张三",
        targetApp = "com.tencent.mm"
    ),
    TaskDefinition(
        id = "task2",
        description = "在淘宝搜索商品",
        targetApp = "com.taobao.taobao"
    ),
    TaskDefinition(
        id = "task3",
        description = "查看微博热搜",
        targetApp = "com.sina.weibo"
    )
)

// 3. 执行多任务
val agent = MultiTaskPhoneAgent(modelConfig, config)
val results = agent.runTasks(tasks)
```

### UI 使用

1. 点击主屏幕右上角的多任务图标
2. 添加需要执行的任务列表
3. 设置最大并发数和是否启用虚拟显示器
4. 点击"开始执行"

## 已知限制

1. **Android 版本要求**: 虚拟显示器功能需要 Android 10+ (API 29+)
2. **应用兼容性**: 部分应用（如相机、AR应用）不支持虚拟显示器
3. **资源消耗**: 每个虚拟显示器会占用额外的系统资源
4. **输入限制**: 某些设备可能不支持向虚拟显示器注入输入事件

## 文件结构

```
app/src/main/java/com/autoglm/android/
├── display/
│   ├── VirtualDisplayManager.kt     # 虚拟显示器管理
│   ├── DisplayInputManager.kt       # 输入事件注入
│   ├── DisplayScreenshotService.kt  # 屏幕截图服务
│   └── AppDisplayCompatibility.kt   # 应用兼容性检测
├── agent/
│   ├── MultiTaskPhoneAgent.kt       # 多任务协调器
│   └── OrchestratorAgent.kt         # 智能编排器
└── ui/chat/
    ├── MultiTaskScreen.kt           # 多任务UI界面
    ├── MultiTaskViewModel.kt        # 多任务状态管理
    ├── OrchestratorScreen.kt        # 编排器UI界面
    └── OrchestratorViewModel.kt     # 编排器状态管理
```

## 智能编排器 (OrchestratorAgent)

智能编排器使用高级模型自动完成以下任务：

### 1. 任务分析

```kotlin
// 高级模型分析任务是否需要多任务并发
data class TaskAnalysis(
    val requiresMultiTask: Boolean,    // 是否需要多任务
    val reasoning: String,              // 分析理由
    val subTasks: List<SubTaskDefinition>,  // 分解的子任务
    val executionStrategy: ExecutionStrategy,  // 执行策略
    val estimatedComplexity: Int        // 预计复杂度
)
```

### 2. 执行策略

- **SEQUENTIAL**: 顺序执行
- **CONCURRENT**: 完全并发
- **HYBRID**: 混合模式（部分并发，部分顺序）
- **ADAPTIVE**: 根据运行时反馈动态调整

### 3. 动态决策

子任务完成后，编排器会：
1. 收集所有子任务结果
2. 调用高级模型判断下一步
3. 可能的决策：
   - `CONTINUE`: 继续到总结阶段
   - `SPAWN_NEW`: 创建新的子任务
   - `RETRY`: 重试失败的任务
   - `COMPLETE`: 任务完成
   - `ABORT`: 中止执行

### 4. 结果总结与可视化

```kotlin
data class OrchestratorResult(
    val success: Boolean,
    val summary: String,           // 高级模型生成的执行总结
    val flowDiagram: String,       // Mermaid格式的流程图
    val subTaskResults: List<SubTaskResult>,
    val executionTree: ExecutionNode?  // 执行树结构
)
```

## 使用方式

### 统一入口（推荐）

智能编排已集成到主对话界面：

1. **点击顶栏 ✨ 图标** 打开高级设置面板
2. **开启"启用智能编排"** 开关
3. 根据需要配置：
   - **虚拟显示器**: 如果设备支持，可启用多任务并发
   - **最大并发数**: 1-5个任务同时执行
   - **高级模型**: 可配置独立的编排器模型（API分离）
4. **正常输入任务** 即可，系统自动：
   - 分析任务是否需要多任务并发
   - 分解子任务并在虚拟显示器上执行
   - 展示实时进度，子任务可展开查看日志
   - 生成执行总结

### 虚拟显示器支持检测

系统会自动检测设备是否支持虚拟显示器：
- 需要 Android 10+ (API 29+)
- 需要 Shizuku 权限
- 如不支持，虚拟显示器选项会被禁用并显示提示

### 代码调用

```kotlin
// 使用智能编排器
val orchestrator = OrchestratorAgent(
    orchestratorModelConfig = advancedModelConfig,  // 高级模型配置
    workerModelConfig = standardModelConfig,        // 工作模型配置
    multiTaskConfig = MultiTaskConfig(
        maxConcurrentTasks = 3,
        enableVirtualDisplays = true
    ),
    onStateChange = { state -> /* 状态更新 */ },
    onSubTaskProgress = { progress -> /* 进度更新 */ }
)

val result = orchestrator.execute("帮我在微信、淘宝和微博上分别完成...")

// 结果包含：
// - result.summary: AI生成的执行总结
// - result.flowDiagram: Mermaid流程图
// - result.subTaskResults: 详细子任务结果
```

## 参考

- [scrcpy virtual_display.md](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual_display.md)
- [Android DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [Android InputManager](https://developer.android.com/reference/android/hardware/input/InputManager)
