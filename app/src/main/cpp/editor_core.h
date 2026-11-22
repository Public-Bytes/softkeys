// Runs the smart cleanup model with llama.cpp. The JNI layer in editor_jni.cpp wraps these
// functions for SmartEditor.java, and bench/editor/editor_main.cpp runs them on a desktop.
#pragma once

#include "llama.h"
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

namespace softkeys {

struct Editor {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string chat_template;
};

inline Editor *editor_load(const char *path, int threads, int context_size) {
    static bool initialized = false;
    if (!initialized) {
        llama_backend_init();
        initialized = true;
    }
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, model_params);
    if (!model) return nullptr;
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = context_size;
    context_params.n_batch = context_size;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = true;
    llama_context *context = llama_init_from_model(model, context_params);
    if (!context) {
        llama_model_free(model);
        return nullptr;
    }
    auto *editor = new Editor();
    editor->model = model;
    editor->context = context;
    editor->vocab = llama_model_get_vocab(model);
    const char *chat_template = llama_model_chat_template(model, nullptr);
    editor->chat_template = chat_template ? chat_template : "chatml";
    return editor;
}

inline void editor_free(Editor *editor) {
    if (!editor) return;
    if (editor->context) llama_free(editor->context);
    if (editor->model) llama_model_free(editor->model);
    delete editor;
}

/**
 * Edits one transcript. The prompt is the model's chat template with an empty system
 * message and the transcript as the user message, which is how the model card measured
 * its best score with llama.cpp. Decoding is greedy. It stops at the end-of-turn token,
 * after max_tokens tokens, or when timeout_ms passes, and returns an empty string with
 * ok set to false when it did not finish.
 */
inline std::string editor_edit(Editor *editor, const std::string &text, int max_tokens, long timeout_ms, bool &ok) {
    ok = false;
    auto start = std::chrono::steady_clock::now();
    auto expired = [&]() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count() > timeout_ms;
    };
    llama_chat_message messages[2] = {{"system", ""}, {"user", text.c_str()}};
    std::vector<char> prompt(text.size() * 2 + 256);
    int length = llama_chat_apply_template(editor->chat_template.c_str(), messages, 2, true, prompt.data(), (int32_t) prompt.size());
    if (length > (int) prompt.size()) {
        prompt.resize(length);
        length = llama_chat_apply_template(editor->chat_template.c_str(), messages, 2, true, prompt.data(), (int32_t) prompt.size());
    }
    if (length < 0) return "";
    std::string formatted(prompt.data(), length);

    int capacity = (int) formatted.size() + 16;
    std::vector<llama_token> tokens(capacity);
    int count = llama_tokenize(editor->vocab, formatted.c_str(), (int32_t) formatted.size(), tokens.data(), capacity, true, true);
    if (count < 0) return "";
    tokens.resize(count);
    if ((int) tokens.size() + max_tokens > (int) llama_n_ctx(editor->context)) return "";

    llama_memory_clear(llama_get_memory(editor->context), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(editor->context, batch) != 0) return "";

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string output;
    bool finished = false;
    for (int i = 0; i < max_tokens; i++) {
        if (expired()) break;
        llama_token token = llama_sampler_sample(sampler, editor->context, -1);
        if (llama_vocab_is_eog(editor->vocab, token)) {
            finished = true;
            break;
        }
        char piece[256];
        int n = llama_token_to_piece(editor->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) output.append(piece, n);
        llama_token next = token;
        batch = llama_batch_get_one(&next, 1);
        if (llama_decode(editor->context, batch) != 0) break;
    }
    llama_sampler_free(sampler);
    ok = finished;
    return finished ? output : "";
}

}  // namespace softkeys
