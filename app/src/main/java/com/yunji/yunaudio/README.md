# Android 音频录制器 - 源码包

## 📦 文件列表

```
AudioRecorderSimple/
├── MainActivity.kt                          # 主Activity（传统View）
├── OpusDecoder.kt                          # Opus解码器
├── AndroidManifest.xml                     # 应用配置
├── res/
│   ├── layout/
│   │   └── activity_main.xml              # 主界面布局
│   ├── drawable/
│   │   ├── button_primary.xml             # 主按钮样式
│   │   ├── button_danger.xml              # 危险按钮样式
│   │   └── button_info.xml                # 信息按钮样式
│   ├── xml/
│   │   └── network_security_config.xml    # 网络安全配置
│   └── values/
│       └── strings.xml                    # 字符串资源
└── README.md                               # 本文档
```

---

## 📋 必需的依赖库

在 `app/build.gradle.kts` 中添加以下依赖：

```kotlin
dependencies {
    // Android 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // OkHttp (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Opus 解码器 (Concentus - 纯Java实现，无需NDK)
    implementation("org.concentus:concentus:1.0.0")
}
```

---

## 🛠️ 导入步骤

### 方法一：手动创建项目

1. **在 Android Studio 创建新项目**
   - File → New → New Project
   - 选择 "Empty Activity"
   - Language: Kotlin
   - Minimum SDK: API 26

2. **复制文件到对应位置**
   ```
   MainActivity.kt           → app/src/main/java/com/example/audiorecorder/
   OpusDecoder.kt           → app/src/main/java/com/example/audiorecorder/
   AndroidManifest.xml      → app/src/main/
   activity_main.xml        → app/src/main/res/layout/
   button_*.xml             → app/src/main/res/drawable/
   network_security_config.xml → app/src/main/res/xml/
   strings.xml              → app/src/main/res/values/
   ```

3. **添加依赖**
   - 在 `app/build.gradle.kts` 中添加上面的依赖
   - 点击 "Sync Now"

4. **运行**
   - 连接设备或启动模拟器
   - 点击 Run

### 方法二：使用现有项目

如果你已有项目，只需：
1. 复制 `MainActivity.kt` 和 `OpusDecoder.kt` 到你的包名下
2. 复制资源文件到对应目录
3. 添加依赖
4. 更新 `AndroidManifest.xml` 中的权限和配置

---

## 📱 build.gradle.kts 完整示例

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audiorecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audiorecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Android 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // OkHttp (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Opus 解码器
    implementation("org.concentus:concentus:1.0.0")
}
```

---

## 🔧 settings.gradle.kts 配置

确保项目根目录的 `settings.gradle.kts` 包含：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AudioRecorder"
include(":app")
```

---

## ✅ 功能特性

- ✅ WebSocket 自动连接
- ✅ Opus 音频解码（使用 Concentus，无需 NDK）
- ✅ 双通道自动转单通道
- ✅ 实时统计显示
- ✅ PCM 文件保存
- ✅ 传统 View 界面（无 Compose）
- ✅ 实时日志显示

---

## 📝 使用说明

1. 启动应用自动连接 WebSocket
2. 等待握手成功（显示音频配置）
3. 点击"开始录制"按钮
4. 点击"停止"按钮
5. 点击"下载 PCM 文件"保存到下载目录

文件保存位置：`/storage/emulated/0/Download/audio_mono_YYYY-MM-DD_HH-mm-ss.pcm`

---

## 🎯 播放 PCM 文件

```bash
# 使用 FFplay
ffplay -f s16le -ar 48000 -ac 1 audio_mono_xxx.pcm

# 使用 Audacity
1. File → Import → Raw Data
2. Encoding: Signed 16-bit PCM
3. Byte order: Little-endian
4. Channels: 1 (Mono)
5. Sample rate: 48000 Hz
```

---

## ⚠️ 注意事项

### 1. 权限
应用需要以下权限：
- `INTERNET` - 网络连接
- `RECORD_AUDIO` - 录音（保留供扩展）
- `WRITE_EXTERNAL_STORAGE` - 保存文件

### 2. 网络安全
- 配置允许明文流量和自签名证书
- 仅用于开发环境
- 生产环境应使用有效的 SSL 证书

### 3. Opus 解码
- 使用 Concentus 纯 Java 实现
- 无需配置 NDK
- 性能略低于原生实现，但对大多数场景足够

---

## 🐛 故障排查

### 无法连接 WebSocket
1. 检查服务器地址是否正确
2. 确认设备与服务器在同一网络
3. 查看 Logcat 日志

### 无法保存文件
1. 检查存储权限
2. 确认设备有足够空间

### 音频质量问题
1. 检查采样率是否匹配
2. 验证 PCM 字节序（Little-Endian）

---

## 📄 许可证

MIT License
