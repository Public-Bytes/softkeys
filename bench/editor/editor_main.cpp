// Runs the smart cleanup model on a desktop with the same code as the app (editor_core.h).
//
//   g++ -O2 -std=c++17 -I<llama.cpp>/include -I<llama.cpp>/ggml/include bench/editor/editor_main.cpp \
//     -L<llama.cpp>/build/bin -lllama -lggml -lggml-base -o build/editor-main
//   LD_LIBRARY_PATH=<llama.cpp>/build/bin build/editor-main model.gguf 4 < inputs.txt
//
// Each input line is edited, and the output, the time, and whether it finished are printed.
#include "../../app/src/main/cpp/editor_core.h"
#include <cstdio>
#include <iostream>

int main(int argc, char **argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: editor-main model.gguf [threads]\n"); return 2; }
    int threads = argc > 2 ? std::atoi(argv[2]) : 4;
    auto load = std::chrono::steady_clock::now();
    softkeys::Editor *editor = softkeys::editor_load(argv[1], threads, 1024);
    if (!editor) { std::fprintf(stderr, "load failed\n"); return 1; }
    std::fprintf(stderr, "loaded in %lld ms\n", (long long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - load).count());
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        auto start = std::chrono::steady_clock::now();
        bool ok = false;
        std::string out = softkeys::editor_edit(editor, line, 400, 10000, ok);
        long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        std::printf("[%lld ms%s] %s\n   -> %s\n", ms, ok ? "" : " UNFINISHED", line.c_str(), out.c_str());
        std::fflush(stdout);
    }
    softkeys::editor_free(editor);
}
