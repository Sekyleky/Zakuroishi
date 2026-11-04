// Stand-in for DXVK's logger: compiler diagnostics are dropped.
#pragma once
#include <string>
namespace dxvk {
  struct Logger {
    static void trace(const std::string&) { }
    static void debug(const std::string&) { }
    static void info (const std::string&) { }
    static void warn (const std::string&) { }
    static void err  (const std::string&) { }
  };
}
