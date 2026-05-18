#include "main.h"

#ifdef NO_GIT_REVISION
#define GIT_REVISION "<omitted>"
#else
#include "program/gitinfo.h"
#endif

#include <sstream>

//------------------------
#include "core/using.h"
//------------------------

string Version::getKataGoVersion() {
  return string("1.16.4");
}

string Version::getKataGoVersionForHelp() {
  return string("KataGo v1.16.4");
}

string Version::getKataGoVersionFullInfo() {
  ostringstream out;
  out << Version::getKataGoVersionForHelp() << endl;
  out << "Git revision: " << Version::getGitRevision() << endl;
  out << "Compile Time: " << __DATE__ << " " << __TIME__ << endl;
#if defined(USE_CUDA_BACKEND)
  out << "Using CUDA backend" << endl;
#elif defined(USE_TENSORRT_BACKEND)
  out << "Using TensorRT backend" << endl;
#elif defined(USE_METAL_BACKEND)
  out << "Using Metal backend" << endl;
#elif defined(USE_OPENCL_BACKEND)
  out << "Using OpenCL backend" << endl;
#elif defined(USE_EIGEN_BACKEND)
  out << "Using Eigen(CPU) backend" << endl;
#else
  out << "Using dummy backend" << endl;
#endif
  return out.str();
}

string Version::getGitRevision() {
  return string(GIT_REVISION);
}

string Version::getGitRevisionWithBackend() {
  string s = string(GIT_REVISION);

#if defined(USE_CUDA_BACKEND)
  s += "-cuda";
#elif defined(USE_TENSORRT_BACKEND)
  s += "-trt";
#elif defined(USE_METAL_BACKEND)
  s += "-metal";
#elif defined(USE_OPENCL_BACKEND)
  s += "-opencl";
#elif defined(USE_EIGEN_BACKEND)
  s += "-eigen";
#else
  s += "-dummy";
#endif
  return s;
}
