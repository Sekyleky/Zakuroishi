// C interface between Zakuroishi and lsfg-vk 1.0's frame generation, on the device the renderer
// draws on. The game loads it with dlopen only while the frame_generation setting is on; the launcher
// loads it to translate an import.
#include "translate.hpp"
#include "lsfg_3_1p.hpp"

#include <android/log.h>
#include <jni.h>

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

/// translates the user's Lossless.dll into dir/spv, all of it or nothing: a failure keeps the shaders
/// already there. returns null, or why it failed. the caller deletes the DLL.
EXPORT jstring Java_io_github_zakuro_android_LosslessImport_translate(JNIEnv* env, jclass, jstring jdll, jstring jdir) {
    const char* dllChars = env->GetStringUTFChars(jdll, nullptr);
    const char* dirChars = env->GetStringUTFChars(jdir, nullptr);
    std::string failure;
    try {
        const auto shaders = zlsfg::translateDll(dllChars);
        const fs::path spv = fs::path(dirChars) / "spv", fresh = fs::path(dirChars) / "spv.new";
        fs::remove_all(fresh);
        fs::create_directories(fresh);
        for (const auto& [name, code] : shaders) {
            std::ofstream out(fresh / (name + ".spv"), std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(code.data()), static_cast<std::streamsize>(code.size()));
            if (!out) throw std::runtime_error("could not store a translated shader");
        }
        fs::remove_all(spv);
        fs::rename(fresh, spv);
    } catch (const std::exception& e) {
        logFailure("import", e);
        failure = e.what();
    } catch (...) {
        failure = "unexpected failure";
    }
    env->ReleaseStringUTFChars(jdll, dllChars);
    env->ReleaseStringUTFChars(jdir, dirChars);
    return failure.empty() ? nullptr : env->NewStringUTF(failure.c_str());
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
