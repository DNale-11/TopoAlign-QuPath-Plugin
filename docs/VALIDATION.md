# Validation of v0.1.0

Date: 2026-09-22. Host: Windows, QuPath 0.7.0, JDK 25.0.2.

- `gradlew.bat build -PqupathHome=E:/Qupath`: passed (6 Java tests).
- `gradlew.bat build`: passed using Maven/SciJava dependencies (6 Java tests).
- Python integration suite: all 6 tests passed against the real TopoAlign service in both the existing Python 3.10.19 environment and the host Python 3.13.9 environment. Python 3.10/3.11 are recommended for a fresh installation because of upstream dependency constraints.
- ServiceLoader discovered the extension from the built JAR using the installed QuPath 0.7.0 libraries.
- An additional host smoke check opened a 16-bit TIFF through QuPath's actual ImageServerProvider, exported one channel, retained an intensity of 45000, and produced the expected 64×48 output from a 128×96 source at downsample 2.

The synthetic integration fixture contains 25 individually labelled cells with varied elliptical morphology. Moving was translated by (+11, +7) pixels. The real feature extraction, two-stage matching, transform estimation and warp pipeline recovered all 25 matches and the inverse translation (-11, -7), with mean residual below 1e-12 pixels. The warped label image matched the fixed image exactly. Export-to-original coordinate conversion was separately verified with different fixed/moving downsample factors.

Failure cases cover binary mask rejection, unsupported non-rigid RANSAC, an exception during registration, existing-output protection, process failure, process cancellation, bounded log reading, and paths containing spaces/Unicode.

Limits: no interactive visual QA of the dialog, real tissue registration benchmark, GPU run, or Cellpose model inference was performed for this release. The image-mode integration test substitutes known segmentation labels while exercising the real matching and image warp pipeline. QuPath service discovery/image export were tested separately from the Python integration tests.

The original algorithm repository is private. GitHub Actions therefore runs Java tests and Python syntax checks by default; real core integration in CI requires an explicitly configured read-only source credential and is otherwise skipped. The first CI attempt exposed this access requirement during dependency installation; it was not an algorithm test failure.
