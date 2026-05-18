# weiqi

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

I downloaded model at https://github.com/lightvector/KataGo/releases/tag/v1.4.5

```
-rw-r--r--@  202733631 May 16 20:12 g170-b30c320x2-s4824661760-d1229536699.bin.gz
```

Download more models at https://katagotraining.org from Archives https://katagoarchive.org

#### Run

```
katago gtp -config gtp_example.cfg -model g170-b30c320x2-s4824661760-d1229536699.bin.gz
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

