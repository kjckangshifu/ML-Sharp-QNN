// sharp_pipeline.h — library interface for the SHARP core
// Extracted from pipeline.c's main(); exposes pre/merge/post as callable functions
// Called from the JNI layer; no command-line parsing
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Preprocess: image.raw -> 35×patch_p*.raw + x2.raw + input_list_*.txt
//               input image.raw [1,3,1536,1536] NCHW f32
//          work directory (outputs are written here)
// Returns 0 on success, non-zero on failure
int sharp_pre(const char* imageRawPath, const char* workDir);

// Merge: HTP outputs -> 6 merged feature raws
// work directory
//           patch_encoder output directory (contains Result_0..34/)
//           image_encoder output directory (contains Result_0/)
// Returns 0 on success, non-zero on failure
int sharp_merge(const char* workDir, const char* peOutDir, const char* ieOutDir);

// Post: delta + disparity + image → output.ply
// Post: delta + disparity + image -> output.ply
//          work directory (contains image.raw, disparity.raw, delta.raw)
// focal length in pixels
// original image width and height
// output PLY file path
// Returns 0 on success, non-zero on failure
int sharp_post(const char* workDir, float fpx, int origW, int origH, const char* outPlyPath);

// Preprocess image: decode JPEG/PNG -> EXIF rotation/focal length -> resize 1536 -> image.raw
// input image path
//             output image.raw path [1,3,1536,1536] NCHW f32 in [0,1]
// output focal length in pixels
//             output disparity_factor = f_px / orig_w
//                     output original image size (after EXIF rotation)
// Returns 0 on success, non-zero on failure
int sharp_prep_image(const char* imagePath, const char* outRawPath,
                     float* outFpx, float* outDfactor, int* outOrigW, int* outOrigH);

// ==============  ==============
// ============== Progress callback ==============
// Progress callback function type
// stage ID (see constants below)
// stage name
// current progress
// total
// elapsed time (ms)
typedef void (*SharpProgressCallback)(int stageId, const char* stageName,
                                       int current, int total, long elapsedMs,
                                       const char* detail);

// Sets the global progress callback (thread-safe)
void sharp_set_progress_callback(SharpProgressCallback cb);

// Stage ID constants (one-to-one with Kotlin PipelineState.kt DEFAULT_STAGES)
#define SHARP_STAGE_PREP_IMAGE   0  // image decode
#define SHARP_STAGE_PRE          1  // preprocess + patch split
#define SHARP_STAGE_PE_INFER     2  // patch_encoder HTP inference
#define SHARP_STAGE_IE_INFER     3  // image_encoder HTP inference
#define SHARP_STAGE_MERGE        4  // feature merge
#define SHARP_STAGE_REST_A       5  // REST Seg A (feature fusion)
#define SHARP_STAGE_REST_B       6  // REST Seg B (disparity estimation)
#define SHARP_STAGE_REST_C       7  // REST Seg C (Gaussian deltas)
#define SHARP_STAGE_POST         8  // postprocess (point cloud)
#define SHARP_STAGE_PLY_OPTIMIZE 9  // PLY optimization

// Low-level progress callback (for direct JNI calls, bypassing sharp_set_progress_callback)
void cbProgress(int stageId, int current, int total, long elapsedMs, const char* detail);
void cbStageComplete(int stageId, const char* stageName, long elapsedMs);

#ifdef __cplusplus
}
#endif
