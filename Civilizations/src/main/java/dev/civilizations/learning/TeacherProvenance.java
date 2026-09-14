package dev.civilizations.learning;

import dev.coreai.PolicyLibrary;
import org.bukkit.configuration.file.FileConfiguration;

public final class TeacherProvenance {
  private TeacherProvenance() {}

  public static PolicyLibrary.Provenance read(FileConfiguration config, String prefix) {
    String sha = config.getString(prefix + ".model.sha256", "");
    String license =
        switch (sha) {
          case "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4" ->
              "Qwen3.5 Apache-2.0; quantization provenance in artifact URL";
          case "a86349a4180c4e6bb43f874c29c404fa2be3f90b15509bd6d86f697dba724ec1" ->
              "DeepSeek MIT; Qwen3 base Apache-2.0; see upstream model card";
          default -> "unreviewed: check exact model license before training or redistribution";
        };
    return new PolicyLibrary.Provenance(
        config.getString(prefix + ".model-id", "unknown"),
        config.getString(prefix + ".model.url", "local unspecified"),
        sha,
        license,
        "local teacher-generated candidate logic; no neural weight training");
  }
}
