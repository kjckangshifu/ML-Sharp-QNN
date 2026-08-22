#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "gf/core/metadata.h"

namespace gf {

using AttributeArray = std::vector<float>;

struct GaussianCloudIR {
    int32_t numPoints = 0;

    std::vector<float> positions;   // [x0,y0,z0, x1,y1,z1, ...] (3*N)
    std::vector<float> scales;      // Log-scale [sx0,sy0,sz0, ...] (3*N)
    std::vector<float> rotations;   // Quaternions [w,x,y,z] per point (4*N)
    std::vector<float> alphas;      // Pre-sigmoid opacity [a0,a1,...] (N)
    std::vector<float> colors;      // SH degree-0 DC RGB interleaved (3*N)
    std::vector<float> sh;          // Higher-order SH (degree>=1)
    std::unordered_map<std::string, AttributeArray> extras;
    GaussMetadata meta;
};

inline int ShCoeffsPerPoint(int degree) {
    if (degree <= 0) return 0;
    const int per_channel = (degree + 1) * (degree + 1) - 1;
    return per_channel * 3;
}

} // namespace gf