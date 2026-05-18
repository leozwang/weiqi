#include <CL/cl.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "OpenCLProxy"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Lazy-loading proxy for OpenCL on Android

static void* g_handle = nullptr;

static void* get_func(const char* name) {
    if (!g_handle) {
        const char* paths[] = {
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "libOpenCL.so"
        };
        for (const char* path : paths) {
            g_handle = dlopen(path, RTLD_NOW);
            if (g_handle) break;
        }
    }
    if (!g_handle) return nullptr;
    return dlsym(g_handle, name);
}

#define PROXY_FUNC(NAME, RET, ARGS, CALL_ARGS) \
    extern "C" RET NAME ARGS { \
        typedef RET (*func_t) ARGS; \
        static func_t proxy_func_ptr = nullptr; \
        if (!proxy_func_ptr) proxy_func_ptr = (func_t)get_func(#NAME); \
        if (proxy_func_ptr) return proxy_func_ptr CALL_ARGS; \
        return (RET)0; \
    }

extern "C" {
    PROXY_FUNC(clGetPlatformIDs, cl_int, (cl_uint a, cl_platform_id *b, cl_uint *c), (a,b,c))
    PROXY_FUNC(clGetPlatformInfo, cl_int, (cl_platform_id a, cl_platform_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
    PROXY_FUNC(clGetDeviceIDs, cl_int, (cl_platform_id a, cl_device_type b, cl_uint c, cl_device_id *d, cl_uint *e), (a,b,c,d,e))
    PROXY_FUNC(clGetDeviceInfo, cl_int, (cl_device_id a, cl_device_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
    
    cl_context clCreateContext(const cl_context_properties *a, cl_uint b, const cl_device_id *c, void (CL_CALLBACK *d)(const char *, const void *, size_t, void *), void *e, cl_int *f) {
        typedef cl_context (*func_t)(const cl_context_properties *, cl_uint, const cl_device_id *, void (CL_CALLBACK *)(const char *, const void *, size_t, void *), void *, cl_int *);
        static func_t func = (func_t)get_func("clCreateContext");
        if (func) return func(a,b,c,d,e,f);
        if (f) *f = -1; return nullptr;
    }

    PROXY_FUNC(clRetainContext, cl_int, (cl_context a), (a))
    PROXY_FUNC(clReleaseContext, cl_int, (cl_context a), (a))
    PROXY_FUNC(clGetContextInfo, cl_int, (cl_context a, cl_context_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
    
    cl_command_queue clCreateCommandQueue(cl_context a, cl_device_id b, cl_command_queue_properties c, cl_int *d) {
        typedef cl_command_queue (*func_t)(cl_context, cl_device_id, cl_command_queue_properties, cl_int *);
        static func_t func = (func_t)get_func("clCreateCommandQueue");
        if (func) return func(a,b,c,d);
        if (d) *d = -1; return nullptr;
    }

    PROXY_FUNC(clRetainCommandQueue, cl_int, (cl_command_queue a), (a))
    PROXY_FUNC(clReleaseCommandQueue, cl_int, (cl_command_queue a), (a))
    
    cl_mem clCreateBuffer(cl_context a, cl_mem_flags b, size_t c, void *d, cl_int *e) {
        typedef cl_mem (*func_t)(cl_context, cl_mem_flags, size_t, void *, cl_int *);
        static func_t func = (func_t)get_func("clCreateBuffer");
        if (func) return func(a,b,c,d,e);
        if (e) *e = -1; return nullptr;
    }

    PROXY_FUNC(clRetainMemObject, cl_int, (cl_mem a), (a))
    PROXY_FUNC(clReleaseMemObject, cl_int, (cl_mem a), (a))
    
    cl_program clCreateProgramWithSource(cl_context a, cl_uint b, const char **c, const size_t *d, cl_int *e) {
        typedef cl_program (*func_t)(cl_context, cl_uint, const char **, const size_t *, cl_int *);
        static func_t func = (func_t)get_func("clCreateProgramWithSource");
        if (func) return func(a,b,c,d,e);
        if (e) *e = -1; return nullptr;
    }

    PROXY_FUNC(clReleaseProgram, cl_int, (cl_program a), (a))
    PROXY_FUNC(clBuildProgram, cl_int, (cl_program a, cl_uint b, const cl_device_id *c, const char *d, void (CL_CALLBACK *e)(cl_program, void *), void *p_f), (a,b,c,d,e,p_f))
    PROXY_FUNC(clGetProgramBuildInfo, cl_int, (cl_program a, cl_device_id b, cl_program_build_info c, size_t d, void *e, size_t *p_f), (a,b,c,d,e,p_f))
    
    cl_kernel clCreateKernel(cl_program a, const char *b, cl_int *c) {
        typedef cl_kernel (*func_t)(cl_program, const char *, cl_int *);
        static func_t func = (func_t)get_func("clCreateKernel");
        if (func) return func(a,b,c);
        if (c) *c = -1; return nullptr;
    }

    PROXY_FUNC(clReleaseKernel, cl_int, (cl_kernel a), (a))
    PROXY_FUNC(clSetKernelArg, cl_int, (cl_kernel a, cl_uint b, size_t c, const void *d), (a,b,c,d))
    PROXY_FUNC(clGetKernelWorkGroupInfo, cl_int, (cl_kernel a, cl_device_id b, cl_kernel_work_group_info c, size_t d, void *e, size_t *p_f), (a,b,c,d,e,p_f))
    PROXY_FUNC(clFinish, cl_int, (cl_command_queue a), (a))
    PROXY_FUNC(clFlush, cl_int, (cl_command_queue a), (a))
    PROXY_FUNC(clEnqueueReadBuffer, cl_int, (cl_command_queue a, cl_mem b, cl_bool c, size_t d, size_t e, void *p_f, cl_uint g, const cl_event *h, cl_event *i), (a,b,c,d,e,p_f,g,h,i))
    PROXY_FUNC(clEnqueueWriteBuffer, cl_int, (cl_command_queue a, cl_mem b, cl_bool c, size_t d, size_t e, const void *p_f, cl_uint g, const cl_event *h, cl_event *i), (a,b,c,d,e,p_f,g,h,i))
    PROXY_FUNC(clEnqueueCopyBuffer, cl_int, (cl_command_queue a, cl_mem b, cl_mem c, size_t d, size_t e, size_t p_f, cl_uint g, const cl_event *h, cl_event *i), (a,b,c,d,e,p_f,g,h,i))
    PROXY_FUNC(clEnqueueNDRangeKernel, cl_int, (cl_command_queue a, cl_kernel b, cl_uint c, const size_t *d, const size_t *e, const size_t *p_f, cl_uint g, const cl_event *h, cl_event *i), (a,b,c,d,e,p_f,g,h,i))
    PROXY_FUNC(clWaitForEvents, cl_int, (cl_uint a, const cl_event *b), (a,b))
    PROXY_FUNC(clGetEventProfilingInfo, cl_int, (cl_event a, cl_profiling_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
    PROXY_FUNC(clReleaseEvent, cl_int, (cl_event a), (a))

    PROXY_FUNC(clGetProgramInfo, cl_int, (cl_program a, cl_program_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
    PROXY_FUNC(clGetEventInfo, cl_int, (cl_event a, cl_event_info b, size_t c, void *d, size_t *e), (a,b,c,d,e))
}
