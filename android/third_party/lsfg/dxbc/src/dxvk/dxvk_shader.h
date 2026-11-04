// Stand-in for DXVK's device layer: the DXBC compiler only needs these types.
// DxvkAccessOp and DxvkBindingInfo come from src/dxvk/dxvk_pipelayout.h (DXVK 2.6.2).
#pragma once

#include <cstdint>
#include <optional>
#include <vulkan/vulkan.h>

#include "../spirv/spirv_code_buffer.h"
#include "../util/rc/util_rc.h"
#include "../util/rc/util_rc_ptr.h"
#include "../util/util_error.h"
#include "../util/util_flags.h"
#include "../util/util_small_vector.h"

namespace dxvk {

  struct DxvkAccessOp {
    enum OpType : uint16_t {
      None      = 0x0u,
      Or        = 0x1u,
      And       = 0x2u,
      Xor       = 0x3u,
      Add       = 0x4u,
      IMin      = 0x5u,
      IMax      = 0x6u,
      UMin      = 0x7u,
      UMax      = 0x8u,

      StoreF    = 0xdu,
      StoreUi   = 0xeu,
      StoreSi   = 0xfu,
    };

    DxvkAccessOp() = default;
    DxvkAccessOp(OpType t)
    : op(uint16_t(t)) { }

    DxvkAccessOp(OpType t, uint16_t constant)
    : op(uint16_t(t) | (constant << 4u)) { }

    uint16_t op = 0u;

    bool operator == (const DxvkAccessOp& t) const { return op == t.op; }
    bool operator != (const DxvkAccessOp& t) const { return op != t.op; }

    template<typename T, std::enable_if_t<std::is_integral_v<T>, bool> = true>
    explicit operator T() const { return op; }
  };

  struct DxvkBindingInfo {
    VkDescriptorType      descriptorType  = VK_DESCRIPTOR_TYPE_MAX_ENUM;
    uint32_t              resourceBinding = 0u;
    VkImageViewType       viewType        = VK_IMAGE_VIEW_TYPE_MAX_ENUM;
    VkShaderStageFlagBits stage           = VK_SHADER_STAGE_FLAG_BITS_MAX_ENUM;
    VkAccessFlags         access          = 0u;
    DxvkAccessOp          accessOp        = DxvkAccessOp::None;
    bool                  uboSet          = false;
    bool                  isMultisampled  = false;
  };

  struct DxvkShaderCreateInfo {
    VkShaderStageFlagBits stage;
    uint32_t bindingCount = 0;
    const DxvkBindingInfo* bindings = nullptr;
    uint32_t inputMask = 0;
    uint32_t outputMask = 0;
    uint32_t flatShadingInputs = 0;
    VkShaderStageFlags pushConstStages = 0;
    uint32_t pushConstSize = 0;
    int32_t xfbRasterizedStream = 0;
    uint32_t patchVertexCount = 0;
    uint32_t xfbStrides[4] = { };
    VkPrimitiveTopology inputTopology = VK_PRIMITIVE_TOPOLOGY_MAX_ENUM;
    VkPrimitiveTopology outputTopology = VK_PRIMITIVE_TOPOLOGY_MAX_ENUM;
  };

  class DxvkShader : public RcObject {
  public:
    DxvkShader(const DxvkShaderCreateInfo&, SpirvCodeBuffer&& code) : m_code(std::move(code)) { }
    SpirvCodeBuffer& code() { return m_code; }
  private:
    SpirvCodeBuffer m_code;
  };

}
