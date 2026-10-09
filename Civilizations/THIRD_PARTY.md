# Third-party components

The new Civilizations source is under MIT; upstream components retain their own licenses.

Bundled in the shaded JAR (Apache License 2.0):

- Google Gson 2.13.2 — https://github.com/google/gson
- Google Error Prone annotations 2.41.0 — https://github.com/google/error-prone
- Apache Commons Compress 1.27.1 — https://commons.apache.org/proper/commons-compress/
- Apache Commons IO 2.16.1 — https://commons.apache.org/proper/commons-io/
- Apache Commons Codec 1.17.1 — https://commons.apache.org/proper/commons-codec/
- Apache Commons Lang 3.16.0 — https://commons.apache.org/proper/commons-lang/

Their license and notice resources are retained in `META-INF` in the JAR, with Apache NOTICE files concatenated. Gson and Commons Java packages are relocated to avoid plugin dependency collisions. Folia and JUnit are not bundled.

Downloaded separately at server runtime:

- llama.cpp b10948 — MIT, https://github.com/ggml-org/llama.cpp/blob/b10948/LICENSE
- Qwen3.5-4B model — Apache 2.0, https://huggingface.co/Qwen/Qwen3.5-4B
- Unsloth GGUF quantization — https://huggingface.co/unsloth/Qwen3.5-4B-GGUF
- Optional NVIDIA CUDA runtime binaries carry NVIDIA's distribution terms in their upstream archive. The default uses Vulkan; CUDA is opt-in.

The model and native runtime are not embedded in the distributed plugin or source ZIP. Pinned download locations, file size, and model SHA-256 are recorded in `src/main/resources/config.yml`; runtime archive SHA-256 values are read from the pinned GitHub release's asset metadata and verified before extraction.
