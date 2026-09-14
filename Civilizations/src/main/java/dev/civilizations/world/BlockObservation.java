package dev.civilizations.world;

import dev.coreai.TerrainRuleBook;
import java.util.Set;
import org.bukkit.block.Block;

/** Physical block facts captured on the owning region; model text is not a source of physics. */
public final class BlockObservation {
  private BlockObservation() {}

  private static final Set<String> DAMAGE =
      Set.of(
          "LAVA",
          "FIRE",
          "SOUL_FIRE",
          "TNT",
          "MAGMA_BLOCK",
          "CACTUS",
          "WITHER_ROSE",
          "SWEET_BERRY_BUSH",
          "POWDER_SNOW");

  public static boolean dangerous(String material, String state) {
    return DAMAGE.contains(material)
        || (material.equals("CAMPFIRE") || material.equals("SOUL_CAMPFIRE"))
            && !String.valueOf(state).contains("lit=false");
  }

  public static boolean learnedClear(TerrainRuleBook rules, String worker, Block b) {
    if (rules == null) return false;
    var facts = capture(b);
    var rule = rules.rule(worker, facts.material(), facts.state());
    if (rule == null) return false;
    try {
      TerrainRuleBook.validate(facts, "CLEARABLE");
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static TerrainRuleBook.Facts capture(Block b) {
    String type = b.getType().name(), state = b.getBlockData().getAsString();
    boolean solid = b.getType().isSolid();
    boolean container = b.getState() instanceof org.bukkit.block.Container;
    boolean fluid =
        b.isLiquid()
            || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
    boolean removable =
        !solid
            && !container
            && !b.getType().isAir()
            && (BlockRules.replaceable(b)
                || type.equals("COBWEB")
                || b.isPassable()
                    && b.getType().getHardness() >= 0
                    && b.getType().getHardness() <= 1);
    return new TerrainRuleBook.Facts(
        type,
        state,
        true,
        b.isPassable(),
        solid,
        removable,
        fluid,
        dangerous(type, state),
        container);
  }
}
