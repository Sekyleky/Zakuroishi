// Finds the frame generation shaders in a user-supplied Lossless.dll and translates them to
// SPIR-V, all at run time on the user's device. Nothing from the DLL is ever bundled.
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace zlsfg {
    /// every shader lsfg-vk 1.0's performance graph asks for, by the name it asks with.
    /// throws std::exception when the file is not a DLL with those shaders.
    std::map<std::string, std::vector<uint8_t>> translateDll(const std::string& path);
}
