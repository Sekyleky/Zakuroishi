// The id table and the binding renumbering come from lsfg-vk 1.0
// (src/extract/extract.cpp, src/extract/trans.cpp), MIT. Only DXBC ids 255-302 are read.
#include "translate.hpp"

#include <pe-parse/parse.h>

#include <dxbc/dxbc_modinfo.h>
#include <dxbc/dxbc_module.h>
#include <dxbc/dxbc_reader.h>

#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>

namespace {

const std::vector<std::pair<const char*, uint32_t>> table = {
    { "p_mipmaps", 255 }, { "p_generate", 256 },
    { "p_alpha[0]", 290 }, { "p_alpha[1]", 291 }, { "p_alpha[2]", 292 }, { "p_alpha[3]", 293 },
    { "p_beta[0]", 298 }, { "p_beta[1]", 299 }, { "p_beta[2]", 300 }, { "p_beta[3]", 301 }, { "p_beta[4]", 302 },
    { "p_gamma[0]", 280 }, { "p_gamma[1]", 282 }, { "p_gamma[2]", 283 }, { "p_gamma[3]", 284 }, { "p_gamma[4]", 285 },
    { "p_delta[0]", 280 }, { "p_delta[1]", 286 }, { "p_delta[2]", 287 }, { "p_delta[3]", 288 }, { "p_delta[4]", 289 },
    { "p_delta[5]", 281 }, { "p_delta[6]", 294 }, { "p_delta[7]", 295 }, { "p_delta[8]", 296 }, { "p_delta[9]", 297 },
};

int onResource(void* out, const peparse::resource& res) {
    if (res.type == peparse::RT_RCDATA && res.buf != nullptr && res.buf->bufLen > 0) {
        auto& resources = *static_cast<std::unordered_map<uint32_t, std::vector<uint8_t>>*>(out);
        resources[res.name].assign(res.buf->buf, res.buf->buf + res.buf->bufLen);
    }
    return 0;
}

struct Binding { uint32_t index{}; uint32_t offset{}; };

std::vector<uint8_t> translate(const std::vector<uint8_t>& bytecode) {
    dxvk::DxbcReader reader(reinterpret_cast<const char*>(bytecode.data()), bytecode.size());
    dxvk::DxbcModule module(reader);
    const dxvk::DxbcModuleInfo info{};
    auto shader = module.compile(info, "CS");
    auto& code = shader->code();

    // the descriptor layouts number their bindings in order of declaration
    std::vector<Binding> bindings;
    std::vector<uint32_t> ids;
    for (auto ins : code) {
        if (ins.opCode() == spv::OpDecorate && ins.arg(2) == spv::DecorationBinding) {
            const uint32_t id = ins.arg(1);
            bindings.resize(std::max(bindings.size(), size_t(id + 1)));
            bindings[id] = { ins.arg(3), ins.offset() + 3 };
            ids.push_back(id);
        }
        if (ins.opCode() == spv::OpFunction)
            break;
    }
    for (size_t i = 0; i < ids.size(); i++)
        code.data()[bindings[ids[i]].offset] = static_cast<uint32_t>(i);

    std::vector<uint8_t> spirv(code.size());
    std::copy_n(reinterpret_cast<const uint8_t*>(code.data()), code.size(), spirv.data());
    return spirv;
}

}

std::map<std::string, std::vector<uint8_t>> zlsfg::translateDll(const std::string& path) {
    peparse::parsed_pe* dll = peparse::ParsePEFromFile(path.c_str());
    if (dll == nullptr)
        throw std::runtime_error("not a readable Lossless.dll");
    std::unordered_map<uint32_t, std::vector<uint8_t>> resources;
    peparse::IterRsrc(dll, onResource, &resources);
    peparse::DestructParsedPE(dll);

    std::map<std::string, std::vector<uint8_t>> out;
    for (const auto& [name, id] : table) {
        auto found = resources.find(id);
        if (found == resources.end())
            throw std::runtime_error(std::string("shader not found: ") + name);
        try {
            out[name] = translate(found->second);
        } catch (const dxvk::DxvkError& e) { // not a std::exception
            throw std::runtime_error(std::string(name) + ": " + e.message());
        }
    }
    return out;
}
