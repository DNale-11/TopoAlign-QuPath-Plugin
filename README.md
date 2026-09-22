# TopoAlign QuPath Plugin

在 **QuPath 0.7.0** 中运行 [TopoAlign](https://github.com/DNale-11/cell_registration) 的细胞图像配准方法。原有 Python 算法负责细胞分割、形态/拓扑特征提取、两阶段匹配和几何变换；QuPath 扩展负责图像读取、通道导出、参数界面、后台任务和结果查看。

This extension connects QuPath to the existing TopoAlign Python registration service. It does not reimplement or bundle the registration algorithm.

## 安装

1. 安装 **QuPath 0.7.0**。本版本针对 Java 25 / QuPath 0.7 构建，不适用于 QuPath 0.5/0.6。
2. 从 [Releases](https://github.com/DNale-11/TopoAlign-QuPath-Plugin/releases) 下载 `qupath-extension-topoalign-0.1.0.jar`。
3. 将 JAR 拖入 QuPath，按提示安装，然后重启 QuPath。菜单位置为 **Extensions → TopoAlign → Register images…**。
4. 配置下面的 Python 环境。JAR 内包含适配脚本，不包含 Python、PyTorch、Cellpose 权重或原项目数据。

QuPath 官方扩展安装说明：[Installing extensions](https://qupath.readthedocs.io/en/stable/docs/intro/extensions.html)。

## Python 环境

推荐 Python **3.10 或 3.11**，与 TopoAlign 当前科学计算依赖版本一致。已经有可用的 TopoAlign 环境时直接复用，界面中填写该环境的 `python.exe` / `python` 完整路径，不要填写 `conda activate` 或其他命令。

### 使用现有本地 TopoAlign 源码

在该 Python 环境中运行（替换路径）：

```bash
python -m pip install -e "/path/to/cell_registration"
python -m pip install "networkx>=2.6,<4" "matplotlib>=3.7,<3.10" "imagecodecs>=2023.9.18,<2024.6"
```

插件的 **TopoAlign source folder** 填写包含 `cell_registration/service.py` 的项目根目录。此设置优先使用选中的源码，适合持续修改算法的开发环境。已安装正确包时也可留空。

### 新环境安装

```bash
conda create -n topoalign-qupath python=3.10 -y
conda activate topoalign-qupath
python -m pip install "TopoAlign @ git+https://github.com/DNale-11/cell_registration.git@693c6781e162f15e9f7783c7da3b94fa270fb2b0"
python -m pip install "networkx>=2.6,<4" "matplotlib>=3.7,<3.10" "imagecodecs>=2023.9.18,<2024.6"
```

**图像模式**额外需要 Cellpose-SAM：

```bash
python -m pip install "cellpose>=4,<5"
```

Cellpose 首次运行可能下载模型，需要网络和足够磁盘空间。使用 GPU 时，该环境还需要与设备兼容的 PyTorch/CUDA；插件中的 GPU 选项控制原服务的 Cellpose 分割。已有标签掩膜时不需要安装 Cellpose，也不需要 GPU。

验证环境：

```bash
python -c "from cell_registration.service import register; print('TopoAlign service ready')"
```

## 使用方法

1. 在 QuPath 打开参考图像，然后点击 **Use current image as fixed**；或直接选择 Fixed / Moving 文件。该按钮读取当前图像的本地文件地址，重新打开文件的第一个 series，不使用当前显示窗口的 LUT、裁剪或其他虚拟服务器变换。
2. 选择输入模式：
   - **Images (Cellpose segmentation)**：QuPath 读取两幅图像，分别提取指定的原始强度通道（从 1 开始），导出 float32 单通道 TIFF，再由 TopoAlign 分割并配准。
   - **Label masks (skip segmentation)**：直接选择两幅二维整数 TIFF 标签图；背景为 0，每个细胞一个不同正整数。至少需要 3 个细胞标签，二值前景图不能替代实例标签图。
3. 图像模式选择 **Downsample**。`1` 为原始分辨率；`4` 为宽、高各缩小至约四分之一。默认读取 **Z=0、T=0**，不是当前查看器选中的 Z/T。
4. 选择 `rigid`（旋转+平移）、`similarity`（允许均匀缩放）或 `affine`（允许剪切及非均匀缩放）。原核心仅为 rigid 实现 RANSAC，因此该选项只在 rigid 模式可选。其余参数默认沿用原服务。
5. 选择输出父目录，点击 **Run registration**。每次创建新的 `topoalign-日期时间-随机后缀` 目录。界面显示日志并支持取消；关闭对话框仅隐藏窗口，重新打开菜单可继续查看任务。
6. 完成后点击 **Open registered image** 或 **Open overlay**。打开结果时，QuPath 会按正常流程处理当前图像的未保存修改。通过 **Open run folder** 查看所有文件。

## 输出与坐标

```text
topoalign-.../
  fixed.tif, moving.tif            # 图像模式的单通道导出
  request.json                    # 输入、通道、倍率、参数和原始文件路径
  topoalign_bridge.py              # 本次执行的适配脚本
  run.log                         # Python 输出与错误
  results/
    registered_moving.tif          # 变换后的 moving，位于 fixed 网格
    overlay.tif                   # fixed 洋红 / moving 绿色叠加预览
    valid_overlap_mask.tif
    fixed_features.csv, moving_features.csv, matches.csv
    transform.moving_to_fixed.json # 导出图像像素坐标的 3×3 矩阵
    transform.full_resolution.json # 原图像素坐标的 3×3 矩阵
    config.resolved.json, diagnostics.json, result.json
```

所有变换方向都是 **moving → fixed**，坐标为 `(x, y)`，使用列向量 `[x, y, 1]ᵀ`。图像模式的原图变换为：

```text
M_full = diag(fixed_downsample, fixed_downsample, 1)
         × M_export
         × diag(1/moving_downsample, 1/moving_downsample, 1)
```

标签模式没有降采样或裁剪，两个变换文件都使用所选标签图的像素坐标；如果标签图来自切片的 ROI 或缩略图，需要自行计入 ROI 偏移和分辨率，不能直接视为切片原图坐标。

界面报告匹配数量、平均残差（**导出像素单位**）和有效重叠比例。`completed` 只表示算法运行成功；请结合匹配误差和叠加图检查质量。

## 当前范围

- 支持单对图像；全切片格式由本机 QuPath 图像读取器决定。一次导出最多 1600 万像素且总通道样本最多 6400 万，超过时需增大 Downsample。
- 配准和输出针对选中的单个强度通道；不生成原始全分辨率、多通道金字塔 WSI。
- TIFF 输出不保留原始物理像素标定/OME 元数据；变换采用像素坐标。可用矩阵进一步处理其他通道或标注，但本版本不自动转移 QuPath 对象。
- 图像模式仅使用 Z=0、T=0 和默认 series。多 series、远程/复合服务器应先导出需要的平面。
- 大型图像导出阶段的取消会在当前读取操作结束后生效；Python 阶段取消会终止进程及其子进程。部分输出保留以便排查，下一次运行使用新目录。
- 本版本已验证真实 TopoAlign 匹配/变换/warp 链路与合成标签图。图像模式测试替代了 Cellpose 分割输出，不代表完成了真实生物图像或 GPU 性能验证。

## 构建与测试

需要 **JDK 25**，Gradle Wrapper 已包含在仓库中：

```bash
./gradlew build
```

Windows 使用 `gradlew.bat build`。依赖从 Maven Central 与 QuPath SciJava 仓库获取；也可直接使用本机 QuPath 的 JAR（目录需包含 `app/*.jar`）：

```powershell
./gradlew.bat build -PqupathHome="E:/Qupath"
```

输出：`build/libs/qupath-extension-topoalign-0.1.0.jar`。JAR 只包含本扩展类和 Python 适配脚本，QuPath/JavaFX 库由宿主提供。

Python 集成测试：

```powershell
$env:TOPOALIGN_SOURCE_ROOT = "F:/programme/nxy/Cell registration"
python -m unittest discover -s tests -v
```

在已安装 TopoAlign 包的环境中可以省略 `TOPOALIGN_SOURCE_ROOT`。测试包含实际两阶段匹配、已知平移恢复、图像 warp、原图坐标换算、输出覆盖保护、失败清单和含中文/空格的路径。

算法来源固定参考原项目提交 [`693c678`](https://github.com/DNale-11/cell_registration/commit/693c6781e162f15e9f7783c7da3b94fa270fb2b0)。适配层使用 `TopoAlignConfig` 与 `cell_registration.service.register()`，可通过 source folder 指向兼容的本地新版本。
