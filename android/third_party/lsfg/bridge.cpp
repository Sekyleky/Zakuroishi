// C interface between Zakuroishi and lsfg-vk 1.0's frame generation, on the device the renderer
// draws on. Loaded with dlopen only while the frame_generation setting is on.
#include "translate.hpp"
#include "lsfg_3_1p.hpp"

#include <android/log.h>

#include <exception>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#define EXPORT extern "C" __attribute__((visibility("default")))

namespace fs = std::filesystem;

namespace {
void logFailure(const char* where, const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, "Zakuro", "frame generation: %s: %s", where, e.what());
}
}

/// translates an imported files/lsfg/Lossless.dll once, keeps only the SPIR-V beside it and deletes
/// the DLL. returns the number of shaders ready, negative on failure.
EXPORT int zlsfg_prepare(const char* dir) {
    try {
        const fs::path root(dir);
        const fs::path dll = root / "Lossless.dll";
        const fs::path spv = root / "spv";
        if (fs::exists(dll)) {
            fs::create_directories(spv);
            for (const auto& [name, code] : zlsfg::translateDll(dll.string())) {
                std::ofstream out(spv / (name + ".spv"), std::ios::binary | std::ios::trunc);
                out.write(reinterpret_cast<const char*>(code.data()), static_cast<std::streamsize>(code.size()));
                if (!out) throw std::runtime_error("could not store a translated shader");
            }
            fs::remove(dll);
        }
        if (!fs::exists(spv)) return 0;
        int count = 0;
        for (const auto& entry : fs::directory_iterator(spv)) count += entry.path().extension() == ".spv";
        return count;
    } catch (const std::exception& e) {
        logFailure("import", e);
        return -1;
    }
}

EXPORT int zlsfg_init(PFN_vkGetInstanceProcAddr gipa, VkInstance instance, VkPhysicalDevice physical,
        VkDevice device, uint32_t family, VkQueue queue, float flow_scale, uint64_t generated, const char* dir) {
    try {
        const fs::path spv = fs::path(dir) / "spv";
        LSFG_3_1P::initializeExternal(gipa, instance, physical, device, family, queue, false, flow_scale, generated,
            [spv](const std::string& name) {
                std::ifstream in(spv / (name + ".spv"), std::ios::binary);
                if (!in) throw std::runtime_error("missing translated shader " + name);
                return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
            });
        return 0;
    } catch (const std::exception& e) {
        logFailure("init", e);
        return -1;
    }
}

/// a graph over the caller's images, all in the general layout. returns its id, or -1.
EXPORT int zlsfg_create(VkImage in0, VkImage in1, const VkImage* out, uint32_t out_count,
        uint32_t width, uint32_t height, VkFormat format) {
    try {
        return LSFG_3_1P::createContextFromImages(in0, in1, std::vector<VkImage>(out, out + out_count),
            VkExtent2D{ width, height }, format);
    } catch (const std::exception& e) {
        logFailure("create", e);
        return -1;
    }
}

/// submits the generated frames between the two inputs to the queue, after what is on it already.
EXPORT int zlsfg_run(int id) {
    try {
        LSFG_3_1P::presentContext(id, -1, {});
        return 0;
    } catch (const std::exception& e) {
        logFailure("run", e);
        return -1;
    }
}

EXPORT void zlsfg_finish() {
    try {
        LSFG_3_1P::finalize();
    } catch (const std::exception& e) {
        logFailure("finish", e);
    }
}
