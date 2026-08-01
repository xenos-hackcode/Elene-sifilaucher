#include <jni.h>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <netinet/in.h>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

// Layered Frida detection - no single check here is meant to work alone, since a single check
// is trivially defeated by Frida's own anti-detection scripts once an attacker knows what to
// look for. Layering raises the real cost of bypassing all of them at once, which is the actual
// achievable goal - this is a real signal to weigh, not a hard guarantee against a determined
// attacker with Frida's own evasion tooling.
//
// Detection strings are kept XOR-obfuscated in the compiled binary (not the source, which stays
// readable) so a static `strings` pass on the shipped .so doesn't just hand an attacker the exact
// signatures being checked for.
namespace {

constexpr unsigned char XOR_KEY = 0x5A;

template<size_t N>
struct Obf {
  char data[N];
  constexpr explicit Obf(const char (&s)[N]) : data{} {
    for (size_t i = 0; i < N; i++) data[i] = static_cast<char>(s[i] ^ XOR_KEY);
  }
  std::string decode() const {
    std::string out(N - 1, '\0');
    for (size_t i = 0; i < N - 1; i++) out[i] = static_cast<char>(data[i] ^ XOR_KEY);
    return out;
  }
};

// Real Frida signature strings, XOR-obfuscated at compile time via the constexpr helper above.
constexpr Obf fridaAgent("frida-agent");
constexpr Obf fridaGadget("frida-gadget");
constexpr Obf gumJsLoop("gum-js-loop");
constexpr Obf gmainStr("gmain");
constexpr Obf gdbusStr("gdbus");
constexpr Obf linjectorStr("linjector");
constexpr Obf fridaHelper("frida-helper");
constexpr Obf poolFrida("pool-frida");
constexpr Obf reFridaServer("re.frida.server");

bool containsAny(const std::string &haystack, std::initializer_list<std::string> needles) {
  for (const auto &needle : needles) {
    if (!needle.empty() && haystack.find(needle) != std::string::npos) return true;
  }
  return false;
}

// Layer 1: scan our own /proc/self/maps for a loaded Frida agent/gadget library or its
// characteristic thread-pool names showing up as mapped memory regions.
bool scanMaps() {
  std::ifstream maps("/proc/self/maps");
  if (!maps.is_open()) return false;
  std::string line;
  const auto needles = {
      fridaAgent.decode(), fridaGadget.decode(), gumJsLoop.decode(), linjectorStr.decode()
  };
  while (std::getline(maps, line)) {
    if (containsAny(line, needles)) return true;
  }
  return false;
}

// Layer 2: probe Frida's default listen port (27042) on loopback - a real, bound Frida server
// accepts the connection quickly. A short timeout keeps this from ever hanging the app if
// nothing's listening (the overwhelmingly common case).
bool probeFridaPort() {
  int sock = socket(AF_INET, SOCK_STREAM, 0);
  if (sock < 0) return false;

  timeval timeout{};
  timeout.tv_sec = 0;
  timeout.tv_usec = 200000; // 200ms - generous enough for loopback, short enough to never stall
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(27042);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

  bool connected = connect(sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == 0;
  close(sock);
  return connected;
}

// Layer 3: scan our own thread names under /proc/self/task for Frida's characteristic
// gum-js-loop/gmain/gdbus/pool-frida worker threads.
bool scanThreadNames() {
  DIR *dir = opendir("/proc/self/task");
  if (dir == nullptr) return false;

  const auto needles = {
      gumJsLoop.decode(), gmainStr.decode(), gdbusStr.decode(), poolFrida.decode()
  };

  bool found = false;
  dirent *entry;
  while (!found && (entry = readdir(dir)) != nullptr) {
    if (entry->d_name[0] == '.') continue;
    std::string commPath = std::string("/proc/self/task/") + entry->d_name + "/comm";
    std::ifstream commFile(commPath);
    if (!commFile.is_open()) continue;
    std::string name;
    std::getline(commFile, name);
    if (containsAny(name, needles)) found = true;
  }
  closedir(dir);
  return found;
}

// Layer 4: a real Frida server process, if attached to this device (not just this process),
// often runs as re.frida.server or frida-helper - checked via /proc/[pid]/cmdline across all
// processes this app can actually see (Android 11+ package-visibility filtering already limits
// this to a coarse "some process has this cmdline" signal, not a full process list).
bool scanRunningProcesses() {
  DIR *procDir = opendir("/proc");
  if (procDir == nullptr) return false;

  const auto needles = {
      reFridaServer.decode(), fridaHelper.decode(), fridaAgent.decode()
  };

  bool found = false;
  dirent *entry;
  int checked = 0;
  while (!found && checked < 512 && (entry = readdir(procDir)) != nullptr) {
    if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
    checked++;
    std::string cmdlinePath = std::string("/proc/") + entry->d_name + "/cmdline";
    std::ifstream f(cmdlinePath, std::ios::binary);
    if (!f.is_open()) continue;
    std::stringstream ss;
    ss << f.rdbuf();
    std::string cmdline = ss.str();
    if (containsAny(cmdline, needles)) found = true;
  }
  closedir(procDir);
  return found;
}

// Layer 5: a coarse timing heuristic - a tight, otherwise-trivial CPU loop runs measurably
// slower under Frida's own instrumentation (function hooking/tracing overhead) than natively.
// Deliberately treated as the weakest, softest signal of the five - real device thermal/CPU
// scheduling variance can trip this on a genuinely clean device too, so it contributes to the
// overall count rather than being trusted alone.
bool timingAnomaly() {
  constexpr int kIterations = 200000;
  volatile long acc = 0;
  auto start = std::chrono::steady_clock::now();
  for (int i = 0; i < kIterations; i++) {
    acc += (i * 2654435761u) ^ (i >> 3);
  }
  auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(
      std::chrono::steady_clock::now() - start).count();
  // Calibrated loosely against real, uninstrumented Galaxy A54 timings with real headroom -
  // this is a soft signal, not a precise measurement, so the threshold stays generous on
  // purpose to keep false positives rare at the cost of missing subtle instrumentation.
  return elapsedUs > 15000;
}

} // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_scifilauncher_FridaDetector_nativeScan(JNIEnv *, jobject) {
  jint signals = 0;
  if (scanMaps()) signals |= 1;
  if (probeFridaPort()) signals |= 2;
  if (scanThreadNames()) signals |= 4;
  if (scanRunningProcesses()) signals |= 8;
  if (timingAnomaly()) signals |= 16;
  return signals;
}
