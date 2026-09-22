# TopoAlign QuPath Plugin

在 **QuPath 0.7.0** 中调用现有 TopoAlign Python 算法，支持图像与细胞标签掩膜配准、通道选择、降采样，以及 rigid / similarity / affine 变换。

## 安装与使用

1. 从 [Releases](https://github.com/DNale-11/TopoAlign-QuPath-Plugin/releases) 下载 JAR，拖入 QuPath 安装并重启。
2. 打开 **Extensions → TopoAlign → Register images…**。
3. 选择已安装 TopoAlign 的 Python 解释器；使用本地源码时，填写包含 `cell_registration/service.py` 的项目目录。
4. 选择 fixed、moving 和输出目录，设置参数后运行。

图像模式需要 **Cellpose 4**；标签掩膜模式可跳过分割。结果包含配准图像、叠加图、匹配表，以及 **moving → fixed** 变换矩阵。

本版本处理单对图像的单个通道（Z=0、T=0），不生成全分辨率多通道 WSI。

## 构建

需要 **JDK 25**：

```bash
./gradlew build
```

Windows 使用 `gradlew.bat build`，JAR 位于 `build/libs/`。

[详细使用说明](docs/USAGE.md) · [验证记录](docs/VALIDATION.md) · [TopoAlign 原项目](https://github.com/DNale-11/cell_registration)
