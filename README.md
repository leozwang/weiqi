# weiqi

## Build and Run

To clone the repository and initialize submodules:
```bash
gh repo clone leozwang/weiqi
cd weiqi
git submodule update --init --recursive
```

To build and install the application:
```bash
bazel build -c opt --config=android_arm64-v8a //src:release && adb install -r bazel-bin/src/release.apk
```

To launch the application:
```bash
adb shell am start -n com.cwave.weiqi/com.cwave.weiqi.MainActivity
```

## Knowledge

### **The Go Text Protocol**

https://www.gnu.org/software/gnugo/gnugo_19.html

### KataGo

https://github.com/lightvector/KataGo

```
gh repo clone lightvector/KataGo
```

#### Install

MacOS

```
brew install katago
```

#### Model

For Android, we use a lightweight, mobile-optimized model to ensure fast inference on mobile GPUs.

The current model in `src/assets/model.bin.gz` is:
*   **Model Name:** `g170e-b10c128-s1141046784-d204142634.bin.gz` (10 blocks, 128 channels)
*   **Source:** Downloaded from the [KataGo Archive (Extended/Mobile)](https://katagoarchive.org/est/) or extracted from `KataGo/cpp/tests/models/`.

Larger models (like the original 200MB `g170-b30c320`) are too heavy for mobile GPUs and will result in moves taking >60 seconds. This 11MB model provides a professional level of play with moves taking only 3-5 seconds.

Download more models at https://katagotraining.org from Archives https://katagoarchive.org

#### Android Performance Optimization

The Android application is optimized for high performance using the following techniques:

1.  **OpenCL Backend:** The engine is built with the **OpenCL** backend, allowing it to run neural network evaluations on the device's GPU (Adreno, Mali, or PowerVR).
2.  **OpenCL Proxy:** Implemented a custom C++ proxy to bypass Android's vendor library namespace restrictions, enabling secure access to the system `libOpenCL.so`.
3.  **Automatic Tuning:** The first time the engine initializes, it performs autotuning to compile the most efficient GPU kernels for your specific hardware.
4.  **Multi-threading:** Configured to use 4 search threads (`numSearchThreads = 4`) to maximize GPU utilization without causing thermal throttling.

#### Compiling KataGo for Android (Bazel)

KataGo is integrated as a modular C++ library and cross-compiled for Android using Bazel.

##### **Switching Backends**
To switch between GPU and CPU backends, modify `KataGo/BUILD.bazel`:

*   **For OpenCL (GPU):**
    *   Set `defines = ["USE_OPENCL_BACKEND", ...]`
    *   Ensure `:opencl` is in `deps`.
*   **For Eigen (CPU):**
    *   Set `defines = ["USE_EIGEN_BACKEND", ...]`
    *   Remove `:opencl` from `deps`.
    *   Add `cpp/neuralnet/opencl*.cpp` to the `exclude` list in `srcs`.

##### **Build Command**
The entire Android app (including the engine) is built with:
```bash
bazel build -c opt --config=android_arm64-v8a //src:release
```

#### Run

To run the engine locally on MacOS:
```
katago gtp -config gtp_example.cfg -model model.bin.gz
```

Output

```
KataGo v1.16.4
Using TrompTaylor rules initially, unless GTP/GUI overrides this
Metal backend 0: Apple M3 Pro, Model version 8 g170-b30c320x2-s4824661760-d1229536699, 19x19
Initializing board with boardXSize 19 boardYSize 19
Loaded config gtp_example.cfg
Loaded model g170-b30c320x2-s4824661760-d1229536699.bin.gz
Model name: g170-b30c320x2-s4824661760-d1229536699
GTP ready, beginning main protocol loop
```

How to play

```
play black Q16
=

genmove white
= D4
```

#### Build

https://github.com/lightvector/KataGo/blob/master/Compiling.md

1. 

git clone https://github.com/lightvector/KataGo.git

Clean cache files

```
rm CMakeCache.txt CMakeFiles
```

AI suggested

```
✦ I have fixed the compilation errors for KataGo on your Mac. The issues were
  caused by missing build dependencies required for the METAL backend and the
  distributed training features.

  Changes and Fixes
   1. Installed Missing Dependencies: I identified that protobuf, abseil, and
      pkg-config were missing, which are required for the Metal backend. I
      installed them using Homebrew:

   1     brew install protobuf abseil pkg-config
   2. Configured and Built: I ran the requested cmake and ninja commands:

   1     cmake -G Ninja -DUSE_BACKEND=METAL -DBUILD_DISTRIBUTED=1 .
   2     ninja
   3. Verification: I verified the build by running the version command and
      the internal tests:
       - ./katago version: Confirmed version 1.16.4 with the Metal backend and
         distributed support.
       - ./katago runtests: All internal board and logic tests passed.

  Current Status
  The katago executable is now successfully built and located in
  /Users/leozwang/src/KataGo/cpp/katago.

   1 cd /Users/leozwang/src/KataGo/cpp
   2 ./katago version
```

#### Optimization

OpenCL vs CUDA vs TensorRT vs Eigen
KataGo has four backends, OpenCL (GPU), CUDA (GPU), TensorRT (GPU), and Eigen (CPU).

### Kaya

https://github.com/kaya-go

