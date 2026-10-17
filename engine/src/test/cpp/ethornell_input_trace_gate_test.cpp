#include "../../main/cpp/ethornell_input_trace_gate.h"
#include <cstdio>
#include <cstdlib>
#include <string>

static void require(bool value) { if (!value) std::abort(); }
int main() {
    char root[] = "/private/var/folders/f7/8jzwqrf15g56w446cgw2txb40000gn/T/opencode/ethornell-trace-XXXXXX";
    require(mkdtemp(root) != nullptr);
    std::string metadata = std::string(root) + "/ethornell_debug";
    std::string sentinel = metadata + "/.ethornell-input-trace";
    require(!ethornell_bridge::inputTraceApproved(root));
    require(mkdir(metadata.c_str(), 0700) == 0);
    int fd = open(sentinel.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0600); require(fd >= 0); close(fd);
#ifdef NDEBUG
    require(!ethornell_bridge::inputTraceApproved(root));
#else
    require(ethornell_bridge::inputTraceApproved(root));
    require(chmod(sentinel.c_str(), 0666) == 0);
    require(!ethornell_bridge::inputTraceApproved(root));
    require(chmod(sentinel.c_str(), 0600) == 0);
    std::string alias = metadata + "/alias";
    require(rename(sentinel.c_str(), alias.c_str()) == 0);
    require(symlink(alias.c_str(), sentinel.c_str()) == 0);
    require(!ethornell_bridge::inputTraceApproved(root));
    require(unlink(sentinel.c_str()) == 0);
    require(rename(alias.c_str(), sentinel.c_str()) == 0);
    require(chmod(metadata.c_str(), 0777) == 0);
    require(!ethornell_bridge::inputTraceApproved(root));
#endif
    require(unlink(sentinel.c_str()) == 0); require(rmdir(metadata.c_str()) == 0); require(rmdir(root) == 0);
}
