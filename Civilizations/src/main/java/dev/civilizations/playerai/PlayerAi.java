package dev.civilizations.playerai;

import dev.civilizations.core.Pos;
import java.util.*;

/**
 * One player's durable brain. Only its entity scheduler may mutate it. World access is behind
 * Actions, so future goals and different player bodies do not depend on Folia or mannequin APIs.
 */
public final class PlayerAi {
  public enum Stage {
    FIND_CAMP,
    GATHER_WOOD,
    CRAFT_PLANKS,
    BUILD_CAMP,
    COMPLETE,
    PAUSED,
    BLOCKED
  }

  public enum Result {
    WAITING,
    DONE,
    SATISFIED,
    BLOCKED
  }

  public record Outcome(Result result, String detail, String material, Pos position) {
    public static Outcome waiting(String detail) {
      return new Outcome(Result.WAITING, detail, null, null);
    }

    public static Outcome blocked(String detail) {
      return new Outcome(Result.BLOCKED, detail, null, null);
    }

    public static Outcome done(String material, Pos position) {
      return new Outcome(Result.DONE, "", material, position);
    }
  }

  public interface Actions {
    Outcome findCamp();

    Outcome gatherWood();

    Outcome place(StarterCamp.Placement placement, String material);

    void halt();

    void checkpoint(State state);
  }

  public record State(
      int schema,
      PlayerAiProfile profile,
      Pos camp,
      int built,
      Map<String, Integer> inventory,
      boolean paused) {
    public State {
      if (schema != 1 || profile == null || built < 0 || built > 18 || built > 0 && camp == null)
        throw new IllegalArgumentException("Unsupported or invalid PlayerAi state");
      inventory = Map.copyOf(inventory);
      if (inventory.size() > 20
          || inventory.entrySet().stream()
              .anyMatch(e -> !validItem(e.getKey()) || e.getValue() < 1 || e.getValue() > 64))
        throw new IllegalArgumentException("Invalid PlayerAi inventory");
      // Revalidate profiles deserialized by Gson, including names from older/malformed data.
      new PlayerAiProfile(profile.id(), profile.seed(), profile.name());
    }
  }

  private static final Set<String> WOODS =
      Set.of(
          "OAK",
          "BIRCH",
          "SPRUCE",
          "JUNGLE",
          "ACACIA",
          "DARK_OAK",
          "MANGROVE",
          "CHERRY",
          "PALE_OAK");

  public static boolean log(String material) {
    return material != null
        && material.endsWith("_LOG")
        && WOODS.contains(material.substring(0, material.length() - 4));
  }

  private static boolean planks(String material) {
    return material != null
        && material.endsWith("_PLANKS")
        && WOODS.contains(material.substring(0, material.length() - 7));
  }

  private static boolean validItem(String material) {
    return log(material) || planks(material) || "CRAFTING_TABLE".equals(material);
  }

  private final PlayerAiProfile profile;
  private final Actions actions;
  private final Map<String, Integer> inventory = new TreeMap<>();
  private Pos camp;
  private int built;
  private boolean paused;
  private long nextAction;
  private Stage stage = Stage.FIND_CAMP;
  private String detail = "Starting";

  public PlayerAi(State state, Actions actions) {
    this.profile = state.profile();
    this.actions = Objects.requireNonNull(actions);
    camp = state.camp();
    built = state.built();
    inventory.putAll(state.inventory());
    paused = state.paused();
  }

  public static State fresh(PlayerAiProfile profile) {
    return new State(1, profile, null, 0, Map.of(), false);
  }

  public State snapshot() {
    return new State(1, profile, camp, built, inventory, paused);
  }

  public Stage stage() {
    return stage;
  }

  public String detail() {
    return detail;
  }

  public void pause(boolean value) {
    paused = value;
    nextAction = 0;
    actions.halt();
    actions.checkpoint(snapshot());
  }

  public void tick(long now) {
    if (paused) {
      stage = Stage.PAUSED;
      detail = "Paused by administrator";
      return;
    }
    if (built == 18) {
      stage = Stage.COMPLETE;
      detail = "Starter canopy and workbench complete; ready for the next milestone";
      return;
    }
    if (now < nextAction) return;
    if (camp == null) {
      stage = Stage.FIND_CAMP;
      Outcome outcome = actions.findCamp();
      if (outcome.result() == Result.DONE) {
        camp = Objects.requireNonNull(outcome.position());
        save(now);
      } else waitFor(outcome, now);
      return;
    }
    String log = inventory.keySet().stream().filter(PlayerAi::log).findFirst().orElse(null);
    if (log != null) {
      stage = Stage.CRAFT_PLANKS;
      take(log, 1);
      inventory.merge(log.substring(0, log.length() - 4) + "_PLANKS", 4, Integer::sum);
      detail = "Crafted four planks from one log";
      save(now);
      return;
    }
    int available =
        inventory.entrySet().stream()
            .filter(e -> planks(e.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
    int required =
        StarterCamp.remainingPlanks(built) - (inventory.containsKey("CRAFTING_TABLE") ? 4 : 0);
    if (available < required) {
      stage = Stage.GATHER_WOOD;
      Outcome outcome = actions.gatherWood();
      if (outcome.result() == Result.DONE && log(outcome.material())) {
        inventory.merge(outcome.material(), 1, Integer::sum);
        detail = "Gathered " + outcome.material();
        save(now);
      } else waitFor(outcome, now);
      return;
    }
    stage = Stage.BUILD_CAMP;
    var placement = StarterCamp.blueprint(camp).get(built);
    if (placement.workbench() && !inventory.containsKey("CRAFTING_TABLE")) {
      for (int i = 0; i < 4; i++) take(firstPlanks(), 1);
      inventory.put("CRAFTING_TABLE", 1);
      detail = "Crafted workbench from four planks";
      save(now);
      return;
    }
    String material = placement.workbench() ? "CRAFTING_TABLE" : firstPlanks();
    Outcome outcome = actions.place(placement, material);
    if (outcome.result() == Result.DONE || outcome.result() == Result.SATISFIED) {
      if (outcome.result() == Result.DONE) take(material, 1);
      built++;
      detail = "Built " + built + "/18 camp blocks";
      save(now);
    } else waitFor(outcome, now);
  }

  private String firstPlanks() {
    return inventory.keySet().stream().filter(PlayerAi::planks).findFirst().orElseThrow();
  }

  private void take(String material, int count) {
    int left = inventory.getOrDefault(material, 0) - count;
    if (left < 0) throw new IllegalStateException("Missing material: " + material);
    if (left == 0) inventory.remove(material);
    else inventory.put(material, left);
  }

  private void save(long now) {
    nextAction = now + 1000;
    actions.checkpoint(snapshot());
  }

  private void waitFor(Outcome outcome, long now) {
    detail = outcome.detail();
    if (outcome.result() == Result.BLOCKED) {
      stage = Stage.BLOCKED;
      nextAction = now + 10_000;
      actions.halt();
    }
  }
}
