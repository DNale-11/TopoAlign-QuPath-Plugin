"""Headless QuPath adapter; registration remains in the original TopoAlign core."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys
import traceback


def run(request: dict) -> dict:
    import numpy as np
    import tifffile
    import cell_registration
    from cell_registration.cli_config import TopoAlignConfig
    from cell_registration.service import register

    mode = request["mode"]
    if mode not in {"image", "mask"}:
        raise ValueError("mode must be image or mask")
    method = request.get("method", "rigid")
    if method not in {"rigid", "similarity", "affine"}:
        raise ValueError("Unsupported transform method")
    use_ransac = request.get("use_ransac", False)
    if use_ransac and method != "rigid":
        raise ValueError("The current core supports RANSAC only for rigid transforms")
    scales = [float(request.get(name + "_downsample", 1)) for name in ("fixed", "moving")]
    if any(not math.isfinite(s) or s < 1 for s in scales):
        raise ValueError("Downsample factors must be finite and >= 1")
    if mode == "mask" and scales != [1, 1]:
        raise ValueError("Mask mode uses the supplied mask pixel coordinates (downsample = 1)")

    shapes = []
    for name in ("fixed", "moving"):
        path = Path(request[name]).resolve(strict=True)
        data = tifffile.imread(path)
        if data.ndim != 2 or not np.isfinite(data).all():
            raise ValueError(f"{name} must be a finite 2D TIFF; export one channel/plane in QuPath")
        if mode == "mask":
            if data.dtype.kind not in "iu" or data.min() < 0 or data.max() > np.iinfo(np.int32).max:
                raise ValueError(f"{name} mask must contain nonnegative integer cell labels")
            if len(np.unique(data[data > 0])) < 3:
                raise ValueError(f"{name} needs at least 3 distinct cell labels; a binary mask is insufficient")
        request[name] = str(path)
        shapes.append(data.shape)
    matching = request.get("matching", {})
    allowed = {"top_k", "feature_weight", "topology_weight", "position_weight", "distance_threshold", "spatial_window_size"}
    if set(matching) - allowed:
        raise ValueError("Unsupported matching option")
    for key, value in matching.items():
        if not math.isfinite(float(value)) or float(value) < 0:
            raise ValueError(f"Invalid matching parameter: {key}")
        if key in {"top_k", "spatial_window_size", "distance_threshold"} and float(value) <= 0:
            raise ValueError(f"{key} must be positive")
    if "top_k" in matching and int(matching["top_k"]) != matching["top_k"]:
        raise ValueError("top_k must be an integer")
    out = Path(request["output_dir"]).resolve()
    # A new directory prevents a failed run being mistaken for an older success.
    out.mkdir(parents=True, exist_ok=False)
    print(f"TopoAlign core: {Path(cell_registration.__file__).resolve()}", flush=True)
    print(f"Registering {mode} inputs: {shapes}; transform direction moving_to_fixed", flush=True)
    cfg = TopoAlignConfig.from_dict({
        "mode": mode,
        ("fixed_mask" if mode == "mask" else "fixed"): request["fixed"],
        ("moving_mask" if mode == "mask" else "moving"): request["moving"],
        "fixed_shape": list(shapes[0]),
        "segmentation": {"channel_axis": "none", "registration_channel": 0, "gpu": bool(request.get("gpu", False))},
        "matching": matching,
        "transform": {"method": method, "use_ransac": bool(use_ransac)},
        "output": {"output_dir": str(out)},
    })
    try:
        result = register(cfg)
        matrix = np.asarray(result.diagnostics["transform_matrix"], dtype=float)
        if matrix.shape != (3, 3) or not np.isfinite(matrix).all() or abs(np.linalg.det(matrix)) < 1e-12:
            raise ValueError("Core returned an invalid/singular transform")
        full = np.diag([scales[0], scales[0], 1]) @ matrix @ np.diag([1 / scales[1], 1 / scales[1], 1])
        transform_path = out / "transform.full_resolution.json"
        transform_path.write_text(json.dumps({
            "direction": "moving_to_fixed", "coordinate_space": "pixel_xy",
            "matrix": full.tolist(), "fixed_downsample": scales[0], "moving_downsample": scales[1],
            "note": "Mask mode coordinates refer to the supplied masks; image mode uses original image pixels.",
        }, indent=2), encoding="utf-8")
        result.artifacts["full_resolution_transform"] = str(transform_path)
        overlap = tifffile.imread(result.artifacts["valid_overlap_mask"])
        result.diagnostics["valid_overlap_fraction"] = float(np.mean(overlap > 0))
        result.diagnostics["core_module"] = str(Path(cell_registration.__file__).resolve())
        if result.diagnostics["valid_overlap_fraction"] < 0.1:
            result.warnings.append("Less than 10% valid overlap; inspect the registration before using it.")
        result.write(out / "result.json")
        (out / "diagnostics.json").write_text(json.dumps({
            **result.diagnostics, "warnings": result.warnings,
        }, indent=2), encoding="utf-8")
        print(f"Completed: {result.diagnostics}", flush=True)
        return result.to_dict()
    except Exception as exc:
        (out / "result.json").write_text(json.dumps({
            "product": "TopoAlign", "status": "failed", "errors": [str(exc)],
        }, indent=2), encoding="utf-8")
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--source-root", type=Path)
    args = parser.parse_args()
    if args.source_root:
        root = args.source_root.resolve(strict=True)
        if not (root / "cell_registration" / "service.py").is_file():
            parser.error("source-root must contain cell_registration/service.py")
        sys.path.insert(0, str(root))
    try:
        run(json.loads(args.request.read_text(encoding="utf-8-sig")))
        return 0
    except Exception:
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
