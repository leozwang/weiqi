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

1.  **Google Pixel 11 TPU (Tensor G6 / SantaFe):** Next-gen NPU acceleration on Pixel 11 devices (Cubs, Grizzly, Kodiak, Yogi) via LiteRT, delivering sub-second AI moves with peak power efficiency (see [Pixel 11 TPU Guide](file:///tmp/weiqi/doc/pixel11_tpu_implementation.md)).
2.  **Google Pixel 9 TPU (Tensor G4):** Direct NPU acceleration on Pixel 9 devices via LiteRT and the Google Tensor hardware dispatcher (see [Pixel 9 TPU Guide](file:///tmp/weiqi/doc/pixel9_tpu_implementation.md)).
3.  **OpenCL Backend:** Built with the **OpenCL** GPU backend for devices with Mali, Adreno, or PowerVR GPUs.
4.  **OpenCL Proxy:** Implemented a custom C++ proxy to bypass Android's vendor library namespace restrictions, enabling secure access to the system `libOpenCL.so`.
5.  **Automatic Tuning:** The first time the engine initializes, it performs autotuning to compile the most efficient GPU kernels for your specific hardware.
6.  **Multi-threading:** Configured to use 4 search threads (`numSearchThreads = 4`) to maximize hardware utilization without causing thermal throttling.

#### How to Switch Between TPU and GPU (In-App)

On Google Pixel 9 (Tensor G4) and Pixel 11 (Tensor G6) phones, the app supports dynamic hardware acceleration on either the **Edge TPU (NPU)** or the **ARM Mali/Immortalis GPU (OpenCL)**:

1. Tap **NEW GAME** in the app.
2. In the dialog, find the **HARDWARE ACCELERATION** section and choose:
   * **Auto (Recommended):** Automatically selects Edge TPU on Pixel devices and OpenCL GPU on other Android devices.
   * **TPU (Google Tensor NPU):** Forces execution on Google's Edge TPU via LiteRT (`model_tpu_p11.tflite` / `model_tpu_p9.tflite`).
   * **GPU (OpenCL):** Forces graphics acceleration on the device GPU via OpenCL (`model.bin.gz`).
   * **CPU (Eigen):** Forces multi-threaded CPU processing fallback (`model.bin.gz`).
3. Tap **START**: The engine will reinitialize on the chosen backend, update the top bar badge (e.g. `[TPU (Pixel 11)]` or `[GPU (Pixel 11 OpenCL)]`), and load the corresponding model file.

#### Benchmarking & Latency Diagnostics

To measure and compare real-time inference latency between TPU and GPU:
1. In the **New Game** dialog, scroll down to **DIAGNOSTICS & HARDWARE**.
2. Toggle **Show Engine Latency** to **ON**.
3. Real-time inference latency (in seconds) will appear in the top action bar (e.g. `TPU (Pixel 11) • 3.45s`), in AI move notifications (e.g. `White (AI) played D4 (3.45s)`), and in the position analysis bar.

#### Understanding AI Move Latency & MCTS Search Visits

KataGo uses Monte Carlo Tree Search (MCTS) to evaluate moves. Rather than evaluating the neural network once per turn, KataGo evaluates the network dozens to hundreds of times depending on the selected **AI Strength (Visits)**:

$$\text{Total Turn Latency} \approx \text{MCTS Visits} \times \text{Per-Evaluation Latency}$$

On mobile hardware (Pixel 9 / Pixel 11):
* **Single Evaluation Latency:** Evaluating a single board state through the 10-block neural network takes approximately **~30ms – 40ms** (including tensor marshalling, delegate invocation, and tree synchronization).
* **Easy (100 visits):** $100 \times 35\text{ms} \approx \mathbf{3.5\text{ seconds}}$ per move.
* **Amateur (500 visits, default):** $500 \times 35\text{ms} \approx \mathbf{15\text{ – }20\text{ seconds}}$ per move.
* **Advanced (1000 visits):** $1000 \times 35\text{ms} \approx \mathbf{30\text{ – }40\text{ seconds}}$ per move.
* **Pro (2500 visits):** $2500 \times 35\text{ms} \approx \mathbf{1.5\text{ – }2\text{ minutes}}$ per move.

**Tips for Fast Play:**
* For rapid casual games, select **Easy (100 visits)** in the **New Game** settings dialog (~3–4 seconds per turn).
* Enable **Show Engine Latency** in the diagnostics section to monitor exact second timings on your device.

#### Verifying TPU Execution (Logcat & Android Framework)

To verify that the engine is executing directly on the Google Edge TPU:

##### 1. Application & Backend Telemetry (Logcat)
Run the following command while launching the app or starting a game:
```bash
adb logcat -v time -s KataGoBackend:I KataGoEngine:I KataGoBridgeTPU:I GameFragment:I
```
**Expected Output:**
```text
I/GameFragment: Pixel 11 (Tensor G6 / SantaFe TPU) detected. Initializing KataGoBridgeTPU.
I/KataGoBackend: =============================================
I/KataGoBackend: VERIFIED RUNTIME BACKEND: TPU_PIXEL_11 (Tensor G6 SantaFe)
I/KataGoBackend: ACTIVE BRIDGE CLASS: KataGoBridgeTPU
I/KataGoBackend: LOADED MODEL FILE: model_tpu_p11.tflite
I/KataGoBackend: INITIALIZATION STATUS: SUCCESS
I/KataGoBackend: =============================================
```

##### 2. Google Tensor TPU System & Driver Logs
To monitor low-level driver initialization and TPU hardware dispatch:
```bash
adb logcat -v time | grep -iE "darwinn|edgetpu|ltert|tflite|google_tensor"
```

##### 3. Process Memory & Device Node Verification
Verify mapped native libraries and hardware device nodes in terminal:
```bash
# Check loaded TPU libraries in the app process
adb shell "cat /proc/\$(pidof com.cwave.weiqi)/maps" | grep -E "libkatago_tpu_jni|LiteRt|darwinn|edgetpu"

# Check TPU hardware device nodes
adb shell ls -l /dev/edgetpu* /dev/accel* /dev/darwinn*
```





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


## Testing In-App Purchases (IAP)

To test the premium unlock (`weiqi_one_time_product`), you need to use Google Play's billing testing sandbox. Since the app uses the official Google Play Billing Library, testing cannot be done purely offline or with a debug build that isn't registered with Google Play.

Follow these steps to set up and perform IAP testing:

### 1. Google Play Console Configuration

Before testing, you must have access to a Google Play Developer account and have the app registered.

1.  **Package Name**: Ensure your app's package name (`com.cwave.weiqi`) is registered in your Google Play Console. If you rename the app, update `applicationId` in `src/BUILD` and `AndroidManifest.xml` accordingly.
2.  **Create In-App Product**:
    *   In the Play Console, go to **Monetize** > **Products** > **In-app products**.
    *   Click **Create product**.
    *   Set **Product ID** to `weiqi_one_time_product` (this must match `PREMIUM_UNLOCK_ID` in `BillingManager.kt`).
    *   Fill in the name, description, and price, then click **Save** and **Activate**.
3.  **Configure License Testing**:
    *   Go to **Setup** > **License testing**.
    *   In the **License testers** field, add the Gmail addresses of your test accounts.
    *   Set **License response** to `RESPOND_NORMALLY`.

### 2. Distribute the Test Build

Google Play Billing only works if the app is installed via Google Play (so Play Store can manage the purchase state).

1.  **Build the Release AAB**:
    ```bash
    bazel build -c opt --config=android_arm64-v8a //src:release_bundle
    ```
    This generates `bazel-bin/src/release.aab`.
2.  **Upload to Internal Testing**:
    *   In Play Console, go to **Testing** > **Internal testing**.
    *   Create a new release and upload the `release.aab` file.
    *   Add your test Gmail accounts to the testers list for this track.
    *   Copy the **Join on the web** link (tester opt-in URL) and share it with your testers.
3.  **Accept Invitation**:
    *   On the test device (or a browser logged into the test Gmail account), open the opt-in link.
    *   Accept the invitation to join the test program.

### 3. Perform the Test on Device

1.  **Prepare Device**:
    *   Ensure the test device is logged into Google Play Store with the registered tester Gmail account.
    *   If you have a debug version of the app installed locally via `adb install`, uninstall it first to avoid signature conflicts.
2.  **Install the App**:
    *   Use the link provided in the opt-in page ("download it on Google Play") to open the Play Store page for the app and install it.
3.  **Execute Purchase**:
    *   Open the app and trigger the premium unlock flow.
    *   The Google Play Billing dialog should appear.
    *   It should indicate that this is a test purchase and display payment options like **"Test card, always approves"** or **"Test card, always declines"**.
    *   Select **"Test card, always approves"** and complete the purchase.
    *   Verify that the app successfully unlocks the premium features.

### 4. Resetting the Purchase for Re-testing

Since the premium unlock is a non-consumable product, once purchased, it remains owned by the test account. To test the purchase flow again, you must revoke the purchase:

1.  In Google Play Console, go to **Order management**.
2.  Find the test transaction.
3.  Click on the order and select **Refund** or **Void**.
4.  Ensure you check the option to **Revoke access** to the product.
5.  Clear the cache/data of the Play Store app on the test device, or wait a few minutes for the purchase state to sync.
6.  Reopen the app; the premium features should be locked again, allowing you to test the purchase flow once more.

### 5. Bypassing Premium Lock for Local Builds

For local development or testing AI strengths without configuring Google Play Console/license testing, you can bypass the in-app purchase lock by hardcoding the premium state:

1. Open `src/java/com/cwave/weiqi/GameFragment.kt`.
2. Locate the variable:
   ```kotlin
   val isPremiumUnlocked by billingManager.isPremiumUnlocked.collectAsState()
   ```
3. Change it to:
   ```kotlin
   val isPremiumUnlocked = true
   ```
This will instantly unlock the "Advanced" and "Pro" AI strengths and disable the IAP dialog flow in your local compiler build.



