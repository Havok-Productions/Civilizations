package dev.civilizations.learning;

import com.google.gson.Gson;
import dev.coreai.recovery.RecoveryLearning;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Local proposals and measured outcomes, invoked on the recovery IO worker, never a region tick.
 */
public final class RecoveryController {
  private final Path root;
  private final RecoveryLearning learning;
  private final Set<String> failures = new HashSet<>();

  public RecoveryController(Path root) throws IOException {
    this.root = root;
    learning = new RecoveryLearning(root);
    Path file = root.resolve("failed-programs.json");
    if (Files.exists(file))
      failures.addAll(Arrays.asList(new Gson().fromJson(Files.readString(file), String[].class)));
  }

  public VoxelRecoveryPlanner.Candidate propose(String snapshot, SkillContext context)
      throws IOException {
    var evaluation = VoxelRecoveryPlanner.evaluate(context);
    RecoveryReplay.save(root.resolve("snapshots"), snapshot, context, evaluation);
    return evaluation.candidates().stream()
        .filter(c -> !failures.contains(key(context, c)))
        .findFirst()
        .orElse(null);
  }

  public double shadowScore(VoxelRecoveryPlanner.Candidate candidate) {
    return learning.score(candidate.features());
  }

  public void outcome(
      String id,
      SkillContext context,
      VoxelRecoveryPlanner.Candidate candidate,
      boolean success,
      String reason)
      throws IOException {
    if (!success) {
      failures.add(key(context, candidate));
      Path temp = root.resolve("failed-programs.tmp");
      Files.writeString(temp, new Gson().toJson(failures));
      Files.move(temp, root.resolve("failed-programs.json"), StandardCopyOption.REPLACE_EXISTING);
    }
    learning.outcome(
        new RecoveryLearning.Sample(
            id, candidate.group(), candidate.strategy(), candidate.features(), success, reason));
  }

  private static String key(SkillContext context, VoxelRecoveryPlanner.Candidate candidate) {
    return context.reuseKey() + ":" + new Gson().toJson(candidate.program().steps());
  }
}
