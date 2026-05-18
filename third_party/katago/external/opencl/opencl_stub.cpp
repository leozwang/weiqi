#include <CL/cl.h>

// Extended stub for Android linking

extern "C" {
    cl_int clGetPlatformIDs(cl_uint a, cl_platform_id *b, cl_uint *c) { return 0; }
    cl_int clGetPlatformInfo(cl_platform_id a, cl_platform_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_int clGetDeviceIDs(cl_platform_id a, cl_device_type b, cl_uint c, cl_device_id *d, cl_uint *e) { return 0; }
    cl_int clGetDeviceInfo(cl_device_id a, cl_device_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_context clCreateContext(const cl_context_properties *a, cl_uint b, const cl_device_id *c, void (CL_CALLBACK *d)(const char *, const void *, size_t, void *), void *e, cl_int *f) { if(f)*f=0; return 0; }
    cl_context clCreateContextFromType(const cl_context_properties *a, cl_device_type b, void (CL_CALLBACK *c)(const char *, const void *, size_t, void *), void *d, cl_int *e) { if(e)*e=0; return 0; }
    cl_int clRetainContext(cl_context a) { return 0; }
    cl_int clReleaseContext(cl_context a) { return 0; }
    cl_int clGetContextInfo(cl_context a, cl_context_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_command_queue clCreateCommandQueue(cl_context a, cl_device_id b, cl_command_queue_properties c, cl_int *d) { if(d)*d=0; return 0; }
    cl_int clRetainCommandQueue(cl_command_queue a) { return 0; }
    cl_int clReleaseCommandQueue(cl_command_queue a) { return 0; }
    cl_int clGetCommandQueueInfo(cl_command_queue a, cl_command_queue_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_mem clCreateBuffer(cl_context a, cl_mem_flags b, size_t c, void *d, cl_int *e) { if(e)*e=0; return 0; }
    cl_int clRetainMemObject(cl_mem a) { return 0; }
    cl_int clReleaseMemObject(cl_mem a) { return 0; }
    cl_int clGetMemObjectInfo(cl_mem a, cl_mem_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_program clCreateProgramWithSource(cl_context a, cl_uint b, const char **c, const size_t *d, cl_int *e) { if(e)*e=0; return 0; }
    cl_program clCreateProgramWithBinary(cl_context a, cl_uint b, const cl_device_id *c, const size_t *d, const unsigned char **e, cl_int *f, cl_int *g) { if(g)*g=0; return 0; }
    cl_int clRetainProgram(cl_program a) { return 0; }
    cl_int clReleaseProgram(cl_program a) { return 0; }
    cl_int clBuildProgram(cl_program a, cl_uint b, const cl_device_id *c, const char *d, void (CL_CALLBACK *e)(cl_program, void *), void *f) { return 0; }
    cl_int clGetProgramInfo(cl_program a, cl_program_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_int clGetProgramBuildInfo(cl_program a, cl_device_id b, cl_program_build_info c, size_t d, void *e, size_t *f) { return 0; }
    cl_kernel clCreateKernel(cl_program a, const char *b, cl_int *c) { if(c)*c=0; return 0; }
    cl_int clCreateKernelsInProgram(cl_program a, cl_uint b, cl_kernel *c, cl_uint *d) { return 0; }
    cl_int clRetainKernel(cl_kernel a) { return 0; }
    cl_int clReleaseKernel(cl_kernel a) { return 0; }
    cl_int clSetKernelArg(cl_kernel a, cl_uint b, size_t c, const void *d) { return 0; }
    cl_int clGetKernelInfo(cl_kernel a, cl_kernel_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_int clGetKernelWorkGroupInfo(cl_kernel a, cl_device_id b, cl_kernel_work_group_info c, size_t d, void *e, size_t *f) { return 0; }
    cl_int clWaitForEvents(cl_uint a, const cl_event *b) { return 0; }
    cl_int clGetEventInfo(cl_event a, cl_event_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_int clRetainEvent(cl_event a) { return 0; }
    cl_int clReleaseEvent(cl_event a) { return 0; }
    cl_int clGetEventProfilingInfo(cl_event a, cl_profiling_info b, size_t c, void *d, size_t *e) { return 0; }
    cl_int clFlush(cl_command_queue a) { return 0; }
    cl_int clFinish(cl_command_queue a) { return 0; }
    cl_int clEnqueueReadBuffer(cl_command_queue a, cl_mem b, cl_bool c, size_t d, size_t e, void *f, cl_uint g, const cl_event *h, cl_event *i) { return 0; }
    cl_int clEnqueueWriteBuffer(cl_command_queue a, cl_mem b, cl_bool c, size_t d, size_t e, const void *f, cl_uint g, const cl_event *h, cl_event *i) { return 0; }
    cl_int clEnqueueCopyBuffer(cl_command_queue a, cl_mem b, cl_mem c, size_t d, size_t e, size_t f, cl_uint g, const cl_event *h, cl_event *i) { return 0; }
    cl_int clEnqueueNDRangeKernel(cl_command_queue a, cl_kernel b, cl_uint c, const size_t *d, const size_t *e, const size_t *f, cl_uint g, const cl_event *h, cl_event *i) { return 0; }
    cl_int clEnqueueMarker(cl_command_queue a, cl_event *b) { return 0; }
    cl_int clEnqueueBarrier(cl_command_queue a) { return 0; }
    void* clGetExtensionFunctionAddress(const char *a) { return 0; }
}
